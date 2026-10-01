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

package net.fabricmc.loom.test.unit.spec

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.spec.SpecStore

/**
 * 验证 L2 规格层持久化存储的行为.
 *
 * <p>该存储只保存**派生数据**：任何条目丢失都只应导致「重新解析一次」，
 * 不能影响正确性。因此这里重点确认三件事：
 * 未命中返回空、写入后可命中、输入变化后自然失效。
 */
class SpecStoreTest extends Specification {
	@TempDir
	Path tempDir

	private SpecStore store() {
		return new SpecStore(tempDir.resolve("userCache"))
	}

	def "未命中时返回空"() {
		when:
		def result = store().load("ns", "identity-1", "disc")

		then:
		result.isEmpty()
	}

	def "写入后可命中"() {
		given:
		def s = store()

		when:
		s.store("ns", "identity-1", "disc", "payload")
		def result = s.load("ns", "identity-1", "disc")

		then:
		result.isPresent()
		result.get() == "payload"
	}

	def "不同命名空间互不干扰"() {
		given:
		def s = store()
		s.store("ns-a", "id", "disc", "A")
		s.store("ns-b", "id", "disc", "B")

		expect:
		s.load("ns-a", "id", "disc").get() == "A"
		s.load("ns-b", "id", "disc").get() == "B"
	}

	def "不同维度互不干扰"() {
		given:
		def s = store()
		s.store("ns", "id", "1.20.1", "v1201")
		s.store("ns", "id", "1.21.1", "v1211")

		expect:
		s.load("ns", "id", "1.20.1").get() == "v1201"
		s.load("ns", "id", "1.21.1").get() == "v1211"
	}

	def "空内容可作为「已解析但无结果」的哨兵存下来"() {
		given:
		def s = store()

		when:
		s.store("ns", "id", "missing-version", "")

		then: "必须与「未命中」区分开，否则每次配置都会重新解析全文清单"
		s.load("ns", "id", "missing-version").isPresent()
		s.load("ns", "id", "missing-version").get().isEmpty()
	}

	def "重写同名条目会覆盖旧内容"() {
		given:
		def s = store()
		s.store("ns", "id", "disc", "old")

		when:
		s.store("ns", "id", "disc", "new")

		then:
		s.load("ns", "id", "disc").get() == "new"
	}

	def "维度值含路径分隔符时被安全处理"() {
		given:
		def s = store()

		when: "维度来自外部输入，可能含 / \\ : 等字符"
		s.store("ns", "id", "a/b\\c:d", "payload")

		then: "不抛异常且能读回"
		s.load("ns", "id", "a/b\\c:d").get() == "payload"
	}

	def "inputIdentity 随文件大小与修改时间变化"() {
		given:
		def file = tempDir.resolve("input.json")
		Files.writeString(file, "content")

		when:
		def first = SpecStore.inputIdentity(file)
		Files.writeString(file, "content-changed-and-longer")
		def second = SpecStore.inputIdentity(file)

		then:
		first.isPresent()
		second.isPresent()
		first.get() != second.get()
	}

	def "inputIdentity 对不存在的文件返回空"() {
		expect:
		SpecStore.inputIdentity(tempDir.resolve("nope.json")).isEmpty()
	}
}
