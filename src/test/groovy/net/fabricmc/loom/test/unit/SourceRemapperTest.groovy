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

import net.fabricmc.loom.util.SourceRemapper

/**
 * 验证 {@code SourceRemapper} 中不依赖 {@code Project} 的重载。
 *
 * <p>这些重载是「把 sources 重映射从配置期搬到执行期」的落点：
 * classpath 与映射集由调用方备妥后，构造 Mercury 的过程不再需要项目模型。
 *
 * <p>本测试聚焦于「classpath 过滤语义」——即哪些路径会被真正加入 Mercury，
 * 因为这是原实现里唯一有实际分支逻辑的部分（其余都是顺序装配）。
 */
class SourceRemapperTest extends Specification {
	@TempDir
	Path testDir

	def "classpath 过滤语义：不存在的路径被跳过，目录仍会被加入"() {
		given:
		def existing = testDir.resolve("existing.jar")
		Files.writeString(existing, "not a real jar, but a regular file")
		def missing = testDir.resolve("missing.jar")
		def directory = Files.createDirectory(testDir.resolve("a-directory"))

		when:
		def mercury = SourceRemapper.createMercuryWithClassPath([existing, missing, directory])

		then: "只有不存在的路径被过滤掉"
		mercury.getClassPath().contains(existing)
		!mercury.getClassPath().contains(missing)

		and: "目录沿用原实现的语义：Files.exists 为真即加入"
		// 记录当前行为而非断言其为正确——原实现用 Files.exists 而非 isRegularFile。
		// 若后续要收紧为「仅普通文件」，应连同原实现一起改并在此更新断言。
		mercury.getClassPath().contains(directory)

		and: "gracefulClasspathChecks 被开启（与原实现一致）"
		mercury.isGracefulClasspathChecks()
	}

	def "空 classpath 也能构造 Mercury"() {
		when:
		def mercury = SourceRemapper.createMercuryWithClassPath([])

		then:
		mercury != null
		mercury.getClassPath().isEmpty()
	}
}
