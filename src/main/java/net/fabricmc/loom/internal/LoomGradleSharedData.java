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
import org.jspecify.annotations.Nullable;

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
		registerSharedData(gradle.getExtensions());
	}

	/**
	 * 在项目级扩展容器中登记共享数据，幂等.
	 *
	 * <p>项目级容器同样会被并行配置的多线程访问（同一构建的多个项目、或同一项目的多个配置线程），
	 * 故判据与注册整体加锁，与 {@link #get(Project)} 使用同一把锁。
	 *
	 * @param project 目标项目
	 * @param data 本 classloader 的共享数据实例
	 */
	public static void beforeProject(Project project, LoomGradleSharedData data) {
		ExtensionContainer extensions = project.getExtensions();

		synchronized (REGISTRATION_LOCK) {
			// 幂等判据只按「名」判断，不能依赖 instanceof：当另一份 Loom 由另一个 classloader 加载时，
			// 同名类并非同一类型，instanceof 会判 false 并重复注册，Gradle 随即抛
			// "Cannot add extension with name 'loomSharedData'"。
			if (extensions.findByName(EXTENSION_NAME) != null) {
				return;
			}

			addSharedData(extensions, data);
		}
	}

	/**
	 * 在构建级扩展容器中登记本 classloader 的共享数据，幂等.
	 *
	 * <p>只按「名」判断是否已注册，理由同 {@link #beforeProject(Project, LoomGradleSharedData)}；
	 * 名已被占用时静默跳过——容器里已有同名扩展本就是期望的终态，本 classloader 真正需要数据时
	 * 会在 {@link #get(Project)} 中降级并输出可诊断的日志。
	 *
	 * @param extensions 目标扩展容器，通常是 {@link Gradle#getExtensions()}
	 */
	static void registerSharedData(ExtensionContainer extensions) {
		synchronized (REGISTRATION_LOCK) {
			if (extensions.findByName(EXTENSION_NAME) != null) {
				return;
			}

			addSharedData(extensions, createSharedData());
		}
	}

	public static LoomGradleSharedData get(Project project) {
		ExtensionContainer extensions = project.getGradle().getExtensions();
		LoomGradleSharedData data = project.getExtensions().findByType(LoomGradleSharedData.class);

		if (data != null) {
			return data;
		}

		// 构建级容器会被同一构建的多个项目（并行配置时即多个线程）同时访问，故整段原子化：
		// 查名 → 注册 → 复查 之间不允许插入其他 Loom 线程，否则会各拿一份实例或撞上抢名异常。
		synchronized (REGISTRATION_LOCK) {
			data = extensions.findByType(LoomGradleSharedData.class);

			if (data == null) {
				beforeProject(project.getGradle());
				data = extensions.findByType(LoomGradleSharedData.class);
			}
		}

		if (data == null) {
			// 按名取到了但类型不符 —— 构建级扩展属于另一个 classloader，既不能复用对方实例，
			// 也不能重复注册；退回本 classloader 的兜底实例（原先 getByType 在此会直接抛错）。
			data = fallback(project.getGradle(), extensions.findByName(EXTENSION_NAME), project.getLogger());
		}

		beforeProject(project, data);
		return data;
	}

	/**
	 * 注册共享数据，并把「名被另一份 classloader 抢占」从崩溃降级为跳过.
	 *
	 * <p>{@link ExtensionContainer#add} 只在「查名」与「注册」之间没有其他写入者时才安全。同一 classloader
	 * 内由 {@link #REGISTRATION_LOCK} 保证；另一个 classloader 的 Loom 有它自己的（静态字段）锁，管不到这里，
	 * 因此仍可能恰好抢在两步之间完成注册，此时 {@code add} 抛 {@link IllegalArgumentException}。
	 * 按名复查可以把「被抢名」与「入参非法」区分开：被抢名时跳过注册即可（容器里已有别人的同名扩展），
	 * 调用方随后会走兜底路径并给出可诊断的日志；入参非法则原样抛出，避免掩盖真实错误。
	 *
	 * @param extensions 目标扩展容器
	 * @param data 待注册的共享数据
	 * @return 本次调用是否完成了注册
	 */
	private static boolean addSharedData(ExtensionContainer extensions, LoomGradleSharedData data) {
		try {
			extensions.add(LoomGradleSharedData.class, EXTENSION_NAME, data);
			return true;
		} catch (IllegalArgumentException e) {
			if (extensions.findByName(EXTENSION_NAME) == null) {
				throw e;
			}

			return false;
		}
	}

	/**
	 * 取本 classloader 在给定构建下的兜底实例，不存在则创建并缓存.
	 *
	 * @param gradle 目标构建，用作兜底实例的缓存键（弱引用，构建结束后即可回收）
	 * @param occupant 当前占用构建级扩展名的对象，仅用于诊断输出，可能为 {@code null}
	 * @param logger 用于输出降级诊断的日志器
	 * @return 本 classloader 的兜底实例
	 */
	private static LoomGradleSharedData fallback(Gradle gradle, @Nullable Object occupant, Logger logger) {
		LoomGradleSharedData data;

		synchronized (FALLBACK) {
			data = FALLBACK.get(gradle);

			if (data != null) {
				return data;
			}

			data = createSharedData();
			FALLBACK.put(gradle, data);
		}

		// 每个 (classloader, 构建) 只记一次：跨 classloader 时数据桥本就不互通，降级必须可诊断，
		// 故用默认控制台（LIFECYCLE）可见的 warn，而不是默认看不到的 info。
		// 注意消息正文里不能再出现 {}，否则会被日志器当成参数占位符。
		logger.warn("构建级扩展 {} 已被其他 classloader 的对象占用：{}。本 classloader 改用私有的共享数据实例，"
				+ "跨 classloader 的数据桥不互通；常见成因是 Loom 被两个 classloader 各加载了一份"
				+ "（settings 与项目 classpath、buildSrc/约定插件与 plugins 块、复合构建），"
				+ "如出现数据缺失请统一 Loom 的加载来源。", EXTENSION_NAME, describe(occupant));
		return data;
	}

	/**
	 * 描述占用构建级扩展名的对象，用于诊断输出.
	 */
	private static String describe(@Nullable Object occupant) {
		if (occupant == null) {
			return "<未知>";
		}

		return occupant.getClass().getName() + "（classloader: " + occupant.getClass().getClassLoader() + "）";
	}

	private static LoomGradleSharedData createSharedData() {
		return new LoomGradleSharedData(net.fabricmc.loom.LoomGradlePlugin.LOOM_VERSION);
	}
}
