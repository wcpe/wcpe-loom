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
import java.util.stream.Collectors

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.FileSystemUtil
import net.fabricmc.loom.util.cache.AtomicFiles

class AtomicFilesTest extends Specification {
	@TempDir
	Path tempDir

	def "tempSibling 保留原扩展名且唯一"() {
		given:
		def jarTarget = tempDir.resolve("minecraft-merged.jar")
		def pomTarget = tempDir.resolve("foo.pom")
		def noExtTarget = tempDir.resolve("plainfile")

		when:
		def jarTmp = AtomicFiles.tempSibling(jarTarget)
		def pomTmp = AtomicFiles.tempSibling(pomTarget)
		def noExtTmp = AtomicFiles.tempSibling(noExtTarget)

		then:
		// 扩展名必须保留：否则 tinyremapper 等按扩展名判断输出类型的工具会出错
		jarTmp.fileName.toString().endsWith(".jar")
		pomTmp.fileName.toString().endsWith(".pom")
		// 同目录、唯一、不预先存在
		jarTmp.parent == jarTarget.parent
		AtomicFiles.tempSibling(jarTarget) != AtomicFiles.tempSibling(jarTarget)
		Files.notExists(jarTmp)
		// 无扩展名时不追加多余的点
		!noExtTmp.fileName.toString().endsWith(".")
	}

	def "publish 适用于 zip/jar 生产者（producer 需把临时文件当作新 jar 创建）"() {
		given:
		// 复现 merge/split/remap 等真实场景：producer 用 getJarFileSystem(tmp, true) 把 tmp 当作新 zip 创建并写入。
		// 若 publish 预先创建了空文件，zip 文件系统会因「空文件不是合法 zip」而失败。
		def target = tempDir.resolve("out.jar")

		when:
		AtomicFiles.publish(target, { tmp ->
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(tmp, true)) {
				Files.writeString(fs.getRoot().resolve("hello.txt"), "hi", StandardCharsets.UTF_8)
			}
		})

		then:
		Files.exists(target)

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(target, false)) {
			Files.readString(fs.getRoot().resolve("hello.txt"), StandardCharsets.UTF_8) == "hi"
		}
	}

	def "publish 后 target 内容完整"() {
		given:
		def target = tempDir.resolve("out.txt")

		when:
		AtomicFiles.publish(target, { tmp ->
			Files.writeString(tmp, "完整内容", StandardCharsets.UTF_8)
		})

		then:
		Files.exists(target)
		Files.readString(target, StandardCharsets.UTF_8) == "完整内容"
	}

	def "publish 会自动创建父目录"() {
		given:
		def target = tempDir.resolve("nested/dir/out.txt")

		when:
		AtomicFiles.publish(target, { tmp ->
			Files.writeString(tmp, "数据", StandardCharsets.UTF_8)
		})

		then:
		Files.readString(target, StandardCharsets.UTF_8) == "数据"
	}

	def "producer 抛异常时 target 不出现且临时文件被清理"() {
		given:
		def target = tempDir.resolve("out.txt")

		when:
		AtomicFiles.publish(target, { tmp ->
			Files.writeString(tmp, "半成品", StandardCharsets.UTF_8)
			throw new IOException("模拟写入中途失败")
		})

		then:
		thrown(IOException)
		// target 不应出现
		Files.notExists(target)
		// 临时文件应被清理，目录里不残留任何文件
		listDir(tempDir).isEmpty()
	}

	def "producer 抛异常时保留 target 旧内容"() {
		given:
		def target = tempDir.resolve("out.txt")
		Files.writeString(target, "旧内容", StandardCharsets.UTF_8)

		when:
		AtomicFiles.publish(target, { tmp ->
			Files.writeString(tmp, "新的半成品", StandardCharsets.UTF_8)
			throw new IOException("模拟写入中途失败")
		})

		then:
		thrown(IOException)
		// 旧内容应被保留
		Files.readString(target, StandardCharsets.UTF_8) == "旧内容"
		// 仅剩 target 一个文件，临时文件已清理
		listDir(tempDir).size() == 1
	}

	def "copy 原子复制源文件到 target"() {
		given:
		def source = tempDir.resolve("source.bin")
		Files.write(source, [1, 2, 3, 4] as byte[])
		def target = tempDir.resolve("target.bin")

		when:
		AtomicFiles.copy(source, target)

		then:
		Files.readAllBytes(target) == [1, 2, 3, 4] as byte[]
		// 源文件保持不变
		Files.exists(source)
	}

	def "move 原子移动源文件到 target"() {
		given:
		def source = tempDir.resolve("source.bin")
		Files.write(source, [9, 8, 7] as byte[])
		def target = tempDir.resolve("target.bin")

		when:
		AtomicFiles.move(source, target)

		then:
		Files.readAllBytes(target) == [9, 8, 7] as byte[]
		// 源文件已被移走
		Files.notExists(source)
	}

	def "publish 覆盖已存在的 target"() {
		given:
		def target = tempDir.resolve("out.txt")
		Files.writeString(target, "旧内容", StandardCharsets.UTF_8)

		when:
		AtomicFiles.publish(target, { tmp ->
			Files.writeString(tmp, "新内容", StandardCharsets.UTF_8)
		})

		then:
		Files.readString(target, StandardCharsets.UTF_8) == "新内容"
		// 不残留临时文件
		listDir(tempDir).size() == 1
	}

	private static List<Path> listDir(Path dir) {
		Files.list(dir).withCloseable { stream ->
			return stream.collect(Collectors.toList())
		}
	}
}
