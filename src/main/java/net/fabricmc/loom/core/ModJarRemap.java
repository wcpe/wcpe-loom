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

import java.io.IOException;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyRemapper.AnalyzeVisitorProvider;

/**
 * L4 计算核心：构建用于 mod jar 重映射的 {@link TinyRemapper}.
 *
 * <h2>为什么需要这个类</h2>
 * 本仓库里同时存在**三条** remapper 构造路径，各自的配置并不相同：
 *
 * <table>
 *   <caption>三条构造路径</caption>
 *   <tr><th>用途</th><th>构造点</th><th>特征</th></tr>
 *   <tr><td>Minecraft jar</td><td>{@link TinyRemapperHelper#getTinyRemapper}</td>
 *       <td>{@code renameInvalidLocals(true)} + {@code invalidLvNamePattern} +
 *           {@code inferNameFromSameLvIndex(true)} + {@code ignoreConflicts} +
 *           {@code rebuildSourceFilenames}；针对混淆 jar 的 LVT 特性</td></tr>
 *   <tr><td>mod jar</td><td>本类</td>
 *       <td>{@code renameInvalidLocals(false)}；不启用上述 LVT 处理</td></tr>
 *   <tr><td>任务型重映射</td><td>{@code TinyRemapperService}</td>
 *       <td>不设置上述任何一项，走 {@code MappingsService} 的映射提供者</td></tr>
 * </table>
 *
 * <p>三者**有意不同**，不能统一——把 MC jar 的 LVT 设置套到 mod jar 上会改变产物。
 * 但也正因为如此，「任务化」时极易拿错配置：{@code RemapMinecraftTask} 的等价性
 * 验证就是这样抓到过一次回归（任务用了 service 的构造，产出与配置期逐字节不同）。
 *
 * <p>本类的职责是把 mod jar 这条路径的构造**固定在一个地方**，
 * 让 {@code ModProcessor} 与将来的任务实现共用它，从而不可能各自漂移。
 */
public final class ModJarRemap {
	private ModJarRemap() {
	}

	/**
	 * 构建 mod jar 重映射器.
	 *
	 * @param mappings 已解析的映射树；必须与调用方其余环节用的是同一份
	 * @param knownIndyBsms 已知的 indy BSM 集合（含各 mod 元数据里声明的）
	 * @param productionNamespace 源命名空间（通常是项目的生产命名空间）
	 * @param targetNamespace 目标命名空间（mod 重映射固定为 named）
	 * @param analyzeVisitor 额外的 analyze visitor；无则为空
	 * @param extensions 追加配置（kotlin 扩展、mod processor 扩展、remapper 扩展）
	 *                   由调用方按既有顺序应用，顺序影响 visitor 链结果
	 * @return 已配置但尚未 {@code build()} 的 builder，便于调用方读取环境或追加配置
	 */
	/**
	 * 追加 remapper 配置的回调.
	 *
	 * <p>允许抛出 {@link IOException}：既有实现里有若干构造步骤（例如 kotlin classloader、
	 * mod processor 扩展）本身会抛受检异常。用普通 {@code Consumer} 会迫使调用方把它包成
	 * 非受检异常，改变原有的异常传播语义。
	 */
	@FunctionalInterface
	public interface Extensions {
		void accept(TinyRemapper.Builder builder) throws IOException;
	}

	public static TinyRemapper.Builder createRemapper(
			MemoryMappingTree mappings,
			Set<String> knownIndyBsms,
			String productionNamespace,
			String targetNamespace,
			@Nullable AnalyzeVisitorProvider analyzeVisitor,
			Extensions extensions) throws IOException {
		TinyRemapper.Builder builder = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE)
				.withKnownIndyBsm(knownIndyBsms)
				.withMappings(TinyRemapperHelper.create(mappings, productionNamespace, targetNamespace, true, true))
				.renameInvalidLocals(false);

		if (analyzeVisitor != null) {
			builder.extraAnalyzeVisitor(analyzeVisitor);
		}

		extensions.accept(builder);
		return builder;
	}
}
