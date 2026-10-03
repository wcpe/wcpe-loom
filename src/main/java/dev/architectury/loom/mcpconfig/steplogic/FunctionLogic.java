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
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import dev.architectury.loom.mcpconfig.McpConfigFunction;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import net.fabricmc.loom.util.Lazy;
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

		// 归一化策略必须声明：这些选项现在会作为任务的 @Nested 输入参与 up-to-date 判定，
		// 而 Gradle 对「缺归一化策略的 @InputFile」会直接让任务失败。工具 jar 只按内容参与判定，故用 NONE。
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getToolJar();
	}

	public static Provider<Options> createOptions(SetupContext context, McpConfigFunction function) {
		return TYPE.create(context.project(), options -> {
			options.getFunction().set(function);
			final Provider<File> jar = context.project().provider(Lazy.of(() -> {
				try {
					return function.download(context).toFile();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			})::get);
			options.getToolJar().set(context.project().getLayout().file(jar));
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
		File jar = getOptions().getToolJar().get().getAsFile();
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

	@Override
	public String getDisplayName(String stepName) {
		return stepName + " with " + getOptions().getFunction().get().version();
	}
}
