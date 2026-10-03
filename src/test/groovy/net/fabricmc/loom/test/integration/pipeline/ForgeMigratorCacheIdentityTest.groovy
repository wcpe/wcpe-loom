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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Forge 迁移器缓存的**身份检查**：共享目录里的另一代内容不得改变迁移产物.
 *
 * <h2>被测缺陷</h2>
 *
 * <p>{@code FieldMappingsMigrator} 与 {@code MethodInheritanceMappingsMigrator} 把「由输入算出的中间数据」
 * 缓存在 forge 缓存目录下（{@code migrated-fields.json} / {@code method-inheritance-migrator.json}），
 * 而那是一条**跨工作树、跨 daemon 共享**的路径（{@code <mcProvider>/<platform>/<forge 版本>}）：既不含
 * mappings 标识，也不含 {@code hasSrg}/{@code hasMojang}，更不含各 jar 的内容。改造前判定缓存是否可用的
 * 唯一条件是 {@code Files.exists}，于是盘上那份迁移结果出自哪一组输入完全不可知——ns 开关变化、
 * 补丁中间产物换代、另一棵工作树跑另一套配置，都会改写同一份缓存，**谁最后写谁说了算**。
 * 后果是同一份声明输入可以对应两种产物字节，而任务判 UP-TO-DATE（或命中构建缓存）时既不重算也不写缓存，
 * 盘上就留下与当前输入无关的陈旧产物。
 *
 * <h2>三条对照</h2>
 *
 * <ul>
 *   <li><b>①冷/暖直比</b>：共享缓存里先躺着**另一代内容**（由真实迁移器、以另一个 jar 为输入写出），
 *       任务重跑一次留档；再删掉两个 JSON、让任务从声明输入冷算一次，两份产物必须逐行一致。
 *       改造前：暖态采信了别代的迁移结果，产物与冷算不同（本仓库实测过约 36/85705 行差异）。</li>
 *   <li><b>②投毒对照</b>：保持声明输入不变，把两个 JSON 换成 {@code {}} 与伪造条目，任务重跑一次，
 *       产物与就绪标记必须与冷参照逐行/逐字相同。改造前：任务会把「空集」当成本代结果，
 *       产物等于「没有迁移过」的原始 mappings（本仓库实测冷参照有 10 条字段迁移，故必然不同）。</li>
 *   <li><b>③活性对照</b>（防比对口径空转）：「键一致的完整缓存 ⇒ 暖态产物 == 冷参照」与
 *       「键一致但内容被扰动 ⇒ 产物与就绪标记**必须**变化」必须同时成立。前半条保证修法不是
 *       「干脆不读缓存」，后半条保证缓存内容真的流进了产物、比对口径确实能识别差异；
 *       本用例因此同时也钉住「{@code mappings-migrated.hash} 随缓存代次变化」。</li>
 * </ul>
 *
 * <h2>怎么把「另一代」造出来</h2>
 *
 * <p>本测试不伪造缓存内容，而是调用**真实迁移器**、只把 {@code patchedIntermediateJar} 换成另一个真实的
 * jar（Forge universal）：迁移结果因此仍是「某组真实输入的纯函数」，只是那组输入不是本工程声明的那一组
 * ——这正是共享目录里会出现别代内容的成因。②注入的则是纯垃圾（{@code {}} / 伪造条目），两种形态分别对应
 * 「另一代写方」与「写了一半/写了别的东西的写方」。
 *
 * <h2>比对口径与证据</h2>
 *
 * <p>产物逐**行**比 SHA-256（mappings 是纯文本 tiny，没有 entry 概念），行数也必须一致；
 * 就绪标记（{@code mappings-migrated.hash}）逐字比。每次运行的路径都从探针写的报告里读，
 * 报告的产物路径即任务自己声明的输出——避免测试自己拼路径拼错而「比了别的文件」。
 *
 * <h2>覆盖边界（如实记录）</h2>
 *
 * <ul>
 *   <li>覆盖 **MERGED 形态 + Forge 1.20.1 + officialMojangMappings**（srg 命名空间，hasSrg = true）。</li>
 *   <li>**NeoForge 的 mojang 分支没有覆盖**：夹具是 Forge。</li>
 *   <li>**跨工作树/跨 daemon 的并发写没有覆盖**：本用例在单个探针进程内构造「别代内容」，
 *       文件级并发由既有的原子发布保证（{@code AtomicFiles}），不在本用例射程内。</li>
 *   <li>**legacy Forge 与 legacy mixin AP 没有覆盖**：两者按 {@code mappingsMigrationProjectionBlocker()}
 *       整批留在配置期；配置期路径与本用例共用同一份 {@code MappingsMigratorCache}，但那条路径的
 *       端到端产物未在此处比对。</li>
 * </ul>
 */
