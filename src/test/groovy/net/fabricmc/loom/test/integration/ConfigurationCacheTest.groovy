/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.PRE_RELEASE_GRADLE
import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.FAILED

class ConfigurationCacheTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "Configuration cache (task #task)"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: PRE_RELEASE_GRADLE)
		gradle.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.4'
                mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                modImplementation 'net.fabricmc.fabric-api:fabric-api:0.95.4+1.20.4'
            }
            """.stripIndent()
		when:
		def result = gradle.run(task: task, isloatedProjects: false)
		def result2 = gradle.run(task: task, isloatedProjects: false)

		then:
		result.task(":${task}").outcome != FAILED
		result2.task(":${task}").outcome != FAILED

		where:
		task                      | _
		"help"                    | _
		"configureClientLaunch"   | _
		"jar"                     | _
		"check"                   | _
		"remapSourcesJar"         | _
		"build"                   | _
	}

	// Test GradleUtils.configurationInputFile invalidates the cache when the file changes
	@Unroll
	def "File input (#version)"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: version)
		gradle.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.4'
                mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }

			abstract class TestTask extends DefaultTask {
				@Input
				abstract Property<String> getModVersion()

				@TaskAction
				void run() {
					println "Version: " + modVersion.get()
				}
			}

			println "Configuring task testTask"
            tasks.register('testTask', TestTask) {
            	modVersion.set(loom.getModVersion()) // loom.getModVersion() returns a String
			}
            """.stripIndent()

		def fabricModJson = new File(gradle.projectDir, "src/main/resources/fabric.mod.json")
		fabricModJson.parentFile.mkdirs()
		fabricModJson.text = fmj("1.0.0")

		when:
		def result = gradle.run(task: "testTask")
		def result2 = gradle.run(task: "testTask")
		fabricModJson.text = fmj("2.0.0")
		def result3 = gradle.run(task: "testTask")

		then:
		// Test that the cache is created
		result.task(":testTask").outcome != FAILED
		result.output.contains("Calculating task graph as no cached configuration is available for tasks: testTask")
		result.output.contains("Configuring task testTask")
		result.output.contains("Version: 1.0.0")

		// Test that the cache is reused when nothing has changed
		result2.task(":testTask").outcome != FAILED
		!result2.output.contains("Calculating task graph")
		!result2.output.contains("Configuring task testTask")
		result2.output.contains("Version: 1.0.0")

		// Test that the cache is invalidated when the file changes
		result3.task(":testTask").outcome != FAILED
		result3.output.contains("Calculating task graph as configuration cache cannot be reused because file 'src/main/resources/fabric.mod.json' has changed.".replace("/", File.separator))
		result3.output.contains("Configuring task testTask")
		result3.output.contains("Version: 2.0.0")

		where:
		version << STANDARD_TEST_VERSIONS
	}

	// 上一次构建被杀（或另一构建持锁）时会在用户缓存里留下锁文件，
	// 该锁文件的清理不得让后续构建反复失效配置缓存
	@Unroll
	def "Leftover cache lock file (#version)"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: version)
		gradle.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.4'
                mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }

			import net.fabricmc.loom.util.Checksum
			println("%%" + Checksum.of(getProject()).sha1().hex() + "%%")
            """.stripIndent()

		when:
		def result1 = gradle.run(task: "help")
		def projectHash = result1.output.split("%%")[1]

		// 模拟上一次构建在配置阶段被杀，留下一个残留锁文件。
		//
		// 注意：这里刻意保留这个**已废弃机制的遗留物**——Loom 现在不再读取也不写它，
		// 但真实世界里升级前的旧版本、或降级回去的构建会在缓存目录里留下这类文件。
		// 本用例要守的就是：这种东西存在与否，都不得影响新版本的配置缓存与产物。
		def lockFile = new File(gradle.gradleHomeDir, "caches/fabric-loom/.${projectHash}.lock")
		lockFile.text = "12345"

		// 残留锁不得使配置缓存失效，此时配置阶段未被重跑
		def result2 = gradle.run(task: "help")

		// 强制重新配置（此时会重新执行整套 setup，包括 mapped jar 的供给）
		gradle.buildGradle << "\n// force reconfiguration\n"
		def result3 = gradle.run(task: "help")
		def result4 = gradle.run(task: "help")

		then:
		result1.output.contains("Calculating task graph as no cached configuration is available")
		// 残留锁文件不得让配置缓存失效：第 2 轮必须直接复用
		result2.output.contains("Reusing configuration cache")
		result3.task(":help").outcome != FAILED
		// 重新配置后仍能正常复用（锁文件不再参与配置期判断，自然也不会污染它）
		result4.output.contains("Reusing configuration cache")
		// 后续构建不得因锁文件被删除或内容变化而失效
		!result4.output.contains(".lock' has been removed")
		!result4.output.contains(".lock' has changed")

		and: "整个过程不得出现「等待锁」——配置期不再存在跨项目串行点"
		!result3.output.contains("Have been waiting on")

		where:
		version << STANDARD_TEST_VERSIONS
	}

	/**
	 * 锁文件机制的废弃说明（本用例的语义变更依据）.
	 *
	 * <p>旧版会用一把 per-project 的文件锁包住整个 setupMinecraft：进入时获取、退出时删除，
	 * 并以「锁文件是否已存在」判断上次构建是否干净收尾。它带来了三个无法解开的矛盾：
	 * <ul>
	 *   <li><b>粒度错配</b>：锁按项目划分，而它保护的是按「MC 版本 + 映射标识」划分的共享缓存。
	 *       同一构建里多个项目共存于同一缓存时，后到的项目必然看到前一个项目的**活**锁文件，
	 *       于是永远打出「上次构建异常终止」——这条诊断在多项目场景下本身就是错的；</li>
	 *   <li><b>与并行配置冲突</b>：开启 Isolated Projects 后 Gradle 并行配置各项目，N 个项目各自
	 *       在自己那把锁上等同一件重活（首次构建要下载、解压、重映射整份 MC），CI 上 1 分钟
	 *       超时必现失败，报出与真实原因无关的「Cannot get MinecraftMetadataProvider before
	 *       it has been setup」；</li>
	 *   <li><b>职责已被覆盖</b>：它声称保证的「同一共享缓存条目只被生产一次」，已由每个生产点
	 *       各自的 per-key 跨进程锁（{@code LoomCacheService}）承担，键覆盖 mc 版本+映射标识、
	 *       mappings、download、remap、processed、forge 补丁链等全部产出路径；且产物一律原子
	 *       发布，被中断的构建不会留下半截产物——残留锁本来就不该触发重建。</li>
	 * </ul>
	 *
	 * <p>因此它被整机制删除，而不是「保留但不等待」：后一种做法会留下一个在多项目场景下必然
	 * 撒谎的诊断。本用例与 {@code OfflineModeTest} 原先断言的「reusing the existing loom cache」
	 * 就是那行诊断的输出；现在改为守真正值得守的东西：<b>残留锁文件的存在不得影响配置缓存
	 * 复用、不得导致构建失败、也不得引入等待</b>。
	 */

	static def fmj(String version) {
		return """
		{
			"schemaVersion": 1,
			"id": "test",
			"version": "${version}"
		}
		"""
	}
}
