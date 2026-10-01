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

import java.util.zip.ZipFile

import dev.architectury.loom.forge.ForgeSourcesService
import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.test.util.ForgeColdLoomCache
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * genSources **执行期** Forge 源码注入在**冷缓存**下的端到端行为.
 *
 * <p>{@code ForgeSourcesService} 有两条注入路径，{@code ForgeSourcesColdCacheTest} 取证的是配置期那条
 * （{@code addForgeSourcesDuringProjectConfiguration}）。本用例取证另一条：
 * {@code GenerateSourcesTask.runDecompileJob} 在反编译之后用**工作输入 jar** 调
 * {@code addForgeSources}。这条路径的输入是任务自己声明的，理论上冷缓存下也应当成立——本用例把这个
 * 「理论上」变成断言。
 *
 * <h4>为什么这条路径必须单独取证</h4>
 * 配置期那条读的是 named MC jar（配置期拿不到，只能整批跳过）；执行期这条读的是**同一个任务**的
 * {@code @Classpath} 输入（named jar 的 backup，见 {@code GenerateSourcesTask.getClassesInputJar}），
 * 它的就位由任务依赖保证。两者失败的形态完全不同，前者的用例跑不到后者。
 *
 * <h4>怎么把「反编译 MC」这一步从用例里去掉</h4>
 * 被测的是反编译**之后**的注入，与反编译器无关。用例因此注册一个空实现反编译器
 * （{@code buildSrc/decompile} 夹具，只往源码包写一个固定条目），把这一步压到秒级；注入路径一行不改。
 * 副作用反而是证据：源码包里出现 {@code META-INF/test.txt} 就说明这份包是**执行期** genSources 写的，
 * 而不是配置期那条路径写的。
 *
 * <h4>冷是真的冷</h4>
 * 被测构建跑在**独立的 loom 共享缓存**上（{@code -Dfabric.loom.cache.dir}，见 {@link ForgeColdLoomCache}）：
 * Forge 工具链与映射构件从默认 home 播种（重建代价高），named MC jar 及其 backup、源码包刻意不播种。
 * 于是「冷」不是删出来的、也没有第二次写入——共享 home 一个字节都不动。
 *
 * <p>种子不存在时（例如从未跑过 Forge 车道）退化成一次全冷的 Forge 构建：慢，但结论不变。
 */
class ForgeSourcesExecutionInjectionColdCacheTest extends Specification implements GradleProjectTestTrait {
	private static final String MC_VERSION = "1.20.1"
	private static final String FORGE_VERSION = "47.2.1"
	/** 被测产物：single-jar Forge 场景下 {@code getMinecraftJars(NAMED)} 指向的那份构件. */
	private static final String MERGED_ARTIFACT = "forge-${MC_VERSION}-${FORGE_VERSION}-minecraft-merged"
	/** 空实现反编译器注册的名字（见 buildSrc/decompile/TestPlugin）：任务名由它拼出 `genSourcesWith<Name>`. */
	private static final String GEN_SOURCES_TASK = "genSourcesWithCustom"
	/** 空实现反编译器写进源码包的条目（见 buildSrc/decompile/CustomDecompiler）. */
	private static final String STUB_DECOMPILER_ENTRY = "META-INF/test.txt"
	private static final String REPORT_MARKER = "WIRING_REPORT="

	/**
	 * 播种好的隔离 loom 共享缓存.
	 *
	 * <p>{@code @Shared}：播种要复制数百 MB 的缓存，同一个测试类的多条用例共用一份即可。
	 * 用例本身会把这唯一一份用成「先冷后热」，因此这里只应有一条用例。
	 */
	@Lazy
	@Shared
	private static File coldLoomCache = ForgeColdLoomCache.seed(MC_VERSION, MERGED_ARTIFACT)

