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
import java.io.IOException;

import dev.architectury.loom.forge.tool.ForgeMavenizerService;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import net.fabricmc.loom.util.service.ScopedServiceFactory;

/** 由 Mavenizer 生产 Forge SRG 中间 JAR，与配置期共用同一生成服务. */
@DisableCachingByDefault(because = "Mavenizer 管理的外部依赖并未全部声明为 Gradle 输入")
public abstract class GenerateForgeMavenizerJarTask extends DefaultTask {
	public static final String NAME = "generateForgeMavenizerJar";

	public GenerateForgeMavenizerJarTask() {
		getOutputs().upToDateWhen(task -> !((GenerateForgeMavenizerJarTask) task).getMavenizer().get().getRefresh().get());
	}

	@Nested
	public abstract Property<ForgeMavenizerService.Options> getMavenizer();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@OutputFile
	public File getOutputReceipt() {
		return ForgeMavenizerService.receipt(getOutputJar().get().getAsFile().toPath()).toFile();
	}

	@TaskAction
	public void generate() throws IOException {
		try (var factory = new ScopedServiceFactory()) {
			final ForgeMavenizerService mavenizer = factory.get(getMavenizer());
			mavenizer.produce(getOutputJar().get().getAsFile().toPath());
		}
	}
}
