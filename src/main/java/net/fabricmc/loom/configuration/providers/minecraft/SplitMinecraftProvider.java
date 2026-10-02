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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.gradle.api.Project;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.pipeline.SplitMinecraftJarsTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.JarReusability;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public final class SplitMinecraftProvider extends MinecraftProvider {
	private Path minecraftClientOnlyJar;
	private Path minecraftCommonJar;

	public SplitMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		super(metadataProvider, configContext);
	}

	@Override
	protected void initFiles() {
		super.initFiles();

		minecraftClientOnlyJar = path("minecraft-client-only.jar");
		minecraftCommonJar = path("minecraft-common.jar");
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftClientOnlyJar, minecraftCommonJar);
	}

	@Override
	public MappingsNamespace getOfficialNamespace() {
		return MappingsNamespace.OFFICIAL;
	}

	@Override
	public void provide() throws Exception {
		super.provide();

		if (isTaskProduction()) {
			// 产物已由 splitMinecraftJars 任务承担（在 super.provide() 里登记），这里不再写任何产物
			return;
		}

		// 无锁快路径：拆分产物已就绪（内容级判据，见 JarReusability.isReusable）且未要求刷新时不获取文件锁。
		// 不能只判存在：两件产物都在跨 daemon／跨工作树共享的 <userCache>/<mcVersion> 下，被中断的就地写会留下
		// 0 字节或截断的 jar；PR #8 移除「残留锁 → 全量重建」兜底后，存在性判定会把它永久复用。
		boolean requiresRefresh = getExtension().refreshDeps() || !JarReusability.isReusable(minecraftClientOnlyJar)
				|| !JarReusability.isReusable(minecraftCommonJar);

		if (!requiresRefresh) {
			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
		final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, cacheKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：可能已被他人在等锁期间拆分完成
			if (!getExtension().refreshDeps() && JarReusability.isReusable(minecraftClientOnlyJar)
					&& JarReusability.isReusable(minecraftCommonJar)) {
				return null;
			}

			requireBundledServerJar();

			try {
				splitJars(getMinecraftClientJar().toPath(), getMinecraftExtractedServerJar().toPath(), minecraftClientOnlyJar, minecraftCommonJar);
			} catch (Exception e) {
				// 失败路径不删除共享产物：两个拆分产物都在跨进程共享的 <userCache>/<mcVersion> 下，
				// 删掉会破坏其它进程（或其它工作树）正在读的文件，并引发无谓的重建。
				// 本次未完成的中间结果只存在于临时文件里，由 splitJars 自行清理。
				throw new RuntimeException("Failed to split minecraft", e);
			}

			return null;
		});
	}

	/**
	 * 注册拆分任务：产物生产交给执行期任务，产物路径与配置期一致.
	 *
	 * <p>「是否可以拆分」的判据与配置期逐字一致（同一份 L2 元数据），且仍在配置期给出**明确的失败**，
	 * 而不是把错误推迟到执行期：这类「版本不支持所选 jar 形态」是配置错误，越早失败越好，
	 * 判据也只依赖不打开 jar 的元数据，因此不需要先有产物。
	 *
	 * <p>两个产物由同一个任务产出：配置期也是「一次拆分、两半一起落位」，任何一半不可复用都要重跑整次拆分。
	 */
	@Override
	protected void registerProviderTasks(Map<Path, Producer> producers) {
		requireBundledServerJar();

		final Project project = getProject();
		final Path clientJar = normalize(getMinecraftClientJar().toPath());
		final Path serverJar = normalize(getMinecraftExtractedServerJar().toPath());
		final Path clientOnlyJar = normalize(minecraftClientOnlyJar);
		final Path commonJar = normalize(minecraftCommonJar);
		// 两个产物同属一次拆分：先登记 client-only，再把 common 指向同一个生产者
		final Producer producer = RemapMinecraftTaskRegistry.claim(project, clientOnlyJar, Map.of(
				"stage", "split-jars",
				"clientJar", clientJar.toString(),
				"serverJar", serverJar.toString(),
				"outputs", "client-only+common"
		), () -> project.getTasks().register("splitMinecraftJars", SplitMinecraftJarsTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Splits the Minecraft client and server jar for %s".formatted(minecraftVersion()));
			// 产物位置与配置期一致：消费侧（重映射任务、genSources）按这些路径找产物
			task.getClientJar().set(clientJar.toFile());
			task.getServerJar().set(serverJar.toFile());
			task.getClientOnlyJar().set(clientOnlyJar.toFile());
			task.getCommonJar().set(commonJar.toFile());
			// 两个输入都由 vanilla 链的任务产出：按任务路径建依赖，冷缓存下才不会在输入落位前开跑
			dependOn(task, producerOf(producers, clientJar));
			dependOn(task, producerOf(producers, serverJar));
		}));

		producers.put(clientOnlyJar, producer);
		producers.put(commonJar, producer);
	}

	/** 校验该版本确实使用 bootstrap server jar：判据与配置期的拆分前置条件逐字一致. */
	private void requireBundledServerJar() {
		if (getServerBundleMetadata() == null) {
			throw new UnsupportedOperationException("Only Minecraft versions using a bundled server jar can be split, please use a merged jar setup for this version of minecraft");
		}
	}

	/**
	 * 把 client 与 server jar 拆成 client-only 与 common 两个产物.
	 *
	 * <p>配置期路径与执行期任务（{@code SplitMinecraftJarsTask}）共用本方法：共享条目清单、
	 * 「两个产物分别原子落位」「失败时清理临时文件」都在这里唯一持有。若任务另写一套，
	 * 两套拆分只要差一点，产物就会在同一个共享目录里互相判为不可复用。
	 *
	 * @param clientJar 待拆分的 client jar
	 * @param serverJar 待拆分的 server jar（抽取产物）
	 * @param clientOnlyJar 客户端侧产物的目标位置
	 * @param commonJar 两侧共享产物的目标位置
	 */
	public static void splitJars(Path clientJar, Path serverJar, Path clientOnlyJar, Path commonJar) throws Exception {
		// 原子发布：split 写到两个同目录唯一临时 jar，完整后再分别原子 move 到最终路径，
		// 避免跨进程读到任一半写的拆分产物误判就绪
		final Path tmpClientOnly = AtomicFiles.tempSibling(clientOnlyJar);
		final Path tmpCommon = AtomicFiles.tempSibling(commonJar);

		try (MinecraftJarSplitter jarSplitter = new MinecraftJarSplitter(clientJar, serverJar)) {
			// Required for loader to compute the version info also useful to have in both jars.
			jarSplitter.sharedEntry("version.json");
			jarSplitter.sharedEntry("assets/.mcassetsroot");
			jarSplitter.sharedEntry("assets/minecraft/lang/en_us.json");

			jarSplitter.split(tmpClientOnly, tmpCommon);
			AtomicFiles.move(tmpClientOnly, clientOnlyJar);
			AtomicFiles.move(tmpCommon, commonJar);
		} finally {
			// 原子 move 成功后临时文件已不存在；失败时清理残留
			Files.deleteIfExists(tmpClientOnly);
			Files.deleteIfExists(tmpCommon);
		}
	}

	public Path getMinecraftClientOnlyJar() {
		return minecraftClientOnlyJar;
	}

	public Path getMinecraftCommonJar() {
		return minecraftCommonJar;
	}
}
