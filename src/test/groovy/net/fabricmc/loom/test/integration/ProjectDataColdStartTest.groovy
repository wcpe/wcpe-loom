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

package net.fabricmc.loom.test.integration

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 覆盖「依赖项目的跨项目数据尚未产出」这一冷启动常态。
 *
 * <p>配置一个依赖了 Loom 项目的项目时，Loom 会去读依赖项目 {@code build/loom/project-data.json}
 * 的路径。该路径只能由依赖项目自己的 {@code exportLoomProjectData} 任务在执行期产出：冷启动
 * （依赖项目从未构建过）时文件必然不存在。这属于配置期常态，绝不能升级成失败——否则消费方
 * 的配置期就会直接炸掉，构建根本无法启动。
 *
 * <p>触发条件是 {@code runtimeClasspath} / {@code compileClasspath} 上必须保留一个
 * {@code ProjectDependency}，夹具因此刻意使用<b>普通</b> {@code implementation project(":lib")}：
 * {@code modImplementation project(":lib")} 会被 Loom 的 mod 重映射换成普通文件依赖，
 * classpath 上不再有 {@code ProjectDependency}，跨项目数据链路就不会被走到。夹具还把
 * {@code :app} 写在 {@code :lib} 之前，令消费方先被配置，从而使 Loom 的内存共享表未命中、
 * 真实回退到读取数据文件这条路径。
 *
 * <p>两类结果必须互不混淆：
 * <ul>
 *     <li>文件不存在：正常返回空数据，构建照常启动；</li>
 *     <li>文件存在却读不出来（例如被写坏）：仍然显式失败，不能被伪装成「还没有数据」。</li>
 * </ul>
 */
class ProjectDataColdStartTest extends Specification implements GradleProjectTestTrait {
	/** 由依赖项目 {@code :lib} 的 exportLoomProjectData 任务产出，位于其 build 目录下。 */
	private static final String DEPENDENCY_DATA_FILE = "lib/build/loom/project-data.json"

	def "consumer project configures and builds when the dependency has not exported its data yet"() {
		setup:
		def gradle = gradleProject(project: "projectDataColdStart", version: DEFAULT_GRADLE)

		and: "冷启动前提：两个子项目都没有 build 目录，跨项目数据文件从未产出过"
		deleteBuildDirectories(gradle)
		assert !dependencyDataFile(gradle).exists()
		assert !new File(gradle.projectDir, "app/build").exists()

		when: "只请求消费方 :app 的任务，依赖项目的 exportLoomProjectData 因此不会被调度"
		def result = gradle.run(tasks: [":app:build"])

		then: "依赖项目的数据尚未产出属于配置期常态，构建必须能启动并跑完"
		result.task(":app:build").outcome == SUCCESS

		and: "整个过程都没有产出该文件，证明构建确实是在「数据文件不存在」的前提下成功的"
		!dependencyDataFile(gradle).exists()
	}

	def "a data file that exists but cannot be parsed still fails the build"() {
		setup:
		def gradle = gradleProject(project: "projectDataColdStart", version: DEFAULT_GRADLE)
		def dataFile = dependencyDataFile(gradle)
		dataFile.parentFile.mkdirs()
		dataFile.text = "{ \"projectPath\": \":lib\" 这不是合法的项目数据"

		when: "文件存在但不可解析：这是真失败，不能静默降级成「依赖项目没有数据」"
		def result = gradle.run(tasks: [":app:build"], expectFailure: true)

		then:
		result.output.contains(dataFile.absolutePath)
		result.output.contains("跨项目数据无法解析") || result.output.contains("JsonSyntaxException")
	}

	/**
	 * 显式清掉两个子项目的构建产物。
	 *
	 * <p>用例本身依赖临时目录的新鲜度，但那是实现细节；把它做成显式步骤后，即使夹具目录被复用
	 * （例如改用 {@code sharedFiles: true}），用例也不会在「其实是热启动」的情况下静默通过。
	 */
	private static void deleteBuildDirectories(GradleProjectTestTrait.GradleProject gradle) {
		new File(gradle.projectDir, "app/build").deleteDir()
		new File(gradle.projectDir, "lib/build").deleteDir()
	}

	private static File dependencyDataFile(GradleProjectTestTrait.GradleProject gradle) {
		return new File(gradle.projectDir, DEPENDENCY_DATA_FILE)
	}
}
