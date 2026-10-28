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

import java.util.concurrent.TimeUnit

import spock.lang.Specification
import spock.lang.Timeout

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 并行配置下的跨项目数据读取不得死锁.
 *
 * <h2>曾经的事故</h2>
 * {@code CompileConfiguration.run()} 曾把整个 {@code setupMinecraft()} 包在一把经系统属性共享、
 * 跨 classloader 的 {@code String} monitor 里（{@code getGlobalLockObject()}）。而 {@code setupMinecraft}
 * 内部会回到 Gradle 的项目配置机制：
 *
 * <pre>
 * MinecraftJarProcessorManager.create → RemappedSpecContext.create → getDependentMods
 *   → LoomProjectData.fromDependency → configuration.getSingleFile()
 *   → Gradle ensureConfigured → DefaultSynchronizer.takeOwnership（阻塞，要求全部 worker lease 归还）
 * </pre>
 *
 * <p>其余配置线程此时正各自持着 worker lease、BLOCKED 在那把 monitor 上。锁序反转成立，
 * daemon 永久卡死：实测下游工程（7 个 Loom 子项目）两个 daemon 各 7 个线程全部停在
 * {@code CompileConfiguration.java:161}，{@code jstack} 显示 6 个 {@code BLOCKED (on object monitor)} +
 * 1 个持锁线程在 {@code Configuration.resolve()} 里等 {@code takeOwnership}，永不推进。
 *
 * <h2>为什么这个用例必须自己带超时</h2>
 * 死锁的表现不是报错而是**不动**：TestKit 的 {@code build()} 会一直等下去，CI 上表现为作业拖满
 * timeout 被强杀，看不到任何失败原因。因此本用例用 {@link Timeout} 把“不推进”转成失败。
 *
 * <p>同样重要的还有判据：不能只看“任务成功”——死锁时任务根本没有跑，报错也不会出现。所以断言落在
 * **两个子项目的配置都真实完成**（各自产出 {@code project-data.json}，即配置期收尾并执行了导出任务）。
 */
class ParallelProjectDataDeadlockTest extends Specification implements GradleProjectTestTrait {
	/** 首次构建要下载 MC 产物，宽松一些；比对目的是“没有卡死”而不是“多快”。 */
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	def "并行配置下子项目间读取跨项目数据不会死锁"() {
		setup:
		def gradle = gradleProject(project: "parallelProjectData", version: "9.5.0")
		gradle.buildSrc("loomClasspath")

		// 两个子项目的 exportLoomProjectData 都跑过，才说明两边的 CompileConfiguration 都走到了
		// 收尾（而不是卡在某把锁上）。这里显式指定两个项目的任务，确保两者都要被配置。
		def tasks = [
			":core:exportLoomProjectData",
			":consumer:exportLoomProjectData",
		]

		when:
		def result = gradle.run(tasks: tasks)

		then: "两个项目都配置并执行完毕——死锁时一个也回不来"
		result.task(":core:exportLoomProjectData")?.outcome != null
		result.task(":consumer:exportLoomProjectData")?.outcome != null

		and: "各自把跨项目数据落了盘"
		new File(gradle.projectDir, "core/build/loom/project-data.json").isFile()
		new File(gradle.projectDir, "consumer/build/loom/project-data.json").isFile()

		when: "暖构建再来一次，确认不会重新卡住"
		def warm = gradle.run(tasks: tasks)

		then: "暖构建下 project-data.json 已存在，按约定路径探测就能免掉跨项目求值"
		warm.task(":core:exportLoomProjectData").outcome in [
			UP_TO_DATE,
			org.gradle.testkit.runner.TaskOutcome.SUCCESS
		]
		warm.task(":consumer:exportLoomProjectData").outcome in [
			UP_TO_DATE,
			org.gradle.testkit.runner.TaskOutcome.SUCCESS
		]
	}
}
