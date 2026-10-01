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

package net.fabricmc.loom.test.integration.buildSrc.forgeSourcesCacheDecompiler

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile

import net.fabricmc.loom.api.decompilers.DecompilationMetadata
import net.fabricmc.loom.api.decompilers.LoomDecompiler
import net.fabricmc.loom.util.Pair
import net.fabricmc.loom.util.ZipUtils

/**
 * 「给每个外层类写一个占位源文件」的替身反编译器.
 *
 * <p>用途：验证反编译缓存三条分支（全命中 / 部分命中 / 不使用缓存）时，真反编译 MC 太慢，而
 * 被测的 Forge 源码注入发生在反编译**之后**、与反编译器实现无关。这里只用几百毫秒写出一份
 * 「每个外层类都有源码」的产物，好让缓存能被填满——这正是拿到「全命中」分支的前提。
 *
 * <h4>写出来的内容带着标记</h4>
 * 每个文件都含 {@link #MARKER}，测试据此区分「反编译器写的」与「ForgeSourcesService 注入的」：
 * 如果某个 Forge 源码条目里出现这个标记，说明它根本没被注入覆盖。
 *
 * <h4>为什么只写外层类</h4>
 * Forge 平台上 {@code GenerateSourcesTask.removeForgeInnerClassSources} 会删掉产物里所有名字含
 * {@code $} 的条目，而 {@code CachedJarProcessor.completeJob} 又要求产物里每个文件都能在
 * {@code outputNameMap} 里找到对应条目（否则直接抛 {@code Unexpected output}）。写了内层类只会
 * 让用例因为无关原因失败，所以与真实反编译器的行为保持一致：每个外层类一个文件。
 *
 * <h4>为什么会在整包输入时故意漏写一个类</h4>
 * 「部分命中」分支需要一个「上一轮没进缓存」的类：只要它在整包那一轮不被写出来，它就不会进缓存，
 * 下一轮就只有它未命中。刻意挑**非 Forge** 类——Forge 类会在同一轮被
 * {@code addForgeSources} 补上，补上就等于进了缓存，造不出未命中。
 * 判据是「输入类数 > {@link #FULL_JAR_CLASS_THRESHOLD}」：部分命中那一轮拿到的输入是只含未命中类的
 * 临时 jar（1 个类），因此那一轮照常写出来，缓存才补得全。
 */
class StubSourceDecompiler implements LoomDecompiler {
	/** 写进每个占位源文件的标记，测试据此判断条目是否被注入覆盖. */
	static final String MARKER = 'STUB_DECOMPILER_OUTPUT'
	/** 超过这个类数视为「整包输入」（见类注释）. */
	private static final int FULL_JAR_CLASS_THRESHOLD = 1000
	/** 每次执行记一行，测试据此判断某一轮反编译器到底跑没跑. */
	private static final String RUN_LOG_PREFIX = '=== RUN'

	@Override
	void decompile(Path compiledJar, Path sourcesDestination, Path linemapDestination, DecompilationMetadata metaData) {
		final List<String> outerClasses = readOuterClassNames(compiledJar)
		final String skipped = outerClasses.size() > FULL_JAR_CLASS_THRESHOLD
				? outerClasses.find { !it.startsWith('net/minecraftforge/') }
				: null

		final List<Pair<String, byte[]>> sources = outerClasses
				.findAll { it != skipped }
				.collect { new Pair<String, byte[]>('/' + it + '.java', stubSource(it).getBytes(StandardCharsets.UTF_8)) }

		ZipUtils.add(sourcesDestination, sources)
		appendRunLog(metaData.options().get('runLog'), compiledJar, outerClasses.size(), skipped)
	}

	/** 输入 jar 里的外层类名（不含 {@code $}、必须带包），排序后返回. */
	private static List<String> readOuterClassNames(Path jar) {
		final List<String> names = []
		final ZipFile zip = new ZipFile(jar.toFile())

		try {
			zip.entries().each { entry ->
				final String name = entry.name

				if (name.endsWith('.class') && !name.contains('$') && name.contains('/')) {
					names.add(name.substring(0, name.length() - '.class'.length()))
				}
			}
		} finally {
			zip.close()
		}

		names.sort()
		return names
	}

	private static String stubSource(String className) {
		final int index = className.lastIndexOf('/')
		final String pkg = className.substring(0, index).replace('/', '.')
		final String simple = className.substring(index + 1)
		return "/* ${MARKER} */\npackage ${pkg};\n\npublic class ${simple} {\n}\n"
	}

	/** 追加一段执行记录（不能覆盖：用例要按轮次对比）. */
	private static void appendRunLog(String path, Path compiledJar, int classCount, String skipped) {
		final File log = new File(path)

		if (log.parentFile != null) {
			log.parentFile.mkdirs()
		}

		int runs = 0

		if (log.exists()) {
			log.text.eachLine { line ->
				if (line.startsWith(RUN_LOG_PREFIX)) {
					runs++
				}
			}
		}

		log << "${RUN_LOG_PREFIX} ${runs + 1} input=${compiledJar.fileName} classes=${classCount} skipped=${skipped == null ? '-' : skipped}\n"
	}
}
