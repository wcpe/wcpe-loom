/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

package net.fabricmc.loom.test.unit

import java.nio.file.Path
import java.util.function.Function
import java.util.stream.Stream

import groovy.transform.CompileStatic
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.Project
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.api.RemapConfigurationSettings
import net.fabricmc.loom.api.fmj.FabricModJsonV1Spec
import net.fabricmc.loom.configuration.processors.speccontext.ProjectView
import net.fabricmc.loom.configuration.processors.speccontext.RemappedProjectView
import net.fabricmc.loom.configuration.processors.speccontext.RemappedSpecContext
import net.fabricmc.loom.test.util.GradleTestUtil
import net.fabricmc.loom.util.ZipUtils
import net.fabricmc.loom.util.fmj.gen.FabricModJsonV1Generator

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

@SuppressWarnings('ExplicitCallToModMethod')
class SpecContextTest extends Specification {
	@TempDir
	Path tempDir

	Project project
	RemappedProjectView projectView
	NamedDomainObjectList<RemapConfigurationSettings> remapConfigurations

	RemapConfigurationSettings implementation
	RemapConfigurationSettings runtimeOnly
	RemapConfigurationSettings compileOnly

	Map<RemapConfigurationSettings, List<Path>> runtimeArtifacts = [:]
	Map<RemapConfigurationSettings, List<Path>> apiArtifacts = [:]

	void setup() {
		project = GradleTestUtil.mockProject()
		projectView = mock(RemappedProjectView.class)
		remapConfigurations = project.getObjects().namedDomainObjectList(RemapConfigurationSettings.class)

		when(projectView.getRemapConfigurations()).thenReturn(remapConfigurations)
		when(projectView.resolveArtifacts(ProjectView.ArtifactUsage.RUNTIME)).thenReturn(resolve(runtimeArtifacts))
		when(projectView.resolveArtifacts(ProjectView.ArtifactUsage.COMPILE)).thenReturn(resolve(apiArtifacts))

		implementation = createConfigurationSettings("implementation")
		runtimeOnly = createConfigurationSettings("runtimeOnly")
		compileOnly = createConfigurationSettings("compileOnly")
		remapConfigurations.addAll([
			implementation,
			runtimeOnly,
			compileOnly
		])

		when(projectView.getCompileRemapConfigurations()).thenReturn([implementation, compileOnly])
		when(projectView.getRuntimeRemapConfigurations()).thenReturn([implementation, runtimeOnly])
	}

	def "Empty"() {
		setup:
		dependencies(
				implementation: [],
				runtimeOnly: [],
				compileOnly: []
				)

		when:
		def specContext = RemappedSpecContext.create(projectView)

		then:
		specContext.modDependencies().size() == 0
		specContext.localMods().size() == 0
		specContext.modDependenciesCompileRuntime().size() == 0
		specContext.modDependenciesCompileRuntimeClient().size() == 0
		specContext.allMods().size() == 0
	}

	def "implementation dependency"() {
		setup:
		dependencies(
				implementation: [mod("test1")],
				runtimeOnly: [],
				compileOnly: []
				)

		when:
		def specContext = RemappedSpecContext.create(projectView)

		then:
		specContext.modDependencies().size() == 1
		specContext.localMods().size() == 0
		specContext.modDependenciesCompileRuntime().size() == 1
		specContext.modDependenciesCompileRuntimeClient().size() == 0
		specContext.allMods().size() == 1
	}

	def "runtime only dependency"() {
		setup:
		dependencies(
				implementation: [],
				runtimeOnly: [mod("test1")],
				compileOnly: []
				)

		when:
		def specContext = RemappedSpecContext.create(projectView)

		then:
		specContext.modDependencies().size() == 1
		specContext.localMods().size() == 0
		specContext.modDependenciesCompileRuntime().size() == 0
		specContext.modDependenciesCompileRuntimeClient().size() == 0
		specContext.allMods().size() == 1
	}

	def "compile only dependency"() {
		setup:
		dependencies(
				implementation: [],
				runtimeOnly: [],
				compileOnly: [mod("test1")]
				)

		when:
		def specContext = RemappedSpecContext.create(projectView)

		then:
		specContext.modDependencies().size() == 1
		specContext.localMods().size() == 0
		specContext.modDependenciesCompileRuntime().size() == 0
		specContext.modDependenciesCompileRuntimeClient().size() == 0
		specContext.allMods().size() == 1
	}

