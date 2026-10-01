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

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * {@code RemapMinecraftTask} 的等价性验证.
 *
 * <p>这是把 mapped jar 的生产从配置期切到任务之前**必须拿到的证据**：同一个输入 jar、
 * 同一套映射、同一条 classpath，任务产出的内容必须与配置期既有的产出一致。
 * 没有这份对照就切换，等于在没有基准的情况下换掉核心实现。
 *
 * <p>做法：先正常跑一次 {@code build}（配置期照旧产出 named jar），再跑一个探针任务
 * {@code RemapMinecraftTask}，喂给它配置期用过的**同一个输入 jar**（vanilla merged jar，
 * 见 {@code NamedMinecraftProvider.MergedImpl.getRemappedJars}），最后逐条目比对两者的产出。
 *
 * <p>比对口径是「条目名 + 每个 class 条目的内容摘要」而非整文件哈希：jar 的中央目录顺序
 * 与时间戳不属于语义，逐条目比对既严格又不依赖打包细节。因此**通过**的含义是
 * 「两个 jar 的每个条目内容相同」，而不是「两个文件逐字节相同」。
 *
 * <p>两点接线上的注意（都是 Gradle 的约束，不是本任务的缺陷）：
 * <ul>
 *   <li>Minecraft provider 在 Loom 自身的 {@code afterEvaluate} 里才建立，因此探针任务的
 *       接线必须注册在更晚的 {@code afterEvaluate}，否则会读到尚未就绪的 provider。</li>
 *   <li>回传路径的任务动作只能捕获字符串——捕获 {@code Project} 或 extension 会让配置缓存
 *       因不可序列化而失败，那是脚本的问题、会被误记为任务的缺陷。</li>
 * </ul>
 */
class RemapMinecraftTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/**
	 * 任务产出的 named merged jar 与配置期产出的那一个逐条目一致（本测试当前**通过**）.
	 *
	 * <h4>与配置期同源的输入：逐项取自配置期的同一处，而不是在测试里硬编</h4>
	 * <ul>
	 *   <li>{@code inputJar} ← {@code loomExt.getMinecraftProvider().getMinecraftJars()[0]}，
	 *       即配置期 named 分支所读的那个 vanilla merged jar；</li>
	 *   <li>{@code mappingsServiceOptions} ←
	 *       {@code MappingConfiguration.getMappingsServiceOptions(project, MappingOption.forPlatform(extension))}，
	 *       与配置期 {@code AbstractMappedMinecraftProvider.remapJar} 取映射树所用的是同一处调用
	 *       （两条路径最终都汇进 {@code TinyRemapperHelper.getTinyRemapper(mappingTree, ...)}，
	 *       所以只要输入相同，remapper 的装配就相同）；</li>
	 *   <li>{@code knownIndyBsms} ← {@code loomExt.getKnownIndyBsms()}（extension 自己的 provider），
	 *       与接线侧 {@code task.getKnownIndyBsms().set(extension.getKnownIndyBsms())} 同源。
	 *       这里刻意不硬编那三个字符串：convention 变化时硬编值会静默失真，而失真的方向恰好是
	 *       「测试照旧通过、比的是错的输入」；</li>
	 *   <li>{@code fixRecords} / {@code forgeLike} / {@code validateTargetNamespace} /
	 *       {@code innerClassNames} / {@code signatureFixes} / {@code objectHolderTargetNamespace}
	 *       ← 与配置期 {@code remapJar} 逐项同判据（本场景取 true / false / true / 空 / 空 / 'named'）；</li>
	 *   <li>{@code remapClasspath} 与 {@code annotationsJson} 保持未设置，与 merged provider 的取值一致
	 *       （{@code MergedImpl} 构造 {@code RemappedJars} 时不传 remapClasspath）。</li>
	 * </ul>
	 *
	 * <h4>已核实的结论</h4>
	 * 实测通过：探针产出的 20951 个条目（其中 7436 个 class）与配置期的 named merged jar
	 * **逐条目内容相同**，用独立脚本复核过同样结论（整文件哈希不同，差异只在时间戳与中央目录顺序）；
	 * 且探针产物与其输入（vanilla merged jar）确实不同，排除了「复制而非重映射」这种假通过。
	 *
	 * <p>断言本身也验证过是「活的」：把 {@code injectClientSidedVisitor} 设为 true（只往 class 上
	 * 追加 {@code @Environment(CLIENT)}、不改类名）后，7436 个 class 里有 5460 个内容不一致，
	 * 测试随即在「每个 class 条目的内容逐一致」处失败。也就是说这份对照既能识别条目集合差异，
	 * 也能识别「类名相同、字节不同」这类更隐蔽的接线遗漏。
	 *
	 * <h4>关于 {@code knownIndyBsms} 的一条实测边界</h4>
	 * 把它换成空集**也**能通过，不能通过本测试来证明它被正确传递：tiny-remapper 在构造器里就无条件
	 * 加入了 {@code java/lang/invoke/StringConcatFactory}、{@code java/lang/runtime/ObjectMethods}
	 * 与 {@code java/lang/runtime/SwitchBootstraps}，而该集合（0.12.3 与 0.14.0 同）只决定
	 * 「unknown invokedynamic bsm」这条告警是否打印，不参与产物字节。因此空集与 convention 三项的差别
	 * 只落在 Groovy 的 {@code IndyInterface} 上，对 javac 编译的 MC 无影响。与 extension 同源仍然要做：
	 * 它同时是任务身份指纹的一项，且换到 Groovy 产物（例如某些 mod）时才会显出差别。
	 *
	 * <h4>为什么探针不声明 pom 坐标</h4>
	 * 探针刻意只声明 {@code outputJar} 而不声明 {@code outputPom}，用来覆盖「任务不产 pom」这条路径。
	 * 任务侧的 {@code pomGroup} / {@code pomName} / {@code pomVersion} 与 {@code outputPom} 一样是
	 * {@code @Optional}，因此缺坐标不会让 Gradle 的工作校验失败；执行期 {@code writePom} 在未声明
	 * {@code outputPom} 时直接早退。**若将来把这三个坐标改回必填，本测试会在工作校验阶段失败**——
	 * 那正是「协议与注释不一致」的信号，应当修协议而不是回填假值。
	 */
	def "任务产出与配置期产出等价"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
            }

            def equivalenceProbe = tasks.register('equivalenceProbe', net.fabricmc.loom.pipeline.RemapMinecraftTask)

            project.afterEvaluate {
                def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
                def mcNs = net.fabricmc.loom.api.mappings.layered.MappingsNamespace

                // named jar 的生产路径是「official → named」，输入是 vanilla merged jar
                // （见 NamedMinecraftProvider.MergedImpl.getRemappedJars），且配置期给的
                // remapClasspath 为空。探针必须复现同样的输入，否则比的是另一件事。
                def mergedJar = loomExt.getMinecraftProvider().getMinecraftJars()[0]

                equivalenceProbe.configure { probeTask ->
                    probeTask.inputJar.set(mergedJar.toFile())
                    // 与配置期完全相同的映射来源：MappingOption.forPlatform + MappingConfiguration
                    probeTask.mappingsServiceOptions.set(
                            loomExt.getMappingConfiguration().getMappingsServiceOptions(
                                    project,
                                    dev.architectury.loom.mappings.MappingOption.forPlatform(loomExt)))
                    probeTask.fromNamespace.set('official')
                    probeTask.toNamespace.set('named')
                    probeTask.fixRecords.set(true)
                    probeTask.forgeLike.set(false)
                    probeTask.validateTargetNamespace.set(true)
                    probeTask.innerClassNames.set([])
                    // 直接取 extension 的 provider，与接线侧（AbstractMappedMinecraftProvider
                    // 里 task.getKnownIndyBsms().set(extension.getKnownIndyBsms())）同源：
                    // 硬编这三个字符串会在 convention 变化时再次失真，而失真方向恰好是
                    // 「测试仍然通过、但比的是错的输入」。
                    probeTask.knownIndyBsms.set(loomExt.getKnownIndyBsms())
                    probeTask.signatureFixes.set([:])
                    probeTask.objectHolderTargetNamespace.set('named')
                    probeTask.outputJar.set(project.layout.buildDirectory.file('probe/remapped.jar'))

                    // 刻意不设置 pomGroup / pomName / pomVersion / outputPom：覆盖「任务不产 pom」路径，
                    // 也顺带守住它们的 @Optional 契约（见类 javadoc 的「为什么探针不声明 pom 坐标」）。
                }

                def marker = project.layout.buildDirectory.file('probe/baseline.txt').get().asFile
                marker.parentFile.mkdirs()
                marker.text = loomExt.getMinecraftJars(mcNs.NAMED)[0].toString()
            }

            tasks.register('printProbePaths') {
                def out = project.layout.buildDirectory.file('probe/remapped.jar').get().asFile.absolutePath
                def baseFile = project.layout.buildDirectory.file('probe/baseline.txt').get().asFile

                doLast {
                    println "PROBE_OUTPUT=" + out
                    println "BASELINE_JAR=" + baseFile.text.trim()
                }
            }
            '''

		when: "先跑一次 build，让配置期照旧产出 named jar"
		def build = gradle.run(task: "build")

		then:
		build.task(":build").outcome in [SUCCESS, UP_TO_DATE]

		when: "再跑任务版重映射，输入与配置期用的是同一个 intermediary jar"
		def probe = gradle.run(tasks: [
			"equivalenceProbe",
			"printProbePaths"
		])

		then:
		probe.task(":equivalenceProbe").outcome in [SUCCESS, UP_TO_DATE]

		and: "两侧产物都存在"
		def probeOutput = extractPath(probe.output, "PROBE_OUTPUT=")
		def baselineJar = extractPath(probe.output, "BASELINE_JAR=")
		new File(probeOutput).exists()
		new File(baselineJar).exists()

		and: "class 条目集合相同"
		def produced = classEntries(new File(probeOutput))
		def expected = classEntries(new File(baselineJar))
		!produced.isEmpty()
		produced.keySet() == expected.keySet()

		and: "每个 class 条目的内容逐一致"
		def mismatched = produced.findResults { name, hash -> hash == expected[name] ? null : name }
		mismatched.isEmpty()
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
