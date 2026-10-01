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

import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 证明「mapped jar 的生产确实由任务承担」的接线检查.
 *
 * <p>{@code RemapMinecraftTaskEquivalenceTest} 证明的是产物内容，它对照的基准是**同一个测试工程里
 * 另一个手写配置的探针任务**。两者都是任务，因此它并不能排除一种失败形态：loom 的接线根本没生效
 * （例如任务没被注册、或注册了却没进任何消费方的任务图），此时产物仍由配置期的旧路径写出，
 * 内容照样一致、测试照样通过——正是「看起来通过了，但接线是空的」。
 *
 * <p>本类补的正是这一环，全部断言都落在「接线」而不是「产物内容」上：
 * <ul>
 *   <li>loom 在自己的配置流程里注册了产出任务，且它的产物路径就是消费侧看到的那个路径；</li>
 *   <li>该任务的输入与配置期的判据逐项一致（漏设任何一项都会静默产出不同的 jar）；</li>
 *   <li>登记给消费侧的文件集合、以及编译期配置的依赖，都携带这个产出任务；</li>
 *   <li>编译期类路径解析时该任务确实进入任务图，并且真的跑过（有真实源码，javac 会用到那个 jar）。</li>
 * </ul>
 *
 * <p>为什么必须有真实源码：{@code minimalBase} 默认没有源码，{@code compileJava} 会是 NO-SOURCE，
 * 那样「编译期类路径可用」这件事根本没被检验过——冷缓存下这个 jar 只有产出任务跑完才存在。
 */
class MinecraftJarTaskWiringTest extends Specification implements GradleProjectTestTrait {
	/** 本场景下 named merged jar 的产出任务名（见 AbstractMappedMinecraftProvider.taskName）. */
	private static final String REMAP_TASK = "remapMinecraftNamedMerged"

