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

package net.fabricmc.loom.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Manifest;

import dev.architectury.loom.neoforge.NeoForgeModDependencies;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * L4 计算核心：重映射后、与项目无关的 mod jar 后处理步骤.
 *
 * <p>这些步骤原本内联在 {@code ModProcessor.remapJars} 的收尾循环里，与若干**依赖项目模型**
 * 的步骤交错：Forge 的 AT 类名重映射、CoreMod 改写、以及把产物写入本地 maven 仓库都需要
 * {@code Project}。把纯步骤抽出来，是为了让「任务化」时能复用同一份实现，而不必连带搬走
 * 那些搬不动的部分。
 *
 * <h2>为什么是四个独立方法而不是一个 apply()</h2>
 * 原本的执行顺序里夹着一处扩展钩子
 * （{@code ModProcessorExtension.finalise}，位于 AW 替换与去嵌套之间）。
 * 若把它们合成一次调用，钩子就会被挤到前面或后面，改变既有行为。
 * 拆成独立步骤后，调用方保留原有的交错顺序，抽取本身不引入时序变化。
 */
public final class ModJarPostProcess {
	private ModJarPostProcess() {
	}

	/**
	 * 把重映射后的 access widener 写回 jar.
	 *
	 * <p>{@code accessWidener} 为「已重映射的字节 + jar 内路径」；为空表示该 mod 不含 AW。
	 */
	public static void replaceAccessWidener(Path jar, @Nullable Pair<byte[], String> accessWidener) throws IOException {
		if (accessWidener != null) {
			ZipUtils.replace(jar, accessWidener.right(), accessWidener.left());
		}
	}

	/**
	 * 去掉嵌套 jar 的元数据.
	 *
	 * <p>开发环境中这些嵌套 jar 不该由 loader 加载，否则会被当作重复的模组。
	 */
	public static void stripNestedJars(Path jar) throws IOException {
		ZipUtils.deleteIfExists(jar, "META-INF/jarjar/metadata.json");
	}

	/**
	 * 在 manifest 中记录本产物所处的映射命名空间.
	 *
	 * @param targetNamespace 产物落位的命名空间
	 */
	public static void writeMappingNamespace(Path jar, String targetNamespace) throws IOException {
		ZipUtils.transform(jar, Map.of(Constants.Manifest.PATH, bytes -> {
			final var manifest = new Manifest(new ByteArrayInputStream(bytes));

			manifest.getMainAttributes().putValue(Constants.Manifest.MAPPING_NAMESPACE, targetNamespace);

			final var out = new ByteArrayOutputStream();
			manifest.write(out);
			return out.toByteArray();
		}));
	}

	/**
	 * 全量重映射 NeoForge 的 access transformer.
	 *
	 * <p>仅 NeoForge 需要：Forge 只在类名层面映射，其余留到运行时按 srg → named 处理。
	 */
	public static void remapNeoForgeAts(Path jar, MemoryMappingTree mappings, String productionNamespace, String targetNamespace) throws IOException {
		NeoForgeModDependencies.remapAts(jar, mappings, productionNamespace, targetNamespace);
	}
}
