/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2023 FabricMC
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import dev.architectury.loom.forge.ForgeMigratedMappingConfiguration;
import dev.architectury.loom.forge.dependency.SrgProvider;
import dev.architectury.loom.mappings.ForgeMappingsMerger;
import dev.architectury.loom.mappings.MCPReader;
import dev.architectury.loom.mappings.MappingOption;
import dev.architectury.loom.util.Stopwatch;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingContext;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsLayer;
import net.fabricmc.loom.configuration.providers.mappings.tiny.MappingsMerger;
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;

public class MappingConfiguration {
	private static final Logger LOGGER = LoggerFactory.getLogger(MappingConfiguration.class);
	private static final String RECORD_SIGNATURES_PATH = "extras/record_signatures.json";

	/** 成对就绪标记的内容：两件产物出自同一次生成. */
	private static final String PAIR_MARKER_READY = "ready";
	/** 成对就绪标记的失效内容：本轮正在（重新）生成这对产物. */
	private static final String PAIR_MARKER_INVALID = "invalidated";

	/**
	 * 跨项目复用缓存：同构项目（同一套 mappings + 同一 MC 版本 + 同一平台）产出的
	 * MappingConfiguration 语义等价，且暖路径下只读，无需每个项目都重开一次 mappings jar。
	 * 键取 mappingsIdentifier —— 它已编码 mappings 坐标、classifier、MC 版本与平台差异.
	 */
	private static final Map<String, MappingConfiguration> SHARED_INSTANCES = new ConcurrentHashMap<>();

	/**
	 * 早缓存索引：键仅由「声明信息」构成（不触发依赖解析），用于在解析 MAPPINGS 之前就命中已产出的实例.
	 */
	private static final Map<String, MappingConfiguration> SHARED_EARLY = new ConcurrentHashMap<>();

	/**
	 * 早缓存索引键：只由「声明信息」与平台维度构成，不触发依赖解析.
	 *
	 * <p>必须覆盖一切会影响最终 mappings 标识的维度，否则同构但不同变体的项目会互相命中：
	 * 平台（Fabric/Quilt/Forge/NeoForge）、classifier 与 artifact 类型、是否使用中间映射、
	 * 是否禁用混淆、以及 Forge 系的具体版本。键尾的版本号用于在维度变化时自然失效旧条目。
	 */
	private static String earlyKey(LoomGradleExtension extension, DependencyInfo dependency, MinecraftProvider minecraftProvider, String declaredVersion) {
		final String forgeVersion = extension.isForgeLike()
				? extension.getForgeProvider().getVersion().getCombined()
				: "";
		final String rawKey = String.join("\u0000",
				"early-mappings-v2",
				extension.getPlatform().get().id(),
				dependency.getDependency().getGroup(),
				dependency.getDependency().getName(),
				declaredVersion,
				dependency.getDeclaredClassifier(),
				dependency.getDeclaredArtifactDimensions(),
				minecraftProvider.minecraftVersion(),
				Boolean.toString(extension.getUseIntermediateMappings().get()),
				Boolean.toString(extension.disableObfuscation()),
				forgeVersion);
		return Checksum.of(rawKey).sha256().hex();
	}

	public final String mappingsIdentifier;

	private final Path mappingsWorkingDir;
	// The mappings that gradle gives us
	private final Path baseTinyMappings;
	// The mappings we use in practice
	public Path tinyMappings;
	public final Path tinyMappingsJar;
	public Path tinyMappingsWithMojang;
	public Path tinyMappingsWithSrg;
	public final Map<String, Path> mixinTinyMappings; // The mixin mappings have other names in intermediary.
	public final Path srgToNamedSrg; // FORGE: srg to named in srg file format
	private final Map<MappingOption, Supplier<Path>> mappingOptions;
	private final Path unpickDefinitions;
	// mappings.tiny 与 mappings.jar 的成对就绪标记：与两件产物同处共享工作目录，跨工作树/daemon 共享
	private final Path mappingsPairMarker;

	private List<AnnotationsData> annotationsData = List.of();

	@Nullable
	private UnpickMetadata unpickMetadata;
	private Map<String, String> signatureFixes;

