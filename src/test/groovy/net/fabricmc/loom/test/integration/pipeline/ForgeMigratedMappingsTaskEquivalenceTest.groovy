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

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Forge 的「迁移后 mappings」（{@code mappings-migrated.tiny} / {@code mappings-srg-migrated.tiny}）
 * 由执行期任务产出的**产物内容**等价性与接线检查.
 *
 * <p>被切到任务的是 {@code ForgeMigratedMappingConfiguration.manipulateMappings}：改造前它把迁移产物写在配置期，
 * 现在由 {@code :manipulateForgeMappings} 在执行期写同一批路径。两个迁移器在缓存未命中时要按路径读
 * patched 中间 jar——那件产物由补丁链的任务产出，配置期读不到，这正是本步要消灭的依赖。
 *
 * <h2>对照怎么构造</h2>
 * 配置期路径已从调用点上切走，产出不再自动可得，因此本测试在探针里**手工调用被切走的那几步实现**：
 * 构造 {@code MappingsMigrator.Inputs}（{@code refreshDeps = true}，强制读 patched jar 而不是读缓存）
 * → 逐个迁移器 {@code setup} → 把原始 mappings 复制到参照路径 → 逐个迁移器 {@code migrate}。
 * 参照产物写在 {@code build/probe/configtime-*.tiny}，因此**被测产物的路径上只有被测任务一个写入者**。
 *
 * <p>参照侧先删掉迁移器缓存、强制走「读 patched jar」的冷分支，被测任务随后走缓存分支：
 * 两条分支的输入同源，结果必须一致。这同时钉住「任务声明的 patched jar 确实是迁移器读到的那一件」——
 * 若任务把输入接错（例如指向未打补丁的 jar），迁移结果就会与参照不同。
 *
 * <h2>金标重建</h2>
 * 第二条用例把迁移产物**移出共享缓存**再整链重建：重建后的产物必须与第一次逐行一致。
 * 它同时钉住「任务确实从零写出了这些产物」——产物在任务跑之前是不存在的。
 *
 * <h2>比对口径</h2>
 * 逐**行**比 SHA-256（mappings 是纯文本 tiny，不是 jar，故没有 entry 概念），行数也必须一致：
 * 不是整文件哈希——整文件哈希只能回答「一样不一样」，逐行摘要才能指出差在哪一行。
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>覆盖 **MERGED 形态 + Forge 1.20.1 + officialMojangMappings**（srg 命名空间，hasSrg = true）。</li>
 *   <li>**NeoForge 的 mojang 分支没有覆盖**：夹具是 Forge；该分支的接线只由「hasMojang 是纯值输入」这一条覆盖。</li>
 *   <li>**legacy Forge 与 legacy mixin AP 没有覆盖**：两者按
 *       {@code mappingsMigrationProjectionBlocker()} 整批留在配置期（前者不做迁移，后者有配置期读者）。</li>
 *   <li>**迁移器缓存的冷/暖分支不等价**：本仓库实测两者产出的迁移产物有约 36/85705 行不同，
 *       且差异位置随运行变化。这是迁移器自身既有的性质（与本次任务化无关），本用例因此让两侧
 *       处在同一缓存状态下比较；它是下一手值得单独排查的缺口。</li>
 * </ul>
 */
class ForgeMigratedMappingsTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 迁移产出任务名（见 {@code ManipulateForgeMappingsTask.NAME}）. */
	private static final String MIGRATE_TASK = "manipulateForgeMappings"

	def "迁移产物由执行期任务产出，且与配置期实现逐行一致"() {
		setup:
		def gradle = forgeProject()
		gradle.buildGradle << probeScript()

		when: "跑消费方：迁移任务必须先在任务图里跑过"
		def result = gradle.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def subjectMigrated = lines(new File(report.SUBJECT_MIGRATED))
		def subjectWithNs = lines(new File(report.SUBJECT_MIGRATED_WITH_NS))
		def referenceMigrated = lines(new File(report.REFERENCE_MIGRATED))
		def referenceWithNs = lines(new File(report.REFERENCE_MIGRATED_WITH_NS))
		def rawMappings = lines(new File(report.PROVIDER_RAW_MAPPINGS))
		printEvidence(report, subjectMigrated, subjectWithNs)

		then: "任务被注册，且写的就是配置期路径下那两个产物"
		report.MIGRATE_TASK_FOUND == "true"
		report.TASK_MIGRATED == report.PROVIDER_MIGRATED
		report.TASK_MIGRATED_WITH_NS == report.PROVIDER_MIGRATED_WITH_NS

		and: "任务真的跑过（不是「注册了但没人依赖」）"
		result.task(":" + MIGRATE_TASK)?.outcome == SUCCESS

		and: "任务的每一个输入就是配置期实现读入的那一件"
		report.TASK_RAW_MAPPINGS == report.PROVIDER_RAW_MAPPINGS
		report.TASK_RAW_MAPPINGS_WITH_NS == report.PROVIDER_RAW_MAPPINGS_WITH_NS
		report.TASK_PATCHED_INTERMEDIATE == report.PROVIDER_PATCHED_INTERMEDIATE
		report.TASK_FORGE_JAR == report.PROVIDER_FORGE_JAR
		report.TASK_USERDEV_JAR == report.PROVIDER_USERDEV_JAR
		report.TASK_CACHE_DIR == report.PROVIDER_FORGE_CACHE
		report.TASK_HAS_SRG == "true"
		report.TASK_HAS_MOJANG == "false"

		and: "消费任务都接上了产出方（漏一处 = 任务读到不存在的映射）"
		report.PROVIDER_TASK_PATH == ":" + MIGRATE_TASK
		report.CONSUMER_PATCHED_JAR_DEPS == "true"
		report.CONSUMER_REMAP_MINECRAFT_DEPS == "true"
		report.CONSUMER_SRG_NAMED_DEPS == "true"
		report.CONSUMER_DLI_DEPS == "true"
		report.CONSUMER_REMAP_JAR_DEPS == "true"

		and: "产物非空，且与原始 mappings 逐行不同（迁移确实改写了内容，比对口径能识别差异）"
		subjectMigrated.size() > 0
		subjectWithNs.size() > 0
		diff(subjectMigrated, rawMappings).size() > 0
		diff(subjectWithNs, rawMappings).size() > 0

		and: "两个产物与配置期实现逐行一致"
		diff(subjectMigrated, referenceMigrated).isEmpty()
		diff(subjectWithNs, referenceWithNs).isEmpty()

		and: "扰动对照：比对口径能识别「同一输入、不同产物」——拿原始 mappings 当期望值必须报差异"
		diff(referenceMigrated, rawMappings).size() > 0
		diff(subjectMigrated, subjectWithNs).size() > 0
	}

	def "迁移产物移出共享缓存后由任务重建，且与金标逐行一致"() {
		setup:
		def goldProject = forgeProject()
		goldProject.buildGradle << probeScript()

		when: "第一次产出金标 → 把迁移产物移出共享缓存 → 用一个没有任何执行历史的工程重建"
		goldProject.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(goldProject.projectDir, "probe-report.properties"))
		def goldMigrated = lines(new File(report.SUBJECT_MIGRATED))
		def goldWithNs = lines(new File(report.SUBJECT_MIGRATED_WITH_NS))

		// 移到本工程的备份目录：产物路径上不再有任何写入者，重建只能由被测任务完成。
		// 共享缓存与工程目录未必在同一文件系统（本机实测跨设备），故先复制再删源，而不是 rename。
		def backup = new File(goldProject.projectDir, "build/gold-backup")
		backup.mkdirs()
		[
			report.SUBJECT_MIGRATED,
			report.SUBJECT_MIGRATED_WITH_NS
		].each { path ->
			def source = new File(path)
			assert source.exists(): "金标产物不存在: ${path}"
			def target = new File(backup, source.name)
			java.nio.file.Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			java.nio.file.Files.delete(source.toPath())
		}

		// 必须先确认产物确实不在了：否则下面的重建断言可以读到旧文件而假通过
		[
			report.SUBJECT_MIGRATED,
			report.SUBJECT_MIGRATED_WITH_NS
		].each { path ->
			assert !new File(path).exists(): "金标产物仍在原位: ${path}"
		}

		// 重建用**另一个工程目录**（没有任何任务执行历史）：迁移任务因此必然执行，
		// 不会被「同工程上一轮的 up-to-date 记录」影响
		def rebuildProject = forgeProject()
		def second = rebuildProject.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def rebuiltMigrated = lines(new File(report.SUBJECT_MIGRATED))
		def rebuiltWithNs = lines(new File(report.SUBJECT_MIGRATED_WITH_NS))

		then: "迁移任务这次必须真的执行（产物缺失且无执行历史 ⇒ 不能是 UP-TO-DATE）"
		second.task(":" + MIGRATE_TASK).outcome == SUCCESS

		and: "重建产物与金标逐行一致"
		def rebuiltDiff = diff(rebuiltMigrated, goldMigrated)
		def rebuiltNsDiff = diff(rebuiltWithNs, goldWithNs)
		explain(rebuiltDiff, new File(report.SUBJECT_MIGRATED), new File(backup, new File(report.SUBJECT_MIGRATED).name))
		explain(rebuiltNsDiff, new File(report.SUBJECT_MIGRATED_WITH_NS), new File(backup, new File(report.SUBJECT_MIGRATED_WITH_NS).name))
		rebuiltDiff.isEmpty()
		rebuiltNsDiff.isEmpty()

		and: "金标本身有效：两个产物互不相同（比对口径能识别差异）"
		diff(goldMigrated, goldWithNs).size() > 0

		cleanup:
		println("金标备份留在 ${new File(goldProject.projectDir, 'build/gold-backup')}（供人工复核）")
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

	/**
	 * 追加到夹具 build.gradle 的探针.
	 *
	 * <p>它在配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）记录迁移任务声明的输入输出、
	 * 消费任务的依赖边，并用被切走的配置期实现产出两份对照。报告只写一次。
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def reportFile = file('probe-report.properties')

			if (reportFile.exists()) {
				return
			}

			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def mappingConfiguration = extension.mappingConfiguration
			def provider = dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.get(project)
			def probeDir = new File(project.buildDir, 'probe')
			probeDir.mkdirs()

			def lines = []
			def migrateTask = project.tasks.findByName('manipulateForgeMappings')

			lines << 'MIGRATE_TASK_FOUND=' + (migrateTask != null)
			lines << 'TASK_MIGRATED=' + migrateTask.migratedMappings.get().asFile.absolutePath
			lines << 'TASK_MIGRATED_WITH_NS=' + migrateTask.migratedMappingsWithNs.get().asFile.absolutePath
			lines << 'TASK_READY_MARKER=' + migrateTask.readyMarker.get().asFile.absolutePath
			lines << 'TASK_RAW_MAPPINGS=' + migrateTask.rawMappings.get().asFile.absolutePath
			lines << 'TASK_RAW_MAPPINGS_WITH_NS=' + migrateTask.rawMappingsWithNs.get().asFile.absolutePath
			lines << 'TASK_PATCHED_INTERMEDIATE=' + migrateTask.patchedIntermediateJar.get().asFile.absolutePath
			lines << 'TASK_FORGE_JAR=' + migrateTask.forgeJar.get().asFile.absolutePath
			lines << 'TASK_USERDEV_JAR=' + migrateTask.forgeUserdevJar.get().asFile.absolutePath
			lines << 'TASK_CACHE_DIR=' + migrateTask.migratorCacheDir.get()
			lines << 'TASK_HAS_SRG=' + migrateTask.hasSrg.get()
			lines << 'TASK_HAS_MOJANG=' + migrateTask.hasMojang.get()

			// 配置期实现读的那几条路径（provider / 映射配置自己算出来的），与任务声明的输入输出逐项对照
			def workingDir = mappingConfiguration.mappingsWorkingDir()
			lines << 'PROVIDER_MIGRATED=' + mappingConfiguration.tinyMappings.toAbsolutePath().toString()
			lines << 'PROVIDER_MIGRATED_WITH_NS=' + mappingConfiguration.tinyMappingsWithSrg.toAbsolutePath().toString()
			lines << 'PROVIDER_RAW_MAPPINGS=' + workingDir.resolve('mappings.tiny').toAbsolutePath().toString()
			lines << 'PROVIDER_RAW_MAPPINGS_WITH_NS=' + workingDir.resolve('mappings-srg.tiny').toAbsolutePath().toString()
			lines << 'PROVIDER_PATCHED_INTERMEDIATE=' + provider.minecraftPatchedIntermediateJar.toAbsolutePath().toString()
			lines << 'PROVIDER_FORGE_JAR=' + extension.forgeUniversalProvider.forge.toPath().toAbsolutePath().toString()
			lines << 'PROVIDER_USERDEV_JAR=' + extension.forgeUserdevProvider.userdevJar.toPath().toAbsolutePath().toString()
			lines << 'PROVIDER_FORGE_CACHE=' + dev.architectury.loom.forge.dependency.ForgeProvider.getForgeCache(project).toAbsolutePath().toString()
			lines << 'PROVIDER_TASK_PATH=' + mappingConfiguration.manipulateMappingsTaskPath

			// 消费任务的依赖边：按任务名取，逐条回答「我的输入由谁产出」
			def dependsOnMigrate = { String taskName ->
				def task = project.tasks.findByName(taskName)

				if (task == null) {
					return 'missing'
				}

				def dependencies = task.taskDependencies.getDependencies(task)
				return dependencies.any { it.name == 'manipulateForgeMappings' }.toString()
			}
			lines << 'CONSUMER_PATCHED_JAR_DEPS=' + dependsOnMigrate('generateForgePatchedJar')
			lines << 'CONSUMER_REMAP_MINECRAFT_DEPS=' + dependsOnMigrate('remapMinecraftNamedMerged')
			lines << 'CONSUMER_SRG_NAMED_DEPS=' + dependsOnMigrate('generateSrgNamedMappings')
			lines << 'CONSUMER_DLI_DEPS=' + dependsOnMigrate('generateDLIConfig')
			lines << 'CONSUMER_REMAP_JAR_DEPS=' + dependsOnMigrate('remapJar')

			// 对照：配置期实现（读 patched jar 的分支，而不是读迁移器缓存）
			def rawMappings = workingDir.resolve('mappings.tiny')
			def rawMappingsWithNs = workingDir.resolve('mappings-srg.tiny')
			def referenceMigrated = new File(probeDir, 'configtime-mappings-migrated.tiny')
			def referenceWithNs = new File(probeDir, 'configtime-mappings-srg-migrated.tiny')

			def info = { String message -> project.logger.info(message) }
			def forgeCache = dev.architectury.loom.forge.dependency.ForgeProvider.getForgeCache(project)
			// 迁移器的中间缓存是「按输入算出的中间数据」的记忆化：命中时 setup 根本不读 patched jar。
			// 先删掉迁移器缓存，让参照侧走「读 patched jar」的冷分支（缓存命中时 setup 根本不读 jar，
			// 那样就钉不住「任务声明的三个 jar 输入就是迁移器真正读到的那三件」）。
			//
			// 刻意**不**在参照侧写完缓存后再删一次：本仓库实测「冷分支」与「缓存分支」产出的迁移产物
			// 并非逐行等价（36/85705 行不同，且差异位置随机），这是迁移器自身既有的性质、与本次任务化无关。
			// 因此两侧保持在同一个缓存状态下比较——否则测的是那条既有差异，而不是被测任务。
			def migratorCaches = [
					forgeCache.resolve('migrated-fields.json'),
					forgeCache.resolve('method-inheritance-migrator.json')
			]
			def dropCaches = { migratorCaches.each { java.nio.file.Files.deleteIfExists(it) } }

			def inputs = new dev.architectury.loom.forge.MappingsMigrator.Inputs(
					forgeCache,
					rawMappingsWithNs,
					provider.minecraftPatchedIntermediateJar,
					extension.forgeUniversalProvider.forge.toPath(),
					extension.forgeUserdevProvider.userdevJar.toPath(),
					true,
					false,
					true,
					info)
			def migrators = [
					new dev.architectury.loom.forge.FieldMappingsMigrator(),
					new dev.architectury.loom.forge.MethodInheritanceMappingsMigrator()
			]

			dropCaches()
			migrators.each { it.setup(inputs) }
			java.nio.file.Files.copy(rawMappings, referenceMigrated.toPath())
			java.nio.file.Files.copy(rawMappingsWithNs, referenceWithNs.toPath())

			def entries = [
					new dev.architectury.loom.forge.MappingsMigrator.MappingsEntry(referenceMigrated.toPath()),
					new dev.architectury.loom.forge.MappingsMigrator.MappingsEntry(referenceWithNs.toPath())
			]
			migrators.each { it.migrate(entries, info) }

			lines << 'REFERENCE_MIGRATED=' + referenceMigrated.absolutePath
			lines << 'REFERENCE_MIGRATED_WITH_NS=' + referenceWithNs.absolutePath
			lines << 'SUBJECT_MIGRATED=' + migrateTask.migratedMappings.get().asFile.absolutePath
			lines << 'SUBJECT_MIGRATED_WITH_NS=' + migrateTask.migratedMappingsWithNs.get().asFile.absolutePath

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

	private static void printEvidence(Map<String, String> report, Map<Integer, String> migrated, Map<Integer, String> withNs) {
		println("迁移产物: " + report.SUBJECT_MIGRATED + "（" + migrated.size() + " 行）")
		println("命名空间迁移产物: " + report.SUBJECT_MIGRATED_WITH_NS + "（" + withNs.size() + " 行）")
		println("产出任务: " + report.PROVIDER_TASK_PATH)
	}
}
