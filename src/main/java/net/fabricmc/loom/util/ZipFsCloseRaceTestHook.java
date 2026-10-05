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

package net.fabricmc.loom.util;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>仅供测试使用</b>的注入钩子：让「共享 zipfs 实例被并发持有者关掉」这一竞态可以被确定性触发.
 *
 * <p>这不是功能开关，禁止在生产构建里设置它——属性名带 {@code loom.test.} 前缀即表示只在测试上下文有意义。
 * 默认（不设属性）{@link #armForRead(List)} 只读一次系统属性就返回，对被注入的代码路径零影响。
 *
 * <h2>为什么需要它</h2>
 * {@code TinyRemapperService.recoverClasspathRead} 的受害侧自愈只在「读取 classpath 期间共享 zipfs
 * 实例被并发持有者关掉」这一竞态下才会被走到，而这个竞态在真实构建里极难命中（上一轮连续全量构建 0 命中），
 * 因此该分支此前只有单测能证明、端到端从未被走到。本钩子把该形态变成可注入、可断言的事件。
 *
 * <h2>注入的形态与真实故障一致</h2>
 * 刻意不复刻「登记簿里已有死条目」那条路径（单测已覆盖，它的成因是关闭时文件不在盘上）。这里复刻的是上一轮
 * 端到端取证的形态：读取方（tiny-remapper 的 {@code FileSystemReference.openJar}）自己按 URI 路线打开 jar，
 * 实例因此进入 JDK 进程级 zipfs 登记簿；本钩子检测到该条目出现——它的出现即「读取方已经拿到并在使用这个共享
 * 实例」——再由一个并发的持有者线程把它关掉，读取方于是在遍历途中撞上 {@code ClosedFileSystemException}。
 * 因此钩子只需一个注入点：{@link #armForRead(List)}，在读 classpath 之前布置好那个并发持有者。
 *
 * <h2>关闭前为什么等一小会儿</h2>
 * 遍历 jar 做的第一件事是对根目录调 {@code ZipFileSystem.isDirectory}，而该方法<b>没有</b> ensureOpen 检查：
 * 关闭若恰好落在「根目录取属性之后、列目录之前」那个微秒级窗口里，读取方拿到的是 {@code NullPointerException}
 * 而不是 {@code ClosedFileSystemException}（实测扁平 victim jar 下约 2%，目录层数越多越容易命中），
 * 自愈分支只认后者，失败类型就变成了随机。等 {@link #CLOSE_DELAY_MS} 毫秒再关，遍历早已铺开、
 * 根目录那次调用已经过去（victim jar 条目数由测试保证足够多，遍历窗口远大于该延迟），失败类型稳定在 CFSE 上。
 * 这个延迟只是把关闭点挪进「读取确实在进行」的区间内，不改变形态本身。
 *
 * <p>需要命中 NPE 那一侧落点（目录遍历撞上已置空的 {@code inodes}）的用例可以用
 * {@link #DELAY_PROPERTY} 把延迟调到 0：目录越多，列目录的次数越多，关闭点落在「属性已取到、正要列目录」
 * 那个窗口里的机会也越多。缺省值不变，不设该属性时行为与本属性加入之前一致。
 *
 * <h2>第二种注入：让读取以「形态被抹掉」的 NPE 失败</h2>
 * 上面那条竞态命中的是 NPE 落点时，异常形态本身就带证据（消息指名 {@code inodes}、栈里有 zipfs 帧）。
 * 但长命 JVM 里同一个隐式空指针被反复抛出后，HotSpot 会改用预分配的「fast throw」异常：消息为 {@code null}、
 * 栈长为 0，判据拿不到任何形态证据。这条子形态由 {@link #READ_FAILURE_PROPERTY} 注入：钩子让读取直接抛出
 * 一个与 fast throw 逐字同形的 NPE（见 {@link #fastThrowNullPointer()}），用来确定性验证
 * {@code TinyRemapperService} 的「重读治不好就原样抛出」这条行为判据——真实 fast throw 由 JIT 决定何时启用，
 * 在单测里无法稳定触发。
 */
@ApiStatus.Internal
public final class ZipFsCloseRaceTestHook {
	/**
	 * 测试专用系统属性：classpath 条目文件名里包含该片段时，就在该条目上布置「读取期间被并发持有者关闭」的竞态.
	 */
	private static final String ENTRY_PROPERTY = "loom.test.zipfs.closeRaceEntry";
	/**
	 * 测试专用系统属性：覆盖「检测到读取方已打开受害条目之后等多久才关闭」的毫秒数（缺省 {@value #CLOSE_DELAY_MS}）.
	 *
	 * <p>存在的理由：关闭点决定命中竞态的哪个落点——{@code ClosedFileSystemException}（有 ensureOpen 的调用点）
	 * 还是 {@code ZipFileSystem.isDirectory} 的 {@code inodes} NPE（没有 ensureOpen 的调用点）。
	 * 扁平 victim jar 需要一点延迟把关闭点推离根目录那一次 {@code newDirectoryStream}（否则失败类型随机）；
	 * 深目录 victim jar 的目录列举次数多得多，不需要这个延迟也能稳定命中，且更早关闭更容易落在
	 * {@code isDirectory} 那个窗口里。缺省值保持不变，不设该属性时行为与本属性加入之前完全一致。
	 */
	private static final String DELAY_PROPERTY = "loom.test.zipfs.closeRaceDelayMs";
	/**
	 * 测试专用系统属性：让 classpath 读取按指定形态<b>失败</b>，用于确定性验证自愈判据.
	 *
	 * <p>取值只认下面两个常量；不设（或取值不认识）时读取照常进行.
	 */
	private static final String READ_FAILURE_PROPERTY = "loom.test.zipfs.readFailure";
	/** {@value #READ_FAILURE_PROPERTY} 取值：只有第一次读取失败，其后照常成功. */
	private static final String READ_FAILURE_FAST_THROW_ONCE = "fastThrowNpeOnce";
	/** {@value #READ_FAILURE_PROPERTY} 取值：每次读取都失败，且反复抛出同一个预分配实例. */
	private static final String READ_FAILURE_FAST_THROW_ALWAYS = "fastThrowNpeAlways";
	/** 检测到读取方已打开受害条目之后再等多久才关闭（毫秒）；原因见类注释与 {@link #DELAY_PROPERTY}. */
	private static final long CLOSE_DELAY_MS = 5;
	/** 等待读取方打开受害条目的上限（毫秒）：超时即放弃，避免在 Gradle daemon 里留下空转线程. */
	private static final long MAX_WAIT_MS = 60_000;
	/** 轮询间隔（纳秒）：远小于 victim jar 的遍历时长，同时避免忙等烧 CPU. */
	private static final long POLL_INTERVAL_NANOS = 200_000L;
	/** 自愈/诊断日志必须让用户看见，用与 {@code TinyRemapperService} 相同、默认可见的 logger. */
	private static final Logger LOGGER = Logging.getLogger("loom_zipfs");
	/** 已经布置过竞态的受害者（绝对路径）：同一 daemon 里重复读同一份 classpath 时不叠加线程. */
	private static final Set<String> ARMED = ConcurrentHashMap.newKeySet();
	/**
	 * 与 HotSpot fast throw 逐字同形的 NPE：预分配、消息为 {@code null}、栈长为 0，且每次抛出的是同一个实例.
	 *
	 * <p>真实 fast throw（{@code -XX:+OmitStackTraceInFastThrow}，默认开启）由 JIT 在「同一个隐式空指针
	 * 在同一处被反复抛出」之后启用，此后抛出的都是 JVM 启动期预分配的那一个实例；本实例在
	 * {@code getMessage()}、{@code getStackTrace()}、{@code getCause()} 上与它完全一致，是单测里可稳定复现的等价模拟.
	 */
	private static final NullPointerException FAST_THROW_NULL_POINTER = createFastThrowNullPointer();
	/** {@link #READ_FAILURE_PROPERTY} 当前已布置的形态（{@code null} 表示未布置）；形态变化即视为重新布置. */
	private static volatile String armedReadFailureForm;
	/** {@value #READ_FAILURE_FAST_THROW_ONCE} 形态是否已经抛过一次. */
	private static final AtomicBoolean onceReadFailureConsumed = new AtomicBoolean();

	private ZipFsCloseRaceTestHook() {
	}

	/**
	 * classpath 读取的唯一出口调用它：布置了读取失败（{@value #READ_FAILURE_PROPERTY}）就按形态抛出，
	 * 否则立即返回.
	 *
	 * <p>不设属性时只读一次系统属性就返回，对被注入的代码路径零影响.
	 */
	public static void throwIfReadFailureArmed() {
		final String form = System.getProperty(READ_FAILURE_PROPERTY);

		if (form == null || form.isBlank()) {
			armedReadFailureForm = null;
			return;
		}

		if (!form.equals(armedReadFailureForm)) {
			// 形态变化即视为「重新布置」：同一个 daemon 里多个用例互不影响，不必依赖用例的执行顺序与清理时机
			armedReadFailureForm = form;
			onceReadFailureConsumed.set(false);

			if (!READ_FAILURE_FAST_THROW_ONCE.equals(form) && !READ_FAILURE_FAST_THROW_ALWAYS.equals(form)) {
				LOGGER.warn("测试钩子属性 {} 的值 {} 无法识别，本次不注入读取失败", READ_FAILURE_PROPERTY, form);
			}
		}

		if (READ_FAILURE_FAST_THROW_ALWAYS.equals(form)
				|| (READ_FAILURE_FAST_THROW_ONCE.equals(form) && onceReadFailureConsumed.compareAndSet(false, true))) {
			throw fastThrowNullPointer();
		}
	}

	/**
	 * 与 HotSpot fast throw 逐字同形的 NPE 实例（见 {@link #FAST_THROW_NULL_POINTER}）.
	 *
	 * <p>暴露出来只为让用例能断言「抛出的就是这同一个实例（对象标识相同，因而逐字一致）」；生产代码不使用.
	 */
	public static NullPointerException fastThrowNullPointer() {
		return FAST_THROW_NULL_POINTER;
	}

	private static NullPointerException createFastThrowNullPointer() {
		final NullPointerException failure = new NullPointerException();
		// 预分配异常没有栈：真实 fast throw 交给 JVM 的这个实例同样有 null 消息、长度 0 的栈
		failure.setStackTrace(new StackTraceElement[0]);
		return failure;
	}

	/**
	 * 在本次 classpath 读取之前布置关闭竞态；不设 {@value #ENTRY_PROPERTY} 时是空操作.
	 *
	 * <p>只认第一个文件名包含标记的条目，因此夹具工程必须保证该标记只命中它自己那条受害者依赖。
	 *
	 * @param toRead 本次即将读取的 classpath 条目
	 */
	public static void armForRead(List<Path> toRead) {
		final String marker = System.getProperty(ENTRY_PROPERTY);

		if (marker == null || marker.isBlank()) {
			return;
		}

		for (Path path : toRead) {
			if (!path.getFileName().toString().contains(marker) || !ARMED.add(path.toAbsolutePath().toString())) {
				continue;
			}

			LOGGER.warn("测试钩子已布置关闭竞态（{}）：classpath 条目 {} 将在读取期间被并发持有者关闭", ENTRY_PROPERTY, path);
			startConcurrentCloser(path);
			return;
		}
	}

	private static void startConcurrentCloser(Path victim) {
		final CountDownLatch spinning = new CountDownLatch(1);
		final Thread closer = new Thread(() -> closeSharedInstanceWhileReading(victim, spinning), "loom-zipfs-close-race");
		closer.setDaemon(true);
		closer.start();

		try {
			// 等线程真正跑起来再返回：这样「读取方打开条目」到「关闭者出手」之间只有轮询间隔，
			// 不含线程创建的开销，关闭点因此稳定落在遍历窗口内。
			if (!spinning.await(5, TimeUnit.SECONDS)) {
				LOGGER.warn("测试钩子的关闭者线程没能及时启动，{} 上的竞态可能不会触发", victim);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void closeSharedInstanceWhileReading(Path victim, CountDownLatch spinning) {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MS);
		final long closeDelayMs = closeDelayMs();
		spinning.countDown();

		while (System.nanoTime() < deadline) {
			final FileSystem shared = FileSystemUtil.findOpenRegistryEntry(victim);

			if (shared == null) {
				// 读取方还没打开这个条目（登记簿里还没有它的活实例）
				parkNanos();

				continue;
			}

			try {
				Thread.sleep(closeDelayMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			LOGGER.warn("测试钩子生效：共享 zipfs 实例 {} 此刻正被读取方使用，改由并发持有者关闭，以复现 JDK-8291712 的关闭竞态", victim);

			try {
				shared.close();
			} catch (IOException e) {
				// 关闭失败说明文件在读取期间被替换过——那正是 JDK-8291712 留下死条目的路径，不是本钩子要复刻的形态
				LOGGER.warn("测试钩子关闭 {} 的共享实例失败", victim, e);
			}

			return;
		}

		LOGGER.warn("测试钩子等待超时：{} 在 {} ms 内没有被读取方打开，本次未布置关闭竞态", victim, MAX_WAIT_MS);
	}

	/** 读一次关闭延迟属性；非法值（无法解析、负数）一律退回缺省，避免测试属性把钩子变成空转. */
	private static long closeDelayMs() {
		final String configured = System.getProperty(DELAY_PROPERTY);

		if (configured == null || configured.isBlank()) {
			return CLOSE_DELAY_MS;
		}

		try {
			final long parsed = Long.parseLong(configured.trim());

			if (parsed < 0) {
				LOGGER.warn("测试钩子属性 {} 的值 {} 非法（不能为负），改用缺省 {} ms", DELAY_PROPERTY, configured, CLOSE_DELAY_MS);
				return CLOSE_DELAY_MS;
			}

			return parsed;
		} catch (NumberFormatException e) {
			LOGGER.warn("测试钩子属性 {} 的值 {} 无法解析，改用缺省 {} ms", DELAY_PROPERTY, configured, CLOSE_DELAY_MS);
			return CLOSE_DELAY_MS;
		}
	}

	private static void parkNanos() {
		// 用 Thread.sleep(0, nanos) 而不是忙等：轮询本身要够密（决定关闭点落点），但没必要烧 CPU
		try {
			Thread.sleep(0, (int) POLL_INTERVAL_NANOS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
