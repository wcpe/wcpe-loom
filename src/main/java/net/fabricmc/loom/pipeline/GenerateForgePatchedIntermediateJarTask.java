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
import dev.architectury.loom.forge.tool.ForgeExternalToolService;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * L3 流水线：生产 Forge 打补丁后的中间 Minecraft jar（{@code minecraft-<type>-<ns>-patched.jar}）.
 *
 * <p>它取代的是 {@code MinecraftPatchedProvider.provide()} 里那段配置期同步执行的
 * {@code producePatchedIntermediate}：binpatcher 打补丁 → 补出缺失的类 → 删掉 vignette 参数名
 * → （非 official 的 Forge 系）修补参数注解 → 原子落位。
 *
 * <h2>生产逻辑不在这里</h2>
 * 真正的生产仍由 {@link MinecraftPatchedProvider#producePatchedIntermediateForTask} 持有：
 * 配置期路径与执行期任务共用它，因此两条路径的 binpatcher 命令行、类改写顺序与产物逐字节一致。
 *
 * <h2>输入为什么是这些</h2>
 * <ul>
 *   <li>{@link #getPrePatchJar()}：binpatcher 的 {@code {clean}}，也是补出缺失类的来源；</li>
 *   <li>{@link #getPatches()}：binpatcher 的 {@code {patch}}（配置期由 {@code type.patches} 解析成路径）；</li>
 *   <li>{@link #getBinpatcherTool()}：工具 classpath、主类与参数模板（惰性，见
 *       {@code MinecraftPatchedProvider.createBinpatcherTool()}）；</li>
 *   <li>{@link #getFixParameterAnnotations()}：配置期算好的纯值，判据与旧路径逐字一致
 *       （{@code isForgeLikeAndNotOfficial() && !isUnobfuscatedForge()}）。</li>
 * </ul>
 */
@CacheableTask
public abstract class GenerateForgePatchedIntermediateJarTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "generateForgePatchedIntermediateJar";

	/** 待打补丁的 pre-patch jar，即 binpatcher 的 {@code {clean}}. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getPrePatchJar();

	/** 补丁 jar，即 binpatcher 的 {@code {patch}}. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getPatches();

	/** binpatcher 工具的声明式选项. */
	@Nested
	public abstract Property<ForgeExternalToolService.Options> getBinpatcherTool();

	/** 是否在打补丁后修补参数注解（非 official 的 Forge 系）. */
	@Input
	public abstract Property<Boolean> getFixParameterAnnotations();

	/** 产物：打补丁后的中间 jar（与配置期同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void generate() throws Exception {
		final Path output = getOutputJar().get().getAsFile().toPath();
		// 原子发布：产物位于跨 daemon 共享的 forge 缓存目录，配置期路径同样是「临时文件 + 原子落位」
		MinecraftPatchedProvider.publishAtomically(output, tmp -> MinecraftPatchedProvider.producePatchedIntermediateForTask(
				getPrePatchJar().get().getAsFile().toPath(),
				tmp,
				getPatches().get().getAsFile().toPath(),
				getBinpatcherTool().get(),
				getFixParameterAnnotations().get(),
				getLogger()::lifecycle,
				getLogger()::info));
	}
}
