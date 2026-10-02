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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.configuration.providers.BundleMetadata;

/**
 * L3 流水线：把 bootstrap server jar 里内嵌的 server jar 抽出来.
 *
 * <p>它取代的是 {@code MinecraftProvider.extractBundledServerJar()} 那段配置期同步执行的抽取
 * （由 {@code provide()} 在锁内调用）。1.18（21w39a）之后的 server 下载是 bootstrap jar，
 * 真正的 server jar 在 {@code META-INF/versions/<版本>/} 下，抽取产物才是后续合并/拆分/重映射的输入。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要抽取</td><td>配置期读 {@code LoomHash} 标记 + {@code Files.exists}</td>
 *       <td>Gradle up-to-date 检查（输出内容与输入指纹）</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td><td>任务图 + 构建缓存</td></tr>
 *   <tr><td>执行时机</td><td>配置期 {@code afterEvaluate}</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>抽取规则不在这里</h2>
 * 真正的抽取（原子发布 + {@code LoomHash} 标记）由 {@link BundleMetadata#unpackEntry} 唯一持有：
 * 配置期路径与执行期路径共用同一份实现。若本任务另写一套，两套产出的 jar 只要差一点，
 * 「任务产物」与「配置期产物」就会在同一个共享目录里互相判为不可复用（{@code LoomHash} 标记不匹配
 * 会触发配置期路径重新抽取），而两者本应是同一个文件。
 *
 * <h2>为什么输入里没有 Project</h2>
 * 配置期那一版要从 Project 取 {@code extension.refreshDeps()}，而「是否要求刷新」本身就是配置期事实，
 * 与「命令行是否要求刷新」同源，因此改为显式的 {@link #getRefreshDeps()} 输入：任务在执行期不触碰项目模型，
 * 配置缓存友好。
 *
 * <h2>产物只有一个文件，刻意不声明目录</h2>
 * 输出位于跨项目共享的 {@code <userCache>/<mcVersion>/}：同一目录里还有 client/server/merged jar 等
 * 别的 provider、别的阶段（甚至别的 loom 版本）的产物，声明目录会把它们纳入本任务的快照与清理范围。
 */
@CacheableTask
public abstract class ExtractMinecraftServerJarTask extends DefaultTask {
	/** bootstrap server jar（1.18 起 server 下载的形态）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getServerJar();

	/** 内嵌 server jar 在 bootstrap jar 中的路径（来自 bundle 元数据的 {@code META-INF/versions.list}）. */
	@Input
	public abstract Property<String> getEntryPath();

	/**
	 * 内嵌 server jar 声明的 sha1.
	 *
	 * <p>它是「这个产物是不是那一份」的身份：既是 up-to-date 判定的输入，也是写入产物
	 * {@code LoomHash} 标记的值——配置期路径据此判定抽取产物可复用。
	 */
	@Input
	public abstract Property<String> getEntrySha1();

	/** 是否忽略既有产物、强制重新抽取（对应配置期的 {@code extension.refreshDeps()}）. */
	@Input
	public abstract Property<Boolean> getRefreshDeps();

	/** 抽取出的 server jar. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void extract() throws IOException {
		BundleMetadata.unpackEntry(
				getServerJar().get().getAsFile().toPath(),
				getOutputJar().get().getAsFile().toPath(),
				getEntryPath().get(),
				getEntrySha1().get(),
				getRefreshDeps().get());
	}
}
