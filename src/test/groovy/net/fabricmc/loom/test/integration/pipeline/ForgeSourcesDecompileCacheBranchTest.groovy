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

import java.security.MessageDigest
import java.util.zip.ZipFile

import dev.architectury.loom.forge.ForgeSourcesService
import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestConstants
import net.fabricmc.loom.test.integration.buildSrc.forgeSourcesCacheDecompiler.StubSourceDecompiler
import net.fabricmc.loom.test.util.ForgeColdLoomCache
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * genSources 里 Forge 源码注入在**反编译缓存三条分支**下是否都写出正确的源码包.
 *
 * <p>{@code GenerateSourcesTask.runWithCache} 只在「本次真的跑了一个反编译 job」时调
 * {@code ForgeSourcesService.addForgeSources}（{@code job instanceof WorkToDoJob} →
 * {@code runDecompileJob}）。因此缓存命中程度不同，注入的时机与输入完全不同，有三条分支：
 * <ol>
 *     <li>{@code FullWorkJob}：缓存一条都没命中。注入面对**整包**输入。</li>
 *     <li>{@code PartialWorkJob}：部分命中。注入面对**只含未命中类**的临时 jar，命中类的源码预期来自缓存合并。</li>
 *     <li>{@code CompletedWorkJob}：全部命中。**注入根本不执行**，整份源码包直接由缓存还原。</li>
 * </ol>
 * 第三条最值得怀疑：如果「注入写在 completeJob 落缓存之前」这个前提不成立，全命中时会拿到一份
 * **不含 Forge 源码**（或只含反编译器产出）的包，而且不报错。本用例把三条分支全部落到实跑，并对
 * 产物内容（不是「文件存在」）做断言。
 *
 * <h4>怎么把「反编译 MC」这一步从用例里去掉</h4>
 * 被测的是反编译**之后**的注入与缓存还原，与反编译器实现无关。用例注册一个替身反编译器
 * （{@code buildSrc/forgeSourcesCacheDecompiler}）：给每个外层类写一个带标记的占位源文件。
 * 这样缓存能被**填满**——这是拿到全命中分支的前提，真反编译器做不到秒级完成。
 * 代价是证据强度：本用例证明的是「注入产物进缓存后被正确还原」，而不是「真反编译器的产物也一样」。
 *
 * <h4>三条分支怎么在同一个项目上一轮轮拿到</h4>
 * <ul>
 *     <li>第一轮 {@code --no-use-cache}：{@code runWithoutCache}，同时拿到「直接注入」的参照产物。</li>
 *     <li>第二轮：换回缓存（{@code --use-cache} 是任务输入，变了一定重跑），缓存是冷的 → {@code FullWorkJob}，
 *         并把这整份产物写进缓存。</li>
 *     <li>第三轮：缓存热了，但替身反编译器在**整包那一轮**刻意漏写了一个非 Forge 类
 *         （见 {@link StubSourceDecompiler}），于是只有它未命中 → {@code PartialWorkJob}。</li>
 *     <li>第四轮：那个类也在第三轮补进了缓存 → 零未命中 → {@code CompletedWorkJob}。</li>
 * </ul>
 * 后两轮之间没有输入变化，Gradle 会把任务判为 up-to-date；用例用
 * {@code -Pfabric.loom.decompileCacheMaxFiles} 微调一个真实存在、且不影响缓存的 {@code @Input}
 * 来强制重跑（该值只用于 prune，设得远大于缓存条目数时不会删任何东西）。
 *
 * <h4>断言用什么当参照</h4>
 * 「内容正确」不靠铁证如山的第三方，但也不是空的：期望的源码路径集合由**上游 Forge sources 构件**
 * 与输入 jar 的类集合推出（注入的过滤条件就是「同名 class 存在」），并对若干条内容做交叉比对：
 * 与第一轮的直接注入逐字节相同、不含替身反编译器的标记、且把 SRC 名换成了 named 名
 * （上游构件里 {@code RenderType.m_110506_()} 在产物里必须是 {@code RenderType.chunkBufferLayers()}）。
 *
 * <h4>冷是真的冷</h4>
 * 被测构建跑在**独立的 loom 共享缓存**上（{@code -Dfabric.loom.cache.dir}，见 {@link ForgeColdLoomCache}）：
 * Forge 工具链与映射构件从默认 home 播种，named MC jar、backup、源码包与反编译缓存刻意不播种。
 * 共享 home 只读，一个字节不动。
 */
