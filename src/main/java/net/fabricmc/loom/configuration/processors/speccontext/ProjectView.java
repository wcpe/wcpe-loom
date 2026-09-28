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

import java.util.List;
import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.attributes.Usage;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.internal.LoomProjectData;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonHelpers;
import net.fabricmc.loom.util.gradle.GradleUtils;

// Used to abstract out the Gradle API usage to ease unit testing.
public interface ProjectView {
	/**
	 * 返回指定配置中依赖项目的跨项目数据.
	 *
	 * @param name 配置名（通常是 {@code runtimeClasspath} / {@code compileClasspath}）
	 * @return 依赖项目的数据流；不是 Loom 项目的依赖不会出现在其中
	 */
	Stream<LoomProjectData> getLoomProjectDataDependencies(String name);

	/**
	 * 旧的跨项目 {@link Project} 视图，现恒为空.
	 *
	 * <p>隔离模式不允许触碰其它项目的模型，这个方法已经没有任何实现会返回非空值；
	 * 调用它只会静默拿到空集合。需要依赖项目的信息请改用
	 * {@link #getLoomProjectDataDependencies(String)} 与 {@code LoomProjectData}。
	 *
	 * @param name 配置名
	 * @return 恒为空流
	 * @deprecated 已无生产实现，改用 {@link #getLoomProjectDataDependencies(String)}
	 */
	@Deprecated
	default Stream<Project> getLoomProjectDependencies(String name) {
		return Stream.empty();
	}

	// Returns the mods defined in the current project
	List<FabricModJson> getMods();

	boolean disableProjectDependantMods();

	boolean areEnvironmentSourceSetsSplit();

	enum ArtifactUsage {
		RUNTIME(Usage.JAVA_RUNTIME),
		COMPILE(Usage.JAVA_API);

		private final String gradleUsage;

		ArtifactUsage(String gradleUsage) {
			this.gradleUsage = gradleUsage;
		}

		public String getGradleUsage() {
			return gradleUsage;
		}
	}

	abstract class AbstractProjectView implements ProjectView {
		protected final Project project;
		protected final LoomGradleExtension extension;

		protected AbstractProjectView(Project project) {
			this.project = project;
			this.extension = LoomGradleExtension.get(project);
		}

		@Override
		public Stream<LoomProjectData> getLoomProjectDataDependencies(String name) {
			final Configuration configuration = project.getConfigurations().getByName(name);
			return configuration.getAllDependencies()
					.withType(ProjectDependency.class)
					.stream()
					// 不是 Loom 项目时 fromDependency 返回空；数据文件存在却读不出来时会抛异常，
					// 不再像以前那样被 filter 静默丢掉（那会让依赖方的 mod/mixin 映射悄悄消失）。
					.flatMap(dependency -> LoomProjectData.fromDependency(project, dependency).stream());
		}

		@Override
		public List<FabricModJson> getMods() {
			return FabricModJsonHelpers.getModsInProject(project);
		}

		@Override
		public boolean disableProjectDependantMods() {
			return GradleUtils.getBooleanProperty(project, Constants.Properties.DISABLE_PROJECT_DEPENDENT_MODS);
		}

		@Override
		public boolean areEnvironmentSourceSetsSplit() {
			return extension.areEnvironmentSourceSetsSplit();
		}
	}
}