class ForgeMigratorCacheIdentityTest extends Specification implements GradleProjectTestTrait {
	/** 迁移产出任务名（见 {@code ManipulateForgeMappingsTask.NAME}）. */
	private static final String MIGRATE_TASK = "manipulateForgeMappings"

	/** 一个夹具工程在三条对照间复用：补丁链与 mappings 合并只跑一次，后续运行只重跑被测任务. */
	@Shared
	private GradleProject fixture

	def "①冷/暖直比：共享缓存里另一代的内容不得改变迁移产物"() {
		setup: "共享缓存里先躺着另一代内容（真实迁移器 + 另一个 jar），任务重跑一次并留档"
		def warm = runTask("foreign")
		def warmMigrated = lines(new File(warm.TASK_MIGRATED))
		def warmWithNs = lines(new File(warm.TASK_MIGRATED_WITH_NS))
		def warmHash = readHash(warm)
		def foreignEntries = entriesOf(new File(probeDir(), "foreign-field-cache.json"))

		when: "删掉两个 JSON（缓存不再存在），对同一份声明输入重跑一次"
		def cold = runTask("cold")
		def coldMigrated = lines(new File(cold.TASK_MIGRATED))
		def coldWithNs = lines(new File(cold.TASK_MIGRATED_WITH_NS))
		def coldHash = readHash(cold)

		then: "两份产物逐行一致、行数一致"
		def migratedDiff = diff(warmMigrated, coldMigrated)
		def withNsDiff = diff(warmWithNs, coldWithNs)
		explain(migratedDiff, new File(warm.TASK_MIGRATED), new File(cold.TASK_MIGRATED))
		explain(withNsDiff, new File(warm.TASK_MIGRATED_WITH_NS), new File(cold.TASK_MIGRATED_WITH_NS))
		migratedDiff.isEmpty()
		withNsDiff.isEmpty()
		warmMigrated.size() == coldMigrated.size()

		and: "就绪标记（迁移器返回值算出的哈希）也逐字一致"
		println("就绪标记：暖态（另一代缓存）=${warmHash} / 冷态=${coldHash}")
		warmHash == coldHash

		and: "对照非空转：产物本身非空、冷参照确实做过迁移，且另一代内容与本代内容确实不同"
		coldMigrated.size() > 0
		diff(coldMigrated, lines(new File(cold.TASK_RAW_MAPPINGS_WITH_NS))).size() > 0
		foreignEntries != entriesOf(new File(cold.FIELD_CACHE))

		cleanup:
		println("另一代缓存留档：${new File(probeDir(), 'foreign-field-cache.json')}")
	}

