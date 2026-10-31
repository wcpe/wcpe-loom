/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.test.integration

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.ZipUtils

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class AccessWidenerTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "accesswidener (gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "accesswidener", version: version)
		when:
		def result = gradle.run(task: "build")
		then:
		result.task(":build").outcome == SUCCESS
		gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "modid.accesswidener") == expected().replaceAll('\r', '')
		where:
		version << STANDARD_TEST_VERSIONS
	}

	String expected() {
		new File("src/test/resources/accesswidener/expected.accesswidener").text
	}

	@Unroll
	def "transitive accesswidener (gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "transitiveAccesswidener", version: version)
		ZipUtils.pack(new File(gradle.projectDir, "dummyDependency").toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "transitive accesswidener official production (gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "transitiveAccesswidenerOfficialProd", version: version)
		ZipUtils.pack(new File(gradle.projectDir, "dummyDependency").toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "transitive accesswidener official production genSources (gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "transitiveAccesswidenerOfficialProd", version: version)
		ZipUtils.pack(new File(gradle.projectDir, "dummyDependency").toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when:
		def result = gradle.run(task: "genSources")

		then:
		result.task(":genSources").outcome == SUCCESS

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "multiple class tweakers in a dependency (gradle #version)"() {
		setup: '一个 mod 带两个 class tweaker：一个只在 client 生效，一个 universal'
		def gradle = gradleProject(project: "multiClassTweaker", version: version)
		ZipUtils.pack(new File(gradle.projectDir, "dummyDependency").toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when:
		def result = gradle.run(task: "build")

		then: '改造前这里会在 AccessWidenerUtils 抛 UnsupportedOperationException("TODO: support multiple class tweakers")，走不到 SUCCESS'
		result.task(":build").outcome == SUCCESS

		// 内容级正确性（两个声明各自重映射成 named、逐个写回不互相覆盖）由单元测试
		// AccessWidenerUtilsTest 覆盖（它造 jar 直接验字节）。
		// 本用例只需证明：真实的 remapMods 链路在多声明下能跑完 —— 旧的单声明路径
		// 在读到第二个声明时就会抛异常，因此“构建成功”本身就是回归判据。

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "invalid (#awLine)"() {
		setup:
		def gradle = gradleProject(project: "accesswidener", version: version)
		new File(gradle.projectDir, "src/main/resources/modid.accesswidener").append(awLine)

		when:
		def result = gradle.run(task: "check", expectFailure: true)

		then:
		result.output.contains(error)

		where:
		awLine 																					| error																											| version
		'accessible\tclass\tnet/minecraft/DoesntExists'											| "Could not find class (net/minecraft/DoesntExists) on line 10"												| DEFAULT_GRADLE
		'accessible\tfield\tnet/minecraft/screen/slot/Slot\tabc\tI'								| "Could not find field (abc:I) in class (net/minecraft/screen/slot/Slot) on line 10"							| DEFAULT_GRADLE
		'accessible\tmethod\tnet/minecraft/client/main/Main\tmain\t([Ljava/lang/NotAString;)V'	| "Could not find method (main([Ljava/lang/NotAString;)V) in class (net/minecraft/client/main/Main) on line 10"	| DEFAULT_GRADLE
	}
}
