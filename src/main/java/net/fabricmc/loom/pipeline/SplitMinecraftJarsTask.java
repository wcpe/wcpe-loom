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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.configuration.providers.minecraft.SplitMinecraftProvider;

/**
 * L3 流水线：把 client 与 server jar 拆成 client-only 与 common 两个 jar.
 *
 * <p>它取代的是 {@code SplitMinecraftProvider.provide()} 里那段配置期同步执行的拆分
 * （无锁快路径 → 跨进程锁 → 锁内二次确认 → 拆分 → 两个产物分别原子落位）。
 * 拆分本身仍走 {@link SplitMinecraftProvider#splitJars}：同一份实现被两条路径共用，
 * 避免两套拆分逻辑产出不等价的 jar（共享条目清单 `version.json` / `assets/.mcassetsroot` /
 * `assets/minecraft/lang/en_us.json` 也在那里唯一持有）。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要拆分</td><td>配置期 {@code JarReusability.isReusable} 逐个产物判定</td>
 *       <td>Gradle up-to-date 检查（两个输出与输入指纹）</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td><td>任务图 + 构建缓存</td></tr>
 *   <tr><td>执行时机</td><td>配置期 {@code afterEvaluate}</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>两个产物必须一起看</h2>
 * 两个输出同属一次拆分的两半，任何一个不可复用都要重跑整次拆分（配置期也是这么判的：
 * 两个产物任一不可复用即进锁重建）。这一点由声明两个 {@code @OutputFile} 表达：
 * Gradle 的 up-to-date 判定要求全部输出都与上次执行一致。
 *
 * <h2>输入只用抽取产物</h2>
 * 能拆分的前提是「该版本的 server 下载是 bootstrap jar」，其真身在抽取产物里；
 * 因此 {@link #getServerJar()} 直接是抽取任务的输出，而非抽取前的 bootstrap jar。
 * 「不是 bootstrap 版本却要求拆分」在配置期就已经拒绝（判据同样来自不打开 jar 的 L2 元数据）。
 */
@CacheableTask
public abstract class SplitMinecraftJarsTask extends DefaultTask {
	/** 待拆分的 client jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getClientJar();

	/** 待拆分的 server jar（抽取产物）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getServerJar();

	/** 客户端侧产物. */
	@OutputFile
	public abstract RegularFileProperty getClientOnlyJar();

	/** 两侧共享产物. */
	@OutputFile
	public abstract RegularFileProperty getCommonJar();

	@TaskAction
	public void split() throws Exception {
		SplitMinecraftProvider.splitJars(
				getClientJar().get().getAsFile().toPath(),
				getServerJar().get().getAsFile().toPath(),
				getClientOnlyJar().get().getAsFile().toPath(),
				getCommonJar().get().getAsFile().toPath());
	}
}
