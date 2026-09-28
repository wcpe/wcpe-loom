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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

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
 * <p>普通 {@code .zip}（例如 {@code mcp.zip}）与 jar 走的是同一条 zipfs 路径，同样适用本判定；
 * 名字不以 {@code .jar}/{@code .zip} 结尾的产物（例如 mapped jar 的 {@code .backup}）只要内容仍是 zip
 * 也同样适用——损坏时会以别的异常类型表现出来，见 {@link #isReusable(Path)} 的处理。
 */
public final class JarReusability {
	/**
	 * 诊断日志.
	 *
	 * <p>用 Gradle 的 {@link Logger#lifecycle(String, Object...)} 而不是 SLF4J 的 {@code info}：
	 * 后者映射到 Gradle 的 INFO 级别，而 Gradle 默认控制台级别是 LIFECYCLE，默认构建里看不到，
	 * 等于没有诊断。{@code CacheEntryLock} 等静态工具类用的是同一种做法。
	 */
	private static final Logger LOGGER = Logging.getLogger("loom_jarReusability");

	/**
	 * 已经播报过「不可复用」的路径.
	 *
	 * <p>本类位于每次构建的热路径上（同一份产物可能被多个 provider 各判一次），故按路径去重：
	 * 同一路径只在「由可用变为不可用」之后播报一次。产物重新生成并再次判为可复用时会清掉记录，
	 * 于是集合大小只与当前损坏的产物数同阶，不会随构建次数无限增长。
	 */
	private static final Set<Path> REPORTED_UNREUSABLE = ConcurrentHashMap.newKeySet();

	private JarReusability() {
	}

	/**
	 * {@return 该 jar/zip 是否可作为输入复用}.
	 *
	 * @param jar 待判定的产物路径
	 */
	public static boolean isReusable(Path jar) {
		if (Files.notExists(jar)) {
			reportUnreusable(jar, "文件不存在");
			return false;
		}

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar, false); var entries = Files.list(fs.getPath("/"))) {
			// 至少一个条目：空 zip（0 条目）虽然能被 zipfs 打开，但作为 Minecraft/Forge 链的输入必然是残骸
			if (entries.findAny().isEmpty()) {
				reportUnreusable(jar, "可作为 zip 打开，但不含任何条目（0 条目空 zip 壳）");
				return false;
			}

			// 恢复可用后清掉播报记录：该路径下次再损坏时仍能被诊断出来
			REPORTED_UNREUSABLE.remove(jar);
			return true;
		} catch (IOException e) {
			// 打不开即视为不可复用：截断文件、空文件、非 zip 内容都会在此抛 IOException。
			reportUnreusable(jar, describeOpenFailure(e));
			return false;
		} catch (RuntimeException e) {
			// JDK 的 zipfs 对「名字不是 .jar/.zip 且内容也不是 zip」的路径抛出的是运行时异常而非 IOException
			// （实测 JDK 21：同一份损坏内容命名为 .jar 时是 ZipException，命名为 .jar.backup 时是
			// ProviderNotFoundException）。这类产物同样应判为「打不开 ⇒ 不可复用」，
			// 若放任其逃逸，调用方就会从「简单重建」变成构建崩溃。
			// 唯独不能吞 FileSystemUtil.UnrecoverableZipException：它表示本 JVM 之后再也打不开该路径，
			// 吞掉只会让上层陷入「重建 → 仍然打不开」的循环，应当尽早暴露。
			if (e instanceof FileSystemUtil.UnrecoverableZipException) {
				throw e;
			}

			reportUnreusable(jar, describeOpenFailure(e));
			return false;
		}
	}

	// 把「打不开」的异常类型与消息带上：诊断时最需要区分的就是空文件、截断与根本不是 zip
	private static String describeOpenFailure(Exception e) {
		return "打不开（%s: %s）".formatted(e.getClass().getSimpleName(), e.getMessage());
	}

	/**
	 * 播报「该产物不可复用」及具体判据.
	 *
	 * <p>这条日志是判据收紧后唯一能定位「为什么每轮构建都在重建同一件产物」的线索：
	 * 只返回 false 而不说明原因时，空 zip、截断文件与打不开在现象上完全一样。级别取 LIFECYCLE
	 * （Gradle 默认可见），并按路径去重，避免热路径上反复调用时刷屏。
	 */
	private static void reportUnreusable(Path jar, String reason) {
		if (!REPORTED_UNREUSABLE.add(jar)) {
			return;
		}

		LOGGER.lifecycle("共享缓存产物不可复用，本轮将重新生成：{}（大小 {}；{}）", jar.toAbsolutePath(), describeSize(jar), reason);
	}

	private static String describeSize(Path jar) {
		try {
			return Files.size(jar) + " 字节";
		} catch (IOException e) {
			// 文件不存在或读不到元数据：只损失这一项诊断信息，不影响其余判据的播报
			return "未知";
		}
	}
}
