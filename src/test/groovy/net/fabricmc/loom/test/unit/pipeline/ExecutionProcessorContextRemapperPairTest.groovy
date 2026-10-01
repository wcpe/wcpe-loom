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

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar
import net.fabricmc.loom.pipeline.ExecutionProcessorContext
import net.fabricmc.loom.pipeline.ExecutionProcessorContext.JarConfigurationKind
import net.fabricmc.loom.task.service.TinyRemapperService
import net.fabricmc.loom.util.service.ScopedServiceFactory

/**
 * 守住 {@link ExecutionProcessorContext#createRemapper} 的命名空间对校验.
 *
 * <p>执行期只装配了任务自己那一对命名空间（当前全部调用方都是「生产命名空间 → named」），
 * 请求别的对时必须**明确拒绝**而不是勉强调用。这条守卫的价值不在「报错好听」：如果它被放宽成
 * 返回一个语义不符的 remapper，处理器会拿着错的映射去解析名字，产物**静默出错**——正是本仓库
 * 已经栽过好几次的那类失效模式。故此用例刻意钉住它，防止后人「顺手放宽」。
 *
 * <p>构造上下文需要一个 {@code ServiceFactory}（构造函数对其做 requireNonNull），但校验发生在
 * **用到它之前**——这里传一个未真正使用过的 {@link ScopedServiceFactory}，用例依然只在断言「守卫
 * 不依赖任何执行期服务就能拒绝」。
 */
class ExecutionProcessorContextRemapperPairTest extends Specification {
	private static ExecutionProcessorContext contextWith(String from, String to) {
		def project = ProjectBuilder.builder().withName("pair-probe").build()
		def remapperOptions = project.objects.newInstance(TinyRemapperService.Options)
		remapperOptions.from.set(from)
		remapperOptions.to.set(to)
		def mappingsOptions = project.objects.newInstance(TinyMappingsService.Options)

		return new ExecutionProcessorContext(
				new ScopedServiceFactory(),
				JarConfigurationKind.MERGED,
				MinecraftJar.Type.MERGED,
				false,
				MappingsNamespace.OFFICIAL,
				MappingsNamespace.INTERMEDIARY,
				mappingsOptions,
				remapperOptions)
	}

	def "请求与装配不符的命名空间对时明确拒绝，且不依赖任何执行期服务"() {
		given: "上下文只装配了 official -> named"
		def context = contextWith("official", "named")

		when: "处理器请求另一对（server 侧常见的 official -> intermediary）"
		context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.INTERMEDIARY)

		then: "必须抛出，且消息把装配对与请求对都写清楚，便于定位"
		def e = thrown(IllegalStateException)
		e.message.contains("official -> named")
		e.message.contains("official -> intermediary")
	}

	def "只对不上一半也要拒绝（不能只看 from 或只看 to）"() {
		given:
		def context = contextWith("official", "named")

		when: "from 对得上、to 对不上"
		context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.SRG)

		then:
		thrown(IllegalStateException)
	}
}