	protected MappingConfiguration(String mappingsIdentifier, Path mappingsWorkingDir) {
		this.mappingsIdentifier = mappingsIdentifier;

		this.mappingsWorkingDir = mappingsWorkingDir;
		this.baseTinyMappings = mappingsWorkingDir.resolve("mappings-base.tiny");
		this.tinyMappings = mappingsWorkingDir.resolve("mappings.tiny");
		this.tinyMappingsJar = mappingsWorkingDir.resolve("mappings.jar");
		this.mappingsPairMarker = mappingsWorkingDir.resolve("mappings-pair.ready");
		this.unpickDefinitions = mappingsWorkingDir.resolve("mappings.unpick");
		this.tinyMappingsWithSrg = mappingsWorkingDir.resolve("mappings-srg.tiny");
		this.tinyMappingsWithMojang = mappingsWorkingDir.resolve("mappings-mojang.tiny");
		this.mixinTinyMappings = new HashMap<>();
		this.srgToNamedSrg = mappingsWorkingDir.resolve("mappings-srg-named.srg");
		this.mappingOptions = new EnumMap<>(MappingOption.class);
		this.mappingOptions.put(MappingOption.DEFAULT, () -> this.tinyMappings);
	}

	public static MappingConfiguration create(Project project, ServiceFactory serviceFactory, DependencyInfo dependency, MinecraftProvider minecraftProvider) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final boolean refresh = minecraftProvider.refreshDeps();

		// 早缓存：getResolvedVersion() 会触发 MAPPINGS 配置的完整依赖解析（实测单模块 2~9s），
		// 而同构项目共用同一份 mappings 声明。故先用「不触发解析」的声明信息查一次缓存。
		final String declaredVersion = dependency.getDependency().getVersion();

		if (!refresh && declaredVersion != null) {
			final MappingConfiguration earlyHit = SHARED_EARLY.get(earlyKey(extension, dependency, minecraftProvider, declaredVersion));

			if (earlyHit != null && earlyHit.hasUsableMappingsPair()) {
				return earlyHit;
			}
		}

		final String version = dependency.getResolvedVersion();
		final Path inputJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve mappings: " + dependency)).toPath();
		final String mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");

		final TinyJarInfo jarInfo = TinyJarInfo.get(inputJar);
		jarInfo.minecraftVersionId().ifPresent(id -> {
			if (!minecraftProvider.minecraftVersion().equals(id)) {
				LOGGER.warn("The mappings (%s) were not built for Minecraft version %s, proceed with caution.".formatted(dependency.getDepString(), minecraftProvider.minecraftVersion()));
			}
		});

		String mappingsIdentifier;

		if (extension.isForgeLike()) {
			mappingsIdentifier = createForgeMappingsIdentifier(extension, mappingsName, version, getMappingsClassifier(dependency, jarInfo.v2()), minecraftProvider.minecraftVersion());
		} else {
			mappingsIdentifier = createMappingsIdentifier(mappingsName, version, getMappingsClassifier(dependency, jarInfo.v2()), minecraftProvider.minecraftVersion());
		}

		if (extension.isQuilt()) {
			mappingsIdentifier += "-arch-quilt";
		}

		final Path workingDir = minecraftProvider.dir(mappingsIdentifier).toPath();

		// 跨项目复用：同构项目命中同一实例即可跳过第二次起的 setup——其成本主要是
		// 重复打开 mappings jar（zipfs 建索引）。命中后仍校验两件产物成对就绪，
		// 避免缓存到已删文件，或只落位了一半的一代产物。
		if (!refresh) {
			final MappingConfiguration shared = SHARED_INSTANCES.get(mappingsIdentifier);

			if (shared != null && shared.hasUsableMappingsPair()) {
				return shared;
			}
		}

		MappingConfiguration mappingProvider;

		if (extension.isForgeLike()) {
			mappingProvider = new ForgeMigratedMappingConfiguration(mappingsIdentifier, workingDir);
		} else {
			mappingProvider = new MappingConfiguration(mappingsIdentifier, workingDir);
		}

		try {
			mappingProvider.setup(project, serviceFactory, minecraftProvider, inputJar);
		} catch (Exception e) {
			// 不再清理共享工作目录（<userCache>/<mappingsIdentifier>，跨工作树/daemon 共享）：
			// 递归删除会连带删掉其它进程刚发布的有效产物，并制造「产物不存在」窗口引发锁外读方重建雪崩。
			// setup 内的所有写入均为「临时文件 + 原子落位」，失败路径不会留下半截产物，故无需清理。
			throw new RuntimeException("Failed to setup mappings: " + dependency.getDepString(), e);
		}

		if (!refresh) {
			SHARED_INSTANCES.put(mappingsIdentifier, mappingProvider);

			if (declaredVersion != null) {
				SHARED_EARLY.put(earlyKey(extension, dependency, minecraftProvider, declaredVersion), mappingProvider);
			}
		}

