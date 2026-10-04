/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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

package dev.architectury.loom.mcpconfig.steplogic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import dev.architectury.loom.mcpconfig.McpConfigFunction;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * Runs a Forge tool configured by a {@linkplain McpConfigFunction function}.
 */
public final class FunctionLogic extends StepLogic<FunctionLogic.Options> {
	public static final ServiceType<Options, FunctionLogic> TYPE = new ServiceType<>(Options.class, FunctionLogic.class);

	public interface Options extends Service.Options {
		@Input
		Property<McpConfigFunction> getFunction();

		/**
		 * 工具 jar 的落位路径.
		 *
		 * <p>{@code @Internal}：它只是「下载缓存里按 URL 算出来的地址」，配置期纯计算即可得，
		 * 本身不是可判定的输入；参与 up-to-date 判定的是 {@link #getToolJar()}。
		 */
		@Internal
		RegularFileProperty getToolJarPath();

		/**
		 * 工具 jar 本身，作为输入参与 up-to-date 判定.
		 *
		 * <p>用 {@code @InputFiles}（而不是 {@code @InputFile}）：该文件在配置期<b>刻意</b>不下载
		 * （见 {@link #createOptions}），因此配置期它可能并不存在，而 {@code @InputFile} 对
		 * 「已设值但文件不存在」会直接判任务失败（{@code @Optional} 也不能豁免）。{@code @InputFiles}
		 * 允许缺失，存在时按内容参与判定。工具的「身份」另由 {@link #getFunction()} 里的
		 * {@code repo}/{@code version} 覆盖——版本或仓库一变即失效。
		 */
		@InputFiles
		@PathSensitive(PathSensitivity.NONE)
		ConfigurableFileCollection getToolJar();
	}

	/**
	 * 配置期只接线，不碰文件系统.
	 *
	 * <p>工具 jar 的路径按 URL 纯计算得出，<b>不做存在性判断、也不下载</b>：一旦在配置期观察
	 * 「下载缓存里的文件是否存在」，配置缓存就会把它记成文件系统输入指纹；而下载缓存位于任务自有的
	 * {@code build/tmp/…} 工作目录内、每次执行结束即删，其存在性逐次翻转，配置缓存将永久无法复用。
	 * 下载改由执行期的 {@link ExecutionContext#ensureToolJar} 完成（任务动作内的观察不被记录）。
	 */
	public static Provider<Options> createOptions(SetupContext context, McpConfigFunction function) {
		return TYPE.create(context.project(), options -> {
			options.getFunction().set(function);

			try {
				final Path path = function.resolvePath(context);
				options.getToolJarPath().set(path.toFile());
				options.getToolJar().setFrom(path.toFile());
			} catch (IOException e) {
				throw new UncheckedIOException("无法解析 MCP 工具 jar 的落位路径", e);
			}
		});
	}

	public FunctionLogic(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	@Override
	public void execute(ExecutionContext context) throws IOException {
		// These are almost always jars, and it's expected by some tools such as ForgeFlower.
		// The other tools seem to work with the name containing .jar anyway.
		// Technically, FG supports an "outputExtension" config value for steps, but it's not used in practice.
		context.setOutput("output.jar");

		McpConfigFunction function = getOptions().getFunction().get();
		File jar = resolveToolJar(context, function);
		String mainClass;

		try (JarFile jarFile = new JarFile(jar)) {
			mainClass = jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
		} catch (IOException e) {
			throw new IOException("Could not determine main class for " + jar.getAbsolutePath(), e);
		}

		context.javaexec(spec -> {
			spec.classpath(jar);
			spec.getMainClass().set(mainClass);
			spec.args(context.resolve(function.args()));
			spec.jvmArgs(context.resolve(function.jvmArgs()));
		});
	}

	/**
	 * {@return 就位后的工具 jar}.
	 *
	 * <p>配置期只算出了落位路径；这里在执行期把它补齐：{@code repo} 非空（裸 URL 下载）时按需下载，
	 * 否则该文件已由 Gradle 依赖解析在配置期就位。
	 */
	private File resolveToolJar(ExecutionContext context, McpConfigFunction function) throws IOException {
		final Path target = getOptions().getToolJarPath().get().getAsFile().toPath();

		if (function.repo() == null) {
			return target.toFile();
		}

		return context.ensureToolJar(target, function.getDownloadUrl()).toFile();
	}

	@Override
	public String getDisplayName(String stepName) {
		return stepName + " with " + getOptions().getFunction().get().version();
	}
}
