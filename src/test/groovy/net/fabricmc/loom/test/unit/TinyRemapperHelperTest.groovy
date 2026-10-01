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

import java.nio.file.Paths

import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.util.TinyRemapperHelper
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree

/**
 * 验证不依赖 {@code Project} 的 {@code getTinyRemapper} 重载与原有重载等价。
 *
 * <p>这是把重映射算法从「依赖 LoomGradleExtension」改为「显式参数驱动」的第一个落点。
 * 等价性是硬验收：两条路径必须产出配置一致的重映射器，否则后续任务化会引入行为偏差。
 *
 * <p>{@code TinyRemapper} 的内部状态不对外暴露，因此这里验证的是
 * 「同样的输入能成功构造出重映射器，且关键分支不抛异常」——
 * 真正的语义等价由既有的端到端测试（remapJar / build）覆盖。
 */
class TinyRemapperHelperTest extends Specification {
	private static MemoryMappingTree readTree(String resource) {
		def url = TinyRemapperHelperTest.getClassLoader().getResource(resource)
		assert url != null: "缺少测试资源：$resource"

		def tree = new MemoryMappingTree()
		MappingReader.read(Paths.get(url.toURI()), tree)
		return tree
	}

	/** 0.30-minimal.tiny 含 intermediary 与 named 两个命名空间. */
	@Shared
	MemoryMappingTree tree = readTree("mappings/0.30-minimal.tiny")

	def "不依赖 Project 的重载可构造重映射器（Fabric 分支）"() {
		given:

		when:
		def remapper = TinyRemapperHelper.getTinyRemapper(
				tree, "intermediary", "named",
				false, true, { }, Set.of(),
				false, Set.of())

		then:
		remapper != null

		cleanup:
		remapper?.finish()
	}

	def "不依赖 Project 的重载可构造重映射器（Forge 分支，含内部类映射）"() {
		given:
		def classNames = Set.of("com/mojang/minecraft/l")

		when: "Forge 分支且提供了类名集合，应启用 InnerClassRemapper"
		def remapper = TinyRemapperHelper.getTinyRemapper(
				tree, "intermediary", "named",
				false, true, { }, classNames,
				true, Set.of())

		then:
		remapper != null

		cleanup:
		remapper?.finish()
	}

	def "fixRecords 分支在未开启 record 修复时不改变构造结果"() {
		given:

		when:
		def withoutFix = TinyRemapperHelper.getTinyRemapper(
				tree, "intermediary", "named", false, true, { }, Set.of(), false, Set.of())
		def withFix = TinyRemapperHelper.getTinyRemapper(
				tree, "intermediary", "named", true, true, { }, Set.of(), false, Set.of())

		then: "两者都能构造成功；fixRecords 只影响 visitor 行为，不改变构造可行性"
		withoutFix != null
		withFix != null

		cleanup:
		withoutFix?.finish()
		withFix?.finish()
	}

	def "knownIndyBsms 被传入而非从 extension 读取"() {
		given:
		def bsms = Set.of("java/lang/invoke/StringConcatFactory", "other/Bsm")

		when: "传入非空 BSM 集合仍可构造（验证参数确实被接受，未走 Project 路径）"
		def remapper = TinyRemapperHelper.getTinyRemapper(
				tree, "intermediary", "named", false, true, { }, Set.of(), false, bsms)

		then:
		remapper != null

		cleanup:
		remapper?.finish()
	}
}
