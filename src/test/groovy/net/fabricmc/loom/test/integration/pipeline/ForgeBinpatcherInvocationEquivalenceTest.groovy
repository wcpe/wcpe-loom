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

import groovy.json.JsonSlurper
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Forge binpatcher 的**调用参数**等价性，以及「调用点已可搬进执行期任务」的判据检查.
 *
 * <p>被改造的是 {@code MinecraftPatchedProvider.patchJars}：改造前它走
 * {@code ForgeToolValueSource.exec(project, …)}——由项目的 ProviderFactory 造 ValueSource 并立刻求值，
 * 工具 classpath 也在同一刻被 {@code DependencyDownloader} 解析掉；现在改为
 * {@code ForgeExternalToolService}（服务化，工具 classpath 是声明式的惰性输入），发起调用只依赖
 * {@code ServiceFactory}。
 *
 * <h2>对照怎么构造</h2>
 * 断言分三组，每组都有独立的取值来源，而不是在实现里自证：
 * <ol>
 *   <li><b>参数模板</b>：直接与 userdev 配置 JSON（{@code forge-config.json}）里的 {@code binpatcher.args}
 *       逐条比对。JSON 由测试自己用 {@code JsonSlurper} 读，不经过 loom 的 codec；</li>
 *   <li><b>实际命令行（argv）</b>：把模板里的 {@code {clean}}／{@code {output}}／{@code {patch}}
 *       按本次调用的三个路径展开后逐条比对。展开口径取自改造前那段代码的语义；</li>
 *   <li><b>工具 classpath 与主类</b>：测试自己按 JSON 里的坐标建 detached configuration 解析，
 *       再自己读那个 jar 的清单拿 {@code Main-Class}——完全不经过被测服务。</li>
 * </ol>
 *
 * <h2>扰动对照（每条断言都配一条）</h2>
 * <ul>
 *   <li><b>不展开占位符</b>：用空占位符表取一次设置 → argv 里会留下 {@code {clean}} 这类字面量，
 *       必须与对照不同。它证明 argv 断言确实能识别「命令行变了」；</li>
 *   <li><b>错位的工具 jar</b>：把工具 classpath 换成 Forge universal jar → classpath 必须与对照不同。
 *       它证明 classpath 断言能识别「跑的是别的工具」；</li>
 *   <li><b>反向排除</b>：执行期产出的 jar 必须与它的输入（未打补丁的中间 jar）不同，
 *       排除「复制而非打补丁」这种假通过，也证明逐 entry 比对能识别内容差异。</li>
 * </ul>
 */
class ForgeBinpatcherInvocationEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 探针在配置期与执行期各发起一次 binpatch 所用的任务名. */
	private static final String PROBE_TASK = "probeBinpatchAtExecution"

	def "binpatcher 的调用参数与改造前逐项一致，且可在执行期由同一份实现发起"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.20.1')
				.replace('@FORGEVERSION@', '47.2.1')
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		gradle.buildGradle << probeScript().stripIndent()

		when: "跑消费方；探针注册的执行期任务必须真的被执行到"
		// 探针在配置期调用被改造的那两处（都需要 Gradle 项目模型），故显式关掉配置缓存；
		// 同时关掉构建缓存：本用例要断言探针任务真的执行了，而不是从缓存里恢复产物
		def result = gradle.run(task: "remapJar", configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		printEvidence(report)

		then: "探针的执行期任务确实执行了"
		result.task(":" + PROBE_TASK).outcome == SUCCESS

		and: "① 参数模板就是 userdev 配置里的那串，逐条一致"
		report.SUBJECT_ARGS_TEMPLATE == report.ORACLE_ARGS_TEMPLATE
		report.SUBJECT_ARGS_TEMPLATE == "--clean|{clean}|--output|{output}|--apply|{patch}"

		and: "② 实际命令行（主类 + argv）与独立推出的对照逐项一致"
		report.SUBJECT_MAIN_CLASS == report.ORACLE_MAIN_CLASS
		report.SUBJECT_MAIN_CLASS == "net.minecraftforge.binarypatcher.ConsoleTool"
		report.SUBJECT_ARGV == report.ORACLE_ARGV

		and: "③ 工具 classpath 与 JVM 执行器与独立推出的对照逐项一致"
		report.SUBJECT_CLASSPATH == report.ORACLE_CLASSPATH
		report.SUBJECT_EXECUTABLE == report.ORACLE_EXECUTABLE
		report.SUBJECT_JVM_ARGS == ""

		and: "扰动对照①：不展开占位符时 argv 必须与对照不同（argv 断言能识别命令行变化）"
		report.PERTURBED_ARGV != report.ORACLE_ARGV
		report.PERTURBED_ARGV.contains("{clean}")

		and: "扰动对照②：工具 jar 换成 Forge userdev jar 时 classpath 必须与对照不同"
		report.PERTURBED_CLASSPATH != report.ORACLE_CLASSPATH

		and: "执行期发起的那次 binpatch 产出非空，且与配置期发起的那次逐 entry 一致"
		def atConfigTime = entries(new File(report.CONFIG_TIME_OUTPUT))
		def atExecutionTime = entries(new File(report.EXECUTION_TIME_OUTPUT))
		atExecutionTime.size() > 0
		diff(atExecutionTime, atConfigTime).isEmpty()

		and: "反向排除：打补丁的产物不是它自己的输入（排除复制而非打补丁）"
		diff(atExecutionTime, entries(new File(report.CLEAN_JAR))).size() > 0
	}

	/**
	 * 追加到夹具 build.gradle 的探针.
	 *
	 * <p>配置期（{@code afterEvaluate}，晚于 Loom 自己的 setup job）做四件事：读 userdev 配置 JSON 独立推出
	 * 对照；取被测服务的工具设置；在配置期与执行期各发起一次 binpatch；把结果写进报告文件。
	 */
	private static String probeScript() {
		return '''
		afterEvaluate {
			def extension = net.fabricmc.loom.LoomGradleExtension.get(project)
			def provider = dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.get(project)
			def probeDir = new File(project.buildDir, 'probe')
			probeDir.mkdirs()
			def lines = []

			// 本次调用的三个路径：与真实生产同形，但落在探针目录里，不触碰共享缓存
			def clean = provider.minecraftIntermediateJar
			def patches = extension.forgeUserdevProvider.joinedPatches
			def configTimeOut = new File(probeDir, 'binpatched-config-time.jar')
			def executionTimeOut = new File(probeDir, 'binpatched-execution-time.jar')
			def placeholders = ['{clean}': clean.toAbsolutePath().toString(),
					'{output}': configTimeOut.toPath().toAbsolutePath().toString(),
					'{patch}': patches.toAbsolutePath().toString()]
			lines << 'CLEAN_JAR=' + clean.toAbsolutePath().toString()

			// —— 独立对照：userdev 配置 JSON 不经 loom 的 codec，由本探针直接读 ——
			def forgeCache = dev.architectury.loom.forge.dependency.ForgeProvider.getForgeCache(project)
			def configJson = new groovy.json.JsonSlurper().parse(forgeCache.resolve('forge-config.json').toFile())
			def oracleTemplate = configJson.binpatcher.args.collect { it as String }
			def oracleClasspathFiles = project.configurations
					.detachedConfiguration(project.dependencies.create(configJson.binpatcher.version as String))
					.files.collect { it.absolutePath }
			def oracleClasspath = oracleClasspathFiles.sort()
			// 主类取「按解析顺序第一个清单里带 Main-Class 的 jar」，与 manifestMainClass 同口径
			def oracleMain = null
			for (jarPath in oracleClasspathFiles) {
				if (!jarPath.endsWith('.jar')) {
					continue
				}

				def found = null
				new java.util.zip.ZipFile(jarPath).withCloseable { zf ->
					def entry = zf.getEntry('META-INF/MANIFEST.MF')
					found = entry == null ? null : new java.util.jar.Manifest(zf.getInputStream(entry)).mainAttributes.getValue('Main-Class')
				}

				if (found != null) {
					oracleMain = found
					break
				}
			}
			// 与 JavaExecutableFetcher 同判据：没有配置 Java 工具链时执行器为「未设置」，沿用 Gradle 自身 JVM
			def toolchainSpec = project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension).toolchain
			def oracleExe = toolchainSpec.languageVersion.isPresent()
					? project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService)
							.launcherFor(toolchainSpec).get().executablePath.asFile.absolutePath
					: '<未设置>'
			def oracleToolchainAbsent = !toolchainSpec.languageVersion.isPresent()

			// —— 被测：工具设置（settingsFor 就是实际执行时用的那一份） ——
			def serviceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
			def tool = provider.createBinpatcherTool()
			def service = serviceFactory.get(tool)
			def settings = service.settingsFor(placeholders)
			def subjectTemplate = tool.argsTemplate.get()
			def subjectArgv = settings.programArgs.get()
			def subjectClasspath = settings.execClasspath.files.collect { it.absolutePath }.sort()
			def subjectMain = settings.mainClass.get()
			def subjectExe = settings.executable.getOrNull()
			def subjectJvmArgs = settings.jvmArgs.get().join('|')
			def perturbedArgv = service.settingsFor([:]).programArgs.get()

			// 扰动②：工具 jar 换成 Forge userdev jar
			def wrongTool = dev.architectury.loom.forge.tool.ForgeExternalToolService
					.createOptions(project, project.files(extension.forgeUserdevProvider.userdevJar), 'wrong.Main')
			def perturbedClasspath = serviceFactory.get(wrongTool).settingsFor(placeholders)
					.execClasspath.files.collect { it.absolutePath }.sort()

			// —— 配置期发起一次，执行期再发起一次：同一份实现，两条驱动路径 ——
			dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.runBinpatcher(
					tool, clean, configTimeOut.toPath(), patches, serviceFactory)

			// 执行期这条路径只认「配置期就已经造好的选项 + 本次调用的路径 + 一个服务工厂」：
			// 不读 extension、不读 provider、也不再建工具选项——这正是「调用点可搬进任务」的判据
			def probeTask = project.tasks.register('probeBinpatchAtExecution') {
				doLast {
					def execFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
					try {
						def execOut = new File(probeDir, 'binpatched-execution-time.jar')
						def execPlaceholders = ['{clean}': clean.toAbsolutePath().toString(),
								'{output}': execOut.toPath().toAbsolutePath().toString(),
								'{patch}': patches.toAbsolutePath().toString()]
						dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.runBinpatcher(
								tool, clean, execOut.toPath(), patches, execFactory)
					} finally {
						execFactory.close()
					}
				}
			}

			lines << 'SUBJECT_ARGS_TEMPLATE=' + subjectTemplate.join('|')
			lines << 'ORACLE_ARGS_TEMPLATE=' + oracleTemplate.join('|')
			lines << 'SUBJECT_ARGV=' + subjectArgv.join('|')
			lines << 'ORACLE_ARGV=' + oracleTemplate.collect { placeholders.getOrDefault(it, it) }.join('|')
			lines << 'PERTURBED_ARGV=' + perturbedArgv.join('|')
			lines << 'SUBJECT_MAIN_CLASS=' + subjectMain
			lines << 'ORACLE_MAIN_CLASS=' + oracleMain
			lines << 'SUBJECT_CLASSPATH=' + subjectClasspath.join('|')
			lines << 'ORACLE_CLASSPATH=' + oracleClasspath.join('|')
			lines << 'PERTURBED_CLASSPATH=' + perturbedClasspath.join('|')
			lines << 'SUBJECT_EXECUTABLE=' + (subjectExe == null ? '<未设置>' : subjectExe)
			lines << 'ORACLE_EXECUTABLE=' + oracleExe
			lines << 'ORACLE_TOOLCHAIN_ABSENT=' + oracleToolchainAbsent
			lines << 'SUBJECT_JVM_ARGS=' + subjectJvmArgs
			lines << 'CONFIG_TIME_OUTPUT=' + configTimeOut.absolutePath
			lines << 'EXECUTION_TIME_OUTPUT=' + executionTimeOut.absolutePath

			probeTask.get().dependsOn(project.tasks.named('generateForgePatchedJar'))
			project.tasks.named('remapJar').configure { dependsOn probeTask.get() }
			serviceFactory.close()

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
		println("被测命令行: " + report.SUBJECT_MAIN_CLASS + " " + report.SUBJECT_ARGV)
		println("对照命令行: " + report.ORACLE_MAIN_CLASS + " " + report.ORACLE_ARGV)
		println("被测 classpath: " + report.SUBJECT_CLASSPATH)
		println("对照 classpath: " + report.ORACLE_CLASSPATH)
		println("执行器: " + report.SUBJECT_EXECUTABLE)
		println("配置期产物: " + report.CONFIG_TIME_OUTPUT)
		println("执行期产物: " + report.EXECUTION_TIME_OUTPUT)
	}
}
