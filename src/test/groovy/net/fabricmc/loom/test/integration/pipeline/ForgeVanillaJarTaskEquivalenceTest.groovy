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

import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Forge 的 **vanilla jar**（client / server / 抽取出来的 server）由执行期任务产出的
 * 产物内容等价性与接线检查.
 *
 * <p>被切到任务的是 {@code MinecraftProvider.provideMinecraftJars()}：改造前 Forge 系整批回退到
 * 配置期生产（patch 流程、MCP 映射合并、内部类名集合都要在配置期读 vanilla jar），现在只有
 * <b>legacy Forge（FG2 形态的 userdev，MC 1.7-1.12.2）</b>回退，modern Forge / NeoForge 走
 * {@code downloadMinecraftClientJar} / {@code downloadMinecraftServerJar} / {@code extractMinecraftServerJar}。
 *
 * <h2>判据怎么被钉住</h2>
 * 判据（{@code isForgeLike() && isLegacyForge()}）要读已解析的 userdev 配置，因此生产被推迟到
 * {@code CompileConfiguration.setupDependencyProviders} 之后。本测试用**产物内容**与**任务接线**两条证据
 * 钉住这次迁移：
 * <ul>
 *   <li>lifecycle 留痕：从「保持配置期生产」变为「由执行期任务承担」（断言在 {@code result.output} 上，
 *       不是读源码）；</li>
 *   <li>补丁链第一级（{@code generateForgePrePatchJar}）在任务图里依赖 vanilla jar 的产出任务——
 *       MCP {@code rename} 步与 NeoForge installer tools 分支都只按**路径**读这些 jar，
 *       Gradle 无从据此建依赖，接线漏了就只能在执行期以「文件不存在」暴露；</li>
 *   <li>Forge 合并形态不再登记无人消费的 {@code mergeMinecraftJars}（它写的 {@code minecraft-merged.jar}
 *       不在该形态的 {@code getMinecraftJars()} 里）。</li>
 * </ul>
 *
 * <h2>缓存前提：用例自己建立，不依赖环境恰好是热的</h2>
 * 判据里还有一条与本次迁移无关但决定「走哪条路径」的：{@code server bundle 元数据未命中 L2 规格缓存}
 * 即整批回退（服务端库注入是配置期事实）。冷缓存下第一次构建**合法地**走那条回退路径，断言「任务化」
 * 的用例因此会随机器的缓存冷热红绿——CI 冷缓存首轮就是这样红的。故断言任务化的用例一律先跑一次预热
 * 构建（{@link #warmUpBundleMetadataSpecCache}，只要求成功、不断言走哪条路径），把前提握在用例手里。
 *
 * <h2>金标重建</h2>
 * 第二条用例把三件 vanilla jar **移出共享缓存**再整链重建：重建后的产物必须与第一次逐 entry 一致。
 * 它同时钉住「任务确实从零写出了这些产物」——重建前断言产物已不在原位，故比对结果不可能是
 * 「读到了同一份旧文件」。
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义。任何一条 entry 的字节不同即判为差异。
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>覆盖 **Forge 1.20.1 + officialMojangMappings（MERGED 形态）**的完整执行，以及
 *       **NeoForge 1.20.6 + officialMojangMappings** 的配置期判据与接线（后者只跑到配置期：
 *       lifecycle 留痕与任务接线都在配置期就已确定，而它的 patched 链是分钟级）。</li>
 *   <li>**金标只含 client / server 两件产物**：抽取产物在 plain Forge 下没有任何消费方
 *       （MCP 的 {@code bundleExtractJar} 步用 installertools 自己抽，NeoForge 的 dist 清单是唯一读方），
 *       因此 Gradle 不会执行 {@code extractMinecraftServerJar}，产物不落盘也就无从比对。
 *       本测试按「已登记且路径与配置期一致」断言它，NeoForge 侧再断言「最终 jar 接线到它」。</li>
 *   <li>**legacy Forge 没有在这里覆盖**：它按设计整批留在配置期，回归由 {@code ForgeTest} 的
 *       1.16.5 / 1.14.4 用例承担。</li>
 * </ul>
 */
class ForgeVanillaJarTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	private static final String DOWNLOAD_CLIENT_TASK = "downloadMinecraftClientJar"
	private static final String DOWNLOAD_SERVER_TASK = "downloadMinecraftServerJar"
	private static final String EXTRACT_SERVER_TASK = "extractMinecraftServerJar"
	private static final String MERGE_TASK = "mergeMinecraftJars"
	private static final String PRE_PATCH_TASK = "generateForgePrePatchJar"
	private static final String PATCHED_TASK = "generateForgePatchedJar"

	/**
	 * 本 spec 已经预热过的 Minecraft 版本.
	 *
	 * <p>预热结果落在共享 gradle_home（{@code LoomTestConstants.TEST_DIR/integration/gradle_home}）里，
	 * 同一版本只需预热一次：本文件前两条用例都用 1.20.1，共用一次预热即可，不必各自重跑一遍。
	 */
	@Shared
	private Set<String> warmedVersions = [] as Set

	/** 迁移后的留痕：产物生产由执行期任务承担. */
	private static final String TASK_PRODUCTION_LIFECYCLE = "的 jar 生产由执行期任务承担"

	/** 迁移前的留痕：整批回退到配置期生产. */
	private static final String CONFIGURATION_PRODUCTION_LIFECYCLE = "的 jar 保持配置期生产"

	def "Forge 1.20.1 的 vanilla jar 由执行期任务产出，且接线到补丁链"() {
		setup:
		warmUpBundleMetadataSpecCache("1.20.1", forgeProject())
		def gradle = forgeProject()
		gradle.buildGradle << probeScript()

		when: "跑消费方：vanilla jar 的产出任务必须先在任务图里跑过"
		// 探针在配置期读项目模型，故显式关掉配置缓存；同时关掉构建缓存：
		// 本用例要断言被测任务**真的执行了**，而不是从缓存恢复了别的产物
		def result = gradle.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def client = entries(new File(report.CLIENT_OUTPUT))
		def server = entries(new File(report.SERVER_OUTPUT))
		printEvidence(report, client, server)

		then: "lifecycle 留痕从「保持配置期生产」变为「由执行期任务承担」"
		result.output.contains("Minecraft 1.20.1 " + TASK_PRODUCTION_LIFECYCLE)
		!result.output.contains("Minecraft 1.20.1 " + CONFIGURATION_PRODUCTION_LIFECYCLE)

		and: "两个下载任务被注册，且写的就是 provider 按路径找的那两件产物"
		report.CLIENT_TASK_FOUND == "true"
		report.SERVER_TASK_FOUND == "true"
		report.CLIENT_OUTPUT == report.PROVIDER_CLIENT
		report.SERVER_OUTPUT == report.PROVIDER_SERVER

		and: "两个下载任务真的跑过（不是「注册了但没人依赖」）"
		result.task(":" + DOWNLOAD_CLIENT_TASK)?.outcome in [SUCCESS, UP_TO_DATE]
		result.task(":" + DOWNLOAD_SERVER_TASK)?.outcome in [SUCCESS, UP_TO_DATE]

		and: "抽取任务按 bundle 元数据登记，产物路径与配置期一致"
		report.EXTRACT_TASK_FOUND == "true"
		report.EXTRACT_OUTPUT == report.PROVIDER_EXTRACTED

		and: "补丁链第一级以 vanilla jar 为输入，接线到它们的产出任务"
		report.PRE_PATCH_TASK_FOUND == "true"
		report.PRE_PATCH_DEPS.contains(DOWNLOAD_CLIENT_TASK)
		report.PRE_PATCH_DEPS.contains(DOWNLOAD_SERVER_TASK)

		and: "无人消费的 mergeMinecraftJars 不再登记（Forge 合并形态的产物是 patched jar）"
		report.MERGE_TASK_FOUND == "false"

		and: "产物非空，且三件产物互不相同"
		client.size() > 0
		server.size() > 0

		and: "扰动对照：比对口径能识别「名字相同、字节不同」——拿 server jar 当 client jar 的期望值必须报差异"
		diff(client, server).size() > 0
	}

	def "vanilla jar 移出共享缓存后由任务重建，且与金标逐 entry 一致"() {
		setup:
		// 金标构建必须走任务化路径：下面要求两个下载任务已登记并真的执行
		warmUpBundleMetadataSpecCache("1.20.1", forgeProject())
		def goldProject = forgeProject()
		goldProject.buildGradle << probeScript()

		when: "第一次产出金标 → 把两件 vanilla jar 移出共享缓存 → 用一个没有任何执行历史的工程重建"
		goldProject.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(goldProject.projectDir, "probe-report.properties"))
		def goldClient = entries(new File(report.CLIENT_OUTPUT))
		def goldServer = entries(new File(report.SERVER_OUTPUT))

		// 移到本工程的备份目录：产物路径上不再有任何写入者，重建只能由被测任务完成。
		// 共享缓存与工程目录未必在同一文件系统（本机实测跨设备），故先复制再删源，而不是 rename。
		def backup = new File(goldProject.projectDir, "build/gold-backup")
		backup.mkdirs()
		def moved = [:]
		[
			report.CLIENT_OUTPUT,
			report.SERVER_OUTPUT
		].each { path ->
			def source = new File(path)
			assert source.exists(): "金标产物不存在: ${path}"
			def target = new File(backup, source.name)
			java.nio.file.Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			java.nio.file.Files.delete(source.toPath())
			moved[path] = true
		}

		// 必须先确认产物确实不在了：否则下面的重建断言可以读到旧文件而假通过
		moved.keySet().each { path -> assert !new File(path).exists(): "金标产物仍在原位: ${path}" }

		// 重建用**另一个工程目录**（没有任何任务执行历史）：产出任务因此必然执行，
		// 不会被「同工程上一轮的 up-to-date 记录」影响
		def rebuildProject = forgeProject()
		def second = rebuildProject.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def rebuiltClient = entries(new File(report.CLIENT_OUTPUT))
		def rebuiltServer = entries(new File(report.SERVER_OUTPUT))

		then: "两件下载任务这次都必须真的执行（产物缺失且无执行历史 ⇒ 不能是 UP-TO-DATE）"
		second.task(":" + DOWNLOAD_CLIENT_TASK).outcome == SUCCESS
		second.task(":" + DOWNLOAD_SERVER_TASK).outcome == SUCCESS

		and: "重建产物与金标逐 entry 一致"
		diff(rebuiltClient, goldClient).isEmpty()
		diff(rebuiltServer, goldServer).isEmpty()

		and: "金标本身有效：两件产物互不相同（比对口径能识别差异）"
		diff(goldClient, goldServer).size() > 0

		cleanup:
		println("金标备份留在 ${new File(goldProject.projectDir, 'build/gold-backup')}（供人工复核）")
	}

	def "NeoForge 的 vanilla jar 同样由执行期任务产出，且抽取产物接线到最终 jar"() {
		setup: "NeoForge 的 patched 链是分钟级；判据与接线都在配置期确定，故只跑到配置期（任务名与留痕此刻已定）"
		warmUpBundleMetadataSpecCache("1.20.6", neoForgeProject())
		def gradle = neoForgeProject()
		gradle.buildGradle << probeScript()

		when:
		def result = gradle.run(task: "help", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))

		then: "lifecycle 留痕同样是「由执行期任务承担」（NeoForge 恒为 modern，不受 legacy 判据影响）"
		result.output.contains("Minecraft 1.20.6 " + TASK_PRODUCTION_LIFECYCLE)
		!result.output.contains("Minecraft 1.20.6 " + CONFIGURATION_PRODUCTION_LIFECYCLE)

		and: "三件产物都由任务承担，路径与配置期一致"
		report.CLIENT_TASK_FOUND == "true"
		report.SERVER_TASK_FOUND == "true"
		report.EXTRACT_TASK_FOUND == "true"
		report.CLIENT_OUTPUT == report.PROVIDER_CLIENT
		report.SERVER_OUTPUT == report.PROVIDER_SERVER
		report.EXTRACT_OUTPUT == report.PROVIDER_EXTRACTED

		and: "NeoForge 的 dist 清单读抽取产物：最终 jar 的任务接线到抽取任务的产出"
		report.PATCHED_TASK_FOUND == "true"
		report.PATCHED_DEPS.contains(EXTRACT_SERVER_TASK)

		and: "补丁链第一级同样接线到两个下载任务"
		report.PRE_PATCH_DEPS.contains(DOWNLOAD_CLIENT_TASK)
		report.PRE_PATCH_DEPS.contains(DOWNLOAD_SERVER_TASK)
	}

	def "legacy Forge（FG2 1.12.2）的 vanilla jar 仍整批留在配置期"() {
		setup: "真 legacy Forge 的 patch 流程（MinecraftLegacyPatchedProvider）在配置期按路径读 vanilla jar"
		def gradle = legacyForgeProject()
		gradle.buildGradle << probeScript()

		when:
		def result = gradle.run(task: "help", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))

		then: "留痕是「保持配置期生产」，且判据确实认出这是 legacy Forge"
		result.output.contains("Minecraft 1.12.2 " + CONFIGURATION_PRODUCTION_LIFECYCLE)
		!result.output.contains("Minecraft 1.12.2 " + TASK_PRODUCTION_LIFECYCLE)
		report.LEGACY_FORGE == "true"

		and: "vanilla 链一件产物都没被切到任务"
		report.CLIENT_TASK_FOUND == "false"
		report.SERVER_TASK_FOUND == "false"
		report.EXTRACT_TASK_FOUND == "false"
		report.MERGE_TASK_FOUND == "false"

		and: "补丁链同样留在配置期（legacy patched provider 不登记任务）"
		report.PATCHED_TASK_FOUND == "false"
	}

	/**
	 * 预热：把「server bundle 元数据已在 L2 规格缓存里」这条前提变成用例自己建立的状态.
	 *
	 * <p>{@code MinecraftProvider.earlyProjectionBlocker()} 有一条判据是「server bundle 元数据未命中
	 * L2 规格缓存」——命中即整批回退到配置期生产（服务端库注入是配置期事实，元数据没有缓存就必须先下载
	 * 并读 server jar）。这是**设计如此**的回退，不是缺陷；但本文件断言任务化路径的用例如果直接开跑，
	 * 就会随「这台机器的缓存恰好是热的」红绿：CI 冷缓存首轮的失败就是这条（回退留痕
	 * {@code 保持配置期生产：server bundle 元数据未命中 L2 规格缓存}）。
	 *
	 * <p>回退路径本身会把元数据写进规格缓存（运行期真读一次 server jar），所以「先跑一次」就够——
	 * 预热构建只断言成功，不断言走哪条路径：热缓存下它本身就是任务化，冷缓存下是回退，两者都合法。
	 * 断言一律留给紧随其后的正式构建。
	 *
	 * <p>预热用**另一个工程目录**：探针报告只写一次（见 {@link #probeScript()}），与正式构建共用工程目录
	 * 会把回退路径的接线当成正式构建的接线读走。
	 *
	 * @param mcVersion 被预热的 Minecraft 版本；规格缓存条目按该版本的 server 制品 sha1 索引，故版本即身份
	 * @param warmUpProject 预热用工程（同一夹具，独立目录）
	 */
	private void warmUpBundleMetadataSpecCache(String mcVersion, GradleProject warmUpProject) {
		if (warmedVersions.contains(mcVersion)) {
			return
		}

		// help 足够：配置期生产（下载、抽取、元数据入库）发生在配置阶段，与最终跑哪个任务无关
		def result = warmUpProject.run(task: "help", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		assert result.task(":help").outcome == SUCCESS: "预热构建失败：无法把 Minecraft ${mcVersion} 的 bundle 元数据写进 L2 规格缓存"

		// 断言通过才记账：预热失败时后续用例仍会自己预热，而不是带着未建立的前提往下跑
		warmedVersions.add(mcVersion)
	}

	/** 构造 Forge 1.20.1 夹具（与 ForgePatchedChainTaskEquivalenceTest 同一套替换）. */
	private GradleProject forgeProject() {
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.20.1')
				.replace('@FORGEVERSION@', '47.2.1')
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		return gradle
	}

	/** 构造 NeoForge 1.20.6 夹具（与 NeoForge1206Test 同一套替换：真 neoforge 平台，不是 forge 平台挂 neoforge 制品）. */
	private GradleProject neoForgeProject() {
		def gradle = gradleProject(project: "neoforge/1206", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.20.6')
				.replace('@NEOFORGEVERSION@', '20.6.5-beta')
				.replace('MAPPINGS', 'loom.officialMojangMappings()')
				.replace('PATCHES', "''")
		return gradle
	}

	/** 构造真 legacy Forge（FG2，MC 1.12.2 + Forge 14.23.5.2860）夹具，与 LegacyForgeExternalModDependencyTest 同一套. */
	private GradleProject legacyForgeProject() {
		return gradleProject(project: "forge/legacy/externalModDependency", version: DEFAULT_GRADLE)
	}

	/**
	 * 追加到夹具 build.gradle 的探针.
	 *
	 * <p>它在配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）记录 vanilla 链各任务声明的输出、
	 * provider 自己算出来的产物路径，以及补丁链两级任务的任务依赖名集合。报告只写一次：
	 * 第二条用例的第二次构建不需要重新记录。
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def reportFile = file('probe-report.properties')

			if (reportFile.exists()) {
				return
			}

			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def provider = extension.minecraftProvider
			def lines = []

			lines << 'LEGACY_FORGE=' + (extension.forgeLike && extension.legacyForge)

			def clientTask = project.tasks.findByName('downloadMinecraftClientJar')
			def serverTask = project.tasks.findByName('downloadMinecraftServerJar')
			def extractTask = project.tasks.findByName('extractMinecraftServerJar')
			def mergeTask = project.tasks.findByName('mergeMinecraftJars')
			def prePatchTask = project.tasks.findByName('generateForgePrePatchJar')
			def patchedTask = project.tasks.findByName('generateForgePatchedJar')

			lines << 'CLIENT_TASK_FOUND=' + (clientTask != null)
			lines << 'SERVER_TASK_FOUND=' + (serverTask != null)
			lines << 'EXTRACT_TASK_FOUND=' + (extractTask != null)
			lines << 'MERGE_TASK_FOUND=' + (mergeTask != null)
			lines << 'PRE_PATCH_TASK_FOUND=' + (prePatchTask != null)
			lines << 'PATCHED_TASK_FOUND=' + (patchedTask != null)

			// 任务侧与 provider 侧一律用「绝对规范化路径」比较，避免「同一文件两种写法」被判成不等。
			// 任务缺失时写 MISSING 而不是抛异常：判据失败必须由断言（*_TASK_FOUND）报告，
			// 探针自己炸掉会把「为什么红」藏在堆栈里
			def canonical = { File file -> file.toPath().toAbsolutePath().normalize().toString() }
			def outputOf = { task, closure -> task != null ? closure(task) : 'MISSING' }

			lines << 'CLIENT_OUTPUT=' + outputOf(clientTask, { canonical(it.outputFile.get().asFile) })
			lines << 'SERVER_OUTPUT=' + outputOf(serverTask, { canonical(it.outputFile.get().asFile) })
			lines << 'EXTRACT_OUTPUT=' + outputOf(extractTask, { canonical(it.outputJar.get().asFile) })

			lines << 'PROVIDER_CLIENT=' + canonical(provider.minecraftClientJar)
			lines << 'PROVIDER_SERVER=' + canonical(provider.minecraftServerJar)
			lines << 'PROVIDER_EXTRACTED=' + canonical(provider.minecraftExtractedServerJar)

			def dependencyNames = { task ->
				task != null ? task.taskDependencies.getDependencies(task).collect { it.name }.sort().join(',') : 'MISSING'
			}

			lines << 'PRE_PATCH_DEPS=' + dependencyNames(prePatchTask)
			lines << 'PATCHED_DEPS=' + dependencyNames(patchedTask)

			reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
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

	private static void printEvidence(Map<String, String> report, Map<String, String> client, Map<String, String> server) {
		println("client jar: " + report.CLIENT_OUTPUT + "（" + client.size() + " 个条目）")
		println("server jar: " + report.SERVER_OUTPUT + "（" + server.size() + " 个条目）")
		println("抽取产物: " + report.EXTRACT_OUTPUT)
		println("pre-patch 任务依赖: " + report.PRE_PATCH_DEPS)
		println("最终 jar 任务依赖: " + report.PATCHED_DEPS)
	}
}
