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

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 检查声明了环境拆分的 mod 依赖有没有产物生产者.
 *
 * <p>mod 依赖有两条分支（见 {@code ModDependencyFactory.create}）：普通 jar 走 {@code SimpleModDependency}，
 * 消费的是无后缀的 {@code <name>-<key>-<version>.jar}；声明了 {@code Fabric-Loom-Split-Environment} 的 jar
 * 走 {@code SplitModDependency}，消费的是 {@code <name>-<key>-common-<version>.jar} 与 {@code -client-} 两条。
 * 两者消费的路径不同，因此「任务写出了某一条」并不自动意味着另一条也有生产者。
 *
 * <p>本用例把三条依赖放进**同一次构建**里做对照（它们的差别只在 manifest 声明）：
 * <ul>
 *   <li>{@code plain-mod}：对照组，未声明拆分环境；</li>
 *   <li>{@code split-basic-mod}：声明拆分环境、无 client 条目；</li>
 *   <li>{@code split-full-mod}：声明拆分环境且有 client 条目（common/client 都产出）。</li>
 * </ul>
 *
 * <p>断言分两层，都不依赖对代码的阅读：**接线**上是「消费方拿到的每条 remapped_mods 路径都被某个
 * {@code RemapModsTask} 声明为产物」，**端到端**上是「build 之后这些路径真的存在文件」。
 * 消费方的路径从构建脚本里读同一个夹具工程解析出的依赖集合，而不是由测试另行推算——
 * 推算出来的路径只能证明测试自己算得对。
 *
 * <p>拆分分支被选中这件事同样由数据证明：只有 {@code SplitModDependency} 会算出 {@code -common-} /
 * {@code -client-} 后缀，对照组的无后缀路径则说明拆分判定确实按 manifest 分流，而不是「三条依赖走同一条路」。
 */
class SplitModDependencyProducerTest extends Specification implements GradleProjectTestTrait {
	def "声明了环境拆分的 mod 依赖也有产物生产者"() {
		setup:
		def gradle = gradleProject(project: "splitModDependency", version: DEFAULT_GRADLE)
		// 依赖在配置期就被解析（文件依赖要算校验和），因此 jar 必须在跑构建之前写好
		writeModDeps(new File(gradle.projectDir, "mod-deps"))
		gradle.buildGradle << '''
            def reportFile = project.file('split-deps-report.properties')

            project.afterEvaluate {
                def lines = []

                // 产出任务声明（也因此写出）的产物：这是「谁产出该文件」的唯一判据
                project.tasks.withType(net.fabricmc.loom.pipeline.RemapModsTask).each { task ->
                    task.outputs.files.files.each { file ->
                        lines << 'TASK_OUTPUT=' + file.absolutePath
                    }
                    task.mods.get().each { mod ->
                        lines << 'BATCH_OUTPUT=' + mod.getOutputJar().get().asFile.absolutePath

                        if (mod.getSplitClientJar().isPresent()) {
                            lines << 'BATCH_OUTPUT=' + mod.getSplitClientJar().get().asFile.absolutePath
                        }
                    }
                }

                // 消费方拿到的文件依赖：由 ModDependency.applyToProject 注入到各个 collector 配置。
                // 这里走 configurations.names（普通 Set）而不是 configurations.findAll——
                // DomainObjectCollection.findAll(Closure) 在 Gradle 9 已弃用，而夹具用 --warning-mode fail。
                project.configurations.names.each { name ->
                    if (!name.startsWith('mod') || !name.endsWith('Mapped')) {
                        return
                    }

                    def config = project.configurations.getByName(name)

                    config.allDependencies.withType(org.gradle.api.artifacts.FileCollectionDependency).each { dep ->
                        dep.files.files.each { file ->
                            lines << 'CONSUMER=' + config.name + '|' + file.absolutePath
                        }

                        try {
                            def builtBy = dep.files.buildDependencies.getDependencies(null)
                                    .collect { it.path }.sort().join(',')
                            lines << 'CONSUMER_BUILT_BY=' + config.name + '|' + builtBy
                        } catch (Exception e) {
                            lines << 'CONSUMER_BUILT_BY=' + config.name + '|<error: ' + e.message + '>'
                        }
                    }
                }

                reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
            }
            '''

		when: "跑一次完整构建，报告由配置期写出"
		def result = gradle.run(task: "build")
		def report = parseReport(new File(gradle.projectDir.toString(), "split-deps-report.properties"))

		then: "构建成功"
		result.task(":build").outcome == SUCCESS

		and: "对照组：未声明拆分环境的 jar 消费的是无后缀产物"
		def consumerJars = consumerPaths(report).findAll { it.endsWith(".jar") }.collect { new File(it) }
		consumerJars.any { it.name.startsWith("plain-mod-") && !it.name.contains("-common-") && !it.name.contains("-client-") }

		and: "确实走到了拆分分支：拆分环境 jar 的消费路径带 -common / -client 后缀（这两个后缀只由 SplitModDependency 算出）"
		consumerJars.any { it.name.startsWith("split-basic-mod-") && it.name.contains("-common-") }
		consumerJars.any { it.name.startsWith("split-full-mod-") && it.name.contains("-common-") }
		consumerJars.any { it.name.startsWith("split-full-mod-") && it.name.contains("-client-") }

		and: "接线：消费方读到的每条 remapped_mods 产物都被某个 remapMods 任务声明（因此有唯一生产者）"
		def declaredOutputs = report.findAll { it.startsWith("TASK_OUTPUT=") }.collect { it.substring("TASK_OUTPUT=".length()) }
		def remappedModsPaths = consumerPaths(report).findAll { it.replace('\\', '/').contains('/remapped_mods/') }
		remappedModsPaths.every { declaredOutputs.contains(it) }

		and: "端到端：build 之后这些产物真的有文件（冷缓存下消费方不会拿到悬空路径）"
		remappedModsPaths.every { new File(it).exists() }

		and: "client 半确实被拆出来了：common 里没有 client 条目，client 里没有 common 条目"
		def splitCommon = consumerJars.find { it.name.startsWith("split-full-mod-") && it.name.contains("-common-") }
		def splitClient = consumerJars.find { it.name.startsWith("split-full-mod-") && it.name.contains("-client-") }
		jarEntries(splitCommon).contains("common-entry.txt")
		!jarEntries(splitCommon).contains("client-entry.txt")
		jarEntries(splitClient).contains("client-entry.txt")
		!jarEntries(splitClient).contains("common-entry.txt")

		and: "两半带着 JarSplitter 写出的环境标记（证明它们真是拆出来的，而不是整体 jar 的副本）"
		splitEnvironmentName(splitCommon) == "common"
		splitEnvironmentName(splitClient) == "client"

		and: "对照组：未声明拆分环境的 jar 一条都不拆"
		def plain = consumerJars.find { it.name.startsWith("plain-mod-") }
		jarEntries(plain).containsAll([
			"common-entry.txt",
			"client-entry.txt"
		])
	}

