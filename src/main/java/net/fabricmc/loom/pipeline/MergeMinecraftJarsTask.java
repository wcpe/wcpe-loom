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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * L3 流水线：把 client 与 server jar 合并成 merged jar.
 *
 * <p>它取代的是 {@code MergedMinecraftProvider.provide()} 里那段配置期同步执行的合并
 * （无锁快路径 → 跨进程锁 → 锁内二次确认 → 合并 → 原子落位）。合并本身仍走
 * {@link MergedMinecraftProvider#mergeJars(java.io.File, java.io.File, java.io.File)}：
 * 同一份实现被两条路径共用，避免两套合并逻辑产出不等价的 jar。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要合并</td><td>配置期 {@code JarReusability.isReusable} 判定</td>
 *       <td>Gradle up-to-date 检查（输出内容与输入指纹）</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td><td>任务图 + 构建缓存</td></tr>
 *   <tr><td>残骸恢复</td><td>手工判「0 字节/截断」</td><td>构建缓存重新恢复</td></tr>
 *   <tr><td>执行时机</td><td>配置期 {@code afterEvaluate}</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>两个输入各自来自哪一步</h2>
 * {@link #getServerJar()} 由接线侧按 bundle 元数据选好：该版本使用 bootstrap jar 时是抽取产物，
 * 否则就是下载下来的 server jar（判据与配置期 {@code MergedMinecraftProvider.mergeJars} 逐字一致）。
 * 选在这里而不是任务里，是因为任务只有一对路径、无从分辨「这个 server jar 是 bootstrap 还是真身」；
 * 而对应关系在配置期已经确定（元数据来自 L2 规格缓存，不打开 jar）。
 *
 * <h2>落位必须原子</h2>
 * 产物位于跨进程、跨 daemon 共享的 {@code <userCache>/<mcVersion>/}，配置期即已原子落位
 * （{@code AtomicFiles.publish}）。本任务沿用同一条契约：否则读方会读到半写的 merged jar，
 * 而内容判定只能发现截断、发现不了「完整但没写完」。
 */
@CacheableTask
public abstract class MergeMinecraftJarsTask extends DefaultTask {
	/** 待合并的 client jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getClientJar();

	/** 待合并的 server jar：bootstrap 版本下是抽取产物，否则是下载下来的 server jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getServerJar();

	/** 合并产物. */
	@OutputFile
	public abstract RegularFileProperty getMergedJar();

	@TaskAction
	public void merge() throws IOException {
		final var clientJar = getClientJar().get().getAsFile();
		final var serverJar = getServerJar().get().getAsFile();
		final var mergedJar = getMergedJar().get().getAsFile().toPath();

		// 原子发布：合并写到同目录唯一临时 jar，完整后再原子 move 到最终路径
		AtomicFiles.publish(mergedJar, tmpJar -> MergedMinecraftProvider.mergeJars(clientJar, serverJar, tmpJar.toFile()));
	}
}
