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

import java.nio.file.Files
import java.nio.file.Path

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 执行期映射产物的缺失状态不应成为配置输入，就绪应交给声明了输出的生产任务.
 *
 * <p>通过真实 MappingConfiguration 子类区分任务产出与配置期产出，不启动 Minecraft 生产链.
 */
class TaskProducedMappingsCacheTest extends Specification {
	private static final String CONFIGURED = "MAPPINGS_CONFIGURED"
	private static final String TINY = "tiny\t2\t0\tintermediary\tnamed\n"

	@TempDir
	Path projectDir

	def setup() {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'task-mappings-cache-test'\n")
		Files.writeString(projectDir.resolve("build.gradle"), """
			plugins {
				id 'top.wcpe.loom-repositories'
			}

			import java.nio.file.Files
			import java.nio.file.Path
			import dev.architectury.loom.mappings.MappingOption
			import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration

			class FixtureMappingConfiguration extends MappingConfiguration {
				private final boolean taskProduced

				FixtureMappingConfiguration(Path path, boolean taskProduced) {
					super('fixture', path)
					this.taskProduced = taskProduced
				}

				@Override
				protected boolean isTaskProducedMappings(MappingOption option) {
					return (taskProduced && option == MappingOption.DEFAULT) || super.isTaskProducedMappings(option)
				}
			}

			def taskProduced = providers.gradleProperty('fixture.taskProduced').map { it.toBoolean() }.getOrElse(true)
			def workingDirectory = layout.buildDirectory.dir('mappings').get().asFile.toPath()
			def mappings = new FixtureMappingConfiguration(workingDirectory, taskProduced)
			def mappingsFile = mappings.getMappingsPath(MappingOption.DEFAULT).toFile()
			println '${CONFIGURED}'

			abstract class WriteMappings extends DefaultTask {
				@OutputFile
				abstract RegularFileProperty getMappingsFile()

				@Input
				abstract Property<String> getMappingsText()

				@TaskAction
				void writeMappings() {
					def output = mappingsFile.get().asFile.toPath()
					Files.createDirectories(output.parent)
					Files.writeString(output, mappingsText.get())
					println 'MAPPINGS_WRITTEN'
				}
			}

			tasks.register('writeMappings', WriteMappings) {
				getMappingsFile().fileValue(mappingsFile)
				getMappingsText().set('tiny\\t2\\t0\\tintermediary\\tnamed\\n')
			}
			""".stripIndent())
	}

	def "任务首次生成及删除后重建映射文件均立即复用配置缓存"() {
		given:
		assert !Files.exists(mappingFile())

		when:
		def first = runner().build()

		then:
		first.output.contains(CONFIGURED)
		first.task(":writeMappings").outcome == TaskOutcome.SUCCESS
		Files.readString(mappingFile()) == TINY

		when: "首轮由任务创建的文件不应使下一轮重新配置"
		def second = runner().build()

		then:
		reused(second)
		second.task(":writeMappings").outcome == TaskOutcome.UP_TO_DATE

		when: "任务输出被删除时由任务重建，而不是让配置期存在性检查触发失效"
		Files.delete(mappingFile())
		def afterDeletion = runner().build()

		then:
		reused(afterDeletion)
		afterDeletion.task(":writeMappings").outcome == TaskOutcome.SUCCESS
		Files.readString(mappingFile()) == TINY
	}

	def "配置期自行产出的映射缺失时仍必须明确失败"() {
		when:
		def failed = runner("-Pfixture.taskProduced=false").buildAndFail()

		then:
		failed.output.contains("Mapping option DEFAULT found but file does not exist!")
		!failed.output.contains(CONFIGURED)
		!Files.exists(mappingFile())
	}

	private Path mappingFile() {
		return projectDir.resolve("build/mappings/mappings.tiny")
	}

	private GradleRunner runner(String... options) {
		def arguments = [
			"writeMappings",
			"--configuration-cache",
			"--configuration-cache-problems=fail",
			"--console=plain",
			"--stacktrace",
			"--gradle-user-home",
			projectDir.resolve("gradle-home").toString()
		] + options.toList()
		return GradleRunner.create()
				.withProjectDir(projectDir.toFile())
				.withPluginClasspath()
				.withGradleVersion("9.5.0")
				.withArguments(arguments)
	}

	private static boolean reused(BuildResult result) {
		assert result.output.contains("Configuration cache entry reused")
		assert !result.output.contains(CONFIGURED)
		return true
	}
}
