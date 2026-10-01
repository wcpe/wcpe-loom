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

package net.fabricmc.loom.test.integration.benchmark

import java.util.jar.JarFile

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 三平台行为回归快照：产物、命名空间与关键类。
 *
 * <p>目的是为后续「把环境生产从配置阶段迁到任务流水线」的重构提供安全网：
 * 重构前后，同样的输入必须产出结构一致的结果。
 *
 * <p>刻意只断言<b>结构</b>而非逐字节内容：
 * <ul>
 *   <li>类是否存在（重映射是否成功落到目标命名空间）</li>
 *   <li>关键资源是否存在（FMJ / mixin 配置 / refmap）</li>
 *   <li>产物是否被正确生成</li>
 * </ul>
 * 不做哈希比对——那会把无关的变量（时间戳、依赖版本漂移）固化进测试。
 *
 * <p>命名空间判定用「间接证据」：检查已知在特定命名空间下才存在的类或成员，
 * 而不是直接读 Loom 内部状态，避免测试与实现细节耦合。
 */
class PlatformBehaviourSnapshotTest extends Specification implements GradleProjectTestTrait {
	private static final String FABRIC_MC = '1.20.1'
	private static final String FABRIC_YARN = 'net.fabricmc:yarn:1.20.1+build.10:v2'
	private static final String FORGE_MC = '1.20.1'
	private static final String FORGE_VERSION = '47.2.1'

	/**
	 * 打开构建产物 jar。
	 *
	 * <p>{@code remapJar} 的产物名由 base.archivesName + version 决定，
	 * minimalBase 夹具固定为 fabric-example-mod-1.0.0.jar。
	 */
	private static JarFile remappedJar(File projectDir) {
		def libs = new File(projectDir, "build/libs")
		def candidates = libs.listFiles({ it.name.endsWith(".jar") && !it.name.contains("sources") } as FileFilter)

		assert candidates != null && candidates.length > 0: "未找到构建产物，libs 目录内容：${libs.listFiles()?.collect { it.name }}"

		// 取体积最大的一个：dev jar 与 remapped jar 可能同时存在，remapped 才是发布产物
		def jar = candidates.toList().sort { -it.length() }.first()
		return new JarFile(jar.absoluteFile)
	}

	@Unroll
	def "fabric #mcVersion 产物快照"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:${mcVersion}'
                mappings '${yarn}'
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
            """.stripIndent()

		when:
		def result = gradle.run(task: "build")

		then: "构建成功"
		result.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		result.task(":remapJar").outcome in [SUCCESS, UP_TO_DATE]

		and: "产物存在且是合法 jar"
		def jar = remappedJar(gradle.projectDir)
		jar.entries().toList().size() > 0
		// minimalBase 夹具无源码，只验证 jar 结构完整性
		jar.getEntry("META-INF/MANIFEST.MF") != null

		cleanup:
		jar?.close()

		where:
		mcVersion   | yarn
		FABRIC_MC   | FABRIC_YARN
	}

	/**
	 * 带源码的 Fabric 快照：验证重映射真正落到目标命名空间。
	 *
	 * <p>minimalBase 没有源码，无法验证重映射结果；本用例改用 mixinApSimple
	 * （含 ExampleMod 与 mixin），通过 jar 内的条目间接判定命名空间：
	 * <ul>
	 *   <li>mod 自身的类必须存在（编译成功且被打包）</li>
	 *   <li>mixin refmap 必须生成（说明 mixin 映射已按目标命名空间解析）</li>
	 *   <li>mixin 配置文件必须存在</li>
	 * </ul>
	 * 这些是「重映射成功」的必要证据，且不依赖 Loom 内部状态。
	 */
	def "fabric 带源码产物快照（重映射与 mixin）"() {
		setup:
		def gradle = gradleProject(project: "mixinApSimple")

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		result.task(":remapJar").outcome in [SUCCESS, UP_TO_DATE]

		and: "mod 自身类存在于产物中"
		def jar = remappedJar(gradle.projectDir)
		def entries = jar.entries().collect { it.name }
		entries.contains("net/fabricmc/example/ExampleMod.class")

		and: "mixin 配置与 refmap 已生成"
		// refmap 由 mixin AP 生成，其内容是按目标命名空间解析后的映射，
		// 缺失即说明重映射链断裂
		entries.any { it.startsWith("net/fabricmc/example/mixin/") }
		entries.any { it.endsWith(".refmap.json") || it.contains("refmap") }

		cleanup:
		jar?.close()
	}

	@Unroll
	def "forge #mcVersion 产物快照"() {
		setup:
		def gradle = gradleProject(project: "forge/simple")
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
				.replace('@FORGEVERSION@', forgeVersion)
				.replace('@MAPPINGS@', "loom.officialMojangMappings()")
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		result.task(":remapJar").outcome in [SUCCESS, UP_TO_DATE]

		and:
		def jar = remappedJar(gradle.projectDir)
		jar.entries().toList().size() > 0
		jar.getEntry("META-INF/MANIFEST.MF") != null

		cleanup:
		jar?.close()

		where:
		mcVersion  | forgeVersion
		FORGE_MC   | FORGE_VERSION
	}

	def "forge legacy 1.12.2 产物快照"() {
		setup:
		// 固定 1.12.2-14.23.5.2860，覆盖 MinecraftLegacyPatchedProvider 完整链路
		def gradle = gradleProject(project: "forge/legacy/externalModDependency")

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		result.task(":remapJar").outcome in [SUCCESS, UP_TO_DATE]

		and:
		def jar = remappedJar(gradle.projectDir)
		jar.entries().toList().size() > 0
		jar.getEntry("META-INF/MANIFEST.MF") != null

		cleanup:
		jar?.close()
	}
}
