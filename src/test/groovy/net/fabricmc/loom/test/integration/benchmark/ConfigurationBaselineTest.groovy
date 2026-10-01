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

import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

/**
 * 阶段 0：三平台配置阶段行为基线。
 *
 * <p>目的不是断言「应该多快」，而是把「配置慢 / CC 无法保存 / CC 失效 / 任务重跑」
 * 这四种状态分开观测，为后续 Task 化改造提供可复现的对照点。
 *
 * <p>本类刻意只做观测与记录，不把当前行为固化为「正确行为」：
 * 任何一项在今天不成立，都应当在输出里被看见，而不是让测试悄悄通过。
 */
class ConfigurationBaselineTest extends Specification implements GradleProjectTestTrait {
	/** Fabric 1.20.1：MERGED jar 配置，intermediary + named + processedNamed. */
	private static final String FABRIC_1_20_1 = '1.20.1'
	private static final String FABRIC_YARN = 'net.fabricmc:yarn:1.20.1+build.10:v2'

	/** 现代 Forge 1.20.1：MinecraftPatchedProvider. */
	private static final String FORGE_1_20_1 = '1.20.1'
	private static final String FORGE_1_20_1_VERSION = '47.2.1'

	/**
	 * 观测一次「冷 → 暖 → 复用」序列，返回原始输出供上层断言。
	 *
	 * <p>连续跑三次是刻意的：第一次会产出新文件（mapped jar、remapped mod 等），
	 * 这些新文件本身会让下一次配置缓存失效；只有输出稳定后的第三次
	 * 才可能真正复用同一条目。这是仓库既有测试（MultiProjectDirectPluginTest）
	 * 已经踩过的规律。
	 */
	private void observeRuns(String label, Closure<GradleProject> projectFactory, String task) {
		def gradle = projectFactory()

		// 冷：无配置缓存条目
		def cold = gradle.run(task: task)
		// 暖：产物已落盘，可能因首轮新文件而失效
		def warm = gradle.run(task: task)
		// 复用：输出已稳定，期望真正复用
		def reused = gradle.run(task: task)

		println("========== [${label}] task=${task} ==========")
		println("cold   : cc-stored=${cold.output.contains('Configuration cache entry stored')} " +
				"cc-reused=${cold.output.contains('Configuration cache entry reused')} " +
				"problem=${cold.output.contains('Configuration cache problems found')}")
		println("warm   : cc-reused=${warm.output.contains('Configuration cache entry reused')} " +
				"problem=${warm.output.contains('Configuration cache problems found')}")
		println("reused : cc-reused=${reused.output.contains('Configuration cache entry reused')} " +
				"problem=${reused.output.contains('Configuration cache problems found')}")

		// 记录失效原因（若存在），这是后续改造要逐条消灭的清单
		[
			["cold", cold],
			["warm", warm],
			["reused", reused]
		].each { name, result ->
			result.output.readLines().findAll {
				it.contains("configuration cache cannot be reused") ||
						it.contains("Calculating task graph as")
			}.each {
				println("  [${label}/${name}] ${it.trim()}")
			}
		}
		println("================================================")
	}

	/**
	 * 观测「配置阶段本身」的耗时，与任务执行耗时分离。
	 *
	 * <p>用 {@code help} 而非 {@code build}：help 几乎不执行任何实际工作，
	 * 因而其端到端耗时近似等于配置阶段开销。这是把「配置慢」从「构建慢」里
	 * 切出来的最小手段，不追求精度，只用于横向对比四种状态。
	 *
	 * <p>四种状态对照：
	 * <ol>
	 *   <li>{@code --no-configuration-cache}：每次都完整配置，即改造要优化的目标</li>
	 *   <li>CC 首次存储：完整配置 + 序列化开销</li>
	 *   <li>CC 命中：期望显著低于前两者</li>
	 *   <li>daemon 重启后 CC 命中：检验是否因 static 缓存丢失而退化</li>
	 * </ol>
	 */
	private void observeConfigurationCost(Closure<GradleProject> projectFactory, String label) {
		def gradle = projectFactory()

		// 1) 无 CC：每次都完整配置，即改造要优化的目标
		def noCache = timed { gradle.run(task: "help", configurationCache: false) }
		// 2) CC 首次：完整配置 + 序列化存储
		def first = timed { gradle.run(task: "help") }
		// 3) CC 命中：期望显著低于前两者
		def hit = timed { gradle.run(task: "help") }
		// 4) 再跑一次确认稳定命中，排除单轮抖动
		def hit2 = timed { gradle.run(task: "help") }

		println("---------- [${label}] 配置阶段耗时（help，wall-clock ms）----------")
		[
			["no-cc（每次完整配置）", noCache],
			["cc-首次存储", first],
			["cc-命中", hit],
			["cc-命中（再次）", hit2]
		].each { name, pair ->
			def result = pair[1]
			println("  ${name.padRight(26)} ${String.format('%6d', pair[0])}ms  " +
					"reused=${result.output.contains('Configuration cache entry reused')} " +
					"stored=${result.output.contains('Configuration cache entry stored')}")
		}

		// 配置期是否仍在做环境供给：这是后续改造的核心验收点。
		// help 不该触发下载、重映射、解包，出现即说明配置期还在做制品加工。
		[
			["no-cc", noCache],
			["cc-first", first],
			["cc-hit", hit]
		].each { name, pair ->
			pair[1].output.readLines().findAll {
				it.contains("Downloading") || it.contains(":remap") ||
						it.contains("Remapping") || it.contains("Extracting") ||
						it.contains("共享缓存产物不可复用")
			}.take(5).each {
				println("    [${label}/${name}] ${it.trim()}")
			}
		}
		println("--------------------------------------------------")
	}

