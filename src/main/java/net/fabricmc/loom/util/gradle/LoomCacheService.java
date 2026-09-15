/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import org.gradle.api.Project;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import net.fabricmc.loom.util.cache.CacheEntryLock;

/**
 * build 作用域的共享缓存编排服务.
 *
 * <p>在 JVM 内按 key 去重，并叠加跨进程文件锁，使同一缓存条目的生产在「单 daemon --parallel」与
 * 「多 daemon」下都串行化。读已发布缓存的路径由调用方自身的存在性检查无锁完成，无需经过此服务。
 */
public abstract class LoomCacheService implements BuildService<BuildServiceParameters.None> {
	public static final String NAME = "loomSharedCache";

	// JVM 内 per-key 监视器，保证同一构建（含 --parallel）内同 key 的生产串行
	private final ConcurrentHashMap<String, Object> monitors = new ConcurrentHashMap<>();

	/**
	 * 在按 key 取得的「JVM 监视器 + 跨进程文件锁」保护下执行一个互斥动作.
	 *
	 * <p>读路径应由调用方自身的存在性检查无锁完成；仅在判定需要生产时才调用本方法取锁。
	 * 各 provider 以 {@code shouldRefreshOutputs} / {@code Files.exists} 作为无锁快路径，
	 * 配合产物的原子发布（写入即完整），保证读方不会看到半成品。
	 *
	 * @param lockRoot 锁文件目录
	 * @param key      互斥 key
	 * @param timeout  等待跨进程锁的超时时间
	 * @param action   取得锁后执行的动作
	 */
	public void runExclusive(Path lockRoot, String key, Duration timeout, Callable<Void> action) throws Exception {
		final Object monitor = monitors.computeIfAbsent(key, k -> new Object());

		synchronized (monitor) {
			CacheEntryLock.withLock(lockRoot, key, timeout, action);
		}
	}

	/**
	 * 共享缓存锁的默认等待超时.
	 *
	 * <p>可通过系统属性 {@code fabric.loom.lock.timeout.minutes} 覆盖
	 * （在 gradle.properties 中写 {@code systemProp.fabric.loom.lock.timeout.minutes=10}）。
	 * 未配置时：CI 上 1 分钟（不太可能解锁），本地 30 分钟（容忍首次冷构建的慢速下载，
	 * 但避免在异常持有者上无限等待——配合等待日志中的持有者 PID 可手动处置）。
	 */
	public static Duration defaultTimeout() {
		final String override = System.getProperty("fabric.loom.lock.timeout.minutes");

		if (override != null) {
			try {
				return Duration.ofMinutes(Long.parseLong(override.trim()));
			} catch (NumberFormatException e) {
				Logging.getLogger(LoomCacheService.class).warn("无效的 fabric.loom.lock.timeout.minutes 值：{}，使用默认超时", override);
			}
		}

		if (System.getenv("CI") != null) {
			return Duration.ofMinutes(1);
		}

		return Duration.ofMinutes(30);
	}

	/**
	 * mod 依赖重映射写 LOCAL 共享 {@code remapped_mods} 缓存时的互斥 key（按根项目目录 + mappings 标识分）.
	 *
	 * <p>同一 checkout 的多个并发构建（如同时跑 client 与 server）共享该 key、串行化写入；
	 * 不同 checkout 根目录不同 → key 不同 → 互不阻塞。两处写入点（ModProcessor / SourceRemapper）
	 * 用同一助手生成 key，避免写法漂移。
	 */
	public static String modDepsKey(File rootDir, String mappingsIdentifier) {
		return "mod-deps:" + rootDir.getAbsolutePath() + ":" + mappingsIdentifier;
	}

	public static Provider<LoomCacheService> get(Project project) {
		return project.getGradle().getSharedServices().registerIfAbsent(NAME, LoomCacheService.class, spec -> { });
	}
}
