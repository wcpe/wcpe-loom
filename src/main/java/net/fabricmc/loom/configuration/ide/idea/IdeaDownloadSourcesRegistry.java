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

package net.fabricmc.loom.configuration.ide.idea;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtensionContainer;

/**
 * 构建级注册表：收集「IDE 下载源码」所需的 genSources 任务路径.
 *
 * <p>隔离项目模式不允许从一个项目遍历或读取其它项目的模型，因此这里改为反向登记：
 * 每个 Loom 项目只写入自己的数据（源码坐标 → 任务路径），根项目再按「任务路径字符串」添加依赖。
 * 字符串依赖由 Gradle 在任务图阶段自行解析，不需要访问其它项目的 TaskContainer。
 */
public final class IdeaDownloadSourcesRegistry {
	private static final String EXTENSION_NAME = "loomIdeaDownloadSources";

	private final Map<String, Set<String>> taskPathsByNotation = new ConcurrentHashMap<>();

	public static IdeaDownloadSourcesRegistry get(Project project) {
		final Gradle gradle = project.getGradle();
		final ExtensionContainer extensions = ((ExtensionAware) gradle).getExtensions();

		if (extensions.findByName(EXTENSION_NAME) instanceof IdeaDownloadSourcesRegistry registry) {
			return registry;
		}

		final IdeaDownloadSourcesRegistry registry = new IdeaDownloadSourcesRegistry();
		extensions.add(IdeaDownloadSourcesRegistry.class, EXTENSION_NAME, registry);
		return registry;
	}

	void register(String notation, String taskPath) {
		taskPathsByNotation
				.computeIfAbsent(notation, key -> Collections.synchronizedSet(new LinkedHashSet<>()))
				.add(taskPath);
	}

	List<String> taskPathsFor(String notation) {
		final Set<String> taskPaths = taskPathsByNotation.get(notation);

		if (taskPaths == null) {
			return List.of();
		}

		synchronized (taskPaths) {
			return List.copyOf(taskPaths);
		}
	}
}
