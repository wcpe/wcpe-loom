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

package net.fabricmc.loom.test.integration.forge

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 验证 Forge 源码包由声明式解压任务产出，并且在输入不变时可复用（增量/构建缓存）。
 */
class ForgeExtractSourcesTest extends Specification implements GradleProjectTestTrait {
	def "extractForgeSources 是可复用的声明式任务"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace('@MCVERSION@', '1.18.1')
				.replace('@FORGEVERSION@', '39.0.63')
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')

		when:
		def result = gradle.run(task: "extractForgeSources", configurationCache: false, args: jdk21Args())

		then: "首次执行展开源码包"
		result.task(":extractForgeSources").outcome == SUCCESS

		and: "输出目录内确实包含 .java 源码"
		def outputDir = new File(gradle.projectDir, "build/loom/forgeSources")
		outputDir.directory
		collectJavaFiles(outputDir).size() > 0

		when: "输入不变时再次执行"
		def second = gradle.run(task: "extractForgeSources", configurationCache: false, args: jdk21Args())

		then: "任务被判定为最新，不再重复解压"
		second.task(":extractForgeSources").outcome == UP_TO_DATE
	}

	private static List<File> collectJavaFiles(File dir) {
		def result = []
		dir.eachFileRecurse { file ->
			if (file.isFile() && file.name.endsWith(".java")) {
				result << file
			}
		}
		return result
	}

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
