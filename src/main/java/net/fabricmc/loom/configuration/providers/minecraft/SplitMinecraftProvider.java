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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public final class SplitMinecraftProvider extends MinecraftProvider {
	private Path minecraftClientOnlyJar;
	private Path minecraftCommonJar;

	public SplitMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		super(metadataProvider, configContext);
	}

	@Override
	protected void initFiles() {
		super.initFiles();

		minecraftClientOnlyJar = path("minecraft-client-only.jar");
		minecraftCommonJar = path("minecraft-common.jar");
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftClientOnlyJar, minecraftCommonJar);
	}

	@Override
	public MappingsNamespace getOfficialNamespace() {
		return MappingsNamespace.OFFICIAL;
	}

	@Override
	public void provide() throws Exception {
		super.provide();

		// 无锁快路径：拆分产物已就绪且未要求刷新时不获取文件锁
		boolean requiresRefresh = getExtension().refreshDeps() || Files.notExists(minecraftClientOnlyJar) || Files.notExists(minecraftCommonJar);

		if (!requiresRefresh) {
			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
		final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, cacheKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：可能已被他人在等锁期间拆分完成
			if (!getExtension().refreshDeps() && Files.exists(minecraftClientOnlyJar) && Files.exists(minecraftCommonJar)) {
				return null;
			}

			BundleMetadata serverBundleMetadata = getServerBundleMetadata();

			if (serverBundleMetadata == null) {
				throw new UnsupportedOperationException("Only Minecraft versions using a bundled server jar can be split, please use a merged jar setup for this version of minecraft");
			}

			final Path clientJar = getMinecraftClientJar().toPath();
			final Path serverJar = getMinecraftExtractedServerJar().toPath();

			// 原子发布：split 写到两个同目录唯一临时 jar，完整后再分别原子 move 到最终路径，
			// 避免跨进程读到任一半写的拆分产物误判就绪
			final Path tmpClientOnly = AtomicFiles.tempSibling(minecraftClientOnlyJar);
			final Path tmpCommon = AtomicFiles.tempSibling(minecraftCommonJar);

			try (MinecraftJarSplitter jarSplitter = new MinecraftJarSplitter(clientJar, serverJar)) {
				// Required for loader to compute the version info also useful to have in both jars.
				jarSplitter.sharedEntry("version.json");
				jarSplitter.sharedEntry("assets/.mcassetsroot");
				jarSplitter.sharedEntry("assets/minecraft/lang/en_us.json");

				jarSplitter.split(tmpClientOnly, tmpCommon);
				AtomicFiles.move(tmpClientOnly, minecraftClientOnlyJar);
				AtomicFiles.move(tmpCommon, minecraftCommonJar);
			} catch (Exception e) {
				Files.deleteIfExists(minecraftClientOnlyJar);
				Files.deleteIfExists(minecraftCommonJar);

				throw new RuntimeException("Failed to split minecraft", e);
			} finally {
				// 原子 move 成功后临时文件已不存在；失败时清理残留
				Files.deleteIfExists(tmpClientOnly);
				Files.deleteIfExists(tmpCommon);
			}

			return null;
		});
	}

	public Path getMinecraftClientOnlyJar() {
		return minecraftClientOnlyJar;
	}

	public Path getMinecraftCommonJar() {
		return minecraftCommonJar;
	}
}
