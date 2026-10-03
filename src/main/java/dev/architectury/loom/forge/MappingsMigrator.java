/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

package dev.architectury.loom.forge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public interface MappingsMigrator {
	/**
	 * 一次迁移所需的全部输入.
	 *
	 * <p><b>刻意不含 {@code Project}</b>：同一份实现被配置期回退路径与执行期任务共用，而任务在
	 * 配置缓存开启时不允许在执行期触碰 {@code Task.project}（Gradle 会直接以
	 * {@code Invocation of 'Task.project' ... is unsupported with the configuration cache} 拒绝执行）。
	 * 因此配置期路径负责把「项目模型里读到的值」折算成这里的纯值，执行期任务给出它自己声明的输入。
	 *
	 * @param cache 迁移器缓存所在目录（与 {@code ForgeProvider.getForgeCache} 同一个）
	 * @param rawMappings 迁移前、已按命名空间合并好的映射树
	 * @param patchedIntermediateJar 打补丁后的中间 Minecraft jar（缓存未命中时才读）
	 * @param forgeJar Forge 的 universal jar（继承关系来源之一）
	 * @param userdevJar Forge 的 userdev jar（同上）
	 * @param hasSrg 本配置是否生成 srg 命名空间的映射
	 * @param hasMojang 本配置是否生成 mojang 命名空间的映射
	 * @param refreshDeps 是否显式要求刷新（刷新时一律走重建分支、忽略缓存）
	 * @param info 日志出口：配置期路径传项目 logger，执行期任务传任务自己的 logger
	 */
	record Inputs(Path cache, Path rawMappings, Path patchedIntermediateJar, Path forgeJar, Path userdevJar,
			boolean hasSrg, boolean hasMojang, boolean refreshDeps, Consumer<String> info) {
	}

	/**
	 * 准备本次迁移所需的中间数据，并返回参与「迁移产物就绪标记」的哈希.
	 *
	 * <p>缓存未命中时本方法要按路径读 patched 中间产物（{@link FieldMappingsMigrator} 读它的字段描述符、
	 * {@link MethodInheritanceMappingsMigrator} 读它的继承关系），因此该路径必须由调用方**显式给出**
	 * （见 {@link Inputs#patchedIntermediateJar()}）：配置期路径给的是自己刚产出的那一件，
	 * 执行期任务给的是它声明的 {@code @InputFile}。早先这里由实现内部向 {@code MinecraftPatchedProvider}
	 * 现取，那正是「配置期按路径读执行期产物」的根源。
	 */
	long setup(Inputs inputs) throws IOException;

	void migrate(List<MappingsEntry> entries, Consumer<String> info) throws IOException;

	record MappingsEntry(Path path) {
	}
}
