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
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 守护 {@code exportLoomProjectData} 在配置缓存下可运行、且输入覆盖导出内容.
 *
 * <p>该任务曾把 {@code LoomProjectData.fromProject(getProject())} 直接写在 {@code @TaskAction} 里，
 * 执行期访问 {@code Project} 在配置缓存下会抛 {@code InvalidUserCodeException}；同时它只把 mod 的
 * JSON 字符串登记为输入，而 DTO 里每个 mod 还带着 mixin 配置/AT-AW/javadoc/原始元数据文件的
 * <b>字节内容</b>，因此改这些资源不会让任务失效，构建缓存可能还原出「JSON 新、资源旧」的错配数据。
 *
 * <p>这两个问题都不会在单项目构建里暴露：只有解析 {@code loomProjectDataElements}（多项目消费方）
 * 才会调度该任务。所以本用例显式请求它，并用 {@code --configuration-cache-problems=fail}
 * 把任何配置缓存问题升级成构建失败——否则问题只是警告，用例会静默通过。
 */
class ExportLoomProjectDataTest extends Specification implements GradleProjectTestTrait {
	private static final String TASK = ":lib:exportLoomProjectData"
	private static final String DATA_FILE = "lib/build/loom/project-data.json"

	def "exportLoomProjectData runs under the configuration cache twice"() {
		setup:
		def gradle = gradleProject(project: "projectDataColdStart", version: DEFAULT_GRADLE)

		when: "首次运行：必须能存下配置缓存条目并真正产出数据文件"
		def first = gradle.run(tasks: [TASK], args: CONFIG_CACHE_STRICT)

		then: "配置缓存问题一律致命，任务成功产出文件"
		first.task(TASK).outcome == SUCCESS
		dataFile(gradle).exists()
		first.output.contains("Configuration cache entry stored")

		when: "第二次运行：必须复用配置缓存条目，且任务判定为最新"
		def second = gradle.run(tasks: [TASK], args: CONFIG_CACHE_STRICT)

		then: "复用条目意味着执行期确实没有触碰 Project（否则存储阶段就会失败）"
		second.task(TASK).outcome == UP_TO_DATE
		second.output.contains("Reusing configuration cache")
	}

	def "exported data is invalidated when the project data changes"() {
		setup:
		def gradle = gradleProject(project: "projectDataColdStart", version: DEFAULT_GRADLE)
		def file = dataFile(gradle)

		when: "先产出一份数据"
		def first = gradle.run(tasks: [TASK], args: CONFIG_CACHE_STRICT)

		then: "导出的是完整 DTO（含项目身份），依赖方据此区分不同项目的同名数据"
		first.task(TASK).outcome == SUCCESS
		file.exists()
		def exported = file.text
		exported.contains("\"projectPath\"")
		exported.contains("\":lib\"")

		when: "改掉直接影响 DTO 的映射依赖后重跑（版本号不在 DTO 里，改它不会影响导出内容）"
		def buildFile = new File(gradle.projectDir, "lib/build.gradle")
		buildFile.text = buildFile.text.replace(
				'mappings "net.fabricmc:yarn:1.20.4+build.3:v2"',
				'mappings "net.fabricmc:yarn:1.20.4+build.1:v2"')
		def second = gradle.run(tasks: [TASK], args: CONFIG_CACHE_STRICT)

		then: "映射标识是导出内容的一部分，任务必须重跑并写出不同的数据"
		second.task(TASK).outcome == SUCCESS
		file.text != exported
		file.text.contains("build.1")
	}

	/** 配置缓存问题即失败；不这样设定时问题只是警告，用例会静默通过。 */
	private static final List<String> CONFIG_CACHE_STRICT = [
		"--configuration-cache",
		"--configuration-cache-problems=fail",
	]

	private static File dataFile(GradleProjectTestTrait.GradleProject gradle) {
		return new File(gradle.projectDir, DATA_FILE)
	}
}
