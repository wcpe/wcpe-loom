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

package net.fabricmc.loom.configuration.decompile;

import java.util.List;

import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MappedMinecraftProvider;
import net.fabricmc.loom.task.GenerateForgePatchedSourcesTask;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.util.Constants;

public class SingleJarDecompileConfiguration extends DecompileConfiguration<MappedMinecraftProvider> {
	public SingleJarDecompileConfiguration(Project project, MappedMinecraftProvider minecraftProvider) {
		super(project, minecraftProvider);
	}

	@Override
	public String getTaskName(MinecraftJar.Type type) {
		return "genSources";
	}

	@Override
	public final void afterEvaluation() {
		final List<MinecraftJar> minecraftJars = minecraftProvider.getMinecraftJars();
		assert minecraftJars.size() == 1;
		final MinecraftJar minecraftJar = minecraftJars.get(0);
		final String taskBaseName = getTaskName(minecraftJar.getType());

		LoomGradleExtension.get(project).getDecompilerOptions().forEach(options -> {
			final String decompilerName = options.getFormattedName();
			String taskName = "%sWith%s".formatted(taskBaseName, decompilerName);
			// Decompiler will be passed to the constructor of GenerateSourcesTask
			project.getTasks().register(taskName, GenerateSourcesTask.class, options).configure(task -> {
				task.getInputJarName().set(minecraftJar.getName());
				task.getSourcesOutputJar().fileValue(GenerateSourcesTask.getJarFileWithSuffix("-sources.jar", minecraftJar.getPath()));

				task.dependsOn(project.getTasks().named("validateAccessWidener"));
				task.setDescription("Decompile minecraft using %s.".formatted(decompilerName));
				task.setGroup(Constants.TaskGroup.FABRIC);
			});
		});

		project.getTasks().register(taskBaseName, task -> {
			task.setDescription("Decompile minecraft using the default decompiler.");
			task.setGroup(Constants.TaskGroup.FABRIC);

			task.dependsOn(project.getTasks().named("genSourcesWith" + DecompileConfiguration.DEFAULT_DECOMPILER));
		});

		// TODO: Support for env-only jars?
		if (extension.isForge() && !extension.isLegacyForge() && extension.getMinecraftJarConfiguration().get() == MinecraftJarConfiguration.MERGED) {
			project.getTasks().register("genForgePatchedSources", GenerateForgePatchedSourcesTask.class, task -> {
				task.setDescription("Decompile Minecraft using Forge's toolchain.");
				task.setGroup(Constants.TaskGroup.FABRIC);

				task.getInputJar().set(MinecraftPatchedProvider.get(project).getMinecraftIntermediateJar().toFile());
				// inputJar 是 Forge 的 pre-patch jar：投影成任务后它只在执行期落位，而上面那行只是按**裸路径**
				// 声明输入，不携带产出方。故显式接上产出任务依赖（惰性查询：本方法注册得比 provide() 更早，
				// 未投影时该 provider 无值，Gradle 会忽略它，与改造前语义一致）。
				task.dependsOn(project.provider(() -> MinecraftPatchedProvider.get(project).getPrePatchJarProducerTaskPath()));
				task.getRuntimeJar().set(minecraftJar.toFile());
				// runtimeJar 是 named 命名空间的 MC jar，即 mapped provider 的产物；生产链迁移后它只在执行期
				// 由重映射任务（RemapMinecraftTask / ProcessMinecraftJarTask）落位，而上面那行只是按**裸路径**
				// 声明输入，不携带产出方——冷缓存下 Gradle 会在输入校验处以
				// 「property 'runtimeJar' specifies file ... which doesn't exist」直接失败（实测 Forge 1.19.2），
				// 因为没有任何任务被要求先产出它。故显式接上该命名空间的产出任务依赖：登记过任务产出时
				// 这份集合由产出任务派生（见 LoomGradleExtension#setMinecraftJarsTaskOutputs），
				// 未登记（配置期回退）时是纯文件集合，dependsOn 无副作用，与改造前语义一致。
				task.dependsOn(extension.getMinecraftJarsCollection(MappingsNamespace.NAMED));
			});
		}
	}
}
