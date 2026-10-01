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

package net.fabricmc.loom;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * A marker plugin to indicate to the main loom plugin not to setup for remapping.
 */
public class LoomNoRemapGradlePlugin implements Plugin<Project> {
	public static final String NAME = "top.wcpe.loom-no-remap";
	/** 上一层分叉（architectury）的插件 id，作为兼容别名保留. */
	public static final String LEGACY_NAME = "dev.architectury.loom-no-remap";

	/** 本项目是否已应用 no-remap 变体（新旧任一 id）. */
	public static boolean isApplied(Project project) {
		return project.getPluginManager().hasPlugin(NAME) || project.getPluginManager().hasPlugin(LEGACY_NAME);
	}

	@Override
	public void apply(Project target) {
		if (LoomGradlePlugin.isApplied(target)) {
			throw new IllegalStateException(NAME + " must be applied before " + LoomGradlePlugin.NAME);
		}

		target.getPlugins().apply(LoomGradlePlugin.NAME);
	}
}
