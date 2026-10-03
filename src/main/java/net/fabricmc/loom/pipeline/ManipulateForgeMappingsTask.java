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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.architectury.loom.forge.ForgeMigratedMappingConfiguration;
import dev.architectury.loom.forge.MappingsMigrator;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * L3 流水线：生产 Forge 的「迁移后 mappings」（{@code mappings-migrated.tiny} 与
 * {@code mappings-srg-migrated.tiny} / {@code mappings-mojang-migrated.tiny}）.
 *
 * <p>它取代的是 {@code ForgeMigratedMappingConfiguration.manipulateMappings} 里那段配置期同步执行的
 * 「复制原始 mappings → 跑两个迁移器改写 → 原子落位 → 发布就绪标记」。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要重建</td><td>配置期 {@code isOutdated} 判定（就绪标记的 hash 比对）</td>
 *       <td>Gradle up-to-date 检查（输入内容 + 输出快照）</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td><td>任务图 + 构建缓存</td></tr>
 *   <tr><td>残骸恢复</td><td>标记先失效、产物全部落位后才发布</td><td>输出快照不符即重建</td></tr>
 *   <tr><td>执行时机</td><td>配置期（{@code setupPost}）</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>生产逻辑不在这里</h2>
 * 真正的生产由 {@link ForgeMigratedMappingConfiguration#manipulate} 持有：配置期回退路径与执行期任务
 * 共用同一份实现（复制顺序、迁移器调用顺序、原子落位与就绪标记内容都一致），因此两条路径的产物逐字节一致。
 * 本任务另写一套「等价」的实现就会引入分叉——同一个输入产出两份略有差异的 mappings，
 * 而且这种差异不会让任何一方失败，只会静默地换一套映射。
 *
 * <h2>为什么输入里有 patched 中间产物</h2>
 * 两个迁移器（{@code FieldMappingsMigrator} / {@code MethodInheritanceMappingsMigrator}）在各自缓存
 * 未命中时要按路径读 patched 中间 jar。这正是本步要消灭的那条「配置期按路径读执行期产物」依赖：
 * 那件产物由 {@code generateForgePatchedIntermediateJar} 产出，配置期读不到。改为任务输入之后，
 * 依赖由任务图表达，缓存未命中也不再需要整批回退到配置期。
 *
 * <h2>为什么就绪标记也是输出</h2>
 * {@code mappings-migrated.hash} 位于跨 daemon、跨工作树共享的缓存目录：另一棵工作树若仍在跑改造前的
 * loom，会读它判断「迁移产物是否与当前输入同代」。因此本任务照旧写出它，内容与配置期路径逐字一致
 * （同一份实现算出的哈希）。它只是兼容性产物，不参与本任务自己的 up-to-date 判定之外的任何逻辑。
 */
@CacheableTask
public abstract class ManipulateForgeMappingsTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "manipulateForgeMappings";

	/** 迁移前、未按命名空间合并的映射树（{@code mappings.tiny}）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRawMappings();

	/** 迁移前、已按命名空间合并的映射树（{@code mappings-srg.tiny} / {@code mappings-mojang.tiny}）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRawMappingsWithNs();

	/** 打补丁后的中间 Minecraft jar：迁移器缓存未命中时读它的字段与继承关系. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getPatchedIntermediateJar();

	/** Forge 的 universal jar：{@code MethodInheritanceMappingsMigrator} 的继承关系来源之一. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getForgeJar();

	/** Forge 的 userdev jar：同上. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getForgeUserdevJar();

	/** 迁移器缓存所在目录（共享的 forge 缓存目录）；目录不存在时执行期创建. */
	@Input
	public abstract Property<String> getMigratorCacheDir();

	/** 本配置是否生成 srg 命名空间的映射（决定读哪一份 patched 中间产物的命名空间）. */
	@Input
	public abstract Property<Boolean> getHasSrg();

	/** 本配置是否为 NeoForge（同上，mojang 命名空间）. */
	@Input
	public abstract Property<Boolean> getHasMojang();

	/** 是否强制重建（对应 {@code --refresh-dependencies}）：旧路径在该情形下无条件重跑迁移器. */
	@Input
	public abstract Property<Boolean> getRefreshDeps();

	/** 平台 id：只用于日志文本（与旧路径的 {@code :manipulated <platform> mappings in ...} 逐字一致）. */
	@Input
	public abstract Property<String> getPlatformId();

	/** 产物：迁移后的默认映射树（{@code mappings-migrated.tiny}，与旧路径同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getMigratedMappings();

	/** 产物：迁移后的命名空间映射树（{@code mappings-srg-migrated.tiny} / {@code mappings-mojang-migrated.tiny}）. */
	@OutputFile
	public abstract RegularFileProperty getMigratedMappingsWithNs();

	/** 产物：迁移产物的就绪标记（{@code mappings-migrated.hash}，内容与旧路径逐字一致）. */
	@OutputFile
	public abstract RegularFileProperty getReadyMarker();

	@TaskAction
	public void manipulate() throws IOException {
		final Path cacheDir = Path.of(getMigratorCacheDir().get());
		Files.createDirectories(cacheDir);

		// 全程不触碰 Project：配置缓存开启时执行期调用 Task.project 会被 Gradle 直接拒绝。
		// 因此生产实现只认这里构造的纯值输入，日志出口是任务自己的 logger。
		ForgeMigratedMappingConfiguration.manipulate(
				new MappingsMigrator.Inputs(
						cacheDir,
						getRawMappingsWithNs().get().getAsFile().toPath(),
						getPatchedIntermediateJar().get().getAsFile().toPath(),
						getForgeJar().get().getAsFile().toPath(),
						getForgeUserdevJar().get().getAsFile().toPath(),
						getHasSrg().get(),
						getHasMojang().get(),
						getRefreshDeps().get(),
						getLogger()::info),
				getRawMappings().get().getAsFile().toPath(),
				getMigratedMappings().get().getAsFile().toPath(),
				getMigratedMappingsWithNs().get().getAsFile().toPath(),
				getReadyMarker().get().getAsFile().toPath(),
				getPlatformId().get(),
				getLogger()::info);
	}
}
