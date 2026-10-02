/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.pipeline;

import java.nio.file.Path;

import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * L3 流水线：生产 modern Forge 的最终 patched jar（与 client-extra）.
 *
 * <p>它取代的是 {@code MinecraftPatchedProvider.remapJar} 里那段配置期同步执行的
 * 「判断是否需要重建 → 取跨进程锁 → 重映射 patched jar / 填充 client-extra → 原子落位」。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要重建</td><td>配置期 {@code provide()} 设的 {@code dirty} + 锁内二次确认</td>
 *       <td>Gradle up-to-date 检查（输入内容 + 输出快照）+ 锁内按可复用性二次确认</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁</td><td>任务图 + 跨进程文件锁（产物仍在共享缓存）</td></tr>
 *   <tr><td>执行时机</td><td>配置期（{@code CompileConfiguration.setupMinecraft}）</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>生产逻辑不在这里</h2>
 * 真正的生产仍由 {@link MinecraftPatchedProvider#produceFinalJarsForTask} 持有：它和配置期路径共用
 * 同一份实现（{@code remapFinalJar} / {@code fillClientExtraJar}），因此两条路径的产物逐字节一致。
 * 本任务另写一套「等价」的实现就会引入分叉——同一个输入产出两份略有差异的 jar，正是这次迁移要消灭的东西。
 * 与 {@code GenerateSrgNamedMappingsTask} 处置一致：同一份实现被两条路径共用。
 *
 * <h2>为什么输出是共享缓存里的那两个绝对路径</h2>
 * 产物位置必须与配置期逐字一致（{@code <userCache>/<mcVersion>/forge/minecraft-<type>-patched.jar}
 * 与 {@code client-extra.jar}）：消费侧（{@code getMinecraftJars()}/
 * {@code getMinecraftJarsCollection(OFFICIAL)}、{@code FORGE_EXTRA} 配置的依赖项）按路径找它们。
 * 该目录跨项目、跨 daemon 共享，因此「同一个路径只有一个生产者」由
 * {@link RemapMinecraftTaskRegistry} 在同一构建内保证，跨构建由 {@code @OutputFile} 的 up-to-date 判定
 * 与产物自身的原子落位承担。
 *
 * <h2>输入为什么是这些</h2>
 * 输入逐项对应生产时真正读入的文件与判据，而不是「把 provider 的对象搬进来」：
 * <ul>
 *   <li>{@link #getAtPatchedJar()}：重映射的输入（打过补丁并做过 AT 的中间 jar）；</li>
 *   <li>{@link #getForgeJar()}：参与重映射的类解析，并作为产物里的非 class 文件来源；</li>
 *   <li>{@link #getForgeUserdevJar()}：{@code inject/} 下的文件会被复制进产物；</li>
 *   <li>{@link #getClientJar()}：client-extra 的非 class 文件来源；</li>
 *   <li>{@link #getServerJar()}：NeoForge 的 dist 清单要按它分边（bootstrap 版本是抽取产物，
 *       否则是下载下来的 server jar）；</li>
 *   <li>{@link #getMappingsServiceOptions()}：上述重映射、coremod 重映射与 dist 清单都从它取映射树，
 *       与配置期 {@code buildRemapper}/{@code remapCoreMods} 用的是同一个映射选项；</li>
 *   <li>{@link #getPatchVersion()}：写在产物 manifest 里的补丁版本，也是配置期路径的就绪判据。</li>
 * </ul>
 */
@CacheableTask
public abstract class GenerateForgePatchedJarTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "generateForgePatchedJar";

	/** 打补丁并做过 access transform 的中间 Minecraft jar，即重映射的输入. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getAtPatchedJar();

	/** Forge 的 universal jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getForgeJar();

	/** Forge 的 userdev jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getForgeUserdevJar();

	/** client jar（client-extra 的内容来源）；server-only 配置下不设置. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getClientJar();

	/** 服务端侧 jar；仅 MERGED 形态用于 NeoForge 的 dist 清单. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getServerJar();

	/** 映射服务配置：产出 jar 的重映射、coremod 重映射与 dist 清单都读它. */
	@Nested
	public abstract Property<TinyMappingsService.Options> getMappingsServiceOptions();

	/** 本配置是否产出 client-extra（与配置期 {@code providesClientJar()} 同判据）. */
	@Input
	public abstract Property<Boolean> getClientExtraEnabled();

	/** Loom 补丁版本：写在产物 manifest 里，也是配置期路径的就绪判据. */
	@Input
	public abstract Property<String> getPatchVersion();

	/** 是否 NeoForge（决定是否挂 mixin 扩展、是否写 dist 清单）. */
	@Input
	public abstract Property<Boolean> getNeoForge();

	/** 是否 Forge（决定 client-extra 是否写空 MANIFEST）. */
	@Input
	public abstract Property<Boolean> getForge();

	/** 是否 unobfuscated Forge（最终 jar 走 userdev 合并分支、dist 清单用空映射树）. */
	@Input
	public abstract Property<Boolean> getUnobfuscatedForge();

	/** 是否在运行时使用 mojang 映射（决定 coremod 重映射的方向）. */
	@Input
	public abstract Property<Boolean> getRuntimeMojang();

	/** 是否 MERGED 形态（决定 dist 清单是否按 client/server 分边）. */
	@Input
	public abstract Property<Boolean> getMerged();

	/** 重映射的源命名空间（Forge 为 {@code srg}、NeoForge 为 {@code mojang}）. */
	@Input
	public abstract Property<String> getRemapNamespace();

	/** coremod 重映射使用的源命名空间（生产命名空间）. */
	@Input
	public abstract Property<String> getCoreModNamespace();

	/** 写进 dist 清单的 {@code Minecraft-Dists} 取值. */
	@Input
	public abstract Property<String> getDistAttribute();

	/** 是否显式要求刷新（对应 {@code --refresh-dependencies}）；与配置期 {@code refreshDeps} 同源. */
	@Input
	public abstract Property<Boolean> getRefreshDeps();

	/**
	 * 跨进程锁的 key：与配置期 {@code withPatchedLock} 取同一个 key，两侧的生产才不会互相穿插.
	 *
	 * <p>是 {@code @Input} 而不是 {@code @Internal}：换了 MC/Forge 版本就是换了一份产物身份。
	 */
	@Input
	public abstract Property<String> getLockKey();

	/**
	 * 跨进程锁所在目录.
	 *
	 * <p>{@code @Internal}：它只决定「在哪里互斥」，不决定产物内容；把它算进指纹会让同一份产物
	 * 因为缓存目录搬家而重建。
	 */
	@Internal
	public abstract Property<String> getLockRoot();

	/** 产物：最终 patched jar（与配置期同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	/** 产物：client-extra（与配置期同一条路径）；不产出时为未设置. */
	@OutputFile
	@Optional
	public abstract RegularFileProperty getClientExtraJar();

	@TaskAction
	public void generate() throws Exception {
		try (var serviceFactory = new ScopedServiceFactory()) {
			// 映射树在执行期从本任务声明的映射配置取：它与配置期路径
			// （MinecraftPatchedProvider.getMappingTree）用的是同一个映射选项
			final TinyMappingsService.Options mappingsOptions = getMappingsServiceOptions().get();
			final TinyMappingsService mappingsService = serviceFactory.get(mappingsOptions);
			final MemoryMappingTree mappings = mappingsService.getMappingTree();

			final var options = new MinecraftPatchedProvider.ProductionOptions(
					path(getAtPatchedJar()),
					path(getForgeJar()),
					path(getForgeUserdevJar()),
					optionalPath(getClientJar()),
					optionalPath(getServerJar()),
					mappings,
					getClientExtraEnabled().get(),
					getNeoForge().get(),
					getForge().get(),
					getUnobfuscatedForge().get(),
					getRuntimeMojang().get(),
					getMerged().get(),
					getRemapNamespace().get(),
					getCoreModNamespace().get(),
					getDistAttribute().get());

			// 生产实现与配置期路径共用（见类注释）；输入与平台开关逐项取自本任务的声明
			MinecraftPatchedProvider.produceFinalJarsForTask(
					options,
					path(getOutputJar()),
					optionalPath(getClientExtraJar()),
					Path.of(getLockRoot().get()),
					getLockKey().get(),
					getRefreshDeps().get(),
					getLogger()::lifecycle);
		}
	}

	private static Path path(RegularFileProperty property) {
		return property.get().getAsFile().toPath();
	}

	private static @Nullable Path optionalPath(RegularFileProperty property) {
		return property.isPresent() ? property.get().getAsFile().toPath() : null;
	}
}
