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

package net.fabricmc.loom.configuration.mods.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.configuration.mods.dependency.ModDependency;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * An interface to aid with applying mod-specific remapping extensions.
 */
public interface ModProcessorExtension {
	List<ModProcessorExtension> EXTENSIONS = List.of(
			MixinRemap.INSTANCE,
			InlineRefmap.INSTANCE
	);

	/**
	 * Return true if the extension applies to the given mod dependency.
	 */
	boolean appliesTo(ModInfo mod);

	/**
	 * Create a TinyRemapper extension that uses the predicate to only apply to mods that match appliesTo.
	 */
	TinyRemapper.Extension createExtension(Context ctx, Predicate<InputTag> applyPredicate) throws IOException;

	void finalise(ModInfo mod, Path path) throws IOException;

	/**
	 * 一个 mod 在「是否 / 如何套用扩展」这件事上被用到的全部事实.
	 *
	 * <p>取代原先直接传 {@link ModDependency}：扩展本来就只用到输入 jar 路径与
	 * mixin 重映射类型、是否内联 refmap 这三项，其中前两项由 jar 内容派生、第三项是
	 * 声明的选项。收窄成纯数据后，配置期与执行期（L3 任务）都能满足同一份契约，
	 * 而不需要把依赖对象带进任务。
	 *
	 * @param inputJar 原始 mod jar
	 * @param mixinRemapType 由 jar 内容派生的 mixin 重映射类型
	 * @param inlineRefmap 声明的 refmap 内联选项
	 */
	record ModInfo(Path inputJar, ArtifactMetadata.MixinRemapType mixinRemapType, boolean inlineRefmap) { }

	/**
	 * 扩展装配上下文.
	 *
	 * @param from 源命名空间
	 * @param to 目标命名空间
	 * @param mixinModJars 本批次中 mixin 重映射类型为 MIXIN 的输入 jar（refmap 内联器需要）
	 */
	record Context(
			String from,
			String to,
			List<Path> mixinModJars) { }
}