class ForgeSourcesDecompileCacheBranchTest extends Specification implements GradleProjectTestTrait {
	private static final String MC_VERSION = '1.20.1'
	private static final String FORGE_VERSION = '47.2.1'
	/** 被测产物：single-jar Forge 场景下 {@code getMinecraftJars(NAMED)} 指向的那份构件. */
	private static final String MERGED_ARTIFACT = "forge-${MC_VERSION}-${FORGE_VERSION}-minecraft-merged"
	/** 替身反编译器注册的名字（见 buildSrc/forgeSourcesCacheDecompiler/TestPlugin）：任务名由它拼出. */
	private static final String GEN_SOURCES_TASK = 'genSourcesWithCachedStub'
	/** 反编译缓存文件：{@code <userCache>/decompile/v2.zip}（版本号见 GenerateSourcesTask.CACHE_VERSION）. */
	private static final String DECOMPILE_CACHE_PATH = 'decompile/v2.zip'
	private static final String FORGE_SOURCES_PREFIX = 'net/minecraftforge/'

	/**
	 * 播种好的隔离 loom 共享缓存.
	 *
	 * <p>{@code @Shared}：播种要复制数百 MB 的缓存，同一测试类的多条用例共用一份即可。
	 * 本用例会把这唯一一份用成「先冷后热」的四轮构建，因此只应有一条用例。
	 */
	@Lazy
	@Shared
	private static File coldLoomCache = ForgeColdLoomCache.seed(MC_VERSION, MERGED_ARTIFACT)

