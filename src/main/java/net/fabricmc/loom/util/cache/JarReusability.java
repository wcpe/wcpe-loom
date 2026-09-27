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
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loom.util.FileSystemUtil;

/**
 * 「产物可作为输入复用」的判定工具：把 jar/zip 的内容校验收敛到唯一实现.
 *
 * <p>共享缓存里的产物可能被其它进程写坏：仍在用「先删除再就地写」的旧版本 loom 会在替换窗口内让目标路径
 * 出现 22 字节的空 zip（本仓库实测到的形态），进程被中断还会留下截断文件。只做存在性判定会把这种半成品
 * 当成就绪产物，一路传到最终产物里，形成「标记是新的、内容是坏的」的静默损坏。因此这里的口径是：
 * 能作为 zip 打开（中央目录完整），且至少含一个条目（不是空 zip 壳）。
 *
 * <p>该口径只拒绝「已经损坏」的产物：正常写入流程（临时文件 + 原子落位）产生的 jar 必然满足它，
 * 所以不会把正常产物永久判为不可用、把读方拖进「每次构建都重建」的循环。
 *
 * <p>普通 {@code .zip}（例如 {@code mcp.zip}）与 jar 走的是同一条 zipfs 路径，同样适用本判定。
 */
public final class JarReusability {
	private JarReusability() {
	}

	/**
	 * {@return 该 jar/zip 是否可作为输入复用}.
	 *
	 * @param jar 待判定的产物路径
	 */
	public static boolean isReusable(Path jar) {
		if (Files.notExists(jar)) {
			return false;
		}

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar, false); var entries = Files.list(fs.getPath("/"))) {
			// 至少一个条目：空 zip（0 条目）虽然能被 zipfs 打开，但作为 Minecraft/Forge 链的输入必然是残骸
			return entries.findAny().isPresent();
		} catch (IOException e) {
			// 打不开即视为不可复用：截断文件、空文件、非 zip 内容都会在此抛 IOException。
			// 刻意不吞 FileSystemUtil.UnrecoverableZipException（RuntimeException）：它表示本 JVM 之后再也打不开
			// 该路径，吞掉只会让上层陷入「重建 → 仍然打不开」的循环，应当尽早暴露。
			return false;
		}
	}
}
