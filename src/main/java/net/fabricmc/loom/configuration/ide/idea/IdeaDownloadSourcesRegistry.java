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
import java.util.WeakHashMap;
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

	/**
	 * 构建级扩展容器的访问互斥：Gradle 的扩展容器内部是普通 LinkedHashMap，
	 * 「查名 → 注册」若不互斥，并行配置（各项目的 afterEvaluate）会各自通过查名而后双双注册，
	 * 慢的一方吃到抢名异常，登记表还可能分裂成两份.
	 */
	private static final Object REGISTRATION_LOCK = new Object();

	/**
	 * 跨 classloader 场景下的兜底登记表：构建级扩展已由另一份 Loom（settings 与 project classpath
	 * 各一份，或多构建复合）注册时，本 classloader 自用一份，保证本 classloader 内登记与读取仍然一致.
	 *
	 * <p>键用弱引用：构建存续期间经由 {@code Project#getGradle()} 始终有强引用，身份稳定；
	 * 构建结束后条目可回收，不会在复用的 daemon 中钉住整个构建对象图。
	 */
	private static final Map<Gradle, IdeaDownloadSourcesRegistry> FALLBACK = new WeakHashMap<>();

	private final Map<String, Set<String>> taskPathsByNotation = new ConcurrentHashMap<>();

	public static IdeaDownloadSourcesRegistry get(Project project) {
		final Gradle gradle = project.getGradle();
		final ExtensionContainer extensions = ((ExtensionAware) gradle).getExtensions();

		synchronized (REGISTRATION_LOCK) {
			final Object existing = extensions.findByName(EXTENSION_NAME);

			// 幂等判据只按「名」判断，不能依赖 instanceof：另一份 Loom 由另一个 classloader 加载时，
			// 同名类并非同一类型，instanceof 会判 false 并重复注册，Gradle 随即抛
			// "Cannot add extension with name 'loomIdeaDownloadSources'"。
			if (existing == null) {
				return register(project, extensions);
			}

			if (existing instanceof IdeaDownloadSourcesRegistry registry) {
				return registry;
			}

			// 名字已被另一份 classloader 的登记表占用：既不能复用对方实例（类型不同），也不能重复注册。
			return fallback(project, existing);
		}
	}

	/**
	 * 注册本 classloader 的登记表，并把「名被另一份 classloader 抢占」从崩溃降级为兜底.
	 *
	 * <p>{@link ExtensionContainer#add} 只在「查名」与「注册」之间没有其他写入者时才安全。同一 classloader
	 * 内由 {@link #REGISTRATION_LOCK} 保证；另一个 classloader 的 Loom 有它自己的（静态字段）锁，管不到这里，
	 * 因此仍可能恰好抢在两步之间完成注册，此时 {@code add} 抛 {@link IllegalArgumentException}。
	 *
	 * @param project 目标项目，用于取构建与日志器
	 * @param extensions 构建级扩展容器
	 * @return 本 classloader 可用的登记表
	 */
	private static IdeaDownloadSourcesRegistry register(Project project, ExtensionContainer extensions) {
		try {
			final IdeaDownloadSourcesRegistry registry = new IdeaDownloadSourcesRegistry();
			extensions.add(IdeaDownloadSourcesRegistry.class, EXTENSION_NAME, registry);
			return registry;
		} catch (IllegalArgumentException e) {
			final Object winner = extensions.findByName(EXTENSION_NAME);

			if (winner == null) {
				// 名并未被占用，异常来自入参非法，原样抛出以免掩盖真实错误
				throw e;
			}

			if (winner instanceof IdeaDownloadSourcesRegistry registry) {
				return registry;
			}

			return fallback(project, winner);
		}
	}

	/**
	 * 取本 classloader 在给定构建下的兜底登记表，不存在则创建并缓存.
	 *
	 * @param project 目标项目，用于取构建（兜底缓存的键）与日志器
	 * @param occupant 当前占用构建级扩展名的对象，仅用于诊断输出
	 * @return 本 classloader 的兜底登记表
	 */
	private static IdeaDownloadSourcesRegistry fallback(Project project, Object occupant) {
		final Gradle gradle = project.getGradle();
		IdeaDownloadSourcesRegistry registry;

		synchronized (FALLBACK) {
			registry = FALLBACK.get(gradle);

			if (registry != null) {
				return registry;
			}

			registry = new IdeaDownloadSourcesRegistry();
			FALLBACK.put(gradle, registry);
		}

		// 每个 (classloader, 构建) 只记一次：跨 classloader 时登记表本就不互通，降级必须可诊断，
		// 故用默认控制台（LIFECYCLE）可见的 warn，而不是默认看不到的 info。
		// 注意消息正文里不能再出现 {}，否则会被日志器当成参数占位符。
		project.getLogger().warn("构建级扩展 {} 已被其他 classloader 的对象占用：{}。本 classloader 改用私有的登记表，"
				+ "跨 classloader 的登记不互通；常见成因是 Loom 被两个 classloader 各加载了一份"
				+ "（settings 与项目 classpath、buildSrc/约定插件与 plugins 块、复合构建），"
				+ "如 IDE 的下载源码钩子缺少其它项目登记的任务路径，请统一 Loom 的加载来源。",
				EXTENSION_NAME, occupant.getClass().getName() + "（classloader: " + occupant.getClass().getClassLoader() + "）");
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
