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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.internal.LoomProjectData;

/**
 * 把当前项目的 {@link LoomProjectData} 导出为文件，供其它项目在内存共享表里查不到时回退读取.
 *
 * <p>输出通过 {@code loomProjectDataElements} 可消费配置暴露：消费方可以解析该配置（触发本任务），
 * 也可以直接读文件，所以它必须随项目身份与内容一起失效。本类保留 {@link CacheableTask}，
 * 并把 {@link LoomProjectData#fromProject(org.gradle.api.Project)} 的**完整结果**登记为唯一输入，
 * 原因有三点：
 *
 * <ul>
 *     <li>不声明输入时，Gradle 只按「输出是否存在」判定 UP-TO-DATE：改了 mod 元数据或映射标识之后文件不刷新，
 *         日常被内存共享表掩盖，只在回退路径与外部消费方那里暴露成过期数据；
 *     <li>构建缓存键里没有项目身份时，多个项目（甚至不同项目路径的工作副本）会算出同一个键，
 *         共享缓存可能把别的项目的 {@code project-data.json} 还原到本项目；
 *     <li>DTO 里的每个 mod 还带着 {@code resources}（mixin 配置、AT/AW、javadoc、原始元数据文件的**字节内容**），
 *         依赖方靠它重建模组视图。早期只把 JSON 字符串登记成输入，这些字节变化不会让本任务失效，
 *         构建缓存可能还原出「JSON 是新的、资源是旧的」的错配数据。
 * </ul>
 *
 * <p><b>执行期不得访问 {@link org.gradle.api.Project}</b>：整个 DTO 在配置期由
 * {@link #getProjectData()} 的 convention 求值并序列化，{@link #exportProjectData()} 只做写文件。
 * 早期的实现在 {@code @TaskAction} 里调用 {@code fromProject(getProject())}，开启配置缓存后
 * 会抛 {@code InvalidUserCodeException}——本任务曾因此完全无法在配置缓存下运行。
 */
@CacheableTask
public abstract class ExportLoomProjectDataTask extends DefaultTask {
	public ExportLoomProjectDataTask() {
		getOutputFile().convention(getProject().getLayout().getBuildDirectory().file("loom/project-data.json"));

		// 在配置期把 DTO 完整算出来：Provider 的求值发生在配置阶段收尾，
		// 此时读映射配置、mod 元数据与 Mixin AP 输出都是合法的；执行期只剩反序列化与写盘。
		getProjectData().convention(getProject().provider(() -> LoomProjectData.fromProject(getProject())));
	}

	/**
	 * 要导出的跨项目数据，作为唯一输入参与指纹与构建缓存键.
	 *
	 * <p>直接登记 {@link LoomProjectData}（而非拆成若干字符串）：它实现了 {@link java.io.Serializable}，
	 * 既能让配置缓存正确存取，也能让缓存键覆盖 mod 资源字节，避免「JSON 新、资源旧」的错配。
	 * 项目身份（{@code projectPath}）本来就在 DTO 里，因此不必再单独声明输入。
	 */
	@Input
	public abstract Property<LoomProjectData> getProjectData();

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	@TaskAction
	public void exportProjectData() throws IOException {
		// 只读输入属性：执行期访问 Project 会被 Gradle 直接拒绝（配置缓存下抛 InvalidUserCodeException）。
		getProjectData().get().write(getOutputFile().get().getAsFile().toPath());
	}
}
