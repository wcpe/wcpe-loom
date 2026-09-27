/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024-2025 FabricMC
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.util.Stopwatch;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public final class ForgeMigratedMappingConfiguration extends MappingConfiguration {
	private final List<MappingsMigrator> migrators = List.of(new FieldMappingsMigrator(), new MethodInheritanceMappingsMigrator());
	private Path hashPath;
	private Path rawTinyMappings;
	private Path rawTinyMappingsWithSrg;
	private Path rawTinyMappingsWithMojang;
	private long hash;

	public ForgeMigratedMappingConfiguration(String mappingsIdentifier, Path mappingsWorkingDir) {
		super(mappingsIdentifier, mappingsWorkingDir);
	}

	@Override
	protected void manipulateMappings(Project project, Path mappingsJar) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.isLegacyForge()) {
			// Legacy forge patches are in official namespace, so if the type of a field is changed by them, then that
			// is effectively a new field and not traceable to any mapping. Therefore this does not apply to it.
			return;
		}

		final Path forgeCache = ForgeProvider.getForgeCache(project);
		Files.createDirectories(forgeCache);

		boolean hasSrg = extension.shouldGenerateSrgTiny();
		boolean hasMojang = extension.isNeoForge();

		this.hashPath = forgeCache.resolve("mappings-migrated.hash");
		this.hash = 1;

		this.rawTinyMappings = this.tinyMappings;
		this.rawTinyMappingsWithSrg = this.tinyMappingsWithSrg;
		this.rawTinyMappingsWithMojang = this.tinyMappingsWithMojang;
		Path rawTinyMappingsWithNs = hasSrg ? this.rawTinyMappingsWithSrg : hasMojang ? this.rawTinyMappingsWithMojang : this.rawTinyMappings;

		this.tinyMappings = mappingsWorkingDir().resolve("mappings-migrated.tiny");
		this.tinyMappingsWithSrg = mappingsWorkingDir().resolve("mappings-srg-migrated.tiny");
		this.tinyMappingsWithMojang = mappingsWorkingDir().resolve("mappings-mojang-migrated.tiny");
		Path tinyMappingsWithNs = hasSrg ? this.tinyMappingsWithSrg : hasMojang ? this.tinyMappingsWithMojang : this.tinyMappings;

		// 迁移器的中间缓存位于共享的 forge 缓存目录，各自原子发布；其内容只由「原始 mappings + 补丁 jar」
		// 决定，同一输入下结果一致，故无锁写入最多互相覆盖成等价内容，不会让读方看到半截文件。
		// 迁移器返回值参与 hash 计算，构成迁移产物的就绪标记。
		for (MappingsMigrator migrator : this.migrators) {
			hash = hash * 31 + migrator.setup(project, extension.getMinecraftProvider(), forgeCache, rawTinyMappingsWithNs, hasSrg, hasMojang);
		}

		// 无锁快路径：就绪标记与各迁移产物齐备且 hash 一致时，本次不含任何共享缓存写入，不取锁
		if (!isOutdated(extension, hasSrg, hasMojang)) {
			project.getLogger().info(":manipulated {} mappings are up to date", extension.getPlatform().get().id());
			return;
		}

		withMappingsLock(project, extension, () -> {
			// 锁内二次确认：等锁期间其它进程可能已产出同一 hash 的迁移结果，此时直接复用
			if (isOutdated(extension, hasSrg, hasMojang)) {
				produceMigratedMappings(project, extension, rawTinyMappingsWithNs, tinyMappingsWithNs);
			}

			return null;
		});
	}

	/**
	 * 生产迁移后的 mappings：复制与迁移结果一律原子发布，就绪标记最后落位.
	 *
	 * <p>旧写法直接以共享产物为输出（先原地覆盖、迁移器再读回并就地重写），读方会看到半截 mappings；
	 * 而 hash 就绪标记先于内容落位时，读方还会读到「标记在、内容半截」的错位状态。
	 */
	private void produceMigratedMappings(Project project, LoomGradleExtension extension, Path rawTinyMappingsWithNs, Path tinyMappingsWithNs) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		AtomicFiles.copy(this.rawTinyMappings, this.tinyMappings);
		AtomicFiles.copy(rawTinyMappingsWithNs, tinyMappingsWithNs);

		for (MappingsMigrator migrator : this.migrators) {
			// 临时文件与产物同目录：跨文件系统的 move 会退化成「复制 + 删除」而不再原子
			final Path path = AtomicFiles.tempSibling(this.tinyMappings);
			final Path pathWithNs = AtomicFiles.tempSibling(tinyMappingsWithNs);

			try {
				Files.copy(this.tinyMappings, path);
				Files.copy(tinyMappingsWithNs, pathWithNs);

				List<MappingsMigrator.MappingsEntry> entries = List.of(new MappingsMigrator.MappingsEntry(path), new MappingsMigrator.MappingsEntry(pathWithNs));
				migrator.migrate(project, entries);

				AtomicFiles.move(path, this.tinyMappings);
				AtomicFiles.move(pathWithNs, tinyMappingsWithNs);
			} finally {
				Files.deleteIfExists(path);
				Files.deleteIfExists(pathWithNs);
			}
		}

		// 就绪标记最后落位：标记先于内容落位会让读方读到「标记在、内容半截」
		AtomicFiles.publish(this.hashPath, tmp -> Files.writeString(tmp, Long.toString(this.hash), StandardCharsets.UTF_8));

		project.getLogger().info(":manipulated {} mappings in " + stopwatch.stop(), extension.getPlatform().get().id());
	}

	/**
	 * 在跨进程锁保护下生产迁移后的 mappings.
	 *
	 * <p>产物位于跨 daemon 共享的缓存目录（不按项目隔离），key 与 lockRoot 和
	 * {@link MappingConfiguration} 生产原始 mappings 时使用的完全一致
	 * （{@code "mappings:" + mappingsIdentifier} + {@code userCache/.locks}），
	 * 因此「重写原始 mappings」与「迁移 mappings」不会交错——否则迁移方可能把刚被覆盖的原始文件复制成迁移产物。
	 */
	private void withMappingsLock(Project project, LoomGradleExtension extension, Callable<Void> action) throws IOException {
		final Path lockRoot = extension.getFiles().getCacheLocks().toPath();
		final String key = "mappings:" + mappingsIdentifier;

		try {
			LoomCacheService.get(project).get().runExclusive(lockRoot, key, LoomCacheService.defaultTimeout(), action);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Could not manipulate mappings for " + mappingsIdentifier, e);
		}
	}

	private boolean isOutdated(LoomGradleExtension extension, boolean hasSrg, boolean hasMojang) throws IOException {
		if (extension.refreshDeps()) return true;
		if (Files.notExists(this.tinyMappings)) return true;
		if (hasSrg && Files.notExists(this.tinyMappingsWithSrg)) return true;
		if (hasMojang && Files.notExists(this.tinyMappingsWithMojang)) return true;
		if (Files.notExists(this.hashPath)) return true;
		String hashStr = Files.readString(hashPath, StandardCharsets.UTF_8);
		return !Long.toString(this.hash).equals(hashStr);
	}
}
