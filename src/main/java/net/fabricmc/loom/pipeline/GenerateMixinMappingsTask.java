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
import java.util.ArrayList;
import java.util.List;

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

import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * L3 流水线：生产 Mixin AP 用的 {@code mappings-mixin-<namespace>.tiny}.
 *
 * <p>它取代的是 {@code MappingConfiguration.getReplacedTarget} 里那段配置期同步执行的
 * 「读平台映射文件首行 → 把两个命名空间名字互换 → 原子落位」。
 *
 * <h2>为什么需要搬出来</h2>
 * Mixin AP 是 <b>javac 的注解处理器</b>，它通过 {@code -AinMapFileNamed<Ns>=<绝对路径>}
 * 参数接收映射文件。参数必须在编译任务<b>启动前</b>就定好，于是那个路径必须在配置期可得——
 * 这正是 {@code getReplacedTarget} 被放在配置期、进而把整个 mappings 迁移拖在配置期的原因。
 *
 * <p>改为任务产出后，路径仍是同一个（{@code <userCache>/<mappingsIdentifier>/mappings-mixin-<ns>.tiny}），
 * 但文件什么时候写出来交给任务图：编译任务依赖本任务，参数里给的路径在执行期已是就绪产物。
 *
 * <h2>与配置期实现的一致性</h2>
 * 首行改写规则、原子落位方式与配置期 {@code writeReplacedTarget} 逐字一致：
 * {@code intermediary → yraidemretni}（先占位避免二次替换），再 {@code <namespace> → intermediary}。
 * 因此产物字节不变。
 */
@CacheableTask
public abstract class GenerateMixinMappingsTask extends DefaultTask {
	public static final String NAME = "generateMixinMappings";

	/** 平台映射文件（未改写的原始 tiny）；它由 mappings 阶段（或迁移任务）产出. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getPlatformMappings();

	/** refmap 的目标命名空间，决定改写后的首行写什么. */
	@Input
	public abstract Property<String> getNamespace();

	/** 产物：{@code mappings-mixin-<namespace>.tiny}. */
	@OutputFile
	public abstract RegularFileProperty getOutputMappings();

	@TaskAction
	public void run() throws IOException {
		final Path source = getPlatformMappings().get().getAsFile().toPath();
		final Path output = getOutputMappings().get().getAsFile().toPath();
		final String namespace = getNamespace().get();

		// intermediary 不需要改写：配置期 getReplacedTarget 对该命名空间直接返回平台映射文件本身
		if ("intermediary".equals(namespace)) {
			AtomicFiles.copy(source, output);
			return;
		}

		final List<String> lines = new ArrayList<>(Files.readAllLines(source));

		if (lines.isEmpty()) {
			throw new IllegalStateException("平台映射文件为空，无法为命名空间 %s 生产 Mixin 映射：%s"
					.formatted(namespace, source));
		}

		// 与配置期逐字一致：先把 intermediary 换成占位符，避免接下来的 namespace → intermediary 二次命中
		lines.set(0, lines.get(0).replace("intermediary", "yraidemretni").replace(namespace, "intermediary"));

		AtomicFiles.publish(output, tmp -> Files.write(tmp, lines));
	}
}
