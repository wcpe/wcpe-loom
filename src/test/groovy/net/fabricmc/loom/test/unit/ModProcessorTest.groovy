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

package net.fabricmc.loom.test.unit

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.pipeline.RemapModsTask

/**
 * 验证 {@code RemapModsTask.collectRemapClasspath} 的过滤语义.
 *
 * <p>该方法是把「从项目模型收集 classpath」与「执行重映射」分开的落点。
 * 两条必须保持的规则：排除本轮正在重映射的输入，且不存在的路径不进入 classpath。
 */
class ModProcessorTest extends Specification {
	@TempDir
	Path testDir

	private File file(String name) {
		def f = testDir.resolve(name).toFile()
		f.text = "content"
		return f
	}

	def "排除本轮正在重映射的输入"() {
		given:
		def beingRemapped = file("mod-being-remapped.jar")
		def other = file("other-mod.jar")

		when:
		def classpath = RemapModsTask.collectRemapClasspath(
				[beingRemapped, other], Set.of(beingRemapped))

		then:
		classpath == [other.toPath()]
	}

	def "不存在的路径被跳过"() {
		given:
		def existing = file("exists.jar")
		def missing = testDir.resolve("missing.jar").toFile()

		when:
		def classpath = RemapModsTask.collectRemapClasspath([existing, missing], Set.of())

		then:
		classpath == [existing.toPath()]
	}

	def "目录被跳过（仅接受普通文件）"() {
		given:
		def dir = Files.createDirectory(testDir.resolve("lib-dir")).toFile()
		def jar = file("lib.jar")

		when:
		def classpath = RemapModsTask.collectRemapClasspath([dir, jar], Set.of())

		then: "这里用 isRegularFile 过滤，比 SourceRemapper 的 Files.exists 更严格"
		classpath == [jar.toPath()]
	}

	def "空输入返回空 classpath"() {
		when:
		def classpath = RemapModsTask.collectRemapClasspath([], Set.of())

		then:
		classpath.isEmpty()
	}

	def "所有输入都被排除时返回空"() {
		given:
		def a = file("a.jar")
		def b = file("b.jar")

		when:
		def classpath = RemapModsTask.collectRemapClasspath([a, b], Set.of(a, b))

		then:
		classpath.isEmpty()
	}
}
