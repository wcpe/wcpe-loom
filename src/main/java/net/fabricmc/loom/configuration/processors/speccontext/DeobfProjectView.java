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

package net.fabricmc.loom.configuration.processors.speccontext;

import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;

import net.fabricmc.loom.internal.LoomProjectData;

public interface DeobfProjectView extends ProjectView {
	FileCollection getDependencies(DebofConfiguration debofConfiguration, DebofConfiguration.TargetSourceSet targetSourceSet);

	/**
	 * 返回给定 deobf 配置上的依赖项目数据.
	 *
	 * <p>这里刻意不重复实现「读取前强制依赖项目求值」：它们全部经由
	 * {@link #getLoomProjectDataDependencies(String)} 读取，非隔离模式下的强制求值、以及隔离模式下
	 * 数据缺失时的去重告警，都在那一个入口完成，语义与限制见该方法的说明。deobf 配置（{@code runtimeClasspath}
	 * 等）就是本项目的 classpath 配置，因此两条路径没有别的读法。
	 *
	 * @param debofConfiguration 目标 deobf 配置
	 * @return 该配置上的依赖项目数据流
	 */
	Stream<LoomProjectData> getProjectDependencies(DebofConfiguration debofConfiguration);

	FileCollection getFullClasspath();

	class Impl extends AbstractProjectView implements DeobfProjectView {
		protected Impl(Project project) {
			super(project);
		}

		@Override
		public FileCollection getDependencies(DebofConfiguration debofConfiguration, DebofConfiguration.TargetSourceSet targetSourceSet) {
			return debofConfiguration.getConfiguration(project, targetSourceSet);
		}

		@Override
		public Stream<LoomProjectData> getProjectDependencies(DebofConfiguration debofConfiguration) {
			// 单点入口：强制求值与缺失告警都在 getLoomProjectDataDependencies 里，见其 Javadoc。
			return debofConfiguration.getConfigurations(project).stream()
					.flatMap(configuration -> getLoomProjectDataDependencies(configuration.getName()));
		}

		@Override
		public FileCollection getFullClasspath() {
			ConfigurableFileCollection classpath = project.files();

			for (DebofConfiguration debofConfiguration : DebofConfiguration.ALL) {
				for (Configuration configuration : debofConfiguration.getConfigurations(project)) {
					classpath.from(configuration);
				}
			}

			return classpath;
		}
	}
}
