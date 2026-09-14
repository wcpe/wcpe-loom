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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 原子发布工具：保证「文件存在 ⟺ 内容完整」这一不变量成立.
 *
 * <p>各最终产物先写入「与 target 同目录的唯一临时文件」，写完整后再用原子 move 落位。
 * 这样跨 daemon 的无锁存在性检查不会在写入期间看到半成品：读方要么看到旧文件、要么看到完整的新文件。
 */
public final class AtomicFiles {
	private AtomicFiles() {
	}

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
	 */
	public static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
