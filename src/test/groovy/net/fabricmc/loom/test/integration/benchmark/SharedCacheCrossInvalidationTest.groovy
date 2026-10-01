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
 * 验证「多工程共用共享缓存是否让彼此的配置缓存失效」。
 *
 * <p>场景来自本机实况：仓库有 5 个 git worktree 同时存在，全部未覆盖
 * {@code fabric.loom.cache.dir}，因此共用同一个
 * {@code ~/.gradle/caches/fabric-loom}（实测内含 1.12.2 / 1.20.1 / 1.20.2 /
 * 1.21.1 / 26.2 五个 MC 版本）。
 *
 * <p>假设：Loom 在配置阶段读取该共享目录（mapped jar、mappings 产物等），
 * Gradle 把这些读取登记为配置缓存输入；于是工程 A 写入共享产物后，
 * 工程 B 的配置缓存条目即被判定失效 —— 即使 B 自己的源码与依赖都没变。
 *
 * <p>本测试让两个<b>不同项目目录</b>共用<b>同一个 gradle user home</b>，
 * 从而共用 fabric-loom 共享缓存，然后交替构建，观察彼此是否互相使失效。
 *
 * <p>只观测与记录，不把当前行为固化为正确行为。
 */
class SharedCacheCrossInvalidationTest extends Specification implements GradleProjectTestTrait {
	def "两个工程共用共享缓存时的配置缓存表现"() {
		setup:
		// 共用的 gradle user home —— fabric-loom 共享缓存位于其下的 caches/fabric-loom
		def sharedHome = new File(LoomTestConstants.TEST_DIR, "benchmark/shared-cross-home")
		sharedHome.mkdirs()

		// 两个独立工程目录，模拟两个 worktree
		def projectA = new File(LoomTestConstants.TEST_DIR, "benchmark/cross-project-a")
		def projectB = new File(LoomTestConstants.TEST_DIR, "benchmark/cross-project-b")
		projectA.mkdirs()
		projectB.mkdirs()

		def gradleA = gradleProject(
				project: "minimalBase",
				projectDir: projectA,
				gradleHomeDir: sharedHome)
		gradleA.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "${net.fabricmc.loom.test.LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
            """.stripIndent()

		def gradleB = gradleProject(
				project: "minimalBase",
				projectDir: projectB,
				gradleHomeDir: sharedHome)
		gradleB.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "${net.fabricmc.loom.test.LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
            """.stripIndent()

		when: "两个工程各自建立稳定的配置缓存条目"
		def a1 = gradleA.run(task: "build")
		def a2 = gradleA.run(task: "build")
		def b1 = gradleB.run(task: "build")
		def b2 = gradleB.run(task: "build")

		then:
		report("A 首次", a1.output)
		report("A 二次", a2.output)
		report("B 首次", b1.output)
		report("B 二次", b2.output)
		a1.task(":build") != null

		and: "关键步骤：A 再构建一次后，B 是否仍能复用自己的配置缓存"
		// 若 A 的构建改写了共享缓存，而 B 的配置阶段观察了那些文件，
		// 则这里 B 应当失效——这正是用户环境可能出现「反复失效」的机理。
		def a3 = gradleA.run(task: "build")
		def b3 = gradleB.run(task: "build")

		report("A 三次", a3.output)
		def b3Reused = report("B 三次（A 刚构建完）", b3.output)

		println("  >>> B 在 A 构建后仍复用配置缓存: ${b3Reused}")

		// 只要求不失败；是否互相使失效由打印输出呈现
		b3.task(":build") != null
	}

	private static boolean report(String label, String output) {
		boolean reused = output.contains("Configuration cache entry reused.")
		boolean stored = output.contains("Configuration cache entry stored.")
		println("  ${label.padRight(22)} reused=${reused} stored=${stored}")

		output.readLines().findAll { it.contains("configuration cache cannot be reused") }
		.each { println("      ${label} 失效: ${it.trim()}") }

		return reused
	}
}
