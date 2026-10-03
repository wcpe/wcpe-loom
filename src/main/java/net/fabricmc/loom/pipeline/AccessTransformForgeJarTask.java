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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;

import dev.architectury.loom.accesstransformer.AccessTransformerService;
import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

/**
 * L3 流水线：对打补丁后的中间 Minecraft jar 执行 Forge 的 access transform
 * （{@code minecraft-<type>-<ns>-at-patched.jar}）.
 *
 * <p>它取代的是 {@code MinecraftPatchedProvider.provide()} 里那段配置期同步执行的
 * {@code accessTransformForge()}。
 *
 * <h2>工具声明为什么是「纯值 + 执行期重建」</h2>
 * AT 工具的 classpath 来自项目依赖解析、JVM 可执行文件来自 Java 工具链，二者都只能在配置期取得；
 * 待应用的 AT 文件则要从 userdev jar 里抽出来，落在一个临时目录里。因此这里按本仓库既有的
 * {@code AccessTransformerJarProcessor} 处置方式：
 * <ul>
 *   <li>配置期把工具信息解析成纯值（{@link AccessTransformerService#resolveTool}）并逐项声明成
 *       {@code @Input}/{@code @Classpath}；</li>
 *   <li>执行期用注入的 {@link ObjectFactory} 重建服务选项
 *       （{@link AccessTransformerService#createOptions(ObjectFactory, AccessTransformerService.Tool, Object)}），
 *       并把 AT 文件抽到本任务自己的临时目录里。</li>
 * </ul>
 * 于是「AT 文件抽取」不再发生在配置期，而实际命令行仍只由 {@code AccessTransformerService.execute} 构造一处，
 * 与配置期路径不会分叉。
 */
@CacheableTask
public abstract class AccessTransformForgeJarTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "accessTransformForgeJar";

	/** 输入：打补丁后的中间 jar（AT 的作用对象）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getPatchedIntermediateJar();

	/** AT 文件的来源：Forge 的 userdev jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getForgeUserdevJar();

	/** AT 文件在 userdev jar 里的目录形态（{@code ats} 声明为目录时设置）. */
	@Input
	@Optional
	public abstract Property<String> getAtDirectory();

	/** AT 文件在 userdev jar 里的清单形态（{@code ats} 声明为文件列表时设置）. */
	@Input
	public abstract ListProperty<String> getAtPaths();

	/** AT 工具的主类. */
	@Input
	public abstract Property<String> getAtMainClass();

	/** AT 工具及其依赖的 jar（配置期解析出的绝对路径）. */
	@Classpath
	public abstract ConfigurableFileCollection getAtClasspath();

	/** AT 工具使用的 JVM 可执行文件；未配置 Java 工具链时为未设置，表示沿用 Gradle 自身的 JVM. */
	@Input
	@Optional
	public abstract Property<String> getAtJavaExecutable();

	/** 是否把 AT 工具的标准输出透传到构建日志. */
	@Input
	public abstract Property<Boolean> getAtVerboseStdout();

	/** 是否把 AT 工具的标准错误透传到构建日志. */
	@Input
	public abstract Property<Boolean> getAtVerboseStderr();

	/** 产物：AT 之后的 jar（与配置期同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	/** 执行期实例化服务选项用（{@code Project} 在执行期不可用，见类注释）. */
	@Inject
	public abstract ObjectFactory getObjects();

	@TaskAction
	public void generate() throws Exception {
		final Path input = getPatchedIntermediateJar().get().getAsFile().toPath();
		final Path output = getOutputJar().get().getAsFile().toPath();

		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			final List<String> atFiles = AccessTransformerService.extractLoaderAts(
					getForgeUserdevJar().get().getAsFile().toPath(), atLocation(), tempFiles);
			final AccessTransformerService service = serviceFactory.get(
					AccessTransformerService.createOptions(getObjects(), atTool(), atFiles));

			// 原子发布：产物位于跨 daemon 共享的 forge 缓存目录，配置期路径同样是「临时文件 + 原子落位」
			AtomicFiles.publish(output, tmp -> {
				Files.deleteIfExists(tmp);
				service.execute(input, tmp);
			});
		}
	}

	/** {@return 本次的 AT 文件位置声明} 与配置期读的 {@code UserdevConfig.ats} 逐项同源. */
	private UserdevConfig.AccessTransformerLocation atLocation() {
		if (getAtDirectory().isPresent()) {
			return new UserdevConfig.AccessTransformerLocation.Directory(getAtDirectory().get());
		}

		return new UserdevConfig.AccessTransformerLocation.FileList(getAtPaths().get());
	}

	/** {@return 配置期解析出的 AT 工具信息} 执行期只认这些纯值. */
	private AccessTransformerService.Tool atTool() {
		final @Nullable String javaExecutable = getAtJavaExecutable().getOrNull();

		return new AccessTransformerService.Tool(
				getAtMainClass().get(),
				getAtClasspath().getFiles().stream().map(File::getAbsolutePath).toList(),
				javaExecutable,
				getAtVerboseStdout().get(),
				getAtVerboseStderr().get());
	}
}
