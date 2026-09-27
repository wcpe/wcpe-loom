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

import java.util.regex.Pattern

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 覆盖「IDE 下载源码钩子」在隔离项目（Isolated Projects）模式下的行为.
 *
 * <p>根 + one + two 三个项目都应用了 Loom 且使用同一份 Minecraft（1.20.4 + officialMojangMappings），
 * 因此三个项目会为同一个源码坐标各登记一次自己的 genSources 任务；根项目的 ijDownloadSources 任务
 * 必须把所有这三个任务挂上，且整个过程不能有跨项目访问。
 */
class IdeaDownloadSourcesHookTest extends Specification implements GradleProjectTestTrait {
	private static final String INIT_SCRIPT_NAME = "ijDownloadSources.gradle"
	private static final String PROBE_TASK_NAME = "printLoomSourcesNotation"
	private static final Pattern NOTATION_PATTERN = Pattern.compile("LOOM_SOURCES_NOTATION=(\\S+)")
	private static final String REGISTRY_MARKER = "LOOM_SOURCES_REGISTRY="

	def "隔离项目模式下下载源码钩子只用任务路径字符串装配任务图"() {
		setup:
		// 夹具的根项目用 apply plugin 的旧写法引入 Loom，需要 buildSrc 把 Loom 放到脚本类路径上。
		def gradle = gradleProject(project: "multiProjectDirectLoom", version: "9.5.0")
		gradle.buildSrc("loomClasspath")
		// 源码坐标的版本部分含 mappings 标识，无法硬编码，因此在夹具根项目注入探针任务取回真实坐标。
		gradle.buildGradle << probeTask()

		when: "第一步：没有 IDE init script 时钩子必须是惰性的"
		def withoutInitScript = gradle.run(task: PROBE_TASK_NAME, isloatedProjects: true)

		then:
		withoutInitScript.task(":$PROBE_TASK_NAME").outcome == SUCCESS
		withoutInitScript.output.contains(REGISTRY_MARKER + "<absent>")

		when: "第二步：带上 IDE 生成的下载源码 init script 再跑一次"
		def notation = parseNotation(withoutInitScript.output)
		def initScript = writeInitScript(gradle.projectDir, notation)
		def result = gradle.run(
				tasks: [
					":ijDownloadSources",
					":$PROBE_TASK_NAME"
				],
				isloatedProjects: true,
				args: [
					"--init-script",
					initScript.absolutePath
				])

		then: "三个 Loom 项目都用自己的任务路径登记了这份坐标，没有跨项目读取"
		notation.startsWith("net.minecraft:minecraft-merged:1.20.4")
		notation.endsWith(":sources")
		registeredTaskPaths(result.output, notation) == ([
			":genSources",
			":one:genSources",
			":two:genSources"
		] as Set)

		and: "IDE 的钩子任务执行成功，且任务图里确实挂上了三个项目的 genSources 任务"
		result.task(":ijDownloadSources").outcome == SUCCESS
		result.task(":genSources").outcome == SUCCESS
		result.task(":one:genSources").outcome == SUCCESS
		result.task(":two:genSources").outcome == SUCCESS
	}

	// 探针任务：打印 Loom 为 IDE 下载源码准备的坐标，以及构建级登记表里该坐标已登记的任务路径。
	// 全部走动态调用，不依赖构建脚本能编译到 Loom 的类。
	private static String probeTask() {
		return """
tasks.register("$PROBE_TASK_NAME") {
	doLast {
		def extension = project.extensions.getByName("loom")
		def provider = extension.getNamedMinecraftProvider()
		provider.getDependencyTypes().each { type ->
			println "LOOM_SOURCES_NOTATION=" + provider.getMavenHelper(type).withClassifier("sources").getNotation()
		}

		def registry = gradle.extensions.findByName("loomIdeaDownloadSources")

		if (registry == null) {
			println "LOOM_SOURCES_REGISTRY=<absent>"
			return
		}

		def field = registry.getClass().getDeclaredField("taskPathsByNotation")
		field.setAccessible(true)
		field.get(registry).each { key, value -> println "LOOM_SOURCES_REGISTRY=" + key + " -> " + value }
	}
}
"""
	}

	// 与 IDE 生成的 init script 保持一致：脚本里必须出现 IjDownloadTask 与 dependencyNotation = '<坐标>'，
	// Loom 正是通过解析脚本文本来判断要下载哪份源码。
	private static File writeInitScript(File projectDir, String notation) {
		File initScript = new File(projectDir, INIT_SCRIPT_NAME)
		initScript.text = """// 模拟 IDE（IntelliJ）生成的「下载 Minecraft 源码」init script
abstract class IjDownloadTask extends DefaultTask {
	@Internal
	String dependencyNotation
}

gradle.rootProject { target ->
	target.tasks.register("ijDownloadSources", IjDownloadTask) {
		dependencyNotation = '${notation}'
		doLast {
			println "ijDownloadSources: " + dependencyNotation
		}
	}
}
"""
		return initScript
	}

	private static String parseNotation(String output) {
		def matcher = NOTATION_PATTERN.matcher(output)
		return matcher.find() ? matcher.group(1) : null
	}

	private static Set<String> registeredTaskPaths(String output, String notation) {
		def prefix = REGISTRY_MARKER + notation + " -> ["
		def line = output.readLines().find { it.startsWith(prefix) }

		if (line == null) {
			return [] as Set
		}

		return line.substring(prefix.length(), line.length() - 1).split(", ").toList().toSet()
	}
}
