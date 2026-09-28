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
import java.util.WeakHashMap;

import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.ExtensionContainer;

/**
 * 在项目隔离模式下传递只包含可序列化值的共享数据.
 */
public final class LoomGradleSharedData implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String EXTENSION_NAME = "loomSharedData";

	/**
	 * 构建级扩展容器的访问互斥：Gradle 的扩展容器内部是普通 LinkedHashMap，
	 * 「查名 → 注册」若不互斥，同一构建的并行配置会各自通过查名而撞上抢名异常，甚至同时写入损坏容器.
	 */
	private static final Object REGISTRATION_LOCK = new Object();

	/**
	 * 跨 classloader 场景下的兜底实例：构建级扩展已由另一份 Loom（settings 与 project classpath
	 * 各一份，或多构建复合）注册时，本 classloader 自用一份，保证数据桥在各自 classloader 内自洽.
	 *
	 * <p>键用弱引用：构建存续期间经由 {@code Project#getGradle()} 始终有强引用，身份稳定；构建结束后
	 * 条目可回收，不会在复用的 daemon 中钉住整个构建对象图。
	 */
	private static final Map<Gradle, LoomGradleSharedData> FALLBACK = new WeakHashMap<>();

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
		ExtensionContainer extensions = gradle.getExtensions();

		synchronized (REGISTRATION_LOCK) {
			// 幂等判据只按「名」判断，不能依赖 instanceof：当另一份 Loom 由另一个 classloader 加载时，
			// 同名类并非同一类型，instanceof 会判 false 并重复注册，Gradle 随即抛
			// "Cannot add extension with name 'loomSharedData'"。
			if (extensions.findByName(EXTENSION_NAME) != null) {
				return;
			}

			extensions.add(LoomGradleSharedData.class, EXTENSION_NAME, createSharedData());
		}
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

		// 构建级容器会被同一构建的多个项目（并行配置时即多个线程）同时访问，故整段原子化：
		// 查名 → 注册 → 复查 之间不允许插入其他 Loom 线程，否则会各拿一份实例或撞上抢名异常。
		synchronized (REGISTRATION_LOCK) {
			ExtensionContainer extensions = project.getGradle().getExtensions();
			data = extensions.findByType(LoomGradleSharedData.class);

			if (data == null) {
				beforeProject(project.getGradle());
				data = extensions.findByType(LoomGradleSharedData.class);
			}
		}

		if (data == null) {
			// 按名取到了但类型不符 —— 构建级扩展属于另一个 classloader，既不能复用对方实例，
			// 也不能重复注册；退回本 classloader 的兜底实例（原先 getByType 在此会直接抛错）。
			data = fallback(project.getGradle(), project.getLogger());
		}

		beforeProject(project, data);
		return data;
	}

	/**
	 * 取本 classloader 在给定构建下的兜底实例，不存在则创建并缓存.
	 */
	private static LoomGradleSharedData fallback(Gradle gradle, Logger logger) {
		LoomGradleSharedData data;

		synchronized (FALLBACK) {
			data = FALLBACK.get(gradle);

			if (data != null) {
				return data;
			}

			data = createSharedData();
			FALLBACK.put(gradle, data);
		}

		// 每个 (classloader, 构建) 只记一次：跨 classloader 时数据桥本就不互通，降级必须可诊断
		logger.info("构建级扩展 {} 已由另一个 classloader 的 Loom 占用，改用本 classloader 私有的共享数据实例", EXTENSION_NAME);
		return data;
	}

	private static LoomGradleSharedData createSharedData() {
		return new LoomGradleSharedData(net.fabricmc.loom.LoomGradlePlugin.LOOM_VERSION);
	}
}
