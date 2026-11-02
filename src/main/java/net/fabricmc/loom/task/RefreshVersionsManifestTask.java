/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.ManifestLocations.ManifestLocation;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.download.DownloadBuilder;

/**
 * 强制刷新 Minecraft 版本清单缓存.
 *
 * <p>配置期只在清单超过 {@code maxAge}（一天）时才尝试联网，且失败时降级用本地副本
 * （见 {@code MinecraftMetadataProvider}）。因此拉取失败不会阻塞构建，代价是清单可能长期停在旧版本。
 * 本任务提供显式入口：拿得到就刷新，拿不到就明确报错，而不是默默继续用旧的。
 *
 * <p>典型用法：
 * <ul>
 *     <li>刚发布新 MC 版本、想让它出现在版本清单里；</li>
 *     <li>怀疑本地清单太旧导致选错了客户端/服务端。</li>
 * </ul>
 *
 * <p>不参与任何其他任务的依赖图，只由人手动调用。
 */
@DisableCachingByDefault
public abstract class RefreshVersionsManifestTask extends AbstractLoomTask {
	/**
	 * 刷新使用的连接超时（秒）.
	 *
	 * <p>比配置期宽容：这是人主动等的一次性操作，不需要「快速失败」。
	 */
	@Input
	public abstract Property<Integer> getConnectTimeoutSeconds();

	public RefreshVersionsManifestTask() {
		setGroup(Constants.TaskGroup.FABRIC);
		setDescription("强制刷新 Minecraft 版本清单缓存（配置期拉不到时会降级用旧副本，本任务则必须成功）");
		getConnectTimeoutSeconds().convention(30);
	}

	@TaskAction
	public void refresh() throws IOException {
		final LoomGradleExtension extension = getExtension();
		final Path userCache = extension.getFiles().getUserCache().toPath();

		int refreshed = 0;
		int failed = 0;

		for (ManifestLocation location : extension.getVersionsManifests()) {
			final Path cacheFile = location.cacheFile(userCache);

			try {
				final DownloadBuilder builder = extension.download(location.url())
						.forceDownload()
						.connectTimeout(Duration.ofSeconds(getConnectTimeoutSeconds().get()));

				final String json = builder.downloadString(cacheFile);

				getLogger().lifecycle("已刷新版本清单 [{}] -> {}（{} 字符）", location.name(), cacheFile, json.length());
				refreshed++;
			} catch (RuntimeException e) {
				failed++;
				getLogger().error("刷新版本清单失败 [{}] {}：{}", location.name(), location.url(), e.getMessage());
			}
		}

		if (refreshed == 0 && failed > 0) {
			throw new IOException("所有版本清单都刷新失败（" + failed + " 个），请检查网络或代理设置");
		}

		if (failed > 0) {
			getLogger().warn("有 {} 个版本清单刷新失败，其余 {} 个已更新", failed, refreshed);
		}
	}
}