	def "反编译缓存的三条分支都写出正确的 Forge 源码"() {
		given: '一个 Forge 1.20.1 项目，被测构建用只做了「产物冷启动」的隔离 loom 缓存'
		def gradle = gradleProject(project: 'forge/simple')
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace('@MCVERSION@', MC_VERSION)
				.replace('@FORGEVERSION@', FORGE_VERSION)
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		gradle.buildSrc('forgeSourcesCacheDecompiler')
		gradle.buildGradle << ('''
			def reportFile = project.file('wiring.properties')

			project.afterEvaluate {
				def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
				def ns = net.fabricmc.loom.api.mappings.layered.MappingsNamespace
				def namedJars = loomExt.getMinecraftJars(ns.NAMED)
				def genSources = project.tasks.findByName('genSourcesWithCachedStub')
				def lines = []
				lines << 'NAMED_JAR=' + (namedJars.isEmpty() ? '' : namedJars[0].toString())
				lines << 'SOURCES_JAR=' + genSources.sourcesOutputJar.get().asFile.absolutePath
				reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
			}

			tasks.register('printWiringReport') {
				def report = reportFile
				doLast {
					println 'WIRING_REPORT=' + report.absolutePath
				}
			}
			''')
		gradle.gradleProperties << "\nsystemProp.fabric.loom.cache.dir=${coldLoomCache.absolutePath.replace("\\", "/")}\n"
		def forgeSourcesArtifact = findForgeSourcesArtifact()
		assert forgeSourcesArtifact != null: "未找到注入的来源构件（Forge sources），无法构造期望集合"

		when: '探针构建：确认这轮是冷的，并拿到产物路径'
		def probe = gradle.run(task: 'printWiringReport', configurationCache: false, args: ['--console=plain'])
		def wiring = parseReport(new File(gradle.projectDir, 'wiring.properties'))
		def namedJar = new File(wiring.NAMED_JAR)
		def backupJar = new File(wiring.NAMED_JAR + '.backup')
		def sourcesJar = new File(wiring.SOURCES_JAR)
		def stubRuns = new File(gradle.projectDir, 'stub-runs.txt')
		def decompileCache = new File(coldLoomCache, DECOMPILE_CACHE_PATH)

		then: 'cold：named jar / backup / 源码包 / 反编译缓存都不存在，配置期那条注入路径整批跳过'
		probe.task(':printWiringReport').outcome == SUCCESS
		!namedJar.exists()
		!backupJar.exists()
		!sourcesJar.exists()
		!decompileCache.exists()
		!stubRuns.exists()
		probe.output.contains(ForgeSourcesService.SKIPPED_LOG_MARKER)
		wiring.SOURCES_JAR == sourcesJarOf(wiring.NAMED_JAR)

		when: '第一轮：--no-use-cache，走 runWithoutCache'
		def runA = gradle.run(task: GEN_SOURCES_TASK, configurationCache: false,
		args: [
			'--console=plain',
			'--no-use-cache'
		])
		def entriesA = javaEntryHashes(sourcesJar)
		def runsA = decompileRuns(stubRuns)
		def expectedForge = expectedForgeSources(forgeSourcesArtifact, backupJar)
		def namedClasses = classEntries(namedJar)

		then: '成功，且确实没走缓存那条路：缓存文件连创建都没有'
		runA.task(':' + GEN_SOURCES_TASK).outcome == SUCCESS
		cacheStats(runA.output).isEmpty()
		!decompileCache.exists()

		and: '整包交给反编译器（替身写满全部外层类），Forge 源码由执行期注入补上'
		runsA.size() == 1
		runsA[0].classes as int == outerClassNames(backupJar).size()
		!runsA[0].skipped.startsWith(FORGE_SOURCES_PREFIX)
		extractedCounts(runA.output) == [expectedForge.size()]

		and: '产出的 Forge 条目集合与期望集合一致：一条不多、一条不少'
		// 期望集合 = 上游来源构件里、且在输入 jar 里有同名 class 的源码（内层类不进包）
		forgeEntries(entriesA).keySet() == expectedForge
		expectedForge.size() > 600
		expectedForge.every { namedClasses.contains(it.replace('.java', '.class')) }

		and: '内容不是占位符，而是注入后的 named 源码'
		def forgeA = forgeEntries(entriesA)
		forgeA.values().every { !it.contains(StubSourceDecompiler.MARKER) }
		// 上游构件在 SRC 命名空间里，注入后必须换成 named 名（这条与注入实现无关，是独立的对照）
		entryText(sourcesJar, 'net/minecraftforge/client/ChunkRenderTypeSet.java').contains('RenderType.chunkBufferLayers()')
		!entryText(sourcesJar, 'net/minecraftforge/client/ChunkRenderTypeSet.java').contains('m_110506_')
		// 绝大多数没有 MC 引用、无需重映射的文件应当与上游逐字节一致（1.20.1 实测 327/664）
		identicalToUpstream(sourcesJar, forgeSourcesArtifact) >= 300

		when: '第二轮：换回缓存，缓存是冷的 → FullWorkJob，并把这份产物写进缓存'
		def runB = gradle.run(task: GEN_SOURCES_TASK, configurationCache: false, args: ['--console=plain'])
		def entriesB = javaEntryHashes(sourcesJar)
		def runsB = decompileRuns(stubRuns)
		def statsB = cacheStats(runB.output)
		def skippedClass = runsB[1].skipped

		then: '这一轮 0 命中：整包重新反编译（这就是 FullWorkJob 的判据）'
		runB.task(':' + GEN_SOURCES_TASK).outcome == SUCCESS
		statsB.size() == 1
		statsB[0].hits == 0
		statsB[0].misses > 0

		and: '未命中数等于「不含 $ 且带包」的类数：JarWalker 的外层类判定与替身写出来的集合一致'
		// 这条同时是「全命中分支可达」的前提：只要有一个类替身写不出来，它就永远进不了缓存
		statsB[0].misses == outerClassNames(backupJar).size()
		runsB.size() == 2
		runsB[1].classes as int == statsB[0].misses
		runsB[1].skipped == skippedClass
		// 被跳过的必须是非 Forge 类：Forge 类会在同一轮被注入补上，那样就造不出未命中
		!skippedClass.startsWith(FORGE_SOURCES_PREFIX)

		and: '第二轮产物与第一轮（直接注入）逐字节一致，整包进缓存的是注入后的版本'
		entriesB == entriesA

		when: '第三轮：缓存热了，但有一个类上一轮没进缓存 → PartialWorkJob'
		def runD = gradle.run(task: GEN_SOURCES_TASK, configurationCache: false,
		args: [
			'--console=plain',
			'-Pfabric.loom.decompileCacheMaxFiles=40000'
		])
		def entriesD = javaEntryHashes(sourcesJar)
		def runsD = decompileRuns(stubRuns)
		def statsD = cacheStats(runD.output)

		then: '这一轮恰好一个未命中：注入只面对「只含未命中类」的临时 jar'
		runD.task(':' + GEN_SOURCES_TASK).outcome == SUCCESS
		statsD.size() == 1
		statsD[0].misses == 1
		statsD[0].hits == statsB[0].misses - 1
		runsD.size() == 3
		runsD[2].classes as int == 1
		runsD[2].input.startsWith('loom-cache-incomplete')
		runsD[2].skipped == '-'

		and: '注入这一轮一个 Forge 文件都没提到：未命中子集里没有 Forge 类'
		extractedCounts(runD.output) == [0]

		and: '但产物仍然完整：Forge 源码全部来自缓存合并，与第一轮逐字节一致'
		forgeEntries(entriesD).keySet() == expectedForge
		forgeEntries(entriesD) == forgeA
		def namesB = entriesB.keySet()
		def namesD = entriesD.keySet()
		namesD - namesB == [
			"${skippedClass}.java".toString()
		] as Set
		namesB - namesD == [] as Set

		when: '第四轮：上一轮把那个类补进了缓存 → 零未命中 → CompletedWorkJob'
		def runE = gradle.run(task: GEN_SOURCES_TASK, configurationCache: false,
		args: [
			'--console=plain',
			'-Pfabric.loom.decompileCacheMaxFiles=40001'
		])
		def entriesE = javaEntryHashes(sourcesJar)
		def runsE = decompileRuns(stubRuns)
		def statsE = cacheStats(runE.output)

		then: '这一轮全部命中'
		runE.task(':' + GEN_SOURCES_TASK).outcome == SUCCESS
		statsE.size() == 1
		statsE[0].misses == 0
		statsE[0].hits == statsB[0].misses

		and: '反编译器一次都没跑，注入也没执行：整份源码包只能是缓存还原出来的'
		runsE.size() == runsD.size()
		extractedCounts(runE.output).isEmpty()

		and: '即便如此，Forge 源码仍然完整且正确（这就是本用例存在的理由）'
		forgeEntries(entriesE).keySet() == expectedForge
		forgeEntries(entriesE) == forgeA
		forgeEntries(entriesE).values().every { !it.contains(StubSourceDecompiler.MARKER) }
		entryText(sourcesJar, 'net/minecraftforge/client/ChunkRenderTypeSet.java').contains('RenderType.chunkBufferLayers()')

		and: '产物与上一轮逐字节一致：缓存还原没有丢掉或改写任何条目'
		entriesE == entriesD
	}

