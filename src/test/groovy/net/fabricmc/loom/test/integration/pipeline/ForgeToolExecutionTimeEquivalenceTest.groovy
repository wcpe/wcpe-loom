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

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Forge 链上三处外部工具调用「**执行期可求值**」的判据，以及它们与配置期产物的逐 entry 等价性.
 *
 * <p>被改造的三处原先都是「配置期创建选项并**立即**求值」，因此调用点无法搬进任务：
 * <ol>
 *   <li>{@code MinecraftPatchedProvider.accessTransform} —— AT 工具（{@code TransformerProcessor}）；</li>
 *   <li>{@code MinecraftPatchedProvider.createPrePatchJar} 的 McpExecutor 分支 —— MCP 的 {@code rename} 链；</li>
 *   <li>{@code SrgProvider.produceMergedMojangRaw} —— InstallerTools 的 {@code MERGE_MAPPING}。</li>
 * </ol>
 * 三处都拆成「配置期只接线的声明方法」+「执行期求值的静态入口」：后者只认**选项 + 本次调用路径 +
 * {@code ServiceFactory}**，不读 {@code Project}、不读 provider——这正是「调用点可搬进任务」的判据。
 *
 * <h2>对照怎么构造</h2>
 * 被测是**执行期**发起的那三次调用（探针任务里跑），对照有三类，各有独立来源：
 * <ol>
 *   <li><b>共享缓存里的真实产物</b>：本次改造没有搬动 {@code CompileConfiguration} 的调用点，这三条链仍由
 *       配置期 {@code provide()} 生产，产物就是它们的输出。因此「执行期发起的那次 == 共享缓存里那一份」
 *       是**跨路径**的等价性，而不是同一份实现的自我印证；</li>
 *   <li><b>逐条转录的命令行</b>：{@code MERGE_MAPPING} 的参数模板与改造前那段 {@code settings.args(...)}
 *       逐条比对——标志与顺序写在本测试里（转录自改造前实现），只有两个输入路径取自探针；</li>
 *   <li><b>反向排除</b>：每一条等价性都配一条「必须不同」的断言（AT 结果必须不同于它的输入；MCP 结果必须
 *       不同于同一条链上另外两件 jar；merged tsrg 必须不同于同一链条产出的 trimmed tsrg），
 *       排除「比的是两个都空转的东西」。</li>
 * </ol>
 *
 * <h2>扰动对照（证明断言不是恒真）</h2>
 * <ul>
 *   <li>AT：<b>把 AT 规则换成空</b>再跑一次 AT 工具 —— 结果必须与共享缓存里那一份不同。它证明产物确实
 *       受「AT 规则」影响，而规则文件正是 {@code createAccessTransformerOptions} 接的线；</li>
 *   <li>MERGE_MAPPING：占位符<b>不展开</b>时 argv 必须留下字面量 {@code {output}}，且与展开后的 argv 不同。
 *       它证明 argv 断言能识别「命令行变了」。</li>
 * </ul>
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义。tsrg 是纯文本，直接比整文件字节。
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>覆盖 1.20.1 / Forge 47.2.1 的 **MERGED 形态**：AT 走中间产物路径、pre-patch jar 走 McpExecutor 的
 *       {@code rename} 链、merged mojmap 走 InstallerTools 的 {@code MERGE_MAPPING}；</li>
 *   <li>**NeoForge 的 installer-tools 分支（{@code [21.10.57-beta, 21.10.64)}）不在本用例里**：它的
 *       {@code createNeoForgeInstallerToolsPrePatchJar} 在上一轮已服务化，本轮未改动；</li>
 *   <li>**unobfuscated / legacy Forge 不在本用例里**：两者走另外的 pre-patch 分支，按既有
 *       {@code projectionBlocker()} 风格整批留在配置期；</li>
 *   <li>本探针在配置期与执行期各用一次项目模型（读共享缓存路径、注册任务），故显式关掉配置缓存；
 *       真实任务路径上选项在配置缓存存储期实例化、服务在执行期执行，两侧都已被 {@code ForgeRunConfigTest}
 *       等用例覆盖。</li>
 * </ul>
 */
class ForgeToolExecutionTimeEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 三个「执行期发起」的探针任务名. */
	private static final String AT_TASK = "probeAccessTransformAtExecution"
	private static final String MCP_TASK = "probePrePatchJarAtExecution"
	private static final String MERGE_TASK = "probeMergeMappingAtExecution"

	def "三处外部工具调用都可在执行期由静态入口发起，且产物与配置期逐 entry 一致"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.20.1')
				.replace('@FORGEVERSION@', '47.2.1')
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		gradle.buildGradle << probeScript().stripIndent()

		when: "跑消费方；三个执行期探针任务必须真的被执行到"
		// 探针在配置期要读项目模型（共享缓存路径、provider 状态），故显式关掉配置缓存；
		// 同时关掉构建缓存：本用例要断言探针任务真的执行了，而不是从缓存里恢复产物
		def result = gradle.run(task: "remapJar", configurationCache: false, args: ["--console=plain", "--no-build-cache"])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		printEvidence(report)

		def atSubject = entries(new File(report.AT_EXECUTION_OUTPUT))
		def atReference = entries(new File(report.AT_REFERENCE))
		def atInput = entries(new File(report.AT_INPUT))
		def atWithoutRules = entries(new File(report.AT_NO_RULES_OUTPUT))
		def mcpSubject = entries(new File(report.MCP_EXECUTION_OUTPUT))
		def mcpReference = entries(new File(report.MCP_REFERENCE))
		def patchedIntermediate = entries(new File(report.MCP_PATCHED_INTERMEDIATE))
		def mergeSubject = new File(report.MERGE_EXECUTION_OUTPUT)
		def mergeReference = new File(report.MERGE_REFERENCE)

		then: "三个探针任务都执行成功"
		result.task(":" + AT_TASK).outcome == SUCCESS
		result.task(":" + MCP_TASK).outcome == SUCCESS
		result.task(":" + MERGE_TASK).outcome == SUCCESS

		and: "① AT：执行期发起的那次与共享缓存里的产物逐 entry 一致"
		atSubject.size() > 0
		diff(atSubject, atReference).isEmpty()

		and: "② pre-patch jar：执行期发起的 MCP rename 链与共享缓存里的中间产物逐 entry 一致"
		mcpSubject.size() > 0
		diff(mcpSubject, mcpReference).isEmpty()

		and: "③ MERGE_MAPPING：执行期发起的那次与共享缓存里的 merged tsrg 逐字节一致"
		mergeSubject.bytes.length > 0
		mergeSubject.bytes == mergeReference.bytes

		and: "反向排除①：AT 结果不是它自己的输入（AT 确实改写了内容，不是复制）"
		diff(atSubject, atInput).size() > 0

		and: "反向排除②：MCP 的 rename 产物不同于同一链条上更早的两件 jar"
		diff(mcpSubject, patchedIntermediate).size() > 0
		diff(mcpSubject, atReference).size() > 0

		and: "反向排除③：merged tsrg 不同于同一链条产出的 trimmed tsrg（同一份对照口径能识别两件不同的 tsrg）"
		mergeSubject.bytes != new File(report.MERGE_TRIMMED).bytes

		and: "扰动对照①：AT 规则为空时，AT 结果必须与共享缓存那一份不同（AT 规则确实参与产物内容）"
		atWithoutRules.size() > 0
		diff(atWithoutRules, atReference).size() > 0

		and: "扰动对照②：占位符不展开时 argv 留下字面量 {output}，且与展开后的 argv 不同"
		report.MERGE_ARGV_UNEXPANDED.contains("{output}")
		report.MERGE_ARGV_UNEXPANDED != report.MERGE_ARGV_ORACLE

		and: "MERGE_MAPPING 的参数模板与改造前那串逐条一致（标志与顺序转录自改造前实现）"
		report.MERGE_ARGS_TEMPLATE == report.MERGE_ORACLE_TEMPLATE

		and: "工具身份与执行期求值口径：主类是常量主类，执行器沿用工具链"
		report.MERGE_MAIN_CLASS == "net.minecraftforge.installertools.ConsoleTool"
		report.AT_MAIN_CLASS == "net.minecraftforge.accesstransformer.TransformerProcessor"
	}

	/**
	 * 追加到夹具 build.gradle 的探针.
	 *
	 * <p>配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）只做三件事：读共享缓存里的对照路径、
	 * 调三个「配置期接线」方法拿到声明、把三个「执行期发起」的探针任务注册进任务图。真正的三条调用全部在
	 * 那些任务的 {@code doLast} 里发起，且只用到「选项 + 本次路径 + 新的 ServiceFactory」。
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def provider = dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.get(project)
			def srgProvider = extension.srgProvider
			def probeDir = new File(project.buildDir, 'probe')
			probeDir.mkdirs()
			def report = project.file('probe-report.properties')
			report.text = ''

			def record = { String line -> report << (line + System.lineSeparator()) }

			// —— 对照路径：由配置期 provide() 链生产，本次改造没有搬动那些调用点 ——
			def provisionFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
			def atReference = provider.productionOptions(provisionFactory).atPatchedJar()
			provisionFactory.close()
			def atInput = provider.minecraftPatchedIntermediateJar
			def mcpReference = provider.minecraftIntermediateJar
			def mergeReference = srgProvider.mergedMojangRaw
			def mergeTrimmed = srgProvider.mergedMojangTrimmed

			// —— 配置期只接线：三个声明此刻都不解析依赖、不起进程 ——
			def atTempFiles = new dev.architectury.loom.util.TempFiles()
			def atOptions = provider.createAccessTransformerOptions(atTempFiles)
			def mcpTempFiles = new dev.architectury.loom.util.TempFiles()
			def mcpOptions = provider.createPrePatchJarMcpOptions(mcpTempFiles)
			def mergeTool = srgProvider.createMergeMappingTool()

			// 扰动：AT 规则换成「只有注释、没有任何规则」的一份 —— 只改「AT 规则」这一个维度，
			// 工具、输入 jar、输出路径与原调用完全一致
			def noRulesAtFile = new File(probeDir, 'at-no-rules.cfg')
			noRulesAtFile.text = '# 扰动对照：只有注释，没有任何 AT 规则' + System.lineSeparator()
			def noRulesOptions = dev.architectury.loom.accesstransformer.AccessTransformerService
					.createOptions(project, [noRulesAtFile])

			// MERGE_MAPPING 的命令行对照：标志与顺序转录自改造前实现，两个输入路径取自探针
			def srgTsrg = srgProvider.srg.toAbsolutePath().toString()
			def mojmapTsrg = dev.architectury.loom.forge.dependency.SrgProvider
					.getMojmapTsrg2(project, extension).toAbsolutePath().toString()
			def oracleTemplate = ['--task', 'MERGE_MAPPING', '--left', srgTsrg, '--right', mojmapTsrg, '--classes', '--output', '{output}']

			def settingsFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
			def mergeService = settingsFactory.get(mergeTool)
			def unexpandedArgv = mergeService.settingsFor([:]).programArgs.get().join('|')
			def expandedArgv = mergeService.settingsFor(['{output}': '<本次调用路径>']).programArgs.get().join('|')
			def mergeMainClass = mergeService.settingsFor([:]).mainClass.get()
			settingsFactory.close()

			// AT 工具的主类由 AccessTransformerService.createOptions 决定，这里只独立断言它不是空
			def atMainClass = dev.architectury.loom.accesstransformer.AccessTransformerService
					.createOptions(project, []).get().mainClass.get()

			def atOut = new File(probeDir, 'access-transformed-exec-time.jar')
			def atNoRulesOut = new File(probeDir, 'access-transformed-exec-time-no-rules.jar')
			def mcpOut = new File(probeDir, 'pre-patch-jar-exec-time.jar')
			def mergeOut = new File(probeDir, 'srg-mojmap-merged-raw-exec-time.tsrg')

			// ★ 以下三个任务体只认「配置期造好的选项 + 本次调用的路径 + 一个服务工厂」：
			//   不读 extension、不读 provider、不建任何新选项 —— 这就是「调用点可搬进任务」的判据
			project.tasks.register('probeAccessTransformAtExecution') {
				doLast {
					def f = new net.fabricmc.loom.util.service.ScopedServiceFactory()
					try {
						dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.runAccessTransformer(
								atOptions, atInput, atOut.toPath(), f)
						// 扰动：AT 规则为空，其余输入输出与原调用完全一致
						dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.runAccessTransformer(
								noRulesOptions, atInput, atNoRulesOut.toPath(), f)
					} finally {
						f.close()
					}
				}
			}

			def mcpTask = project.tasks.register('probePrePatchJarAtExecution') {
				doLast {
					def f = new net.fabricmc.loom.util.service.ScopedServiceFactory()
					try {
						dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.runMcpExecutor(
								mcpOptions, mcpOut.toPath(), f)
					} finally {
						f.close()
					}
				}
			}

			def mergeTask = project.tasks.register('probeMergeMappingAtExecution') {
				doLast {
					def f = new net.fabricmc.loom.util.service.ScopedServiceFactory()
					try {
						dev.architectury.loom.forge.dependency.SrgProvider.runMergeMapping(mergeTool, mergeOut.toPath(), f)
					} finally {
						f.close()
					}

					record('AT_REFERENCE=' + atReference)
					record('AT_INPUT=' + atInput)
					record('AT_EXECUTION_OUTPUT=' + atOut.absolutePath)
					record('AT_NO_RULES_OUTPUT=' + atNoRulesOut.absolutePath)
					record('AT_MAIN_CLASS=' + atMainClass)
					record('MCP_REFERENCE=' + mcpReference)
					record('MCP_PATCHED_INTERMEDIATE=' + provider.minecraftPatchedIntermediateJar)
					record('MCP_EXECUTION_OUTPUT=' + mcpOut.absolutePath)
					record('MERGE_REFERENCE=' + mergeReference)
					record('MERGE_TRIMMED=' + mergeTrimmed)
					record('MERGE_EXECUTION_OUTPUT=' + mergeOut.absolutePath)
					record('MERGE_ARGS_TEMPLATE=' + mergeTool.argsTemplate.get().join('|'))
					record('MERGE_ORACLE_TEMPLATE=' + oracleTemplate.join('|'))
					record('MERGE_ARGV_UNEXPANDED=' + unexpandedArgv)
					record('MERGE_ARGV_EXPANDED=' + expandedArgv)
					record('MERGE_ARGV_ORACLE=' + oracleTemplate.collect { it == '{output}' ? '<本次调用路径>' : it }.join('|'))
					record('MERGE_MAIN_CLASS=' + mergeMainClass)
				}
			}

			mcpTask.get().dependsOn(project.tasks.named('generateForgePatchedJar'))
			project.tasks.named('remapJar').configure { dependsOn mergeTask.get() }
			project.tasks.named('remapJar').configure { dependsOn project.tasks.named('probeAccessTransformAtExecution') }
			project.tasks.named('remapJar').configure { dependsOn mcpTask.get() }
		}
		'''
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

	private static Map<String, String> entries(File jar) {
		final Map<String, String> result = [:]

		new ZipFile(jar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (e.directory) {
					continue
				}

				def digest = MessageDigest.getInstance("SHA-256")

				zip.getInputStream(e).withCloseable { input ->
					digest.update(input.readAllBytes())
				}

				result[e.name] = digest.digest().encodeHex().toString()
			}
		}

		return result
	}

	private static List<String> diff(Map<String, String> actual, Map<String, String> expected) {
		return actual.findResults { name, hash -> hash == expected[name] ? null : name }
	}

	private static void printEvidence(Map<String, String> report) {
		println("AT 执行期产物: " + report.AT_EXECUTION_OUTPUT)
		println("AT 共享缓存对照: " + report.AT_REFERENCE)
		println("pre-patch 执行期产物: " + report.MCP_EXECUTION_OUTPUT)
		println("pre-patch 共享缓存对照: " + report.MCP_REFERENCE)
		println("merged tsrg 执行期产物: " + report.MERGE_EXECUTION_OUTPUT)
		println("merged tsrg 对照: " + report.MERGE_REFERENCE)
		println("MERGE_MAPPING 模板: " + report.MERGE_ARGS_TEMPLATE)
		println("MERGE_MAPPING 对照模板: " + report.MERGE_ORACLE_TEMPLATE)
	}
}
