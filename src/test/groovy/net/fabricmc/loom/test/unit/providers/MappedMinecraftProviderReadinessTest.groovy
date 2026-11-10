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

package net.fabricmc.loom.test.unit.providers

import java.nio.file.Path

import org.gradle.api.Project
import org.mockito.Mockito
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.api.processor.MinecraftJarProcessor
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider
import net.fabricmc.loom.configuration.providers.minecraft.mapped.ProcessedNamedMinecraftProvider
import net.fabricmc.loom.test.util.GradleTestUtil
import net.fabricmc.tinyremapper.TinyRemapper

class MappedMinecraftProviderReadinessTest extends Specification {
	@TempDir
	Path directory

	def "可任务化的供给不预读尚未生产的jar或POM"() {
		given:
		def provider = new ProbeProvider(GradleTestUtil.mockProject(), directory)

		expect:
		provider.isReadyForConfiguration(null)
		provider.outputChecks == 0
	}

	def "显式关闭任务生产时仍按真实产物状态决定是否可无锁供给"() {
		given:
		def provider = new ProbeProvider(GradleTestUtil.mockProject(), directory)
		provider.useConfigurationProduction()

		expect:
		!provider.isReadyForConfiguration(null)
		provider.outputChecks == 1

		when:
		provider.outputsReady = true

		then:
		provider.isReadyForConfiguration(null)
		provider.outputChecks == 2
	}

	def "未知重映射钩子仍回退到原有产物检查而不被直接放行"() {
		given:
		def provider = new UnknownHookProvider(GradleTestUtil.mockProject(), directory)

		expect:
		!provider.isReadyForConfiguration(null)
		provider.outputChecks == 1
	}

	def "未知provide覆写仍按原有产物状态判断而不被直接放行"() {
		given:
		def provider = new UnknownProvideProvider(GradleTestUtil.mockProject(), directory)

		expect:
		!provider.isReadyForConfiguration(null)
		provider.outputChecks == 1
	}

	def "可任务化处理器包装不预读父产物或处理后产物"() {
		given:
		def parent = new ProbeProvider(GradleTestUtil.mockProject(), directory)
		def manager = Mockito.mock(MinecraftJarProcessorManager)
		Mockito.when(manager.getProcessors()).thenReturn([])
		def provider = new ProcessedProbe(parent, manager)

		expect:
		provider.isReadyForConfiguration(null)
		parent.outputChecks == 0
		provider.outputChecks == 0
	}

	def "不可任务化处理器保留回退检查"() {
		given:
		def parent = new ProbeProvider(GradleTestUtil.mockProject(), directory)
		def manager = Mockito.mock(MinecraftJarProcessorManager)
		def processor = Mockito.mock(MinecraftJarProcessor)
		Mockito.when(processor.descriptor()).thenThrow(new UnsupportedOperationException("无任务描述符"))
		Mockito.when(manager.getProcessors()).thenReturn([processor])
		def provider = new ProcessedProbe(parent, manager)

		expect:
		!provider.isReadyForConfiguration(null)
		provider.outputChecks == 1
	}

	def "处理器包装不能把仍需配置期生产的父供给当作已就绪"() {
		given:
		def parent = new ProbeProvider(GradleTestUtil.mockProject(), directory)
		parent.useConfigurationProduction()
		def manager = Mockito.mock(MinecraftJarProcessorManager)
		def provider = new ProcessedProbe(parent, manager)
		provider.outputsReady = true

		expect:
		!provider.isReadyForConfiguration(null)
		parent.outputChecks == 1
		provider.outputChecks == 0
	}

	private static class ProbeProvider extends NamedMinecraftProvider<MinecraftProvider> {
		private final List<RemappedJars> remappedJars
		int outputChecks
		boolean outputsReady

		ProbeProvider(Project project, Path root) {
			super(project, null)
			remappedJars = [
				new RemappedJars(root.resolve("input.jar"), new MinecraftJar.Merged(root.resolve("output.jar")), MappingsNamespace.OFFICIAL)
			]
		}

		@Override
		List<RemappedJars> getRemappedJars() {
			return remappedJars
		}

		@Override
		List<MinecraftJar> getMinecraftJars() {
			return remappedJars.collect { it.outputJar() }
		}

		@Override
		MavenScope getMavenScope() {
			return MavenScope.GLOBAL
		}

		@Override
		boolean isUpToDate(ProvideContext context) {
			outputChecks++
			return outputsReady
		}

		void useConfigurationProduction() {
			disableTaskProduction()
		}
	}

	private static class UnknownProvideProvider extends ProbeProvider {
		UnknownProvideProvider(Project project, Path root) {
			super(project, root)
		}

		@Override
		List<MinecraftJar> provide(ProvideContext context) {
			throw new AssertionError("就绪检查不应执行未知provide实现")
		}
	}

	private static class ProcessedProbe extends ProcessedNamedMinecraftProvider<MinecraftProvider, ProbeProvider> {
		int outputChecks
		boolean outputsReady

		ProcessedProbe(ProbeProvider parent, MinecraftJarProcessorManager manager) {
			super(parent, manager)
		}

		@Override
		boolean isUpToDate(ProvideContext context) {
			outputChecks++
			return outputsReady
		}
	}

	private static class UnknownHookProvider extends ProbeProvider {
		UnknownHookProvider(Project project, Path root) {
			super(project, root)
		}

		@Override
		protected void configureRemapper(RemappedJars jars, TinyRemapper.Builder builder) {
			// 不声明钩子的可投影形态，保持第三方扩展的保守回退协议。
		}
	}
}
