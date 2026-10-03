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
	/**
	 * 就绪标记的「失效」哨兵值.
	 *
	 * <p>重建期间标记必须处于不可用状态，而删除标记文件会让并发读方在「存在性检查 → 读取」之间
	 * 撞上 {@code NoSuchFileException}（{@link #isOutdated} 正是这样分两步读的），故改为原子写入
	 * 一个不可能是合法 hash 的值：合法标记只可能是 {@code Long.toString(hash)} 的十进制表示，
	 * 而 {@link #isOutdated} 用的是字符串比较，因此哨兵必然被判为「需重建」，判定自洽。
	 */
	private static final String INVALIDATED_HASH = "invalidated";
	private final List<MappingsMigrator> migrators = List.of(new FieldMappingsMigrator(), new MethodInheritanceMappingsMigrator());
	private Path hashPath;
	private Path rawTinyMappings;
	private Path rawTinyMappingsWithSrg;
	private Path rawTinyMappingsWithMojang;
	private long hash;

	public ForgeMigratedMappingConfiguration(String mappingsIdentifier, Path mappingsWorkingDir) {
		super(mappingsIdentifier, mappingsWorkingDir);
	}

	/**
	 * {@return 本次配置期是否会按路径读 patched 中间产物}.
	 *
	 * <p>{@link #manipulateMappings} 在配置期调用 {@link #migrators}，而两个迁移器在各自缓存未命中时都会
	 * 按路径读那件产物（见 {@code MinecraftPatchedProvider#getOrProduceMinecraftPatchedIntermediateJar}）。
	 * 判据必须与迁移器 {@code setup} 里的分支逐字对应——否则「判定为不需要读」而实际读了，
	 * 配置期就会拿到执行期才产出的文件。
	 *
	 * <p>这是「把 patched 中间产物投影成执行期任务」的**前置阻碍**：迁移器自身任务化之后，
	 * 本方法与 {@code MinecraftPatchedProvider.provideProjectionBlocker()} 里对应的那一条应当一并删除。
	 *
	 * @param forgeCache 迁移器缓存所在的 forge 缓存目录（与 {@code migrator.setup} 的 {@code cache} 同一个）
	 * @param refreshDeps 是否显式要求刷新（刷新时迁移器一律走重建分支）
	 * @param hasSrg 本配置是否生成 srg 命名空间的映射
	 * @param hasMojang 本配置是否为 NeoForge（生成 mojang 命名空间的映射）
	 */
	public static boolean needsPatchedIntermediateJar(Path forgeCache, boolean refreshDeps, boolean hasSrg, boolean hasMojang) {
		return FieldMappingsMigrator.needsPatchedIntermediateJar(forgeCache, refreshDeps, hasSrg, hasMojang)
				|| MethodInheritanceMappingsMigrator.needsPatchedIntermediateJar(forgeCache, refreshDeps);
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

		// 状态文件必须与它守护的产物同域：产物都落在 mappings 工作目录下，而 forgeCache 按 MC+Forge
		// 版本共享；若状态文件放在 forgeCache，同版本下不同 mappings 配置会互相覆盖哈希，
		// 导致 shouldMigrate() 与错误的基准比较——要么空跑迁移，要么把过期映射当成有效复用。
		this.hashPath = mappingsWorkingDir().resolve("mappings-migrated.hash");
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

		// 无锁快路径：就绪标记与各迁移产物齐备且 hash 一致时，本次不含任何共享缓存写入，不取锁。
		// 重建方在整个重建期间把标记置为失效值（见 produceMigratedMappings），故此处不会读到
		// 「标记有效、产物却只迁移了一半」的错位状态。
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
	 * 生产迁移后的 mappings：复制与迁移结果一律原子发布，就绪标记先失效、最后才落位.
	 *
	 * <p>旧写法直接以共享产物为输出（先原地覆盖、迁移器再读回并就地重写），读方会看到半截 mappings；
	 * 而 hash 就绪标记先于内容落位时，读方还会读到「标记在、内容半截」的错位状态。
	 *
	 * <p>「单文件原子」不等于「集合原子」：多件产物逐个落位，过程中盘上必然存在「部分新、部分旧」
	 * 的混合集合，此时旧标记（内容与重建前的 hash 相同）看起来依然合法。带 refresh 的重建尤其危险——
	 * 它是唯一「hash 未变却重写全部产物」的路径，其它 {@code refresh=false} 的进程会在整个重建时长内
	 * 通过 {@link #isOutdated} 的 hash 比较直接判定「已就绪」，并据此读入只迁移了一半的 mappings。
	 * 因此重建的第一步就是让标记失效，全部产物落位后才发布真实 hash，维持
	 * 「标记有效 ⟹ 所有产物已就绪」这一不变式；若中途失败，标记留在失效态，下次构建会重建（安全方向）。
	 */
	private void produceMigratedMappings(Project project, LoomGradleExtension extension, Path rawTinyMappingsWithNs, Path tinyMappingsWithNs) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		// 先失效标记再碰任何产物：保证重建期间没有读方能凭旧标记把半迁移产物当成就绪产物
		invalidateMigratedMappingsMarker();
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
	 * 让迁移产物的就绪标记失效，直到本轮产物全部落位.
	 *
	 * <p>用原子发布写哨兵值而不是删除标记文件：删除会在「存在性检查 → 读取」之间给并发读方
	 * 留下 {@code NoSuchFileException} 窗口，原子写入则保证标记始终存在，内容要么是哨兵、要么是完整 hash。
	 */
	private void invalidateMigratedMappingsMarker() throws IOException {
		AtomicFiles.publish(this.hashPath, tmp -> Files.writeString(tmp, INVALIDATED_HASH, StandardCharsets.UTF_8));
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

	/**
	 * 迁移产物是否需要重建.
	 *
	 * <p>判据与「标记有效 ⟹ 所有产物已就绪」的不变式自洽：标记缺失、内容为失效哨兵
	 * （见 {@link #INVALIDATED_HASH}，说明有进程正在重建）或与本次算出的 hash 不一致，都判为需重建。
	 */
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
