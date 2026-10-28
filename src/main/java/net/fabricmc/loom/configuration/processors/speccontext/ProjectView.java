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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
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
	 * 依赖项目的数据要么恰好因配置顺序已可得，要么缺失。这里做两件事弥补：
	 * <ul>
	 *   <li>把依赖项目的 {@code exportLoomProjectData} 产物声明为本项目编译任务的输入
	 *       （{@code wireProjectDataTask}），Gradle 据此自动把该任务纳入任务图——本次构建里
	 *       数据文件就一定会在被读取前产出，不再依赖「上一次构建恰好跑过导出」；</li>
	 *   <li>数据仍缺失时（例如对端不是 Loom 项目）输出一条按（当前项目，依赖项目）去重的告警。</li>
	 * </ul>
	 * 需要说明的是：配置期读取与执行期产出之间存在时序差，接线保证的是「文件在本次构建内会被
	 * 产出、供执行期消费」，它不能把配置期那次读取变成「一定能读到」（配置期本就不执行任务）。
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

		/**
		 * 已经接过线的依赖项目路径.
		 *
		 * <p>同一依赖项目会同时出现在 runtime 与 compile 两条 classpath 上，接线方法因此会被调用两次，
		 * 而 Gradle 不允许同一个任务上出现同名输入属性（{@code Multiple input file properties with name ...}）。
		 * 去重范围限定在本视图实例内：与告警去重同理，不用静态状态，避免被复用的 daemon 带进后续构建。
		 */
		private final Set<String> wiredProjectDataDependencies = new HashSet<>();

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

				// 只在隔离模式下接线，理由见 wireProjectDataTask 的说明。
				if (extension.isProjectIsolationActive()) {
					wireProjectDataTask(dependency);
				}
			}

			final Optional<LoomProjectData> data = LoomProjectData.fromDependency(project, dependency);

			if (data.isEmpty() && readDependencyData && extension.isProjectIsolationActive() && isDependencyLoomProject(dependency)) {
				// 隔离模式下无法强制求值，缺失只能告警；不在读取无意义（disableProjectDependantMods）
				// 的场景告警，否则会为没有消费方的项目制造噪音。
				//
				// 必须再限定「依赖确实是 Loom 项目」：fromDependency 的 Optional.empty() 有**两种**含义，
				// 它自己的 javadoc 已写明——(a) 依赖本来就不是 Loom 项目，没有该可消费配置；
				// (b) 是 Loom 项目但数据在配置期不可得。这两种后果天差地别，却共用同一条告警：
				//   (a) 它压根没有 mod 元数据 / access widener / access transformer / mixin 可丢，
				//       告警纯属噪音（实测下游 14 模块工程里 22 条告警中绝大多数属于此类，
				//       而被点名的 9 个模块没有一个带 mixin/AW/AT；唯一带 mixin 的 impl-server 反而没被点名）；
				//   (b) 才是真问题。
				// 不区分会让真问题淹没在噪音里。
				warnMissingDependencyDataOnce(dependency);
			}

			return data;
		}

		/**
		 * {@return 该依赖是否暴露了 Loom 的跨项目数据配置}.
		 *
		 * <p><b>为什么不能查对端的插件应用状态</b>：{@code LoomGradlePlugin.isApplied(dependent)} 会读
		 * {@code dependent.getPluginManager()}，属于「对端项目的模型」。在 Isolated Projects 下这是非法
		 * 跨项目访问，实测会报出成百上千条「cannot access 'Project.pluginManager' functionality on
		 * another project」并把整个构建打挂。
		 *
		 * <p>改用 Gradle 自己的依赖解析结果：声明一条指向对方 {@code loomProjectDataElements} 的依赖，
		 * 看 Gradle 能否解析出变体。非 Loom 项目没有该可消费配置，解析结果会带
		 * {@link UnresolvedDependencyResult}。这条路径完全在依赖图层面，不碰对端的模型对象，
		 * 因此在 IP 下是允许的——与 {@code LoomProjectData.resolveDataFile} 用的是同一种机制。
		 *
		 * <p>只有确实暴露了该配置、却仍然拿不到数据，才是真问题（数据未就绪）；
		 * 本来就没这个配置的项目（普通 java 库）不该被警告——它们压根没有 mod 元数据、
		 * access widener、access transformer 或 mixin 可丢。
		 *
		 * @param dependency 待判定的项目依赖
		 * @return 对方暴露了 {@code loomProjectDataElements} 时为 true
		 */
		private boolean isDependencyLoomProject(ProjectDependency dependency) {
			try {
				final Configuration probe = project.getConfigurations().detachedConfiguration(
						project.getDependencies().project(Map.of(
								"path", dependency.getPath(),
								"configuration", LoomProjectData.DATA_ELEMENTS_CONFIGURATION
						)));
				probe.setTransitive(false);

				final ResolutionResult result = probe.getIncoming().getResolutionResult();
				return result.getRoot().getDependencies().stream().anyMatch(dep -> dep instanceof ResolvedDependencyResult);
			} catch (RuntimeException e) {
				// 判定失败不升级为告警（宁可少报，不可误报）
				LOGGER.debug("Failed to determine whether {} exposes Loom project data", dependency.getPath(), e);
				return false;
			}
		}

		/**
		 * 把依赖项目的跨项目数据文件接线到本项目的任务图上，让它在消费之前被产出.
		 *
		 * <p><b>只在隔离模式调用</b>（调用点见 {@link #getLoomProjectData}）：非隔离模式下数据一律
		 * 来自内存共享表（读取前用 {@code evaluationDependsOn} 强制对端先求值），导出的文件不参与
		 * 本次构建。在那里接线只会多跑一个本体不需要的任务，并打破「非隔离模式不依赖导出文件」
		 * 这条既有契约（{@code ProjectDataColdStartTest} 就钉着它）。
		 *
		 * <p><b>为什么需要这一步</b>：{@code exportLoomProjectData} 是执行期任务，配置期读不到它
		 * 尚未产出的文件；而数据缺失就是那条「跨项目数据不可得」告警的成因。仅靠「读文件」无法
		 * 解决——文件总得有人先写出来。Gradle 的标准做法是把该产物声明成**任务输入**：
		 * {@code files(detachedConfiguration(...))} 自带的 {@code buildDependencies} 会让 Gradle
		 * 自动把生产者的任务纳入任务图，无需手写 {@code dependsOn(":other:task")}。
		 *
		 * <p><b>为什么不破坏三个机制</b>（均在含 {@code parallel/configuration-cache/
		 * isolated-projects/configure-on-demand} 的最小工程上实测）：
		 * <ul>
		 *   <li>配置缓存：{@code detachedConfiguration} 与 {@code FileCollection} 都是 Gradle 原生
		 *       可序列化对象，无 {@code InvalidUserCodeException}；</li>
		 *   <li>构建缓存：{@code exportLoomProjectData} 本身是 {@code @CacheableTask}，带完整
		 *       {@code @Input}/{@code @OutputFile}，命中时直接复用；</li>
		 *   <li>按需配置：接线走 artifact 依赖，不调 {@code tasks.named(":other:...")}
		 *       （后者在 Isolated Projects 下抛 {@code InvalidUserCodeException}）。
		 *       实测「无关项目是否被配置」与「是否接线」无关。</li>
		 * </ul>
		 *
		 * <p>接线是幂等的：每次解析只创建一条 detached 配置，且只用于挂输入，不参与任何产物路径。
		 *
		 * @param dependency 依赖项目
		 */
		private void wireProjectDataTask(ProjectDependency dependency) {
			// 只给真正的 Loom 项目接线：非 Loom 项目没有 loomProjectDataElements 可消费配置，
			// 声明它的 artifact 会让 Gradle 在解析阶段报
			// 「no variant with that configuration name exists」而把整个构建打挂。
			// 这类依赖本来就没有跨项目数据可用，不接线也不会损失任何东西。
			if (!isDependencyLoomProject(dependency)) {
				return;
			}

			// 同一项目会同时出现在 runtime 与 compile 两条 classpath 上，本方法因此会被调用两次；
			// Gradle 不允许同一个任务上出现同名输入属性。
			if (!wiredProjectDataDependencies.add(dependency.getPath())) {
				return;
			}

			try {
				final Configuration dataConfiguration = project.getConfigurations().detachedConfiguration(
						project.getDependencies().project(Map.of(
								"path", dependency.getPath(),
								"configuration", LoomProjectData.DATA_ELEMENTS_CONFIGURATION
						)));
				dataConfiguration.setTransitive(false);
				// 非 Loom 项目没有这个可消费配置；lenient 让「没这个变体」退化为空集合，
				// 而不是在任务依赖求解阶段把整个构建打挂。
				dataConfiguration.getIncoming().getResolutionResult();
				final var dataFiles = dataConfiguration.getIncoming()
						.artifactView(view -> view.setLenient(true))
						.getFiles();

				// 声明成「所有编译任务」的输入：Gradle 据此把对端的 exportLoomProjectData 纳入任务图。
				project.getTasks().withType(org.gradle.api.tasks.compile.JavaCompile.class)
						.configureEach(task -> task.getInputs().files(dataFiles).withPropertyName(
								"loomProjectData_" + dependency.getPath().replace(':', '_')));
			} catch (RuntimeException e) {
				// 接线失败不得升级为构建失败：最坏情形退化为「数据不可得」，由告警告知
				LOGGER.debug("Failed to wire project data task of {}", dependency.getPath(), e);
			}
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
