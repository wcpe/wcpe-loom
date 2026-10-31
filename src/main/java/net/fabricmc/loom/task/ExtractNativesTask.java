/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

import javax.inject.Inject;

import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.tasks.Sync;
import org.gradle.work.DisableCachingByDefault;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Constants;

/**
 * 下载并把 Minecraft 的平台相关 natives 解压到 natives 目录.
 *
 * <p>输入一律以 {@link org.gradle.api.provider.Provider} 形态登记：早先的实现在<b>构造函数里</b>遍历
 * {@code getFiles()}，任务一被 realize（哪怕本次构建根本不跑它，例如 {@code gradle help}）就会解析整个
 * {@code minecraftNatives} 配置——一次网络 + 磁盘动作，配置阶段白付，且让配置缓存无法避开.
 *
 * <p>改用 {@code named(...).flatMap(...).map(...)} 之后，解析推迟到「Gradle 真正需要这份文件树」时：
 * <ul>
 *   <li>任务不参与本次构建：配置与解析都不发生；</li>
 *   <li>任务参与构建：在输入指纹计算/执行时解析，与改造前等价（仍是同一个配置、同一批 jar）；</li>
 *   <li>up-to-date 与缓存行为不变——解析出来的文件仍是本任务的输入，供 {@link Sync} 比对快照。</li>
 * </ul>
 *
 * <p>产物仍是每个 natives jar 一棵 {@code zipTree}，各自套一份 {@code exclude}/{@code rename} 规则，
 * 与改造前逐 jar 构建 CopySpec 的形态一一对应，不改变叠加顺序之外的任何语义。
 */
@DisableCachingByDefault
public abstract class ExtractNativesTask extends Sync {
	@Inject
	protected abstract ArchiveOperations getArchiveOperations();

	@Inject
	public ExtractNativesTask() {
		// 只在「有人要这份文件树」时才解析配置：named().flatMap() 不立即求值，
		// 因此任务的 realize 本身不再触发 minecraftNatives 配置的解析。
		from(getProject().getConfigurations().named(Constants.Configurations.MINECRAFT_NATIVES).flatMap(configuration ->
				configuration.getElements().map(artifacts -> artifacts.stream()
						.map(artifact -> getArchiveOperations().zipTree(artifact.getAsFile()))
						.toList())), copySpec -> {
							copySpec.exclude("META-INF/**");
							// Fix pre LWJGL 3 versions on Macos. See: https://github.com/FabricMC/fabric-loom/issues/955
							copySpec.rename(s -> s.replace(".jnilib", ".dylib"));
						});

		into(LoomGradleExtension.get(getProject()).getFiles().getNativesDirectory(getProject()));

		setDescription("Downloads and extracts the minecraft natives");
	}
}
