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

package net.fabricmc.loom.test.unit.pipeline

import java.nio.file.Path
import java.util.function.Function
import java.util.function.Supplier

import org.gradle.api.Project
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

import net.fabricmc.loom.pipeline.RemapMinecraftTask
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry

/**
 * 「同一个产物路径，同一构建内只有一个生产者任务」的登记语义.
 *
 * <p>这是把 mapped jar 的生产移交给任务图之后、Gradle 自己管不了的那一半：产物位于跨项目共享的 maven 仓库，
 * 两个配置相同的子项目会算出同一条路径。Gradle 对这种「两个任务声明同一个 {@code @OutputFile}」既不报错
 * 也不排序（实测两个任务都会执行、后完成的覆盖先完成的），因此「谁生产」必须由 loom 自己判定，
 * 而且要能区分「同一批输入的两个请求」与「同一路径上的两组不同输入」——前者共享，后者拒绝。
 *
 * <p>用 {@code ProjectBuilder} 造出同一次构建的两个项目，覆盖三件事：登记表按构建共享、同指纹复用同一个
 * 生产者（不产生第二个任务）、异指纹被拒绝且指出差异项。第三条用例单独覆盖「表必须放在跨 classloader
 * 可见的位置」：真实多模块工程里同一构建的不同项目会各带一份 Loom（约定插件/included build），
 * 表若按 classloader 分家，两边都会认为自己才是生产者——正是本次修复的那个失败形态。
 *
 * <p>后半段覆盖 {@link RemapMinecraftTaskRegistry#claimAll}（mod 线的批量生产单位）：批次与批次之间
 * 往往只是**部分重叠**（多模块工程里各模块的 mod 集合不同），因此必须逐条判定——已被别人认领的产物
 * 本任务既不写出也不声明，剩下的才归自己；整批都已认领时干脆不创建任务。这几条是「同一路径只有一个
 * 生产者」在多产物任务上的实际判据，单看单产物那几条用例覆盖不到。
 */
class RemapMinecraftTaskRegistryTest extends Specification {
	private static final String TASK_NAME = "remapMinecraftNamedMerged"

	private Project rootProject
	private Project subProject
	private Path artifact

	def setup() {
		rootProject = ProjectBuilder.builder().withName("root").build()
		subProject = ProjectBuilder.builder().withName("sub").withParent(rootProject).build()
		// 与真实落位同形：全局 maven 仓库里的一个构件 jar（绝对路径，跨项目共享）
		artifact = rootProject.projectDir.toPath()
				.resolve("minecraftMaven/net/minecraft/minecraft-merged/1.20.1-yarn/minecraft-merged-1.20.1-yarn.jar")
	}

	def "登记表按构建存放，不在 loom 自己的 classloader 里"() {
		given:
		def claims = claim(rootProject, artifact, TASK_NAME, ["inputJar": "/cache/merged.jar"])

		expect: "表存在构建级扩展属性里：只有 JDK 类型才可能被另一份 classloader 读回去"
		ExtraPropertiesExtension extras = rootProject.gradle.extensions.extraProperties
		extras.has("loomSharedArtifactProducers")

		Map<String, Map<String, String>> table = (Map<String, Map<String, String>>) extras.get("loomSharedArtifactProducers") as Map
		table.containsKey(artifact.toAbsolutePath().normalize().toString())
		table.values().every { entry ->
			entry.values().every { value ->
				value instanceof String
			}
		}

		and: "登记到的产出任务是项目内的绝对任务路径"
		claims.taskPath() == ":$TASK_NAME"
	}

	def "同一路径且输入相同时，第二个项目复用的是同一个生产者"() {
		given:
		def identity = ["inputJar": "/cache/merged.jar", "toNamespace": "named"]

		when: "两个项目各自登记同一条产物路径"
		def fromRoot = claim(rootProject, artifact, TASK_NAME, identity)
		def fromSub = claim(subProject, artifact, TASK_NAME, identity)

		then: "拿到同一条产出路径与同一个产出任务，且只存在一个任务（不会出现第二个生产者）"
		fromRoot == fromSub
		fromRoot.artifact() == fromSub.artifact()
		fromRoot.taskPath() == fromSub.taskPath()
		rootProject.getTasks().getNames().contains(TASK_NAME)
		!subProject.getTasks().getNames().contains(TASK_NAME)
	}

	def "同一路径但输入不同时拒绝共享，并指出差异项"() {
		given:
		claim(rootProject, artifact, TASK_NAME, ["inputJar": "/cache/merged.jar", "knownIndyBsms": ""])

		when: "另一个项目以不同的 knownIndyBsms 登记同一路径"
		claim(subProject, artifact, TASK_NAME, ["inputJar": "/cache/merged.jar", "knownIndyBsms": "custom/bootstrapper"])

		then: "拒绝共享：同一条路径上两组输入会互相覆盖，产物会与其中一方的配置不符"
		def error = thrown(IllegalStateException)
		error.message.contains("knownIndyBsms")
		error.message.contains("custom/bootstrapper")
		error.message.contains(":sub")
	}