	/** 探针报出的源码包路径（{@code GenerateSourcesTask.getJarFileWithSuffix}）. */
	private static String sourcesJarOf(String namedJar) {
		assert namedJar.toLowerCase(Locale.ROOT).endsWith('.jar'): "意外路径 ${namedJar}"
		return namedJar.substring(0, namedJar.length() - 4) + '-sources.jar'
	}

	/** 定位注入的来源：Gradle 模块缓存里的 Forge sources 构件（{@code <mc>-<forge>-sources.jar}）. */
	private static File findForgeSourcesArtifact() {
		final File root = new File(LoomTestConstants.TEST_DIR,
				"integration/gradle_home/caches/modules-2/files-2.1/net.minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}")

		if (!root.isDirectory()) {
			return null
		}

		return root.listFiles()
				.findAll { it.isDirectory() }
				.collect { new File(it, "forge-${MC_VERSION}-${FORGE_VERSION}-sources.jar") }
				.findAll { it.isFile() }
				.first()
	}

	/**
	 * 期望出现在源码包里的 Forge 源码路径集合.
	 *
	 * <p>注入的过滤条件就是「来源构件里的源码，其同名 class 存在于输入 jar」（内层类另外被显式丢弃）。
	 * 这里用上游构件与输入 jar 独立地把这个集合算出来，不依赖注入的实现。
	 */
	private static Set<String> expectedForgeSources(File forgeSourcesArtifact, File inputJar) {
		final Set<String> classes = classEntries(inputJar)
		return javaEntries(forgeSourcesArtifact)
				.findAll { !it.contains('$') && classes.contains(it.replace('.java', '.class')) } as Set
	}

