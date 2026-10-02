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
import java.nio.file.Path;

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

import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * L3 流水线：把「srg → named」的映射整份写成 SRG 文本（Forge 的 {@code mappings-srg-named.srg}）.
 *
 * <p>它取代的是 {@code MappingConfiguration.applyToProject} 里那段配置期同步执行的生成
 * （配置文件锁 → 锁内二次确认 → 写临时文件 → 原子落位）。转换本身仍走
 * {@link MappingConfiguration#writeSrgNamedMappings(Path, Path)}：同一份实现被两条路径共用，
 * 避免两套转换逻辑产出不等价的 srg 文本——这与本流水线其余环节（合并/拆分/重映射）的处置一致。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要生成</td><td>配置期 {@code isReusableMappingsText} 判定（存在且非空）</td>
 *       <td>Gradle up-to-date 检查（输入内容 + 输出快照）</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td><td>任务图 + 构建缓存</td></tr>
 *   <tr><td>残骸恢复</td><td>手工判「0 字节残骸」并重建</td><td>输出快照不符即重建</td></tr>
 *   <tr><td>执行时机</td><td>配置期（{@code afterEvaluate}）</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>为什么输入是「srg 命名空间的 tiny」而不是映射 jar</h2>
 * 旧路径用的是 {@code TinyMappingsService}（{@code MappingOption.WITH_SRG}），它读的就是
 * {@code mappings-srg-migrated.tiny} 这一份纯文本映射树；本任务的 {@link #getMappings()} 取同一条路径，
 * 读取方式也同源（{@code MappingReader.read(Path, MappingTree)}）。换成映射 jar 需要重跑整条
 * layered/migrated 链，那是另一个阶段的事，也不属于本次迁移范围。
 *
 * <h2>就绪判据的差别</h2>
 * 旧路径的「存在且非空」判据是为了识别「先删后写被中断」留下的 0 字节残骸；换成任务后这一层由
 * Gradle 的输出快照承担：快照不符（含被截断）即重建，且任务每次都是原子落位，不会自己制造残骸。
 * {@link #getRefreshDeps()} 只是把 {@code --refresh-dependencies} 的强制重建语义保留下来（与
 * {@code ExtractMinecraftServerJarTask} 同款），不参与产物的内容判定。
 */
@CacheableTask
public abstract class GenerateSrgNamedMappingsTask extends DefaultTask {
	/** 输入的映射树：{@code mappings-srg-migrated.tiny}（srg 命名空间）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMappings();

	/** 是否强制重建（对应 {@code --refresh-dependencies}）:旧路径在该情形下无条件重写产物. */
	@Input
	public abstract Property<Boolean> getRefreshDeps();

	/** 产物：{@code mappings-srg-named.srg}（与旧路径同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getSrgFile();

	@TaskAction
	public void generate() throws IOException {
		final Path mappings = getMappings().get().getAsFile().toPath();
		final Path srgFile = getSrgFile().get().getAsFile().toPath();

		// 原子发布：产物位于跨进程、跨 daemon 共享的 mappings 工作目录，旧路径即原子落位。
		// 读方（游戏启动时的 ForgeGradle GradleStart）按路径直接打开它，因此「存在 ⟺ 内容完整」这一不变量必须保住。
		AtomicFiles.publish(srgFile, tmp -> MappingConfiguration.writeSrgNamedMappings(mappings, tmp));
	}
}
