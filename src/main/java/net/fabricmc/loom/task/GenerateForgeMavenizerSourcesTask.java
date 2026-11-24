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

package net.fabricmc.loom.task;

import java.io.IOException;
import java.nio.file.Path;

import dev.architectury.loom.forge.tool.ForgeMavenizerSourcesBundle;
import dev.architectury.loom.util.TempFiles;
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

import net.fabricmc.loom.task.service.SourceRemapperService;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

/** 将与当前运行时同批的完整 Mojang 源码转换为项目的 named 源码附挂. */
@CacheableTask
public abstract class GenerateForgeMavenizerSourcesTask extends DefaultTask {
	public static final String NAME = "genForgePatchedSources";

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getBundle();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRuntimeSrgJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRuntimeReceipt();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getNamedJar();

	@Nested
	public abstract Property<SourceRemapperService.Options> getSourceRemapper();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void generate() throws IOException {
		try (var temporary = new TempFiles(); var factory = new ScopedServiceFactory()) {
			final var input = ForgeMavenizerSourcesBundle.unpack(path(getBundle()), path(getRuntimeSrgJar()),
					path(getRuntimeReceipt()), temporary.directory("loom-forge-sources-"));
			final SourceRemapperService remapper = factory.get(getSourceRemapper());
			AtomicFiles.publish(path(getOutputJar()), output -> remapper.remapSourcesJarStrict(input.sourcesJar(), output, input.mojangClassesJar()));
		}
	}

	private static Path path(RegularFileProperty file) {
		return file.get().getAsFile().toPath();
	}
}
