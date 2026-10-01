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

package net.fabricmc.loom.configuration.mods.dependency.refmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.configuration.mods.dependency.ModDependency;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.mixin.MixinConfiguration;

public class MixinRefmapInliner {
	private static final Logger LOGGER = LoggerFactory.getLogger(MixinRefmapInliner.class);

	/**
	 * 从若干 mod jar 构建 refmap 内联器.
	 *
	 * <p>只读取各 jar 的 {@code fabric.mod.json}，不依赖 {@link ModDependency} 的项目状态，
	 * 因此可在执行期调用（L3 任务路径）。调用方负责按 mixin 重映射类型预先筛选 jar。
	 */
	public static MixinReferenceRemapper createFromModJars(String from, String to, List<Path> mixinModJars) throws IOException {
		List<MixinConfiguration> mixinConfigurations = new ArrayList<>();

		for (Path modJar : mixinModJars) {
			FabricModJson fabricModJson = FabricModJsonFactory.createFromZipNullable(modJar);

			if (fabricModJson == null) {
				LOGGER.warn("Failed to read fabric.mod.json from {}", modJar);
				continue;
			}

			try {
				mixinConfigurations.addAll(MixinConfiguration.fromMod(fabricModJson));
			} catch (IOException e) {
				throw ExceptionUtil.createDescriptiveWrapper(IOException::new, "Failed to read mixin configuration from " + modJar, e);
			}
		}

		return MixinReferenceRemapperImpl.createFromRefmaps(from, to, mixinConfigurations.stream().map(MixinConfiguration::refmap));
	}

	/** 当前为空实现：refmap 已在 {@link #createFromModJars} 阶段内联，无需再移除. */
	public static void removeRefmap(Path outputPath) {
	}
}
