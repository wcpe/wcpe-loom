/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
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

package dev.architectury.loom.forge.dependency;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public class ForgeUniversalProvider extends DependencyProvider {
	private File forge;

	public ForgeUniversalProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		forge = new File(getExtension().getForgeProvider().getGlobalCache(), "forge-universal.jar");

		// 无锁快路径：缓存就绪且未要求刷新时不取锁
		if (!forge.exists() || refreshDeps()) {
			produceWithLock(dependency);
		}
	}

	/**
	 * 在跨进程锁保护下生产 forge-universal.jar.
	 *
	 * <p>缓存位于 {@code userCache}（不按项目隔离），同一 MC+Forge 版本的多个 daemon 会指向同一路径；
	 * 由首个取得锁的进程写入，其余进程等待后直接复用。锁内二次确认 + 原子落位。
	 */
	private void produceWithLock(DependencyInfo dependency) {
		final String lockKey = "forge-universal:" + getExtension().getMinecraftProvider().minecraftVersion()
				+ ":" + getExtension().getForgeProvider().getVersion().getCombined();
		final Path lockRoot = getExtension().getForgeProvider().getGlobalCache().toPath().resolve(Constants.Cache.LOCKS_DIR);

		try {
			CacheEntryLock.withLock(lockRoot, lockKey, LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：等锁期间可能已被其它进程完成生产
				if (forge.exists() && !refreshDeps()) {
					return null;
				}

				final File dep = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve Forge"));
				AtomicFiles.copy(dep.toPath(), forge.toPath());
				return null;
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException("Could not produce Forge universal jar", e);
		}
	}

	public File getForge() {
		return forge;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_UNIVERSAL;
	}
}
