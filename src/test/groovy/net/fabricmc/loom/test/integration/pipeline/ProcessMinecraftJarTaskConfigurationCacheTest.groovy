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
import net.fabricmc.loom.util.ZipUtils

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 处理任务在**配置缓存存储/复用**下能否成立.
 *
 * <h2>为什么必须有这一块</h2>
 * 既有的等价性用例（{@code ProcessMinecraftJarTaskEquivalenceTest} 等）全部显式关掉了配置缓存，
 * 因为它们要在探针里读 Gradle 项目模型；而 {@code MinecraftJarTaskWiringTest} 虽然开着配置缓存，
 * 夹具里只有**本地** access widener。两条腿各缺一半，于是「链上有**依赖 mod** 带的 access widener」
 * 与「配置缓存」这两个条件的**交集**从未被跑到过——而真实项目恰好落在交集里：
 * {@code ModAccessWidenerEntry} 曾把整个 {@code FabricModJson}（内含 Gson 的 {@code JsonObject}）
 * 塞进 spec，spec 又是 {@code ProcessMinecraftJarTask} 的 {@code @Internal} 属性，
 * 配置缓存存储时直接失败（{@code value '...' is not assignable to 'com.google.gson.internal.LinkedTreeMap'}）。
 * 单元测试抓不到它：{@code @Internal} 属性照样要序列化，而「能不能序列化」只有在真实构建里才被问到。
 *
 * <h2>为什么用 {@code problems=fail}</h2>
 * 待测项目（AllinCore）开的是 {@code org.gradle.configuration-cache.problems=fail}，
 * 存储阶段出问题即构建失败。这里用同一个开关，让「有 CC 问题」这件事在测试里就是失败，
 * 而不是靠扫输出文本——文本断言会随 Gradle 版本漂移，而这个开关是 Gradle 的正式行为。
 *
 * <h2>夹具为什么长这样</h2>
 * 依赖 mod 自带一个 intermediary 头的 access widener（与等价性用例同源）：
 * 它是 {@code ModAccessWidenerEntry} 唯一的产生方式——本地 AW 走的是
 * {@code LocalAccessWidenerEntry}（只有路径与校验和，本来就是纯值），覆盖不到本 bug。
 * jar 自带的 {@code Fabric-Loom-Remap: false} 是 loom 的既有 opt-out，用来避开 L3 那条链上
 * {@code RemapModsTask} 的既有缺陷（{@code @InputFile} 缺 {@code @PathSensitive}），它与本用例无关。
 *
 * <h2>两次运行各自证明什么</h2>
 * 第一次是**存储**：链上带 mod AW 的任务状态必须能写进配置缓存。
 * 第二次是**复用**：任务对象由缓存重建后，spec 里的 AW 内容必须仍能让链真的执行
 * （重建出空值或半截值的写法会在这里暴露，而不是在存储阶段）。
 */
class ProcessMinecraftJarTaskConfigurationCacheTest extends Specification implements GradleProjectTestTrait {
	/** 依赖 mod 自带的 access widener：intermediary 头，规则带 {@code transitive-} 前缀. */
	private static final String MOD_ACCESS_WIDENER = "accessWidener\tv2\tintermediary\n\ntransitive-accessible\tfield\tnet/minecraft/class_3797\tfield_16737\tZ\n"
	/** 依赖 mod 的 fabric.mod.json：除 id/version 外只声明那个 access widener. */
	private static final String MOD_FABRIC_MOD_JSON = '''{
  "schemaVersion": 1,
  "id": "cc-probe-dep",
  "version": "1.0.0",
  "name": "Configuration Cache Probe Dependency",
  "accessWidener": "cc-probe.accesswidener"
}
'''
	/** 依赖 mod 的 manifest：声明 loom 的既有 opt-out，让它不参与 L3 的 mod 重映射. */
	private static final String MOD_MANIFEST = "Manifest-Version: 1.0\nFabric-Loom-Remap: false\n\n"
	/** 本场景下命名 jar 的处理任务名（见 ProcessedNamedMinecraftProvider.taskName）. */
	private static final String PROCESS_TASK = "processMinecraftNamedMerged"
	/** 配置缓存存储失败时 Gradle 的固定措辞，用于把「是这次的问题」与别的失败原因分开. */
	private static final String CC_PROBLEMS_HEADER = "Configuration cache problems found in this build"

	def "配置缓存能存储并复用带依赖 mod access widener 的处理链"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
                // 依赖 mod 自带 access widener：链上因此出现 ModAccessWidenerEntry，
                // 它带着 mod 的元数据一起进任务状态（见类注释）。
                modImplementation files("dummy.jar")
            }
            '''

		def source = new File(gradle.projectDir, "src/main/java/com/example/Example.java")
		source.parentFile.mkdirs()
		source.text = "package com.example;\n\npublic class Example {\n\tpublic static final String NAME = \"example\";\n}\n"

		def modDir = new File(gradle.projectDir, "dummyDependency")
		modDir.mkdirs()
		new File(modDir, "fabric.mod.json").text = MOD_FABRIC_MOD_JSON
		new File(modDir, "cc-probe.accesswidener").text = MOD_ACCESS_WIDENER
		new File(modDir, "META-INF").mkdirs()
		new File(modDir, "META-INF/MANIFEST.MF").text = MOD_MANIFEST
		ZipUtils.pack(modDir.toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when: "第一次构建：配置缓存需要被存下来"
		def store = gradle.run(tasks: ["build"], args: [
			"--configuration-cache-problems=fail",
			"--console=plain"
		])
		def storeOutput = store.output

		then: "构建通过，且链上的处理任务真的执行了"
		store.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		store.task(":" + PROCESS_TASK).outcome in [SUCCESS, UP_TO_DATE]

		and: "配置缓存被存下，而不是带着问题失败"
		!storeOutput.contains(CC_PROBLEMS_HEADER)
		storeOutput.contains("Configuration cache entry stored")

		when: "再跑两次：第一次构建会新建 Minecraft jar，Gradle 会因此先判定「配置缓存不能复用」"
		// 这是 Gradle 对文件系统条目「由不存在变为存在」的正常失效，与本 bug 无关，
		// 所以最多重试两次，只要求其中一次真的复用了缓存。
		def reused = null

		for (int i = 0; i < 2 && reused == null; i++) {
			def run = gradle.run(tasks: ["build"], args: [
				"--configuration-cache-problems=fail",
				"--console=plain"
			])
			assert !run.output.contains(CC_PROBLEMS_HEADER): "复用时也报了配置缓存问题：${run.output.take(2000)}"

			if (run.output.contains("Configuration cache entry reused")) {
				reused = run
			}
		}

		then: "配置缓存被复用（否则每次都要重新配置，这一机制等于不成立）"
		reused != null

		and: "由缓存重建出来的任务与存储前是同一个：处理任务处于最新状态"
		// 若 spec 里的规则内容在序列化里丢掉或变了样，链的指纹就会变，任务只能重跑——这里就会看到 SUCCESS
		reused.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		reused.task(":" + PROCESS_TASK).outcome == UP_TO_DATE
	}
}
