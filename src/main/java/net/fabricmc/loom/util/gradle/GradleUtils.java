/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.util.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.util.function.Consumer;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomCompanionGradlePlugin;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;

public final class GradleUtils {
	private GradleUtils() {
	}

	// For some crazy reason afterEvaluate is still invoked when the configuration fails
	public static void afterSuccessfulEvaluation(Project project, Runnable afterEvaluate) {
		project.afterEvaluate(p -> {
			if (p.getState().getFailure() != null) {
				// Let gradle handle the failure
				return;
			}

			afterEvaluate.run();
		});
	}

	public static void allLoomProjects(Gradle gradle, Consumer<Project> consumer) {
		gradle.allprojects(project -> {
			if (isLoomProject(project)) {
				consumer.accept(project);
			}
		});
	}

	public static boolean isLoomProject(Project project) {
		return LoomGradlePlugin.isApplied(project);
	}

	public static boolean isLoomCompanionProject(Project project) {
		return LoomCompanionGradlePlugin.isApplied(project);
	}

	public static Provider<Boolean> getBooleanPropertyProvider(Project project, String key) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.isProjectIsolationActive()) {
			return project.getProviders().gradleProperty(key).map(Boolean::parseBoolean);
		}

		// Works around https://github.com/gradle/gradle/issues/23572
		return project.provider(() -> {
			final Object value = project.findProperty(key);

			if (value instanceof String str) {
				try {
					return Boolean.parseBoolean(str);
				} catch (final IllegalArgumentException ex) {
					return false;
				}
			} else {
				return false;
			}
		});
	}

	public static Provider<Integer> getIntegerPropertyProvider(Project project, String key) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.isProjectIsolationActive()) {
			// Project.findProperty 在属性未定义时会沿项目层级向父项目查找，隔离项目下会被判定为
			// 跨项目访问（cannot dynamically look up a property in the parent project）。
			// 与 getBooleanPropertyProvider / getProperty 保持一致，改用 gradleProperty。
			return project.getProviders().gradleProperty(key).map(value -> {
				try {
					return Integer.parseInt(value);
				} catch (final NumberFormatException ex) {
					throw new IllegalArgumentException("Property " + key + " must be an integer", ex);
				}
			});
		}

		return project.provider(() -> {
			final Object value = project.findProperty(key);

			if (value == null) {
				return null;
			}

			try {
				return Integer.parseInt(value.toString());
			} catch (final NumberFormatException ex) {
				throw new IllegalArgumentException("Property " + key + " must be an integer", ex);
			}
		});
	}

	public static boolean getBooleanProperty(Project project, String key) {
		return getBooleanProperty(project, key, false);
	}

	public static boolean getBooleanProperty(Project project, String key, boolean defaultValue) {
		return getBooleanPropertyProvider(project, key).getOrElse(defaultValue);
	}

	@Nullable
	public static Object getProperty(Project project, String key) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.isProjectIsolationActive()) {
			final String gradleProperty = project.getProviders().gradleProperty(key).getOrNull();

			if (gradleProperty != null) {
				return gradleProperty;
			}

			// gradleProperty 只覆盖 GRADLE_USER_HOME 与**根项目**的 gradle.properties，
			// 读不到子项目目录下那份。而子项目把自己的属性写在各自的 gradle.properties 是
			// 合法且常见的用法（本仓库的 Forge 车道就靠 <sub>/gradle.properties 里的
			// loom.platform=forge 声明平台）。早期这里直接返回 null，导致 platform 退化为
			// 默认的 FABRIC → isForgeLike() 为 false → forge/neoforge 相关 configuration
			// 根本不注册，最终表现为项目执行 add("forge", ...) 时报
			// 「Configuration with name 'forge' not found」。
			//
			// 刻意不用 project.findProperty()：它在属性未定义时会沿层级向父项目查找，
			// 隔离项目下会被判定为跨项目访问（cannot dynamically look up a property in
			// the parent project）。这里只读**本项目目录**下的文件，不产生任何跨项目读取。
			return readOwnGradleProperties(project, key);
		}

		return project.findProperty(key);
	}

	/**
	 * 从**本项目目录**的 {@code gradle.properties} 读取属性；不存在该文件时返回 {@code null}.
	 *
	 * <p>只读本项目目录，不向父项目回溯，因此在隔离项目模式下是安全的。
	 * 文件内容变化会进入配置缓存输入（见 {@link #configurationInputFile}），
	 * 与 Gradle 自身对该文件的处理一致。
	 */
	@Nullable
	private static String readOwnGradleProperties(Project project, String key) {
		final File propertiesFile = new File(project.getProjectDir(), "gradle.properties");

		if (!propertiesFile.isFile()) {
			return null;
		}

		configurationInputFile(project, propertiesFile);

		final Properties properties = new Properties();

		try (InputStream input = Files.newInputStream(propertiesFile.toPath())) {
			properties.load(input);
		} catch (IOException e) {
			// 读不出来时按「没有这个属性」处理，与 gradleProperty 未命中的语义保持一致，
			// 不把可选的属性读取升级成构建失败。
			return null;
		}

		return properties.getProperty(key);
	}

	// A hack to include the given file in the configuration cache input
	// this ensures that configuration cache is invalidated when the file changes
	public static File configurationInputFile(Project project, File file) {
		final RegularFileProperty property = project.getObjects().fileProperty();
		property.set(file);
		return property.getAsFile().get();
	}

	/**
	 * {@return 当前项目是否为根项目}.
	 *
	 * <p>通过项目隔离视图比较路径，而不是直接比较 {@code getRootProject()} 的对象引用：
	 * 隔离项目模式下跨项目模型访问会触发校验错误，而 {@code IsolatedProject} 只暴露路径等标识信息。
	 */
	public static boolean isRootProject(Project project) {
		return project.getPath().equals(project.getIsolated().getRootProject().getPath());
	}
}
