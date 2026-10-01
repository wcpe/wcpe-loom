/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers.minecraft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.minecraft.ManifestLocations.ManifestLocation;
import net.fabricmc.loom.configuration.providers.minecraft.VersionsManifest.Version;
import net.fabricmc.loom.spec.SpecStore;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.download.DownloadBuilder;

public final class MinecraftMetadataProvider {
	/** L2 规格层命名空间：版本清单的「目标版本条目」缓存. */
	private static final String NAMESPACE_VERSIONS_MANIFEST = "versions-manifest";

	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftMetadataProvider.class);

	private final Options options;
	private final Function<String, DownloadBuilder> download;

	private ManifestEntryLocation versionEntry;
	private MinecraftVersionMeta versionMeta;

	private MinecraftMetadataProvider(Options options, Function<String, DownloadBuilder> download) {
		this.options = options;
		this.download = download;
	}

	public static MinecraftMetadataProvider create(ConfigContext configContext) {
		final String minecraftVersion = resolveMinecraftVersion(configContext.project());

		return new MinecraftMetadataProvider(
				MinecraftMetadataProvider.Options.create(
						minecraftVersion,
						configContext.project()
				),
				configContext.extension()::download
		);
	}

	private static String resolveMinecraftVersion(Project project) {
		final DependencyInfo dependency = DependencyInfo.create(project, Constants.Configurations.MINECRAFT);
		return dependency.getDependency().getVersion();
	}

	public String getMinecraftVersion() {
		return options.minecraftVersion();
	}

	public MinecraftVersionMeta getVersionMeta() {
		try {
			if (versionEntry == null) {
				versionEntry = getVersionEntry();
			}

			if (versionMeta == null) {
				versionMeta = readVersionMeta();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e.getMessage(), e);
		}

		return versionMeta;
	}

	public boolean isUnobfuscated() {
		return getVersionMeta().isVersionOrNewer(Constants.RELEASE_TIME_1_21_11_UNOBFUSCATED_SNAPSHOTS)
				&& !getVersionMeta().downloads().containsKey("client_mappings");
	}

	private ManifestEntryLocation getVersionEntry() throws IOException {
		// Custom URL always takes priority
		if (options.customManifestUrl() != null) {
			VersionsManifest.Version customVersion = new VersionsManifest.Version(
					options.minecraftVersion(),
					options.customManifestUrl()
			);
			return new ManifestEntryLocation(null, customVersion);
		}

		final List<ManifestEntrySupplier> suppliers = new ArrayList<>();

		// First try finding the version with caching
		for (ManifestLocation location : options.versionsManifests()) {
			suppliers.add(() -> getManifestEntry(location, false));
		}

		// Then force download the manifest to find the version
		for (ManifestLocation location : options.versionsManifests()) {
			suppliers.add(() -> getManifestEntry(location, true));
		}

		for (ManifestEntrySupplier supplier : suppliers) {
			final ManifestEntryLocation version = supplier.get();

			if (version != null) {
				return version;
			}
		}

		throw new RuntimeException("Failed to find minecraft version: " + options.minecraftVersion());
	}

	@Nullable
	private ManifestEntryLocation getManifestEntry(ManifestLocation location, boolean forceDownload) throws IOException {
		DownloadBuilder builder = download.apply(location.url());

		if (forceDownload) {
			builder = builder.forceDownload();
		} else {
			builder = builder.defaultCache();
		}

		final Path cacheFile = location.cacheFile(options.userCache());
		final String versionManifest = builder.downloadString(cacheFile);
		final VersionsManifest.Version version = findVersion(versionManifest, cacheFile);

		if (version != null) {
			return new ManifestEntryLocation(location, version);
		}

		return null;
	}

	/**
	 * 从版本清单中取出目标版本，解析结果按清单文件的内容身份落盘.
	 *
	 * <p>版本清单是**全部** Minecraft 版本的清单（数百 KB ～ MB），而每次配置只需要其中一条。
	 * 逐次全文 GSON 解析是不必要的重复：清单内容一旦下载完成就不再变化，因此把「目标版本在这一代
	 * 清单中的条目」缓存下来，后续配置只解析这一条。
	 *
	 * <p>缓存键含清单文件的内容身份（大小 + mtime），清单被刷新（含 {@code forceDownload}）后
	 * 身份变化、自然失效，不会读到过期条目。缓存只存派生数据，丢失仅意味着多解析一次。
	 *
	 * @param manifestJson 清单的原始 JSON（已确保为最新一代）
	 * @param manifestFile 清单的落盘位置，用于取内容身份
	 * @return 目标版本条目；清单中不存在该版本时为空
	 */
	private @Nullable Version findVersion(String manifestJson, Path manifestFile) {
		final SpecStore store = new SpecStore(options.userCache());
		final var identity = SpecStore.inputIdentity(manifestFile);

		if (identity.isPresent()) {
			final var cached = store.load(NAMESPACE_VERSIONS_MANIFEST, identity.get(), options.minecraftVersion());

			if (cached.isPresent()) {
				// 空内容表示「这一代清单里确实没有该版本」，是已解析过的结论，不是缓存缺失
				if (cached.get().isEmpty()) {
					return null;
				}

				try {
					return LoomGradlePlugin.GSON.fromJson(cached.get(), Version.class);
				} catch (RuntimeException e) {
					// 缓存条目损坏：退回全文解析，本轮顺带把它覆盖成正确内容
					LOGGER.debug("Corrupt spec cache entry for manifest {}, re-parsing", manifestFile, e);
				}
			}
		}

		final VersionsManifest manifest = LoomGradlePlugin.GSON.fromJson(manifestJson, VersionsManifest.class);
		final VersionsManifest.Version version = manifest.getVersion(options.minecraftVersion());

		identity.ifPresent(id -> store.store(NAMESPACE_VERSIONS_MANIFEST, id, options.minecraftVersion(),
				version != null ? LoomGradlePlugin.GSON.toJson(version) : ""));

		return version;
	}

	private MinecraftVersionMeta readVersionMeta() throws IOException {
		final DownloadBuilder builder = download.apply(versionEntry.entry.url());

		if (versionEntry.entry.sha1() != null) {
			builder.sha1(versionEntry.entry.sha1());
		} else {
			builder.defaultCache();
		}

		final String fileName = getVersionMetaFileName();
		final Path cacheFile = options.workingDir().resolve(fileName);
		final String json = builder.downloadString(cacheFile);
		return LoomGradlePlugin.GSON.fromJson(json, MinecraftVersionMeta.class);
	}

	private String getVersionMetaFileName() {
		// custom version metadata
		if (versionEntry.manifest == null) {
			return "minecraft_info_" + Integer.toHexString(versionEntry.entry.url().hashCode()) + ".json";
		}

		// metadata url taken from versions manifest
		return versionEntry.manifest.name() + "_minecraft_info.json";
	}

	public record Options(String minecraftVersion,
					ManifestLocations versionsManifests,
					@Nullable String customManifestUrl,
					Path userCache,
					Path workingDir) {
		public static Options create(String minecraftVersion, Project project) {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);
			final Path userCache = extension.getFiles().getUserCache().toPath();
			final Path workingDir = MinecraftProvider.minecraftWorkingDirectory(project, minecraftVersion).toPath();

			final ManifestLocations manifestLocations = extension.getVersionsManifests();
			final Property<String> customMetaUrl = extension.getCustomMinecraftMetadata();

			return new Options(
					minecraftVersion,
					manifestLocations,
					customMetaUrl.getOrNull(),
					userCache,
					workingDir
			);
		}
	}

	@FunctionalInterface
	private interface ManifestEntrySupplier {
		ManifestEntryLocation get() throws IOException;
	}

	private record ManifestEntryLocation(ManifestLocation manifest, VersionsManifest.Version entry) {
	}
}
