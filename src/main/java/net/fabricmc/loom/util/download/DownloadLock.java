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

package net.fabricmc.loom.util.download;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个下载目标的跨进程互斥锁.
 *
 * <h2>锁文件里记的是什么</h2>
 *
 * <p>锁文件 {@code <name>.lock} 用 {@link Files#createFile} 独占创建，创建成功后立刻写入持有者信息
 * （PID、进程启动时刻、主机名、token）。判活全部基于这份信息：<b>锁文件存在</b>只说明「曾经有人持有」，
 * 与「现在有人在写」是两件事——改造前把两者当成同一件事，于是崩溃残留会让下一次构建白下载一遍，
 * 而真正并发的第二个写入者却直接被判失败。
 *
 * <h2>残留与「正在写」怎么区分</h2>
 *
 * <ul>
 *   <li>持有者在本机、PID 已不存在（或进程启动时刻与记录不符，说明 PID 被复用）→ <b>残留</b>，立即接管；</li>
 *   <li>持有者在本机、PID 还活着且不是本 JVM → <b>正在写</b>，等待；</li>
 *   <li>持有者是本 JVM：本 classloader 的持有表里有它 → 另一个线程在写，等待；表里没有、且锁文件已存在
 *       超过 {@code SAME_JVM_GRACE} → 本 JVM 的遗留锁（收尾删除失败或另一份 Loom classloader 已放手），接管；</li>
 *   <li>跨主机（共享缓存盘 / NFS）或持有者信息还没写完 → 无法判活，按锁文件年龄推断：
 *       刚创建视为正在写，超过 {@code EMPTY_CONTENT_GRACE} / {@code FOREIGN_STALE_AFTER} 视为残留.</li>
 * </ul>
 *
 * <h2>为什么等待超时也接管</h2>
 *
 * <p>这把锁只负责去重与礼仪，<b>正确性不依赖它</b>：下载写入本次尝试专用的唯一临时文件，
 * 写完整后原子落位（{@link net.fabricmc.loom.util.cache.AtomicFiles}），因此两个写入者同时存在
 * 最多重复一次网络传输，不会产出半成品或损坏的文件。既然如此，「持有者挂死也不让构建失败」
 * 就比「等不到锁就抛异常」更合适。
 */
public final class DownloadLock implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(DownloadLock.class);

	// 本 JVM（本 classloader）当前持有的锁：用于把「同进程另一线程在写」与「本进程的遗留锁」区分开
	private static final ConcurrentMap<Path, String> HELD = new ConcurrentHashMap<>();

	// 持有者信息还没写出来时的等待窗口（创建与写信息之间的窗口在毫秒级）
	private static final Duration EMPTY_CONTENT_GRACE = Duration.ofSeconds(2);

	// 持有者在本 JVM 但不在本 classloader 的持有表里时的等待窗口
	private static final Duration SAME_JVM_GRACE = Duration.ofSeconds(10);

	// 跨主机（共享缓存盘）无法判活时的陈旧阈值
	private static final Duration FOREIGN_STALE_AFTER = Duration.ofMinutes(30);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

	private static final String LOCAL_HOST = localHostName();

	private final Path lockFile;
	private final Path key;
	private final String token;
	private final boolean tookOverLiveLock;
	private final boolean waited;

	private DownloadLock(Path lockFile, Path key, String token, boolean tookOverLiveLock, boolean waited) {
		this.lockFile = lockFile;
		this.key = key;
		this.token = token;
		this.tookOverLiveLock = tookOverLiveLock;
		this.waited = waited;
	}

	/**
	 * 取得 {@code lockFile} 的独占权，必要时接管残留锁.
	 *
	 * @param lockFile 锁文件路径，父目录会被创建
	 * @param timeout  等待「正在写」的持有者释放锁的最长时间，超时后强制接管
	 * @return 已持有的锁，调用方必须在 finally / try-with-resources 中关闭
	 * @throws IOException 无法创建或接管锁文件时
	 */
	public static DownloadLock acquire(Path lockFile, Duration timeout) throws IOException {
		final Path key = lockFile.toAbsolutePath().normalize();
		final String token = UUID.randomUUID().toString();
		final long timeoutNanos = timeout.toNanos();
		long deadline = System.nanoTime() + timeoutNanos;
		boolean forced = false;
		boolean announcedWait = false;
		boolean waited = false;

		Files.createDirectories(key.getParent());

		while (true) {
			if (tryCreate(lockFile, token)) {
				HELD.put(key, token);
				return new DownloadLock(lockFile, key, token, forced, waited);
			}

			waited = true;
			final Snapshot snapshot = Snapshot.read(lockFile);

			if (snapshot == null) {
				// 判活期间持有者刚好放手，重来一次即可
				continue;
			}

			if (holderOf(snapshot, key) == Holder.STALE) {
				LOGGER.debug("接管残留的下载锁 {}", lockFile);

				if (!deleteSnapshot(lockFile, snapshot)) {
					pause();
				}

				continue;
			}

			if (System.nanoTime() >= deadline) {
				if (!forced) {
					// 只报一次：下面可能还要重试几轮才能真的删掉别人的锁，每轮都 warn 就是刷屏
					LOGGER.warn("等待下载锁超时（{}），接管 {} 继续下载；内容由唯一临时文件 + 原子落位保证完整", timeout, lockFile);
					forced = true;
				}

				// 顺延一个超时周期：抢不到就继续按正常节奏等，而不是每 50ms 重报一次
				deadline = System.nanoTime() + timeoutNanos;

				if (!deleteSnapshot(lockFile, snapshot)) {
					pause();
				}

				continue;
			}

			if (!announcedWait) {
				LOGGER.info("正在等待另一个进程/线程下载 {}（持有者：{}）", key.getFileName(), snapshot.describeOwner());
				announcedWait = true;
			}

			pause();
		}
	}

	/**
	 * {@return 本次取得锁是否属于「持有者看起来还活着但仍被接管」}.
	 *
	 * <p>调用方据此可以加一条提示；下载本身不依赖该信息。
	 */
	public boolean tookOverLiveLock() {
		return tookOverLiveLock;
	}

	/**
	 * {@return 取得锁的过程中是否碰过别人留下的锁文件（等过或接管过）}.
	 *
	 * <p>调用方据此判断「锁内重检」是否值得做：从未碰到过锁文件，就不可能有人在此期间把产物写好，
	 * 重检只是白白多一次 IO（并可能多算一次 hash）。
	 */
	public boolean waitedForLock() {
		return waited;
	}

	@Override
	public void close() {
		HELD.remove(key, token);

		try {
			// 只删自己那一把：若等锁期间超时被接管，锁文件里已经是别人的 token，这时删掉等于放开两个写入者
			// （虽不影响正确性，但会让去重失效）。收尾这一步在写路径上，多读一次文件不算热路径负担。
			final Snapshot snapshot = Snapshot.read(lockFile);

			if (snapshot != null && snapshot.owner() != null && !token.equals(snapshot.owner().token())) {
				return;
			}

			Files.deleteIfExists(lockFile);
		} catch (IOException e) {
			// 删不掉不是构建失败的理由：下一次 acquire 会把它判成残留并接管
			LOGGER.debug("释放下载锁失败：{}", lockFile, e);
		}
	}

	private enum Holder {
		ALIVE,
		STALE
	}

	private static boolean tryCreate(Path lockFile, String token) throws IOException {
		try {
			Files.createFile(lockFile);
		} catch (FileAlreadyExistsException e) {
			return false;
		}

		writeOwner(lockFile, token);
		return true;
	}

	// 持有者信息写不进去也不影响互斥（只是少了诊断与判活依据），因此失败只记 debug
	private static void writeOwner(Path lockFile, String token) {
		try {
			final String info = "pid=%d start=%d host=%s token=%s since=%d".formatted(
					ProcessHandle.current().pid(),
					ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L),
					LOCAL_HOST,
					token,
					System.currentTimeMillis());
			Files.writeString(lockFile, info, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.debug("写入下载锁持有者信息失败（仅影响诊断与判活）：{}", lockFile, e);
		}
	}

	private static Holder holderOf(Snapshot snapshot, Path key) {
		final Owner owner = snapshot.owner();

		if (owner == null) {
			// 内容还不可读：可能是持有者刚创建完还没有写信息，也可能创建完就崩了
			return olderThan(snapshot.lastModified(), EMPTY_CONTENT_GRACE) ? Holder.STALE : Holder.ALIVE;
		}

		if (!LOCAL_HOST.equals(owner.host())) {
			// 跨主机（共享缓存盘）或早期版本写下的锁：只能按年龄推断，阈值取得大一些以免误接管正在进行的慢下载
			return olderThan(snapshot.lastModified(), FOREIGN_STALE_AFTER) ? Holder.STALE : Holder.ALIVE;
		}

		final Optional<ProcessHandle> handle = ProcessHandle.of(owner.pid());

		if (handle.isEmpty()) {
			return Holder.STALE;
		}

		final ProcessHandle process = handle.get();
		final Optional<Instant> actualStart = process.info().startInstant();

		if (owner.start() > 0 && actualStart.isPresent() && actualStart.get().toEpochMilli() != owner.start()) {
			// PID 被复用了：写下这把锁的进程早已不在
			return Holder.STALE;
		}

		if (owner.pid() == ProcessHandle.current().pid()) {
			if (HELD.containsKey(key)) {
				// 同一 JVM 内的另一个线程正在写
				return Holder.ALIVE;
			}

			// 本 JVM 写下的、却没有人认领的锁：收尾删除失败留下的遗留锁
			return olderThan(snapshot.lastModified(), SAME_JVM_GRACE) ? Holder.STALE : Holder.ALIVE;
		}

		return process.isAlive() ? Holder.ALIVE : Holder.STALE;
	}

	// 只删「刚刚判定为残留/超时」的那一个文件：期间若被别人换掉（fileKey 或 mtime 变了）就放弃，避免误删活着的锁
	private static boolean deleteSnapshot(Path lockFile, Snapshot snapshot) {
		try {
			final BasicFileAttributes now = Files.readAttributes(lockFile, BasicFileAttributes.class);

			if (!now.lastModifiedTime().equals(snapshot.lastModified()) || !Objects.equals(now.fileKey(), snapshot.fileKey())) {
				return false;
			}

			Files.delete(lockFile);
			return true;
		} catch (NoSuchFileException e) {
			return true;
		} catch (IOException e) {
			LOGGER.debug("删除下载锁失败：{}", lockFile, e);
			return false;
		}
	}

	private static boolean olderThan(FileTime lastModified, Duration grace) {
		return lastModified.toInstant().isBefore(Instant.now().minus(grace));
	}

	private static void pause() throws IOException {
		try {
			Thread.sleep(POLL_INTERVAL.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("等待下载锁时被中断", e);
		}
	}

	private static String localHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (IOException e) {
			return "unknown";
		}
	}

	private record Snapshot(FileTime lastModified, @Nullable Object fileKey, @Nullable Owner owner) {
		@Nullable
		static Snapshot read(Path lockFile) throws IOException {
			try {
				final BasicFileAttributes attributes = Files.readAttributes(lockFile, BasicFileAttributes.class);
				final String content;

				try {
					content = Files.readString(lockFile, StandardCharsets.UTF_8);
				} catch (NoSuchFileException e) {
					return null;
				}

				return new Snapshot(attributes.lastModifiedTime(), attributes.fileKey(), Owner.parse(content));
			} catch (NoSuchFileException e) {
				return null;
			}
		}

		String describeOwner() {
			return owner == null ? "信息未写出" : owner.describe();
		}
	}

	private record Owner(long pid, long start, String host, @Nullable String token) {
		@Nullable
		static Owner parse(String content) {
			final Map<String, String> values = new HashMap<>();

			for (String part : content.trim().split("\\s+")) {
				final int equals = part.indexOf('=');

				if (equals > 0) {
					values.put(part.substring(0, equals), part.substring(equals + 1));
				}
			}

			final String pid = values.get("pid");

			if (pid == null) {
				return null;
			}

			try {
				return new Owner(Long.parseLong(pid), parseLong(values.get("start")), values.getOrDefault("host", "unknown"), values.get("token"));
			} catch (NumberFormatException e) {
				return null;
			}
		}

		private static long parseLong(@Nullable String value) {
			if (value == null) {
				return 0L;
			}

			try {
				return Long.parseLong(value);
			} catch (NumberFormatException e) {
				return 0L;
			}
		}

		String describe() {
			return "pid=%d host=%s".formatted(pid, host);
		}
	}
}
