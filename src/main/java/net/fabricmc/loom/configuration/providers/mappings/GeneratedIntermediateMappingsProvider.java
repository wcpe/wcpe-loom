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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import dev.architectury.loom.util.LoggerFilter;
import dev.architectury.loom.util.Stopwatch;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.api.mappings.intermediate.IntermediateMappingsProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.stitch.commands.CommandGenerateIntermediary;

public abstract class GeneratedIntermediateMappingsProvider extends IntermediateMappingsProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedIntermediateMappingsProvider.class);

	public MinecraftProvider minecraftProvider;

	@Override
	public void provide(Path tinyMappings) throws IOException {
		final boolean refresh = minecraftProvider.refreshDeps();

		// 无锁快路径：产物位于共享缓存目录（<userCache>/<mcVersion>/<name>.tiny），已就绪且未要求刷新时直接返回。
		// 就绪判据为内容级（存在且非空），不能只判存在：被中断的就地写会留下 0 字节残骸，
		// PR #8 移除「残留锁 → 全量重建」这条兜底后，存在性判定会把它永久复用。
		if (isReusableTiny(tinyMappings) && !refresh) {
			return;
		}

		// 进锁前先记下产物指纹：锁内二次确认靠它判断「等锁期间是否已有人发布了新一轮产物」
		final ArtifactStamp stampBeforeLock = ArtifactStamp.of(tinyMappings);

		// 产物的共享身份 = MC 版本 + provider 名（路径即 <userCache>/<mcVersion>/<name>.tiny），
		// 故锁 key 由这两者派生，保证不同工作树/daemon 算出同一 key；锁文件放在产物所属目录下的 .locks。
		final Path lockRoot = tinyMappings.getParent().resolve(Constants.Cache.LOCKS_DIR);
		final String key = "mc-intermediary:" + getMinecraftVersion().get() + ":" + getName();

		try {
			CacheEntryLock.withLock(lockRoot, key, LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：
				// - 未要求刷新：等锁期间若产物已被其它进程生成并发布，直接复用；
				// - 要求刷新：refresh 只需保证「真正重建一次」，而该产物内容只是「合并后的 MC jar + stitch」
				//   的函数、路径按 mcVersion 定址（同一版本的产物内容恒定），等锁期间若已有人发布过新一轮产物，
				//   我们要的那次重建就已经发生，再生成一遍只是白等一次分钟级的合并 + stitch。
				// 注意不能用 refresh 直接短路本判据：否则等锁期间别人刚生成好的同一份产物会被再生成一遍。
				if (!refresh) {
					if (isReusableTiny(tinyMappings)) {
						return null;
					}
				} else if (wasRepublishedWhileWaiting(stampBeforeLock, tinyMappings)) {
					return null;
				}

				generate(tinyMappings);
				return null;
			});
		} catch (IOException | RuntimeException e) {
			// 生成过程自身的失败（含 stitch 失败）与锁超时保持原有语义向上抛出
			throw e;
		} catch (Exception e) {
			// 兜底：仅剩锁工具可能抛出的受检异常
			throw new IOException("Failed to generate intermediate mappings: " + key, e);
		}
	}

	private void generate(Path tinyMappings) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		LOGGER.info(":generating dummy intermediary");

		// create a temporary folder into which stitch will output the v1 file
		// we cannot just create a temporary file directly, cause stitch will try to read it if it exists
		Path tmpFolder = Files.createTempDirectory("dummy-intermediary");
		Path tinyV1 = tmpFolder.resolve("intermediary-v1.tiny");
		Path mergedJar = tmpFolder.resolve("merged.jar");

		try {
			File clientJar = minecraftProvider.getMinecraftClientJar();
			File serverJar = minecraftProvider.getMinecraftServerJar();

			try (var jarMerger = new MinecraftJarMerger(clientJar, serverJar, mergedJar.toFile())) {
				jarMerger.enableSyntheticParamsOffset();
				jarMerger.merge();
			}

			CommandGenerateIntermediary command = new CommandGenerateIntermediary();
			LoggerFilter.withSystemOutAndErrSuppressed(() -> {
				try {
					command.run(new String[]{ mergedJar.toAbsolutePath().toString(), tinyV1.toAbsolutePath().toString() });
				} catch (IOException e) {
					throw e;
				} catch (Exception e) {
					throw new IOException("Failed to generate intermediary", e);
				}
			});

			// 原子发布共享产物：先在临时文件上写完，再原子 move 到最终路径。
			// 不再「先删后写」——删除会制造「产物不存在」窗口，锁外的存在性快路径会误判并触发重复生成。
			AtomicFiles.publish(tinyMappings, tmp -> {
				try (MappingWriter writer = MappingWriter.create(tmp, MappingFormat.TINY_2_FILE)) {
					MappingReader.read(tinyV1, writer);
				}
			});
		} finally {
			Files.deleteIfExists(mergedJar);
			Files.deleteIfExists(tinyV1);
			Files.delete(tmpFolder);
		}

		LOGGER.info(":generated dummy intermediary in " + stopwatch.stop());
	}

	/**
	 * {@return 该 intermediary 产物是否可作为输入复用}.
	 *
	 * <p>这是 {@code .tiny} 文本产物而非 jar，故不适用 {@link net.fabricmc.loom.util.cache.JarReusability#isReusable(Path)}
	 * 的 zip 口径（拿文本去开 zipfs 必然失败，会把正常产物永久判为不可用）。等价的内容判据取「存在且非空」：
	 *
	 * <ul>
	 *     <li>正常产物恒非空——它由 {@code generate} 经 tiny v2 writer 写出，至少含映射头，故这条不会引起
	 *     「每次构建都重建」；</li>
	 *     <li>能拦下「先删后写」被中断、或旧版本 loom 就地重建时留下的 0 字节残骸——这正是实测中出现过的形态，
	 *     而 0 字节产物会让下游 {@code MappingReader} 读到空映射，属于静默的错误映射。</li>
	 * </ul>
	 *
	 * <p>刻意不做逐行解析等更严的校验：该判定位于每次构建的无锁快路径上，全量解析一份 stitch 产物是秒级开销，
	 * 收益却只覆盖「截断到非 0 长度」这一小类残骸。
	 */
	private static boolean isReusableTiny(Path tinyMappings) {
		try {
			return Files.size(tinyMappings) > 0;
		} catch (IOException e) {
			// 不存在（NoSuchFileException）或读不到元数据：按不可复用处理，交由调用方重新生成
			return false;
		}
	}

	/**
	 * 等锁期间该产物是否已被新一轮发布.
	 *
	 * <p>判据为「进锁后的指纹 ≠ 进锁前的指纹」且产物在位：产物缺失（例如被外部清理）不算已发布，
	 * 此时仍须自己生成，否则会把「无产物」当成「已重建」。
	 */
	private static boolean wasRepublishedWhileWaiting(ArtifactStamp stampBeforeLock, Path tinyMappings) throws IOException {
		final ArtifactStamp stampInLock = ArtifactStamp.of(tinyMappings);
		return stampInLock.exists() && !stampInLock.equals(stampBeforeLock);
	}

	/**
	 * 产物指纹：存在性 + 大小 + 修改时间.
	 *
	 * <p>该产物的写入方式是「临时文件 + 原子 move」，任何指纹变化都来自一次完整发布，故可用于判断
	 * 「等锁期间是否已有人发布了新一轮产物」。退一步说，即便时间戳粒度过粗导致漏判，后果也只是
	 * 多做一次生成，不会破坏显式刷新的语义（漏判方向是安全的）。
	 */
	private record ArtifactStamp(boolean exists, long size, long lastModifiedMillis) {
		static ArtifactStamp of(Path path) throws IOException {
			try {
				final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
				return new ArtifactStamp(true, attributes.size(), attributes.lastModifiedTime().toMillis());
			} catch (NoSuchFileException e) {
				// 取指纹期间文件被外部删除：按「不存在」处理，由调用方决定是否重新生成
				return new ArtifactStamp(false, -1L, -1L);
			}
		}
	}

	@Override
	public @NonNull String getName() {
		return "generated-intermediate";
	}
}