	def "冷缓存下 genSources 执行期注入的输入由产出任务落位"() {
		setup:
		def gradle = gradleProject(project: "forge/simple")
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace("@MCVERSION@", MC_VERSION)
				.replace("@FORGEVERSION@", FORGE_VERSION)
				.replace("@MAPPINGS@", "loom.officialMojangMappings()")
				.replace("@REPOSITORIES@", "")
				.replace("@PACKAGE@", "net.minecraftforge:forge")
				.replace("@JAVA_VERSION@", "17")
		gradle.buildSrc("decompile")
		gradle.buildGradle << ('''
			def reportFile = project.file('wiring.properties')

			project.afterEvaluate {
				def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
				def ns = net.fabricmc.loom.api.mappings.layered.MappingsNamespace
				def namedJars = loomExt.getMinecraftJars(ns.NAMED)
				def genSources = project.tasks.findByName('genSourcesWithCustom')
				def lines = []
				lines << 'NAMED_JAR=' + (namedJars.isEmpty() ? '' : namedJars[0].toString())
				lines << 'SOURCES_JAR=' + genSources.sourcesOutputJar.get().asFile.absolutePath
				// 登记给消费侧的文件集合携带哪些产出任务（裸 File 列表不会携带）
				lines << 'PRODUCER_TASKS=' + loomExt.getMinecraftJarsCollection(ns.NAMED).buildDependencies
						.getDependencies(null).collect { it.name }.unique().sort().join(',')
				reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
			}

			tasks.register('printWiringReport') {
				def report = reportFile
				doLast {
					println 'WIRING_REPORT=' + report.absolutePath
				}
			}
			''')
		// loom 的产物仓库整体隔离到本次用例专属目录；Gradle 自身的缓存继续用默认 home
		gradle.gradleProperties << "\nsystemProp.fabric.loom.cache.dir=${coldLoomCache.absolutePath.replace("\\", "/")}\n"

		when: "冷缓存下先跑一次只做配置的探针构建"
		def probe = gradle.run(task: "printWiringReport", configurationCache: false, args: ["--console=plain"])
		def wiring = parseReport(new File(gradle.projectDir, "wiring.properties"))
		def namedJar = new File(wiring.NAMED_JAR)
		def backupJar = new File(wiring.NAMED_JAR + ".backup")
		def sourcesJar = new File(wiring.SOURCES_JAR)

		then: "探针拿到了路径，而 named jar 与它的 backup 此刻都不存在：这就是冷"
		probe.task(":printWiringReport").outcome == SUCCESS
		wiring.NAMED_JAR.endsWith(".jar")
		!namedJar.exists()
		!backupJar.exists()
		!sourcesJar.exists()

		and: "配置期那条注入路径整批跳过（它的输入拿不到），所以下面的源码包只能来自执行期"
		probe.output.contains(ForgeSourcesService.SKIPPED_LOG_MARKER)

		and: "genSources 的产出就是配置期那条路径守卫的同一个文件"
		wiring.SOURCES_JAR == sourcesJarOf(wiring.NAMED_JAR)

		and: "登记给消费侧的集合确实携带产出任务（空集合会让下面的断言变成空转）"
		!producersOf(wiring).isEmpty()

		when: "同一个冷缓存上直接跑 genSources（配置期仍然看不到 named jar）"
		def run = gradle.run(task: GEN_SOURCES_TASK, configurationCache: false, args: ["--console=plain"])

		then: "本次配置期依旧是冷的"
		run.output.contains(ForgeSourcesService.SKIPPED_LOG_MARKER)

		and: "genSources 成功，且 named jar 与 backup 都在本次构建里落位（产出任务真的被接上了）"
		run.task(":" + GEN_SOURCES_TASK).outcome == SUCCESS
		namedJar.exists()
		backupJar.exists()
		producersOf(wiring).every { name -> run.task(":" + name)?.outcome in [SUCCESS, UP_TO_DATE] }

		and: "源码包里有空实现反编译器的条目：这份包是执行期的 genSources 写的，不是配置期写的"
		def entries = entriesOf(sourcesJar)
		entries.contains(STUB_DECOMPILER_ENTRY)

		and: "执行期的注入拿到的是一份完整输入：注入的 Forge 源码是一次完整集合，不是空壳或子集"
		def forgeSources = entries.findAll { it.endsWith(".java") }
		forgeSources.size() > 600
		forgeSources.every { it.startsWith("net/minecraftforge/") }
		// 注入的规模由 ForgeSourcesService 自己报出来（执行期那条路径上的过滤结果）
		extractedCounts(run.output).every { it > 600 }

		and: "过滤确实对着执行期那份输入 jar 做：每个源码条目在 named jar（以及作为过滤输入的 backup）里都有同名 class"
		def namedClasses = classEntries(namedJar)
		def backupClasses = classEntries(backupJar)
		forgeSources.every { namedClasses.contains(it.replace(".java", ".class")) }
		forgeSources.every { backupClasses.contains(it.replace(".java", ".class")) }
	}

	/** 探针报出的产出任务名（登记给消费侧的那份集合携带的任务）. */
	private static List<String> producersOf(Map<String, String> wiring) {
		return wiring.PRODUCER_TASKS.isEmpty() ? [] : wiring.PRODUCER_TASKS.split(",").toList()
	}

	/**
	 * 从构建输出里取「执行期注入提取了多少 Forge 源码」的计数.
	 *
	 * <p>取的是 {@code ForgeSourcesService} 自己的 lifecycle 日志；配置期那条路径在本用例里整批跳过，
	 * 因此这些计数只可能来自执行期的注入。
	 */
	private static List<Integer> extractedCounts(String output) {
		def counts = []
		output.eachLine { line ->
			def matcher = line =~ /extracted (\d+) forge source classes/

			if (matcher.find()) {
				counts << matcher.group(1).toInteger()
			}
		}
		return counts
	}

	/** 由 named jar 路径推出源码包路径（见 GenerateSourcesTask.getJarFileWithSuffix）. */
	private static String sourcesJarOf(String namedJar) {
		assert namedJar.toLowerCase(Locale.ROOT).endsWith(".jar"): "意外路径 ${namedJar}"
		return namedJar.substring(0, namedJar.length() - 4) + "-sources.jar"
	}

	/** 解析构建脚本写出的 {@code KEY=VALUE} 报告. */
	private static Map<String, String> parseReport(File report) {
		assert report.exists(): "未找到接线报告 ${report.absolutePath}"

		final Map<String, String> result = [:]

		report.readLines().each { line ->
			def index = line.indexOf('=')

			if (index > 0) {
				result[line.substring(0, index)] = line.substring(index + 1)
			}
		}

		return result
	}

	/** jar 里的条目名集合. */
	private static Set<String> entriesOf(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries().findAll { !it.directory }.collect { it.name } as Set
		}
	}

	/** jar 里的 {@code .class} 条目名集合. */
	private static Set<String> classEntries(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries().findAll { !it.directory && it.name.endsWith(".class") }.collect { it.name } as Set
		}
	}
}
