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

package net.fabricmc.loom.util.gradle;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.Constants;

/**
 * 每个构建、每个 Loom 版本仅输出一次版本提示，不读写 JVM 全局系统属性.
 *
 * <p>服务首次实例化时输出日志；registerIfAbsent 负责并行项目间的去重，生命周期由 Gradle 管理。
 * 不再使用配置期自修改的 loom.printed.logged，避免仅打印日志就污染下一次配置缓存指纹。
 */
public abstract class LoomVersionLogService implements BuildService<BuildServiceParameters.None> {
	public static void logVersion(Project project) {
		// 只触发首次实例化，不将自定义服务类型传入任务，避免不同插件 classloader 之间的类型耦合。
		project.getGradle().getSharedServices().registerIfAbsent(
				"loom-version-log-" + LoomGradlePlugin.LOOM_VERSION, LoomVersionLogService.class, spec -> { }
		).get();
	}

	@Inject
	public LoomVersionLogService() {
		final var logger = Logging.getLogger(LoomGradlePlugin.class);
		logger.lifecycle("Architectury Loom: " + LoomGradlePlugin.LOOM_VERSION);

		if (Constants.PLUGIN_BETA) {
			logger.lifecycle("This version of Architectury Loom is in beta! Please report any issues you encounter: https://github.com/architectury/architectury-loom/issues");
		} else if (Constants.PLUGIN_DEPRECATED) {
			logger.lifecycle("You are using an outdated version of Architectury Loom! This version will not receive any support, please consider updating!");
		}
	}
}
