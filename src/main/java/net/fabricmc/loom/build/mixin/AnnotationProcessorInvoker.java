/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2025 FabricMC
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

package net.fabricmc.loom.build.mixin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.configuration.ide.idea.IdeaUtils;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.pipeline.GenerateMixinMappingsTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.LoomVersions;

/**
 * Normally javac invokes annotation processors, but when the scala or kapt plugin are installed they will want to invoke
 * the annotation processor themselves.
 * See Java and Kapt implementations for a more deep understanding of the things passed by the children.
 */
public abstract class AnnotationProcessorInvoker<T extends Task> {
	public static final String JAVA = "java";
	public static final String SCALA = "scala";
	public static final String GROOVY = "groovy";

	private static final Pattern MSG_KEY_PATTERN = Pattern.compile("^[A-Z]+[A-Z_]+$");
	private static final Pattern MSG_VALUE_PATTERN = Pattern.compile("^(note|warning|error|disabled)$");

	protected final Project project;
	private final LoomGradleExtension loomExtension;
	protected final MixinExtension mixinExtension;
	protected final Map<SourceSet, TaskProvider<T>> invokerTasks;
	private final String name;
	private final Collection<Configuration> apConfigurations;
	/** 源集 → Mixin 映射产出路径；在 configureMixin() 里一次性登记，供 passMixinArguments 取用. */
	private final Map<SourceSet, Path> mixinMappingsOutputs = new HashMap<>();

	protected AnnotationProcessorInvoker(Project project,
											Collection<Configuration> apConfigurations,
											Map<SourceSet, TaskProvider<T>> invokerTasks, String name) {
		this.project = project;
		this.loomExtension = LoomGradleExtension.get(project);
		this.mixinExtension = loomExtension.getMixin();
		this.apConfigurations = apConfigurations;
		this.invokerTasks = invokerTasks;
		this.name = name;
	}

	protected static Collection<Configuration> getApConfigurations(Project project, Function<SourceSet, String> getApConfigNameFunc) {
		MixinExtension mixin = LoomGradleExtension.get(project).getMixin();
		return mixin.getApConfigurationsStream(getApConfigNameFunc).collect(Collectors.toList());
	}

	protected abstract void passArgument(T compileTask, String key, String value);

	protected abstract File getRefmapDestinationDir(T task);

	protected final String getRefmapDestination(T task, String refmapName) throws IOException {
		return new File(getRefmapDestinationDir(task), refmapName).getCanonicalPath();
	}

	private void passMixinArguments(T task, SourceSet sourceSet) {
		try {
			LoomGradleExtension loom = LoomGradleExtension.get(project);
			String refmapName = Objects.requireNonNull(MixinExtension.getMixinInformationContainer(sourceSet)).refmapNameProvider().get();

			// 映射文件改由任务产出，而不是在配置期现场写：
			// getReplacedTarget 会立即读平台映射文件并写产物，把它放在这里就等于要求整条 mappings
			// 迁移在配置期完成。改为任务后，参数里给的仍是同一个路径，但文件何时落盘交给任务图。
			//
			// 注意：产出任务已在 configureMixin() 里、早于本方法登记好（见那里的注释）。
			// 这里不能再 register——本方法跑在任务的 configure 动作里，那个上下文禁止创建新任务。
			final Path mappings = Objects.requireNonNull(mixinMappingsOutputs.get(sourceSet),
					"Mixin mappings 产出任务未登记：" + sourceSet.getName());

			final File mixinMappings = getMixinMappingsForSourceSet(project, sourceSet);

			task.getOutputs().file(mixinMappings).withPropertyName("mixin-ap-" + sourceSet.getName()).optional();

			String refmapTargetNamespace = IntermediaryNamespaces.replaceMixinIntermediaryNamespace(project, loom.getMixin().getRefmapTargetNamespace().get());
			String capitalizedTargetNamespace = refmapTargetNamespace.substring(0, 1).toUpperCase(Locale.ROOT) + refmapTargetNamespace.substring(1);

			Map<String, String> args = new HashMap<>() {{
					put(Constants.MixinArguments.IN_MAP_FILE_NAMED + capitalizedTargetNamespace, mappings.toFile().getCanonicalPath());
					put(Constants.MixinArguments.OUT_MAP_FILE_NAMED + capitalizedTargetNamespace, mixinMappings.getCanonicalPath());
					put(Constants.MixinArguments.OUT_REFMAP_FILE, getRefmapDestination(task, refmapName));
					put(Constants.MixinArguments.DEFAULT_OBFUSCATION_ENV, "named:" + refmapTargetNamespace);
					put(Constants.MixinArguments.QUIET, "true");
				}};

			if (mixinExtension.getShowMessageTypes().get()) {
				args.put(Constants.MixinArguments.SHOW_MESSAGE_TYPES, "true");
			}

			mixinExtension.getMessages().get().forEach((key, value) -> {
				checkPattern(key, MSG_KEY_PATTERN);
				checkPattern(value, MSG_VALUE_PATTERN);

				args.put("MSG_" + key, value);
			});

			project.getLogger().debug("Outputting refmap to dir: " + getRefmapDestinationDir(task) + " for compile task: " + task);
			args.forEach((k, v) -> passArgument(task, k, v));
		} catch (IOException e) {
			project.getLogger().error("Could not configure mixin annotation processors", e);
		}
	}

