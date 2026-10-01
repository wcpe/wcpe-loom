/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.validator.ClassTweakerValidatingVisitor;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.tinyremapper.TinyRemapper;

@DisableCachingByDefault
public abstract class ValidateAccessWidenerTask extends DefaultTask {
	@SkipWhenEmpty
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getAccessWidener();

	@Classpath
	public abstract ConfigurableFileCollection getTargetJars();

	@Inject
	public ValidateAccessWidenerTask() {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		getAccessWidener().convention(extension.getAccessWidenerPath()).finalizeValueOnRead();
		// 刻意不对 getTargetJars() 调 finalizeValueOnRead()：实测（Gradle 9.x）finalize 会把嵌套文件集合
		// 展平成「解析出的文件列表」，随之丢掉它们的任务依赖——于是本任务不会等产出 MC jar 的任务跑完，
		// 冷缓存下直接读到一个还不存在的 jar 并抛 NoSuchFileException。成员 jar 现在由任务产出
		// （见 LoomGradleExtension#setMinecraftJarsTaskOutputs），这份依赖必须留着。
		// 该集合只在执行期被读一次，放弃 finalize 的代价可以忽略。
		getTargetJars().from(extension.getMinecraftJarsCollection(MappingsNamespace.NAMED));

		// Ignore outputs for up-to-date checks as there aren't any (so only inputs are checked)
		getOutputs().upToDateWhen(task -> true);
	}

	@TaskAction
	public void run() {
		final TinyRemapper tinyRemapper = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE).build();

		for (File file : getTargetJars().getFiles()) {
			tinyRemapper.readClassPath(file.toPath());
		}

		AtomicBoolean hasProblem = new AtomicBoolean(false);

		final ClassTweakerValidatingVisitor validator = new ClassTweakerValidatingVisitor(tinyRemapper.getEnvironment(), (lineNumber, message) -> {
			hasProblem.set(true);
			getLogger().error("{} on line {}", message, lineNumber);
		});

		final ClassTweakerReader accessWidenerReader = ClassTweakerReader.create(validator);

		try (BufferedReader reader = Files.newBufferedReader(getAccessWidener().get().getAsFile().toPath(), StandardCharsets.UTF_8)) {
			accessWidenerReader.read(reader);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read access widener", e);
		} finally {
			tinyRemapper.finish();
		}

		if (hasProblem.get()) {
			getLogger().error("access-widener validation failed for {}", getAccessWidener().get().getAsFile().getName());
			throw new RuntimeException("access-widener validation failed for %s".formatted(getAccessWidener().get().getAsFile().getName()));
		}
	}
}
