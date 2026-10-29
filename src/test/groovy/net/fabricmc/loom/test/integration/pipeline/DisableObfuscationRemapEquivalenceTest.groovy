/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2023 FabricMC
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

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * {@code disableObfuscation} 形态下，任务产出与配置期产出的等价性对照.
 *
 * <h4>为什么需要这份对照</h4>
 * {@code AbstractMappedMinecraftProvider.verifyProjectable} 至今拒绝投影该形态，理由写在代码里：
 * 「直通形态的产物就是输入 jar 的副本，一旦投影写错（例如漏了 backup、或把 deobf 的 pom 写到了别处），
 * 产物仍是『构建成功』，静默损坏的代价远高于一次回退」——解除条件正是本测试。
 *
 * <h4>两侧实现在哪</h4>
 * <ul>
 *   <li>配置期：{@code AbstractMappedMinecraftProvider.remapJar} 的 {@code disableObfuscation} 分支——
 *       只有两句：{@code AtomicFiles.copy} + {@code getMavenHelper(...).savePom()}。
 *       backup 不在这里，而由 {@code produceInConfiguration} 里的 {@code createBackupJars}
 *       **按整批**做；</li>
 *   <li>任务期：{@code RemapMinecraftTask.remap()} 的 {@code copyOnly} 分支——
 *       {@code AtomicFiles.copy} + {@code writePom} + {@code writeBackupJar}，backup 是**逐 jar** 做。</li>
 * </ul>
 * 所以本测试要验证的不只是「jar 内容一致」，还有**三个产出物都就位**（jar / pom / backup）。
 *
 * <h4>为何未设 mappings 相关输入</h4>
 * 直通形态没有 {@code MappingConfiguration}，{@code mappingsServiceOptions} 虽为必填
 * {@code @Nested}，但在 {@code copyOnly} 下不参与执行（{@code remap()} 先分支直接复制）。
 * 本测试以此证实：该形态走任务路径时不需要映射树。
 */
class DisableObfuscationRemapEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/**
	 * 任务产出的 jar 与配置期产出的那个逐条目一致，且 pom / backup 同样就位.
	 *
	 * <p>测试工程用 {@code minimalBaseNoRemap}（应用 {@code dev.architectury.loom-no-remap}），
	 * 这是走 {@code disableObfuscation} 的入口。
	 */
	def "直通形态：任务产出与配置期产出等价"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap")
		gradle.buildGradle << '''
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
            }

            def equivalenceProbe = tasks.register('equivalenceProbe', net.fabricmc.loom.pipeline.RemapMinecraftTask)

            project.afterEvaluate {
                def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)

                // 配置期基线：直通形态下 provider 的产物就是 remapJar 的 disableObfuscation 分支
                // 原子复制出来的那个 jar。探针要复现的正是它。
                def baseline = loomExt.getMinecraftProvider().getMinecraftJars()[0]

                equivalenceProbe.configure { probeTask ->
                    // 输入 = 同一个 vanilla jar（直通形态不改写字节码，输入输出内容应当一致）
                    probeTask.inputJar.set(baseline.toFile())
                    probeTask.outputJar.set(project.layout.buildDirectory.file('probe/remapped.jar'))

                    // 直通模式开关：与配置期 remapJar 的 disableObfuscation 分支同源
                    probeTask.copyOnly.set(true)

                    probeTask.fromNamespace.set('official')
                    probeTask.toNamespace.set('named')

                    // 下列必填输入在 copyOnly 下不参与执行（remap() 先分支直接复制，不会走到重映射），
                    // 但 Gradle 的工作校验要求所有 @Input 都有值，缺一个就直接失败。
                    // 取值与配置期在 disableObfuscation 分支下的实际输入一致：该分支根本不读这些项。
                    probeTask.fixRecords.set(false)
                    probeTask.forgeLike.set(false)
                    probeTask.validateTargetNamespace.set(false)
                    probeTask.objectHolderTargetNamespace.set('named')

                    // 刻意不设 mappingsServiceOptions：直通形态没有 MappingConfiguration，
                    // 这正是该形态当初被拦在任务路径外的原因。任务协议现在把它标为可选，
                    // 由 copyOnly 分支与 remap() 里的断言共同保证“不需要它时不会用到它”。

                    // 输出位置：必须与 pom 同目录且同名（任务会校验 pom 是 jar 的同名兄弟，
                    // 见 writePom）。因此两者都落在 maven 构件目录里，与生产路径一致。
                    def artifactDir = project.layout.buildDirectory.dir('probe/maven/com/example/probe/1.0.0')
                    probeTask.outputJar.set(artifactDir.map { it.file('probe-1.0.0.jar') })

                    // pom 坐标：让 writePom 有东西可写，验证 pom 产出路径。
                    // pom 的位置由 LocalMavenHelper 按 group/name/version 展开，任务会校验声明值与之一致。
                    probeTask.pomGroup.set('com.example')
                    probeTask.pomName.set('probe')
                    probeTask.pomVersion.set('1.0.0')
                    probeTask.pomMavenRoot.set(project.layout.buildDirectory.dir('probe/maven').get().asFile)
                    probeTask.outputPom.set(artifactDir.map { it.file('probe-1.0.0.pom') })

                    // backup 输出：任务会校验它必须是 <jar 文件名>.backup 且与 jar 同目录
                    // （见 writeBackupJar 的断言）。它是「产物已齐」的标志。
                    probeTask.outputBackupJar.set(artifactDir.map { it.file('probe-1.0.0.jar.backup') })
                }

                def marker = project.layout.buildDirectory.file('probe/baseline.txt').get().asFile
                marker.parentFile.mkdirs()
                marker.text = baseline.toFile().absolutePath
            }

            tasks.register('printProbePaths') {
                def out = project.layout.buildDirectory.file('probe/maven/com/example/probe/1.0.0/probe-1.0.0.jar').get().asFile.absolutePath
                def backup = project.layout.buildDirectory.file('probe/maven/com/example/probe/1.0.0/probe-1.0.0.jar.backup').get().asFile.absolutePath
                def pom = project.layout.buildDirectory.file('probe/maven/com/example/probe/1.0.0/probe-1.0.0.pom').get().asFile.absolutePath
                def baseFile = project.layout.buildDirectory.file('probe/baseline.txt').get().asFile

                doLast {
                    println "PROBE_OUTPUT=" + out
                    println "PROBE_BACKUP=" + backup
                    println "PROBE_POM=" + pom
                    println "BASELINE_JAR=" + baseFile.text.trim()
                }
            }
            '''

		when: "先跑一次 build，让配置期照旧产出直通 jar"
		def build = gradle.run(task: "build")

		then:
		build.task(":build").outcome in [SUCCESS, UP_TO_DATE]

		when: "再跑任务版复制，输入与配置期用的是同一个 vanilla jar"
		def probe = gradle.run(tasks: [
			"equivalenceProbe",
			"printProbePaths"
		])

		then:
		probe.task(":equivalenceProbe").outcome in [SUCCESS, UP_TO_DATE]

		and: "两侧 jar 都存在"
		def probeOutput = extractPath(probe.output, "PROBE_OUTPUT=")
		def baselineJar = extractPath(probe.output, "BASELINE_JAR=")
		new File(probeOutput).exists()
		new File(baselineJar).exists()

		and: "class 条目集合相同"
		def produced = classEntries(new File(probeOutput))
		def expected = classEntries(new File(baselineJar))
		!produced.isEmpty()
		produced.keySet() == expected.keySet()

		and: "每个 class 条目的内容逐一致（直通模式不改写字节码）"
		def mismatched = produced.findResults { name, hash -> hash == expected[name] ? null : name }
		mismatched.isEmpty()

		and: "三个产出物都就位：jar / pom / backup（这是配置期与任务期唯一的差异点）"
		new File(extractPath(probe.output, "PROBE_POM=")).exists()
		new File(extractPath(probe.output, "PROBE_BACKUP=")).exists()
	}

	/** 从构建输出里取形如 PREFIX<path> 的路径. */
	private static String extractPath(String output, String prefix) {
		def line = output.readLines().find { it.startsWith(prefix) }
		assert line != null: "未在构建输出里找到 ${prefix}"
		return line.substring(prefix.length()).trim()
	}

	/** 取出 jar 中 class 条目的名字与内容摘要. */
	private static Map<String, String> classEntries(File jar) {
		final Map<String, String> result = [:]

		new ZipFile(jar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (e.directory || !e.name.endsWith(".class")) {
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
}
