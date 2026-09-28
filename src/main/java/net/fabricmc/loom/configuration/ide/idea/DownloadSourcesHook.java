/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

package net.fabricmc.loom.configuration.ide.idea;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;

// See: https://github.com/JetBrains/intellij-community/blob/a09b1b84ab64a699794c860bc96774766dd38958/plugins/gradle/java/src/util/GradleAttachSourcesProvider.java
record DownloadSourcesHook(Project project, Task task) {
	public static final String INIT_SCRIPT_NAME = "ijDownloadSources";
	private static final Pattern NOTATION_PATTERN = Pattern.compile("dependencyNotation = '(?<notation>.*)'");
	private static final Logger LOGGER = LoggerFactory.getLogger(DownloadSourcesHook.class);

	public static boolean hasInitScript(Project project) {
		List<File> initScripts = project.getGradle().getStartParameter().getInitScripts();

		for (File initScript : initScripts) {
			if (initScript.getName().contains(INIT_SCRIPT_NAME)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 由每个 Loom 项目在自身配置阶段调用：把「源码坐标 → genSources 任务路径」登记到构建级注册表.
	 *
	 * <p>登记的是本项目自己的信息，不涉及跨项目访问，因此在隔离项目模式下也是安全的。
	 *
	 * @param project 当前正在配置的 Loom 项目
	 */
	static void register(Project project) {
		if (!hasInitScript(project)) {
			return;
		}

		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final NamedMinecraftProvider<?> minecraftProvider;

		try {
			minecraftProvider = extension.getNamedMinecraftProvider();
		} catch (RuntimeException e) {
			// Minecraft 提供器尚未就绪（例如未启用混淆配置）时无需登记。
			LOGGER.debug("Minecraft provider not ready in {}, skipping download sources registration", project.getPath());
			return;
		}

		final List<MinecraftJar.Type> dependencyTypes = minecraftProvider.getDependencyTypes();

		if (dependencyTypes.isEmpty()) {
			return;
		}

		final var decompileConfiguration = extension.getMinecraftJarConfiguration().get().createDecompileConfiguration(project);
		final IdeaDownloadSourcesRegistry registry = IdeaDownloadSourcesRegistry.get(project);

		for (MinecraftJar.Type type : dependencyTypes) {
			final String notation = minecraftProvider.getMavenHelper(type).withClassifier("sources").getNotation();
			registry.register(notation, taskPath(project, decompileConfiguration.getTaskName(type)));
		}
	}

	// 以任务路径字符串表达依赖：Gradle 在任务图阶段自行解析，无需读取其它项目的 TaskContainer。
	private static String taskPath(Project project, String taskName) {
		return project.getPath().equals(":") ? ":" + taskName : project.getPath() + ":" + taskName;
	}

	/**
	 * 由根项目调用：为自己的 {@code ijDownloadSources} 任务添加对各子项目 genSources 任务的依赖.
	 */
	void tryHook() {
		final IdeaDownloadSourcesRegistry registry = IdeaDownloadSourcesRegistry.get(project);
		List<File> initScripts = project.getGradle().getStartParameter().getInitScripts();

		for (File initScript : initScripts) {
			if (!initScript.getName().contains(INIT_SCRIPT_NAME)) {
				continue;
			}

			try {
				final String script = Files.readString(initScript.toPath(), StandardCharsets.UTF_8);
				final String notation = parseInitScript(script);

				if (notation == null) {
					LOGGER.debug("failed to parse init script dependency");
					continue;
				}

				final List<String> taskPaths = registry.taskPathsFor(notation);

				if (taskPaths.isEmpty()) {
					LOGGER.debug("init script is trying to download sources for another Minecraft jar ({}) not used by this build", notation);
					continue;
				}

				for (String taskPath : taskPaths) {
					task.dependsOn(taskPath);
					LOGGER.info("Running genSources task: {} for {}", taskPath, notation);
				}

				break;
			} catch (IOException e) {
				// 这里失败会让 ijDownloadSources 任务照常成功却什么都不做（不会挂上任何 genSources 任务），
				// 因此必须留下默认可见的日志，不能像以前那样静默吞掉。
				LOGGER.warn("Failed to read IDEA init script {}, no genSources task will be hooked up", initScript, e);
			}
		}
	}

	@Nullable
	private String parseInitScript(String script) {
		if (!script.contains("IjDownloadTask")) {
			// Failed some basic sanity checks.
			return null;
		}

		// A little gross but should do the job nicely.
		final Matcher matcher = NOTATION_PATTERN.matcher(script);

		if (matcher.find()) {
			return matcher.group("notation");
		}

		return null;
	}
}
