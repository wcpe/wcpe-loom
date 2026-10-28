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

import groovy.json.JsonSlurper
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 验证依赖项目的 mixin 映射能真正经跨项目数据链路传到消费方。
 *
 * <p>AW / AT / mixin 三种跨项目数据走的不是同一条路：
 * <ul>
 *   <li>{@code AccessWidenerJarProcessor} 读 {@code context.modDependenciesCompileRuntime()}；</li>
 *   <li>{@code AccessTransformerJarProcessor} 只读 {@code context.localMods()}，<b>不跨项目</b>，
 *       因此不存在也不需要验证「跨项目 AT 传播」；</li>
 *   <li>{@code MixinAPMappingService} 在隔离模式下遍历
 *       {@code LoomProjectData.getDependencies(thisProject)} 取 {@code mixinMappingFiles()}。</li>
 * </ul>
 * 本用例针的是第三条：这是唯一一条以前没有端到端验证的通道。
 *
 * <p><b>验证方式</b>：跨项目数据的落盘载体是依赖项目的
 * {@code build/loom/project-data.json}（由 {@code exportLoomProjectData} 产出），其
 * {@code mixinMappingFiles} 字段就是消费方会拿去用的那份清单。直接断言这个文件，
 * 比在夹具里调内部 API 稳定：夹具的构建脚本跑在另一个类加载器中，看不到插件的 internal 类。
 *
 * <p>夹具的 :lib 带一个真实 mixin（目标 {@code net.minecraft.client.MinecraftClient}）与
 * {@code mixins.json}，因此编译它时 mixin AP 必定产出 {@code mixin-map-*.tiny}；:app 用普通
 * {@code implementation project(":lib")} 依赖它（用 modImplementation 会被换成文件依赖，
 * 链路根本不触发）。
 */
class CrossProjectMixinMappingsTest extends Specification implements GradleProjectTestTrait {
	private static final String LIB_DATA_FILE = "lib/build/loom/project-data.json"

	def "dependency project exports its mixin mappings for cross-project consumption"() {
		setup:
		def gradle = gradleProject(project: "crossProjectMixinMappings", version: DEFAULT_GRADLE)

		when: "先编生产者（产出 mixin 映射），再单独跑它的导出任务"
		def result = gradle.run(
				tasks: [
					":lib:compileJava",
					":lib:exportLoomProjectData"
				],
				isloatedProjects: true)

		then: "构建成功"
		result.task(":lib:compileJava").outcome == SUCCESS
		result.task(":lib:exportLoomProjectData").outcome == SUCCESS

		and: "生产者确实产出了 mixin 映射文件（否则这条链路没有东西可传）"
		def mixinMaps = mixinMapFiles(gradle)
		!mixinMaps.isEmpty()

		and: "导出的跨项目数据里带着那条 mixin 映射"
		def dataFile = new File(gradle.projectDir, LIB_DATA_FILE)
		dataFile.isFile()

		def data = new JsonSlurper().parse(dataFile)
		data.projectPath == ":lib"
		data.mixinMappingFiles
		data.mixinMappingFiles.any { it.contains("mixin-map-") && it.endsWith(".tiny") }

		and: "那些路径里至少有一条指向真实存在的映射文件（不是写了就不管的悬空路径）"
		// 不能用 every{}：LoomProjectData.readMixinMappingFiles 会为每个源集都登记一条路径，
		// 而只有真的有 mixin 的源集（这里是 main）才会产出文件；test 源集没有 mixin，
		// 它的那条路径本来就指向不存在的文件，这是既有语义，不是缺陷。
		data.mixinMappingFiles.any { new File(it as String).isFile() }
	}

	def "consumer using the dependency does not lose the mixin mappings"() {
		setup:
		def gradle = gradleProject(project: "crossProjectMixinMappings", version: DEFAULT_GRADLE)

		when: "让消费方真的编译一次（跨项目数据链路在配置期被走到）"
		def result = gradle.run(tasks: [":app:compileJava"], isloatedProjects: true)

		then: "构建成功"
		result.task(":app:compileJava").outcome == SUCCESS

		and: "隔离模式下没有出现「跨项目数据在配置期不可得」的告警"
		!result.output.contains("跨项目数据在配置期不可得")

		and: "依赖项目的数据文件被自动产出（接线把 exportLoomProjectData 拉进了任务图）"
		new File(gradle.projectDir, LIB_DATA_FILE).isFile()
	}

	private static List<File> mixinMapFiles(GradleProjectTestTrait.GradleProject gradle) {
		List<File> found = []
		// 子项目的 map 文件落在 <子项目>/build/loom-cache 下（getProjectBuildCache 对子项目
		// 返回 projectPersistentCache，即 <projectCacheDir>/loom-cache/projects/<路径>；
		// 但测试临时工程里 projectCacheDir 就是 <子项目>/build，因此实际就是这一层）。
		def cacheDir = new File(gradle.projectDir, "lib/build/loom-cache")

		if (cacheDir.isDirectory()) {
			cacheDir.eachFileRecurse {
				if (it.isFile() && it.name.startsWith("mixin-map-") && it.name.endsWith(".tiny")) {
					found << it
				}
			}
		}

		return found
	}
}
