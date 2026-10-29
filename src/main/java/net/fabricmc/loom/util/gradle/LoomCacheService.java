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

import net.fabricmc.loom.util.AsyncCache;
import net.fabricmc.loom.util.cache.CacheEntryLock;

/**
 * build 作用域的共享缓存编排服务.
 *
 * <p>在 JVM 内按 key 去重，并叠加跨进程文件锁，使同一缓存条目的生产在「单 daemon --parallel」与
 * 「多 daemon」下都串行化。读已发布缓存的路径由调用方自身的存在性检查无锁完成，无需经过此服务。
 */
public abstract class LoomCacheService implements BuildService<BuildServiceParameters.None> {
	public static final String NAME = "loomSharedCache";
	private static final String SERVICE_NAME = NAME + ":loader-"
			+ Integer.toUnsignedString(System.identityHashCode(LoomCacheService.class.getClassLoader()), 16);

	// JVM 内 per-key 监视器，保证同一构建（含 --parallel）内同 key 的生产串行
	private final ConcurrentHashMap<String, Object> monitors = new ConcurrentHashMap<>();
	// 同一 Loom classloader 内的子项目共享异步缓存；不同 loader 由服务名隔离，不共享强类型对象。
	private final ConcurrentHashMap<String, AsyncCache<?>> asyncCaches = new ConcurrentHashMap<>();

	/**
	 * 返回当前 Loom classloader 作用域内按名称共享的异步缓存.
	 *
	 * <p>调用方必须保证同一名称始终使用同一种值类型。服务注册名包含 classloader 身份，
	 * 因而不会读取另一插件 classloader 创建的强类型缓存实例。
	 */
	@SuppressWarnings("unchecked")
	public <T> AsyncCache<T> getAsyncCache(String cacheName) {
		return (AsyncCache<T>) asyncCaches.computeIfAbsent(cacheName, key -> new AsyncCache<>());
	}

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
	 * 双检锁形式执行「先检查、再生产」：已就绪时连锁都不取.
	 *
	 * <p><b>为什么需要它</b>：{@link #runExclusive} 是纯串行——后到的调用方必须先拿到锁，
	 * 才能进入临界区做 {@code isUpToDate()} 检查，然后立刻退出。干净环境（CI 首次构建）下
	 * N 个项目配置同一份 Minecraft 时，它们算出同一个 key，于是第 2..N 个项目各自白等
	 * 「第一个项目做完整条 download→remap」的时间，而这个时间远超锁的 1 分钟超时。
	 *
	 * <p>双检锁把等待变成「只等第一个项目生产完」，而不是「每个都排一遍队」：
	 * <ol>
	 *   <li>锁外检查：已就绪 → 直接跑 {@code action} 之外的回退分支，不取锁；</li>
	 *   <li>未就绪 → 取锁（可能等前一个项目生产完）；</li>
	 *   <li>锁内<b>重检</b>：若前一个项目已经把产物做好，就跳过生产。这是关键一步：
	 *       没有它，第 2..N 个项目拿到锁后会把同一份产物再重建一遍。</li>
	 * </ol>
	 *
	 * <p>两次检查都必须是纯只读的存在性/脏标志判断（调用方保证），不得在锁外写共享缓存。
	 *
	 * @param lockRoot     锁文件目录
	 * @param key          互斥 key
	 * @param timeout      等待跨进程锁的超时时间
	 * @param upToDate     只读就绪判定；锁内会被再次调用
	 * @param onUpToDate   已就绪时的动作（跑在锁外，应做「读已发布产物」这类无副作用的事）
	 * @param produce      需要生产时的动作（跑在锁内）
	 */
	public void runExclusiveChecking(Path lockRoot, String key, Duration timeout,
			Callable<Boolean> upToDate, Callable<Void> onUpToDate, Callable<Void> produce) throws Exception {
		if (Boolean.TRUE.equals(upToDate.call())) {
			// 快路径：产物已就绪，全程不取锁（与 runExclusive 的「只在需要生产时才取锁」一致）
			onUpToDate.call();
			return;
		}

		final Object monitor = monitors.computeIfAbsent(key, k -> new Object());

		synchronized (monitor) {
			CacheEntryLock.withLock(lockRoot, key, timeout, () -> {
				// 锁内重检：前一个持锁者可能刚把产物做完，此时不必再产一遍
				if (Boolean.TRUE.equals(upToDate.call())) {
					onUpToDate.call();
					return null;
				}

				return produce.call();
			});
		}
	}

	/**
	 * 共享缓存锁的默认等待超时.
	 *
	 * <p>可通过系统属性 {@code fabric.loom.lock.timeout.minutes} 覆盖
	 * （在 gradle.properties 中写 {@code systemProp.fabric.loom.lock.timeout.minutes=10}）。
	 * 未配置时：CI 上 10 分钟（干净环境首次构建要下载、解压、重映射整份 MC，
	 * 实测在 CI runner 上远超 1 分钟；原先按「CI 上不太可能解锁」取的 1 分钟正是
	 * 多项目并发配置时超时的直接原因），本地 30 分钟（容忍首次冷构建的慢速下载，
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
			return Duration.ofMinutes(10);
		}

		return Duration.ofMinutes(30);
	}

	/**
	 * mod 依赖重映射写 LOCAL 共享 {@code remapped_mods} 缓存时的互斥 key（按根项目目录 + mappings 标识分）.
	 *
	 * <p>同一 checkout 的多个并发构建（如同时跑 client 与 server）共享该 key、串行化写入；
	 * 不同 checkout 根目录不同 → key 不同 → 互不阻塞。当前唯一的取锁点是
	 * {@code SourceRemapper.remapAll}（sources 缓存）；二进制产出已改由 L3 任务持有产出目录、
	 * 由任务图串行化，不再使用本 key。key 由本助手统一生成，避免两处写法漂移。
	 */
	public static String modDepsKey(File rootDir, String mappingsIdentifier) {
		return "mod-deps:" + rootDir.getAbsolutePath() + ":" + mappingsIdentifier;
	}

	/**
	 * 返回当前 Loom classloader 对应的缓存服务.
	 *
	 * <p>同一 Gradle root 可能通过约定插件和直接插件加载多份 Loom。注册名包含当前
	 * classloader 身份，避免从另一 loader 取得不可强转的服务实现；同一 loader 的多个
	 * 子项目仍共享服务和内存缓存。跨 daemon 正确性继续由 {@link CacheEntryLock} 保证。
	 */
	public static Provider<LoomCacheService> get(Project project) {
		return project.getGradle().getSharedServices().registerIfAbsent(SERVICE_NAME, LoomCacheService.class, spec -> { });
	}
}