	/** {@return jar 内的条目名}. */
	private static Set<String> jarEntries(File jar) {
		def zip = new ZipFile(jar)

		try {
			return Collections.list(zip.entries()).collect { it.name }.toSet()
		} finally {
			zip.close()
		}
	}

	/** {@return jar 的 manifest 里 {@code Fabric-Loom-Split-Environment-Name} 的取值}. */
	private static String splitEnvironmentName(File jar) {
		def zip = new ZipFile(jar)

		try {
			def entry = zip.getEntry("META-INF/MANIFEST.MF")
			def manifest = zip.getInputStream(entry).withStream { stream -> new Manifest(stream) }
			return manifest.mainAttributes.getValue("Fabric-Loom-Split-Environment-Name")
		} finally {
			zip.close()
		}
	}

	/** {@return 报告里消费方拿到的文件依赖绝对路径}. */
	private static List<String> consumerPaths(List<String> report) {
		return report.findAll { it.startsWith("CONSUMER=") }.collect { it.substring(it.indexOf('|') + 1) }
	}

	/** 解析构建脚本写出的 {@code KEY=VALUE} 报告. */
	private static List<String> parseReport(File report) {
		assert report.exists(): "未找到拆分依赖报告 ${report.absolutePath}"

		return report.readLines().findAll { it.contains('=') }
	}

	/**
	 * 写出三个内容相同、只有 manifest 声明不同的 mod jar.
	 *
	 * <p>字段名取自 {@code Constants.Manifest}（{@code Fabric-Loom-Split-Environment} /
	 * {@code Fabric-Loom-Client-Only-Entries}），语义见 {@code JarSplitter.analyseTarget}：
	 * 先看拆分开关，再看 client 条目列表——为空是 COMMON_ONLY，存在未列入 client 的条目则是 SPLIT。
	 *
	 * @param dir 依赖目录，不存在时创建
	 */
	private static void writeModDeps(File dir) {
		writeModJar(new File(dir, "plain-mod.jar"), null, null)
		writeModJar(new File(dir, "split-basic-mod.jar"), "true", null)
		writeModJar(new File(dir, "split-full-mod.jar"), "true", "client-entry.txt")
	}

	private static void writeModJar(File file, String splitEnvironment, String clientEntries) {
		def manifest = new Manifest()
		def attributes = manifest.mainAttributes
		attributes.putValue("Manifest-Version", "1.0")

		if (splitEnvironment != null) {
			attributes.putValue("Fabric-Loom-Split-Environment", splitEnvironment)
		}

		if (clientEntries != null) {
			attributes.putValue("Fabric-Loom-Client-Only-Entries", clientEntries)
		}

		file.parentFile.mkdirs()

		file.withOutputStream { stream ->
			def jar = new JarOutputStream(stream, manifest)
			addEntry(jar, "fabric.mod.json", '{"schemaVersion": 1, "id": "loom-split-test", "version": "1.0.0"}'.bytes)
			addEntry(jar, "common-entry.txt", "common".bytes)
			addEntry(jar, "client-entry.txt", "client".bytes)
			jar.close()
		}
	}

	private static void addEntry(JarOutputStream jar, String name, byte[] content) {
		jar.putNextEntry(new JarEntry(name))
		jar.write(content)
		jar.closeEntry()
	}
}