		return mappingProvider;
	}

	public Path getMappingsPath(MappingOption mappingOption) {
		Supplier<Path> mappingsSupplier = this.mappingOptions.get(mappingOption);

		if (mappingsSupplier == null) {
			throw new UnsupportedOperationException("Unsupported mapping option: " + mappingOption + ", it is possible that this option is not supported by this project / platform!");
		} else if (Files.notExists(mappingsSupplier.get())) {
			throw new UnsupportedOperationException("Mapping option " + mappingOption + " found but file does not exist!");
		}

		return Objects.requireNonNull(mappingsSupplier.get());
	}

	public Provider<TinyMappingsService.Options> getMappingsServiceOptions(Project project) {
		return getMappingsServiceOptions(project, MappingOption.DEFAULT);
	}

	public Provider<TinyMappingsService.Options> getMappingsServiceOptions(Project project, MappingOption mappingOption) {
		return TinyMappingsService.createOptions(project, Objects.requireNonNull(getMappingsPath(mappingOption)));
	}

	public TinyMappingsService getMappingsService(Project project, ServiceFactory serviceFactory) {
		return serviceFactory.get(getMappingsServiceOptions(project));
	}

	public TinyMappingsService getMappingsService(Project project, ServiceFactory serviceFactory, MappingOption mappingOption) {
		return serviceFactory.get(getMappingsServiceOptions(project, mappingOption));
	}

	private void setup(Project project, ServiceFactory serviceFactory, MinecraftProvider minecraftProvider, Path inputJar) throws Exception {
		final boolean refresh = minecraftProvider.refreshDeps();

		// 无锁快路径：两件 mappings 产物成对就绪（含成对标记，见 hasUsableMappingsPair）且未要求刷新时，
		// 不获取文件锁，仅在内存中重新提取额外信息（每次必须执行）
		if (!refresh && hasUsableMappingsPair()) {
			// 轻量预检：先读 zip 中央目录的条目名判断有无 extras，再决定是否打开 zipfs。
			// 打开 zipfs 会建索引，成本远高于读条目名；而 layered mappings jar 往往只有
			// 「目录 + mappings/mappings.tiny」两个条目，此时可整段跳过。
			if (jarContainsExtras(inputJar)) {
				try (FileSystem fileSystem = FileSystems.newFileSystem(inputJar, (ClassLoader) null)) {
					extractExtras(fileSystem);
				}
			}

			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(project).get();
		final Path lockRoot = LoomGradleExtension.get(project).getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, mappingsLockKey(), LoomCacheService.defaultTimeout(), () -> {
			produceMappings(project, serviceFactory, minecraftProvider, inputJar, refresh);
			return null;
		});
	}

	/**
	 * mappings 共享产物的跨进程互斥 key.
	 *
	 * <p>由共享身份（{@link #mappingsIdentifier} 已编码 mappings 坐标、classifier、MC 版本与平台差异）派生，
	 * 保证不同工作树/daemon 对同一批产物算出同一把锁；lockRoot 为 {@code <userCache>/.locks}，同样跨工作树一致。
	 */
	private String mappingsLockKey() {
		return "mappings:" + mappingsIdentifier;
	}

	/**
	 * 在 mappings 锁内执行一段共享产物生产动作.
	 *
	 * <p>{@link LoomCacheService#runExclusive} 声明抛出 {@link Exception}，而本类各生产入口只声明
	 * {@link IOException}；这里把非 IO 异常折叠为 {@link RuntimeException} 后上抛，避免为加锁改动对外签名。
	 */
	private void runWithMappingsLock(Project project, String failureMessage, Callable<Void> action) throws IOException {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final Path lockRoot = extension.getFiles().getCacheLocks().toPath();

		try {
			LoomCacheService.get(project).get().runExclusive(lockRoot, mappingsLockKey(), LoomCacheService.defaultTimeout(), action);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(failureMessage, e);
		}
	}

	// 产 mappings 文件：受 per-key 锁保护。注意 extractExtras 会在内存中填充字段，
	// 但本方法仅在「冷/刷新」路径执行；暖路径的内存填充已在 setup 的无锁快路径中完成。
	private void produceMappings(Project project, ServiceFactory serviceFactory, MinecraftProvider minecraftProvider, Path inputJar, boolean refresh) throws IOException {
		// 刷新（--refresh-dependencies）不再靠删除共享产物实现：删除会制造「产物不存在」窗口，
		// 锁外的存在性快路径会误判、并连带删掉其它进程正在读的文件。
		// 改为「强制重建 + 原子替换」，读方始终看到旧的完整文件或新的完整文件。
		final boolean writeTiny = Files.notExists(tinyMappings) || refresh;
		// mappings.jar 的内容派生自 mappings.tiny（把同一份 tiny 原样打进 zip），故本轮重写了 tiny 就必须
		// 同轮重写 jar：否则「新的 mappings.tiny + 旧的 mappings.jar」同样会被后面的标记认证为成对就绪。
		final boolean writeJar = writeTiny || Files.notExists(tinyMappingsJar);

		// 只要本轮会写其中任一件，就先让成对标记失效：两件产物是两次独立发布，
		// 若两次发布之间标记仍为就绪，读方会命中「新的 mappings.tiny + 旧的 mappings.jar」。
		if (writeTiny || writeJar) {
			invalidateMappingsPairMarker();
		}

		if (writeTiny) {
			storeMappings(project, serviceFactory, minecraftProvider, inputJar);
		} else {
			try (FileSystemUtil.Delegate fileSystem = FileSystemUtil.getReadOnlyJarFileSystem(inputJar)) {
				extractExtras(fileSystem.get());
			}
		}

		if (writeJar) {
			// 原子发布 mappings.jar：先在临时文件上完成全部加工再原子落位，不再「先删后就地写」
			AtomicFiles.publish(tinyMappingsJar, tmp -> ZipUtils.add(tmp, "mappings/mappings.tiny", Files.readAllBytes(tinyMappings)));
		}

		// 两件产物都已落位，最后提交成对标记：标记就绪 ⟹ 这对产物出自同一次生成
		AtomicFiles.publish(mappingsPairMarker, tmp -> Files.writeString(tmp, PAIR_MARKER_READY, StandardCharsets.UTF_8));
	}

	/**
	 * 暖路径判据：mappings.tiny 与 mappings.jar 成对就绪.
	 *
	 * <p>这两件产物由 {@link #produceMappings} 分两次独立发布，「单件原子」并不等于「成对原子」：
	 * 两次 move 之间读方会拿到「新的 mappings.tiny + 旧的 mappings.jar」，而 remap 用前者、
	 * MAPPINGS_FINAL 暴露后者，于是同一次构建里这两处可能来自两代不同的映射产物。
	 * 注意「只认最后发布的那一件」并不能解决：
	 * 旧一代的 mappings.jar 一直留在盘上，两次发布之间它依然是「存在」的，存在性无法区分代次。
	 * 因此这里额外要求成对标记处于就绪态：生产方在写这对产物之前先把标记置为失效值，
	 * 两件都落位后才写回就绪值，于是「标记就绪 ⟹ 两件产物已被提交为一对」。
	 *
	 * <p>残余窗口：本方法检查标记与调用方随后读取两件文件之间仍有毫秒级间隙，理论上仍可能在
	 * 生产方「发布完 mappings.tiny、尚未发布 mappings.jar」这一小段（写 zip 的时间）撞上混合产物。
	 * 彻底消除需要版本化路径（读方先读指针、再读不可变的代次文件），改动面远超本次范围；
	 * 相比修复前「整个重建时长（分钟级）内都可能读到混合产物」，窗口已被压到最小。
	 *
	 * <p>代价：标记是本次新增的，升级后第一次构建（或标记被外部清理时）暖路径会判为未就绪而进锁一次；
	 * 锁内确认两件产物均已存在且未要求刷新后只会补写标记，不会重新提取 mappings。
	 */
	private boolean hasUsableMappingsPair() {
		try {
			return Files.exists(tinyMappings) && Files.exists(tinyMappingsJar)
					&& PAIR_MARKER_READY.equals(Files.readString(mappingsPairMarker, StandardCharsets.UTF_8));
		} catch (IOException e) {
			// 读标记失败（例如正被并发原子替换的瞬间）按「未就绪」处理：进锁重新确认，不影响正确性
			return false;
		}
	}

	/**
	 * 让成对就绪标记失效，直到本轮两件产物全部落位.
	 *
	 * <p>用原子发布写哨兵值而不是删除标记文件：删除会在「存在性检查 → 读取」之间给并发读方留下
	 * {@code NoSuchFileException} 窗口（本类此前多处按「先查存在再读内容」两步读取共享文件）；
	 * 原子写入则保证标记始终存在，内容只可能是哨兵或 {@link #PAIR_MARKER_READY}。
	 *
	 * <p>若生产中途失败，标记留在失效态，下轮构建会重新生成这对产物（安全方向）。
	 */
	private void invalidateMappingsPairMarker() throws IOException {
		AtomicFiles.publish(mappingsPairMarker, tmp -> Files.writeString(tmp, PAIR_MARKER_INVALID, StandardCharsets.UTF_8));
	}

	public void setupPost(Project project) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		final boolean refresh = extension.refreshDeps();

		if (extension.isNeoForge()) {
			this.mappingOptions.put(MappingOption.WITH_MOJANG, () -> this.tinyMappingsWithMojang);
		}

		if (extension.shouldGenerateSrgTiny()) {
			this.mappingOptions.put(MappingOption.WITH_SRG, () -> this.tinyMappingsWithSrg);

			if (extension.isForge() && extension.getForgeProvider().usesMojangAtRuntime()) {
				this.mappingOptions.put(MappingOption.WITH_MOJANG, () -> this.tinyMappingsWithSrg);
			}
		}

		// 无锁快路径：两件共享产物（mappings-mojang.tiny / mappings-srg.tiny）均已就绪且未要求刷新时，
		// 本方法不含任何共享写入，故不取锁。
		final boolean needsMojangMerge = extension.isNeoForge() && (refresh || Files.notExists(tinyMappingsWithMojang));
		final boolean needsSrgMerge = extension.shouldGenerateSrgTiny() && (refresh || Files.notExists(tinyMappingsWithSrg));

		if (needsMojangMerge || needsSrgMerge) {
			// 一次写一组互相依赖的共享产物（mojang 合并结果 + srg 合并结果）时，整段包进同一把 mappings 锁：
			// 与 produceMappings 共用 key，避免同一 mappings 的产物被不同工作树/daemon 交叉生产。
			runWithMappingsLock(project, "生成共享 mappings（mojang/srg 合并结果）失败", () -> {
				// Generate the Mojmap-merged mappings if needed.
				// Note that this needs to happen before manipulateMappings for FieldMigratedMappingConfiguration.
				if (needsMojangMerge && (refresh || Files.notExists(tinyMappingsWithMojang))) {
					mergeMojangAtomic(project, tinyMappingsWithMojang);
				}

				// 锁内二次确认：等锁期间可能已被其它进程产出（refresh 时仍需强制重建）
				if (needsSrgMerge && (refresh || Files.notExists(tinyMappingsWithSrg))) {
					mergeSrgAtomic(project, extension, tinyMappingsWithSrg);
				}

				return null;
			});
		}

		manipulateMappings(project, tinyMappingsJar);
	}

	// mojang 合并产物的原子发布：在临时文件上合并完成后原子落位，避免留下半截 tiny
	private void mergeMojangAtomic(Project project, Path target) throws IOException {
		AtomicFiles.publish(target, tmp -> mergeMojang(project, tinyMappings, tmp));
	}

	// srg 合并产物的原子发布；Forge 在运行时使用 mojang 映射时需先产出 mojang 合并结果作为中间文件
	private void mergeSrgAtomic(Project project, LoomGradleExtension extension, Path target) throws IOException {
		if (extension.isForge() && extension.getForgeProvider().usesMojangAtRuntime()) {
			// 中间产物用进程独享的系统临时文件，最终产物仍走原子发布
			final Path intermediate = Files.createTempFile("mappings", ".tiny");

			try {
				mergeMojang(project, tinyMappings, intermediate);
				AtomicFiles.publish(target, tmp -> mergeSrg(project, intermediate, tmp));
			} finally {
				Files.deleteIfExists(intermediate);
			}
		} else {
			AtomicFiles.publish(target, tmp -> mergeSrg(project, tinyMappings, tmp));
		}
	}

	public void applyToProject(Project project, DependencyInfo dependency) throws IOException {
		if (unpickMetadata != null) {
			if (unpickMetadata.hasConstants()) {
				String notation = switch (unpickMetadata) {
				case UnpickMetadata.V1 v1 -> String.format("%s:%s:%s:constants",
						dependency.getDependency().getGroup(),
						dependency.getDependency().getName(),
						dependency.getDependency().getVersion()
				);
				case UnpickMetadata.V2 v2 -> Objects.requireNonNull(v2.constants());
				};

				project.getDependencies().add(Constants.Configurations.MAPPING_CONSTANTS, notation);
			}
		}

		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.isForge()) {
			if (!extension.shouldGenerateSrgTiny()) {
				throw new IllegalStateException("We have to generate srg tiny in a forge environment!");
			}

			if (Files.notExists(srgToNamedSrg) || extension.refreshDeps()) {
				final boolean refresh = extension.refreshDeps();
				// 该产物与 mappings-srg.tiny 同处共享工作目录且由它派生，故与 produceMappings / setupPost
				// 共用同一把 mappings 锁，避免不同工作树/daemon 交叉生产这一组互相依赖的产物。
				runWithMappingsLock(project, "生成 srg->named mappings 失败", () -> {
					// 锁内二次确认：等锁期间可能已被其它进程产出（refresh 时仍需强制重建）
					if (!refresh && Files.exists(srgToNamedSrg)) {
						return null;
					}

					try (var serviceFactory = new ScopedServiceFactory()) {
						TinyMappingsService mappingsService = getMappingsService(project, serviceFactory, MappingOption.WITH_SRG);

						// 原子发布：先在临时文件上写完整份 srg 文本，再原子 move 落位
						AtomicFiles.publish(srgToNamedSrg, tmp -> {
							try (MappingWriter writer = MappingWriter.create(tmp, MappingFormat.SRG_FILE)) {
								MappingVisitor visitor = new MappingSourceNsSwitch(new MappingDstNsReorder(writer, "named"), "srg");
								mappingsService.getMappingTree().accept(visitor);
							}
						});
					}

					return null;
				});
			}
		}

		project.getDependencies().add(Constants.Configurations.MAPPINGS_FINAL, project.files(tinyMappingsJar.toFile()));
	}

	public static Path getRawSrgFile(Project project) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.getSrgProvider().isTsrgV2()) {
			return extension.getSrgProvider().getMergedMojangTrimmed();
		}

		return extension.getSrgProvider().getSrg();
	}

	public static Path getMojmapSrgFileIfPossible(Project project) {
		try {
			LoomGradleExtension extension = LoomGradleExtension.get(project);
			return SrgProvider.getMojmapTsrg2(project, extension);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void mergeMojang(Project project, Path source, Path target) throws IOException {
		final Stopwatch stopwatch = Stopwatch.createStarted();
		final MappingContext context = new GradleMappingContext(project, "tmp-mojang");

		try (Tiny2FileWriter writer = new Tiny2FileWriter(Files.newBufferedWriter(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), false)) {
			ForgeMappingsMerger.mergeMojang(context, source, null, true).accept(writer);
		}

		project.getLogger().info(":merged mojang mappings in {}", stopwatch.stop());
	}

	private static void mergeSrg(Project project, Path source, Path target) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		// FIXME why is this special case necessary?
		ForgeMappingsMerger.ExtraMappings extraMappings = extension.isLegacyForge()
				? null
				: ForgeMappingsMerger.ExtraMappings.ofMojmapTsrg(getMojmapSrgFileIfPossible(project));

		try (Tiny2FileWriter writer = new Tiny2FileWriter(Files.newBufferedWriter(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), false)) {
			ForgeMappingsMerger.mergeSrg(getRawSrgFile(project), source, extraMappings, true).accept(writer);
		}

		project.getLogger().info(":merged srg mappings in " + stopwatch.stop());
	}

	protected void manipulateMappings(Project project, Path mappingsJar) throws IOException {
	}

	private static String getMappingsClassifier(DependencyInfo dependency, boolean isV2) {
		String[] depStringSplit = dependency.getDepString().split(":");

		if (depStringSplit.length >= 4) {
			return "-" + depStringSplit[3] + (isV2 ? "-v2" : "");
		}

		return isV2 ? "-v2" : "";
	}

	private void storeMappings(Project project, ServiceFactory serviceFactory, MinecraftProvider minecraftProvider, Path inputJar) throws IOException {
		LOGGER.info(":extracting " + inputJar.getFileName());

		if (isMCP(inputJar)) {
			try {
				readAndMergeMCP(project, serviceFactory, minecraftProvider, inputJar);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}

			return;
		}

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getReadOnlyJarFileSystem(inputJar)) {
			// 原子发布 baseTinyMappings：它同样位于共享工作目录，跨进程写入时不能让读方看到半截文件
			AtomicFiles.publish(baseTinyMappings, tmp -> extractMappings(delegate.fs(), tmp));
			extractExtras(delegate.fs());
		}

		if (areMappingsV2(baseTinyMappings)) {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			if (extension.getUseIntermediateMappings().get()) {
				// These are unmerged v2 mappings
				IntermediateMappingsService intermediateMappingsService = serviceFactory.get(IntermediateMappingsService.createOptions(project, minecraftProvider));

				AtomicFiles.publish(tinyMappings, tmp -> MappingsMerger.mergeAndSaveMappings(baseTinyMappings, tmp, minecraftProvider, intermediateMappingsService));
			} else {
				AtomicFiles.copy(baseTinyMappings, tinyMappings);
			}
		} else {
			if (LoomGradleExtension.get(project).isForgeLike()) {
				// (2022-09-11) This is due to ordering issues.
				// To complete V1 mappings, we need the full MC jar.
				// On Forge, producing the full MC jar needs the list of all Forge dependencies
				//   -> needs our remapped dependency from srg to named class names (1.19+)
				//   -> needs the mappings
				//   = a circular dependency
				throw new UnsupportedOperationException("Forge cannot be used with V1 mappings!");
			}

			final List<Path> minecraftJars = minecraftProvider.getMinecraftJars();

			if (minecraftJars.size() != 1) {
				throw new UnsupportedOperationException("V1 mappings only support single jar minecraft providers");
			}

			// These are merged v1 mappings
			LOGGER.info(":populating field names");
			// 原子发布：stitch 的字段名补全要求输出文件不存在（临时文件即满足），故无需再先删旧产物
			AtomicFiles.publish(tinyMappings, tmp -> suggestFieldNames(minecraftJars.get(0), baseTinyMappings, tmp));
		}
	}

	private void readAndMergeMCP(Project project, ServiceFactory serviceFactory, MinecraftProvider minecraftProvider, Path mcpJar) throws Exception {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		IntermediateMappingsService intermediateMappingsService = serviceFactory.get(IntermediateMappingsService.createOptions(project, minecraftProvider));
		Path intermediaryTinyPath = intermediateMappingsService.getIntermediaryTiny();
		SrgProvider provider = extension.getSrgProvider();

		if (provider == null) {
			if (!extension.shouldGenerateSrgTiny()) {
				Configuration srg = project.getConfigurations().maybeCreate(Constants.Configurations.SRG);
				srg.setTransitive(false);
			}

			provider = new SrgProvider(project);
			project.getDependencies().add(provider.getTargetConfig(), "de.oceanlabs.mcp:mcp_config:" + extension.getMinecraftProvider().minecraftVersion());
			Configuration configuration = project.getConfigurations().getByName(provider.getTargetConfig());
			provider.provide(DependencyInfo.create(project, configuration.getDependencies().iterator().next(), configuration));
		}

		Path srgPath = getRawSrgFile(project);
		MappingTree tree = new MCPReader(intermediaryTinyPath, srgPath).read(mcpJar);

		// 原子发布 MCP 合并出的 tiny mappings：先在临时文件上写完再原子落位
		AtomicFiles.publish(tinyMappings, tmp -> {
			try (MappingWriter writer = MappingWriter.create(tmp, MappingFormat.TINY_2_FILE)) {
				tree.accept(writer);
			}
		});
	}

	private boolean isMCP(Path path) throws IOException {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(path)) {
			return Files.exists(fs.getPath("fields.csv")) && Files.exists(fs.getPath("methods.csv"));
		}
	}

	private static boolean areMappingsV2(Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			return MappingReader.detectFormat(reader) == MappingFormat.TINY_2_FILE;
		} catch (NoSuchFileException e) {
			// TODO: just check the mappings version when Parser supports V1 in readMetadata()
			return false;
		}
	}

	public static void extractMappings(Path jar, Path extractTo) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getReadOnlyJarFileSystem(jar)) {
			extractMappings(delegate.fs(), extractTo);
		}
	}

	public static void extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Files.copy(jar.getPath("mappings/mappings.tiny"), extractTo, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * 轻量预检：仅凭 zip 中央目录的条目名判断该 jar 是否带 extras，避免为「必然空跑」的
	 * extractExtras 打开 zipfs（打开会建索引，是暖路径上的主要成本）.
	 */
	private static boolean jarContainsExtras(Path inputJar) throws IOException {
		try (ZipFile zipFile = new ZipFile(inputJar.toFile())) {
			if (zipFile.getEntry(AnnotationsLayer.ANNOTATIONS_PATH) != null) {
				return true;
			}

			if (zipFile.getEntry(RECORD_SIGNATURES_PATH) != null) {
				return true;
			}

			return zipFile.getEntry(UnpickMetadata.UNPICK_DEFINITIONS_PATH) != null
					&& zipFile.getEntry(UnpickMetadata.UNPICK_METADATA_PATH) != null;
		}
	}

	private void extractExtras(FileSystem jar) throws IOException {
		extractAnnotationsData(jar);
		extractUnpickDefinitions(jar);
		extractSignatureFixes(jar);
	}

	private void extractAnnotationsData(FileSystem jar) throws IOException {
		Path annotationsPath = jar.getPath(AnnotationsLayer.ANNOTATIONS_PATH);

		if (!Files.exists(annotationsPath)) {
			return;
		}

		try (BufferedReader reader = Files.newBufferedReader(annotationsPath, StandardCharsets.UTF_8)) {
			annotationsData = AnnotationsData.readList(reader);
		}
	}

	private void extractUnpickDefinitions(FileSystem jar) throws IOException {
		Path unpickPath = jar.getPath(UnpickMetadata.UNPICK_DEFINITIONS_PATH);
		Path unpickMetadataPath = jar.getPath(UnpickMetadata.UNPICK_METADATA_PATH);

		if (!Files.exists(unpickPath) || !Files.exists(unpickMetadataPath)) {
			return;
		}

		// 原子发布共享的 mappings.unpick：它位于共享工作目录，锁外（暖路径）也会被重写，
		// 就地写会让并发读方看到半截文件，故走「临时文件 + 原子 move」
		AtomicFiles.copy(unpickPath, unpickDefinitions);

		unpickMetadata = UnpickMetadata.parse(unpickMetadataPath);
	}

	private void extractSignatureFixes(FileSystem jar) throws IOException {
		Path recordSignaturesJsonPath = jar.getPath(RECORD_SIGNATURES_PATH);

		if (!Files.exists(recordSignaturesJsonPath)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(recordSignaturesJsonPath, StandardCharsets.UTF_8)) {
			//noinspection unchecked
			signatureFixes = LoomGradlePlugin.GSON.fromJson(reader, Map.class);
		}
	}

	private void suggestFieldNames(Path inputJar, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, inputJar.toFile().getAbsolutePath(),
						oldMappings.toAbsolutePath().toString(),
						newMappings.toAbsolutePath().toString());
	}

	private void runCommand(Command command, String... args) {
		try {
			command.run(args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Path mappingsWorkingDir() {
		return mappingsWorkingDir;
	}

	protected static String createMappingsIdentifier(String mappingsName, String version, String classifier, String minecraftVersion) {
		//          mappingsName      . mcVersion . version        classifier
		// Example: net.fabricmc.yarn . 1_16_5    . 1.16.5+build.5 -v2
		return mappingsName + "." + minecraftVersion.replace(' ', '_').replace('.', '_').replace('-', '_') + "." + version + classifier;
	}

	protected static String createForgeMappingsIdentifier(LoomGradleExtension extension, String mappingsName, String version, String classifier, String minecraftVersion) {
		final String base = createMappingsIdentifier(mappingsName, version, classifier, minecraftVersion);
		final String platform = extension.getPlatform().get().id();
		final String forgeVersion = extension.getForgeProvider().getVersion().getCombined();
		return base + "-" + platform + "-" + forgeVersion;
	}

	public String mappingsIdentifier() {
		return mappingsIdentifier;
	}

	public File getUnpickDefinitionsFile() {
		return unpickDefinitions.toFile();
	}

	public boolean hasUnpickDefinitions() {
		return unpickMetadata != null;
	}

	public List<AnnotationsData> getAnnotationsData() {
		return annotationsData;
	}

	public UnpickMetadata getUnpickMetadata() {
		return Objects.requireNonNull(unpickMetadata, "Unpick metadata is not available");
	}

	@Nullable
	public Map<String, String> getSignatureFixes() {
		return signatureFixes;
	}

	public Path getReplacedTarget(LoomGradleExtension loom, String namespace) {
		if (namespace.equals("intermediary")) return getPlatformMappingFile(loom);

		return mixinTinyMappings.computeIfAbsent(namespace, k -> {
			Path path = mappingsWorkingDir.resolve("mappings-mixin-" + namespace + ".tiny");

			try {
				// 无锁快路径：产物已在位且未要求刷新时直接返回，不取锁也不写文件
				if (Files.notExists(path) || loom.refreshDeps()) {
					writeReplacedTarget(loom, namespace, path);
				}

				return path;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * 生产一份共享的 {@code mappings-mixin-<ns>.tiny}.
	 *
	 * <p>该文件位于共享的 mappings 工作目录（{@code <userCache>/<mappingsIdentifier>/}），
	 * 多个工作树/daemon 会同时读写它，故写入整段包进与 {@link #produceMappings} 同一把 mappings 锁，
	 * 并采用「临时文件 + 原子 move」发布：读方要么看到旧的完整文件、要么看到新的完整文件，
	 * 不会读到半截内容，也不再「先删后写」制造产物不存在的窗口。
	 */
	private void writeReplacedTarget(LoomGradleExtension loom, String namespace, Path path) throws Exception {
		// 此处拿不到 Project（无法用 LoomCacheService 的 JVM 内监视器），直接用同一 key 的跨进程锁；
		// 同一 JVM 内的并发由 FileLock 的重叠检测 + 轮询等待串行化。
		final Path lockRoot = loom.getFiles().getCacheLocks().toPath();

		CacheEntryLock.withLock(lockRoot, mappingsLockKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：等锁期间可能已被其它进程产出（refresh 时仍需强制重建）
			if (!loom.refreshDeps() && Files.exists(path)) {
				return null;
			}

			List<String> lines = new ArrayList<>(Files.readAllLines(getPlatformMappingFile(loom)));
			lines.set(0, lines.get(0).replace("intermediary", "yraidemretni").replace(namespace, "intermediary"));

			AtomicFiles.publish(path, tmp -> Files.write(tmp, lines));
			return null;
		});
	}

	/**
	 * The mapping file that is specific to the platform settings.
	 * It contains SRG (Forge/common) or Mojang mappings (NeoForge) as needed.
	 *
	 * @return the platform mapping file path
	 */
	public Path getPlatformMappingFile(LoomGradleExtension extension) {
		if (extension.shouldGenerateSrgTiny()) {
			return tinyMappingsWithSrg;
		} else if (extension.isNeoForge()) {
			return tinyMappingsWithMojang;
		} else {
			return tinyMappings;
		}
	}
}
