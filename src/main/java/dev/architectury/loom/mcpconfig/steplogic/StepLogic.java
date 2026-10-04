/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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

package dev.architectury.loom.mcpconfig.steplogic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import dev.architectury.loom.forge.config.ConfigValue;
import dev.architectury.loom.forge.tool.ForgeToolExecutor;
import dev.architectury.loom.util.collection.CollectionUtil;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;

/**
 * The logic for executing a step. This corresponds to the {@code type} key in the step JSON format.
 */
public abstract class StepLogic<O extends Service.Options> extends Service<O> {
	public StepLogic(O options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public abstract void execute(ExecutionContext context) throws IOException;

	public String getDisplayName(String stepName) {
		return stepName;
	}

	public interface ExecutionContext {
		Logger logger();
		Path setOutput(String fileName) throws IOException;
		Path setOutput(Path output);
		Path cache() throws IOException;
		/** Mappings extracted from {@code data.mappings} in the MCPConfig JSON. */
		Path mappings();
		String resolve(ConfigValue value);
		DownloadBuilder downloadBuilder(String url);
		void javaexec(Action<? super ForgeToolExecutor.Settings> configurator);

		/**
		 * 把工具 jar 就位（缺失才下载）并返回其路径.
		 *
		 * <p>下载被刻意推迟到执行期：配置期对「下载缓存中的文件」做存在性判断会被配置缓存记成
		 * 文件系统输入指纹，而该文件在任务结束时被清掉，下一次构建的存在性就与记录值相反——
		 * 于是配置缓存永远无法复用。执行期（任务动作内）的文件系统观察不会被记录，因此不会污染指纹。
		 *
		 * @param target 配置期纯计算出的落位路径
		 * @param url    下载地址
		 */
		Path ensureToolJar(Path target, String url) throws IOException;

		default List<String> resolve(List<ConfigValue> configValues) {
			return CollectionUtil.map(configValues, this::resolve);
		}
	}

	public interface SetupContext {
		Project project();

		/**
		 * {@return 该 URL 在下载缓存中的落位路径}.
		 *
		 * <p><b>纯路径计算，不做任何文件系统访问</b>：本方法在配置期被调用，一旦在这里判断文件是否存在
		 * （或顺手把文件下下来），配置缓存就会把该路径记成文件系统输入；而下载缓存位于任务自有工作目录内、
		 * 每次执行结束即删，存在性逐次翻转，配置缓存将永久失效。真正的下载见
		 * {@link ExecutionContext#ensureToolJar}。
		 */
		Path downloadCachePath(String url) throws IOException;

		Path downloadDependency(String notation);
		Provider<FileCollection> getMinecraftLibraries();
	}

	@FunctionalInterface
	public interface StepLogicProvider {
		@Nullable Provider<? extends Service.Options> getStepLogic(SetupContext context, String name, String type);
	}
}
