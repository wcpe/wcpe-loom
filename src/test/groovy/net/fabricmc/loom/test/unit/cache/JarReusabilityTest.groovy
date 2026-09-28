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

package net.fabricmc.loom.test.unit.cache

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.cache.JarReusability

/**
 * JarReusability 是共享缓存所有就绪判据的唯一实现，故这里逐条钉住它的边界：
 * 正常产物必须判 true（否则会退化成「每次构建都重建」），
 * 各类残骸必须判 false 且不得抛异常（否则调用方会从「重建」变成构建崩溃）。
 */
class JarReusabilityTest extends Specification {
	@TempDir
	Path tempDir

	def "正常 jar 判为可复用"() {
		given:
		def jar = writeJar(tempDir.resolve("good.jar"), ["mappings/mappings.tiny": "tiny v2 内容\n"])

		expect:
		JarReusability.isReusable(jar)
	}

	def "不存在的产物判为不可复用"() {
		expect:
		!JarReusability.isReusable(tempDir.resolve("missing.jar"))
	}

	def "0 字节残骸判为不可复用"() {
		given:
		def jarNamed = Files.createFile(tempDir.resolve("empty.jar"))
		def zipNamed = Files.createFile(tempDir.resolve("empty.zip"))
		def noKnownExt = Files.createFile(tempDir.resolve("minecraft-merged-named.jar.backup"))

		expect:
		// 三种命名都要判 false：JDK 的 zipfs 对不同命名的失败类型不同（.jar/.zip 抛 IOException，
		// 其它命名抛 ProviderNotFoundException 这类运行时异常），判据必须把两族都算作「不可复用」
		!JarReusability.isReusable(jarNamed)
		!JarReusability.isReusable(zipNamed)
		!JarReusability.isReusable(noKnownExt)
	}

	def "截断 jar 判为不可复用"() {
		given:
		def jar = writeJar(tempDir.resolve("good.jar"), ["a.txt": "内容"])
		def truncated = tempDir.resolve("truncated.jar")
		byte[] bytes = Files.readAllBytes(jar)
		// 用 intdiv：Groovy 的 `/` 返回 BigDecimal，不是 Arrays.copyOf 需要的 int
		int half = bytes.length.intdiv(2)
		Files.write(truncated, Arrays.copyOf(bytes, half))
		def truncatedNoKnownExt = tempDir.resolve("truncated.jar.backup")
		Files.write(truncatedNoKnownExt, Arrays.copyOf(bytes, half))

		expect:
		!JarReusability.isReusable(truncated)
		!JarReusability.isReusable(truncatedNoKnownExt)
	}

	def "0 条目空 zip 壳判为不可复用"() {
		given:
		def emptyZip = writeJar(tempDir.resolve("shell.jar"), [:])

		expect:
		// 能被 zipfs 打开但没有条目，作为 Minecraft/Forge 链的输入必然是残骸
		!JarReusability.isReusable(emptyZip)
	}

	def "非 zip 内容判为不可复用"() {
		given:
		def text = tempDir.resolve("text.jar")
		Files.writeString(text, "这不是一个 zip 文件", StandardCharsets.UTF_8)
		def textNoKnownExt = tempDir.resolve("text.jar.backup")
		Files.writeString(textNoKnownExt, "这不是一个 zip 文件", StandardCharsets.UTF_8)

		expect:
		!JarReusability.isReusable(text)
		!JarReusability.isReusable(textNoKnownExt)
	}

	def "产物被重新生成后重新判为可复用"() {
		given:
		def jar = Files.createFile(tempDir.resolve("rebuilt.jar"))

		expect:
		!JarReusability.isReusable(jar)

		when:
		// 覆盖为完整产物（模拟重建后再次判定：诊断播报的去重不能影响判定结果）
		writeJar(jar, ["mappings/mappings.tiny": "tiny v2 内容\n"])

		then:
		JarReusability.isReusable(jar)
	}

	// 造一个含指定条目的 jar；用 try/finally 而不是 try-with-resources，与仓库其余测试保持一致
	private static Path writeJar(Path path, Map<String, String> entries) {
		def output = new ZipOutputStream(Files.newOutputStream(path))

		try {
			entries.each { name, content ->
				output.putNextEntry(new ZipEntry(name))
				output.write(content.getBytes(StandardCharsets.UTF_8))
				output.closeEntry()
			}
		} finally {
			output.close()
		}

		return path
	}
}