	def "不同产物路径互不影响，各自创建任务"() {
		when:
		def first = claim(rootProject, artifact, TASK_NAME, ["inputJar": "/cache/merged.jar"])
		def otherArtifact = artifact.resolveSibling("minecraft-merged-1.20.2-yarn.jar")
		def second = claim(subProject, otherArtifact, "remapMinecraftNamedMergedOther", ["inputJar": "/cache/merged.jar"])

		then:
		first != second
		first.taskPath() == ":$TASK_NAME"
		second.taskPath() == ":sub:remapMinecraftNamedMergedOther"
		rootProject.getTasks().getNames().contains(TASK_NAME)
		subProject.getTasks().getNames().contains("remapMinecraftNamedMergedOther")
	}

	/** 以给定身份登记一条产物路径，登记回调里注册一个真实的任务. */
	private RemapMinecraftTaskRegistry.Producer claim(Project project, Path outputJar, String taskName, Map<String, String> identity) {
		Supplier<TaskProvider<RemapMinecraftTask>> registrar = {
			project.getTasks().register(taskName, RemapMinecraftTask)
		}

		return RemapMinecraftTaskRegistry.claim(project, outputJar, identity, registrar)
	}

	def "批量登记：已被别人认领的产物不再归本任务，剩下的才创建任务"() {
		given: "根项目先认领第一条产物（模拟「配置顺序在前」的那个项目）"
		def shared = art("shared-1.0.0.jar")
		def rootOnly = art("root-only-1.0.0.jar")
		claimBatch(rootProject, [shared, rootOnly], ["platform": "fabric"], TASK_NAME)

		when: "子项目请求一个与之部分重叠的批次：shared 已有主，extra 还没有"
		def extra = art("extra-1.0.0.jar")
		def seenByRegistrar = []
		def producers = claimBatch(subProject, [shared, extra], ["platform": "fabric"], "remapModsSub", seenByRegistrar)

		then: "本任务只认领 extra —— 写出集合与声明集合都只含这一条"
		seenByRegistrar == [
			extra.toAbsolutePath().normalize()
		]

		and: "两条产物的生产者：shared 指向根项目那个任务，extra 指向子项目新任务"
		producers[shared.toAbsolutePath().normalize()].taskPath() == ":$TASK_NAME"
		producers[extra.toAbsolutePath().normalize()].taskPath() == ":sub:remapModsSub"

		and: "根项目那条产物不会被子项目的第二个任务重复生产"
		!subProject.getTasks().getNames().contains(TASK_NAME)
	}

	def "批量登记：整批都已被认领时不创建任务，消费方直接挂到对方任务上"() {
		given:
		def first = art("a-1.0.0.jar")
		def second = art("b-1.0.0.jar")
		claimBatch(rootProject, [first, second], ["platform": "fabric"], TASK_NAME)

		when: "子项目要的整批产物都已有主"
		def seenByRegistrar = []
		def producers = claimBatch(subProject, [first, second], ["platform": "fabric"], "remapModsSub", seenByRegistrar)

		then: "没有第二个生产者，也不会有第二个任务"
		seenByRegistrar.isEmpty()
		!subProject.getTasks().getNames().contains("remapModsSub")

		and: "每条产物都指回根项目那个唯一的生产者"
		producers.values().every { it.taskPath() == ":$TASK_NAME" }
	}

	def "批量登记：同一批次里的重复路径只登记一次"() {
		when: "同一批里两次出现同一条产物（同坐标被判重两次）"
		def artifact = art("dup-1.0.0.jar")
		def producers = claimBatch(rootProject, [artifact, artifact], ["platform": "fabric"], TASK_NAME)

		then:
		producers.size() == 1
		producers[artifact.toAbsolutePath().normalize()].taskPath() == ":$TASK_NAME"
	}

	def "批量登记：同一路径但输入不同时同样拒绝，且不创建任务"() {
		given:
		def artifact = art("shared-1.0.0.jar")
		claimBatch(rootProject, [artifact], ["platform": "fabric"], TASK_NAME)

		when: "子项目以不同的平台登记同一条路径"
		claimBatch(subProject, [artifact], ["platform": "neoforge"], "remapModsSub")

		then: "拒绝共享，且失败发生在建任务之前（不会留下半个生产者）"
		def error = thrown(IllegalStateException)
		error.message.contains("platform")
		error.message.contains("neoforge")
		!subProject.getTasks().getNames().contains("remapModsSub")
	}

	/** 共享仓库里的一条产物路径. */
	private Path art(String fileName) {
		rootProject.projectDir.toPath().resolve("remapped_mods/remapped/net/fabricmc/${fileName}")
	}

	/** 以给定身份登记一批产物路径，登记回调里注册一个真实的任务，并记下回调收到的产物. */
	private Map<Path, RemapMinecraftTaskRegistry.Producer> claimBatch(Project project, List<Path> artifacts,
			Map<String, String> identity, String taskName, List<Path> seenByRegistrar = []) {
		Function<List<Path>, TaskProvider<RemapMinecraftTask>> registrar = { List<Path> owned ->
			seenByRegistrar.addAll(owned)
			project.getTasks().register(taskName, RemapMinecraftTask)
		}

		return RemapMinecraftTaskRegistry.claimAll(project, artifacts, identity, registrar)
	}
}
