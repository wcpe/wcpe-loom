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

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Forge 的 patched 三级产物（pre-patch / intermediate / at-patched）由执行期任务产出的
 * **产物内容**等价性与接线检查.
 *
 * <p>被切到任务的是 {@code MinecraftPatchedProvider.provide()}：改造前它把三级产物写在配置期，
 * 现在由 {@code :generateForgePrePatchJar} → {@code :generateForgePatchedIntermediateJar}
 * → {@code :accessTransformForgeJar} 在执行期逐级写同一批路径。
 *
 * <h2>对照怎么构造</h2>
 * 配置期路径已从调用点上切走，产出不再自动可得，因此本测试在探针里**手工调用被切走的那几处实现**：
 * <ul>
 *   <li>pre-patch：{@code provider.createPrePatchJarMcpOptions(tempDir)} + {@code runMcpExecutor(...)}；</li>
 *   <li>intermediate：{@code MinecraftPatchedProvider.producePatchedIntermediateForTask(...)}；</li>
 *   <li>at-patched：{@code provider.createAccessTransformerOptions(tempFiles)} + {@code runAccessTransformer(...)}。</li>
 * </ul>
 * 参照产物写在 {@code build/probe/configtime-*.jar}，因此**被测产物的路径上只有被测任务一个写入者**——
 * 不会出现「配置期先写一份、任务再覆盖」这类别名，否则「两者一致」可以由「读到了同一份文件」伪造出来。
 *
 * <p>该实现与任务共用，故「两者一致」在实现层面是构造性的；本测试真正要钉住的是**接线**：
 * 每一级任务读的是不是上一级的产物、写的是不是那条路径、最终 jar 的任务读的是不是 AT 任务的产物。
 *
 * <h2>金标重建</h2>
 * 第二条用例把三级产物**移出共享缓存**再整链重建：重建后的产物必须与第一次逐 entry 一致。
 * 它同时钉住「任务确实从零写出了这些产物」——产物在任务跑之前是不存在的，测试断言的重建结果是任务写的。
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义。任何一条 entry 的字节不同即判为差异。
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>覆盖 **MERGED 形态 + Forge 1.20.1 + officialMojangMappings**（MCP rename 分支）。</li>
 *   <li>**NeoForge installer tools 分支没有覆盖**：它只在 NeoForge {@code [21.10.57-beta, 21.10.64)}
 *       这一小段版本上生效，夹具里没有这种版本；该分支的接线只由本测试的「分支是纯值」这一条覆盖。</li>
 *   <li>**unobfuscated / legacy 没有覆盖**：两者按 {@code provideProjectionBlocker()} 整批留在配置期。</li>
 *   <li>**迁移器缓存冷启动没有覆盖**：该情形同样整批回退（见 {@code provideProjectionBlocker()} 的第二条），
 *       本测试跑在暖缓存上。</li>
 * </ul>
 */
class ForgePatchedChainTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 三级产出任务名（见对应 Task 类的 NAME 常量）. */
	private static final String PRE_PATCH_TASK = "generateForgePrePatchJar"
	private static final String INTERMEDIATE_TASK = "generateForgePatchedIntermediateJar"
	private static final String AT_TASK = "accessTransformForgeJar"

	def "三级 patched 产物由执行期任务产出，且与配置期实现逐 entry 一致"() {
		setup:
		def gradle = forgeProject()
		gradle.buildGradle << probeScript()

		when: "跑消费方：三级产出任务必须先在任务图里跑过"
		// 探针在配置期调用被切走的实现（它需要 Gradle 项目模型），故显式关掉配置缓存；
		// 同时关掉构建缓存：本用例要断言被测任务**真的执行了**，而不是从缓存恢复了别的产物
		def result = gradle.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def subjectPrePatch = entries(new File(report.SUBJECT_PRE_PATCH))
		def subjectIntermediate = entries(new File(report.SUBJECT_INTERMEDIATE))
		def subjectAtPatched = entries(new File(report.SUBJECT_AT_PATCHED))
		def referencePrePatch = entries(new File(report.REFERENCE_PRE_PATCH))
		def referenceIntermediate = entries(new File(report.REFERENCE_INTERMEDIATE))
		def referenceAtPatched = entries(new File(report.REFERENCE_AT_PATCHED))
		printEvidence(report, subjectPrePatch, subjectIntermediate, subjectAtPatched)

		then: "三级产出任务都被注册，且写的就是配置期路径下那三个产物"
		report.PRE_PATCH_TASK_FOUND == "true"
		report.INTERMEDIATE_TASK_FOUND == "true"
		report.AT_TASK_FOUND == "true"
		report.PRE_PATCH_OUTPUT == report.PROVIDER_PRE_PATCH
		report.INTERMEDIATE_OUTPUT == report.PROVIDER_INTERMEDIATE
		report.AT_OUTPUT == report.PROVIDER_AT_PATCHED

		and: "三级任务真的跑过（不是「注册了但没人依赖」）"
		result.task(":" + PRE_PATCH_TASK)?.outcome in [SUCCESS, UP_TO_DATE]
		result.task(":" + INTERMEDIATE_TASK)?.outcome in [SUCCESS, UP_TO_DATE]
		result.task(":" + AT_TASK)?.outcome in [SUCCESS, UP_TO_DATE]

		and: "链是接上的：每一级的输入就是上一级的产物"
		report.INTERMEDIATE_INPUT == report.PRE_PATCH_OUTPUT
		report.AT_INPUT == report.INTERMEDIATE_OUTPUT

		and: "最终 jar 的任务读的就是 AT 任务的产物（末端接线）"
		report.FINAL_JAR_TASK_AT_PATCHED_INPUT == report.AT_OUTPUT

		and: "pre-patch 任务的分支与补丁输入取自配置期的同一处判据"
		report.PRE_PATCH_BRANCH == "mcp-rename"
		report.INTERMEDIATE_PATCHES == report.JOINED_PATCHES

		and: "产物非空，且每一级都不是它自己的输入（反向排除「复制而非加工」）"
		subjectPrePatch.size() > 0
		subjectIntermediate.size() > 0
		subjectAtPatched.size() > 0
		diff(subjectIntermediate, subjectPrePatch).size() > 0
		diff(subjectAtPatched, subjectIntermediate).size() > 0

		and: "三级产物都与配置期实现逐 entry 一致"
		diff(subjectPrePatch, referencePrePatch).isEmpty()
		diff(subjectIntermediate, referenceIntermediate).isEmpty()
		diff(subjectAtPatched, referenceAtPatched).isEmpty()

		and: "扰动对照：比对口径能识别「名字相同、字节不同」——拿另一级的产物当期望值必须报差异"
		diff(subjectPrePatch, subjectIntermediate).size() > 0
		diff(subjectIntermediate, subjectAtPatched).size() > 0
	}

	def "三级产物移出共享缓存后由任务整链重建，且与金标逐 entry 一致"() {
		setup:
		def goldProject = forgeProject()
		goldProject.buildGradle << probeScript()

		when: "第一次产出金标 → 把三级产物移出共享缓存 → 用一个没有任何执行历史的工程整链重建"
		goldProject.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(goldProject.projectDir, "probe-report.properties"))
		def goldPrePatch = entries(new File(report.SUBJECT_PRE_PATCH))
		def goldIntermediate = entries(new File(report.SUBJECT_INTERMEDIATE))
		def goldAtPatched = entries(new File(report.SUBJECT_AT_PATCHED))

		// 移到本工程的备份目录：产物路径上不再有任何写入者，重建只能由被测任务完成。
		// 共享缓存与工程目录未必在同一文件系统（本机实测跨设备），故先复制再删源，而不是 rename。
		def backup = new File(goldProject.projectDir, "build/gold-backup")
		backup.mkdirs()
		def moved = [:]
		[
			report.SUBJECT_PRE_PATCH,
			report.SUBJECT_INTERMEDIATE,
			report.SUBJECT_AT_PATCHED
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

		// 重建用**另一个工程目录**（没有任何任务执行历史）：三级任务因此必然执行，
		// 不会被「同工程上一轮的 up-to-date 记录」影响——那会让「产物缺失却报 UP-TO-DATE」掩盖真实行为
		def rebuildProject = forgeProject()
		def second = rebuildProject.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def rebuiltPrePatch = entries(new File(report.SUBJECT_PRE_PATCH))
		def rebuiltIntermediate = entries(new File(report.SUBJECT_INTERMEDIATE))
		def rebuiltAtPatched = entries(new File(report.SUBJECT_AT_PATCHED))

		then: "三级任务这次都必须真的执行（产物缺失且无执行历史 ⇒ 不能是 UP-TO-DATE）"
		second.task(":" + PRE_PATCH_TASK).outcome == SUCCESS
		second.task(":" + INTERMEDIATE_TASK).outcome == SUCCESS
		second.task(":" + AT_TASK).outcome == SUCCESS

		and: "重建产物与金标逐 entry 一致"
		diff(rebuiltPrePatch, goldPrePatch).isEmpty()
		diff(rebuiltIntermediate, goldIntermediate).isEmpty()
		diff(rebuiltAtPatched, goldAtPatched).isEmpty()

		and: "金标本身有效：三级产物互不相同（比对口径能识别差异）"
		diff(goldPrePatch, goldIntermediate).size() > 0
		diff(goldIntermediate, goldAtPatched).size() > 0

		cleanup:
		println("金标备份留在 ${new File(goldProject.projectDir, 'build/gold-backup')}（供人工复核）")
	}

	/** 构造 Forge 1.20.1 夹具（与 ForgePatchedJarTaskEquivalenceTest 同一套替换）. */
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
	 * <p>它在配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）记录三级任务声明的输入输出，
	 * 并用被切走的配置期实现产出三份对照。报告只写一次：第二条用例的第二次构建不需要再产出对照，
	 * 否则整链会多跑一遍（MCP rename 步是分钟级的）。
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def reportFile = file('probe-report.properties')

			if (reportFile.exists()) {
				return
			}

			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def provider = dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.get(project)
			def probeDir = new File(project.buildDir, 'probe')
			probeDir.mkdirs()

			def lines = []
			def prePatchTask = project.tasks.findByName('generateForgePrePatchJar')
			def intermediateTask = project.tasks.findByName('generateForgePatchedIntermediateJar')
			def atTask = project.tasks.findByName('accessTransformForgeJar')
			def finalTask = project.tasks.findByName('generateForgePatchedJar')

			lines << 'PRE_PATCH_TASK_FOUND=' + (prePatchTask != null)
			lines << 'INTERMEDIATE_TASK_FOUND=' + (intermediateTask != null)
			lines << 'AT_TASK_FOUND=' + (atTask != null)
			lines << 'PRE_PATCH_OUTPUT=' + prePatchTask.outputJar.get().asFile.absolutePath
			lines << 'INTERMEDIATE_OUTPUT=' + intermediateTask.outputJar.get().asFile.absolutePath
			lines << 'AT_OUTPUT=' + atTask.outputJar.get().asFile.absolutePath
			lines << 'PRE_PATCH_BRANCH=' + prePatchTask.branch.get()
			lines << 'INTERMEDIATE_INPUT=' + intermediateTask.prePatchJar.get().asFile.absolutePath
			lines << 'INTERMEDIATE_PATCHES=' + intermediateTask.patches.get().asFile.absolutePath
			lines << 'INTERMEDIATE_FIX_PARAMS=' + intermediateTask.fixParameterAnnotations.get()
			lines << 'AT_INPUT=' + atTask.patchedIntermediateJar.get().asFile.absolutePath
			lines << 'FINAL_JAR_TASK_AT_PATCHED_INPUT=' + finalTask.atPatchedJar.get().asFile.absolutePath
			lines << 'JOINED_PATCHES=' + extension.forgeUserdevProvider.joinedPatches.toAbsolutePath().toString()

			// 配置期实现读的那三条路径（provider 自己算出来的），与任务声明的输入输出逐项对照
			lines << 'PROVIDER_PRE_PATCH=' + provider.minecraftIntermediateJar.toAbsolutePath().toString()
			lines << 'PROVIDER_INTERMEDIATE=' + provider.minecraftPatchedIntermediateJar.toAbsolutePath().toString()
			lines << 'PROVIDER_AT_PATCHED=' + provider.minecraftPatchedIntermediateAtJar.toAbsolutePath().toString()

			def lifecycle = { String message -> project.logger.lifecycle(message) }
			def info = { String message -> project.logger.info(message) }

			// 对照①：pre-patch jar（配置期实现：MCP rename 步，工作目录用一次性临时目录）
			def tempFiles = new dev.architectury.loom.util.TempFiles()
			def serviceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
			def referencePrePatch = new File(probeDir, 'configtime-pre-patch.jar')
			provider.runMcpExecutor(provider.createPrePatchJarMcpOptions(tempFiles), referencePrePatch.toPath(), serviceFactory)

			// 对照②：intermediate jar（配置期实现：binpatcher + 补类 + 删参数名）
			def referenceIntermediate = new File(probeDir, 'configtime-intermediate.jar')
			dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.producePatchedIntermediateForTask(
					provider.minecraftIntermediateJar,
					referenceIntermediate.toPath(),
					extension.forgeUserdevProvider.joinedPatches,
					provider.createBinpatcherTool(),
					intermediateTask.fixParameterAnnotations.get(),
					lifecycle,
					info)

			// 对照③：at-patched jar（配置期实现：AT 工具）
			def referenceAtPatched = new File(probeDir, 'configtime-at-patched.jar')
			def atTempFiles = new dev.architectury.loom.util.TempFiles()
			provider.runAccessTransformer(provider.createAccessTransformerOptions(atTempFiles),
					provider.minecraftPatchedIntermediateJar, referenceAtPatched.toPath(), serviceFactory)

			atTempFiles.close()
			tempFiles.close()
			serviceFactory.close()

			lines << 'REFERENCE_PRE_PATCH=' + referencePrePatch.absolutePath
			lines << 'REFERENCE_INTERMEDIATE=' + referenceIntermediate.absolutePath
			lines << 'REFERENCE_AT_PATCHED=' + referenceAtPatched.absolutePath
			lines << 'SUBJECT_PRE_PATCH=' + prePatchTask.outputJar.get().asFile.absolutePath
			lines << 'SUBJECT_INTERMEDIATE=' + intermediateTask.outputJar.get().asFile.absolutePath
			lines << 'SUBJECT_AT_PATCHED=' + atTask.outputJar.get().asFile.absolutePath

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

	private static void printEvidence(Map<String, String> report, Map<String, String> prePatch,
			Map<String, String> intermediate, Map<String, String> atPatched) {
		println("pre-patch 产物: " + report.SUBJECT_PRE_PATCH + "（" + prePatch.size() + " 个条目）")
		println("intermediate 产物: " + report.SUBJECT_INTERMEDIATE + "（" + intermediate.size() + " 个条目）")
		println("at-patched 产物: " + report.SUBJECT_AT_PATCHED + "（" + atPatched.size() + " 个条目）")
		println("链: " + report.PRE_PATCH_TASK + " -> " + report.INTERMEDIATE_TASK + " -> " + report.AT_TASK)
	}
}
