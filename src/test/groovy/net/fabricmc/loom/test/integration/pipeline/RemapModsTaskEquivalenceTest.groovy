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

import java.util.zip.ZipFile

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * {@code RemapModsTask} 的重映射 classpath 等价性：**覆写传播**必须仍然发生.
 *
 * <p>背景（{@code 6d22a088}）：{@code ModProcessor} 迁到 {@code RemapModsTask} 时漏了一路 classpath。
 * 旧实现在配置期显式 {@code readClassPath(extension.getMinecraftJars(productionNamespace))}，新任务只从
 * 各 remap 配置的源文件收集，MC jar 不在其中。此时重映射器解析不到 MC 类型层级，**覆写继承自 MC 类/接口
 * 的方法名不再被重命名**——方法名留在源命名空间（如 {@code method_25931}），而目标命名空间要的是
 * {@code reload}。
 *
 * <p>这个缺陷危险的地方在于它**不报错**：任务照旧 SUCCESS，产物照旧写出，只有消费方编译时才以
 * 「未实现抽象成员」暴露（或者更糟——静默地不再被调用）。因此本用例的断言刻意**不看任务是否成功**，
 * 而是读产物字节码里的方法名；「任务跑通了」正是当初让这个 bug 漏出去的那个判据。
 *
 * <h2>载体：fabric-api 的 {@code SimpleResourceReloadListener}</h2>
 * 该接口的覆写链是
 * {@code SimpleResourceReloadListener → IdentifiableResourceReloadListener}（mod jar 内）
 * {@code → net.minecraft.class_3302}（{@code PreparableReloadListener}，**不在** mod jar 内）。
 * 它自己声明了 {@code method_25931}（Yarn 里的 {@code reload}）作覆写，而映射表里这条方法挂在
 * {@code class_3302} 上。要把它改名，重映射器必须能沿类型层级从 mod 类走到 {@code class_3302}，
 * 也就是必须有 MC jar 在 classpath 上。这正是被漏掉的那一路，也正是本仓库既有 MC jar 侧等价性测试
 * （{@code RemapMinecraftTaskEquivalenceTest} 等）覆盖不到的一侧：**mod jar 没有对照测试**。
 *
 * <p>用真实模块而不是自建 jar：这份覆写链是真实工程里被抓到的那一例（见 {@code 6d22a088} 的提交信息），
 * 而且它自带真实的多层接口继承，自建的桩要多写几层才能等价。模块坐标与 MC 版本对上即可，
 * 该模块的 pom 没有依赖，不会拖进整棵 fabric-api 依赖树。
 *
 * <h2>断言口径</h2>
 * <ul>
 *   <li>**输入** jar 里这个方法名是 {@code method_25931}（源命名空间）——先钉住「本来完全不同名」，
 *       否则产物里出现 {@code reload} 可能只是它原本就叫这个，本用例就成了假通过；</li>
 *   <li>**产出** jar 里它是 {@code reload}（目标命名空间），且不再有 {@code method_25931}。</li>
 * </ul>
 *
 * <p>产出路径不从约定推算，而是由构建脚本把 {@code RemapModsTask} 的 {@code ModSpec} 原样报出来
 * （与 {@code SplitModDependencyProducerTest} 同一手法）：推算出来的路径只能证明测试自己算得对。
 * 任务名单为空会直接让用例失败——那说明 mod 压根没被任务重映射，本用例也就没有验证到任何东西。
 */
class RemapModsTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 覆写链的末端：mod jar 里这个接口自己声明了从 {@code class_3302} 继承来的 {@code reload}. */
	private static final String CLASS_ENTRY = "net/fabricmc/fabric/api/resource/SimpleResourceReloadListener.class"
	/** 源命名空间（intermediary）里的 {@code reload}. */
	private static final String SOURCE_NAME = "method_25931"
	/** 目标命名空间（named / Yarn）里的 {@code reload}. */
	private static final String TARGET_NAME = "reload"

	def "mod 覆写继承自 MC 的方法名按目标命名空间重命名"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.4'
                mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
                // 被验证的 mod：SimpleResourceReloadListener 的 reload 是从 class_3302 继承来的覆写
                modImplementation 'net.fabricmc.fabric-api:fabric-resource-loader-v0:0.11.18+b66dcf784f'
            }

            def reportFile = project.file('remap-mods-report.properties')

            project.afterEvaluate {
                def lines = []

                project.tasks.withType(net.fabricmc.loom.pipeline.RemapModsTask).each { task ->
                    lines << 'REMAP_TASK=' + task.path

                    task.mods.get().each { mod ->
                        lines << 'MOD_INPUT=' + mod.getInputJar().get().asFile.absolutePath
                        lines << 'MOD_OUTPUT=' + mod.getOutputJar().get().asFile.absolutePath
                    }
                }

                reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
            }
            '''

		when: "跑一次构建，让 RemapModsTask 产出重映射后的 mod jar"
		def build = gradle.run(task: "build")

		then: "构建成功（这一条**不是**本用例的判据，只是排除构建本身失败）"
		build.task(":build").outcome in [SUCCESS, UP_TO_DATE]

		and: "确实有 RemapModsTask 处理了这个 mod，且它报了产出路径"
		def report = new File(gradle.projectDir.toString(), "remap-mods-report.properties")
		report.exists()
		def lines = report.readLines()
		def inputs = paths(lines, "MOD_INPUT=")
		def outputs = paths(lines, "MOD_OUTPUT=")
		lines.any { it.startsWith("REMAP_TASK=") }
		inputs.any { modJar(it) }
		outputs.any { modJar(it) }

		and: "输入 jar 里覆写方法名还在源命名空间——先钉住「不是本来就叫 reload」"
		def inputJar = inputs.find { modJar(it) }
		def inputMethods = declaredMethods(inputJar, CLASS_ENTRY)
		inputMethods.contains(SOURCE_NAME)
		!inputMethods.contains(TARGET_NAME)

		and: "产出 jar 里同一个方法名跟着目标命名空间走了（覆写传播没有丢）"
		def outputJar = outputs.find { modJar(it) }
		outputJar.exists()
		def outputMethods = declaredMethods(outputJar, CLASS_ENTRY)
		outputMethods.contains(TARGET_NAME)
		!outputMethods.contains(SOURCE_NAME)
	}

	/** {@return 报告里形如 {@code PREFIX<path>} 的那些路径}. */
	private static List<File> paths(List<String> lines, String prefix) {
		return lines.findAll { it.startsWith(prefix) }.collect { new File(it.substring(prefix.length()).trim()) }
	}

	/** {@return 该路径是不是我们要验的那条 mod 产物}. */
	private static boolean modJar(File file) {
		return file.name.startsWith("fabric-resource-loader-v0")
	}

	/** {@return jar 内某个 class 条目自己声明的方法名}. */
	private static List<String> declaredMethods(File jar, String entryName) {
		byte[] bytecode = null

		new ZipFile(jar).withCloseable { zip ->
			def entry = zip.getEntry(entryName)

			if (entry != null) {
				zip.getInputStream(entry).withCloseable { input ->
					bytecode = input.readAllBytes()
				}
			}
		}

		assert bytecode != null: "jar ${jar.absolutePath} 里没有 ${entryName}"

		def node = new ClassNode()
		new ClassReader(bytecode).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
		return node.methods.collect { it.name }
	}
}
