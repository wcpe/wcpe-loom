/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2025 FabricMC
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

package net.fabricmc.loom.task;

import java.io.File;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskOutputs;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.task.launch.GenerateDLIConfigTask;
import net.fabricmc.loom.task.launch.GenerateLog4jConfigTask;
import net.fabricmc.loom.task.launch.GenerateRemapClasspathTask;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public abstract class LoomTasks implements Runnable {
	@Inject
	protected abstract Project getProject();

	@Inject
	protected abstract TaskContainer getTasks();

	@Override
	public void run() {
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		getTasks().named("exportLoomProjectData", task -> task.setDescription("导出隔离项目所需的项目数据"));

		if (!extension.disableObfuscation()) {
			registerMigrateMappingsTasks();
		}

		var generateLog4jConfig = getTasks().register("generateLog4jConfig", GenerateLog4jConfigTask.class, t -> {
			t.setDescription("Generate the log4j config file");
		});

		TaskProvider<GenerateRemapClasspathTask> generateRemapClasspath = null;

		if (!extension.disableObfuscation()) {
			generateRemapClasspath = getTasks().register("generateRemapClasspath", GenerateRemapClasspathTask.class, t -> {
				t.setDescription("Generate the remap classpath file");
			});
		}

		// Make the lambda happy
		final TaskProvider<GenerateRemapClasspathTask> generateRemapClasspathTask = generateRemapClasspath;

		getTasks().register("generateDLIConfig", GenerateDLIConfigTask.class, t -> {
			t.setDescription("Generate the DevLaunchInjector config file");

			// Must allow these IDE files to be generated first
			t.mustRunAfter("eclipse");

			t.dependsOn(generateLog4jConfig);

			if (!extension.disableObfuscation()) {
				GenerateRemapClasspathTask remapClasspath = Objects.requireNonNull(generateRemapClasspathTask.get());
				t.getRemapClasspathFile().set(remapClasspath.getRemapClasspathFile());
			}

			// srg→named 的产出任务：本任务只把它的**路径**当字符串写进 DLI 配置
			// （见 GenerateDLIConfigTask.ForgeInputs 的 net.minecraftforge.gradle.GradleStart.srg.srg-mcp），
			// Gradle 看不见这条输入依赖，故显式声明——否则 runClient / IDE 同步可能在产出任务之前跑完，
			// 而游戏启动时按该路径打开 srg 文件会失败。
			// 用 Provider 延迟取值：产出任务在 afterEvaluate（映射阶段）才登记，本注册块在插件 apply 时就跑。
			t.dependsOn(getProject().provider(() -> {
				if (extension.disableObfuscation() || !extension.isForge()) {
					return List.of();
				}

				final String srgTask = extension.getMappingConfiguration().getSrgNamedTaskPath();
				return srgTask == null ? List.of() : List.of(srgTask);
			}));
		});

		getTasks().register("configureLaunch", task -> {
			task.dependsOn(getTasks().named("generateDLIConfig"));
			task.dependsOn(getTasks().named("generateLog4jConfig"));

			if (!extension.disableObfuscation()) {
				task.dependsOn(getTasks().named("generateRemapClasspath"));
			}

			task.setDescription("Setup the required files to launch Minecraft");
			task.setGroup(Constants.TaskGroup.FABRIC);
		});

		TaskProvider<ValidateAccessWidenerTask> validateAccessWidener = getTasks().register("validateAccessWidener", ValidateAccessWidenerTask.class, t -> {
			t.setDescription("Validate all the rules in the access widener against the Minecraft jar");
			t.setGroup("verification");
		});

		getTasks().named("check").configure(task -> task.dependsOn(validateAccessWidener));

		registerIDETasks();
		registerRunTasks();
		registerForgeSourceExtractionTask(extension);

		// Must be done in afterEvaluate to allow time for the build script to configure the jar config.
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> {
			if (extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.SERVER_ONLY) {
				// Server only, nothing more to do.
				return;
			}

			final MinecraftVersionMeta versionInfo = extension.getMinecraftProvider().getVersionInfo();

			if (versionInfo == null) {
				// Something has gone wrong, don't register the task.
				return;
			}

			registerClientSetupTasks(getTasks(), versionInfo.hasNativesToExtract());
		});
	}

	/**
	 * 注册声明式解压 Forge 安装器源码包的任务.
	 *
	 * <p>该任务把“归档 -> 输出目录”的转换交给 Gradle 建模：单一 {@code @OutputDirectory} 输出便于构建缓存复用，
	 * 同时避免与其它任务产生重叠输出。平台不是 Forge-like，或 userdev 配置尚未就绪时直接跳过注册，
	 * 由 {@code ForgeSourcesService} 回退到内联解压路径。
	 */
	private void registerForgeSourceExtractionTask(LoomGradleExtension extension) {
		if (!extension.isForgeLike()) {
			return;
		}

		getTasks().register(ExtractArchiveFilesTask.FORGE_SOURCES_TASK_NAME, ExtractArchiveFilesTask.class, task -> {
			task.setDescription("展开 Forge 安装器源码包，供 genSources 复用");

			// 只保留 Java 源码条目：源码包里的资源文件不参与后续重映射。
			task.includeSuffixes("**/*.java");
			task.emptyMarker(ExtractArchiveFilesTask.EMPTY_MARKER_FILE);

			task.getOutputDirectory().convention(
					getProject().getLayout().getBuildDirectory().dir(ExtractArchiveFilesTask.FORGE_SOURCES_OUTPUT_DIRECTORY));

			task.getArchives().from(getProject().provider(() -> {
				final String sources = extension.getForgeUserdevProvider().getConfig().sources();
				return dev.architectury.loom.util.DependencyDownloader.download(getProject(), sources);
			}));
		});
	}

	private void registerMigrateMappingsTasks() {
		SourceSetHelper.getSourceSets(getProject()).all(sourceSet -> {
			if (SourceSetHelper.isMainSourceSet(sourceSet)) {
				getTasks().register("migrateMappings", MigrateMappingsTask.class, t -> {
					t.setDescription("Migrates source code mappings to a new version.");
				});

				return;
			}

			if (!SourceSetHelper.getFirstSrcDir(sourceSet).exists()) {
				return;
			}

			getTasks().register(sourceSet.getTaskName("migrate", "mappings"), MigrateMappingsTask.class, t -> {
				t.setDescription("Migrates source code mappings to a new version.");
				t.getInputDir().set(SourceSetHelper.getFirstSrcDir(sourceSet));
				t.getOutputDir().convention(getProject().getLayout().getProjectDirectory().dir(sourceSet.getTaskName("remapped", "src")));
			});
		});

		getTasks().register("migrateClassTweakerMappings", MigrateClassTweakerMappingsTask.class, t -> {
			t.setDescription("Migrates access widener and class tweaker mappings to a new version.");
		});
	}

	private void registerIDETasks() {
		getTasks().register("genEclipseRuns", GenEclipseRunsTask.class, t -> {
			t.setDescription("Generates Eclipse run configurations for this project.");
			t.dependsOn(getIDELaunchConfigureTaskName(getProject()));
			t.setGroup(Constants.TaskGroup.IDE);
		});

		getTasks().register("vscode", GenVsCodeProjectTask.class, t -> {
			t.setDescription("Generates VSCode launch configurations.");
			t.dependsOn(getIDELaunchConfigureTaskName(getProject()));
			t.setGroup(Constants.TaskGroup.IDE);
		});
	}

	public static String getRunConfigTaskName(RunConfiguration config) {
		String configName = config.getName();
		return "run" + configName.substring(0, 1).toUpperCase() + configName.substring(1);
	}

	private void registerRunTasks() {
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final boolean renderDocSupported = RenderDocRunTask.isSupported(Platform.CURRENT);

		Check.require(extension.getRunConfigs().isEmpty(), "Run configurations must not be registered before loom");

		extension.getRunConfigs().whenObjectAdded(config -> {
			var runTask = getTasks().register(getRunConfigTaskName(config), RunGameTask.class, config);

			runTask.configure(t -> {
				t.setDescription("Starts the '" + config.getName() + "' run configuration");
				t.dependsOn(config.getRuntimeEnvironment().map(env -> env.equals("client") ? "configureClientLaunch" : "configureLaunch"));
			});

			if (config.getName().equals("client") && renderDocSupported) {
				getTasks().register("runClientRenderDoc", RenderDocRunTask.class, config);
			}
		});

		if (renderDocSupported) {
			configureRenderDocTasks();
		}

		extension.getRunConfigs().whenObjectRemoved(runConfigSettings -> {
			getTasks().named(getRunConfigTaskName(runConfigSettings), task -> {
				// Disable the task so it can't be run
				task.setEnabled(false);
				task.setGroup("other");
			});
		});

		extension.getRunConfigs().create("client", RunConfiguration::client);
		extension.getRunConfigs().create("server", RunConfiguration::server);

		// Remove the client or server run config when not required. Done by name to not remove any possible custom run configs
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> {
			String taskName;

			boolean serverOnly = extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.SERVER_ONLY;
			boolean clientOnly = extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.CLIENT_ONLY;

			if (serverOnly) {
				// Server only, remove the client run config
				taskName = "client";
			} else if (clientOnly) {
				// Client only, remove the server run config
				taskName = "server";
			} else {
				return;
			}

			extension.getRunConfigs().removeIf(settings -> settings.getName().equals(taskName)
					|| settings.getName().equals(taskName + "RenderDoc"));
		});
	}

	private void configureRenderDocTasks() {
		final Platform.OperatingSystem operatingSystem = Platform.CURRENT.getOperatingSystem();
		final String renderDocVersion = LoomVersions.RENDERDOC.version();
		final String renderDocBaseName = operatingSystem.isWindows()
				? "RenderDoc_%s_64".formatted(renderDocVersion)
				: "renderdoc_%s".formatted(renderDocVersion);
		final String renderDocFilename = operatingSystem.isWindows()
				? "%s.zip".formatted(renderDocBaseName)
				: "%s.tar.gz".formatted(renderDocBaseName);
		final String renderDocUrl = "https://maven.fabricmc.net/org/renderdoc/%s".formatted(renderDocFilename);
		final String executableExt = operatingSystem.isWindows() ? ".exe" : "";

		var downloadRenderDoc = getTasks().register("downloadRenderDoc", DownloadTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);

			task.getUrl().set(renderDocUrl);
			task.getOutput().set(getProject().getLayout().getBuildDirectory().file(renderDocFilename));
		});

		var extractRenderDoc = getTasks().register("extractRenderDoc", Sync.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);

			if (operatingSystem.isWindows()) {
				task.from(getProject().zipTree(downloadRenderDoc.flatMap(DownloadTask::getOutput)));
			} else {
				task.from(getProject().tarTree(downloadRenderDoc.flatMap(DownloadTask::getOutput)));
			}

			task.into(getProject().getLayout().getBuildDirectory().dir("renderdoc"));
		});

		Provider<File> renderDocDir = extractRenderDoc.map(Sync::getOutputs)
				.map(TaskOutputs::getFiles)
				.map(FileCollection::getSingleFile)
				.map(dir -> new File(dir, renderDocBaseName));

		if (operatingSystem.isLinux()) {
			renderDocDir = renderDocDir.map(dir -> new File(dir, "bin"));
		}

		Provider<File> renderDocCMD = renderDocDir.map(dir -> new File(dir, "renderdoccmd" + executableExt));
		Provider<File> renderDocUI = renderDocDir.map(dir -> new File(dir, "qrenderdoc" + executableExt));

		getTasks().register("startRenderDocUI", RenderDocRunUITask.class, task -> task.getRenderDocExecutable().fileProvider(renderDocUI));

		getTasks().withType(RenderDocRunTask.class).configureEach(task -> {
			task.getRenderDocExecutable().fileProvider(renderDocCMD);
		});
	}

	private static void registerClientSetupTasks(TaskContainer tasks, boolean extractNatives) {
		tasks.register("downloadAssets", DownloadAssetsTask.class, t -> {
			t.setDescription("Downloads required game assets for Minecraft.");
		});

		if (extractNatives) {
			tasks.register("extractNatives", ExtractNativesTask.class, t -> {
				t.setDescription("Extracts the Minecraft platform specific natives.");
			});
		}

		tasks.register("configureClientLaunch", task -> {
			task.dependsOn(tasks.named("downloadAssets"));
			task.dependsOn(tasks.named("configureLaunch"));

			if (extractNatives) {
				task.dependsOn(tasks.named("extractNatives"));
			}

			task.setDescription("Setup the required files to launch the Minecraft client");
			task.setGroup(Constants.TaskGroup.FABRIC);
		});
	}

	public static Provider<Task> getIDELaunchConfigureTaskName(Project project) {
		return project.provider(() -> {
			final MinecraftJarConfiguration jarConfiguration = LoomGradleExtension.get(project).getMinecraftJarConfiguration().get();
			final String name = jarConfiguration == MinecraftJarConfiguration.SERVER_ONLY ? "configureLaunch" : "configureClientLaunch";
			return project.getTasks().getByName(name);
		});
	}
}
