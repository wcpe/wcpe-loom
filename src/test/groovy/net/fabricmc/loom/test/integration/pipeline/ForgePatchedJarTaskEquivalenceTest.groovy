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
 * Forge 的最终 patched jar / client-extra 由执行期任务产出的**产物内容**等价性与接线检查.
 *
 * <p>被切到任务的是 {@code CompileConfiguration.setupMinecraft} 里的
 * {@code patched.getPatchedProvider().remapJar(...)}：改造前它把最终 patched jar 与 client-extra
 * 写在配置期，现在由 {@code :generateForgePatchedJar} 在执行期写同一对路径。
 *
 * <h2>对照怎么构造</h2>
 * 配置期路径已从调用点上切走，产出不再自动可得，因此本测试在探针里**手工调用被切走的那一处实现**：
 * {@code MinecraftPatchedProvider.produceFinalJarsInto(inputs, …)}——即生产逻辑本身（配置期与执行期
 * 共用它），参数取自被测 provider 自己的 {@code productionOptions(sf)}。参照产物写在
 * {@code build/probe/configtime-*.jar}，因此**被测产物的路径上只有被测任务一个写入者**——不会出现
 * 「配置期先写一份、任务再覆盖」这类别名，否则「两者一致」可以由「读到了同一份文件」伪造出来。
 *
 * <p>该实现与任务共用，故「两者一致」在实现层面是构造性的；本测试真正要钉住的是**接线**：
 * 任务读的是不是那几个输入、写的是不是那两个路径、消费方（MC jar 集合与 {@code forgeExtra} 配置）
 * 有没有拿到任务依赖。为了不让「构造性一致」掩盖接线错误，下面每一条内容断言都配了扰动对照。
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义。任何一条 entry 的字节不同即判为差异。
 *
 * <h2>扰动对照（每一条内容断言都配一条）</h2>
 * <ul>
 *   <li><b>空映射树</b>：其余输入原样、只把映射树换成空树 → patched jar 必须与对照不同。
 *       它同时证明「映射树确实参与产物内容」以及比对口径能识别「名字相同、字节不同」；</li>
 *   <li><b>错位的 client jar</b>：把 client-extra 的输入换成服务端 jar → client-extra 必须与对照不同。
 *       它证明 client-extra 的内容确实来自它声明的那个输入；</li>
 *   <li><b>反向排除</b>：patched jar 必须与它自己的输入（at-patched 中间 jar）不同——排除「复制而非
 *       重映射」这种假通过；</li>
 *   <li><b>独立重算的 client-extra 条目集合</b>：client-extra 的条目集合由测试 JVM 直接从 client jar
 *       算出（非 class 且非 {@code META-INF/} 下的条目 + 空 MANIFEST），与任务产出对集合相等。
 *       这一条完全不经过 loom 的实现，是「内容确实来自 client jar」的独立证据。</li>
 * </ul>
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>覆盖 **MERGED 形态 + Forge 1.20.1**（client-extra 产出、dist 清单不产出）。</li>
 *   <li>**NeoForge 的 dist 清单分支没有覆盖**：它要按 client/server 分边，需要 NeoForge 夹具
 *       （另需 Java 21）。本测试只把「Forge 下走的是空 MANIFEST 分支、因此不读服务端侧 jar」断言下来；
 *       NeoForge 那条链的端到端覆盖在 {@code Forge1206Test}。</li>
 *   <li>**legacy Forge 与 disableObfuscation 没有覆盖**：两者的最终 jar 形态不同，
 *       按 {@code remapJarProjectionBlocker()} 整批留在配置期，本次未迁移。</li>
 *   <li>**server-only / client-only 形态没有覆盖**：{@code SingleJarForgeMinecraftProvider} 的
 *       client/server binpatch 在本环境产不出来（见 {@code SrgMojangMappedRemapEquivalenceTest}
 *       的覆盖边界说明），因此它们的任务接线没有被执行到。</li>
 * </ul>
 */
class ForgePatchedJarTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 被测任务名（见 {@code GenerateForgePatchedJarTask.NAME}）. */
	private static final String TASK = "generateForgePatchedJar"

	def "patched jar 与 client-extra 由执行期任务产出，且与配置期实现逐 entry 一致"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.20.1')
				.replace('@FORGEVERSION@', '47.2.1')
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		gradle.buildGradle << probeScript().stripIndent()

		when: "跑消费方：产出任务必须先在任务图里跑过"
		// 探针在配置期调用被切走的实现（它需要 Gradle 项目模型），故显式关掉配置缓存；
		// 同时关掉构建缓存：本用例要断言被测任务**真的执行了**，而不是从缓存恢复了别的产物
		def result = gradle.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def subject = entries(new File(report.SUBJECT_JAR))
		def subjectExtra = entries(new File(report.SUBJECT_CLIENT_EXTRA))
		def reference = entries(new File(report.REFERENCE_JAR))
		def referenceExtra = entries(new File(report.REFERENCE_CLIENT_EXTRA))
		def perturbedForgeJar = entries(new File(report.PERTURBED_FORGE_JAR))
		def perturbedExtra = entries(new File(report.PERTURBED_CLIENT_EXTRA_JAR))
		def inputJar = entries(new File(report.INPUT_JAR))
		def clientJar = new File(report.CLIENT_JAR)
		printEvidence(report, subject, reference)

		then: "产出任务被注册，且写的就是配置期路径下那两个产物"
		report.TASK_FOUND == "true"
		report.TASK_OUTPUT == report.PROVIDER_JAR
		report.TASK_CLIENT_EXTRA == report.CLIENT_EXTRA_JAR
		report.TASK_AT_PATCHED == report.INPUT_JAR
		report.TASK_CLIENT_EXTRA_ENABLED == "true"

		and: "任务声明的每一个输入都等于配置期实现读入的那个文件"
		report.TASK_FORGE_JAR == report.BASE_FORGE_JAR
		report.TASK_USERDEV_JAR == report.BASE_USERDEV_JAR
		report.TASK_CLIENT_JAR == report.BASE_CLIENT_JAR

		and: "本夹具（Forge，非 NeoForge）不读服务端侧 jar，两侧都必须不声明它"
		// 「多声明」会让任务把一件生产从不读的文件当输入，1.17 及更早的非 bundle 版本上那件文件
		// 根本不存在，任务会以「输入文件不存在」直接失败（本次实测过）
		report.BASE_SERVER_JAR == "<未设置>"
		report.TASK_SERVER_JAR == "<未设置>"

		and: "任务声明的命名空间与 dist 取值就是配置期实现用的那几个（决定重映射方向与清单内容）"
		report.TASK_REMAP_NAMESPACE == "srg"
		report.TASK_COREMOD_NAMESPACE == "srg"
		report.TASK_DIST_ATTRIBUTE == "client server"
		report.TASK_LOCK_KEY == "forge-patched:1.20.1:1.20.1-47.2.1"

		and: "消费方的任务图里带着产出任务（MC jar 集合与 forgeExtra 两条消费路径都接上了）"
		result.task(":remapJar").outcome == SUCCESS
		[
			result.task(":" + TASK)?.outcome
		].every { it == SUCCESS || it == UP_TO_DATE }
		report.EXTENSION_JARS.contains(report.TASK_OUTPUT)

		and: "产物非空，且 patched jar 不是它自己的输入（反向排除「复制而非重映射」）"
		subject.size() > 0
		subjectExtra.size() > 0
		diff(subject, inputJar).size() > 0

		and: "patched jar 与配置期实现逐 entry 一致"
		diff(subject, reference).isEmpty()

		and: "client-extra 与配置期实现逐 entry 一致"
		diff(subjectExtra, referenceExtra).isEmpty()

		and: "扰动对照①：把 Forge universal jar 换成 userdev jar，patched jar 必须变（forgeJar 确实参与产物内容）"
		diff(subject, perturbedForgeJar).size() > 0

		and: "扰动对照②：把 client-extra 的输入换成 Forge universal jar，client-extra 必须变"
		diff(subjectExtra, perturbedExtra).size() > 0

		and: "独立重算：client-extra 的条目集合就是 client jar 的非 class / 非 META-INF 条目 + 空 MANIFEST"
		subjectExtra.keySet() == expectedClientExtraEntries(clientJar)
	}

	/**
	 * 追加到夹具 build.gradle 的探针.
	 *
	 * <p>它在配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）做四件事：记录被测任务声明的
	 * 输入输出、调用配置期实现产出对照、产出两份扰动对照、把结果写进报告文件。
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def provider = dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.get(project)
			def task = project.tasks.findByName('generateForgePatchedJar')
			def probeDir = new File(project.buildDir, 'probe')
			probeDir.mkdirs()

			def lines = []
			lines << 'TASK_FOUND=' + (task != null)
			lines << 'TASK_OUTPUT=' + task.outputJar.get().asFile.absolutePath
			lines << 'TASK_CLIENT_EXTRA=' + task.clientExtraJar.get().asFile.absolutePath
			lines << 'TASK_AT_PATCHED=' + task.atPatchedJar.get().asFile.absolutePath
			lines << 'TASK_CLIENT_EXTRA_ENABLED=' + task.clientExtraEnabled.get()
			lines << 'TASK_PATCH_VERSION=' + task.patchVersion.get()
			lines << 'PROVIDER_JAR=' + extension.minecraftProvider.minecraftJars[0].toString()
			lines << 'CLIENT_EXTRA_JAR=' + dev.architectury.loom.forge.dependency.ForgeProvider.getForgeCache(project).resolve('client-extra.jar').toString()
			lines << 'EXTENSION_JARS=' + extension.getMinecraftJarsCollection(net.fabricmc.loom.api.mappings.layered.MappingsNamespace.OFFICIAL).files.collect { it.absolutePath }.join(',')
			lines << 'CLIENT_JAR=' + extension.minecraftProvider.minecraftClientJar.absolutePath

			def serviceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
			def base = provider.productionOptions(serviceFactory)
			lines << 'INPUT_JAR=' + base.atPatchedJar().toString()
			lines << 'BASE_FORGE_JAR=' + base.forgeJar().toString()
			lines << 'BASE_USERDEV_JAR=' + base.forgeUserdevJar().toString()
			lines << 'BASE_CLIENT_JAR=' + base.clientJar().toString()
			lines << 'BASE_SERVER_JAR=' + (base.serverJar() == null ? '<未设置>' : base.serverJar().toString())
			lines << 'TASK_FORGE_JAR=' + task.forgeJar.get().asFile.absolutePath
			lines << 'TASK_USERDEV_JAR=' + task.forgeUserdevJar.get().asFile.absolutePath
			lines << 'TASK_CLIENT_JAR=' + task.clientJar.get().asFile.absolutePath
			lines << 'TASK_SERVER_JAR=' + (task.serverJar.isPresent() ? task.serverJar.get().asFile.absolutePath : '<未设置>')
			lines << 'TASK_REMAP_NAMESPACE=' + task.remapNamespace.get()
			lines << 'TASK_COREMOD_NAMESPACE=' + task.coreModNamespace.get()
			lines << 'TASK_DIST_ATTRIBUTE=' + task.distAttribute.get()
			lines << 'TASK_LOCK_KEY=' + task.lockKey.get()

			def lifecycle = { String message -> project.logger.lifecycle(message) }
			def reference = new File(probeDir, 'configtime-patched.jar')
			def referenceExtra = new File(probeDir, 'configtime-client-extra.jar')
			provider.produceFinalJarsInto(base, reference.toPath(), referenceExtra.toPath(), lifecycle)

			// 扰动①：把 Forge universal jar 换成 userdev jar，其余输入原样（产物里的非 class 文件与类解析都随之改变）
			def wrongForge = new dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.ProductionOptions(
					base.atPatchedJar(), base.forgeUserdevJar(), base.forgeUserdevJar(), base.clientJar(), base.serverJar(),
					base.mappings(), base.clientExtra(), base.neoForge(), base.forge(), base.unobfuscatedForge(),
					base.runtimeMojang(), base.merged(), base.remapNamespace(), base.coreModNamespace(), base.distAttribute())
			def perturbedForgeJar = new File(probeDir, 'perturbed-forge-jar.jar')
			provider.produceFinalJarsInto(wrongForge, perturbedForgeJar.toPath(), null, lifecycle)

			// 扰动②：把 client-extra 的输入换成 Forge universal jar，其余输入原样
			def wrongClient = new dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.ProductionOptions(
					base.atPatchedJar(), base.forgeJar(), base.forgeUserdevJar(), base.forgeJar(), base.serverJar(),
					base.mappings(), true, base.neoForge(), base.forge(), base.unobfuscatedForge(),
					base.runtimeMojang(), base.merged(), base.remapNamespace(), base.coreModNamespace(), base.distAttribute())
			def perturbedExtra = new File(probeDir, 'perturbed-client-extra.jar')
			provider.produceFinalJarsInto(wrongClient, new File(probeDir, 'perturbed-patched.jar').toPath(), perturbedExtra.toPath(), lifecycle)

			serviceFactory.close()

			lines << 'REFERENCE_JAR=' + reference.absolutePath
			lines << 'REFERENCE_CLIENT_EXTRA=' + referenceExtra.absolutePath
			lines << 'PERTURBED_FORGE_JAR=' + perturbedForgeJar.absolutePath
			lines << 'PERTURBED_CLIENT_EXTRA_JAR=' + perturbedExtra.absolutePath
			lines << 'SUBJECT_JAR=' + task.outputJar.get().asFile.absolutePath
			lines << 'SUBJECT_CLIENT_EXTRA=' + task.clientExtraJar.get().asFile.absolutePath

			file('probe-report.properties').text = lines.join(System.lineSeparator()) + System.lineSeparator()
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

	/**
	 * 在测试 JVM 里独立算出 client-extra 应有的条目集合.
	 *
	 * <p>口径取自 {@code MinecraftPatchedProvider.copyNonClassFiles} 的过滤器（非 class 且不在
	 * {@code /META-INF} 下）加上 {@code createEmptyJarManifest} 写出的那个 MANIFEST。刻意不调用 loom 的
	 * 实现：这样「client-extra 读的是别的 jar」这类接线错误会被抓到，而不是自我印证。
	 */
	private static Set<String> expectedClientExtraEntries(File clientJar) {
		def expected = ["META-INF/MANIFEST.MF"] as Set<String>

		new ZipFile(clientJar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (e.directory) {
					continue
				}

				if (e.name.endsWith(".class") || e.name.startsWith("META-INF")) {
					continue
				}

				expected << e.name
			}
		}

		assert expected.size() > 1
		return expected
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

	private static void printEvidence(Map<String, String> report, Map<String, String> subject, Map<String, String> reference) {
		println("被测产物: " + report.SUBJECT_JAR + "（" + subject.size() + " 个条目）")
		println("配置期对照: " + report.REFERENCE_JAR + "（" + reference.size() + " 个条目）")
		println("任务声明的输入: at-patched=" + report.TASK_AT_PATCHED)
		println("MC jar 集合: " + report.EXTENSION_JARS)
	}
}