	def "compile only runtime only dependency"() {
		setup:
		def test1 = mod("test1")
		dependencies(
				implementation: [],
				runtimeOnly: [test1],
				compileOnly: [test1]
				)

		when:
		def specContext = RemappedSpecContext.create(projectView)

		then:
		specContext.modDependencies().size() == 1
		specContext.localMods().size() == 0
		specContext.modDependenciesCompileRuntime().size() == 1
		specContext.modDependenciesCompileRuntimeClient().size() == 0
		specContext.allMods().size() == 1
	}

	/**
	 * {@code modDependencies()} 必须惰性求值：求值点是在「首次读取」而不是「构建上下文」.
	 *
	 * <p>这是本类唯一能钉住「配置期恒定花费」的用例。{@code modDependencies()} 是上下文里唯一
	 * 会遍历**全部** remap 配置并逐个打开 mod jar 的字段（见 {@code getDependentMods} 直接对
	 * {@code entry.getSourceConfiguration().get().resolve()} 逐个 artifact 读元数据）；而
	 * {@code modDependenciesCompileRuntime()} 只遍历其中 {@code applyDependencyTransforms=true}
	 * 的子集。
	 *
	 * <p><b>验证手段</b>：在 {@code create} **之后**才把一个 remap 配置加进配置列表。若求值真被推迟，
	 * 这个后加的配置会出现在首次读取的结果里；若仍在 {@code create} 里就求值，它就会缺席。
	 * 这个手段不依赖对内部实现的计数埋点，因此不会因为实现换了读取方式而假绿/假红。
	 *
	 * <p>断言钉住三件事：
	 * <ol>
	 *   <li>{@code create} 之后、首次读之前，后加的配置还没被看到（证明 create 阶段没求值）；</li>
	 *   <li>首次读取时能看到它，且原有依赖也在（惰性不等于丢失）；</li>
	 *   <li>再读一次结果不变（同一个上下文里只算一次）。</li>
	 * </ol>
	 */
	def "mod dependencies are resolved lazily on first read"() {
		setup:
		dependencies(
				implementation: [mod("test1")],
				runtimeOnly: [],
				compileOnly: []
				)

		when:
		def specContext = RemappedSpecContext.create(projectView)

		// create 已经返回，此时才加入的配置：只有把求值推迟到首次读取，它才会被纳入
		def latecomer = createConfigurationSettings("latecomer")
		remapConfigurations.add(latecomer)
		configureDependencies([mod("lateMod")], latecomer)

		def firstRead = specContext.modDependencies()
		def secondRead = specContext.modDependencies()

		then:
		// 1. 首次读取能看到 create 之后才加的配置（反证：create 阶段没有求值）
		firstRead*.id.sort() == ["lateMod", "test1"]
		// 2. 再读一次结果一致（同一个上下文里只算一次）
		secondRead*.id.sort() == firstRead*.id.sort()
	}

	private void dependencies(Map<Object, List<Path>> files) {
		configureDependencies(files.implementation, this.implementation)
		configureDependencies(files.runtimeOnly, this.runtimeOnly)
		configureDependencies(files.compileOnly, this.compileOnly)

		runtimeArtifacts[this.implementation].addAll(files.implementation)
		runtimeArtifacts[this.runtimeOnly].addAll(files.runtimeOnly)
		apiArtifacts[this.implementation].addAll(files.implementation)
		apiArtifacts[this.compileOnly].addAll(files.compileOnly)
	}

	private void configureDependencies(List<Path> files, RemapConfigurationSettings settings) {
		project.configurations.register(settings.name)
		project.dependencies.add(settings.name, project.files(files))
	}

	private Path mod(String modId) {
		def zip = tempDir.resolve("${modId}.zip")

		def spec = project.objects.newInstance(FabricModJsonV1Spec.class)
		spec.modId.set(modId)
		spec.version.set("1.0.0")
		def json = FabricModJsonV1Generator.INSTANCE.generate(spec)
		ZipUtils.add(zip, "fabric.mod.json", json)

		return zip
	}

	private RemapConfigurationSettings createConfigurationSettings(String name) {
		def settings = project.getObjects().newInstance(RemapConfigurationSettings.class, name)
		settings.applyDependencyTransforms.set(true)

		runtimeArtifacts.put(settings, [])
		apiArtifacts.put(settings, [])

		return settings
	}

	@CompileStatic
	private Function<RemapConfigurationSettings, Stream<Path>> resolve(Map<RemapConfigurationSettings, List<Path>> artifacts) {
		return { settings ->
			def paths = artifacts.get(settings)
			return paths != null ? paths.stream() : Stream.empty()
		}
	}
}
