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

package net.fabricmc.loom.test.integration.pipeline

import java.nio.file.Files

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingWriter
import net.fabricmc.mappingio.adapter.MappingDstNsReorder
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Forge 的 {@code mappings-srg-named.srg} 由执行期任务产出的接线与等价性检查.
 *
 * <p>本产物是 {@code MappingConfiguration.applyToProject} 里唯一一件真 IO（改造前在配置期做）：
 * 把 srg 命名空间的映射树整份写成 SRG 文本。用例断言四件事：
 *
 * <ul>
 *   <li><b>生产者存在且路径一致</b>：任务注册在 {@code applyToProject} 生产该文件的那条路径上
 *       （{@code MappingConfiguration.srgToNamedSrg}）。路径换一处，消费侧（DLI 配置里写死的 srg 路径）
 *       就会悬空；</li>
 *   <li><b>输入是 srg 命名空间的映射树</b>：{@code mappings-srg-migrated.tiny}，取自
 *       {@code MappingOption.WITH_SRG}——与旧路径经 {@code TinyMappingsService} 读的是同一份文件。
 *       读错映射选项（例如读 {@code mappings.tiny}）会静默产出另一份 srg；</li>
 *   <li><b>消费方拿到任务依赖</b>：{@code generateDLIConfig} 跑之前 {@code :generateSrgNamedMappings}
 *       已经跑过，且它写出的 DLI 配置里的 {@code net.minecraftforge.gradle.GradleStart.srg.srg-mcp}
 *       指向的正是这个任务产出的文件；</li>
 *   <li><b>产物逐字节一致</b>：用测试 JVM 里独立写的「读同一棵树的同一个命名空间、写成同一格式」
 *       与任务产出对 sha1。这一层抓的是接线接错（读了另一个映射选项、切错命名空间）；
 *       「配置期回退路径与任务路径共用同一份实现」由 {@code MappingConfiguration.writeSrgNamedMappings}
 *       在实现层面保证。</li>
 * </ul>
 *
 * <p>{@code useCustomMixin=false} 是刻意设置的：默认值（true）下 DLI 配置不写 srg 路径那一项，
 * 本用例就检查不到「消费方读到的是产出任务的文件」这一环。
 */
class SrgNamedMappingsTaskTest extends Specification implements GradleProjectTestTrait {
	def "srg→named 的产物由执行期任务产出，且消费方拿到任务依赖"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.20.1')
				.replace('@FORGEVERSION@', '47.2.1')
				.replace('@MAPPINGS@', "'net.fabricmc:yarn:1.20.1+build.10:v2'")
				.replace('@REPOSITORIES@', "maven { url = 'https://maven.minecraftforge.net/' }")
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		gradle.buildGradle << '''
		// 走「消费 srg→named 文件」的那条 DLI 分支（默认的 useCustomMixin=true 不写 srg 路径）
		net.fabricmc.loom.LoomGradleExtension.get(project).getForge().getUseCustomMixin().set(false)

		def srgReport = project.file('srg-report.properties')

		afterEvaluate {
			def mappingConfiguration = net.fabricmc.loom.LoomGradleExtension.get(project).getMappingConfiguration()
			def srgTask = project.tasks.findByName('generateSrgNamedMappings')
			def lines = []

			lines << 'SRG_OUTPUT=' + mappingConfiguration.srgToNamedSrg.toAbsolutePath()
			lines << 'TASK_FOUND=' + (srgTask != null)

			if (srgTask != null) {
				lines << 'TASK_OUTPUT=' + srgTask.srgFile.get().asFile.absolutePath
				lines << 'TASK_INPUT=' + srgTask.mappings.get().asFile.absolutePath
			}

			// applyToProject 的另一处副作用：mappings.jar 被登记进 mappingsFinal
			lines << 'MAPPINGS_FINAL=' + project.configurations.getByName('mappingsFinal').files.collect { it.name }.sort().join(',')

			srgReport.text = lines.join(System.lineSeparator()) + System.lineSeparator()
		}
		'''.stripIndent()

		when: "跑消费方：产出任务必须先在任务图里跑过"
		def result = gradle.run(task: "generateDLIConfig")
		def report = parseReport(new File(gradle.projectDir.toString(), "srg-report.properties"))
		def produced = new File(report.SRG_OUTPUT)
		def input = new File(report.TASK_INPUT)
		def expected = computeExpectedSrg(input)

		then: "产出任务被注册，且落在 applyToProject 原本写的那条路径上"
		report.TASK_FOUND == "true"
		report.TASK_OUTPUT == report.SRG_OUTPUT

		and: "任务的输入就是 srg 命名空间的映射树（mappings-srg.tiny 的迁移产物）"
		input.name == "mappings-srg-migrated.tiny"
		input.exists()

		and: "消费方的任务图里带着产出任务"
		result.task(":generateSrgNamedMappings").outcome == SUCCESS
		result.task(":generateDLIConfig").outcome == SUCCESS

		and: "消费方写出的 DLI 配置指向的正是产出任务写的那个文件"
		def dliConfig = new File(gradle.projectDir.toString(), ".gradle/loom-cache/launch.cfg").text
		dliConfig.contains("net.minecraftforge.gradle.GradleStart.srg.srg-mcp=" + produced.absolutePath)

		and: "applyToProject 的另一处副作用仍在：mappingsFinal 里有 mappings.jar"
		report.MAPPINGS_FINAL.contains("mappings.jar")

		and: "产物与测试 JVM 里独立算出的同一变换逐字节一致"
		produced.length() > 0
		sha1(produced) == sha1(expected)
	}

	/** 读报告文件；键值各占一行. */
	private static Map<String, String> parseReport(File file) {
		if (!file.exists()) {
			throw new FileNotFoundException("未生成报告文件: " + file)
		}

		return file.readLines().findAll { it.contains('=') }.collectEntries {
			def split = it.split('=', 2)
			[(split[0]): split[1]]
		}
	}

	/**
	 * 在测试 JVM 里独立做一遍同样的变换：读 srg 命名空间的映射树、切成 named、写成 SRG 文本.
	 *
	 * <p>刻意不调用 loom 的实现：这样「读了别的映射选项」或「切错命名空间」这类接线错误会被抓到，
	 * 而不是自我印证。
	 */
	private static File computeExpectedSrg(File mappings) {
		def tree = new MemoryMappingTree()
		MappingReader.read(mappings.toPath(), tree)

		def expected = Files.createTempFile("expected-srg-named", ".srg").toFile()
		expected.deleteOnExit()

		MappingWriter.create(expected.toPath(), MappingFormat.SRG_FILE).withCloseable { writer ->
			def visitor = new MappingSourceNsSwitch(new MappingDstNsReorder(writer, "named"), "srg")
			tree.accept(visitor)
		}

		assert expected.length() > 0
		return expected
	}

	private static String sha1(File file) {
		return java.security.MessageDigest.getInstance("SHA-1")
				.digest(file.bytes)
				.collect { String.format("%02x", it) }
				.join()
	}
}
