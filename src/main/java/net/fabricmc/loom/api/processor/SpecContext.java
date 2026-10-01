/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.api.processor;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.processors.speccontext.ProjectView;
import net.fabricmc.loom.internal.LoomProjectData;
import net.fabricmc.loom.util.fmj.FabricModJson;

public interface SpecContext {
	/**
	 * Returns a list of all the external mods that this project depends on regardless of configuration.
	 */
	List<FabricModJson> modDependencies();

	List<FabricModJson> localMods();

	/**
	 * Return a set of mods that should be used for transforms, that target EITHER the common or client.
	 */
	List<FabricModJson> modDependenciesCompileRuntime();

	/**
	 * Return a set of mods that should be used for transforms, that target ONLY the client.
	 */
	List<FabricModJson> modDependenciesCompileRuntimeClient();

	MappingsNamespace productionNamespace();

	default List<FabricModJson> allMods() {
		return Stream.concat(modDependencies().stream(), localMods().stream()).toList();
	}

	/**
	 * 返回 {@link SpecContext} 依赖的其它项目的 {@link Project}，现恒为空.
	 *
	 * <p>该方法与 {@link ProjectView#getLoomProjectDependencies(String)} 一样，是隔离模式之前
	 * 直接访问跨项目模型的旧 SPI：现在没有任何实现会返回非空值，第三方 processor 调用它只会
	 * 静默拿到空集合。需要依赖项目的信息请改用 {@link #getDependentProjectData(ProjectView)}。
	 *
	 * @param projectView 当前项目的视图
	 * @return 恒为空流
	 * @deprecated 已无生产实现，改用 {@link #getDependentProjectData(ProjectView)}
	 */
	@Deprecated
	static Stream<Project> getDependentProjects(ProjectView projectView) {
		return Stream.empty();
	}

	/**
	 * 返回 runtime/compile classpath 上去重后的依赖项目数据.
	 *
	 * <p>去重依赖 {@link LoomProjectData#equals(Object)} 按项目路径判等：同一项目经内存共享表与
	 * 导出文件两条路径可能得到两个实例，只有路径判等才能把它们视作同一个项目。
	 *
	 * @param projectView 当前项目的视图
	 * @return 依赖项目的数据流
	 */
	static Stream<LoomProjectData> getDependentProjectData(ProjectView projectView) {
		final Stream<LoomProjectData> runtimeProjects = projectView.getLoomProjectDataDependencies(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
		final Stream<LoomProjectData> compileProjects = projectView.getLoomProjectDataDependencies(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);

		return Stream.concat(runtimeProjects, compileProjects)
				.distinct();
	}

	// Sort to ensure stable caching
	static List<FabricModJson> distinctSorted(List<FabricModJson> mods) {
		return mods.stream()
				.distinct()
				.sorted(Comparator.comparing(FabricModJson::getId))
				.toList();
	}
}