	private static List timed(Closure<BuildResult> action) {
		long start = System.currentTimeMillis()
		def result = action()
		return [
			System.currentTimeMillis() - start,
			result
		]
	}

	@Unroll
	def "fabric #mcVersion configuration baseline"() {
		when:
		observeRuns("fabric-${mcVersion}", {
			def gradle = gradleProject(project: "minimalBase")
			gradle.buildGradle << """
                dependencies {
                    minecraft 'com.mojang:minecraft:${mcVersion}'
                    mappings '${yarn}'
                    modImplementation "${net.fabricmc.loom.test.LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                }
                """.stripIndent()
			return gradle
		}, "build")

		then:
		// 基线阶段只要求构建本身不失败，不据此判定性能是否达标
		noExceptionThrown()

		where:
		mcVersion      | yarn
		FABRIC_1_20_1  | FABRIC_YARN
	}

	@Unroll
	def "forge #mcVersion configuration baseline"() {
		when:
		observeRuns("forge-${mcVersion}", {
			def gradle = gradleProject(project: "forge/simple")
			gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
					.replace('@FORGEVERSION@', forgeVersion)
					.replace('@MAPPINGS@', "loom.officialMojangMappings()")
					.replace('@REPOSITORIES@', '')
					.replace('@PACKAGE@', 'net.minecraftforge:forge')
					.replace('@JAVA_VERSION@', '17')
			return gradle
		}, "build")

		then:
		noExceptionThrown()

		where:
		mcVersion     | forgeVersion
		FORGE_1_20_1  | FORGE_1_20_1_VERSION
	}

	/**
	 * 配置阶段耗时基线：只跑 {@code help}，用于把「配置慢」与「构建慢」分开。
	 *
	 * <p>刻意只选 Fabric 1.20.1 与 Forge legacy 1.12.2 两条差异最大的链，
	 * 避免基线本身耗时过长；现代 Forge 的特性介于两者之间。
	 */
	def "fabric #mcVersion configuration cost"() {
		when:
		observeConfigurationCost({
			def gradle = gradleProject(project: "minimalBase")
			gradle.buildGradle << """
                dependencies {
                    minecraft 'com.mojang:minecraft:${mcVersion}'
                    mappings '${yarn}'
                    modImplementation "${net.fabricmc.loom.test.LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                }
                """.stripIndent()
			return gradle
		}, "fabric-${mcVersion}")

		then:
		noExceptionThrown()

		where:
		mcVersion      | yarn
		FABRIC_1_20_1  | FABRIC_YARN
	}

	def "forge legacy 1.12.2 configuration cost"() {
		when:
		observeConfigurationCost({
			return gradleProject(project: "forge/legacy/externalModDependency")
		}, "forge-legacy-1.12.2")

		then:
		noExceptionThrown()
	}

	def "forge legacy 1.12.2 configuration baseline"() {
		when:
		observeRuns("forge-legacy-1.12.2", {
			// 复用既有夹具：它固定 1.12.2-14.23.5.2860（只发布 userdev3），
			// 覆盖 MinecraftLegacyPatchedProvider 的完整链路
			return gradleProject(project: "forge/legacy/externalModDependency")
		}, "build")

		then:
		noExceptionThrown()
	}
}