	/** 产物里与上游构件逐字节相同的 Forge 源文件数（无需重映射的文件应当原样保留）. */
	private static int identicalToUpstream(File sourcesJar, File forgeSourcesArtifact) {
		final Map<String, String> upstream = javaEntryHashes(forgeSourcesArtifact)
		return javaEntryHashes(sourcesJar).findAll { path, hash ->
			path.startsWith(FORGE_SOURCES_PREFIX) && upstream.get(path) == hash
		}.size()
	}

	/** 产物里的 Forge 源码条目：路径 -> 内容 sha256. */
	private static Map<String, String> forgeEntries(Map<String, String> entries) {
		return entries.findAll { path, hash -> path.startsWith(FORGE_SOURCES_PREFIX) }
	}

	/** 解析构建输出里的「反编译缓存命中/未命中」（CachedJarProcessor 就是按这两个数选分支的）. */
	private static List<Map<String, Integer>> cacheStats(String output) {
		def stats = []

		output.eachLine { line ->
			def matcher = line =~ /Decompile cache stats: (\d+) hits, (\d+) misses/

			if (matcher.find()) {
				stats << [hits: matcher.group(1).toInteger(), misses: matcher.group(2).toInteger()]
			}
		}

		return stats
	}

	/** 解析「执行期注入提取了多少 Forge 源码」（{@code ForgeSourcesService} 自己的 lifecycle 日志）. */
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

	/** 读回替身反编译器每次执行的记录（每段以 {@code === RUN} 开头，见 StubSourceDecompiler）. */
	private static List<Map<String, String>> decompileRuns(File log) {
		if (!log.exists()) {
			return []
		}

		def runs = []

		log.eachLine { line ->
			if (line.startsWith('=== RUN')) {
				def fields = line.split(' ')
				def run = [run: fields[2]]
				fields.drop(3).each { field ->
					def index = field.indexOf('=')
					run[field.substring(0, index)] = field.substring(index + 1)
				}
				runs << run
			}
		}

		return runs
	}

	/** jar 里 {@code .java} 条目名 -> 内容 sha256. */
	private static Map<String, String> javaEntryHashes(File jar) {
		final Map<String, String> result = [:]

		new ZipFile(jar).withCloseable { zip ->
			zip.entries().each { entry ->
				if (!entry.directory && entry.name.endsWith('.java')) {
					zip.getInputStream(entry).withCloseable { stream ->
						result[entry.name] = sha256(stream.bytes)
					}
				}
			}
		}

		return result
	}

	/** jar 里的 {@code .java} 条目名集合. */
	private static Set<String> javaEntries(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries()
					.findAll { !it.directory && it.name.endsWith('.java') }
					.collect { it.name } as Set
		}
	}

	/** jar 里的 {@code .class} 条目名集合. */
	private static Set<String> classEntries(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries()
					.findAll { !it.directory && it.name.endsWith('.class') }
					.collect { it.name } as Set
		}
	}

	/** 按 JarWalker 的规则数外层类：名字不含 {@code $} 且带包. */
	private static Set<String> outerClassNames(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries()
					.findAll { !it.directory && it.name.endsWith('.class') && !it.name.contains('$') && it.name.contains('/') }
					.collect { it.name.substring(0, it.name.length() - '.class'.length()) } as Set
		}
	}

	private static String entryText(File jar, String entryName) {
		new ZipFile(jar).withCloseable { zip ->
			return new String(zip.getInputStream(zip.getEntry(entryName)).withCloseable { it.bytes }, 'UTF-8')
		}
	}

	private static String sha256(byte[] bytes) {
		return MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
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
}
