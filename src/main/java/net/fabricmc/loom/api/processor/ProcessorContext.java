/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

package net.fabricmc.loom.api.processor;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.LazyCloseable;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;

public interface ProcessorContext {
	/**
	 * 本上下文服务的 jar 是否来自 split（客户端与服务端分开产出）配置.
	 *
	 * <p>split 配置会产出两个 jar，它们与 client only / server only 配置产出的 jar 在
	 * {@link #isMerged()}、{@link #includesClient()}、{@link #includesServer()} 上完全一致：
	 * 单看一个 jar 无法区分「这是 split 的一半」还是「本来就是单边配置」。
	 * 只有本方法能给出这一信息，需要在 split 下改变行为的 processor 必须用它判断。
	 *
	 * @return 本 jar 是否属于 split 配置
	 */
	boolean isSplit();

	boolean isMerged();

	boolean includesClient();

	boolean includesServer();

	LazyCloseable<TinyRemapper> createRemapper(MappingsNamespace from, MappingsNamespace to);

	MemoryMappingTree getMappings();

	boolean disableObfuscation();

	MappingsNamespace getProductionNamespace();

	/**
	 * 本平台在工具链中自带数据文件所用的命名空间：fabric/quilt 为 {@code intermediary}、
	 * forge 为 {@code srg}、neoforge 为 {@code mojang}.
	 *
	 * <p>它与 {@link #getProductionNamespace()} 不是一回事：后者可以被用户配置成
	 * {@code official} 之类的取值，而平台自带的文件（例如 Forge 的 {@code accesstransformer.cfg}）
	 * 始终写在平台自己的命名空间里。
	 *
	 * @return 平台默认命名空间
	 */
	MappingsNamespace getIntermediaryNamespace();
}