	public void configureMixin() {
		ConfigurationContainer configs = project.getConfigurations();
		MinecraftSourceSets minecraftSourceSets = MinecraftSourceSets.get(project);

		// 先在本方法里（仍在配置期、且不在任何任务的 configure 动作内）登记 mappings 产出任务。
		// 不能推迟到下面的 passMixinArguments：那里跑在任务 configure 动作中，
		// Gradle 9 会以「DefaultTaskContainer#register(...) cannot be executed in the current context」拒绝。
		final LoomGradleExtension loom = LoomGradleExtension.get(project);
		final String targetNamespace = loom.getMixin().getRefmapTargetNamespace().get();

		for (Map.Entry<SourceSet, TaskProvider<T>> entry : invokerTasks.entrySet()) {
			mixinMappingsOutputs.put(entry.getKey(), registerMixinMappingsTask(loom, entry.getValue(), targetNamespace));
		}

		if (!IdeaUtils.isIdeaSync()) {
			for (Configuration processorConfig : apConfigurations) {
				project.getLogger().info("Adding mixin to classpath of AP config: " + processorConfig.getName());
				// Pass named MC classpath to mixin AP classpath
				processorConfig.extendsFrom(
						configs.named(Constants.Configurations.LOADER_DEPENDENCIES),
						configs.named(Constants.Configurations.MAPPINGS_FINAL)
				);

				// Add Mixin and mixin extensions (fabric-mixin-compile-extensions pulls mixin itself too)
				project.getDependencies().add(processorConfig.getName(),
						LoomVersions.MIXIN_COMPILE_EXTENSIONS.mavenNotation());
			}
		}

		for (Map.Entry<SourceSet, TaskProvider<T>> entry : invokerTasks.entrySet()) {
			entry.getValue().configure(t -> passMixinArguments(t, entry.getKey()));
		}
	}

	private static void checkPattern(String input, Pattern pattern) {
		final Matcher matcher = pattern.matcher(input);

		if (!matcher.find()) {
			throw new IllegalArgumentException("Mixin argument (%s) does not match pattern (%s)".formatted(input, pattern.toString()));
		}
	}

	/**
	 * 登记（或复用）产出 {@code mappings-mixin-<ns>.tiny} 的任务，并把本编译任务依赖它.
	 *
	 * <p>返回的路径与配置期 {@code getReplacedTarget} 完全一致（{@code <mappingsWorkingDir>/mappings-mixin-<ns>.tiny}），
	 * 保证 Mixin AP 拿到的仍是同一个位置；区别只在文件何时写出。
	 *
	 * <p>用 {@code RemapMinecraftTaskRegistry.claim} 而不是直接 register：同一产物路径在本构建内
	 * 只能有一个生产者（多个子项目可能算出同一条路径），且产出方可能来自另一份 Loom classloader。
	 *
	 * @param loom          当前项目的 loom 扩展
	 * @param compileTask   要接线依赖的编译任务
	 * @param namespace     refmap 目标命名空间
	 * @return 产物路径（供 {@code -AinMapFileNamed<Ns>=} 参数使用）
	 */
	private Path registerMixinMappingsTask(LoomGradleExtension loom, TaskProvider<T> compileTask, String namespace) {
		final var mappingConfiguration = loom.getMappingConfiguration();
		final Path output = mappingConfiguration.mappingsWorkingDir().resolve("mappings-mixin-" + namespace + ".tiny").toAbsolutePath().normalize();

		final String taskPath = RemapMinecraftTaskRegistry.claim(project, output, Map.of(
				"stage", "mixin-mappings",
				"namespace", namespace,
				"mappingsIdentifier", mappingConfiguration.mappingsIdentifier
		), () -> project.getTasks().register(GenerateMixinMappingsTask.NAME, GenerateMixinMappingsTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Generates the Mixin mappings for namespace %s".formatted(namespace));
			task.getPlatformMappings().set(loom.getMappingConfiguration().getPlatformMappingFile(loom).toFile());
			task.getNamespace().set(namespace);
			task.getOutputMappings().set(output.toFile());
			// 平台映射文件自身可能由迁移任务产出：按路径声明输入不会带任务依赖，必须显式接线
			loom.getMappingConfiguration().addMappingsProducerDependency(task);
		})).taskPath();

		if (taskPath != null) {
			// 仍在配置期（本方法只从 configureMixin 的循环里调用），可以在任务 configure 动作里接线依赖
			compileTask.configure(t -> t.dependsOn(taskPath));
		}

		return output;
	}

	public static File getMixinMappingsForSourceSet(Project project, SourceSet sourceSet) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		return new File(extension.getFiles().getProjectBuildCache(), "mixin-map-" + extension.getMappingConfiguration().mappingsIdentifier() + "." + sourceSet.getName() + ".tiny");
	}
}
