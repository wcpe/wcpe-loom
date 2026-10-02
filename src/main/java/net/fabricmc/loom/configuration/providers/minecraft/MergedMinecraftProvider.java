/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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
import java.util.Map;
import java.util.Objects;

import org.gradle.api.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.pipeline.MergeMinecraftJarsTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.JarReusability;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public class MergedMinecraftProvider extends MinecraftProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(MergedMinecraftProvider.class);
	private Path minecraftMergedJar;

	public MergedMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		super(metadataProvider, configContext);

		if (isLegacyVersion()) {
			throw new RuntimeException("something has gone wrong - merged jar configuration selected but Minecraft " + metadataProvider.getMinecraftVersion() + " does not allow merging the obfuscated jars - the legacy-merged jar configuration should have been selected!");
		}
	}

	@Override
	protected void initFiles() {
		super.initFiles();
		minecraftMergedJar = path("minecraft-merged.jar");
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftMergedJar);
	}

	@Override
	public MappingsNamespace getOfficialNamespace() {
		return MappingsNamespace.OFFICIAL;
	}

	@Override
	public void provide() throws Exception {
		super.provide();

		if (!provideServer() || !provideClient()) {
			throw new UnsupportedOperationException("This version does not provide both the client and server jars - please select the client-only or server-only jar configuration!");
		}

		if (isTaskProduction()) {
			// 产物已由 mergeMinecraftJars 任务承担（在 super.provide() 里登记），这里不再写任何产物
			return;
		}

		// 无锁快路径：merged jar 已就绪（内容级判据，见 JarReusability.isReusable）且未要求刷新时不获取文件锁。
		// 不能只判存在：该产物落在跨 daemon／跨工作树共享的 <userCache>/<mcVersion> 下，被中断就地写会留下
		// 0 字节或截断的 merged jar，PR #8 移除「残留锁 → 全量重建」兜底后，存在性判定会把它永久复用。
		if (!JarReusability.isReusable(minecraftMergedJar) || getExtension().refreshDeps()) {
			final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
			final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

			cacheService.runExclusive(lockRoot, cacheKey(), LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：可能已被他人在等锁期间合并完成
				if (!JarReusability.isReusable(minecraftMergedJar) || getExtension().refreshDeps()) {
					try {
						mergeJars();
					} catch (Throwable e) {
						// 失败路径不删除共享产物：client/server 输入 jar 与合并产物都在跨进程共享的 <userCache>/<mcVersion> 下，
						// 删掉会破坏其它进程（或其它工作树）正在读的文件，并引发无谓的重新下载与重建。
						// 合并的中间结果只存在于同目录临时文件里，由 AtomicFiles.publish 在失败时清理。
						getProject().getLogger().error("合并 JAR 失败！已保留原有产物，请重新运行命令以重试。", e);
						throw e;
					}
				}

				return null;
			});
		}
	}

	/**
	 * 注册合并任务：产物生产交给执行期任务，产物路径与配置期一致.
	 *
	 * <p>server 侧输入按配置期同一判据选好：该版本使用 bootstrap jar 时取抽取产物，否则取下载下来的
	 * server jar。判据来自 L2 规格缓存（不打开 jar），因此这一步不需要产物已存在。
	 */
	@Override
	protected void registerProviderTasks(Map<Path, Producer> producers) {
		final Project project = getProject();
		final Path clientJar = normalize(getMinecraftClientJar().toPath());
		final Path serverJar = normalize(getServerJarToMerge().toPath());
		final Path mergedJar = normalize(minecraftMergedJar);

		producers.put(mergedJar, RemapMinecraftTaskRegistry.claim(project, mergedJar, Map.of(
				"stage", "merge-jars",
				"clientJar", clientJar.toString(),
				"serverJar", serverJar.toString()
		), () -> project.getTasks().register("mergeMinecraftJars", MergeMinecraftJarsTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Merges the Minecraft client and server jar for %s".formatted(minecraftVersion()));
			// 产物位置与配置期一致：消费侧（重映射任务、genSources）按这个路径找 merged jar
			task.getClientJar().set(clientJar.toFile());
			task.getServerJar().set(serverJar.toFile());
			task.getMergedJar().set(mergedJar.toFile());
			// 两个输入都由 vanilla 链的任务产出：按任务路径建依赖，冷缓存下才不会在输入落位前开跑
			dependOn(task, producerOf(producers, clientJar));
			dependOn(task, producerOf(producers, serverJar));
		})));
	}

	/** {@return 本次该拿哪个 server jar 参与合并} 与配置期 {@link #mergeJars()} 同一判据. */
	private File getServerJarToMerge() {
		if (getServerBundleMetadata() != null) {
			// bootstrap 版本：真身在抽取产物里
			return getMinecraftExtractedServerJar();
		}

		return getMinecraftServerJar();
	}

	protected void mergeJars() throws IOException {
		File minecraftClientJar = getMinecraftClientJar();
		final File serverJar = getServerJarToMerge();
		final File clientJar = minecraftClientJar;
		// 原子发布：合并写到同目录唯一临时 jar，完整后再原子 move 到 minecraftMergedJar
		AtomicFiles.publish(minecraftMergedJar, tmpJar -> mergeJars(clientJar, serverJar, tmpJar.toFile()));
	}

	public static void mergeJars(File clientJar, File serverJar, File mergedJar) throws IOException {
		LOGGER.info(":merging jars");

		Objects.requireNonNull(clientJar, "Cannot merge null client jar?");
		Objects.requireNonNull(serverJar, "Cannot merge null server jar?");

		try (var jarMerger = new MinecraftJarMerger(clientJar, serverJar, mergedJar)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	public Path getMergedJar() {
		return minecraftMergedJar;
	}
}
