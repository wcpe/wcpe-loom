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

package net.fabricmc.loom.task;

import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.internal.LoomProjectData;

/**
 * 把当前项目的 {@link LoomProjectData} 导出为文件，供其它项目在内存共享表里查不到时回退读取.
 *
 * <p>输出通过 {@code loomProjectDataElements} 可消费配置暴露：消费方可以解析该配置（触发本任务），
 * 也可以直接读文件，所以它必须随项目身份与内容一起失效。本类保留 {@link CacheableTask}，
 * 同时把 {@link LoomProjectData#fromProject(org.gradle.api.Project)} 真正依赖的内容逐项声明为输入，
 * 原因有两点：
 *
 * <ul>
 *     <li>不声明输入时，Gradle 只按「输出是否存在」判定 UP-TO-DATE：改了 mod 元数据或映射标识之后文件不刷新，
 *         日常被内存共享表掩盖，只在回退路径与外部消费方那里暴露成过期数据；
 *     <li>构建缓存键里没有项目身份时，多个项目（甚至不同项目路径的工作副本）会算出同一个键，
 *         共享缓存可能把别的项目的 {@code project-data.json} 还原到本项目。
 * </ul>
 *
 * <p>所有输入都用 {@link org.gradle.api.provider.Provider} 惰性求值：配置期只登记「怎么取」，
 * 读取映射配置、mod 元数据与 Mixin AP 输出都发生在执行期，不会在配置阶段强制求值。
 */
@CacheableTask
public abstract class ExportLoomProjectDataTask extends DefaultTask {
	public ExportLoomProjectDataTask() {
		getOutputFile().convention(getProject().getLayout().getBuildDirectory().file("loom/project-data.json"));

		getProjectPath().convention(getProject().getPath());
		getMappingId().convention(getProject().provider(() -> LoomProjectData.readMappingId(getProject())));
		getProductionNamespace().convention(getProject().provider(() -> LoomProjectData.readProductionNamespace(getProject())));
		getSplitEnvironmentSourceSets().convention(getProject().provider(() -> LoomGradleExtension.get(getProject()).areEnvironmentSourceSetsSplit()));
		getModJson().convention(getProject().provider(() -> LoomProjectData.readModJson(getProject())));
		getMixinMappingFiles().convention(getProject().provider(() -> LoomProjectData.readMixinMappingFiles(getProject())));
	}

	/**
	 * 项目路径，作为数据身份参与缓存键，避免共享缓存在不同项目之间复用同一份数据.
	 */
	@Input
	public abstract Property<String> getProjectPath();

	/**
	 * 映射标识，与 {@link LoomProjectData#fromProject(org.gradle.api.Project)} 写入 DTO 的值同源.
	 *
	 * <p>映射尚未就绪或未启用混淆时该属性为空（用 {@code @Optional} 表达，而不是伪造一个占位值）。
	 */
	@Input
	@Optional
	public abstract Property<String> getMappingId();

	/**
	 * 生产命名空间，与 DTO 里写入的值同源；未配置时为空.
	 */
	@Input
	@Optional
	public abstract Property<String> getProductionNamespace();

	/**
	 * 是否拆分环境源集，与 DTO 里写入的值同源.
	 */
	@Input
	public abstract Property<Boolean> getSplitEnvironmentSourceSets();

	/**
	 * 项目内全部 mod 的元数据 JSON，与 DTO 中的 mod 列表一一对应.
	 */
	@Input
	public abstract ListProperty<String> getModJson();

	/**
	 * Mixin AP 映射文件的路径列表.
	 *
	 * <p>只登记路径而不是文件内容：DTO 里保存的也是路径，依赖方直接按路径读取生产者项目的文件，
	 * 文件内容不属于导出内容。反过来，把它们声明成 {@code @InputFiles} 会让本任务输入命中
	 * {@code compileJava} 的输出，从而引入未声明的隐式任务依赖（Gradle 会因此禁用执行优化）。
	 */
	@Input
	public abstract ListProperty<String> getMixinMappingFiles();

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	@TaskAction
	public void exportProjectData() throws IOException {
		LoomProjectData.fromProject(getProject()).write(getOutputFile().get().getAsFile().toPath());
	}
}
