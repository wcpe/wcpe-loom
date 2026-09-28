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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 把若干归档（jar/zip）解压到单一输出目录的声明式任务.
 *
 * <p>与手工解压相比，本任务：
 * <ul>
 *     <li>只声明一个 {@link OutputDirectory}，不产出与之重叠的 {@code @OutputFile}，避免任务输出重叠；
 *     <li>使用 Gradle 的 {@link ArchiveOperations#zipTree} 与 {@link FileSystemOperations#sync}，
 *         因此可被构建缓存与增量执行复用；
 *     <li>归档只参与输入哈希而不参与相对路径计算（{@link PathSensitivity#NONE}），
 *         这样同一份依赖在 Gradle 缓存目录中的绝对路径变化不会引起无谓重跑。
 * </ul>
 *
 * <p>当所有输入归档都不含任何可选后缀文件时，会在输出目录写入一个空标记文件，
 * 保证任务在“确实没有可解压内容”时也有稳定的输出，而不会产出空目录。
 */
@CacheableTask
public abstract class ExtractArchiveFilesTask extends DefaultTask {
	/**
	 * 展开 Forge 安装器源码包的任务名.
	 */
	public static final String FORGE_SOURCES_TASK_NAME = "extractForgeSources";

	/**
	 * 上述任务的输出目录（相对于构建目录）.
	 */
	public static final String FORGE_SOURCES_OUTPUT_DIRECTORY = "loom/forgeSources";

	/**
	 * 无匹配条目时写入的标记文件名；该文件同时作为“同步已完成”的稳定输出.
	 */
	public static final String EMPTY_MARKER_FILE = ".loom-empty-archive";

	/**
	 * 待解压的归档集合.
	 */
	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	protected abstract ConfigurableFileCollection getArchives();

	/**
	 * 只保留这些后缀的条目；为空表示保留全部条目.
	 */
	@Input
	protected abstract ListProperty<String> getIncludedSuffixes();

	/**
	 * 无匹配条目时写入的输出目录内标记文件名.
	 */
	@Input
	protected abstract ListProperty<String> getEmptyMarkers();

	/**
	 * 只解压这些后缀的条目；配合 {@link #getIncludedSuffixes()} 的约定（空列表表示全部）.
	 */
	public void includeSuffixes(String... suffixes) {
		getIncludedSuffixes().set(java.util.List.of(suffixes));
	}

	public void emptyMarker(String marker) {
		getEmptyMarkers().set(java.util.List.of(marker));
	}

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@Inject
	protected abstract ArchiveOperations getArchiveOperations();

	@Inject
	protected abstract FileSystemOperations getFileSystemOperations();

	public ExtractArchiveFilesTask() {
		setGroup("loom");
		setDescription("解压归档文件到输出目录，供后续任务复用");
	}

	@TaskAction
	protected void run() throws IOException {
		final Path outputDir = getOutputDirectory().get().getAsFile().toPath();
		final java.util.List<String> suffixes = getIncludedSuffixes().get();
		final Path emptyMarker = outputDir.resolve(getEmptyMarkers().get().getFirst());

		Files.createDirectories(outputDir);

		getFileSystemOperations().sync(spec -> {
			spec.into(outputDir);

			for (var archive : getArchives()) {
				var tree = getArchiveOperations().zipTree(archive);

				if (!suffixes.isEmpty()) {
					tree = tree.matching(patternFilterable -> patternFilterable.include(suffixes));
				}

				spec.from(tree);
			}
		});

		if (isEmptyDirectory(outputDir, emptyMarker)) {
			Files.writeString(emptyMarker, "", StandardCharsets.UTF_8);
		} else {
			Files.deleteIfExists(emptyMarker);
		}
	}

	private static boolean isEmptyDirectory(Path outputDir, Path emptyMarker) throws IOException {
		try (var stream = Files.walk(outputDir)) {
			return stream
					.filter(path -> !path.equals(outputDir))
					.filter(path -> !path.equals(emptyMarker))
					.findAny()
					.isEmpty();
		}
	}
}