	def "命名 jar 的生产由 loom 注册的重映射任务承担"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
            }

            def reportFile = project.file('wiring.properties')

            project.afterEvaluate {
                def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
                def mcNs = net.fabricmc.loom.api.mappings.layered.MappingsNamespace
                def namedJar = new File(loomExt.getMinecraftJars(mcNs.NAMED)[0].toString())
                // 这里必须写字面量：整段是追加到夹具 build.gradle 的三引号字符串，不会被 Groovy 插值，
                // 裸写 REMAP_TASK 会变成生成脚本里无法解析的属性（Could not get unknown property）。
                // 与下面第二条用例 findByName 处理任务名的写法一致。
                def remapTask = project.tasks.findByName('remapMinecraftNamedMerged')
                def lines = []

                lines << 'TASK_REGISTERED=' + (remapTask != null)

                if (remapTask != null) {
                    // 产物路径：消费侧按 getMinecraftJars(NAMED) 找 jar，任务必须写到同一个文件
                    lines << 'OUTPUT_JAR=' + remapTask.outputJar.get().asFile.absolutePath
                    lines << 'EXPECTED_JAR=' + namedJar.absolutePath
                    // 输入：任何一项与配置期判据不一致，产出的都是另一个 jar
                    lines << 'FROM_NAMESPACE=' + remapTask.fromNamespace.get()
                    lines << 'TO_NAMESPACE=' + remapTask.toNamespace.get()
                    lines << 'COPY_ONLY=' + remapTask.copyOnly.get()
                    lines << 'CLIENT_VISITOR=' + remapTask.injectClientSidedVisitor.get()
                    lines << 'MIXIN_EXTENSION=' + remapTask.injectMixinExtension.get()
                    lines << 'FIX_RECORDS=' + remapTask.fixRecords.get()
                    lines << 'FORGE_LIKE=' + remapTask.forgeLike.get()
                    lines << 'VALIDATE_TARGET_NS=' + remapTask.validateTargetNamespace.get()
                    lines << 'INNER_CLASS_COUNT=' + remapTask.innerClassNames.get().size()
                }

                // 登记给消费侧的文件集合是否携带产出任务（裸 Path 不会携带）
                def collection = loomExt.getMinecraftJarsCollection(mcNs.NAMED)
                lines << 'COLLECTION_TASKS=' + collection.buildDependencies.getDependencies(null).collect { it.name }.sort().join(',')

                // 编译期配置的依赖是否携带产出任务（这正是冷缓存下 compileJava 能过的前提）。
                // 这里查的是配置自身的 build dependencies：它由配置里的各个依赖（含 FileCollectionDependency）
                // 的 build dependencies 汇总而来，因此「任务在不在里面」就等价于「消费方有没有拿到任务依赖」。
                def namedCompile = project.configurations.getByName('minecraftNamedCompile')
                lines << 'CLASSPATH_DEP_KIND=' + namedCompile.allDependencies
                        .collect { it.class.simpleName }.sort().join(',')
                lines << 'CLASSPATH_DEPS=' + namedCompile.allDependencies.size()
                lines << 'CLASSPATH_TASKS=' + namedCompile.buildDependencies.getDependencies(null)
                        .collect { task -> task.name }.unique().sort().join(',')

                reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
            }

            tasks.register('printWiringReport') {
                def report = reportFile
                doLast {
                    println "WIRING_REPORT=" + report.absolutePath
                }
            }
            '''

		// 真实源码：让 compileJava 真的解析并编译 against 那个 jar，而不是 NO-SOURCE 跳过
		def source = new File(gradle.projectDir, "src/main/java/com/example/Example.java")
		source.parentFile.mkdirs()
		source.text = "package com.example;\n\npublic class Example {\n\tpublic static final String NAME = \"example\";\n}\n"

		when: "跑一次 build，产出任务必须出现在任务图里并被消费"
		def build = gradle.run(task: "build")
		def report = gradle.run(tasks: [
			REMAP_TASK,
			"printWiringReport"
		])
		def wiring = parseReport(new File(gradle.projectDir.toString(), "wiring.properties"))

		then: "loom 注册了产出任务"
		wiring.TASK_REGISTERED == "true"

		and: "任务产物路径就是消费侧看到的 named jar 路径"
		wiring.OUTPUT_JAR == wiring.EXPECTED_JAR
		new File(wiring.OUTPUT_JAR).exists()

		and: "build 的产物由该任务产出（任务真跑过，不是「注册了但没人依赖」）"
		build.task(":" + REMAP_TASK) != null
		build.task(":" + REMAP_TASK).outcome in [SUCCESS, UP_TO_DATE]
		build.task(":compileJava").outcome in [SUCCESS, UP_TO_DATE]

		and: "任务的输入与配置期判据逐项一致"
		wiring.FROM_NAMESPACE == "official"
		wiring.TO_NAMESPACE == "named"
		wiring.COPY_ONLY == "false"
		wiring.CLIENT_VISITOR == "false"
		wiring.MIXIN_EXTENSION == "false"
		wiring.FIX_RECORDS == "true"
		wiring.FORGE_LIKE == "false"
		wiring.VALIDATE_TARGET_NS == "true"
		wiring.INNER_CLASS_COUNT == "0"

		and: "登记给消费侧的文件集合携带产出任务"
		wiring.COLLECTION_TASKS.split(",").toList().contains(REMAP_TASK)

		and: "编译期配置的依赖是携带任务依赖的文件集合，且确实带上产出任务"
		wiring.CLASSPATH_DEP_KIND.contains("FileCollectionDependency")
		wiring.CLASSPATH_TASKS.split(",").toList().contains(REMAP_TASK)

		and: "单独跑 compileJava 时产出任务也在任务图里（冷缓存下 javac 不会读到不存在的 jar）"
		def compile = gradle.run(task: "compileJava")
		compile.task(":" + REMAP_TASK) != null
		compile.task(":compileJava").outcome in [SUCCESS, UP_TO_DATE]
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

	/**
	 * 证明 jar processor 链（{@code ProcessMinecraftJarTask} + 补出 pom/backup 的伴随任务）也真的被接上.
	 *
	 * <p>为什么必须单独一条：上一条用例里 jar processor 链是**空的**（没有任何 processor 的 spec），
	 * loom 会直接把 named provider 当作最终 provider，{@code ProcessedNamedMinecraftProvider}
	 * 根本不参与——于是「处理器链改由任务承担」这件事在测试里从未被执行过。一个 API 上无法接线的
	 * processor 链、或者伴随任务漏注册，都会在上一条用例里完全看不出来。
	 *
	 * <p>这里用 access widener 让链上至少有一环，从而把 {@code ProcessedNamedMinecraftProvider}
	 * 的整条新路径拉进任务图。
	 */
	def "jar processor 链由任务承担"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            loom.accessWidenerPath = file('src/main/resources/test.accesswidener')

            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
            }

            def reportFile = project.file('wiring.properties')

            project.afterEvaluate {
                def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
                def mcNs = net.fabricmc.loom.api.mappings.layered.MappingsNamespace
                def namedJar = new File(loomExt.getMinecraftJars(mcNs.NAMED)[0].toString())
                def remapTask = project.tasks.findByName('remapMinecraftNamedMerged')
                def processTask = project.tasks.findByName('processMinecraftNamedMerged')
                def sidecarTask = project.tasks.findByName('writeMinecraftJarSidecarsMerged')
                def lines = []

                lines << 'NAMED_JAR=' + namedJar.absolutePath
                lines << 'REMAP_TASK_REGISTERED=' + (remapTask != null)
                lines << 'PROCESS_TASK_REGISTERED=' + (processTask != null)
                lines << 'SIDECAR_TASK_REGISTERED=' + (sidecarTask != null)

                if (remapTask != null) {
                    lines << 'REMAP_OUTPUT=' + remapTask.outputJar.get().asFile.absolutePath
                }

                if (processTask != null) {
                    lines << 'PROCESS_INPUT=' + processTask.inputJar.get().asFile.absolutePath
                    lines << 'PROCESS_OUTPUT=' + processTask.outputJar.get().asFile.absolutePath
                    lines << 'PROCESSOR_COUNT=' + processTask.processorDescriptors.get().size()
                }

                def collection = loomExt.getMinecraftJarsCollection(mcNs.NAMED)
                lines << 'COLLECTION_TASKS=' + collection.buildDependencies.getDependencies(null).collect { it.name }.sort().join(',')

                reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
            }
            '''

		def aw = new File(gradle.projectDir, "src/main/resources/test.accesswidener")
		aw.parentFile.mkdirs()
		aw.text = "accessWidener\tv2\tnamed\n"

		def source = new File(gradle.projectDir, "src/main/java/com/example/Example.java")
		source.parentFile.mkdirs()
		source.text = "package com.example;\n\npublic class Example {\n\tpublic static final String NAME = \"example\";\n}\n"

		when:
		def build = gradle.run(task: "build")
		def wiring = parseReport(new File(gradle.projectDir.toString(), "wiring.properties"))

		then: "处理链的任务与伴随任务都被注册"
		wiring.REMAP_TASK_REGISTERED == "true"
		wiring.PROCESS_TASK_REGISTERED == "true"
		wiring.SIDECAR_TASK_REGISTERED == "true"
		wiring.PROCESSOR_COUNT.toInteger() > 0

		and: "处理任务的输入就是重映射任务的产物（链是接上的，不是两个独立产物）"
		wiring.PROCESS_INPUT == wiring.REMAP_OUTPUT

		and: "消费侧看到的 named jar 是处理后的产物，不是父 provider 的产物"
		wiring.PROCESS_OUTPUT == wiring.NAMED_JAR
		wiring.REMAP_OUTPUT != wiring.NAMED_JAR

		and: "登记给消费侧的文件集合携带处理任务（覆盖了父 provider 的登记）"
		wiring.COLLECTION_TASKS.split(",").toList().contains("processMinecraftNamedMerged")

		and: "build 真的把这两步都跑过，且构件目录里的三个文件都就位"
		build.task(":remapMinecraftNamedMerged").outcome in [SUCCESS, UP_TO_DATE]
		build.task(":processMinecraftNamedMerged").outcome == SUCCESS
		build.task(":writeMinecraftJarSidecarsMerged").outcome == SUCCESS
		new File(wiring.NAMED_JAR).exists()
		new File(wiring.NAMED_JAR + ".backup").exists()
		new File(wiring.NAMED_JAR.replaceFirst(/\.jar$/, ".pom")).exists()
	}
}
