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

package dev.architectury.loom.forge.minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;

public final class MergedForgeMinecraftProvider extends MergedMinecraftProvider implements ForgeMinecraftProvider {
	private MinecraftPatchedProvider patchedProvider;

	public MergedForgeMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		super(metadataProvider, configContext);
	}

	@Override
	protected void mergeJars() throws IOException {
		// Don't merge jars in the superclass

		if (getServerBundleMetadata() != null) {
			extractBundledServerJar();
		}
	}

	/**
	 * Forge 的合并形态不产出 {@code minecraft-merged.jar}，因此不登记基类的合并任务.
	 *
	 * <p>本形态的产物是 patched jar（见 {@link #getMinecraftJars()}），而 {@link #mergeJars()} 也不做合并、
	 * 只抽取内嵌 server jar——抽取本身已由基类生产段按 {@code serverBundleMetadata} 登记成
	 * {@code extractMinecraftServerJar}。沿用基类的登记会凭空多出一个无人消费的 {@code mergeMinecraftJars}：
	 * 它写的 {@code minecraft-merged.jar} 不在 {@link #getMinecraftJars()} 里，没有任何消费方，
	 * 却会白跑一次（并把两个 vanilla jar 的产出方拉进任务图）。
	 */
	@Override
	protected void registerProviderTasks(Map<Path, Producer> producers) {
	}

	@Override
	public Path getMergedJar() {
		return getPatchedProvider().getMinecraftPatchedJar();
	}

	/**
	 * patched jar 的路径要等 {@code MinecraftPatchedProvider.provide()} 里的 {@code initPatchedFiles()}
	 * 之后才有值（本形态的最终产物全部来自那条链），因此生产段登记产物时先问一句「能不能求值」.
	 *
	 * <p>不能求值即跳过：patch 链自己会走 {@code registerTaskProducedArtifact} 登记同一批产物
	 * （那时路径已经算出来了），登记是「以最后一次为准」的并进语义，不会丢。
	 */
	@Override
	protected boolean isFinalProductKnown() {
		return getPatchedProvider().getMinecraftPatchedJar() != null;
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(getPatchedProvider().getMinecraftPatchedJar());
	}

	@Override
	public MinecraftPatchedProvider getPatchedProvider() {
		if (this.patchedProvider == null) {
			// legacy Forge（1.8-1.16）走 FG2 的 patching 流程，需要专门的实现。
			// 判定依赖已解析的 userdev 配置（ForgeUserdevProvider.isLegacyForge 未解析时会抛异常），
			// 故延迟到这里而非构造函数。
			if (LoomGradleExtension.get(getProject()).isModernForgeLike()) {
				this.patchedProvider = new MinecraftPatchedProvider(getProject(), this, MinecraftPatchedProvider.Type.MERGED);
			} else {
				this.patchedProvider = new MinecraftLegacyPatchedProvider(getProject(), this, MinecraftPatchedProvider.Type.MERGED);
			}
		}

		return patchedProvider;
	}
}
