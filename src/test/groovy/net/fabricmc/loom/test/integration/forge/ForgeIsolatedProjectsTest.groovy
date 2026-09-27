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

/**
 * Forge / NeoForge 场景在隔离项目模式（Isolated Projects）下的回归测试。
 *
 * <p>Loom 在隔离项目模式下不再跨项目读取 {@code Project} 与 {@code LoomGradleExtension}，
 * 而是读取可序列化的 {@code LoomProjectData} DTO。这里用真实 Forge 工程覆盖两条路径：
 * <ul>
 *     <li>单项目：确认 Forge 自身的项目内逻辑在隔离模式下不被破坏；</li>
 *     <li>多子项目：{@code mod} 子项目通过 {@code modImplementation project(":common")} 依赖另一个
 *     Loom 子项目，这是与 {@code ProjectView} / {@code LoomProjectData} 最直接相关的场景。</li>
 * </ul>
 * 之所以必须覆盖多子项目：单项目夹具的 runtime/compile classpath 里没有任何
 * {@code ProjectDependency}，{@code LoomProjectData.getDependencies} 根本不会被触发，
 * 因此单项目用例无法覆盖 DTO 读取路径。
 *
 * <p>Fabric 侧的同类覆盖见 {@code MultiProjectDirectPluginTest}，Forge 侧此前没有任何隔离项目覆盖。
 */
class ForgeIsolatedProjectsTest extends Specification implements GradleProjectTestTrait {
	// 与 ForgeTest 使用同一组版本，便于复用测试 gradle home 里已有的 Minecraft / Forge 缓存。
	private static final String MC_VERSION = "1.20.1"
	private static final String FORGE_VERSION = "47.2.1"

	def "single forge project configures and builds with isolated projects"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace('@MCVERSION@', MC_VERSION)
				.replace('@FORGEVERSION@', FORGE_VERSION)
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')

		when:
		// 排除 remapSourcesJar：它只涉及 Minecraft 源码反编译，与隔离项目无关，但会显著拖慢用例。
		def result = gradle.run(
				task: "build",
				args: ["-x", "remapSourcesJar"],
				isloatedProjects: true,
				configureOnDemand: true)

		then:
		assertIsolatedProjectsActive(result.output)
		result.task(":build").outcome == SUCCESS
		result.task(":remapJar").outcome == SUCCESS
		findIsolatedProjectsViolations(result.output).isEmpty()
	}

	def "multi project forge build does not cross project access with isolated projects"() {
		setup:
		def gradle = gradleProject(project: "forge/multiProjectIsolated", version: DEFAULT_GRADLE)
		replacePlaceholders(gradle)

		when:
		// configure-on-demand 让 :common 只在真正被需要时才配置，
		// 把 LoomProjectData 的跨项目读取推到最真实的场景下。
		def result = gradle.run(
				tasks: [":mod:build"],
				isloatedProjects: true,
				configureOnDemand: true)

		then:
		assertIsolatedProjectsActive(result.output)
		result.task(":mod:build").outcome == SUCCESS
		result.task(":mod:remapJar").outcome == SUCCESS
		// :common 的产物被 :mod 消费，说明跨项目依赖确实被解析过
		result.task(":common:jar").outcome == SUCCESS
		findIsolatedProjectsViolations(result.output).isEmpty()
	}

	private static void replacePlaceholders(GradleProjectTestTrait.GradleProject gradle) {
		def replacements = [
			'@MCVERSION@': MC_VERSION,
			'@FORGEVERSION@': FORGE_VERSION,
		]

		gradle.projectDir.eachFileRecurse { file ->
			if (!file.isFile() || !file.name.endsWith('.gradle')) {
				return
			}

			def text = file.text
			replacements.each { placeholder, value -> text = text.replace(placeholder, value) }
			file.text = text
		}
	}

	/**
	 * 断言本次构建确实开启了隔离项目模式。
	 *
	 * <p>如果 Gradle 不再识别该开关（例如启动参数改名或拼写变化），用例必须立刻失败，
	 * 而不是因为“隔离模式其实没打开”而静默通过。
	 */
	private static void assertIsolatedProjectsActive(String output) {
		assert output.contains("Isolated Projects is an incubating feature") :
		"本次构建没有启用隔离项目模式，隔离回归验证无效"
	}

	/**
	 * 收集输出中所有“隔离项目 / 配置缓存违规”的证据行。
	 *
	 * <p>开启 {@code -Dorg.gradle.unsafe.isolated-projects=true} 后，Gradle 会把跨项目模型访问
	 * 作为 configuration cache 的 problem 上报，并在存储缓存阶段让构建失败，输出形如：
	 * <pre>
	 * 1 problem was found storing the configuration cache.
	 * - Build file 'mod/build.gradle': line 23: Project ':mod' cannot access 'Project.configurations'
	 *   functionality on another project ':common'
	 * </pre>
	 * 这里只匹配 Gradle 违规上报特有的措辞，避免把普通构建失败信息误判为违规。
	 */
	private static List<String> findIsolatedProjectsViolations(String output) {
		def markers = [
			"Configuration cache problems found in this build",
			"problem was found storing the configuration cache",
			"problems were found storing the configuration cache",
			"functionality on another project",
			"cannot access 'Project",
		]

		return output.readLines().findAll { line ->
			markers.any { marker -> line.contains(marker) }
		}
	}
}
