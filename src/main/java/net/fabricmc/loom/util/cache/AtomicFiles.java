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
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 原子发布工具：保证「文件存在 ⟺ 内容完整」这一不变量成立.
 *
 * <p>各最终产物先写入「与目标同目录的唯一临时文件」，写完整后再用原子 move 落位。
 * 这样跨 daemon 的无锁存在性检查不会在写入期间看到半成品：读方要么看到旧文件、要么看到完整的新文件。
 *
 * <h2>Windows 的替换限制</h2>
 *
 * <p>POSIX 的 {@code rename(2)} 可以覆盖任何同名文件，但 Windows 不允许替换（或删除）正被其它句柄打开的文件：
 * 只要读方还持有句柄，{@code MoveFileEx(REPLACE_EXISTING)} 就会以 {@code ERROR_ACCESS_DENIED} 失败，
 * Java 侧表现为 {@link AccessDeniedException}。实测 {@code InputStream}、{@code FileChannel}、
 * {@code ZipFile} 与 {@code FileSystem}(zipfs) 的读取句柄都会触发该限制。
 *
 * <p>因此 {@link #move} 对这种情况做「有上限的退避重试」：短生命周期的读方（例如反复读取的构建）会被自动让过，
 * 持续占用则最终抛出带诊断信息的异常，而不是把平台限制原样丢给调用方。读方的不变量不受影响——
 * 重试期间目标始终是旧的完整文件。
 */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	/**
	 * 替换被读方占用时的重试预算.
	 *
	 * <p>超过该时长仍无法替换即判定为「读方长期持有句柄」，抛出可诊断的异常。
	 */
	private static final Duration REPLACE_RETRY_BUDGET = Duration.ofSeconds(30);
	private static final long RETRY_INITIAL_MILLIS = 5;
	private static final long RETRY_MAX_MILLIS = 250;

	/**
	 * 接收一个 Path 并向其写入内容的回调，允许抛出 {@link IOException}.
	 */
	@FunctionalInterface
	public interface IOConsumer<T> {
		void accept(T t) throws IOException;
	}

	/**
	 * 原子发布：让 producer 把内容写入「同目录唯一临时文件」，写完后原子 move 到 target.
	 *
	 * <p>临时文件用 {@link Files#createTempFile(Path, String, String, java.nio.file.attribute.FileAttribute[])}
	 * 生成唯一名，避免并发写同一 target 时多个临时文件互相踩踏。无论成功失败，finally 都会清理临时文件。
	 *
	 * @param target   最终落位路径
	 * @param producer 内容生产者，接收临时文件路径并把内容写进去
	 */
	public static void publish(Path target, IOConsumer<Path> producer) throws IOException {
		Files.createDirectories(target.getParent());
		// 生成唯一且「尚不存在」的临时路径，且不预先创建文件——这点很关键：
		//   - 唯一名（UUID）避免并发写同一 target 时多个临时文件互相踩踏；
		//   - 不预先创建空文件，是因为很多生产者（zip/jar 文件系统、tinyremapper 输出）要求目标不存在、由其自行创建，
		//     若预先创建一个空文件，会被当成「损坏的 zip」而失败（暖缓存会跳过这些步骤，故该问题只在冷缓存暴露）。
		final Path tmp = tempSibling(target);

		try {
			producer.accept(tmp);
			move(tmp, target);
		} finally {
			// 原子 move 成功后 tmp 已不存在；失败时清理残留，避免遗留垃圾临时文件
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * 为 target 生成一个同目录、唯一且尚不存在的临时路径（不创建文件）.
	 *
	 * <p>供「一次产生多个输出、不便用 {@link #publish} 逐个包裹」的场景使用（例如 jar 拆分写两个文件）：
	 * 调用方把内容写入这些临时路径后，用 {@link #move} 各自原子落位。
	 */
	public static Path tempSibling(Path target) {
		final String name = target.getFileName().toString();
		final int dot = name.lastIndexOf('.');
		// 保留 target 的原始扩展名（.jar/.zip 等）：tinyremapper 的 OutputConsumerPath 等工具按扩展名
		// 决定输出是 zip 还是目录，若临时文件以 .tmp 结尾会被当成目录输出，在 Windows 上报错。
		final String stem = dot >= 0 ? name.substring(0, dot) : name;
		final String ext = dot >= 0 ? name.substring(dot) : "";
		return target.resolveSibling(stem + "." + UUID.randomUUID() + ".tmp" + ext);
	}

	/**
	 * 便捷方法：原子复制一个已存在的源文件到 target.
	 */
	public static void copy(Path source, Path target) throws IOException {
		publish(target, tmp -> Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING));
	}

	/**
	 * 原子 move：优先 ATOMIC_MOVE，个别平台不支持时退化为普通 move.
	 *
	 * <p>用于调用方已将内容写入同目录临时文件、需把其原子落位到 target 的场景
	 * （例如一次产生多个输出、不便用 {@link #publish} 逐个包裹时）。
	 *
	 * <p>目标被其它进程/线程的读取句柄占用时（Windows 的替换限制，见类注释）会做有上限的退避重试，
	 * 让过短生命周期的读方；持续占用则抛出带诊断信息的 {@link IOException}。
	 */
	public static void move(Path source, Path target) throws IOException {
		final long deadline = System.nanoTime() + REPLACE_RETRY_BUDGET.toNanos();
		long backoffMillis = RETRY_INITIAL_MILLIS;

		while (true) {
			try {
				try {
					Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
				}

				return;
			} catch (AccessDeniedException e) {
				// Windows 上读方持有目标句柄时的表现。读方是短生命周期的，重试即可成功；
				// 因此这里不立刻失败，避免把「另一个进程正在读」变成构建失败。
				backoffMillis = retryOrFail(target, deadline, backoffMillis, e);
			} catch (FileSystemException e) {
				// 共享冲突的另一族表现：JDK 的 WindowsException.translateToIOException 只把
				// ERROR_ACCESS_DENIED(5) 映射为 AccessDeniedException，ERROR_SHARING_VIOLATION(32)
				//（源或目标被占用，网络盘/部分锁定语义下常见）会落到普通 FileSystemException，
				// 消息形如 "...being used by another process"。这类冲突与上面的成因相同、同样是短生命周期，
				// 若漏在这个 catch 之外，退避预算就完全不起作用，只好把平台限制原样丢给调用方。
				// 注意 FileSystemException 是 AccessDeniedException 的父类：本分支必须写在它之后。
				if (!isShareConflict(e)) {
					throw e;
				}

				backoffMillis = retryOrFail(target, deadline, backoffMillis, e);
			}
		}
	}

	/**
	 * 判断一个 {@link FileSystemException} 是否属于「文件被其它进程占用」这一族.
	 *
	 * <p>Java 没有暴露错误码，只能按消息匹配：覆盖英文与中文两种系统消息形态，以及
	 * 「cannot access the file」这一不带 because 从句的变体（源或目标被占用都会走到这里）。
	 * 误判的代价是有上限的——最多多等一个退避预算（见 {@link #REPLACE_RETRY_BUDGET}）后
	 * 仍以带诊断信息的异常结束，不会吞掉真正的失败。
	 */
	private static boolean isShareConflict(FileSystemException e) {
		final String message = e.getMessage();

		if (message == null) {
			return false;
		}

		final String lower = message.toLowerCase(Locale.ROOT);
		return lower.contains("being used by another process")
				|| lower.contains("used by another process")
				|| lower.contains("cannot access the file")
				|| lower.contains("另一个程序正在使用")
				|| lower.contains("正由另一进程使用")
				|| lower.contains("被另一进程使用");
	}

	// 退避预算内则休眠后重试，超出预算则抛出带诊断信息的异常（复用 describeShareConflict）；
	// {@return 下一次的退避时长}
	private static long retryOrFail(Path target, long deadline, long backoffMillis, IOException cause) throws IOException {
		if (System.nanoTime() >= deadline) {
			throw new IOException(describeShareConflict(target), cause);
		}

		sleepBriefly(backoffMillis);
		return Math.min(backoffMillis * 2, RETRY_MAX_MILLIS);
	}

	/**
	 * 构造可诊断的替换冲突说明.
	 *
	 * <p>把平台限制、最可能的原因与用户可采取的动作写进消息里——原始 {@link AccessDeniedException}
	 * 只给出「拒绝访问」，无法指向真正的问题。
	 */
	private static String describeShareConflict(Path target) {
		return "无法替换共享缓存产物 %s：目标文件正被其它进程或线程读取。".formatted(target.toAbsolutePath())
				+ " Windows 不允许替换（或删除）已被打开的文件，已等待 %d 秒仍未能落位。"
				.formatted(REPLACE_RETRY_BUDGET.toSeconds())
				+ " 若同时运行多个工作树或 Gradle daemon 共享同一 userCache，请错开构建；"
				+ "单个构建内出现该提示通常意味着另一个 Loom 进程长期持有该文件。";
	}

	private static void sleepBriefly(long millis) throws IOException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("等待替换共享缓存产物时被中断", e);
		}
	}
}
