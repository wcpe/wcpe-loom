/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom.configuration.ide.idea;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.StartParameter;
import org.gradle.TaskExecutionRequest;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.task.LoomTasks;
import net.fabricmc.loom.util.gradle.GradleUtils;

public abstract class IdeaConfiguration implements Runnable {
	@Inject
	protected abstract Project getProject();

	public void run() {
		getProject().getTasks().register("ideaSyncTask", IdeaSyncTask.class, task -> {
			if (LoomGradleExtension.get(getProject()).getRunConfigs().stream().anyMatch(config -> config.getGenerateRunConfig().get())) {
				task.dependsOn(LoomTasks.getIDELaunchConfigureTaskName(getProject()));
			} else {
				task.setEnabled(false);
			}
		});

		hookDownloadSources();

		if (!IdeaUtils.isIdeaSync()) {
			return;
		}

		final StartParameter startParameter = getProject().getGradle().getStartParameter();
		final List<TaskExecutionRequest> taskRequests = new ArrayList<>(startParameter.getTaskRequests());

		// This doesnt overwrite any existing task requests, use Gradle to create a TaskExecutionRequest for us before adding it to the list of existing ones.
		startParameter.setTaskNames(List.of("ideaSyncTask"));
		taskRequests.addAll(startParameter.getTaskRequests());
		startParameter.setTaskRequests(taskRequests);
	}

	private void hookDownloadSources() {
		// 每个 Loom 项目只登记自己可提供的源码任务；根项目再按任务路径字符串添加依赖。
		// 这样避免了从根项目遍历 allprojects 的跨项目访问，隔离项目模式下同样可用。
		//
		// 登记必须推迟到本项目配置完成之后：Minecraft 提供器由 CompileConfiguration 在自己的
		// afterEvaluate 中安装，而 SETUP_JOBS 里 CompileConfiguration 先于本类注册，两处的
		// afterEvaluate 动作按注册顺序执行，因此这里登记时提供器一定已就绪。
		// 若改回在插件 apply 期直接登记，提供器尚为空，getNamedMinecraftProvider() 抛出的 NPE
		// 会被 register() 静默吞掉，登记表将永远为空，ijDownloadSources 也就挂不上任何
		// genSources 任务（IDE 的「下载源码」因此失效）。
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> DownloadSourcesHook.register(getProject()));

		if (!GradleUtils.isRootProject(getProject())) {
			return;
		}

		if (!DownloadSourcesHook.hasInitScript(getProject())) {
			return;
		}

		getProject().getTasks().configureEach(task -> {
			if (task.getName().startsWith(DownloadSourcesHook.INIT_SCRIPT_NAME)) {
				new DownloadSourcesHook(getProject(), task).tryHook();
			}
		});
	}
}
