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

package net.fabricmc.loom.test.integration.benchmark

import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestConstants
import net.fabricmc.loom.test.util.GradleProjectTestTrait

/**
 * 验证 remapped mod 失效是「固定工作目录」还是「每次新建目录」导致的。
 *
 * <p>动机：remapped_mods 落在 {@code <rootProject>/.gradle/loom-cache/remapped_mods}
 * （见 LoomFilesBaseImpl.getRemappedModCache），是<b>项目级</b>目录。
 * 而既有的 TestKit 用例默认用 {@code File.createTempDir()} 作项目目录，
 * 于是每次运行都从零生产 remapped mod，「文件被创建」必然发生一次。
 *
 * <p>真实用户的工程目录是固定的，产物持久存在；两者的配置缓存表现可能完全不同。
 * 本测试用固定目录复现真实场景，以判断那个失效点是否真的会在用户环境反复出现。
 *
 * <p>本类只观测与记录，不把当前行为固化为正确行为。
 */
class RemappedModCacheInvalidationTest extends Specification implements GradleProjectTestTrait {
	def "固定工作目录下的 remapped mod 配置缓存表现"() {
		setup:
		// 固定目录：模拟真实工程，产物在多次运行间持久
		def fixedDir = new File(LoomTestConstants.TEST_DIR, "benchmark/fixed-remapped-mods")
		fixedDir.mkdirs()

		def gradle = gradleProject(
				project: "forge/legacy/externalModDependency",
				projectDir: fixedDir)

		when:
		def runs = (1..4).collect { n ->
			def result = gradle.run(task: "build")
			println("  run${n}: reused=${result.output.contains("Configuration cache entry reused.")} " +
					"stored=${result.output.contains("Configuration cache entry stored.")}")
			result.output.readLines().findAll {
				it.contains("configuration cache cannot be reused")
			}.each { println("    run${n} 失效: ${it.trim()}") }
			return result
		}

		then:
		// 只要求不失败，具体复现情况由打印输出呈现
		runs.every { it.task(":build") != null }
	}
}
