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

package net.fabricmc.loom.configuration.ide.idea

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * 覆盖「IDE 下载源码钩子」在隔离项目模式下的构建级登记表。
 *
 * <p>测试与被测类同包，因为 {@code register} / {@code taskPathsFor} 是包级私有：这里的可见性正是
 * 「只有 Loom 内部（同一包）才能写登记表」的约束，不应为了测试放宽。
 */
class IdeaDownloadSourcesRegistryTest extends Specification {
	// 形如 net.minecraft:<jar 名>:<MC 版本>-<mappings 标识>:sources，版本部分由 mappings 决定。
	private static final String MERGED_SOURCES = "net.minecraft:minecraft-merged:1.20.4-official:sources"
	private static final String SERVER_SOURCES = "net.minecraft:minecraft-server:1.20.4-official:sources"
	private static final String CLIENT_SOURCES = "net.minecraft:minecraft-client:1.20.4-official:sources"

	def "同一坐标会收集所有项目登记的任务路径"() {
		given:
		def registry = new IdeaDownloadSourcesRegistry()

		when: "根项目与两个子项目都用了同一份 Minecraft 坐标"
		registry.register(MERGED_SOURCES, ":genSources")
		registry.register(MERGED_SOURCES, ":one:genSources")
		registry.register(MERGED_SOURCES, ":two:genSources")

		then:
		registry.taskPathsFor(MERGED_SOURCES) == [
			":genSources",
			":one:genSources",
			":two:genSources"
		]
	}

	def "重复登记同一任务路径只保留一份"() {
		given:
		def registry = new IdeaDownloadSourcesRegistry()

		when: "同一个项目/同一坐标被重复登记"
		registry.register(MERGED_SOURCES, ":genSources")
		registry.register(MERGED_SOURCES, ":genSources")

		then:
		registry.taskPathsFor(MERGED_SOURCES) == [":genSources"]
	}

	def "不同坐标之间的登记互相隔离"() {
		given:
		def registry = new IdeaDownloadSourcesRegistry()

		when:
		registry.register(MERGED_SOURCES, ":genSources")
		registry.register(SERVER_SOURCES, ":genSourcesWithSources")

		then:
		registry.taskPathsFor(MERGED_SOURCES) == [":genSources"]
		registry.taskPathsFor(SERVER_SOURCES) == [":genSourcesWithSources"]

		and: "没有被任何项目登记过的坐标查不到任务路径"
		registry.taskPathsFor(CLIENT_SOURCES).isEmpty()
	}

	def "同一构建内的所有项目共享同一份登记表"() {
		given: "登记表挂在 Gradle 作用域上，因此子项目写的条目根项目也能看到"
		def rootProject = ProjectBuilder.builder().withName("root").build()
		def subProject = ProjectBuilder.builder().withName("sub").withParent(rootProject).build()

		when:
		def fromRoot = IdeaDownloadSourcesRegistry.get(rootProject)
		def fromSub = IdeaDownloadSourcesRegistry.get(subProject)

		then:
		fromRoot.is(fromSub)

		and:
		fromSub.register(MERGED_SOURCES, ":sub:genSources")
		fromRoot.taskPathsFor(MERGED_SOURCES) == [":sub:genSources"]
	}

	def "不同构建之间不共享登记表"() {
		given:
		def firstBuildProject = ProjectBuilder.builder().withName("first").build()
		def secondBuildProject = ProjectBuilder.builder().withName("second").build()

		when:
		def firstRegistry = IdeaDownloadSourcesRegistry.get(firstBuildProject)
		def secondRegistry = IdeaDownloadSourcesRegistry.get(secondBuildProject)

		then:
		!firstRegistry.is(secondRegistry)

		and: "一个构建里的登记不会泄漏到另一个构建"
		firstRegistry.register(MERGED_SOURCES, ":genSources")
		secondRegistry.taskPathsFor(MERGED_SOURCES).isEmpty()
	}
}
