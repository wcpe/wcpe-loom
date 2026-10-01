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

package net.fabricmc.loom.test.integration.buildSrc.forgeSourcesCacheDecompiler

import org.gradle.api.Plugin
import org.gradle.api.Project

import net.fabricmc.loom.LoomGradleExtension

/**
 * 注册替身反编译器（{@link StubSourceDecompiler}），生成任务名为 {@code genSourcesWithCachedStub}.
 */
class TestPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		def extension = LoomGradleExtension.get(project)
		// 在配置期就把路径取成字符串：decompiler options 会在执行期被读（算缓存键），
		// 那时再碰 project 在 Isolated Projects / 配置缓存下是非法访问。
		final String projectDir = project.projectDir.absolutePath.replace('\\', '/')

		def markerTask = project.tasks.register('writeStubMarker', StubMarkerTask.class) {
			outputFile = project.file('stub-marker.txt')
		}

		extension.decompilerOptions.register('cachedStub') {
			decompilerClassName.set(StubSourceDecompiler.class.name)
			// classpath 只用来提供一个内容恒定的条目，见 StubMarkerTask 的注释
			classpath.from(markerTask)
			classpath.builtBy(markerTask)
			// 路径必须跨轮恒定：它进缓存键，换路径等于换缓存。轮次由文件内容区分，不由路径区分。
			options.put('runLog', "${projectDir}/stub-runs.txt")
		}
	}
}
