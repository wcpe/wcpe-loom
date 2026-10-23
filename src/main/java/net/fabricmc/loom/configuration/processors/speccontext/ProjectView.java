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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.attributes.Usage;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

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
	 * <p>这些数据会被打进 jar processor 的 spec 并参与其 {@code hashCode}，因此它决定了
	 * processed jar 的路径与共享缓存的命中。数据有两个来源：构建级内存共享表
	 * （由依赖项目自身的 {@code afterEvaluate} 写入）与依赖项目导出的数据文件。后者由依赖项目的
	 * 任务在执行期产出，配置期（冷启动）必然不存在，所以配置期能否读到数据只取决于前者，
	 * 也就是只取决于依赖项目是否已经完成求值。
	 *
	 * <p><b>非隔离模式</b>：本方法在读取前用 {@link Project#evaluationDependsOn(String)} 强制每个
	 * 依赖项目先完成求值，把「依赖项目的 {@code afterEvaluate} 先于本次读取」钉死，从而共享表必定
	 * 命中、哈希恒定包含依赖数据，与配置顺序、是否 {@code --parallel} 无关。只有当确实会消费这些
	 * 数据（{@link #disableProjectDependantMods()} 为 false）时才强制求值，不给不需要它的构建增加
	 * 配置开销。若两个 Loom 项目互相声明项目依赖，强制求值会让 Gradle 抛出循环求值异常
	 * （{@code CircularReferenceException}，消息里会点名参与循环的项目）；Loom 不做环检测，交给
	 * Gradle 报告。
	 *
	 * <p><b>隔离模式（Isolated Projects）</b>：跨项目求值被 Gradle 禁止，无法用上面的手段补救，
	 * 依赖项目的数据要么恰好因配置顺序已可得，要么缺失——本方法不改变这一点，只在数据确实缺失时
	 * 输出一条按（当前项目，依赖项目）去重的告警（见 {@code AbstractProjectView} 的实现说明）。
	 * 彻底消除该不确定性需要把 mapped/processed jar 的产出从配置期搬进任务，属于架构级改动。
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
		private static final Logger LOGGER = Logging.getLogger(ProjectView.class);

		protected final Project project;
		protected final LoomGradleExtension extension;

		/**
		 * 已经就「依赖项目数据缺失」告警过的依赖项目路径.
		 *
		 * <p>同一依赖项目会同时出现在 runtime 与 compile 两条 classpath 上，存在多个依赖时也会各自
		 * 走到这里，因此必须去重，否则一次配置就会刷出多条内容相同的告警。去重范围限定在本视图实例
		 * （即本项目的本次配置）内，不用静态状态：静态状态会被复用的 daemon 带进后续构建，导致告警
		 * 静默消失。
		 */
		private final Set<String> warnedMissingDependencyData = new HashSet<>();

		protected AbstractProjectView(Project project) {
			this.project = project;
			this.extension = LoomGradleExtension.get(project);
		}

		@Override
		public Stream<LoomProjectData> getLoomProjectDataDependencies(String name) {
			final Configuration configuration = project.getConfigurations().getByName(name);
			// 只有在确实会消费依赖项目数据时才做强制求值：调用方在 disableProjectDependantMods 为真时
			// 根本不使用返回值，为此强制求值只会平白增加配置开销（甚至把本来不需要的项目拉进配置）。
			final boolean readDependencyData = !disableProjectDependantMods();

			return configuration.getAllDependencies()
					.withType(ProjectDependency.class)
					.stream()
					.flatMap(dependency -> getLoomProjectData(dependency, readDependencyData).stream());
		}

		/**
		 * 读取单个依赖项目的跨项目数据，必要时先让该依赖项目完成求值.
		 *
		 * <p>不是 Loom 项目时 {@code fromDependency} 返回空；数据文件存在却读不出来时它会抛异常，
		 * 不再像以前那样被 filter 静默丢掉（那会让依赖方的 mod/mixin 映射悄悄消失）。
		 *
		 * @param dependency 待查询的项目依赖
		 * @param readDependencyData 本次读取是否真的会被消费，见 {@link #getLoomProjectDataDependencies(String)}
		 * @return 依赖项目的跨项目数据；空表示该依赖不是 Loom 项目，或数据在配置期不可得
		 */
		private Optional<LoomProjectData> getLoomProjectData(ProjectDependency dependency, boolean readDependencyData) {
			if (readDependencyData) {
				ensureDependencyEvaluated(dependency);
			}

			final Optional<LoomProjectData> data = LoomProjectData.fromDependency(project, dependency);

			if (data.isEmpty() && readDependencyData && extension.isProjectIsolationActive()) {
				// 隔离模式下无法强制求值，缺失只能告警；不在读取无意义（disableProjectDependantMods）
				// 的场景告警，否则会为没有消费方的项目制造噪音。
				warnMissingDependencyDataOnce(dependency);
			}

			return data;
		}

		/**
		 * 非隔离模式下强制依赖项目先完成求值，使其 {@code afterEvaluate} 里的数据登记先于本次读取.
		 *
		 * <p>这是本项目对「配置期依赖数据必须确定」的全部保证，语义与限制见
		 * {@link #getLoomProjectDataDependencies(String)}。
		 *
		 * @param dependency 待求值的项目依赖
		 */
		private void ensureDependencyEvaluated(ProjectDependency dependency) {
			if (extension.isProjectIsolationActive()) {
				// Isolated Projects 下跨项目求值被禁止，调用它会被 Gradle 判为非法访问而直接把构建打挂；
				// 这里只能退化为「不做保证」，由 getLoomProjectData 的告警兜底。
				return;
			}

			final String path = dependency.getPath();

			if (project.findProject(path) == null) {
				// 依赖替换、复合构建等场景下，项目的路径可能根本不在本构建里，此时
				// evaluationDependsOn 会抛 UnknownProjectException。这里只是「尽力而为」的加固，
				// 找不到项目时维持原有行为（交给数据文件回退路径），不把一个可选优化升级成失败。
				return;
			}

			// 互相依赖的两个项目会在这里让 Gradle 抛出循环求值异常；Loom 不做环检测，交给 Gradle 报告。
			project.evaluationDependsOn(path);
		}

		/**
		 * 在隔离模式下依赖项目数据确实缺失时，按（当前项目，依赖项目）去重地输出一条默认可见的告警.
		 *
		 * <p>缺失会让依赖项目的 access widener / access transformer / mixin 等数据不参与本次产物，
		 * 且是否可得取决于项目配置顺序，因此普通 {@code warn} 级别输出，让用户知道这是已知限制。
		 *
		 * @param dependency 缺少数据的项目依赖
		 */
		private void warnMissingDependencyDataOnce(ProjectDependency dependency) {
			if (!warnedMissingDependencyData.add(dependency.getPath())) {
				return;
			}

			// 注意消息正文里不能再出现 {}，否则会被日志器当成参数占位符。
			LOGGER.warn("Isolated Projects 已启用，且依赖项目 {} 的跨项目数据在配置期不可得（内存共享表未命中，"
					+ "导出的数据文件也不存在）：该项目的 access widener / access transformer / mixin 等数据"
					+ "可能不会应用于 {} 本次构建的产物。这是 Isolated Projects 禁止跨项目求值导致的已知限制，"
					+ "不是你的配置错误；该数据是否可得取决于项目配置顺序，所以同一工作树上的结果可能不稳定。"
					+ "需要确定行为时请关闭 Isolated Projects（不要启用 org.gradle.unsafe.isolated-projects），"
					+ "或先单独构建一次依赖项目、让它把数据文件产出到磁盘。",
					dependency.getPath(), project.getPath());
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