	def "②投毒对照：清空或伪造的迁移器缓存不得改变迁移产物"() {
		setup: "先取一份冷参照：缓存不存在时由任务从声明输入算出"
		def reference = runTask("cold")
		def referenceMigrated = lines(new File(reference.TASK_MIGRATED))
		def referenceWithNs = lines(new File(reference.TASK_MIGRATED_WITH_NS))
		def referenceHash = readHash(reference)
		def trueEntries = entriesOf(new File(reference.FIELD_CACHE))

		assert trueEntries.size() > 0: "冷参照没有产出任何字段迁移，本条对照会变成空转"
		assert diff(referenceMigrated, lines(new File(reference.TASK_RAW_MAPPINGS_WITH_NS))).size() > 0:
		"冷参照与原始 mappings 逐行相同，说明它压根没做过迁移"

		when: "声明输入一个字不改，只把共享目录里的两个缓存换成 {} 与伪造条目"
		poisonCaches(reference)
		def poisoned = runTask("report")
		def poisonedMigrated = lines(new File(poisoned.TASK_MIGRATED))
		def poisonedWithNs = lines(new File(poisoned.TASK_MIGRATED_WITH_NS))

		then: "产物必须与冷参照逐行一致、行数一致"
		def migratedDiff = diff(poisonedMigrated, referenceMigrated)
		def withNsDiff = diff(poisonedWithNs, referenceWithNs)
		explain(migratedDiff, new File(poisoned.TASK_MIGRATED), new File(reference.TASK_MIGRATED))
		explain(withNsDiff, new File(poisoned.TASK_MIGRATED_WITH_NS), new File(reference.TASK_MIGRATED_WITH_NS))
		migratedDiff.isEmpty()
		withNsDiff.isEmpty()
		poisonedMigrated.size() == referenceMigrated.size()

		and: "就绪标记也必须回到冷参照的哈希（毒缓存不得参与哈希）"
		println("就绪标记：冷参照=${referenceHash} / 被投毒=${readHash(poisoned)}")
		readHash(poisoned) == referenceHash

		and: "对照非空转：被投毒的内容确实与本代真实内容不同"
		trueEntries != entriesOf(new File(probeDir(), "poison-field-cache.json"))
	}

	def "③活性对照：键一致的缓存确实参与产物与就绪标记的生成"() {
		setup: "取冷参照，并确认任务这次写下了带内容键的缓存"
		def reference = runTask("cold")
		def referenceMigrated = lines(new File(reference.TASK_MIGRATED))
		def referenceWithNs = lines(new File(reference.TASK_MIGRATED_WITH_NS))
		def referenceHash = readHash(reference)
		def trueEntries = entriesOf(new File(reference.FIELD_CACHE))

		expect: "冷参照做了真迁移，且缓存里存着本代的迁移结果"
		trueEntries.size() > 0
		diff(referenceMigrated, lines(new File(reference.TASK_RAW_MAPPINGS_WITH_NS))).size() > 0

		when: "什么都不动，只让任务重跑一次（缓存键与本代输入一致）"
		def warm = runTask("report")
		def warmMigrated = lines(new File(warm.TASK_MIGRATED))
		def warmWithNs = lines(new File(warm.TASK_MIGRATED_WITH_NS))

		then: "暖态产物与就绪标记 == 冷参照（缓存被采信且内容等价）"
		def warmMigratedDiff = diff(warmMigrated, referenceMigrated)
		def warmWithNsDiff = diff(warmWithNs, referenceWithNs)
		explain(warmMigratedDiff, new File(warm.TASK_MIGRATED), new File(reference.TASK_MIGRATED))
		explain(warmWithNsDiff, new File(warm.TASK_MIGRATED_WITH_NS), new File(reference.TASK_MIGRATED_WITH_NS))
		warmMigratedDiff.isEmpty()
		warmWithNsDiff.isEmpty()
		readHash(warm) == referenceHash

		when: "把缓存内容扰动成本代的空结果（内容键保持不动，声明输入也不动），再重跑一次"
		emptyCacheEntries(new File(reference.FIELD_CACHE))
		emptyCacheEntries(new File(reference.METHOD_CACHE))
		def perturbed = runTask("report")
		def perturbedMigrated = lines(new File(perturbed.TASK_MIGRATED))
		def perturbedWithNs = lines(new File(perturbed.TASK_MIGRATED_WITH_NS))

		then: "产物必须**变化**：缓存内容真的流进了产物，比对口径不是空转的"
		def perturbedMigratedDiff = diff(perturbedMigrated, referenceMigrated)
		def perturbedWithNsDiff = diff(perturbedWithNs, referenceWithNs)
		explain(perturbedMigratedDiff, new File(perturbed.TASK_MIGRATED), new File(reference.TASK_MIGRATED))
		explain(perturbedWithNsDiff, new File(perturbed.TASK_MIGRATED_WITH_NS), new File(reference.TASK_MIGRATED_WITH_NS))
		perturbedMigratedDiff.size() + perturbedWithNsDiff.size() > 0

		and: "就绪标记随缓存代次变化（哈希是「迁移器读到了什么」的函数）"
		println("就绪标记：冷参照=${referenceHash} / 缓存被扰动=${readHash(perturbed)}")
		readHash(perturbed) != referenceHash

		when: "恢复共享状态：删掉被扰动的两个缓存，让任务从声明输入冷算一次"
		// 产物与缓存都落在**跨工作树共享**的 forge/mappings 目录里，别的用例会直接读它们：
		// 本用例是唯一一个故意扰动缓存的用例，因此结束时必须把共享目录恢复到「正常构建之后的形态」，
		// 否则后续用例读到的是「没有迁移过的」产物，会以完全无关的原因失败。
		def restored = runTask("cold")
		def restoredMigrated = lines(new File(restored.TASK_MIGRATED))
		def restoredWithNs = lines(new File(restored.TASK_MIGRATED_WITH_NS))

		then: "共享目录不留残骸：恢复后的产物与就绪标记回到冷参照"
		diff(restoredMigrated, referenceMigrated).isEmpty()
		diff(restoredWithNs, referenceWithNs).isEmpty()
		readHash(restored) == referenceHash

		and: "这一轮真的重算了（否则「恢复」可能只是读到了扰动前遗留的内容）"
		diff(restoredMigrated, perturbedMigrated).size() > 0
	}

