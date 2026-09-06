/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 WCPE
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

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class MultiProjectDirectPluginTest extends Specification implements GradleProjectTestTrait {
	def "multiple subprojects can apply Loom directly in one Gradle root"() {
	setup:
		def gradle = gradleProject(project: "multiProjectDirectLoom", version: "9.5.0")
		gradle.buildSrc("loomClasspath")

		when:
		def result = gradle.run(
				tasks: [":one:configureClientLaunch", ":two:configureClientLaunch"],
				isloatedProjects: true)
		// 首次执行会创建运行配置输出，isolated-projects 会因此使下一次重新存储；
		// 输出稳定后第三次必须真正复用同一配置缓存条目。
		def warm = gradle.run(
				tasks: [":one:configureClientLaunch", ":two:configureClientLaunch"],
				isloatedProjects: true)
		def reused = gradle.run(
				tasks: [":one:configureClientLaunch", ":two:configureClientLaunch"],
				isloatedProjects: true)

		then:
		result.task(":one:configureClientLaunch").outcome == SUCCESS
		result.task(":two:configureClientLaunch").outcome == SUCCESS
		warm.task(":one:configureClientLaunch").outcome in [SUCCESS, UP_TO_DATE]
		warm.task(":two:configureClientLaunch").outcome in [SUCCESS, UP_TO_DATE]
		reused.task(":one:configureClientLaunch").outcome in [SUCCESS, UP_TO_DATE]
		reused.task(":two:configureClientLaunch").outcome in [SUCCESS, UP_TO_DATE]
		reused.output.contains("Configuration cache entry reused.")
	}
}
