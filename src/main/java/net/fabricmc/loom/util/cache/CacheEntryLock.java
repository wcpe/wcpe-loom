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

package net.fabricmc.loom.util.cache;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import net.fabricmc.loom.util.Checksum;

/**
 * 跨进程的按 key 细粒度文件锁工具.
 *
 * <p>每个缓存条目对应一把独立的锁文件，多个 Gradle daemon 进程在生产同一缓存条目时
 * 通过该锁串行化，避免重复或损坏的写入。读已发布缓存的路径不应使用此工具（读路径无锁）。
 */
public final class CacheEntryLock {
	// 本线程已持有的锁文件（绝对路径）→ 重入层数。只在当前线程内可见，因此不会削弱任何互斥：
	// 跨线程仍靠 NIO 文件锁（OverlappingFileLockException 分支继续轮询），跨进程仍靠文件锁本身。
	// 用锁文件路径而不是 key 作身份：不同 lockRoot 下同名 key 指向不同锁文件，本就不该算重入。
	private static final ThreadLocal<Map<String, Integer>> HELD_LOCKS = ThreadLocal.withInitial(HashMap::new);

	private CacheEntryLock() {
	}

	/**
	 * 在按 key 取得的跨进程文件锁保护下执行 {@code action}.
	 *
	 * <p>取得锁后会把持有者信息（PID、工作目录、时间）写入旁文件 {@code <hash>.lock.owner}，
	 * 供其它等待进程读取并播报「锁被谁持有」；释放锁时删除旁文件。持有者信息不写入锁文件本体，
	 * 因为 Windows 的文件锁是强制锁，其它进程读被锁文件会被拒绝。
	 *
	 * <p><b>可重入性</b>：同一线程已持有同一锁文件时，内层调用<b>不再抢锁</b>，直接执行
	 * {@code action}，退出时只递减重入计数。仅同线程同锁文件可重入；跨线程、跨进程仍完全互斥。
	 *
	 * @param lockRoot 锁文件所在目录，会按需创建
	 * @param key      缓存条目的逻辑 key，内部以其 sha256 命名锁文件
	 * @param timeout  等待锁的最长时间，超时抛出 {@link GradleException}
	 * @param action   取得锁后执行的动作
	 * @param <T>      动作返回值类型
	 * @return action 的返回值
	 * @throws Exception action 自身抛出的异常会原样向上传播
	 */
	public static <T> T withLock(Path lockRoot, String key, Duration timeout, Callable<T> action) throws Exception {
		// 锁文件长期复用，不在结束时删除，避免「删文件」与「持锁」之间的竞态
		final Path lockFile = lockRoot.resolve(Checksum.of(key).sha256().hex() + ".lock");
		final Path ownerFile = lockFile.resolveSibling(lockFile.getFileName() + ".owner");
		// 以绝对规范化路径作为重入身份，避开 "a/../b" 与相对路径对不上同一文件的问题
		final String lockId = lockFile.toAbsolutePath().normalize().toString();
		final Map<String, Integer> held = HELD_LOCKS.get();

		if (held.containsKey(lockId)) {
			// 重入：外层已持有该文件锁，内层不得再碰 channel/lock，只把层数 +1
			held.put(lockId, held.get(lockId) + 1);

			try {
				return action.call();
			} finally {
				// 递归只减计数，绝不删旁文件、绝不 release（那些归最外层）
				decrement(held, lockId);
			}
		}

		// createDirectories 放在重入判定之后：内层路径完全不做 IO，与「取得锁后直接执行」的语义一致
		Files.createDirectories(lockRoot);

		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
			FileLock lock = acquireFileLockWithTimeout(channel, key, ownerFile, timeout);
			writeOwnerInfo(ownerFile, key);
			// 必须在拿到文件锁之后才记账：抢锁失败/超时抛异常时不会留下脏计数，也就不会被误判成重入
			held.put(lockId, 1);

			try {
				return action.call();
			} finally {
				// 先清本线程的持有记录，再删旁文件、最后 release。可重入性只靠本线程的计数，
				// 而本线程此刻尚在 finally 里，不可能并发地重入同一个 key，所以这个顺序是安全的。
				held.remove(lockId);

				// 本线程已不持任何锁时把整个 map 从 ThreadLocal 摘掉，
				// 避免长命线程（Gradle daemon 的 worker 线程）永久挂着一个空 map
				if (held.isEmpty()) {
					HELD_LOCKS.remove();
				}

				// 先删持有者旁文件再释放锁：等待者只在拿不到锁时才读旁文件，
				// 此顺序保证旁文件存在时其内容必出自当前（或刚释放的）持有者，不会长期误导
				try {
					Files.deleteIfExists(ownerFile);
				} catch (IOException e) {
					// 删除失败不影响正确性（下一个持有者会覆盖），忽略
				}

				if (lock.isValid()) {
					lock.release();
				}
			}
		}
	}

	// 重入计数递减一层；归零时直接移除条目.
	//
	// 不用 merge(key, -1, Integer::sum)：那样归零后条目会以值 0 留下，而且一旦被减成负数，
	// 条目就永远清不掉，后续调用会把它误判为「已持有」而不取锁。
	private static void decrement(Map<String, Integer> held, String lockId) {
		final Integer depth = held.get(lockId);

		if (depth == null || depth <= 1) {
			held.remove(lockId);
		} else {
			held.put(lockId, depth - 1);
		}
	}

	// 带超时的 NIO 文件锁获取：通过轮询 tryLock() 实现超时等待
	@SuppressWarnings("BusyWait")
	private static FileLock acquireFileLockWithTimeout(FileChannel channel, String key, Path ownerFile, Duration timeout) throws IOException {
		final long timeoutMs = timeout.toMillis();
		final Logger logger = Logging.getLogger("loom_cacheEntryLock");
		long waitedMs = 0;

		while (true) {
			FileLock lock = null;

			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException e) {
				// 同一 JVM 内已有线程持有/正在获取该文件的重叠锁；视为竞争，继续轮询等待
			}

			if (lock != null) {
				return lock;
			}

			if (waitedMs == 0) {
				logger.lifecycle("正在等待共享缓存锁（key={}）{}", key, describeOwner(ownerFile));
			}

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("等待共享缓存锁时被中断（key=%s）".formatted(key), e);
			}

			waitedMs += 100;

			if (waitedMs >= 1000 * 60 && waitedMs % (1000 * 60) == 0L) {
				logger.lifecycle("已等待共享缓存锁 {} 分钟（key={}）{}", waitedMs / 1000 / 60, key, describeOwner(ownerFile));

				if (waitedMs == 1000 * 60 * 2) {
					logger.lifecycle("提示：若持有锁的构建正在缓慢下载 Minecraft，可在 gradle.properties 配置镜像（如 loom_libraries_base / loom_version_manifests / loom_resources_base）以加速；持有进程若已无响应可按上述 PID 手动结束。");
				}
			}

			if (waitedMs >= timeoutMs) {
				throw new GradleException("等待共享缓存锁超时（key=%s, 超时=%dms）%s".formatted(key, timeoutMs, describeOwner(ownerFile)));
			}
		}
	}

	// 把持有者信息写入旁文件：PID、工作目录、获取时间。写失败不影响锁语义，仅损失诊断信息
	private static void writeOwnerInfo(Path ownerFile, String key) {
		try {
			final String info = "pid=%d, dir=%s, key=%s, since=%s".formatted(
					ProcessHandle.current().pid(),
					System.getProperty("user.dir", "?"),
					key,
					LocalTime.now().withNano(0));
			Files.write(ownerFile, info.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Logging.getLogger("loom_cacheEntryLock").debug("写入锁持有者信息失败（仅影响诊断）", e);
		}
	}

	// 读取持有者旁文件生成描述；读不到（持有者刚释放/写失败）时返回空串
	private static String describeOwner(Path ownerFile) {
		try {
			if (Files.exists(ownerFile)) {
				return "，当前持有者：[" + Files.readString(ownerFile, StandardCharsets.UTF_8).trim() + "]";
			}
		} catch (IOException e) {
			// 旁文件读取竞态（持有者正在删除）等情况，忽略即可
		}

		return "...";
	}
}
