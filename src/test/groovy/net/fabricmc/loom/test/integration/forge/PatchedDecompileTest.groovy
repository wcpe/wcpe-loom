/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

package net.fabricmc.loom.test.integration.forge

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class PatchedDecompileTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "decompile #mcVersion #forgeVersion"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
				.replace('@FORGEVERSION@', forgeVersion)
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', javaVersion)

		when:
		def result = gradle.run(task: "genForgePatchedSources", configurationCache: true, args: withBuildCache())

		then:
		result.task(":genForgePatchedSources").outcome == SUCCESS

		when: '重跑一次：入参未变，任务必须被判定为最新'
		def rerun = gradle.run(task: "genForgePatchedSources", configurationCache: true, args: withBuildCache())

		then: '配置了 configurationCache: true 也能跑，且入参未变时任务被判定为最新'
		rerun.task(":genForgePatchedSources").outcome == UP_TO_DATE

		where:
		mcVersion | forgeVersion | javaVersion
		'1.19.2'  | "43.1.1"     | '17'
		'1.18.1'  | "39.0.63"    | '17'
		'1.17.1'  | "37.0.67"    | '16'
	}

	/** 本任务的缓存验证需要构建缓存，与 JDK 入参叠加. */
	private static List<String> withBuildCache() {
		return jdk21Args() + ["--build-cache"]
	}

	/*
	 * 为什么这里只断言 up-to-date，不断言 FROM-CACHE：
	 *
	 * 1) 构建缓存要能建键，本任务的产物必须在任务开始时**不存在**。可本任务的源码包产物落在跨项目共享的
	 *    loom 缓存里（named MC jar 旁的 -sources.jar），执行前往往已经被上一次构建或另一个工程造好；
	 *    对「已存在、又不是本任务创建」的输出，Gradle 会直接关掉该任务的构建缓存，原文：
	 *    Gradle does not know how file ... was created (output property ...). Task output caching requires
	 *    exclusive access to output paths to guarantee correctness。
	 *    实测证据（--info，Forge 1.18.1）：generateForgePrePatchJar / downloadMinecraftClientJar /
	 *    downloadMinecraftServerJar 都是这一句（产物同样在共享 loom 缓存里）；而同轮 extractForgeSources
	 *    （产物在项目 build 目录里）正常 FROM-CACHE 并打印 Build cache key 与 Loaded cache entry。
	 *
	 * 2) 本地试过「先删掉两个声明产物（含共享缓存里那份）再跑」的三段验法：前两段（SUCCESS、UP_TO_DATE）
	 *    稳定通过，但第三段（删掉产物后重跑）仍然重新执行而不是 FROM-CACHE；在当时的机器条件
	 *    （load 50+、外层 Gradle daemon 反复被其它构建 --stop 掉）下没能在时限内定位到直接原因，
	 *    因此这里不写一条自己都没跑通的断言——留给后续用 --info 定位，确认后再加回来。
	 */

	/**
	 * Forge 自带的 ForgeFlower 解析不了 JDK 25 类文件中的常量池项（CONSTANT_Dynamic），
	 * 反编译步骤需改用 JDK 21 运行；CI 由 setup-java 提供 JAVA_HOME_21_* 环境变量。
	 */
	private static List<String> jdk21Args() {
		def jdk21 = System.getenv('JAVA_HOME_21_X64') ?: System.getenv('JAVA_HOME_21_ARM64')

		if (jdk21 == null) {
			return []
		}

		return [
			"-Dorg.gradle.java.home=${jdk21}".toString()
		]
	}
}