	/**
	 * 一次「按模式准备共享缓存 → 重跑迁移任务」.
	 *
	 * <p>产物在本次运行前先删掉：任务因此必然执行（up-to-date 判定不会把「不重跑」伪装成「等价」），
	 * 而上游（补丁链、mappings 合并）保持 UP-TO-DATE，故每条用例的真实成本只有被观测的那一步。
	 */
	private Map<String, String> runTask(String mode) {
		def project = sharedFixture()
		new File(project.projectDir, "probe-mode.txt").text = mode

		def result = project.run(task: MIGRATE_TASK, configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def task = result.task(":" + MIGRATE_TASK)
		assert task != null && task.outcome == SUCCESS: "迁移任务本次必须真的执行，实际 outcome=${task?.outcome}"

		def report = parseReport(new File(probeDir(), "run-${mode}.properties"))
		// 每次运行都重写路径报告：产物路径只从任务自己声明的输出读，测试不自己拼路径
		def paths = parseReport(new File(probeDir(), "probe-paths.properties"))
		report.putAll(paths)
		return report
	}

	private File probeDir() {
		return new File(sharedFixture().projectDir, "build/probe")
	}

	/**
	 * 把两个缓存换成「改造前格式的垃圾内容」：外层没有内容键，内容也与本代输入无关.
	 *
	 * <p>形态刻意沿用改造前那种「裸内容」写法（字段表是对象、方法表是 {@code [{left,right}]} 列表），
	 * 因此对改造前的代码是**可解析且会被采信**的——它不会报错，只会静默换一套迁移结果。
	 * 这正是「另一棵树/更旧版本在这个共享目录里留下的东西」会造成的后果，也是本条对照要钉住的点。
	 *
	 * <p>同时留一份到 {@code build/probe}：用例需要断言「被投毒的内容确实与本代真实内容不同」，
	 * 否则整条对照可能是空转的。
	 */
	private void poisonCaches(Map<String, String> report) {
		new File(report.FIELD_CACHE).text = '{}'
		new File(report.METHOD_CACHE).text = '[{"left":"#poison","right":"()V"}]'
		new File(probeDir(), "poison-field-cache.json").text = new File(report.FIELD_CACHE).text
		new File(probeDir(), "poison-method-cache.json").text = new File(report.METHOD_CACHE).text
	}

	/**
	 * 把缓存里的迁移结果清空，**保持内容键不变**：这是「键一致但内容被扰动」的形态，
	 * 用来证明缓存内容真的被消费（产物与就绪标记必须随之变化）。
	 *
	 * <p>兼容两种信封：带 {@code key}/{@code entries} 的新格式，以及改造前的裸内容（那时没有键，
	 * 读到什么用什么）。两种形态下都把「条目」清空。
	 */
	private static void emptyCacheEntries(File cacheFile) {
		def json = new JsonSlurper().parseText(cacheFile.text)

		if (json instanceof Map && json.containsKey('key') && json.containsKey('entries')) {
			def entries = json.entries

			if (entries instanceof Map) {
				cacheFile.text = JsonOutput.toJson([key: json.key, entries: [:]])
			} else {
				cacheFile.text = JsonOutput.toJson([key: json.key, entries: []])
			}

			return
		}

		cacheFile.text = json instanceof Map ? '{}' : '[]'
	}

	/** 取缓存里的「迁移条目」：带键信封取 entries，改造前的裸内容取它自己. */
	private static Map<Object, Object> entriesOf(File cacheFile) {
		if (!cacheFile.exists()) {
			return [:]
		}

		def json = new JsonSlurper().parseText(cacheFile.text)
		def entries = json instanceof Map && json.containsKey('entries') ? json.entries : json
		def result = [:]

		if (entries instanceof Map) {
			result.putAll(entries)
		} else if (entries instanceof List) {
			entries.eachWithIndex { entry, index -> result[index] = entry.toString() }
		}

		return result
	}

	/** 读就绪标记的内容（迁移器返回值算出的哈希，纯文本）. */
	private static String readHash(Map<String, String> report) {
		def marker = new File(report.TASK_READY_MARKER)
		assert marker.exists(): "就绪标记不存在: ${marker}"
		return marker.text.trim()
	}

	/** 构造 Forge 1.20.1 夹具（与 ForgeMigratedMappingsTaskEquivalenceTest 同一套替换）. */
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

	/** 夹具工程（探针只追加一次）：三条对照共用，避免把补丁链跑三遍. */
	private GradleProject sharedFixture() {
		if (fixture == null) {
			fixture = forgeProject()
			fixture.buildGradle << probeScript()
		}

		return fixture
	}

	/**
	 * 追加到夹具 build.gradle 的探针.
	 *
	 * <p>它在配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）读 {@code probe-mode.txt} 决定本次
	 * 对共享缓存做什么，并把「任务自己声明的输入输出路径」写成报告：路径由任务给出，测试不自己拼，
	 * 避免「比了别的文件」而假通过。
	 *
	 * <ul>
	 *   <li>{@code report}：什么都不动（用于「缓存应当被采信」与投毒后的重跑）。</li>
	 *   <li>{@code cold}：删掉两个 JSON，让本次只能从声明输入冷算。</li>
	 *   <li>{@code foreign}：调用**真实迁移器**、以另一个 jar（Forge universal 而不是 patched 中间产物）
	 *       为输入，把结果写进任务同一个共享缓存文件——这就是共享目录里「另一代内容」的成因，
	 *       也正是改造前会被无条件采信的那一份。</li>
	 * </ul>
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def probeDir = new File(project.buildDir, 'probe')
			probeDir.mkdirs()
			def modeFile = file('probe-mode.txt')
			def mode = modeFile.exists() ? modeFile.text.trim() : 'report'
			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def mappingConfiguration = extension.mappingConfiguration
			def forgeCache = dev.architectury.loom.forge.dependency.ForgeProvider.getForgeCache(project)
			java.nio.file.Files.createDirectories(forgeCache)
			def migrateTask = project.tasks.findByName('manipulateForgeMappings')
			// 本用例研究的是**迁移器缓存的身份**，与 Gradle 的 up-to-date 判定无关：
			// 关掉本任务的输出判定，让它每次都必须真的执行，而上游（补丁链、mappings 合并）保持 UP-TO-DATE。
			migrateTask.outputs.upToDateWhen { false }
			def fieldCache = forgeCache.resolve('migrated-fields.json')
			def methodCache = forgeCache.resolve('method-inheritance-migrator.json')

			def lines = []
			lines << 'MIGRATE_TASK_FOUND=' + (migrateTask != null)
			lines << 'TASK_MIGRATED=' + migrateTask.migratedMappings.get().asFile.absolutePath
			lines << 'TASK_MIGRATED_WITH_NS=' + migrateTask.migratedMappingsWithNs.get().asFile.absolutePath
			lines << 'TASK_READY_MARKER=' + migrateTask.readyMarker.get().asFile.absolutePath
			lines << 'TASK_RAW_MAPPINGS_WITH_NS=' + migrateTask.rawMappingsWithNs.get().asFile.absolutePath
			lines << 'TASK_PATCHED_INTERMEDIATE=' + migrateTask.patchedIntermediateJar.get().asFile.absolutePath
			lines << 'FORGE_CACHE=' + forgeCache.toAbsolutePath().toString()
			lines << 'FIELD_CACHE=' + fieldCache.toAbsolutePath().toString()
			lines << 'METHOD_CACHE=' + methodCache.toAbsolutePath().toString()
			def text = lines.join(System.lineSeparator()) + System.lineSeparator()
			new File(probeDir, 'probe-paths.properties').text = text

			if (mode == 'foreign') {
				def info = { String message -> project.logger.info(message) }
				def foreignInputs = new dev.architectury.loom.forge.MappingsMigrator.Inputs(
						forgeCache,
						mappingConfiguration.mappingsWorkingDir().resolve('mappings-srg.tiny'),
						extension.forgeUniversalProvider.forge.toPath(),
						extension.forgeUniversalProvider.forge.toPath(),
						extension.forgeUserdevProvider.userdevJar.toPath(),
						true,
						false,
						true,
						info)
				new dev.architectury.loom.forge.FieldMappingsMigrator().setup(foreignInputs)
				new dev.architectury.loom.forge.MethodInheritanceMappingsMigrator().setup(foreignInputs)
				new File(probeDir, 'foreign-field-cache.json').text = fieldCache.text
				new File(probeDir, 'foreign-method-cache.json').text = methodCache.text
			} else if (mode == 'cold') {
				java.nio.file.Files.deleteIfExists(fieldCache)
				java.nio.file.Files.deleteIfExists(methodCache)
			}

			new File(probeDir, 'run-' + mode + '.properties').text = text
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

	/** 逐行 SHA-256：mappings 是纯文本 tiny，没有 zip entry，逐行摘要才能指出差在哪一行. */
	private static Map<Integer, String> lines(File mappings) {
		final Map<Integer, String> result = [:]
		int index = 0

		mappings.eachLine { line ->
			def digest = MessageDigest.getInstance("SHA-256")
			digest.update(line.getBytes("UTF-8"))
			result[index++] = digest.digest().encodeHex().toString()
		}

		return result
	}

	private static List<Integer> diff(Map<Integer, String> actual, Map<Integer, String> expected) {
		return actual.findResults { index, hash -> hash == expected[index] ? null : index }
	}

	/**
	 * 差异诊断：报出行数、前几个差异行号与两侧的原文.
	 *
	 * <p>只报「有多少行不同」不足以定位；mappings 的差异往往是一整片（迁移器改写了几百行），
	 * 因此把差异**区间**也打出来——区间边界能直接指向是哪一步（字段描述符迁移 / 方法继承删除）出了偏差。
	 */
	private static void explain(List<Integer> diffIndices, File actualFile, File expectedFile) {
		if (diffIndices.isEmpty()) {
			return
		}

		def actualLines = actualFile.readLines()
		def expectedLines = expectedFile.readLines()
		println("差异：${diffIndices.size()} 行；实际 ${actualLines.size()} 行 / 期望 ${expectedLines.size()} 行")
		println("差异行号（前 20）：${diffIndices.take(20)}")

		diffIndices.take(3).each { index ->
			println("  行 ${index}: 实际=${index < actualLines.size() ? actualLines[index] : '<越界>'}")
			println("  行 ${index}: 期望=${index < expectedLines.size() ? expectedLines[index] : '<越界>'}")
		}
	}
}
