/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.work.DisableCachingByDefault;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.assets.AssetIndex;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.MirrorUtil;
import net.fabricmc.loom.util.download.DownloadExecutor;
import net.fabricmc.loom.util.download.DownloadFactory;
import net.fabricmc.loom.util.download.GradleDownloadProgressListener;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.ProgressGroup;

// TODO: Reintroduce the progress bar.
@DisableCachingByDefault
public abstract class DownloadAssetsTask extends AbstractLoomTask {
	@Input
	public abstract Property<String> getAssetsHash();

	@Input
	public abstract Property<Integer> getDownloadThreads();

	@Input
	public abstract Property<String> getMinecraftVersion();

	@Input
	public abstract Property<String> getResourcesBaseUrl();

	@Input
	protected abstract Property<String> getAssetsIndexJson();

	@OutputDirectory
	public abstract RegularFileProperty getAssetsDirectory();

	@OutputDirectory
	public abstract RegularFileProperty getLegacyResourcesDirectory();

	@Inject
	protected abstract ProgressLoggerFactory getProgressLoggerFactory();

	@Nested
	protected abstract DownloadFactory getDownloadFactory();

	// 共享缓存锁服务：跨进程串行化同一个 assets 目录的写入
	private final Provider<LoomCacheService> cacheService;

	@Inject
	public DownloadAssetsTask() {
		final MinecraftVersionMeta versionInfo = getExtension().getMinecraftProvider().getVersionInfo();
		final File assetsDir = new File(getExtension().getFiles().getUserCache(), "assets");

		getAssetsDirectory().set(assetsDir);
		getAssetsHash().set(versionInfo.assetIndex().sha1());
		getDownloadThreads().convention(Math.min(Runtime.getRuntime().availableProcessors(), 10));
		getMinecraftVersion().set(versionInfo.id());
		getMinecraftVersion().finalizeValue();

		if (versionInfo.assets().equals("legacy")) {
			getLegacyResourcesDirectory().set(new File(assetsDir, "/legacy/" + versionInfo.id()));
		} else {
			// pre-1.6 resources
			RunConfiguration client = getExtension().getRunConfigs().findByName("client");
			File runDir = client != null ? client.getRunDirectory().getAsFile().get() : getProject().file("run");
			getLegacyResourcesDirectory().set(new File(runDir, "resources"));
		}

		getResourcesBaseUrl().set(MirrorUtil.getResourcesBase(getProject()));
		getResourcesBaseUrl().finalizeValue();

		getAssetsIndexJson().set(LoomGradlePlugin.GSON.toJson(getExtension().getMinecraftProvider().getVersionInfo().assetIndex()));

		getAssetsHash().finalizeValue();
		getAssetsDirectory().finalizeValueOnRead();
		getLegacyResourcesDirectory().finalizeValueOnRead();

		cacheService = LoomCacheService.get(getProject());
		usesService(cacheService);
	}

	@TaskAction
	public void downloadAssets() throws IOException {
		// ~/.gradle/caches/fabric-loom/assets 是跨项目、跨工作树共享的目录：用户同时开 10+ 个项目时，
		// 各自的第一轮 runClient 会把同一批对象写同一处，改造前完全没有任何互斥。
		// 这里给「整个 assets 目录的写入」加一把跨进程锁，key 与仓库其它调用点形式一致（`<领域>:<版本>`）。
		//
		// 锁目录（<userCache>/.locks）由 assets 目录的父目录推出，与 GenerateSourcesTask 取法一致：
		// 执行期不碰 extension，否则配置缓存下会因访问 Project 而失败。
		final Path assetsDir = getAssetsDirectory().get().getAsFile().toPath();
		final Path lockRoot = assetsDir.getParent().resolve(Constants.Cache.LOCKS_DIR);
		final String lockKey = "assets:" + getMinecraftVersion().get();
		final Callable<Void> download = () -> {
			downloadAssetsUnlocked();
			return null;
		};

		// runExclusiveChecking 形态：先判就绪再取锁。
		//
		// 就绪判据是「本版本的索引 json 存在」（一次 stat）。它由 downloadAssetsUnlocked 的第一步写出，
		// 带 sha1 校验与原子落位，因此存在即意味着「这个版本已经跑过一次下载」。
		// 不把判据做成「逐项 stat 全部对象」：完整索引有上千个对象，预扫会让热路径比改造前更慢；
		// 而热路径本来就要逐项做 sha1 校验（Download.requiresDownload → isHashValid），缺项自然会被补上。
		//
		// 两个分支跑同一段逻辑是故意的：锁只负责串行化「N 个构建同时冷启动同一版本」这一段，
		// 下载本身幂等（每个对象均 sha1 校验 + 原子落位），已就绪时在锁外跑与改造前完全等价。
		try {
			cacheService.get().runExclusiveChecking(lockRoot, lockKey, LoomCacheService.defaultTimeout(),
					this::isAssetIndexPresent, download, download);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to download assets for " + getMinecraftVersion().get(), e);
		}
	}

	// 只读就绪判定，可在锁外调用：一次 stat，不扫目录、不写任何东西
	private boolean isAssetIndexPresent() {
		final MinecraftVersionMeta.AssetIndex assetIndex = LoomGradlePlugin.GSON.fromJson(getAssetsIndexJson().get(), MinecraftVersionMeta.AssetIndex.class);
		return Files.exists(getAssetIndexFile(assetIndex));
	}

	private void downloadAssetsUnlocked() throws IOException {
		final AssetIndex assetIndex = getAssetIndex();

		try (ProgressGroup progressGroup = new ProgressGroup("Download Assets", getProgressLoggerFactory());
				DownloadExecutor executor = new DownloadExecutor(getDownloadThreads().get())) {
			for (AssetIndex.Object object : assetIndex.getObjects()) {
				final String sha1 = object.hash();
				final String url = getResourcesBaseUrl().get() + sha1.substring(0, 2) + "/" + sha1;

				getDownloadFactory()
						.download(url)
						.sha1(sha1)
						.progress(new GradleDownloadProgressListener(object.name(), progressGroup::createProgressLogger))
						.downloadPathAsync(getAssetsPath(object, assetIndex), executor);
			}
		}
	}

	private AssetIndex getAssetIndex() throws IOException {
		final MinecraftVersionMeta.AssetIndex assetIndex = LoomGradlePlugin.GSON.fromJson(getAssetsIndexJson().get(), MinecraftVersionMeta.AssetIndex.class);

		final String json = getDownloadFactory().download(assetIndex.url())
				.sha1(assetIndex.sha1())
				.downloadString(getAssetIndexFile(assetIndex));

		return LoomGradlePlugin.GSON.fromJson(json, AssetIndex.class);
	}

	// 本版本的资产索引文件：判定「这个版本的 assets 是否已经拉过」就看它
	// （它由 getAssetIndex 带 sha1 校验、原子落位写出，存在即意味着这个版本至少完整跑过一次）
	private Path getAssetIndexFile(MinecraftVersionMeta.AssetIndex assetIndex) {
		final String name = "indexes" + File.separator + assetIndex.fabricId(getMinecraftVersion().get()) + ".json";
		return new File(getAssetsDirectory().get().getAsFile(), name).toPath();
	}

	private Path getAssetsPath(AssetIndex.Object object, AssetIndex index) {
		if (index.mapToResources() || index.virtual()) {
			return new File(getLegacyResourcesDirectory().get().getAsFile(), object.path()).toPath();
		}

		final String filename = "objects" + File.separator + object.hash().substring(0, 2) + File.separator + object.hash();
		return new File(getAssetsDirectory().get().getAsFile(), filename).toPath();
	}
}
