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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import dev.architectury.loom.forge.tool.ForgeExternalToolService;

import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

/**
 * 为 legacy Forge（FG2 形态的 userdev，MC 1.7-1.12.2）打一个 vanilla jar 的二进制补丁.
 *
 * <p>它取代 {@code MinecraftLegacyPatchedProvider.patchJars()} 里的单件生产：binpatcher 打补丁，
 * 再把补丁未触及的非 class 文件补齐，最后用 {@code ParameterAnnotationsFixer} 修正 Forge 补丁产物
 * 里违反 JVM 规范的参数注解（详见该类的说明）。
 *
 * <h2>为什么一个类型服务两个产物</h2>
 * client / server 用的是同一条流程，只差输入 jar 与补丁文件。用同一个任务类型 + 两个实例
 * （不同任务名、不同输入输出）比写两个类更不容易出现「只改了一边」的不一致；
 * 也与配置期 {@code patchJars()} 里那两次对称的 {@code publishAtomically} 一一对应。
 *
 * <h2>产物一致性</h2>
 * 调用的是与配置期完全相同的 {@code runBinpatcher}、{@code copyMissingClasses}、
 * {@code walkFileSystems} 与 {@code modifyClasses}，调用顺序也一致，因此产物逐字节相同。
 */
@CacheableTask
public abstract class PatchLegacyMinecraftJarsTask extends DefaultTask {
	/** 任务名：client 侧. */
	public static final String NAME_CLIENT = "patchLegacyMinecraftClientJar";

	/** 任务名：server 侧. */
	public static final String NAME_SERVER = "patchLegacyMinecraftServerJar";

	/** 输入：未打补丁的 vanilla jar（binpatcher 的 {@code {clean}}，也是补齐非 class 文件的来源）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getCleanJar();

	/** 输入：补丁 jar（binpatcher 的 {@code {patch}}）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getPatches();

	/** binpatcher 工具的声明式选项（配置期解析成纯值，执行期只发进程）. */
	@Nested
	public abstract Property<ForgeExternalToolService.Options> getBinpatcherTool();

	/** 产物：打完补丁的 jar（与配置期同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void generate() throws Exception {
		final Path output = getOutputJar().get().getAsFile().toPath();
		// 原子发布：产物位于跨 daemon 共享的 forge 缓存目录，配置期路径同样是「临时文件 + 原子落位」
		AtomicFiles.publish(output, tmp -> {
			try {
				produce(tmp);
			} catch (Exception e) {
				throw new RuntimeException("打 legacy Minecraft 补丁失败", e);
			}
		});
	}

	private void produce(Path output) throws Exception {
		getLogger().lifecycle(":patching jars");

		final Path clean = getCleanJar().get().getAsFile().toPath();
		final Path patches = getPatches().get().getAsFile().toPath();

		try (var serviceFactory = new ScopedServiceFactory()) {
			// 与配置期共用同一处实现（binpatcher 命令行与参数模板不会分叉）
			MinecraftPatchedProvider.runBinpatcher(getBinpatcherTool().get(), clean, output, patches, serviceFactory);
		}

		// Patching only preserves affected classes, everything else we need to copy manually
		MinecraftPatchedProvider.copyMissingClassesStatic(clean, output);
		MinecraftPatchedProvider.walkFileSystems(clean, output,
				file -> !file.toString().endsWith(".class"), MinecraftPatchedProvider::copyReplacing);

		// Workaround Forge patches apparently violating the JVM spec (see ParameterAnnotationsFixer for details)
		modifyClasses(output);

		getLogger().lifecycle(":patched jars");
	}

	/**
	 * 用 ASM 逐类改写（与配置期 {@code modifyClasses(Path, Function)} 同一条实现）.
	 *
	 * <p>配置期那个是实例方法且依赖 {@code project}（读临时文件目录），这里把等价逻辑搬到静态：
	 * 先解压到临时 jar、逐类过 visitor、再写回去。为了让两条路径不出现两份实现，
	 * 本任务直接调 {@code MinecraftPatchedProvider.modifyClassesStatic}。
	 */
	private void modifyClasses(Path jar) throws Exception {
		dev.architectury.loom.forge.minecraft.MinecraftLegacyPatchedProvider
				.modifyParameterAnnotations(jar);
	}
}
