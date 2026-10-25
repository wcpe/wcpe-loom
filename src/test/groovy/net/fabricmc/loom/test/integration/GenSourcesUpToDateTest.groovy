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

package net.fabricmc.loom.test.integration

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 守护 {@code genSources} 在连续构建间能判定为最新.
 *
 * <p>{@code SourceMappingsService} 的 {@code mappings} / {@code processorHash} 都是
 * {@code @Input(File)} / {@code @Input}，Gradle 要在任务执行前拿它们做指纹。早期实现把它们
 * 与「生成 mappings 文件」这个动作一起延迟求值，于是快照时二者恒为空 → 指纹不稳定 →
 * 任务每轮重跑，用户看到的就是 genSources 永远不 UP-TO-DATE。
 *
 * <p>这里刻意跑两次并断言第二次为 {@link org.gradle.testkit.runner.TaskOutcome#UP_TO_DATE}：
 * 只断言「能跑成功」是抓不到那个缺陷的，因为重跑也能成功，只是慢。
 *
 * <p>同时开启配置缓存：mappings 的路径求值依赖 {@code MinecraftJarProcessorManager}，
 * 而那套东西持有 {@code Project}。断言第二次构建复用配置缓存条目，可以顺带守住
 * 「求值只发生在配置期」这个前提——一旦有人让它挪到执行期，会以
 * {@code InvalidUserCodeException} 的形式在此失败。
 *
 * <p><b>历史根因（已修复，本用例即为回归网）。</b>曾经 {@code genSources} 第二次构建不是
 * UP-TO-DATE，用 {@code --info} 抓到的是这条链：
 * <pre>
 *   Task ':processMinecraftNamedMerged' is not up-to-date because:
 *     Output property 'outputJar' ... minecraft-merged-&lt;mc&gt;-&lt;mappings&gt;.jar has changed.
 *   Task ':genSourcesWithCfr' is not up-to-date because:
 *     Output property 'classesOutputJar' ... (同一个 jar) has changed.
 * </pre>
 * 即 {@code GenerateSourcesTask} 把 {@code classesOutputJar} 声明成 named MC jar 本体，
 * 而那个路径已是 {@code processMinecraft*} 的 {@code outputJar}——一条路径两个生产者。
 * {@code applyLineNumbers} 每轮把带行号的 jar 写回本体，于是两者互相判定对方改写了产物，
 * 陷入永久重跑。修复方式：{@code classesOutputJar} 改为独占的兄弟路径（{@code .linenumbers}
 * 后缀），不再写回 MC jar 本体。
 *
 * <p>另有一处相关的可复现性修复：重映射产物原先带「当前时刻」的 ZIP 条目时间戳，
 * 使输出字节每轮都变（同样表现为 {@code outputJar has changed}）；现已在落位前归一时戳。
 */
class GenSourcesUpToDateTest extends Specification implements GradleProjectTestTrait {
	def "genSources is up to date on the second build"() {
		setup:
		def gradle = setupProject()

		when: "首次构建：产出源码"
		def first = gradle.run(tasks: ["genSources"], args: ["--configuration-cache"])

		then:
		first.task(":genSources").outcome == SUCCESS
		first.output.contains("Configuration cache entry stored")

		when: "第二次构建：输入没有变化"
		def second = gradle.run(tasks: ["genSources"], args: ["--configuration-cache"])

		then: "必须判定为最新，而不是又跑一遍（这正是映射指纹不稳定的旧缺陷）"
		second.task(":genSources").outcome == UP_TO_DATE

		and: "配置缓存条目被复用，说明求值确实只发生在配置期"
		second.output.contains("Reusing configuration cache")
	}

	def "genSources reruns when the project changes the mappings"() {
		setup:
		def gradle = setupProject()

		when:
		def first = gradle.run(tasks: ["genSources"], args: ["--configuration-cache"])

		then:
		first.task(":genSources").outcome == SUCCESS

		when: "换一套映射，产物必须重建"
		def buildFile = new File(gradle.projectDir, "build.gradle")
		buildFile.text = buildFile.text.replace(
				'mappings "net.fabricmc:yarn:1.18.1+build.18:v2"',
				'mappings "net.fabricmc:yarn:1.18.1+build.20:v2"')
		def second = gradle.run(tasks: ["genSources"], args: ["--configuration-cache"])

		then: "映射变化必须让任务失效，不能因为缓存键漏项而错误复用旧源码"
		second.task(":genSources").outcome == SUCCESS
	}

	/** minimalBase 不含依赖声明，按仓库惯例在测试里补上。 */
	private GradleProjectTestTrait.GradleProject setupProject() {
		def gradle = gradleProject(project: "minimalBase", version: DEFAULT_GRADLE)
		gradle.buildGradle << '''
			dependencies {
				minecraft "com.mojang:minecraft:1.18.1"
				mappings "net.fabricmc:yarn:1.18.1+build.18:v2"
			}
		'''.stripIndent()
		return gradle
	}
}
