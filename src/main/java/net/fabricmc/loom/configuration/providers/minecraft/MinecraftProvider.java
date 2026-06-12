/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2025 FabricMC
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.configuration.providers.minecraft.verify.MinecraftJarVerification;
import net.fabricmc.loom.configuration.providers.minecraft.verify.SignatureVerificationFailure;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.download.DownloadExecutor;
import net.fabricmc.loom.util.download.GradleDownloadProgressListener;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.ProgressGroup;

public abstract class MinecraftProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftProvider.class);

	private final MinecraftMetadataProvider metadataProvider;

	private File minecraftClientJar;
	// Note this will be the boostrap jar starting with 21w39a
	private File minecraftServerJar;
	// The extracted server jar from the boostrap, only exists in >=21w39a
	private File minecraftExtractedServerJar;
	@Nullable
	private BundleMetadata serverBundleMetadata;

	private final ConfigContext configContext;

	public MinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		this.metadataProvider = metadataProvider;
		this.configContext = configContext;
	}

	protected boolean provideClient() {
		return true;
	}

	protected boolean provideServer() {
		return true;
	}

	public void provide() throws Exception {
		// 内存配置：无论缓存冷热都必须执行
		initFiles();

		verifyJavaVersion();

		// 无锁快路径：未要求刷新且所有产物已就绪时，不获取任何文件锁
		if (jarsRequireProduction()) {
			// 产文件部分（下载/抽取/校验）用 per-key 锁保护，按 mcVersion 串行、不同版本可并行
			final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
			final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

			cacheService.runExclusive(lockRoot, downloadLockKey(), LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：等锁期间可能已被其它进程/线程下载完成，避免重复下载/抽取
				if (!jarsRequireProduction()) {
					return null;
				}

				boolean didDownload = downloadJars();

				if (provideServer()) {
					// 锁内读取 bundle 元数据用于决定是否抽取（extractBundledServerJar 依赖该字段）
					serverBundleMetadata = BundleMetadata.fromJar(minecraftServerJar.toPath());

					if (serverBundleMetadata != null) {
						extractBundledServerJar();
					}
				}

				if (didDownload) {
					verifyJars();
				}

				return null;
			});
		}

		// 暖路径补设字段：上面锁内可能整段跳过（缓存就绪时未进入生产），但 server jar 必已就绪，
		// 故在锁外对已存在的 server jar 再读取一次，保证暖缓存下 serverBundleMetadata 也正确。
		if (provideServer() && serverBundleMetadata == null) {
			serverBundleMetadata = BundleMetadata.fromJar(minecraftServerJar.toPath());
		}

		// 内存配置：libraryProvider 每次都必须执行
		final MinecraftLibraryProvider libraryProvider = new MinecraftLibraryProvider(this, configContext.project());
		libraryProvider.provide();
	}

	// 下载/抽取产物的跨进程锁 key：始终按版本，确保同一 mcVersion 的所有 jar 配置共享同一把下载锁、只下载一次。
	// 不可用会被子类覆写的 cacheKey()，否则不同 jar 配置（如 server-only/client-only single jar）会用不同 key 并发下载同一批共享 jar。
	private String downloadLockKey() {
		return "minecraft:" + minecraftVersion();
	}

	// 跨进程互斥 key：用于子类自身产物（merge/split/env-only jar）的生产锁，子类可覆写以区分不同产物
	protected String cacheKey() {
		return "minecraft:" + minecraftVersion();
	}

	// 无锁存在性检查：判断下载/抽取产物是否需要生产。返回 false 即所有产物已就绪，可走无锁快路径。
	private boolean jarsRequireProduction() {
		if (getExtension().refreshDeps()) {
			return true;
		}

		if (provideClient() && !minecraftClientJar.exists()) {
			return true;
		}

		if (provideServer()) {
			if (!minecraftServerJar.exists()) {
				return true;
			}

			// 该版本若使用 bundler，则抽取后的 server jar 也必须存在。
			// serverBundleMetadata 此时尚未读取，故直接读已下载的 server jar 判断是否为 bundler。
			try {
				if (BundleMetadata.fromJar(minecraftServerJar.toPath()) != null && !minecraftExtractedServerJar.exists()) {
					return true;
				}
			} catch (IOException e) {
				// 读取失败视为需要重新生产
				return true;
			}
		}

		return false;
	}

	private void verifyJavaVersion() {
		if (configContext.extension().disableObfuscation()) {
			return;
		}

		// Verify that the current Gradle Java version is the same or higher than the required Java version for this Minecraft version.
		// This is required so the remappers can retrive the correct context of Java classes when remapping.

		final MinecraftVersionMeta.JavaVersion javaVersion = getVersionInfo().javaVersion();

		if (javaVersion != null) {
			final int requiredMajorJavaVersion = getVersionInfo().javaVersion().majorVersion();
			final JavaVersion requiredJavaVersion = JavaVersion.toVersion(requiredMajorJavaVersion);

			if (!JavaVersion.current().isCompatibleWith(requiredJavaVersion)) {
				throw new IllegalStateException("Minecraft " + minecraftVersion() + " requires Java " + requiredJavaVersion + " but Gradle is using " + JavaVersion.current());
			}
		}
	}

	protected void initFiles() {
		if (provideClient()) {
			minecraftClientJar = file("minecraft-client.jar");
		}

		if (provideServer()) {
			minecraftServerJar = file("minecraft-server.jar");
			minecraftExtractedServerJar = file("minecraft-extracted_server.jar");
		}
	}

	private void verifyJars() throws IOException, SignatureVerificationFailure {
		if (!GradleUtils.getBooleanProperty(getProject(), Constants.Properties.ENABLE_MINECRAFT_VERIFICATION)) {
			LOGGER.info("Skipping Minecraft jar verification!");
			return;
		}

		LOGGER.info("Verifying Minecraft jars");

		MinecraftJarVerification verification = getProject().getObjects().newInstance(MinecraftJarVerification.class, minecraftVersion());

		if (provideClient()) {
			verification.verifyClientJar(minecraftClientJar.toPath());
		}

		if (provideServer()) {
			if (serverBundleMetadata == null) {
				verification.verifyServerJar(minecraftServerJar.toPath());
			} else {
				verification.verifyServerJar(getMinecraftExtractedServerJar().toPath());
			}
		}

		LOGGER.info("Jar verification complete");
	}

	// Returns true when a file was downloaded
	private boolean downloadJars() throws IOException {
		AtomicBoolean didDownload = new AtomicBoolean(false);

		try (ProgressGroup progressGroup = new ProgressGroup(getProject(), "Download Minecraft jars");
				DownloadExecutor executor = new DownloadExecutor(2)) {
			if (provideClient()) {
				final MinecraftVersionMeta.Download client = getVersionInfo().download("client");
				getExtension().download(client.url())
						.sha1(client.sha1())
						.progress(new GradleDownloadProgressListener("Minecraft client", progressGroup::createProgressLogger))
						.downloadPathAsync(minecraftClientJar.toPath(), executor)
						.thenAccept(downloadResult -> {
							if (downloadResult.didDownload()) {
								didDownload.set(true);
							}
						});
			}

			if (provideServer()) {
				final MinecraftVersionMeta.Download server = getVersionInfo().download("server");
				getExtension().download(server.url())
						.sha1(server.sha1())
						.progress(new GradleDownloadProgressListener("Minecraft server", progressGroup::createProgressLogger))
						.downloadPathAsync(minecraftServerJar.toPath(), executor)
						.thenAccept(downloadResult -> {
							if (downloadResult.didDownload()) {
								didDownload.set(true);
							}
						});
			}
		}

		if (didDownload.get()) {
			LOGGER.info("Downloaded new Minecraft jars");
			return true;
		}

		LOGGER.info("Using cached Minecraft jars");
		return false;
	}

	private void extractBundledServerJar() throws IOException {
		Check.require(provideServer(), "Not configured to provide server jar");
		Objects.requireNonNull(getServerBundleMetadata(), "Cannot bundled mc jar from none bundled server jar");

		LOGGER.info(":Extracting server jar from bootstrap");

		if (getServerBundleMetadata().versions().size() != 1) {
			throw new UnsupportedOperationException("Expected only 1 version in META-INF/versions.list, but got %d".formatted(getServerBundleMetadata().versions().size()));
		}

		getServerBundleMetadata().versions().get(0).unpackEntry(minecraftServerJar.toPath(), getMinecraftExtractedServerJar().toPath(), configContext.project());
	}

	public File workingDir() {
		return minecraftWorkingDirectory(configContext.project(), minecraftVersion());
	}

	public File dir(String path) {
		File dir = file(path);
		dir.mkdirs();
		return dir;
	}

	public File file(String path) {
		return new File(workingDir(), path);
	}

	public Path path(String path) {
		return file(path).toPath();
	}

	public File getMinecraftClientJar() {
		Check.require(provideClient(), "Not configured to provide client jar");
		return minecraftClientJar;
	}

	// May be null on older versions
	@Nullable
	public File getMinecraftExtractedServerJar() {
		Check.require(provideServer(), "Not configured to provide server jar");
		return minecraftExtractedServerJar;
	}

	// This may be the server bundler jar on newer versions prob not what you want.
	public File getMinecraftServerJar() {
		Check.require(provideServer(), "Not configured to provide server jar");
		return minecraftServerJar;
	}

	public String minecraftVersion() {
		return Objects.requireNonNull(metadataProvider, "Metadata provider not setup").getMinecraftVersion();
	}

	public MinecraftVersionMeta getVersionInfo() {
		return Objects.requireNonNull(metadataProvider, "Metadata provider not setup").getVersionMeta();
	}

	/**
	 * @return true if the minecraft version is older than 1.3.
	 */
	public boolean isLegacyVersion() {
		return getVersionInfo().isLegacyVersion();
	}

	/**
	 * Returns true if the minecraft version is between Beta 1.0 (inclusive) and 1.3 (exclusive),
	 * which splits the {@code official} mapping namespace into env-specific variants.
	 */
	public boolean isLegacySplitOfficialNamespaceVersion() {
		return getVersionInfo().isLegacySplitOfficialNamespaceVersion();
	}

	@Nullable
	public BundleMetadata getServerBundleMetadata() {
		return serverBundleMetadata;
	}

	public abstract List<Path> getMinecraftJars();

	public abstract MappingsNamespace getOfficialNamespace();

	protected Project getProject() {
		return configContext.project();
	}

	protected LoomGradleExtension getExtension() {
		return configContext.extension();
	}

	public boolean refreshDeps() {
		return getExtension().refreshDeps();
	}

	public static File minecraftWorkingDirectory(Project project, String version) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		File workingDir = new File(extension.getFiles().getUserCache(), version);
		workingDir.mkdirs();
		return workingDir;
	}
}
