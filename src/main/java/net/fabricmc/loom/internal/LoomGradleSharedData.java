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

package net.fabricmc.loom.internal;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtensionContainer;

/**
 * 在项目隔离模式下传递只包含可序列化值的共享数据.
 */
public final class LoomGradleSharedData implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String EXTENSION_NAME = "loomSharedData";

	private final String pluginVersion;
	private final Map<String, LoomProjectData> projects = new LinkedHashMap<>();

	public LoomGradleSharedData(String pluginVersion) {
		this.pluginVersion = pluginVersion;
	}

	public String pluginVersion() {
		return pluginVersion;
	}

	public synchronized void putProject(LoomProjectData data) {
		projects.put(data.projectPath(), data);
	}

	public synchronized LoomProjectData getProject(String path) {
		return projects.get(path);
	}

	public static void beforeProject(Gradle gradle) {
		ExtensionContainer extensions = ((ExtensionAware) gradle).getExtensions();

		if (extensions.findByName(EXTENSION_NAME) instanceof LoomGradleSharedData) {
			return;
		}

		LoomGradleSharedData data = new LoomGradleSharedData(net.fabricmc.loom.LoomGradlePlugin.LOOM_VERSION);
		extensions.add(LoomGradleSharedData.class, EXTENSION_NAME, data);
	}

	public static void beforeProject(Project project, LoomGradleSharedData data) {
		if (project.getExtensions().findByName(EXTENSION_NAME) == null) {
			project.getExtensions().add(LoomGradleSharedData.class, EXTENSION_NAME, data);
		}
	}

	public static LoomGradleSharedData get(Project project) {
		LoomGradleSharedData data = project.getExtensions().findByType(LoomGradleSharedData.class);

		if (data != null) {
			return data;
		}

		ExtensionContainer extensions = ((ExtensionAware) project.getGradle()).getExtensions();
		data = extensions.findByType(LoomGradleSharedData.class);

		if (data == null) {
			beforeProject(project.getGradle());
			data = extensions.getByType(LoomGradleSharedData.class);
		}

		beforeProject(project, data);
		return data;
	}
}
