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
import net.fabricmc.loom.pipeline.GenerateSrgNamedMappingsTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.cache.JarReusability;
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
import net.fabricmc.mappingio.tree.MemoryMappingTree;
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

	/**
	 * srg→named 产物的产出任务路径；未投影（整批回退配置期）时为 {@code null}.
	 *
	 * <p>它承载消费侧接线：消费方（{@code GenerateDLIConfigTask}）只把产物**路径**当字符串写进 DLI 配置，
	 * Gradle 看不见这条输入依赖，故由消费方在任务图上显式声明（见
	 * {@link #projectSrgNamedToTasks(Project, LoomGradleExtension)}）。按路径而不是任务实例传递：
	 * 产出方可能由另一份 Loom classloader 配置。
	 */
	@Nullable
	private String srgNamedTaskPath;

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
		// 线程安全实现：本实例经 SHARED_INSTANCES 在 daemon 内跨项目共享，而 getReplacedTarget 由
		// Mixin AP 的配置路径调用——两个项目并行配置时会同时向这张表 computeIfAbsent，普通 HashMap
		// 在扩容/树化期间并发写会丢条目甚至自引用死循环。ConcurrentHashMap 的 computeIfAbsent 对同一 key
		// 只允许一个线程执行映射函数、其余线程等其结果，正好满足「同一 namespace 只产出一份」的语义。
		// 注意它禁止映射函数内部再改同一张表（会抛 IllegalStateException），本类的映射函数只做文件 I/O。
		this.mixinTinyMappings = new ConcurrentHashMap<>();
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

	/**
	 * {@return srg→named 产物的产出任务路径；未投影（整批回退配置期）时为 {@code null}}.
	 *
	 * <p>供消费方把「我的输入就是你的产出」表达成任务依赖。返回值是任务**路径**而不是任务实例：产出方
	 * 可能由另一份 Loom classloader 配置（约定插件/included build 各自带一份 Loom），把对方的任务实例
	 * 交给本 classloader 的任务会在使用处抛 {@link ClassCastException}。
	 */
	public @Nullable String getSrgNamedTaskPath() {
		return srgNamedTaskPath;
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
		// 标记为失效值 ⟹ 上一轮在两次发布之间失败（publish 抛异常、进程被杀），盘上就是「新的 mappings.tiny
		// + 旧的 mappings.jar」这一被撕开的一对；而这两件产物在存在性判定下都「在」，若只看存在性就会直接
		// 走到方法末尾把标记写回就绪值，把混合代次认证成「成对」。故这里强制重写两件产物（安全方向）。
		// 注意与「标记缺失」区分：标记缺失只说明标记是本轮新增或被外部清理，产物本身可能完好，
		// 沿用下面的补写标记路径即可，不必白白重建一次。
		final boolean pairTorn = readPairMarkerState() == PairMarkerState.INVALIDATED;

		// 刷新（--refresh-dependencies）不再靠删除共享产物实现：删除会制造「产物不存在」窗口，
		// 锁外的存在性快路径会误判、并连带删掉其它进程正在读的文件。
		// 改为「强制重建 + 原子替换」，读方始终看到旧的完整文件或新的完整文件。
		// 就绪判据必须与锁外的 hasUsableMappingsPair 成对，且两件产物各用各的口径：
		// 只判存在时，0 字节残骸会被锁外快路径判为未就绪（每轮进锁），却在锁内被判为「已有产物」而跳过重写，
		// 于是残骸永远不会自愈；这里改成内容级判据后，「快路径判不可用」蕴含「锁内必然重写」。
		final boolean writeTiny = refresh || pairTorn || !isReusableMappingsText(tinyMappings);
		// mappings.jar 的内容派生自 mappings.tiny（把同一份 tiny 原样打进 zip），故本轮重写了 tiny 就必须
		// 同轮重写 jar：否则「新的 mappings.tiny + 旧的 mappings.jar」同样会被后面的标记认证为成对就绪。
		final boolean writeJar = writeTiny || !JarReusability.isReusable(tinyMappingsJar);

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
	 * 若标记停在失效值（上一轮两次发布之间失败），则不做这种「补写」——
	 * 那种状态意味着盘上的一对可能已被撕开，{@link #produceMappings} 会强制重写两件产物。
	 *
	 * <p>标记就绪之外还要求两件产物「内容可用」：这对产物位于跨工作树/daemon 共享的 mappings 工作目录，
	 * 被旧版本 loom 就地重建时可能留下 0 字节残骸。两者的判据不同，不能一刀切：
	 * {@code mappings.tiny} 是纯文本，按 {@link #isReusableMappingsText(Path)} 判定；
	 * {@code mappings.jar} 是 zip，按 {@link JarReusability#isReusable(Path)} 判定。
	 * 判据与 {@link #produceMappings} 内的锁内确认成对（外层快路径判为未就绪 ⟹ 锁内必然重写该件），
	 * 否则会出现「每轮都进锁、却永远修不好残骸」的无声循环。
	 */
	private boolean hasUsableMappingsPair() {
		return readPairMarkerState() == PairMarkerState.READY
				&& isReusableMappingsText(tinyMappings) && JarReusability.isReusable(tinyMappingsJar);
	}

	/**
	 * 成对就绪标记的读取结果.
	 *
	 * <p>必须把「标记缺失」与「标记为失效值」分成两种状态：前者可安全沿用补写路径，后者表示存在被撕开的一对，
	 * 必须强制重建。把两者都当作「不可用」会让 {@link #produceMappings} 无法区分该走哪条路。
	 */
	private enum PairMarkerState {
		/** 标记为就绪值：两件产物已被提交为一对. */
		READY,
		/** 标记为失效值：上一轮正在（重新）生成这一对产物，且没有走完. */
		INVALIDATED,
		/** 标记缺失或内容不可读：升级后首次构建、被外部清理，或正被并发原子替换. */
		UNKNOWN
	}

	/**
	 * 读取成对就绪标记的状态.
	 *
	 * <p>读不到文件或读失败（例如正被并发原子替换）都归入 {@link PairMarkerState#UNKNOWN}：
	 * 那是「无法判断」而非「已失效」，据此强制重建只会在升级后多重建一次，代价可接受但非必要。
	 */
	private PairMarkerState readPairMarkerState() {
		try {
			final String content = Files.readString(mappingsPairMarker, StandardCharsets.UTF_8);

			if (PAIR_MARKER_READY.equals(content)) {
				return PairMarkerState.READY;
			}

			// 只有确认为失效值才返回 INVALIDATED：标记文件被外部写坏时按「无法判断」处理，避免无谓重建
			return PAIR_MARKER_INVALID.equals(content) ? PairMarkerState.INVALIDATED : PairMarkerState.UNKNOWN;
		} catch (IOException e) {
			return PairMarkerState.UNKNOWN;
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
		// 就绪判据为内容级（见 isReusableMappingsText），不能只判存在：两件产物都在共享工作目录下被
		// 多个工作树/daemon 交叉读写，被中断的就地写会留下 0 字节残骸，存在性判定会把它永久复用。
		final boolean needsMojangMerge = extension.isNeoForge() && (refresh || !isReusableMappingsText(tinyMappingsWithMojang));
		final boolean needsSrgMerge = extension.shouldGenerateSrgTiny() && (refresh || !isReusableMappingsText(tinyMappingsWithSrg));

		if (needsMojangMerge || needsSrgMerge) {
			// 一次写一组互相依赖的共享产物（mojang 合并结果 + srg 合并结果）时，整段包进同一把 mappings 锁：
			// 与 produceMappings 共用 key，避免同一 mappings 的产物被不同工作树/daemon 交叉生产。
			runWithMappingsLock(project, "生成共享 mappings（mojang/srg 合并结果）失败", () -> {
				// Generate the Mojmap-merged mappings if needed.
				// Note that this needs to happen before manipulateMappings for FieldMigratedMappingConfiguration.
				if (needsMojangMerge && (refresh || !isReusableMappingsText(tinyMappingsWithMojang))) {
					mergeMojangAtomic(project, tinyMappingsWithMojang);
				}

				// 锁内二次确认：等锁期间可能已被其它进程产出（refresh 时仍需强制重建）
				if (needsSrgMerge && (refresh || !isReusableMappingsText(tinyMappingsWithSrg))) {
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

			final String blocker = srgNamedProjectionBlocker();

			if (blocker == null) {
				projectSrgNamedToTasks(project, extension);
			} else {
				// 整批回退：配置期路径逐字保留（含它自己的就绪判据、跨进程锁与原子发布）
				generateSrgNamedInConfiguration(project, extension, blocker);
			}
		}

		project.getDependencies().add(Constants.Configurations.MAPPINGS_FINAL, project.files(tinyMappingsJar.toFile()));
	}

	/**
	 * {@return 不能把 srg→named 的生成投影成执行期任务的原因；可以投影时为 {@code null}}.
	 *
	 * <p>本产物的消费面很窄，全仓库只有一处读它：{@code GenerateDLIConfigTask} 把它的**路径**写进
	 * DLI 配置的 {@code net.minecraftforge.gradle.GradleStart.srg.srg-mcp}
	 * （见 {@code GenerateDLIConfigTask.ForgeInputs}），真正的读取发生在游戏启动时（ForgeGradle 的
	 * {@code GradleStart} 按那个属性打开文件）。既然唯一的消费方是一个任务、而它的依赖可以接线
	 * （见 {@link #projectSrgNamedToTasks}），判据就只剩一类「配置期必须真读/真写该产物」的形态。
	 *
	 * <p><b>任何新增的配置期读者都必须在这里补一条判据</b>：回退必须可见，不能是静默的——新增读者
	 * 读到的是「产出任务还没跑」的文件。
	 *
	 * <ul>
	 *   <li><b>映射树来源不可用</b>：srg 命名空间的映射树（Forge 下即 {@code mappings-srg-migrated.tiny}）
	 *       缺失或为空时投影对双方都没有好处：任务拿到的是不可读的输入（必然失败），而配置期那条路径
	 *       会以它自己的判据处理这一轮——产物可用就直接复用、不碰输入。这类残骸形态本仓库实测出现过
	 *       （见 {@link #isReusableMappingsText(Path)}），故整批回退，让改造前的路径去处理。</li>
	 * </ul>
	 */
	private @Nullable String srgNamedProjectionBlocker() {
		final Path source = srgNamedMappingsPath();

		if (source == null) {
			return "srg 命名空间的映射树来源未登记（" + MappingOption.WITH_SRG + "）：产出任务没有可读的输入";
		}

		if (!isReusableMappingsText(source)) {
			return "srg 命名空间的映射树不可用（" + describeMappingsTextSize(source) + "）：产出任务没有可读的输入";
		}

		return null;
	}

	/**
	 * {@return srg 命名空间的映射树路径；该选项未登记时为 {@code null}}.
	 *
	 * <p>与 {@link #getMappingsPath(MappingOption)} 的区别只有一个：不因「文件不存在」抛异常。投影前的
	 * 判定本身就要能看见并描述这种形态（见 {@link #srgNamedProjectionBlocker()}），否则判定会退化成一个
	 * 没人接得住的异常。
	 */
	private @Nullable Path srgNamedMappingsPath() {
		final Supplier<Path> mappingsSupplier = mappingOptions.get(MappingOption.WITH_SRG);
		return mappingsSupplier == null ? null : mappingsSupplier.get();
	}

	/**
	 * 把 srg→named 的生成投影成执行期任务.
	 *
	 * <p>配置期只做「配置期已知量 → 任务输入」的映射，不写产物文件：产物有效性交给 Gradle 的 up-to-date
	 * 判定与构建缓存，并发保护交给任务图。产物路径沿用既有位置（{@code <userCache>/<mappingsIdentifier>/}），
	 * 否则 {@code GenerateDLIConfigTask} 写进 DLI 配置的那条路径会悬空。
	 *
	 * <p>输入取 {@code MappingOption.WITH_SRG} 指向的 tiny 文件——与旧路径经 {@code TinyMappingsService}
	 * 读的是同一份文件；该文件由 mappings 阶段（{@code setupPost} / {@code manipulateMappings}）产出，
	 * 因此本任务的输入在配置期结束时必定已落位（这也是本次不把整棵映射树推迟到执行期的原因）。
	 *
	 * <p><b>消费侧接线</b>：{@link #srgNamedTaskPath} 登记给消费方（{@code GenerateDLIConfigTask}），
	 * 由它在任务图上建依赖。按任务**路径**而不是任务实例登记：产出方可能由另一份 Loom classloader
	 * 配置（约定插件/included build 各自带一份 Loom），把对方的任务实例交过来会在使用处抛
	 * {@link ClassCastException}。
	 */
	private void projectSrgNamedToTasks(Project project, LoomGradleExtension extension) {
		final Path source = Objects.requireNonNull(srgNamedMappingsPath(), "投影前已由判据保证输入可用");
		final Path output = srgToNamedSrg;
		final boolean refresh = extension.refreshDeps();

		// 同一产物路径在本构建内只能有一个生产者：同一构建内的多个同构项目会算出同一条路径。
		// 指纹只放**决定内容**的量。刻意不放 refreshDeps：它只影响「这轮要不要重建」，
		// 不改变字节，而它按项目设置（loom.refreshDeps），放进指纹会让「只有其中一个项目刷新」
		// 变成整批共享失败。
		srgNamedTaskPath = RemapMinecraftTaskRegistry.claim(project, output, Map.of(
				"stage", "srg-named",
				"mappings", source.toAbsolutePath().normalize().toString()
		), () -> project.getTasks().register("generateSrgNamedMappings", GenerateSrgNamedMappingsTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Generates the srg -> named mappings for %s".formatted(mappingsIdentifier));
			task.getMappings().set(source.toFile());
			task.getRefreshDeps().set(refresh);
			task.getSrgFile().set(output.toFile());
		})).taskPath();

		// 用 Gradle 的 lifecycle 而不是 SLF4J 的 info：默认控制台级别是 LIFECYCLE，
		// 「本次到底走哪条生产路径」必须默认可见，否则回退是静默的
		project.getLogger().lifecycle("srg→named 映射的生成由执行期任务承担：{}", srgNamedTaskPath);
	}

	/**
	 * 配置期生成 srg→named 的旧路径（整批回退时使用）.
	 *
	 * <p>本方法就是改造前 {@code applyToProject} 里的那一段，逐字保留：判据、跨进程锁、锁内二次确认、
	 * 原子发布都不动。回退路径与投影路径产出的是同一件产物、同一条路径，因此它必须一直可用
	 * （也是「投影判据必须与回退原因同处」的意义所在）。
	 *
	 * @param blocker 回退原因，只用于留痕
	 */
	private void generateSrgNamedInConfiguration(Project project, LoomGradleExtension extension, String blocker) throws IOException {
		project.getLogger().lifecycle("srg→named 映射的生成整批回退到配置期：{}", blocker);

		// 就绪判据为内容级（见 isReusableMappingsText）。该产物虽以 .srg 结尾，内容仍是纯文本：
		// 它由 MappingWriter.create(tmp, MappingFormat.SRG_FILE) 经 java.io.Writer 逐行写出，
		// 与 .tiny 同属文本映射而非 zip，故同样不能套用 JarReusability 的 zip 口径，取「存在且非空」。
		// 只判存在的代价偏大：该文件是 dev 启动配置里 SRG→named 的映射来源，复用 0 字节残骸
		// 会让开发环境静默地按错误映射启动，而不是报错。
		if (!isReusableMappingsText(srgToNamedSrg) || extension.refreshDeps()) {
			final boolean refresh = extension.refreshDeps();
			// 该产物与 mappings-srg.tiny 同处共享工作目录且由它派生，故与 produceMappings / setupPost
			// 共用同一把 mappings 锁，避免不同工作树/daemon 交叉生产这一组互相依赖的产物。
			runWithMappingsLock(project, "生成 srg->named mappings 失败", () -> {
				// 锁内二次确认：等锁期间可能已被其它进程产出（refresh 时仍需强制重建）
				if (!refresh && isReusableMappingsText(srgToNamedSrg)) {
					return null;
				}

				// 原子发布：先在临时文件上写完整份 srg 文本，再原子 move 落位
				AtomicFiles.publish(srgToNamedSrg, tmp -> writeSrgNamedMappings(srgNamedTree(project), tmp));
				return null;
			});
		}
	}

	/** {@return 配置期读到的 srg 命名空间映射树} 走 {@code TinyMappingsService}，与改造前同一来源. */
	private MemoryMappingTree srgNamedTree(Project project) throws IOException {
		try (var serviceFactory = new ScopedServiceFactory()) {
			TinyMappingsService mappingsService = getMappingsService(project, serviceFactory, MappingOption.WITH_SRG);
			return mappingsService.getMappingTree();
		}
	}

	/**
	 * 把 {@code srg → named} 的映射整份写成 SRG 文本.
	 *
	 * <p>两条路径共用这一份实现：读写方式、命名空间切换与目标命名空间必须逐字一致，否则同一件产物在
	 * 「投影」与「回退」下会不一样。本流水线的其余环节（合并/拆分/重映射）同样按「同一份实现被两条路径
	 * 共用」处置。
	 *
	 * @param source 输入的 tiny 映射（srg 命名空间的映射树）
	 * @param target 输出路径；必须是**尚不存在**的路径（调用方负责原子发布，见 {@code AtomicFiles}）
	 */
	public static void writeSrgNamedMappings(Path source, Path target) throws IOException {
		final MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(source, mappingTree);
		writeSrgNamedMappings(mappingTree, target);
	}

	/**
	 * 把 {@code srg → named} 的映射整份写成 SRG 文本.
	 *
	 * <p>与 {@link #writeSrgNamedMappings(Path, Path)} 的差别只有「映射树从哪来」：执行期路径直接读
	 * {@code MappingOption.WITH_SRG} 指向的文件，配置期回退路径读 {@code TinyMappingsService} 的树
	 * （同一份文件、同一个读法）。写法本身共用，避免两套转换逻辑产出不等价的 srg 文本。
	 *
	 * @param mappingTree srg 命名空间的映射树
	 * @param target      输出路径；必须是**尚不存在**的路径（调用方负责原子发布，见 {@code AtomicFiles}）
	 */
	public static void writeSrgNamedMappings(MappingTree mappingTree, Path target) throws IOException {
		try (MappingWriter writer = MappingWriter.create(target, MappingFormat.SRG_FILE)) {
			MappingVisitor visitor = new MappingSourceNsSwitch(new MappingDstNsReorder(writer, "named"), "srg");
			mappingTree.accept(visitor);
		}
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
	 * {@return 该映射文本产物是否可作为输入复用}.
	 *
	 * <p>本类的多件共享产物——{@code intermediary-v2.tiny}、{@code mappings-mojang.tiny}、
	 * {@code mappings-srg.tiny}、{@code mappings-srg-named.srg} 与 mixin 映射——都是纯文本映射，不是 jar，
	 * 故一律不适用 {@link net.fabricmc.loom.util.cache.JarReusability#isReusable(Path)} 的 zip 口径：
	 * 拿文本去开 zipfs 必然失败，会把正常产物永久判为不可用。它们的内容判据等价地取「存在且非空」：
	 *
	 * <ul>
	 *     <li>正常产物恒非空——{@code .tiny} 至少含映射头，{@code .srg} 由 {@code MappingWriter} 经
	 *     {@code java.io.Writer} 逐行写出（SRG 与 tiny 同为文本格式，不是 zip 也不是压缩流），
	 *     二者都必然写出内容，故这条不会把正常产物拖进「每次构建都重建」；</li>
	 *     <li>能拦下「先删后写」被中断、或旧版本 loom 就地重建时留下的 0 字节残骸。这类残骸正是本仓库
	 *     实测过的形态，而被复用后会一路传到最终产物：下游 {@code MappingReader} 读到空映射，
	 *     或 {@link #getReplacedTarget} 在改写首行时对空行表取下标而抛异常。</li>
	 * </ul>
	 *
	 * <p>刻意不做逐行解析、首尾行或末尾换行等更严的校验：该判定位于每次构建的无锁快路径上，
	 * 全量解析一份 stitch 产物是秒级开销，收益却只覆盖「截断到非 0 长度」这一小类残骸。
	 *
	 * @param mappingsFile 待判定的映射文本产物路径
	 */
	public static boolean isReusableMappingsText(Path mappingsFile) {
		try {
			return Files.size(mappingsFile) > 0;
		} catch (IOException e) {
			// 不存在（NoSuchFileException）或读不到元数据：按不可复用处理，交由调用方重新生成
			return false;
		}
	}

	/**
	 * 诊断用：映射文本产物的大小.
	 *
	 * <p>「0 字节」与「文件不存在」在现象上都是「判据不通过」，但对排查者是完全不同的两种原因，
	 * 故报错信息里必须带上大小。取不到元数据时只损失这一项诊断信息，不影响判定本身。
	 */
	private static String describeMappingsTextSize(Path mappingsFile) {
		try {
			return Files.size(mappingsFile) + " 字节";
		} catch (IOException e) {
			return "大小未知（文件不存在或读不到元数据）";
		}
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

	/**
	 * 取得 Mixin refmap 重映射所需的映射文件.
	 *
	 * <p>{@code intermediary} 命名空间直接返回平台映射文件（见 {@link #getPlatformMappingFile}），
	 * 其余命名空间返回一份由它派生的、改写过首行的 {@code mappings-mixin-*.tiny}（见 {@link #writeReplacedTarget}）。
	 * 两条路都以平台映射文件为输入，故这里先按共享产物的文本判据（见 {@link #isReusableMappingsText(Path)}）
	 * 确认它可用：
	 *
	 * <ul>
	 *     <li>直接返回的分支原先没有任何就绪判定，0 字节残骸会被原样交给 Mixin AP，
	 *     让 refmap 静默地按空映射生成（直到运行时才以「找不到映射」的形式炸开）；</li>
	 *     <li>派生分支读取该文件后立刻取首行（{@code lines.get(0)}），空行表会抛出无从诊断的
	 *     {@link IndexOutOfBoundsException}。</li>
	 * </ul>
	 *
	 * <p>这里只做「不可用即报错」，不尝试就地重建：平台映射文件由 mappings 阶段
	 * （{@code setup} / {@code setupPost}）产出，本方法既拿不到 Project 也拿不到 ServiceFactory，
	 * 无法重跑那条流水线；而唯一的调用方（Mixin AP 的参数装配）必然在 mappings 阶段之后执行，
	 * 正常构建里该文件恒非空，故这条判定不会误报，只会在确有残骸时把静默错误换成可诊断的失败。
	 *
	 * @param loom      当前项目的 loom 扩展
	 * @param namespace refmap 的目标命名空间
	 */
	public Path getReplacedTarget(LoomGradleExtension loom, String namespace) {
		final Path platformMappings = getPlatformMappingFile(loom);

		if (!isReusableMappingsText(platformMappings)) {
			throw new IllegalStateException(("平台映射文件不可用：%s（%s），无法为命名空间 %s 提供 Mixin 映射。"
					+ "该文件由 mappings 阶段产出，正常构建中不应缺失或为空；若确认它是残骸，"
					+ "请删除该文件后重新构建（或使用 --refresh-dependencies 强制重建）。")
					.formatted(platformMappings, describeMappingsTextSize(platformMappings), namespace));
		}

		if (namespace.equals("intermediary")) return platformMappings;

		return mixinTinyMappings.computeIfAbsent(namespace, k -> {
			Path path = mappingsWorkingDir.resolve("mappings-mixin-" + namespace + ".tiny");

			try {
				// 无锁快路径：产物已在位且未要求刷新时直接返回，不取锁也不写文件。
				// 就绪判据为内容级（见 isReusableMappingsText）：本文件内容源自 getPlatformMappingFile
				// 的逐行拷贝，是纯文本 tiny；0 字节残骸被复用会让 Mixin AP 读到空映射。
				if (!isReusableMappingsText(path) || loom.refreshDeps()) {
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
		// 此处拿不到 Project（无法用 LoomCacheService 的 JVM 内监视器），直接用同一 key 的跨进程锁。
		// 该锁不可重入：同一 JVM 内已有线程持有同一 key 时，CacheEntryLock.acquireFileLockWithTimeout
		// 只在 OverlappingFileLockException 之后空等轮询，直到 LoomCacheService.defaultTimeout() 超时。
		// 因此调用点必须保证不会在已持有该 key 的锁内再次进入（尤其是与 produceMappings 的重入）；
		// 本方法自身不调用任何需要同一把锁的代码。
		final Path lockRoot = loom.getFiles().getCacheLocks().toPath();

		CacheEntryLock.withLock(lockRoot, mappingsLockKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：等锁期间可能已被其它进程产出（refresh 时仍需强制重建）。
			// 判据必须与外层快路径一致，否则 0 字节残骸会在锁内被判为「已产出」而直接返回，
			// 快路径每轮都进锁、却永远修不好该文件。
			if (!loom.refreshDeps() && isReusableMappingsText(path)) {
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
