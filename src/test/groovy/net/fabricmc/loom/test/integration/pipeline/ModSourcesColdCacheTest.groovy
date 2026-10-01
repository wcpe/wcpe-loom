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

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.SourceRemapper

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 配置期 mod 源码重映射在**冷缓存**下的行为.
 *
 * <p>背景：MC jar 的生产已从配置期搬到任务（{@code RemapMinecraftTask} /
 * {@code ProcessMinecraftJarTask}），因此配置期读不到它们的**内容**。而配置期的 mod 源码重映射
 * （{@code SourceRemapper}，由 IDE 同步或 {@code -Dfabric.loom.remapSources=true} 触发）需要把
 * MC jar 放进 Mercury 的 classpath——原来的实现用 {@code Files.isRegularFile} 过滤 classpath，
 * 冷缓存下会把 MC 这一整块**静默丢掉**，于是重映射在缺 classpath 的情况下照样跑完，
 * 结果还会被写进 remapped mods 缓存并被永久复用。
 *
 * <p>本用例固定这条契约（日志标记直接引用 {@link SourceRemapper#SKIPPED_LOG_MARKER}，
 * 不在这里另抄一份字面量）：
 * <ol>
 *   <li>冷缓存（named MC jar 不在磁盘上）→ 整批跳过、**不写任何产物**、并在日志里说明原因；</li>
 *   <li>产物就位后 → 同一条路径照常产出可读的源码包（跳过不是「把功能关掉」）。</li>
 * </ol>
 *
 * <h2>冷缓存是真的冷，不是「删出来的」</h2>
 * 本用例用独立的临时 {@code --gradle-user-home}：Loom 的全局产物仓库（{@code caches/fabric-loom}）
 * 与模块缓存都从空开始，因此第一次构建就是冷缓存，不必去删共享缓存里的文件——那种删法会破坏
 * 同一台机器上并发运行的其它构建。代价是首次运行要重新下载 MC 与映射；换来的是可重复、可并行的用例。
 *
 * <h2>为什么要在冷跑之前先建出 remapped_working 目录</h2>
 * 目标目录存在与否决定了旧实现的失效方式是「静默」还是「报错」：目录存在时，缺 classpath 的重映射
 * 会正常跑完并写下错误产物（这正是本用例要挡住的静默路径）；目录不存在时它反而会因为 zipfs
 * 建不了文件而抛 NoSuchFileException。用例因此显式建出该目录，让断言落在**静默**那条路径上；
 * 最后一次构建前再把目录删掉，顺带覆盖「目标目录不存在时也要能产出」（父目录自建）。
 *
 * <h2>为什么全程关闭配置缓存</h2>
 * 配置缓存命中时整个配置阶段都不会执行，「配置期是否跳过」就无从观察；本用例验证的正是配置阶段的
 * 行为，因此每次都显式 {@code configurationCache: false}。
 */
class ModSourcesColdCacheTest extends Specification implements GradleProjectTestTrait {
	private static final String JAR_MARKER = "NAMED_MC_JAR="
	private static final String WORKING_DIR = "build/loom-cache/remapped_working"

	def "冷缓存下配置期 mod 源码重映射跳过而不是用不完整的 classpath 产出结果"() {
		setup:
		// 临时 gradle home：全局 mc 产物仓库为空 = 真实的冷缓存
		def gradle = gradleProject(project: "minimalBase", gradleHomeDir: File.createTempDir())
		// 打开配置期 mod 源码加工（默认只在 IDE 同步时开启）
		gradle.gradleProperties << "systemProp.fabric.loom.remapSources=true\n"
		gradle.buildGradle << """
			dependencies {
				minecraft 'com.mojang:minecraft:1.20.4'
				mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
				// 带 sources 的 mod 依赖：只有 sources 存在时配置期才会安排重映射。
				// 该模块的源码里有真实的 MC 类型引用（class_2960 等），不是空源码包。
				modImplementation 'net.fabricmc.fabric-api:fabric-api-base:0.4.36+78d798af4f'
			}

			def namedMinecraftJars = []

			project.afterEvaluate {
				namedMinecraftJars = net.fabricmc.loom.LoomGradleExtension.get(project)
						.getMinecraftJars(net.fabricmc.loom.api.mappings.layered.MappingsNamespace.NAMED)
						.collect { it.toString() }
			}

			tasks.register('printNamedMinecraftJars') {
				doLast {
					namedMinecraftJars.each { println '${JAR_MARKER}' + it }
				}
			}

			// MC jar 的生产任务：冷缓存下这些产物由任务在生产期落位。
			// 按类型取而不是按任务名硬编：Mercury 的 classpath 同时需要生产命名空间
			// （Fabric 下是 intermediary）与 named 两侧的 jar，任务名会随 provider 形态变化。
			tasks.register('produceMinecraftJars') {
				dependsOn(project.provider {
					project.tasks.withType(net.fabricmc.loom.pipeline.RemapMinecraftTask).names +
							project.tasks.withType(net.fabricmc.loom.pipeline.ProcessMinecraftJarTask).names
				})
			}
		"""
		// 目标目录先建出来：让旧实现的失效方式是「静默写下错误产物」而不是「建不了文件而报错」
		new File(gradle.projectDir, WORKING_DIR).mkdirs()

		when: "冷缓存下（MC 产物尚未生产）做一次只配置、不生产的构建"
		def cold = gradle.run(task: "printNamedMinecraftJars", configurationCache: false)
		def minecraftJars = namedJars(cold.output)

		then: "探针拿到了 named MC jar 的路径，且这些产物此刻确实不存在"
		!minecraftJars.isEmpty()
		minecraftJars.every { !it.exists() }

		and: "明确跳过并说明原因，且一个 mod 源码产物都不写"
		cold.output.contains(SourceRemapper.SKIPPED_LOG_MARKER)
		modSourcesOutputs(gradle.projectDir).isEmpty()

		when: "让产出任务把 MC jar 生产出来"
		def produce = gradle.run(task: "produceMinecraftJars", configurationCache: false)

		then: "产出任务成功，本次配置仍然跳过（配置期先于任务执行）"
		produce.task(":produceMinecraftJars").outcome == SUCCESS
		produce.output.contains(SourceRemapper.SKIPPED_LOG_MARKER)

		and: "产物已就位"
		minecraftJars.every { it.exists() }

		when: "把工作目录删掉（覆盖「目标目录不存在时也要能产出」），再做一次相同的构建"
		deleteQuietly(new File(gradle.projectDir, WORKING_DIR))
		def warm = gradle.run(task: "printNamedMinecraftJars", configurationCache: false)
		def remappedSources = modSourcesOutputs(gradle.projectDir)

		then: "这次不再跳过，并且写出了一个可读的源码包（跳过不等于把功能关掉）"
		!warm.output.contains(SourceRemapper.SKIPPED_LOG_MARKER)
		!remappedSources.isEmpty()
		remappedSources.every { javaEntryCount(it) > 0 }
	}

	/** 从构建输出里取出 named MC jar 的路径. */
	private static List<File> namedJars(String output) {
		return output.readLines()
				.findAll { it.startsWith(JAR_MARKER) }
				.collect { new File(it.substring(JAR_MARKER.length()).trim()) }
	}

	/** 找出项目里所有 remapped mod 源码产物（working 文件与 maven 缓存里的 sources 分类器）. */
	private static List<File> modSourcesOutputs(File projectDir) {
		final List<File> results = []
		final List<File> roots = [
			new File(projectDir, WORKING_DIR),
			new File(projectDir, ".gradle/loom-cache/remapped_mods")
		]

		roots.each { root ->
			if (root.exists()) {
				root.eachFileRecurse { file ->
					if (file.isFile() && file.name.endsWith("-sources.jar")) {
						results << file
					}
				}
			}
		}

		return results
	}

	private static void deleteQuietly(File file) {
		if (file.exists() && !file.deleteDir()) {
			throw new IOException("Failed to delete " + file)
		}
	}

	/** 统计 jar 里的 .java 条目数，顺带验证它是可读的 zip. */
	private static int javaEntryCount(File jar) {
		int count = 0

		new ZipFile(jar).withCloseable { zip ->
			for (def entry : zip.entries()) {
				if (entry.name.endsWith(".java")) {
					count++
				}
			}
		}

		return count
	}
}
