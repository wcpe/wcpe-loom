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

package net.fabricmc.loom.internal

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.gradle.api.plugins.ExtensionContainer
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * 覆盖「构建级共享数据」在跨 classloader 与并行配置下的注册语义。
 *
 * <p>跨 classloader 无法在单测里真的造出第二份 Loom，因此用两类等价手段：
 * 一是往构建级容器里塞一个同名外来对象（对类型身份判据而言与「另一份 Loom 的实例」完全等价，
 * {@code findByType} 一样会判 null）；二是用一个假扩展容器模拟「另一份 classloader 恰好抢在
 * 查名与注册之间完成注册」这一无法稳定复现的竞态窗口。
 */
class LoomGradleSharedDataTest extends Specification {
	def "构建级名字被外来对象占用时不抛异常，且多次调用返回同一实例"() {
		given: "构建级扩展名已被另一个 classloader 的对象占用"
		def project = ProjectBuilder.builder().withName("root").build()
		def foreign = new Object()
		project.getGradle().getExtensions().add(LoomGradleSharedData.EXTENSION_NAME, foreign)

		when: "本 classloader 取共享数据（原先会在 add 处抛 Cannot add extension with name 'loomSharedData'）"
		def first = LoomGradleSharedData.get(project)
		def second = LoomGradleSharedData.get(project)

		then: "不抛异常，且多次调用拿到同一份实例"
		first != null
		second.is(first)

		and: "外来对象仍然占着构建级名字，本实例没有被塞进构建级容器"
		project.getGradle().getExtensions().getByName(LoomGradleSharedData.EXTENSION_NAME).is(foreign)

		and: "本 classloader 的实例挂在项目级扩展上，项目内取到的就是同一份"
		project.getExtensions().getByName(LoomGradleSharedData.EXTENSION_NAME).is(first)
	}

	def "不同项目共享同一份构建级实例"() {
		given:
		def rootProject = ProjectBuilder.builder().withName("root").build()
		def subProject = ProjectBuilder.builder().withName("sub").withParent(rootProject).build()

		when:
		def fromRoot = LoomGradleSharedData.get(rootProject)
		def fromSub = LoomGradleSharedData.get(subProject)

		then:
		fromRoot.is(fromSub)
	}

	def "并行首次取只产生一个实例"() {
		given: "同一构建下的多个项目，模拟并行配置"
		def rootProject = ProjectBuilder.builder().withName("root").build()
		def projects = (0..<8).collect { index -> ProjectBuilder.builder().withName("sub" + index).withParent(rootProject).build() }
		// 预热：真实构建里 Gradle 对象的扩展容器在 settings 求值阶段（单线程）就已创建，而 ProjectBuilder
		// 下它是「首次访问时惰性创建」且创建过程未同步 —— 多线程同时首次访问会各自造出一个不同的空容器
		// （实测 8 线程拿到 8 个不同实例）。那是 Gradle 自身的惰性初始化竞态，不在被测范围内，
		// 故先在主线程创建一次，让并发阶段只考察被测类的互斥与幂等。
		def buildExtensions = rootProject.getGradle().getExtensions()
		def start = new CountDownLatch(1)
		def pool = Executors.newFixedThreadPool(projects.size())

		when:
		def futures = projects.collect { project ->
			pool.submit({
				start.await()
				return LoomGradleSharedData.get(project)
			} as Callable)
		}
		start.countDown()
		def results = futures.collect { it.get(60, TimeUnit.SECONDS) }
		pool.shutdown()

		then: "所有线程拿到同一份构建级实例，也没有人吃到抢名异常"
		results.every { result -> result.is(results[0]) }
		rootProject.getGradle().getExtensions().getByType(LoomGradleSharedData).is(results[0])

		and: "预热时拿到的容器就是后续所有访问看到的容器（前提不成立时这里会先失败）"
		buildExtensions.is(rootProject.getGradle().getExtensions())
	}

	def "项目级扩展只登记一次，重复登记不会覆盖已有实例"() {
		given:
		def project = ProjectBuilder.builder().withName("root").build()
		def registered = LoomGradleSharedData.get(project)

		when: "另一份数据试图重复登记"
		LoomGradleSharedData.beforeProject(project, new LoomGradleSharedData("9.9.9"))

		then:
		project.getExtensions().getByName(LoomGradleSharedData.EXTENSION_NAME).is(registered)
	}

	def "另一份 classloader 抢在查名与注册之间完成注册时降级为跳过"() {
		given: "查名时名还没被占，add 时已经被另一份 classloader 抢走"
		def winner = new Object()
		def stolen = false
		def extensions = [
			findByName: { String name -> stolen ? winner : null },
			add: { Class type, String name, Object extension ->
				// 争用窗口只存在于「查名」与「注册」之间，这里让 add 撞上已完成的抢名
				stolen = true
				throw new IllegalArgumentException("Cannot add extension with name '" + name + "'")
			}
		] as ExtensionContainer

		when:
		LoomGradleSharedData.registerSharedData(extensions)

		then: "降级为跳过注册，而不是把异常炸到配置阶段"
		noExceptionThrown()
		extensions.findByName(LoomGradleSharedData.EXTENSION_NAME).is(winner)
	}

	def "名并未被占用时的注册失败仍原样抛出"() {
		given: "add 抛出的不是抢名，而是入参非法"
		def extensions = [
			findByName: { String name -> null },
			add: { Class type, String name, Object extension ->
				throw new IllegalArgumentException("入参非法（模拟）")
			}
		] as ExtensionContainer

		when:
		LoomGradleSharedData.registerSharedData(extensions)

		then: "按名复查确认不是抢名后必须原样抛出，避免掩盖真实错误"
		IllegalArgumentException e = thrown()
		e.message == "入参非法（模拟）"
	}
}
