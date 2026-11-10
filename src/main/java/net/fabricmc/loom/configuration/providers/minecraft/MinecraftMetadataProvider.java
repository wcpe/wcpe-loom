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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
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
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.download.DownloadBuilder;

public final class MinecraftMetadataProvider {
	/** L2 规格层命名空间：版本清单的「目标版本条目」缓存. */
	private static final String NAMESPACE_VERSIONS_MANIFEST = "versions-manifest";

	/**
	 * 元数据类下载的连接超时.
	 *
	 * <p>取得很短：这类下载失败时只影响「能不能拿到最新的版本清单」，不影响构建能否开始（
	 * 有本地副本就降级）。默认的 2 分钟会让一次网络抖动把整个构建挂上数分钟。
	 */
	private static final Duration METADATA_CONNECT_TIMEOUT = Duration.ofSeconds(15);

	/** 元数据类下载的单次请求超时. */
	private static final Duration METADATA_REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftMetadataProvider.class);

	private final Options options;
	private final Function<String, DownloadBuilder> download;
	private final @Nullable Provider<String> metadataSource;

	private ManifestEntryLocation versionEntry;
	private MinecraftVersionMeta versionMeta;

	// 原始解析器只由 ValueSource 内部与独立下载测试使用，不把派生缓存 I/O 暴露给配置输入跟踪。
	MinecraftMetadataProvider(Options options, Function<String, DownloadBuilder> download) {
		this(options, download, null);
	}

	private MinecraftMetadataProvider(Options options, Function<String, DownloadBuilder> download, @Nullable Provider<String> metadataSource) {
		this.options = options;
		this.download = download;
		this.metadataSource = metadataSource;
	}

	public static MinecraftMetadataProvider create(ConfigContext configContext) {
		final Project project = configContext.project();
		final Options options = Options.create(resolveMinecraftVersion(project), project);
		final Provider<String> metadataSource = MinecraftMetadataValueSource.create(
				project.getProviders(), options,
				project.getGradle().getStartParameter().isOffline(), configContext.extension().manualRefreshDeps()
		);

		return new MinecraftMetadataProvider(options, configContext.extension()::download, metadataSource);
	}

	private static String resolveMinecraftVersion(Project project) {
		final DependencyInfo dependency = DependencyInfo.create(project, Constants.Configurations.MINECRAFT);
		return dependency.getDependency().getVersion();
	}

	public String getMinecraftVersion() {
		return options.minecraftVersion();
	}

	public MinecraftVersionMeta getVersionMeta() {
		if (metadataSource != null) {
			if (versionMeta == null) {
				versionMeta = LoomGradlePlugin.GSON.fromJson(metadataSource.get(), MinecraftVersionMeta.class);
			}

			return versionMeta;
		}

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

		// 元数据下载是「拿不到就让构建起不来」的强依赖，因此把超时压到「快速失败」的量级：
		// 默认的 2 分钟连接 + 10 分钟请求 × 3 次重试，在网络抖动的环境下实测单次配置要 8~9 分钟，
		// 而且最终仍然失败。压到 15s / 30s 后最坏情况在 45s 量级内落定，要么成功要么降级。
		builder = builder.connectTimeout(METADATA_CONNECT_TIMEOUT)
				.requestTimeout(METADATA_REQUEST_TIMEOUT);

		final Path cacheFile = location.cacheFile(options.userCache());

		try {
			final String versionManifest = builder.downloadString(cacheFile);
			final VersionsManifest.Version version = findVersion(versionManifest, cacheFile);

			if (version != null) {
				return new ManifestEntryLocation(location, version);
			}

			return null;
		} catch (IOException e) {
			// 下载失败且本地已有可用的旧清单时降级：元数据只用来「选侧/找版本 URL」，
			// 不是产物本身。本地副本完全够用，不该因为拉不到新版就把整个构建打死。
			//
			// 注意：必须捕 IOException —— DownloadException 继承自它而不是 RuntimeException，
			// 写成 catch (RuntimeException) 会让异常直接冒泡、降级分支永不执行。
			final ManifestEntryLocation fallback = readManifestEntryFromCache(location, cacheFile);

			if (fallback != null) {
				LOGGER.warn(
						"无法刷新 Minecraft 版本清单（{}），本次改用本地已有副本：{}",
						location.url(),
						e.getMessage()
				);
				return fallback;
			}

			// 本地也没有可用的旧副本——退回到原始失败，不掩盖真正的问题。
			throw e;
		}
	}

	/**
	 * 从已落盘的清单副本里取目标版本条目，供「下载失败时降级」使用.
	 *
	 * <p>不校验 maxAge、不联网：本地文件存在且能解析出目标版本就够。
	 */
	@Nullable
	private ManifestEntryLocation readManifestEntryFromCache(ManifestLocation location, Path cacheFile) {
		if (!Files.isRegularFile(cacheFile)) {
			return null;
		}

		try {
			final String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
			final VersionsManifest.Version version = findVersion(json, cacheFile);

			if (version == null) {
				return null;
			}

			return new ManifestEntryLocation(location, version);
		} catch (RuntimeException | IOException e) {
			LOGGER.debug("本地版本清单副本不可用：{}", cacheFile, e);
			return null;
		}
	}

	/**
	 * 从版本清单中取出目标版本，解析结果按实际读取内容的 SHA-256 身份落盘.
	 *
	 * <p>版本清单包含全部 Minecraft 版本，每次配置只需要其中一条。派生缓存以已经读取的 JSON
	 * 内容为键，不再重新 stat 文件：HTTP 304 更新时间戳不会产生新条目，并发替换文件也不会把
	 * 旧 JSON 的结果错误地写入新一代文件的身份下。配置输入隔离由外层 ValueSource 承担。
	 *
	 * @param manifestJson 清单的原始 JSON
	 * @param manifestFile 清单的落盘位置，用于损坏缓存的诊断
	 * @return 目标版本条目；清单中不存在该版本时为空
	 */
	private @Nullable Version findVersion(String manifestJson, Path manifestFile) {
		final SpecStore store = new SpecStore(options.userCache());
		final String identity = Checksum.of(manifestJson).sha256().hex();
		final var cached = store.load(NAMESPACE_VERSIONS_MANIFEST, identity, options.minecraftVersion());

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

		final VersionsManifest manifest = LoomGradlePlugin.GSON.fromJson(manifestJson, VersionsManifest.class);
		final VersionsManifest.Version version = manifest.getVersion(options.minecraftVersion());

		store.store(NAMESPACE_VERSIONS_MANIFEST, identity, options.minecraftVersion(),
				version != null ? LoomGradlePlugin.GSON.toJson(version) : "");

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
