/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2025 FabricMC
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import dev.architectury.loom.accesstransformer.AccessTransformerService;
import dev.architectury.loom.forge.AccessTransformerRuleTranslator;
import dev.architectury.loom.forge.CoreModClassRemapper;
import dev.architectury.loom.forge.ForgeMigratedMappingConfiguration;
import dev.architectury.loom.forge.InnerClassRemapper;
import dev.architectury.loom.forge.JarClassHierarchy;
import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.forge.dependency.DependencyProvider;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.dependency.ForgeUserdevProvider;
import dev.architectury.loom.forge.dependency.PatchProvider;
import dev.architectury.loom.forge.tool.ForgeExternalToolService;
import dev.architectury.loom.forge.tool.ForgeMavenizerService;
import dev.architectury.loom.mappings.MappingOption;
import dev.architectury.loom.mcpconfig.McpConfigProvider;
import dev.architectury.loom.mcpconfig.McpExecutor;
import dev.architectury.loom.mcpconfig.McpExecutorBuilder;
import dev.architectury.loom.neoforge.SidedJarIndexGenerator;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.Stopwatch;
import dev.architectury.loom.util.TempFiles;
import dev.architectury.loom.util.ThreadingUtils;
import dev.architectury.loom.util.Version;
import dev.architectury.loom.util.function.FsPathConsumer;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.pipeline.AccessTransformForgeJarTask;
import net.fabricmc.loom.pipeline.GenerateForgeMavenizerJarTask;
import net.fabricmc.loom.pipeline.GenerateForgePatchedIntermediateJarTask;
import net.fabricmc.loom.pipeline.GenerateForgePatchedJarTask;
import net.fabricmc.loom.pipeline.GenerateForgePrePatchJarTask;
import net.fabricmc.loom.pipeline.PrepareForgeMavenizerSourcesTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.cache.JarReusability;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public class MinecraftPatchedProvider {
	private static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	protected static final String CURRENT_LOOM_PATCH_VERSION = "12";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	// The version where the bug was introduced.
	private static final String MIN_NEOFORGE_MANUAL_CLEAN_JAR_CREATION_VERSION = "21.10.57-beta";
	// The version where the bug was fixed.
	private static final String MAX_NEOFORGE_MANUAL_CLEAN_JAR_CREATION_VERSION = "21.10.64";

	/**
	 * 判定「两侧同一命名空间」的类名重合率下限.
	 *
	 * <p>同一命名空间下补丁只改动少部分类名，重合率接近 1；跨命名空间（重映射前后）接近 0。
	 * 取 0.3 这种保守值：宁可跳过纠正（保留缺陷、由警告暴露），也不要误改。
	 */
	private static final double SAME_NAMESPACE_THRESHOLD = 0.3;

	// 对子类开放：legacy Forge（1.8-1.16）支持的 MinecraftLegacyPatchedProvider 需要访问这些成员
	protected final Project project;
	protected final Logger logger;
	protected final MinecraftProvider minecraftProvider;
	protected final Type type;

	// Step 1: Remap Minecraft to intermediate mappings, merge if needed
	private Path minecraftIntermediateJar;
	// Step 2: Binary Patch
	private Path minecraftPatchedIntermediateJar;
	// Step 3: Access Transform
	private Path minecraftPatchedIntermediateAtJar;
	/**
	 * 中间产物的命名空间标识，同时决定 AT 规则的源命名空间.
	 *
	 * <p>它与产物文件名用的是同一个值（构造器里算出的 {@code intermediateId}）：
	 * Forge 是 {@code srg}，NeoForge 是 {@code mojang}（unobfuscated 时 {@code official}）。
	 *
	 * <p>AT 规则的书写命名空间随平台而变，实测两版 Forge 的 userdev AT 文件对同一个方法写法不同：
	 * Forge 1.21.1 写 {@code m_10595_}（srg），NeoForge 1.20.2 写 {@code register}（mojang）。
	 * 故 AT 的源命名空间必须取本值，不能硬编码成 srg——硬编码会让 NeoForge 车道的 508 条规则
	 * 全部因「类找不到」而失败（NeoForge 的映射树里根本没有 srg 列）。
	 */
	private String intermediateNamespace;
	// Step 4: Remap Patched AT & Forge to official
	private Path minecraftPatchedJar;
	private Path minecraftClientExtra;

	// 对子类开放：legacy Forge 子类需要读写该标志，且 isDirty() 必须能反映子类的重建状态
	protected boolean dirty = false;

	// pre-patch jar 的产出方：投影成任务时非空（见 registerPatchedChainTasks），配置期生产时为 null
	private @Nullable Producer prePatchJarProducer;

	public static MinecraftPatchedProvider get(Project project) {
		MinecraftProvider provider = LoomGradleExtension.get(project).getMinecraftProvider();

		if (provider instanceof ForgeMinecraftProvider patched) {
			return patched.getPatchedProvider();
		} else {
			throw new UnsupportedOperationException("Project " + project.getPath() + " does not use MinecraftPatchedProvider!");
		}
	}

	public MinecraftPatchedProvider(Project project, MinecraftProvider minecraftProvider, Type type) {
		this.project = project;
		this.logger = project.getLogger();
		this.minecraftProvider = minecraftProvider;
		this.type = type;
	}

	private LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(project);
	}

	public static final String MAVENIZER_GENERATION = "mavenizer-v1";
	private static final String MAVENIZER_MCP = "1.21.11-20251223.124241";

	/** 下游按生产策略分流，不根据文件名猜测字节码来源. */
	public boolean usesMavenizer() {
		return usesMavenizer(getExtension(), type, minecraftProvider.minecraftVersion());
	}

	private static boolean usesMavenizer(LoomGradleExtension extension, Type type, String mcVersion) {
		return type == Type.MERGED && "1.21.11".equals(mcVersion) && extension.isForge()
				&& !extension.isLegacyForge() && !extension.disableObfuscation() && !extension.isUnobfuscatedForge()
				&& "1.21.11-61.2.5".equals(extension.getForgeProvider().getVersion().getCombined());
	}

	public static boolean usesMavenizer(LoomGradleExtension extension, MinecraftProvider minecraft) {
		return minecraft instanceof MergedForgeMinecraftProvider && usesMavenizer(extension, Type.MERGED, minecraft.minecraftVersion());
	}

	public static String mappingGenerationSuffix(LoomGradleExtension extension, MinecraftProvider minecraft) {
		return usesMavenizer(extension, minecraft) ? "-" + MAVENIZER_GENERATION : "";
	}

	public Path productionCacheDirectory() {
		Path base = ForgeProvider.getForgeCache(project);
		return usesMavenizer() ? base.resolve(MAVENIZER_GENERATION) : base;
	}

	private String mavenizerMcpVersion() {
		String mcp = getExtension().getForgeUserdevProvider().getConfig().mcp();

		if (!("de.oceanlabs.mcp:mcp_config:" + MAVENIZER_MCP + "@zip").equals(mcp)) {
			throw new IllegalStateException("Forge 官方源码重编译未验证此 MCP 坐标：" + mcp);
		}

		return MAVENIZER_MCP;
	}

	private Provider<ForgeMavenizerService.Options> mavenizerOptions() {
		return ForgeMavenizerService.createOptions(project, getExtension().getForgeProvider().getVersion().getCombined(),
				minecraftProvider.minecraftVersion(), mavenizerMcpVersion(), productionCacheDirectory().resolve("tool"));
	}

	/** 声明同一批次的源码原料；只由源码任务消费，不参与游戏构建或启动. */
	public Producer registerMavenizerSourcesBundle() {
		if (!usesMavenizer()) {
			throw new IllegalStateException("当前 Forge 产物不是 Mavenizer 源码重编译产物");
		}

		final Path runtime = getMinecraftPatchedIntermediateJar();
		final Path receipt = ForgeMavenizerService.receipt(runtime);
		final Path bundle = productionCacheDirectory().resolve("mavenizer-sources-v1.zip");
		final var identity = Map.of(
				"stage", "mavenizer-sources-v1", "runtime", runtime.toString(), "mcp", mavenizerMcpVersion());

		return RemapMinecraftTaskRegistry.claim(project, bundle, identity, () -> {
			return project.getTasks().register(PrepareForgeMavenizerSourcesTask.NAME, PrepareForgeMavenizerSourcesTask.class, task -> {
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getMavenizer().set(mavenizerOptions());
				task.getRuntimeSrgJar().set(runtime.toFile());
				task.getRuntimeReceipt().set(receipt.toFile());
				task.getOutputBundle().set(bundle.toFile());
				// 收据与运行时同属一个生产任务；配置期回退时两件均已落盘。
				minecraftProvider.addProducerDependency(task, runtime);
			});
		});
	}

	private void initPatchedFiles() {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = productionCacheDirectory();
		// Note: strings used instead of platform id since FML requires one of these exact strings
		// depending on the loader to recognise Minecraft.
		String patchId = (getExtension().isNeoForge() ? "neoforge" : "forge") + "-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		final String intermediateId = getExtension().isNeoForge()
				? (getExtension().isUnobfuscatedForge() ? "official" : "mojang")
				: "srg";
		intermediateNamespace = intermediateId;
		minecraftIntermediateJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + ".jar");
		minecraftPatchedIntermediateJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + "-patched.jar");
		minecraftPatchedIntermediateAtJar = usesMavenizer() ? minecraftPatchedIntermediateJar : forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + "-at-patched.jar");
		minecraftPatchedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-patched.jar");
		minecraftClientExtra = forgeWorkingDir.resolve("client-extra.jar");
	}

	/**
	 * {@return 本配置下必须可复用的共享产物列表，供 {@link #needsWork()} 判定}.
	 *
	 * <p>列表只包含当前 jar 配置确实会生成的产物：server-only 不提供 client jar，
	 * {@code client-extra.jar} 既不生成也不进 classpath、不注册进 FORGE_EXTRA（见 {@link #providesClientJar()}），
	 * 把它计入判定会让 {@link #needsWork()} 恒为真、每次构建都白取一次跨进程锁。
	 */
	protected Path[] getGlobalCaches() {
		if (usesMavenizer()) {
			return new Path[] { minecraftPatchedIntermediateJar, minecraftPatchedJar, minecraftClientExtra };
		}

		List<Path> files = new ArrayList<>(Arrays.asList(
				minecraftIntermediateJar,
				minecraftPatchedIntermediateJar,
				minecraftPatchedIntermediateAtJar,
				minecraftPatchedJar,
				minecraftClientExtra
		));

		if (!providesClientJar()) {
			files.remove(minecraftClientExtra);
		}

		return files.toArray(Path[]::new);
	}

	/**
	 * {@return 该共享产物是否可作为输入复用}.
	 *
	 * <p>口径与理由见 {@link JarReusability#isReusable(Path)}：能作为 zip 打开且至少含一个条目。
	 * 实现只有那一份，其它共享缓存链（mapped minecraft jar、mcp.zip、SrgProvider 产物等）用的是同一判定；
	 * 本方法保留，是因为 legacy 子类仍以 {@code isReusableJar} 的名字调用它。
	 */
	protected static boolean isReusableJar(Path jar) {
		return JarReusability.isReusable(jar);
	}

	/**
	 * 锁内二次确认：判定现有产物能否复用，并返回本次是否需要整链重建.
	 *
	 * <p>本方法刻意不再删除共享产物。这些 jar 位于跨 daemon 共享的 forge 缓存目录：
	 * 删除会让锁外只做存在性判定的读方拿到缺失文件，也会让其它工作树误判「需要重建」而互相触发重建。
	 * 取而代之的策略是：
	 * <ul>
	 *     <li>可复用的产物原样保留，缺失或损坏的产物由后续步骤按件补齐（{@link #needsWork()} 同样基于可复用性判定）；</li>
	 *     <li>需要整链重建时由返回值表达，各步骤以「临时文件 + 原子落位」重新生成，
	 *     读方见到的要么是旧文件、要么是完整的新文件。</li>
	 * </ul>
	 *
	 * @return 是否需要整链重建
	 */
	protected boolean checkCache() throws IOException {
		if (getExtension().refreshDeps()) {
			// 显式刷新（--refresh-dependencies / -Dloom.refresh）：强制整链重建
			return true;
		}

		// 最终产物的 manifest 承载补丁版本标记；缺失或版本过期时，中间产物无法证明与当前算法同代，整链重建
		try {
			return !isPatchedJarUpToDate(minecraftPatchedJar);
		} catch (IOException e) {
			// manifest 读不出来：共享产物被外部破坏，同样按整链重建处理
			return true;
		}
	}

	// See https://github.com/neoforged/NeoForge/issues/2848
	private boolean shouldUseNeoForgeInstallerToolsToCreatePrePatchJar() {
		if (!getExtension().isNeoForge()) {
			return false;
		}

		Version currentVersion = Version.parse(getExtension().getForgeProvider().getVersion().getCombined());
		Version minVersion = Version.parse(MIN_NEOFORGE_MANUAL_CLEAN_JAR_CREATION_VERSION);

		if (currentVersion.compareTo(minVersion) < 0) {
			return false; // old enough to skip the workaround
		}

		Version maxVersion = Version.parse(MAX_NEOFORGE_MANUAL_CLEAN_JAR_CREATION_VERSION);
		return currentVersion.compareTo(maxVersion) < 0;
	}

	public void provide() throws Exception {
		initPatchedFiles();

		// 可投影则把三级产物（pre-patch / intermediate / at-patched）的生产交给执行期任务，
		// 否则整批沿用改造前的配置期路径（含留痕，见 provideProjectionBlocker）
		final String blocker = provideProjectionBlocker();

		if (blocker == null) {
			registerPatchedChainTasks();
			return;
		}

		logger.lifecycle("Forge 的 patched 中间产物保持配置期生产：{}", blocker);

		if (usesMavenizer()) {
			prePatchJarProducer = registerPrePatchTask();
		}

		// 无锁快路径：产物齐备且补丁版本最新时，本次仅做内存配置，不触碰共享缓存，不取锁
		if (!needsWork()) {
			this.dirty = false;
			return;
		}

		withPatchedLock(this::providePatched);
	}

	/**
	 * {@return 不能把三级 patched 产物的生产投影成执行期任务的原因；可以投影时为 {@code null}}.
	 *
	 * <p>与 {@code MinecraftProvider.projectionBlocker()} 同一风格：每一条都是一个「本形态的生产链不在本阶段
	 * 定义之内」或「配置期有确定的读者会按路径读这些 jar」的形态，命中即整段退回配置期路径
	 * （那条路径与改造前逐字一致），并留下 lifecycle 留痕。
	 *
	 * <ul>
	 *   <li><b>legacy Forge（1.8-1.16）</b>：{@link MinecraftLegacyPatchedProvider#provide()} 覆写了本方法，
	 *       走的是 FG2 工作目录下的一组产物，形态与 modern 不同。</li>
	 *   <li><b>disableObfuscation / unobfuscated Forge</b>：pre-patch jar 走
	 *       {@code createUnobfuscatedPrePatchJar}（userdev 合并分支），且 {@code remapJar} 也在配置期生产
	 *       最终 jar 并读 at-patched jar——两处都不在本次迁移的形态之内。</li>
	 *   <li><b>mappings 迁移留在配置期</b>：{@code ForgeMigratedMappingConfiguration.manipulateMappings}
	 *       在配置期生产迁移产物时，要按路径读 intermediate jar
	 *       （见 {@link #getOrProduceMinecraftPatchedIntermediateJar()}）。投影后那件产物只在执行期落位，
	 *       配置期读者会拿到不存在的文件——因此该情形整批回退。迁移本身同样任务化之后，剩下的阻碍
	 *       只有「配置期有别的读者要真读迁移产物」，判据统一放在
	 *       {@link ForgeMigratedMappingConfiguration#mappingsMigrationProjectionBlocker}。</li>
	 * </ul>
	 */
	protected @Nullable String provideProjectionBlocker() {
		// disableObfuscation / unobfuscated 原本在这里整批回退，理由是「pre-patch jar 走 userdev 合并分支，
		// 且 remapJar 也在配置期生产最终 jar」。前半句已不成立：pre-patch jar 与常规形态共用同一个
		// McpExecutor，只是排的是 merge / preProcessJar 而不是 rename（见
		// createUnobfuscatedPrePatchJarMcpOptions），已可由 BRANCH_MCP_UNOBFUSCATED 在任务里跑。
		// 后半句（最终 patched jar 的合并）另有判据与留痕，见 remapJarProjectionBlocker。

		final String migrationBlocker = ForgeMigratedMappingConfiguration.mappingsMigrationProjectionBlocker(getExtension());

		if (migrationBlocker != null) {
			return "mappings 迁移留在配置期（" + migrationBlocker + "），它要按路径读 patched 中间产物";
		}

		return null;
	}

	/**
	 * 把三级 patched 产物的生产登记成执行期任务.
	 *
	 * <p>三级各一个任务、各一条产物路径，逐级以前一级的产物为输入（任务依赖逐级建立）；
	 * 产物路径一律沿用既有位置，否则消费侧（{@code getMinecraftJars()}、{@code genForgePatchedSources}）
	 * 按路径找产物的地方会全部悬空。
	 *
	 * <p>同一产物路径在本构建内只能有一个生产者，因此逐产物走
	 * {@link RemapMinecraftTaskRegistry#claim}：同一构建内两个配置相同的子项目不会各自建一个任务写同一个文件。
	 */
	private Producer registerPrePatchTask() {
		final var extension = getExtension();
		final Path prePatchJar = minecraftIntermediateJar.toAbsolutePath().normalize();

		// 分支判据与配置期 createPrePatchJar 逐字一致
		final boolean installerTools = shouldUseNeoForgeInstallerToolsToCreatePrePatchJar();
		// unobfuscated 形态排的是 merge / preProcessJar 而不是 rename（与配置期同名分支逐字一致）
		final boolean unobfuscated = getExtension().disableObfuscation();
		final String prePatchBranch = installerTools ? GenerateForgePrePatchJarTask.BRANCH_NEOFORGE_INSTALLER_TOOLS
				: unobfuscated ? GenerateForgePrePatchJarTask.BRANCH_MCP_UNOBFUSCATED
				: GenerateForgePrePatchJarTask.BRANCH_MCP_RENAME;
		// MCP 执行链的工作目录：配置期路径用随机临时目录，任务路径用项目内的固定目录，两者都「本次执行即用即删」
		final Path mcpWorkDir = project.getLayout().getBuildDirectory()
				.dir("tmp/" + GenerateForgePrePatchJarTask.NAME + "/" + type.id).get().getAsFile().toPath();

		return RemapMinecraftTaskRegistry.claim(project, prePatchJar, Map.of(
				"stage", "forge-pre-patch-jar",
				"mcVersion", extension.getMinecraftProvider().minecraftVersion(),
				"forgeVersion", extension.getForgeProvider().getVersion().getCombined(),
				"type", type.id,
				"branch", prePatchBranch
		), () -> project.getTasks().register(GenerateForgePrePatchJarTask.NAME, GenerateForgePrePatchJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Produces the pre-patch Minecraft jar for %s".formatted(
					extension.getMinecraftProvider().minecraftVersion()));
			task.getOutputJar().set(prePatchJar.toFile());
			task.getOffline().set(project.getGradle().getStartParameter().isOffline());
			task.getManualRefreshDeps().set(extension.manualRefreshDeps());
			task.getMcpWorkDir().set(mcpWorkDir.toFile());
			// 复用判据的身份与输入（见 GenerateForgePrePatchJarTask#generate）：配置期算成纯值，
			// 执行期只做比对。installer tools 的声明只组装一次：它既进任务的 @Nested 输入，
			// 也进复用判据的输入指纹（工具 classpath 换了，产物就可能变）
			final ForgeExternalToolService.Options installerToolsOptions = installerTools
					? createNeoForgeInstallerTools() : null;
			task.getReuseIdentity().set(prePatchReuseIdentity(extension, installerTools, unobfuscated));
			task.getReuseInputs().from(prePatchReuseInputs(installerToolsOptions, unobfuscated));

			if (installerTools) {
				task.getBranch().set(GenerateForgePrePatchJarTask.BRANCH_NEOFORGE_INSTALLER_TOOLS);
				task.getInstallerTools().set(installerToolsOptions);
				task.getClientMappingsUrl().set(Objects.requireNonNull(
						minecraftProvider.getVersionInfo().download("client_mappings"),
						"client_mappings download is not available for this Minecraft version").url());
			} else {
				task.getBranch().set(prePatchBranch);

				try {
					// 两条 MCP 分支只差排队哪一步：rename vs merge/preProcessJar
					task.getMcpOptions().set(unobfuscated
							? createUnobfuscatedPrePatchJarMcpOptions(mcpWorkDir)
							: createPrePatchJarMcpOptions(mcpWorkDir));
				} catch (IOException e) {
					throw new UncheckedIOException("无法声明 pre-patch jar 的 MCP 执行器选项", e);
				}
			}

			// 本任务的输入是 vanilla jar（两条分支都读它，见 wireVanillaJarDependencies）
			wireVanillaJarDependencies(task);
		}));
	}

	private void registerPatchedChainTasks() {
		final var extension = getExtension();
		final Path prePatchJar = minecraftIntermediateJar.toAbsolutePath().normalize();
		final Path patchedIntermediate = minecraftPatchedIntermediateJar.toAbsolutePath().normalize();
		final Path atPatched = minecraftPatchedIntermediateAtJar.toAbsolutePath().normalize();
		final Producer prePatchProducer = registerPrePatchTask();
		this.prePatchJarProducer = prePatchProducer;

		if (usesMavenizer()) {
			final Producer producer = RemapMinecraftTaskRegistry.claim(project, patchedIntermediate, Map.of(
					"stage", MAVENIZER_GENERATION, "mcp", MAVENIZER_MCP, "forge", extension.getForgeProvider().getVersion().getCombined()
			), () -> project.getTasks().register(GenerateForgeMavenizerJarTask.NAME, GenerateForgeMavenizerJarTask.class, task -> {
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getMavenizer().set(mavenizerOptions());
				task.getOutputJar().set(patchedIntermediate.toFile());
			}));
			minecraftProvider.registerTaskProducedArtifact(patchedIntermediate, producer);
			logger.lifecycle("Forge 的完整 SRG 产物由官方源码重编译任务承担：{}", producer.taskPath());
			return;
		}

		final Path runtimeInstaller = runtimeInstaller();
		final Producer patchedProducer = RemapMinecraftTaskRegistry.claim(project, patchedIntermediate, Map.of(
				"stage", "forge-patched-intermediate-jar",
				"runtimeInstaller", runtimeInstaller == null ? "" : runtimeInstaller.toString(),
				"mcVersion", extension.getMinecraftProvider().minecraftVersion(),
				"forgeVersion", extension.getForgeProvider().getVersion().getCombined(),
				"type", type.id
		), () -> project.getTasks().register(GenerateForgePatchedIntermediateJarTask.NAME,
				GenerateForgePatchedIntermediateJarTask.class, task -> {
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.setDescription("Produces the patched intermediate Minecraft jar for %s".formatted(
							extension.getMinecraftProvider().minecraftVersion()));
					task.getOutputJar().set(patchedIntermediate.toFile());
					task.getPrePatchJar().set(prePatchJar.toFile());
					// 补丁与工具声明都是配置期读项目模型得到的纯值/惰性声明（见 type.patches 与 createBinpatcherTool）
					task.getPatches().set(type.patches.apply(extension.getPatchProvider(), extension.getForgeUserdevProvider()).toFile());
					task.getBinpatcherTool().set(createBinpatcherTool());

					if (runtimeInstaller != null) {
						task.getRuntimeInstaller().set(runtimeInstaller.toFile());
					}
					task.getFixParameterAnnotations().set(extension.isForgeLikeAndNotOfficial() && !extension.isUnobfuscatedForge());
					dependsOnProducer(task, prePatchProducer);
				}));

		final Producer atPatchedProducer = RemapMinecraftTaskRegistry.claim(project, atPatched, Map.of(
				"stage", "forge-at-patched-jar",
				"mcVersion", extension.getMinecraftProvider().minecraftVersion(),
				"forgeVersion", extension.getForgeProvider().getVersion().getCombined(),
				"type", type.id
		), () -> project.getTasks().register(AccessTransformForgeJarTask.NAME, AccessTransformForgeJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Access transforms the patched Minecraft jar for %s".formatted(
					extension.getMinecraftProvider().minecraftVersion()));
			task.getOutputJar().set(atPatched.toFile());
			task.getPatchedIntermediateJar().set(patchedIntermediate.toFile());
			task.getForgeUserdevJar().set(getForgeUserdevJar());
			// AT 工具信息只能在配置期解析（依赖解析 + Java 工具链），故解析成纯值后声明成任务输入
			final AccessTransformerService.Tool atTool = AccessTransformerService.resolveTool(project);
			task.getAtMainClass().set(atTool.mainClass());
			task.getAtClasspath().from(atTool.classpath());
			task.getAtJavaExecutable().set(atTool.javaExecutable());
			task.getAtVerboseStdout().set(atTool.verboseStdout());
			task.getAtVerboseStderr().set(atTool.verboseStderr());

			// AT 文件的位置声明与配置期读的 UserdevConfig.ats 同源；文件本身在执行期抽到临时目录
			extension.getForgeUserdevProvider().getConfig().ats().visit(directory -> {
				task.getAtDirectory().set(directory);
				return null;
			}, paths -> {
				task.getAtPaths().set(paths);
				return null;
			});

			// AT 规则在 SRG 命名空间，而本链路的 AT 作用对象是 notch jar（见 AccessTransformerRuleTranslator）
			// 无混淆形态没有映射配置，也不需要翻译（见下方 atSourceNamespace 的分流），此时不声明映射输入
			if (!extension.disableObfuscation()) {
				task.getMappings().set(mappingsFile(project).toFile());
				// 映射文件在投影形态下由迁移任务在执行期产出（manipulateForgeMappings），
				// 而上面只按路径声明成 @InputFile——不带任务依赖。缺这条接线时 Gradle 会直接拒绝构建：
				// 「accessTransformForgeJar uses this output of task manipulateForgeMappings without
				// declaring an explicit or implicit dependency」。接线方式与同类消费方一致（见下方
				// registerPatchedJarTask 里 migrateForgeJar 那处）。
				extension.getMappingConfiguration().addMappingsProducerDependency(task);
			}

			// AT 规则是否需要翻译，取决于「规则的书写命名空间」与「AT 作用对象的命名空间」是否一致。
			// 只有 legacy Forge 不一致：userdev 的 AT 用 srg 成员名，而作用对象是 official（notch）jar，
			// 不翻译的话 AT 工具按字面量一条都匹配不上，且不报错（AT 静默失效）。
			// 其余车道两者一致，规则原样交给 AT 工具即可——强行翻译反而会因映射表缺失而失败
			// （实测 1.20.1 有 18 条规则如 Gui.screenWidth 报「mojang → official 找不到」）。
			if (getExtension().isLegacyForge()) {
				task.getAtSourceNamespace().set(intermediateNamespace);
				task.getAtTargetNamespace().set(MappingsNamespace.OFFICIAL.toString());
			}

			dependsOnProducer(task, patchedProducer);
		}));

		// at-patched jar 的产出方必须让 registerPatchedJarTask 找得到：那里的
		// minecraftProvider.addProducerDependency(task, atPatched) 正是从这张表里取任务依赖
		minecraftProvider.registerTaskProducedArtifact(atPatched, atPatchedProducer);
		// 中间产物的产出方同样要登记：mappings 迁移任务把它声明成 @InputFile（迁移器缓存未命中时读它），
		// 只按路径声明输入不会带任务依赖，Gradle 的隐式依赖校验会直接拒绝构建
		minecraftProvider.registerTaskProducedArtifact(patchedIntermediate, patchedProducer);

		logger.lifecycle("Forge 的 patched 三级产物由执行期任务承担：{}",
				List.of(prePatchProducer.taskPath(), patchedProducer.taskPath(), atPatchedProducer.taskPath()));
	}

	/**
	 * {@return pre-patch jar 的产出任务路径；未投影（配置期生产）时为 {@code null}}.
	 *
	 * <p>供「按裸路径声明该产物为输入」的消费方（{@code genForgePatchedSources}）建任务依赖：
	 * 它注册得比 {@link #provide()} 更早，故只能把这次查询做成惰性 provider。
	 */
	public @Nullable String getPrePatchJarProducerTaskPath() {
		return prePatchJarProducer != null ? prePatchJarProducer.taskPath() : null;
	}

	/**
	 * 把「本任务读 vanilla jar」表达成任务依赖.
	 *
	 * <p>本形态的输入是 vanilla jar：MCP {@code rename} 步经 {@code downloadClient}/{@code downloadServer}
	 * 读它们，NeoForge installer tools 分支的 {@code --input} 也指向它们。但它们在任务里只以**路径**形式
	 * 出现（MCP 执行器选项、工具参数模板），Gradle 无从据此建依赖：vanilla jar 一旦改由执行期任务产出
	 * （见 {@code MinecraftProvider.provideMinecraftJars()}），冷缓存下本任务会在输入落位前开跑。
	 *
	 * <p>未投影时（legacy / unobfuscated）这些产物早已在配置期落盘，登记表里查不到产出方，本方法为空操作。
	 *
	 * <p>逐形态取用的 jar 与 {@link #createNeoForgeInstallerTools()} 的输入参数、以及 MCP 配置里
	 * {@code joined}/{@code client}/{@code server} 三个环境的步进集合逐项对应：
	 * {@code MERGED} 两个都读，单边形态只读自己那一边。
	 */
	private void wireVanillaJarDependencies(Task task) {
		if (type == Type.MERGED || type == Type.CLIENT_ONLY) {
			minecraftProvider.addProducerDependency(task, minecraftProvider.getMinecraftClientJar().toPath());
		}

		if (type == Type.MERGED || type == Type.SERVER_ONLY) {
			minecraftProvider.addProducerDependency(task, minecraftProvider.getMinecraftServerJar().toPath());
		}
	}

	/**
	 * {@return pre-patch jar 的复用身份前缀（决定「该产出什么」的那些纯值）}.
	 *
	 * <p>它只用于「产物已就绪就跳过整条 MCP 链」的判据（见 {@code GenerateForgePrePatchJarTask}），不参与产物
	 * 字节：与产物内容有关的细节（步进集合、工具版本）由 {@link #prePatchReuseInputs} 的文件指纹覆盖。
	 *
	 * <p>逐项都是「换了就必须重建」的量：
	 * <ul>
	 *   <li>{@code patchVersion}：Loom 链路算法版本，与 patched jar 的 manifest 标记同源；</li>
	 *   <li>{@code branch}：rename / merge-preProcessJar / installer tools 三条分支产物不同；</li>
	 *   <li>{@code platform} / {@code mcVersion} / {@code forgeVersion}：换了就是另一份产物（产物路径本身也按
	 *       forge 版本分段，这里是第二道锁）；</li>
	 *   <li>{@code type} / {@code namespace}：side 与中间命名空间写进了产物文件名与重映射目标。</li>
	 * </ul>
	 *
	 * <p>MCPConfig 的版本标识不必单列：它的落位路径 {@code <mc>/mcp/<version>/mcp.zip} 本身就是输入指纹的
	 * 一部分（{@link #prePatchReuseInputs}）。
	 */
	private String prePatchReuseIdentity(LoomGradleExtension extension, boolean installerTools, boolean unobfuscated) {
		return "patchVersion=" + CURRENT_LOOM_PATCH_VERSION
				+ "|branch=" + (installerTools ? GenerateForgePrePatchJarTask.BRANCH_NEOFORGE_INSTALLER_TOOLS
						: unobfuscated ? GenerateForgePrePatchJarTask.BRANCH_MCP_UNOBFUSCATED
						: GenerateForgePrePatchJarTask.BRANCH_MCP_RENAME)
				+ "|platform=" + extension.getPlatform().get().id()
				+ "|mcVersion=" + extension.getMinecraftProvider().minecraftVersion()
				+ "|forgeVersion=" + extension.getForgeProvider().getVersion().getCombined()
				+ "|type=" + type.id
				+ "|namespace=" + intermediateNamespace;
	}

	/**
	 * {@return pre-patch jar 的复用输入文件（内容决定产物内容的那些）}.
	 *
	 * <p>与两条分支实际读入的文件集合逐项对应：
	 * <ul>
	 *   <li><b>共同</b>：MCPConfig 的 {@code mcp.zip} 与解包出的 {@code config.json}——后者决定步进集合、工具坐标、
	 *       函数参数，是「MCPConfig 换了一份」最直接的证据（它是几 KB 的纯文本，指纹会取内容摘要）；</li>
	 *   <li><b>MCP 分支</b>：解包目录里那份静态映射（{@code config.json} 的 {@code data.mappings}，{@code mergeMappings} 步的
	 *       输入），以及本形态要读的 vanilla jar（{@code downloadClient} / {@code downloadServer} 的输出）；</li>
	 *   <li><b>unobfuscated 分支</b>：只有 vanilla jar（{@code merge} / {@code preProcessJar} 不读映射）；</li>
	 *   <li><b>installer tools 分支</b>：工具 classpath 与 vanilla jar（两者都定死在参数模板里）。</li>
	 * </ul>
	 *
	 * <p>注意这里取的是**链真正读入的**文件，不是 loom 自己的映射产物（{@code rename} 步的 {@code --map} 指向 MCP
	 * 链自己算出的 {@code mergeMappings} 输出，其上游就是上面那份静态映射）。
	 *
	 * @param installerToolsOptions installer tools 分支的工具声明；其余分支为 {@code null}
	 * @param unobfuscated 是否无混淆形态（该形态没有映射配置，不能读 {@code getMappingConfiguration()}）
	 */
	private List<Object> prePatchReuseInputs(ForgeExternalToolService.@Nullable Options installerToolsOptions,
			boolean unobfuscated) {
		final List<Object> inputs = new ArrayList<>();
		final McpConfigProvider mcpConfigProvider = getExtension().getMcpConfigProvider();
		inputs.add(mcpConfigProvider.getMcp());
		inputs.add(mcpConfigProvider.getUnpackedZip().resolve("config.json"));

		if (!unobfuscated && mcpConfigProvider.hasMappings()) {
			inputs.add(mcpConfigProvider.getMappings());
		}

		if (type == Type.MERGED || type == Type.CLIENT_ONLY) {
			inputs.add(minecraftProvider.getMinecraftClientJar());
		}

		if (type == Type.MERGED || type == Type.SERVER_ONLY) {
			inputs.add(minecraftProvider.getMinecraftServerJar());
		}

		if (installerToolsOptions != null) {
			// 工具 classpath 是惰性文件集合（配置期尚未解析），直接作为输入交给任务，执行期取值
			inputs.add(installerToolsOptions.getClasspath());
		}

		return inputs;
	}

	/** 把上一级产物的产出方登记成任务依赖；未建任务（已有其它生产者）时什么也不做. */
	private static void dependsOnProducer(Task task, Producer producer) {
		if (producer.taskPath() != null) {
			task.dependsOn(producer.taskPath());
		}
	}

	/**
	 * {@return 是否需要重建 patched jar}.
	 *
	 * <p>产物缺失或 Loom 补丁版本过期即需要工作。manifest 读取遇到损坏文件（多进程并发下
	 * 的半截产物）时按需要工作处理，进入锁内走完整判定。
	 *
	 * <p>参与判定的产物集合由 {@link #getGlobalCaches()} 给出，它只含本配置下确实会生成的产物：
	 * server-only 不含 client-extra，否则判定恒为「需要工作」，无锁快路径永远走不到。
	 */
	private boolean needsWork() {
		// 无锁读路径：除存在性外还要拒绝空 zip／截断文件等半成品（其它进程可能正在就地重建共享缓存）
		if (getExtension().refreshDeps() || Stream.of(getGlobalCaches()).anyMatch(jar -> !isReusableProductionJar(jar))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(minecraftPatchedJar);
		} catch (IOException e) {
			return true;
		}
	}

	private boolean isReusableProductionJar(Path jar) {
		if (jar.equals(minecraftPatchedIntermediateJar) && usesMavenizer()) {
			return ForgeMavenizerService.isReusable(jar, getExtension().getForgeProvider().getVersion().getCombined(),
					minecraftProvider.minecraftVersion(), mavenizerMcpVersion());
		}

		return isReusableJar(jar);
	}

	private Void providePatched() throws Exception {
		// 锁内二次确认：等锁期间其它进程可能已完成生产。本方法不删除任何共享产物，
		// 「整链重建」由 checkCache 的返回值表达，缺失或损坏的产物则由下面的可复用性判定按件补齐
		final boolean forceRebuild = checkCache();
		this.dirty = forceRebuild;

		if (usesMavenizer()) {
			if (getExtension().refreshDeps() || !isReusableProductionJar(minecraftPatchedIntermediateJar)) {
				produceMavenizerJar();
				this.dirty = true;
			}

			this.dirty |= !isReusableJar(minecraftClientExtra);
			return null;
		}

		if (forceRebuild || !isReusableJar(minecraftIntermediateJar)) {
			this.dirty = true;
			// 原子落位：临时文件写完才替换最终产物，锁外读方不会看到半截 jar
			publishAtomically(minecraftIntermediateJar, this::createPrePatchJar);
		}

		if (dirty || !isReusableJar(minecraftPatchedIntermediateJar)) {
			this.dirty = true;
			publishAtomically(minecraftPatchedIntermediateJar, this::producePatchedIntermediate);
		}

		if (dirty || !isReusableJar(minecraftPatchedIntermediateAtJar)) {
			this.dirty = true;
			accessTransformForge();
		}

		// client-extra 在 remapJar 阶段生成，本阶段不重建它，但必须在这里置位重建信号：
		// 否则会出现「needsWork 判定需要工作、providePatched 却查不出任何需要重建的件」的死角，
		// remapJar 因 dirty=false 直接返回，损坏的 client-extra 既不被修复、又被注册进 FORGE_EXTRA
		// （见 registerExtraDependencies），此后每次构建都重复这个空转。
		// server-only 不生成 client-extra，不能参与判定，否则 dirty 恒为真、每轮都整链重建
		if (providesClientJar() && !isReusableJar(minecraftClientExtra)) {
			this.dirty = true;
		}

		return null;
	}

	/**
	 * {@return 打补丁后的中间 Minecraft jar；产物不可复用时先按件补齐}.
	 *
	 * <p>该 jar 位于跨 daemon 共享的 forge 缓存目录：其它工作树的构建（尤其是仍在删除整组产物的
	 * 旧版本 loom）可能让它在被读取前一刻消失，直接读取会以
	 * {@link java.nio.file.NoSuchFileException} 打断整个构建。
	 *
	 * <p>判定与补齐都基于「可复用」（存在、能作为 zip/jar 打开且至少含一个条目，见
	 * {@link #isReusableJar(Path)}）而非「存在」：共享缓存里可能出现 22 字节空 zip 或截断文件，
	 * 只判存在性会把这类半成品原样交给调用方——它能以只读 jar 文件系统打开但读不出任何 class，
	 * 下游（FieldMappingsMigrator / MethodInheritanceMappingsMigrator）会把「读不出内容」当成
	 * 「没有内容」，从而把空结果写进映射缓存，形成静默错误映射。
	 *
	 * <p>补齐走 {@link #produceIntermediateJarIfMissing()}：产物齐备时它是无锁快路径，不可复用时在
	 * {@code forge-patched} 跨进程锁内只重建缺失件，并以原子方式落位。
	 */
	public Path getOrProduceMinecraftPatchedIntermediateJar() {
		final Path jar = getMinecraftPatchedIntermediateJar();

		if (isReusableProductionJar(jar)) {
			return jar;
		}

		logger.lifecycle(":共享缓存中的中间产物 {} 不可复用（缺失或损坏），先按件补齐（其它进程可能正在重建该缓存）", jar);

		try {
			produceIntermediateJarIfMissing();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException("补齐共享缓存中的 patched 中间产物失败: " + jar, e);
		}

		// 补齐后必须再校验一次：仍然不可复用说明该产物这次没能被完整生产出来
		// （例如锁内判定把它当作可复用而跳过重建，或被外部进程在补齐后再次写坏）。
		// 这里抛异常而不是返回坏路径：坏路径会让下游把「读不出内容」当成「没有内容」，静默产出错误映射
		if (!isReusableProductionJar(jar)) {
			throw new UncheckedIOException(new IOException(
					"补齐后共享缓存中的 patched 中间产物仍不可复用（无法作为 zip/jar 打开，或没有任何条目）: " + jar));
		}

		return jar;
	}

	/**
	 * 确保 {@link #getMinecraftPatchedIntermediateJar()} 指向的产物已存在.
	 *
	 * <p>默认实现调用 {@link #provide()}；legacy 链的产物在 {@link #remapJar(ServiceFactory)}
	 * 阶段生成（{@code provide()} 只做判定），由子类覆盖补齐。
	 *
	 * <p>任务路径下不能走 {@code provide()}：那条路径只做接线、不写产物。本方法只被**配置期读者**调用
	 * （{@code getOrProduceMinecraftPatchedIntermediateJar} 的调用方：mappings 迁移的配置期回退路径），
	 * 而那个读者与 {@link #provideProjectionBlocker()} 用的是同一个判据——走到这里时任务路径本不该生效。
	 * 真走到这里（产物在判定之后被其它工作树删掉或写坏）时，{@code provide()} 只做接线，
	 * 随后 {@code getOrProduceMinecraftPatchedIntermediateJar} 的可复用性复检会以明确异常报出，
	 * 而不是把不存在的路径交给迁移器去读。
	 */
	protected void produceIntermediateJarIfMissing() throws Exception {
		if (usesMavenizer()) {
			produceMavenizerJar();
		} else {
			provide();
		}
	}

	private void produceMavenizerJar() throws IOException {
		try (var factory = new ScopedServiceFactory()) {
			final ForgeMavenizerService mavenizer = factory.get(mavenizerOptions());
			mavenizer.produce(minecraftPatchedIntermediateJar);
		}
	}

	/**
	 * {@return 不能把最终 patched jar 的生产投影成执行期任务的原因；可以投影时为 {@code null}}.
	 *
	 * <p>与 {@code MinecraftProvider.projectionBlocker()} 同一风格：每一条都是一个「本形态的生产链不在本阶段
	 * 定义之内」的形态，命中即整段退回配置期路径（那条路径与改造前逐字一致），并留下 lifecycle 留痕。
	 *
	 * <ul>
	 *   <li><b>legacy Forge（1.8-1.16）</b>：它的产物是一组 FG2 工作目录下的 jar
	 *       （{@code client/server/merged-patched.jar}、{@code forge.jar}），且 {@code remapJar} 是与父类
	 *       不同的整链实现（见 {@code MinecraftLegacyPatchedProvider.remapJar}），形态与 modern 不同。</li>
	 * </ul>
	 */
	protected @Nullable String remapJarProjectionBlocker() {
		if (getExtension().isLegacyForge()) {
			return "legacy Forge（1.8-1.16）的 patched jar 是 FG2 工作目录下的一组产物，生产链与 modern 不同";
		}

		// disableObfuscation / unobfuscated 原本在这里整段回退，理由是「最终 jar 走
		// mergeUnobfuscatedPatchedJar（把 userdev jar 合进 AT 后的 jar）且映射树为空」。
		// 两者现在都已由 GenerateForgePatchedJarTask 承担：它的 getUnobfuscatedForge() 就是切换这条
		// 合并分支的开关（与配置期 productionOptions 同一判据），映射树为空的情形也在任务里按同一判据取值。

		return null;
	}

	public void remapJar(ServiceFactory serviceFactory) throws Exception {
		final String blocker = remapJarProjectionBlocker();

		if (blocker == null) {
			// 任务路径：本次只做接线（登记产物与 FORGE_EXTRA 依赖），真正生产交给任务图与构建缓存
			registerPatchedJarTask();
			return;
		}

		logger.lifecycle("Forge 的 patched jar 保持配置期生产：{}", blocker);

		// 无锁快路径：provide 已判定无需重建（dirty=false）时，此处仅注册依赖（纯内存操作），不取锁
		if (!dirty) {
			registerExtraDependencies();
			return;
		}

		withPatchedLock(() -> remapPatchedJarWithDirty(serviceFactory));
	}

	/**
	 * 把最终 patched jar（与 client-extra）的生产登记成执行期任务.
	 *
	 * <p>两件产物由同一次生产写出（同一次重映射 + 同一次 client-extra 填充），故用同一个任务；
	 * server-only 配置下 client-extra 既不生成也不进 classpath（见 {@link #providesClientJar()}），
	 * 此时不声明该输出。
	 *
	 * <p>登记之后必须把产物交给 {@code MinecraftProvider} 的记录：消费侧
	 * （{@code getMinecraftJarsCollection(OFFICIAL)}、mapped provider 的重映射任务）正是从这里拿到
	 * 「我的输入由哪个任务产出」的任务依赖。
	 */
	/**
	 * {@return AT 规则翻译要用的映射文件}
	 *
	 * <p>与 {@code buildRemapper} / {@link #getMappingTree} 取映射用的是同一个 {@link MappingOption}，
	 * 保证「翻译 AT 用的映射」与「重映射 MC jar 用的映射」是同一份数据，不会出现两边命名空间不一致。
	 */
	private Path mappingsFile(Project project) {
		final LoomGradleExtension extension = getExtension();
		return extension.getMappingConfiguration().getMappingsPath(MappingOption.forPlatform(extension));
	}

	private void registerPatchedJarTask() {
		final Path patchedJar = minecraftPatchedJar.toAbsolutePath().normalize();
		final Path clientExtra = providesClientJar() ? minecraftClientExtra.toAbsolutePath().normalize() : null;
		final var extension = getExtension();
		// 无混淆形态没有映射配置（getMappingConfiguration() 会抛异常），而那个形态下最终 jar 走
		// mergeUnobfuscatedPatchedJar、不读映射树，故两条映射相关声明都按空处理
		final boolean unobfuscated = extension.disableObfuscation();
		final var mappingConfiguration = unobfuscated ? null : extension.getMappingConfiguration();
		// 与 buildRemapper / remapCoreMods / generateNeoForgeDistManifest 取映射树用的是同一个映射选项
		final var mappingsOptions = unobfuscated ? null
				: mappingConfiguration.getMappingsServiceOptions(project, MappingOption.forPlatform(extension));
		// 平台开关与命名空间：与 productionOptions 同一处取值，保证任务声明与配置期实现逐项同源
		final String remapNamespace = IntermediaryNamespaces.intermediary(project);
		final String coreModNamespace = extension.getProductionNamespace().get();
		final String distAttribute = type.getNeoForgeDistsAttribute();
		final File serverJar = needsServerJarForDistManifest(extension) ? getServerJarForDistManifest() : null;
		final Path lockRoot = ForgeProvider.getForgeCache(project).resolve(Constants.Cache.LOCKS_DIR);
		final String lockKey = patchedLockKey();

		final Producer producer = RemapMinecraftTaskRegistry.claim(project, patchedJar, Map.of(
				"stage", "forge-patched-jar",
				"mcVersion", extension.getMinecraftProvider().minecraftVersion(),
				"forgeVersion", extension.getForgeProvider().getVersion().getCombined(),
				"type", type.id,
				"clientExtra", Boolean.toString(providesClientJar())
		), () -> project.getTasks().register(GenerateForgePatchedJarTask.NAME, GenerateForgePatchedJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Produces the patched Minecraft jar for %s".formatted(
					extension.getMinecraftProvider().minecraftVersion()));
			task.getAtPatchedJar().set(minecraftPatchedIntermediateAtJar.toFile());
			task.getCompleteForgeInput().set(usesMavenizer());

			if (!usesMavenizer()) {
				task.getForgeJar().set(getForgeJar());
				task.getForgeUserdevJar().set(getForgeUserdevJar());
			}

			if (mappingsOptions != null) {
				task.getMappingsServiceOptions().set(mappingsOptions);
				// 映射树取自迁移产物：投影时它由迁移任务产出，按路径声明输入不带任务依赖，必须显式接线
				mappingConfiguration.addMappingsProducerDependency(task);
			}

			task.getClientExtraEnabled().set(providesClientJar());
			task.getPatchVersion().set(CURRENT_LOOM_PATCH_VERSION);
			task.getNeoForge().set(extension.isNeoForge());
			task.getForge().set(extension.isForge());
			task.getUnobfuscatedForge().set(extension.isUnobfuscatedForge());
			task.getRuntimeMojang().set(extension.getForgeProvider().usesMojangAtRuntime());
			task.getMerged().set(type == Type.MERGED);
			task.getRemapNamespace().set(remapNamespace);
			task.getCoreModNamespace().set(coreModNamespace);
			task.getDistAttribute().set(distAttribute);
			task.getRefreshDeps().set(extension.refreshDeps());
			task.getLockKey().set(lockKey);
			task.getLockRoot().set(lockRoot.toAbsolutePath().toString());
			task.getOutputJar().set(patchedJar.toFile());

			if (clientExtra != null) {
				task.getClientJar().set(minecraftProvider.getMinecraftClientJar());
				task.getClientExtraJar().set(clientExtra.toFile());

				if (serverJar != null) {
					// NeoForge 的 dist 清单要按 client/server 分边（见 generateNeoForgeDistManifest）；
					// 服务端侧取的是与生产同一判据的那一件（bootstrap 版本是抽取产物，否则是下载下来的 server jar）
					task.getServerJar().set(serverJar);
					minecraftProvider.addProducerDependency(task, serverJar.toPath());
				}

				// client-extra 读 client jar：投影后它由执行期任务产出，按路径声明输入不带任务依赖
				minecraftProvider.addProducerDependency(task, minecraftProvider.getMinecraftClientJar().toPath());
			}

			// at-patched jar 由本链上一阶段（配置期 provide / 或任务化后的 intermediate 任务）产出：
			// 未迁移时它已落盘，无需依赖；迁移后由产出方另行登记，这里按路径声明即可
			minecraftProvider.addProducerDependency(task, minecraftPatchedIntermediateAtJar);
		}));

		minecraftProvider.registerTaskProducedArtifact(patchedJar, producer);
		registerExtraDependencies(producer.taskPath());
	}

	private Void remapPatchedJarWithDirty(ServiceFactory serviceFactory) throws Exception {
		// 锁内二次确认：等锁期间其它进程可能已完成生产（dirty 为幂等判定）；本方法不删除共享产物
		// dirty 也可能只表示「client-extra 需要重建」（见 providePatched）：此时最终 jar 会被幂等地重发一次，
		// 而 client-extra 的修复不会被跳过
		if (dirty) {
			final ProductionOptions options = productionOptions(serviceFactory);
			publishAtomically(minecraftPatchedJar, output -> runProduction(options, output, null, logger::lifecycle));

			if (providesClientJar()) {
				publishAtomically(minecraftClientExtra, output -> runProduction(options, null, output, logger::lifecycle));
			}
		}

		registerExtraDependencies();
		return null;
	}

	/**
	 * 一次最终产物生产所需的全部输入与平台开关.
	 *
	 * <p>逐项对应执行期任务声明的输入，也逐项对应配置期路径自己持有的状态：两条路径都构造这一个对象，
	 * 生产实现只认它。这样「任务声明的输入」与「生产真正读入的输入」不可能不一致——否则 up-to-date
	 * 判定就是假的（改了输入却不重建），而等价性对照也验不出「接线接错了哪个输入」。
	 *
	 * <p>它必须**完全不依赖 Gradle 项目模型**（只有路径、布尔与字符串）：任务对象会被配置缓存序列化，
	 * 任何指向 {@code Project}/{@code SourceSet} 的引用都会让整个 Forge 工程无法使用配置缓存。
	 * 平台开关因此在这里显式取值，而不是在执行期回读 extension。
	 *
	 * @param atPatchedJar 打过补丁并做过 access transform 的中间 Minecraft jar（重映射的输入）
	 * @param forgeJar Forge 的 universal jar
	 * @param forgeUserdevJar Forge 的 userdev jar
	 * @param clientJar client jar；不产出 client-extra 时为 {@code null}
	 * @param serverJar 服务端侧 jar（bootstrap 版本是抽取产物，否则是下载下来的 server jar）；
	 *         只有 NeoForge 的 MERGED dist 清单会读它，其余情形为 {@code null}
	 * @param mappings 映射树；unobfuscated Forge 下为 {@code null}（那时没有映射配置）
	 * @param clientExtra 本次是否产出 client-extra
	 * @param neoForge 是否 NeoForge（决定是否挂 mixin 扩展、是否写 dist 清单）
	 * @param forge 是否 Forge（决定 client-extra 是否写空 MANIFEST）
	 * @param unobfuscatedForge 是否 unobfuscated Forge（最终 jar 走 userdev 合并分支、dist 清单用空映射树）
	 * @param runtimeMojang 是否在运行时使用 mojang 映射（决定 coremod 重映射的方向）
	 * @param merged 是否 MERGED 形态（决定 dist 清单是否按 client/server 分边）
	 * @param remapNamespace 重映射的源命名空间（Forge 为 {@code srg}、NeoForge 为 {@code mojang}）
	 * @param coreModNamespace coremod 重映射使用的源命名空间（生产命名空间）
	 * @param distAttribute 写进 dist 清单的 {@code Minecraft-Dists} 取值
	 */
	public record ProductionOptions(
			Path atPatchedJar,
			@Nullable Path forgeJar,
			@Nullable Path forgeUserdevJar,
			@Nullable Path clientJar,
			@Nullable Path serverJar,
			@Nullable MemoryMappingTree mappings,
			boolean clientExtra,
			boolean neoForge,
			boolean forge,
			boolean unobfuscatedForge,
			boolean runtimeMojang,
			boolean merged,
			String remapNamespace,
			String coreModNamespace,
			String distAttribute) {
		public ProductionOptions {
			if ((forgeJar == null) != (forgeUserdevJar == null) || forgeJar == null && (!forge || unobfuscatedForge)) {
				throw new IllegalArgumentException("最终重映射需要完整 Forge 输入，或成对提供 universal 和 userdev");
			}
		}

		public boolean completeForgeInput() {
			return forgeJar == null;
		}
	}

	/**
	 * {@return 本 provider 当前状态对应的生产输入与平台开关}.
	 *
	 * <p>任务接线侧同样用它：任务声明的每一个输入都取自这里，因此两侧的输入逐项同源。
	 * 唯一例外是映射树——任务在执行期从它自己的 {@code @Nested} 映射配置取（见 {@code GenerateForgePatchedJarTask}），
	 * 与这里的 {@link #getMappingTree(ServiceFactory)} 用的是同一个映射选项。
	 */
	public ProductionOptions productionOptions(ServiceFactory serviceFactory) {
		final var extension = getExtension();
		final boolean clientExtra = providesClientJar();
		// unobfuscated Forge 不走映射配置（setupMinecraft 不为它创建 MappingConfiguration）
		final MemoryMappingTree mappings = extension.isUnobfuscatedForge() ? null : getMappingTree(serviceFactory);

		return new ProductionOptions(
				minecraftPatchedIntermediateAtJar,
				usesMavenizer() ? null : getForgeJar().toPath(),
				usesMavenizer() ? null : getForgeUserdevJar().toPath(),
				clientExtra ? minecraftProvider.getMinecraftClientJar().toPath() : null,
				needsServerJarForDistManifest(extension) ? getServerJarForDistManifest().toPath() : null,
				mappings,
				clientExtra,
				extension.isNeoForge(),
				extension.isForge(),
				extension.isUnobfuscatedForge(),
				extension.getForgeProvider().usesMojangAtRuntime(),
				type == Type.MERGED,
				IntermediaryNamespaces.intermediary(project),
				extension.getProductionNamespace().get(),
				type.getNeoForgeDistsAttribute());
	}

	/**
	 * {@return 本次生产是否会读服务端侧 jar}.
	 *
	 * <p>只有 NeoForge 的 MERGED dist 清单会读它（见 {@link #generateNeoForgeDistManifest}）。
	 * 判定必须与那一处逐字对应：**多声明**会让任务把一件生产从不读的文件当输入，1.17 及更早的
	 * 非 bundle 版本上那件文件根本不存在，任务会以「输入文件不存在」直接失败；**少声明**则会让
	 * 改了服务端 jar 却不重建产物。
	 */
	private boolean needsServerJarForDistManifest(LoomGradleExtension extension) {
		return providesClientJar() && type == Type.MERGED && extension.isNeoForge();
	}

	/**
	 * {@return NeoForge dist 清单用于分边的服务端 jar}.
	 *
	 * <p>判据与改造前 {@code generateNeoForgeDistManifest} 里那一处逐字一致：bootstrap 版本的抽取产物
	 * 优先，缺失时退回下载下来的 server jar。
	 */
	private File getServerJarForDistManifest() {
		return Objects.requireNonNullElse(minecraftProvider.getMinecraftExtractedServerJar(), minecraftProvider.getMinecraftServerJar());
	}

	/**
	 * 把最终 patched jar（与 client-extra）生产到给定路径.
	 *
	 * <p>配置期路径（{@link #remapJar(ServiceFactory)}）与执行期任务
	 * （{@link GenerateForgePatchedJarTask}）共用这一处实现，因此两条路径的产物逐字节一致——
	 * 同一次重映射、同一批输入，不存在「两套实现各自近似」的分叉。
	 *
	 * <p>本方法不做任何「是否需要生产」的判定，也不落位：判定由调用方负责（配置期看 {@code dirty}，
	 * 任务侧看 {@code needsFinalJarWork}），落位一律由 {@code AtomicFiles} 的原子发布完成。
	 *
	 * <p>静态：任务对象要能被配置缓存序列化，因此生产不能经由 provider 实例（见 {@link ProductionOptions}）。
	 *
	 * @param options 生产输入与平台开关
	 * @param patchedJarOutput 最终 patched jar 的落位路径；为 {@code null} 表示本次不产出它
	 * @param clientExtraOutput client-extra 的落位路径；为 {@code null} 表示本次不产出它
	 * @param lifecycle lifecycle 日志出口（配置期是项目 logger，执行期是任务 logger）
	 */
	public static void runProduction(ProductionOptions options, @Nullable Path patchedJarOutput, @Nullable Path clientExtraOutput,
			Consumer<String> lifecycle) throws Exception {
		if (patchedJarOutput != null) {
			if (options.unobfuscatedForge()) {
				mergeUnobfuscatedPatchedJar(options, patchedJarOutput, lifecycle);
			} else {
				remapPatchedJar(options, patchedJarOutput, lifecycle);
			}
		}

		if (clientExtraOutput != null) {
			fillClientExtraJar(options, clientExtraOutput);
		}
	}

	/**
	 * 把最终 patched jar（与 client-extra）生产到**指定路径**，不触碰共享缓存路径、不取跨进程锁.
	 *
	 * <p>供电线验证使用：它让「配置期实现」可以被独立调用一次作为对照，对照产物落在独立文件上，
	 * 因此被测产物的路径上只有被测任务一个写入者——不会出现「配置期先写一份、任务再覆盖」这类别名，
	 * 否则「两者一致」可以由「读到了同一份文件」伪造出来。
	 *
	 * @param options 生产输入（通常取自 {@link #productionOptions(ServiceFactory)}）
	 * @param patchedJarOutput 最终 patched jar 的落位路径
	 * @param clientExtraOutput client-extra 的落位路径；为 {@code null} 表示不产出它
	 * @param lifecycle lifecycle 日志出口
	 */
	public static void produceFinalJarsInto(ProductionOptions options, Path patchedJarOutput, @Nullable Path clientExtraOutput,
			Consumer<String> lifecycle) throws Exception {
		runProduction(options, patchedJarOutput, clientExtraOutput, lifecycle);
	}

	/**
	 * 执行期任务的生产入口：按需把最终 patched jar 与 client-extra 原子落位到任务声明的输出路径.
	 *
	 * <p>「是否需要生产」的判据与配置期路径同源（见 {@code needsFinalJarWork}），
	 * 齐备时不写任何文件：于是任务路径与配置期路径在同一份共享缓存上「写了哪些文件」完全一致。
	 * 执行时机与跨进程并发保护分别由任务图与这里取的文件锁承担。
	 *
	 * @param options 生产输入与平台开关（任务从自己声明的输入构造）
	 * @param patchedJarOutput 最终 patched jar 的落位路径（与配置期同一条路径）
	 * @param clientExtraOutput client-extra 的落位路径；本配置不产出它时为 {@code null}
	 * @param lockRoot 跨进程锁所在目录（与配置期 {@code withPatchedLock} 同一个目录）
	 * @param lockKey 跨进程锁的 key（与配置期同一个 key）
	 * @param refreshDeps 是否显式要求刷新（对应 {@code --refresh-dependencies}）
	 * @param lifecycle lifecycle 日志出口
	 */
	public static void produceFinalJarsForTask(ProductionOptions options, Path patchedJarOutput, @Nullable Path clientExtraOutput,
			Path lockRoot, String lockKey, boolean refreshDeps, Consumer<String> lifecycle) throws Exception {
		if (!needsFinalJarWork(patchedJarOutput, clientExtraOutput, refreshDeps, lifecycle)) {
			return;
		}

		withLock(lockRoot, lockKey, () -> {
			// 锁内二次确认：等锁期间其它进程可能已完成生产
			if (!needsFinalJarWork(patchedJarOutput, clientExtraOutput, refreshDeps, lifecycle)) {
				return null;
			}

			publishAtomically(patchedJarOutput, output -> runProduction(options, output, null, lifecycle));

			if (clientExtraOutput != null) {
				publishAtomically(clientExtraOutput, output -> runProduction(options, null, output, lifecycle));
			}

			return null;
		});
	}

	/**
	 * {@return 最终 patched jar（与 client-extra）是否需要重建}.
	 *
	 * <p>判据取自配置期 {@link #needsWork()} 中与最终产物相关的那些：显式刷新、产物不可复用
	 * （存在且能作为 jar 打开、至少含一个条目）、或补丁版本标记过期。
	 *
	 * <p>静态：任务侧要用它做锁内二次确认，而任务不持有 provider（见 {@link ProductionOptions}）。
	 *
	 * @param refreshDeps 是否显式要求刷新
	 * @param lifecycle lifecycle 日志出口
	 */
	private static boolean needsFinalJarWork(Path patchedJar, @Nullable Path clientExtra, boolean refreshDeps,
			@Nullable Consumer<String> lifecycle) {
		if (refreshDeps || !isReusableJar(patchedJar)
				|| (clientExtra != null && !isReusableJar(clientExtra))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(patchedJar, lifecycle);
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * 把最终产物登记进 FORGE_EXTRA 配置.
	 *
	 * @param patchedJarTaskPath patched jar（与 client-extra）的产出任务路径；配置期路径下为 {@code null}
	 */
	private void registerExtraDependencies(@Nullable String patchedJarTaskPath) {
		if (getExtension().isUnobfuscatedForge()) {
			DependencyProvider.addDependency(project, getForgeJar(), Constants.Configurations.FORGE_EXTRA);
		}

		if (providesClientJar()) {
			// 任务路径下 client-extra 由执行期产出：登记时必须带上产出任务，否则解析该配置的消费方
			// （compileClasspath / runtimeClasspath 经 FORGE_EXTRA 扩展）会读到尚不存在的文件
			DependencyProvider.addDependency(project, clientExtraDependency(patchedJarTaskPath), Constants.Configurations.FORGE_EXTRA);
		}
	}

	private void registerExtraDependencies() {
		registerExtraDependencies(null);
	}

	/** {@return client-extra 的依赖形态} 任务路径下携带产出任务，配置期路径下是裸文件. */
	private Object clientExtraDependency(@Nullable String patchedJarTaskPath) {
		if (patchedJarTaskPath == null) {
			return minecraftClientExtra;
		}

		// client-extra 与 patched jar 由同一个任务写出，故按 patched jar 的产出任务建依赖
		final ConfigurableFileCollection files = project.files(minecraftClientExtra);
		files.builtBy(patchedJarTaskPath);
		return files;
	}

	/**
	 * 在跨进程锁保护下执行 patched jar 生产.
	 *
	 * <p>产物位于跨 daemon 共享的 forge 缓存目录（不按项目隔离），且 {@code client-extra.jar} 在
	 * 各 Type 实例间共享同一路径；同一 MC+Forge 版本的多个并发构建会互相读写/删除同一批文件，
	 * 必须整段生产串行化。锁内判定均为幂等二次确认，等锁期间其它进程完成生产后直接复用。
	 */
	protected void withPatchedLock(Callable<Void> action) {
		withLock(ForgeProvider.getForgeCache(project).resolve(Constants.Cache.LOCKS_DIR), patchedLockKey(), action);
	}

	/** {@return 跨进程锁的 key} 配置期与执行期任务必须取同一个 key，否则两边的生产会互相穿插. */
	private String patchedLockKey() {
		return "forge-patched:" + getExtension().getMinecraftProvider().minecraftVersion()
				+ ":" + getExtension().getForgeProvider().getVersion().getCombined();
	}

	/**
	 * 在跨进程锁保护下执行 patched jar 生产.
	 *
	 * <p>产物位于跨 daemon 共享的 forge 缓存目录（不按项目隔离），且 {@code client-extra.jar} 在
	 * 各 Type 实例间共享同一路径；同一 MC+Forge 版本的多个并发构建会互相读写/删除同一批文件，
	 * 必须整段生产串行化。锁内判定均为幂等二次确认，等锁期间其它进程完成生产后直接复用。
	 *
	 * <p>静态：执行期任务同样要取这把锁，而任务不持有 provider。
	 */
	protected static void withLock(Path lockRoot, String lockKey, Callable<Void> action) {
		try {
			CacheEntryLock.withLock(lockRoot, lockKey, LoomCacheService.defaultTimeout(), action);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException("Could not produce patched Minecraft jars", e);
		}
	}

	/**
	 * 原子发布：在「临时文件 + 原子落位」保护下执行可能抛出任意受检异常的生产动作.
	 *
	 * <p>补丁步骤会调用外部工具（binpatcher / installer tools 等），其受检异常不限于 {@link IOException}，
	 * 无法直接作为 {@link AtomicFiles.IOConsumer}；这里统一折叠为 {@link UncheckedIOException} 后向上抛，
	 * 以保证共享缓存里永远不会出现半截产物。
	 *
	 * @param target   最终落位路径（位于跨 daemon 共享的缓存目录）
	 * @param producer 内容生产者，接收本次独占的临时文件路径
	 */
	public static void publishAtomically(Path target, ThrowingProducer producer) throws IOException {
		AtomicFiles.publish(target, tmp -> {
			try {
				producer.accept(tmp);
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new UncheckedIOException(new IOException("原子发布 " + target + " 失败", e));
			}
		});
	}

	/**
	 * 允许抛出任意受检异常的生产回调（{@link AtomicFiles.IOConsumer} 只允许 {@link IOException}）.
	 */
	@FunctionalInterface
	public interface ThrowingProducer {
		void accept(Path output) throws Exception;
	}

	/**
	 * server-only 的 jar 配置不提供 client jar，client-extra 既不生成也不加入 classpath.
	 *
	 * <p>否则 getMinecraftClientJar 会抛 "Not configured to provide client jar"。
	 */
	private boolean providesClientJar() {
		return getExtension().getMinecraftJarConfiguration().get() != MinecraftJarConfiguration.SERVER_ONLY;
	}

	private static void mergeUnobfuscatedPatchedJar(ProductionOptions options, Path output, Consumer<String> lifecycle) throws IOException {
		lifecycle.accept(":merging userdev into minecraft");
		Path forgeUserdevJar = options.forgeUserdevJar();

		// output 是本次独占的临时文件：整份写入后在它上面完成全部加工，再由调用方原子落位
		Files.copy(options.atPatchedJar(), output);

		// No manifest available to reuse here (mergetool's output has none, Forge's own jar is signed).
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(output, false)) {
			createEmptyJarManifest(fs.getPath("META-INF", "MANIFEST.MF"));
		}

		copyUserdevFiles(forgeUserdevJar, output);
		applyLoomPatchVersion(output);
	}

	private void createPrePatchJar(Path output) throws IOException {
		if (getExtension().isUnobfuscatedForge()) {
			createUnobfuscatedPrePatchJar(output);
			return;
		}

		if (shouldUseNeoForgeInstallerToolsToCreatePrePatchJar()) {
			createNeoForgeInstallerToolsPrePatchJar(output);
			return;
		}

		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			runMcpExecutor(createPrePatchJarMcpOptions(tempFiles), output, serviceFactory);
		}
	}

	/**
	 * {@return 「跑 rename 步」的 MCP 执行器声明（配置期只接线、不执行）}.
	 *
	 * <p>与改造前那段内联代码的唯一区别是「不再顺带执行」：{@link McpExecutorBuilder#build()} 返回的是惰性
	 * provider，步进逻辑选项、工具 jar 的坐标解析、以及 {@code ConstantLogic} 里 {@code downloadClient} 的
	 * 输出路径读取都推迟到它被取值时。因此本方法既能在配置期路径里调用，也能把返回值挂成任务的
	 * {@code @Nested} 输入，由任务在执行期发起同一次调用。
	 *
	 * <p>{@code tempFiles} 必须活到那次调用结束：MCP 执行链的步进缓存落在它管理的临时目录里。
	 */
	public Provider<McpExecutor.Options> createPrePatchJarMcpOptions(TempFiles tempFiles) throws IOException {
		return createPrePatchJarMcpOptions(tempFiles.directory("loom-mcp"));
	}

	/**
	 * {@return 「跑 rename 步」的 MCP 执行器声明，工作目录由调用方给出}.
	 *
	 * <p>与 {@link #createPrePatchJarMcpOptions(TempFiles)} 等价，区别只在于工作目录的来源：配置期路径用
	 * {@code TempFiles}（本次调用结束即删），执行期任务用它自己的目录（见 {@code GenerateForgePrePatchJarTask}）。
	 * 两条路径的步进集合、初始配置与工具声明都取自同一处，因此不会分叉。
	 *
	 * @param mcpCache MCP 执行链的工作目录（由调用方负责创建与清理）
	 */
	public Provider<McpExecutor.Options> createPrePatchJarMcpOptions(Path mcpCache) throws IOException {
		McpExecutorBuilder builder = createMcpExecutor(mcpCache);
		builder.enqueue("rename");
		return builder.build();
	}

	/**
	 * {@return disableObfuscation / unobfuscated 形态的 pre-patch jar 声明（排 MCP 的 merge / preProcessJar 步）}.
	 *
	 * <p>与 {@link #createPrePatchJarMcpOptions(Path)} 形状完全一致：只排队哪一步不同。
	 * 步进名字的选择与配置期 {@code createUnobfuscatedPrePatchJar} 逐字一致
	 * （{@code isNeoForge() ? "preProcessJar" : "merge"}），故两条路径的 MCP 步进集合相同。
	 *
	 * @param mcpCache MCP 执行链的工作目录（由调用方负责创建与清理）
	 */
	public Provider<McpExecutor.Options> createUnobfuscatedPrePatchJarMcpOptions(Path mcpCache) throws IOException {
		McpExecutorBuilder builder = createMcpExecutor(mcpCache);
		builder.enqueue(getExtension().isNeoForge() ? "preProcessJar" : "merge");
		return builder.build();
	}

	/**
	 * 执行期求值：只认选项、本次落位路径与服务工厂，不读项目模型.
	 *
	 * <p>把 pre-patch jar 的生产搬进任务之后，执行期这一侧就是本方法；它与配置期路径
	 * 共用 {@link McpExecutor#execute} 这一处实现，因此两条路径的 MCP 步进集合、命令行与产物不会分叉。
	 *
	 * @param options 配置期接好的 MCP 执行器选项
	 * @param output MCP 最后一步产物的落位路径（调用方负责原子落位）
	 */
	public static void runMcpExecutor(Provider<McpExecutor.Options> options, Path output, ServiceFactory serviceFactory) throws IOException {
		McpExecutor executor = serviceFactory.get(options);
		Path result = executor.execute();
		// output 是本次独占的临时文件，写完后由调用方原子落位到共享缓存
		Files.copy(result, output);
	}

	private void createUnobfuscatedPrePatchJar(Path output) throws IOException {
		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			McpExecutorBuilder builder = createMcpExecutor(tempFiles.directory("loom-mcp"));
			builder.enqueue(getExtension().isNeoForge() ? "preProcessJar" : "merge");
			McpExecutor executor = serviceFactory.get(builder.build());
			Path result = executor.execute();
			Files.copy(result, output, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void createNeoForgeInstallerToolsPrePatchJar(Path output) throws IOException {
		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			final Path mappings = tempFiles.file("mappings", ".txt");

			getExtension().download(minecraftProvider.getVersionInfo().download("client_mappings").url())
					.downloadPath(mappings);

			final ForgeExternalToolService installerTools = serviceFactory.get(createNeoForgeInstallerTools());
			installerTools.exec(Map.of(
					"{mappings}", mappings.toAbsolutePath().toString(),
					"{output}", output.toAbsolutePath().toString()
			));
		}
	}

	/**
	 * {@return NeoForge installer-tools 的声明式选项（工具 classpath、主类与参数模板）}.
	 *
	 * <p>参数模板里的取值分两类：
	 * <ul>
	 *   <li><b>配置期就确定的纯值</b>——{@code --task}、输入 jar 与 {@code --neoform-data}——就地定死在模板里，
	 *       于是这条链搬进任务后不需要在执行期回读项目模型；</li>
	 *   <li><b>只有调用时才知道的路径</b>——{@code {mappings}}（本次下载的 client 映射）与
	 *       {@code {output}}（本次调用的目标 jar）——留作占位符。</li>
	 * </ul>
	 *
	 * <p>参数顺序与改造前那串 {@code settings.args(...)} 逐条一致。
	 *
	 * <p>与 {@link #createBinpatcherTool()} 同理，供任务侧把同一条调用搬进执行期：把返回值挂成任务的
	 * {@code @Nested} 输入即可。
	 */
	public ForgeExternalToolService.Options createNeoForgeInstallerTools() {
		// todo: does it work without fatjar
		final FileCollection classpath = DependencyDownloader.download(project, LoomVersions.NEOFORGE_INSTALLER_TOOLS.mavenNotation() + ":fatjar");
		final ForgeExternalToolService.Options tool = ForgeExternalToolService
				.createOptions(project, classpath, "net.neoforged.installertools.ConsoleTool").get();
		final List<String> args = new ArrayList<>();
		args.add("--task");
		args.add("PROCESS_MINECRAFT_JAR");

		switch (type) {
		case CLIENT_ONLY -> addInputArg(args, minecraftProvider.getMinecraftClientJar());
		case SERVER_ONLY -> addInputArg(args, minecraftProvider.getMinecraftServerJar());

		case MERGED -> {
			addInputArg(args, minecraftProvider.getMinecraftClientJar());
			addInputArg(args, minecraftProvider.getMinecraftServerJar());
		}
		}

		args.add("--input-mappings");
		args.add("{mappings}");
		// 外部工具自行创建该文件：指向本次独占的临时文件，避免锁外读方看到半截产物
		args.add("--output");
		args.add("{output}");
		args.add("--neoform-data");
		args.add(getExtension().getMcpConfigProvider().getMcp().toAbsolutePath().toString());

		tool.getArgsTemplate().set(args);
		return tool;
	}

	private static void addInputArg(List<String> args, File input) {
		args.add("--input");
		args.add(input.getAbsolutePath());
	}

	private static void fillClientExtraJar(ProductionOptions options, Path output) throws IOException {
		// output 是本次独占的临时文件：原子发布要求生产者自行创建目标，故无需（也不应）先删除任何共享产物
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(output, true)) {
			Path manifestPath = fs.getPath("META-INF", "MANIFEST.MF");

			if (options.neoForge()) {
				generateNeoForgeDistManifest(options, manifestPath);
			} else if (options.forge()) {
				// Generates an empty manifest for forge client-extra jar.
				// In ForgeGradle, it copies the client manifest when generating client-extra.
				//
				// This will let UnionFS read this instead of the merged mapped jar in later launch process. (see ForgeUserdevLaunchHandler#getMinecraftPaths, Forge 1.21.1+)
				// Otherwise, it reads MANIFEST.MF of the merged minecraft jar which may have 'Automatic-Module-Name',
				// overriding "minecraft" mod id to it.
				createEmptyJarManifest(manifestPath);
			}
		}

		copyNonClassFiles(Objects.requireNonNull(options.clientJar(), "本次生产不产出 client-extra，故没有 client jar"), output);
	}

	// Generates the jar manifest for NeoForge client-extra jars.
	// The manifest includes a Minecraft-Dists attribute that specifies the dists in the current dev env,
	// as well as Minecraft-Dist attributes on every dist-only file.
	private static void generateNeoForgeDistManifest(ProductionOptions options, Path manifestPath) throws IOException {
		// For unobfuscated NeoForge the classes are already in official namespace; an empty tree is safe
		// because SidedJarIndexGenerator falls back to the original name when no mapping is found.
		MemoryMappingTree mappings = options.unobfuscatedForge()
				? new MemoryMappingTree()
				: Objects.requireNonNull(options.mappings(), "NeoForge 的 dist 清单需要映射树");

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().putValue("Minecraft-Dists", options.distAttribute());

		if (options.merged()) {
			Path clientJar = Objects.requireNonNull(options.clientJar(), "MERGED 形态必须提供 client jar");
			Path serverJar = Objects.requireNonNull(options.serverJar(), "MERGED 形态必须提供服务端 jar");
			SidedJarIndexGenerator generator = new SidedJarIndexGenerator(clientJar, serverJar, mappings);
			generator.split((filePath, dist) -> {
				var fileAttributes = new Attributes();
				fileAttributes.putValue("Minecraft-Dist", dist);
				manifest.getEntries().put(filePath, fileAttributes);
			});
		}

		Files.createDirectories(manifestPath.getParent());

		try (OutputStream out = Files.newOutputStream(manifestPath)) {
			manifest.write(out);
		}
	}

	/**
	 * {@return 本工程的映射树}.
	 *
	 * <p>对子类开放：legacy Forge（1.8-1.16）的 forge.jar 修补需要按 srg→named 重映射，
	 * 与这里产出 {@code minecraftPatchedAtJar} 用的是同一棵映射树。
	 */
	protected MemoryMappingTree getMappingTree(ServiceFactory serviceFactory) {
		// 无混淆环境不创建 MappingConfiguration（getMappingConfiguration 会直接抛
		// "Cannot get mappings configuration in a non-obfuscated environment"）。
		// 调用方应已按 isUnobfuscatedForge() 分流；这里再拦一次，把违反前提的情形
		// 变成明确的编程错误而不是误导性的映射缺失异常。
		if (getExtension().isUnobfuscatedForge()) {
			throw new IllegalStateException("无混淆环境没有映射配置，调用方应先按 isUnobfuscatedForge() 分流");
		}

		final MappingOption mappingOption = MappingOption.forPlatform(getExtension());
		final MappingConfiguration mappingConfiguration = getExtension().getMappingConfiguration();

		// 环内自愈：provide() 早于 MappingConfiguration#setupPost，此时 WITH_SRG 尚未登记。
		// 这里按需补齐一次（幂等），否则 getMappingsService 会抛
		// UnsupportedOperationException: Unsupported mapping option: WITH_SRG。
		// 冷缓存或 patched 产物失效时才会走到；缓存命中时 provide() 直接复用、不进本方法。
		if (!mappingConfiguration.hasMappingOption(mappingOption)) {
			try {
				mappingConfiguration.ensureMappingOptionsReady(project);
			} catch (IOException e) {
				throw new UncheckedIOException("补齐映射选项失败：" + mappingOption, e);
			}
		}

		TinyMappingsService mappingsService = mappingConfiguration.getMappingsService(project, serviceFactory, mappingOption);
		return mappingsService.getMappingTree();
	}

	private static TinyRemapper buildRemapper(ProductionOptions options, Path input) throws IOException {
		final String sourceNamespace = options.remapNamespace();
		MemoryMappingTree mappings = Objects.requireNonNull(options.mappings(), "patched jar 的重映射需要映射树");

		TinyRemapper.Builder builder = TinyRemapper.newRemapper()
				.withMappings(TinyRemapperHelper.create(mappings, sourceNamespace, "official", true, true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappings, sourceNamespace, "official"))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true);

		if (options.neoForge()) {
			builder.extension(new MixinExtension(inputTag -> true));
		}

		return builder.build();
	}

	/**
	 * 修补参数注解.
	 *
	 * <p>静态：执行期任务不持有 provider（见 {@link ProductionOptions} 的同类说明），
	 * 配置期路径与任务路径共用这一处实现，日志出口由调用方给出。
	 */
	static void fixParameterAnnotation(Path jarFile, Consumer<String> info) throws Exception {
		info.accept(":fixing parameter annotations for " + jarFile.toAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			// best-effort：这里逐类修补注解，单个类解析/写入失败只应该让该类保持原样，
			// 而不应该让整个 patched jar 流程在「已经改了一半」的状态下失败。
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter().tolerateFailures();

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassNode node = new ClassNode();
					ClassVisitor visitor = new ParameterAnnotationFixer(node, null);
					reader.accept(visitor, 0);

					ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					node.accept(writer);
					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				});
			}

			completer.completeToleratingFailures("parameter annotation fixes in " + jarFile.toAbsolutePath());
		}

		info.accept(":fixed parameter annotations for " + jarFile.toAbsolutePath() + " in " + stopwatch.stop());
	}

	/**
	 * 删除 vignette 参数名.
	 *
	 * <p>静态：理由同 {@link #fixParameterAnnotation(Path, Consumer)}。
	 */
	static void deleteParameterNames(Path jarFile, Consumer<String> info) throws Exception {
		info.accept(":deleting parameter names for " + jarFile.toAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			// best-effort：与 fixParameterAnnotation 同理，逐类改名，单类失败不应该让整个 jar 流程失败。
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter().tolerateFailures();
			Pattern vignetteParameters = Pattern.compile("p_[0-9a-zA-Z]+_(?:[0-9a-zA-Z]+_)?");

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassWriter writer = new ClassWriter(0);

					reader.accept(new ClassVisitor(Constants.ASM_VERSION, writer) {
						@Override
						public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
							return new MethodVisitor(Constants.ASM_VERSION, super.visitMethod(access, name, descriptor, signature, exceptions)) {
								@Override
								public void visitParameter(String name, int access) {
									if (name != null && vignetteParameters.matcher(name).matches()) {
										super.visitParameter(null, access);
									} else {
										super.visitParameter(name, access);
									}
								}

								@Override
								public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
									if (!vignetteParameters.matcher(name).matches()) {
										super.visitLocalVariable(name, descriptor, signature, start, end, index);
									}
								}
							};
						}
					}, 0);

					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				});
			}

			completer.completeToleratingFailures("parameter name removals in " + jarFile.toAbsolutePath());
		}

		info.accept(":deleted parameter names for " + jarFile.toAbsolutePath() + " in " + stopwatch.stop());
	}

	private File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
	}

	/**
	 * {@return 已重映射到 named 的 Forge jar} 仅 legacy Forge 具备该产物.
	 *
	 * <p>legacy 的 Forge universal jar 按 srg 名发布，{@code patchForge} 会把它重映射成 named，
	 * 落位在 Forge 缓存工作目录的 {@code forge.jar}。该产物是
	 * {@link dev.architectury.loom.forge.ForgeMemberNameFixer} 建立「srg 名 → named 名」映射的
	 * 对照来源之一；现代 Forge 的 universal jar 本身就在 named 空间，没有这份产物。
	 *
	 * <p>这里按路径定位而非依赖实例类型：{@code MergedForgeMinecraftProvider.getPatchedProvider()}
	 * 在部分时序下返回的是本基类而非 {@link MinecraftLegacyPatchedProvider}（实测 1.12.2 即如此），
	 * 若只靠子类覆写会拿到 null。产物不存在时返回 {@code null}，调用方据此跳过。
	 */
	public @Nullable Path getNamedForgeJar() {
		// 不用 ForgeProvider.getForgeCache(project)：多项目构建下它按 extension.getForgeProvider()
		// 的版本拼路径，实测在 1.12.2 车道里取到的是 26.2 的版本目录（跨项目状态串用），
		// 于是永远定位不到产物。改从「本项目的 forge universal jar 所在目录」推导——
		// legacy 的 named forge jar 与它同目录，文件名固定为 forge.jar。
		final File universal = getForgeJar();

		if (universal == null) {
			return null;
		}

		final Path candidate = universal.toPath().resolveSibling("forge.jar");
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private File getForgeUserdevJar() {
		return getExtension().getForgeUserdevProvider().getUserdevJar();
	}

	protected boolean isPatchedJarUpToDate(Path jar) throws IOException {
		return isPatchedJarUpToDate(jar, logger::lifecycle);
	}

	/**
	 * {@return 产物的 manifest 是否标记为当前补丁版本}.
	 *
	 * @param lifecycle lifecycle 日志出口；为 {@code null} 时不输出「版本过期」的诊断行
	 */
	private static boolean isPatchedJarUpToDate(Path jar, @Nullable Consumer<String> lifecycle) throws IOException {
		if (Files.notExists(jar)) return false;

		byte[] manifestBytes = ZipUtils.unpackNullable(jar, "META-INF/MANIFEST.MF");

		if (manifestBytes == null) {
			return false;
		}

		Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
		Attributes attributes = manifest.getMainAttributes();
		String value = attributes.getValue(LOOM_PATCH_VERSION_KEY);

		if (Objects.equals(value, CURRENT_LOOM_PATCH_VERSION)) {
			return true;
		} else {
			if (lifecycle != null) {
				lifecycle.accept(":forge patched jars not up to date. current version: " + value);
			}

			return false;
		}
	}

	protected void accessTransformForge() throws IOException {
		// 原子落位：AT 后的 jar 属于共享缓存，必须先完整生成再替换，避免锁外读方拿到半截 jar
		AtomicFiles.publish(minecraftPatchedIntermediateAtJar, tmp -> accessTransform(minecraftPatchedIntermediateJar, tmp));
	}

	/**
	 * 对给定 jar 执行 Forge 的 access transform.
	 *
	 * <p>现代 Forge 走 {@link #accessTransformForge()}（中间产物路径）；legacy Forge 的补丁
	 * 流程在 FG2 自己的工作目录下产出 jar（client/server/merged-patched.jar），必须显式指定
	 * 输入输出，否则会写错产物，令后续的 walkFileSystems / applyLoomPatchVersion 拿到陈旧文件。
	 */
	protected void accessTransform(Path input, Path target) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":access transforming minecraft");

		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			runAccessTransformer(createTranslatedAccessTransformerOptions(tempFiles, input, serviceFactory), input, target, serviceFactory);
		}

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	/**
	 * {@return 把 AT 规则翻译到 {@code input} 命名空间之后的 AT 工具选项}.
	 *
	 * <p>legacy Forge 的 {@code forge_at.cfg} 用 SRG 命名空间（MCP 类名 + srg 成员名），而本链路 AT
	 * 作用的对象是 notch jar。AT 工具按字面量匹配、自身不做名称翻译，直接喂原始规则会一条都匹配不上
	 * 且不报错（表现为 AT 静默失效，症状推迟到运行期的 {@code IllegalAccessError}）。
	 * 因此这里先把规则翻译到 {@code input} 所在命名空间，再交给工具。
	 *
	 * <p>继承关系取自 {@code input} 自身：AT 允许把规则写在子类上而成员声明在父类，
	 * 上溯必须以字节码里的真实继承为准。
	 */
	private Provider<AccessTransformerService.Options> createTranslatedAccessTransformerOptions(TempFiles tempFiles, Path input,
			ServiceFactory serviceFactory) {
		return createAccessTransformerOptions(tempFiles).map(options -> {
			try {
				// 没有任何 AT 规则时直接返回：无规则可翻译，也就不需要映射树。
				//
				// 这一步是必须的短路——getMappingTree() 对 Forge 平台会请求 WITH_SRG 映射，
				// 而 withPatchedLock 阶段早于 MappingConfiguration#setupPost（后者才登记 WITH_SRG），
				// 在「零 AT 文件」的车道上会因此抛
				// UnsupportedOperationException: Unsupported mapping option: WITH_SRG。
				// 缓存命中时 patched jar 被直接复用、不进入本方法，所以该缺陷只在冷缓存下暴露。
				if (options.getAccessTransformers().isEmpty()) {
					logger.lifecycle(":no access transformer rules, skipping AT translation");
					return options;
				}

				// 无混淆环境没有 MappingConfiguration（LoomGradleExtension#getMappingConfiguration
				// 在该形态下直接抛 "Cannot get mappings configuration in a non-obfuscated
				// environment"），且 MC 本身即 official 名，AT 规则无需 SRG→official 翻译，
				// 直接用原始规则即可。此处与 productionOptions 中
				// `isUnobfuscatedForge() ? null : getMappingTree(...)` 采取同一判据。
				if (getExtension().isUnobfuscatedForge()) {
					logger.lifecycle(":unobfuscated environment, using AT rules as-is");
					return options;
				}

				final MemoryMappingTree mappings = getMappingTree(serviceFactory);
				final JarClassHierarchy hierarchy = JarClassHierarchy.read(List.of(input));
				final List<File> translated = new ArrayList<>();

				for (File atFile : options.getAccessTransformers().getFiles()) {
					final List<String> result = AccessTransformerRuleTranslator.translate(
							new AccessTransformerRuleTranslator.Input(
									Files.readAllLines(atFile.toPath()),
									mappings,
									MappingsNamespace.SRG.toString(),
									MappingsNamespace.OFFICIAL.toString(),
									hierarchy));

					final Path tmpFile = tempFiles.file("at-translated", ".cfg");
					Files.write(tmpFile, result);
					translated.add(tmpFile.toFile());
				}

				logger.lifecycle(":translated " + translated.size() + " AT file(s) to the official namespace");
				options.getAccessTransformers().setFrom(translated);
				return options;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	/**
	 * {@return AT 工具的声明式选项（工具 classpath、主类与待应用的 AT 文件）}.
	 *
	 * <p>配置期只接线、不求值：{@link AccessTransformerService#createOptionsForLoaderAts} 返回的是惰性
	 * provider——依赖解析（{@code DependencyDownloader} 只建 detached configuration）与「把 AT 文件从
	 * userdev jar 里抽出来」都推迟到 {@link #runAccessTransformer} 真正取值的那一刻。因此本方法既能在
	 * 配置期路径里调用，也能把返回值挂成任务的 {@code @Nested} 输入，由任务在执行期发起同一次调用。
	 *
	 * <p>{@code tempFiles} 必须活到那次调用结束：AT 文件抽取到它管理的临时目录里。
	 */
	public Provider<AccessTransformerService.Options> createAccessTransformerOptions(TempFiles tempFiles) {
		return AccessTransformerService.createOptionsForLoaderAts(project, tempFiles);
	}

	/**
	 * 执行期求值：只认选项、本次调用的输入输出与服务工厂，不读项目模型.
	 *
	 * <p>与 {@link #createAccessTransformerOptions} 配对，让配置期路径与「把 {@code provide()} 链搬进任务」
	 * 之后的路径共用同一份实现：AT 的实际参数仍只由 {@link AccessTransformerService#execute} 构造一处，
	 * 因此两条路径跑出来的命令行不会分叉。
	 *
	 * @param options 配置期接好的 AT 选项
	 * @param input 待执行 AT 的 jar
	 * @param target AT 结果落位路径（本方法负责原子落位：先写私有副本，再 move 到 target）
	 */
	public static void runAccessTransformer(Provider<AccessTransformerService.Options> options, Path input, Path target,
			ServiceFactory serviceFactory) throws IOException {
		final AccessTransformerService service = serviceFactory.get(options);
		// 原子落位：target 是共享缓存里的产物（legacy Forge 的 client/server/merged-patched.jar），
		// 旧写法「先删 target 再就地写」会留下「文件不存在」窗口；并发读方在窗口里打开它，关闭时就会因
		// toRealPath() 失败而把该路径的 zipfs 登记簿条目永久留成死实例（JDK-8291712）。
		// 「执行 AT 前删掉目标」的既有语义保留，只是删的变成调用方独占的私有副本。
		AtomicFiles.publish(target, tmp -> {
			Files.deleteIfExists(tmp);
			service.execute(input, tmp);
		});
	}

	private static void remapPatchedJar(ProductionOptions options, Path mcOutput, Consumer<String> lifecycle) throws Exception {
		lifecycle.accept(":remapping minecraft (TinyRemapper, %s -> official)".formatted(options.remapNamespace()));
		Path mcInput = options.atPatchedJar();
		Path forgeJar = options.forgeJar();
		Path forgeUserdevJar = options.forgeUserdevJar();
		// mcOutput 是本次独占的临时文件（尚不存在），tiny-remapper 自行创建；无需先删任何共享产物

		TinyRemapper remapper = buildRemapper(options, mcInput);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			if (options.completeForgeInput()) {
				outputConsumer.addNonClassFiles(mcInput, NonClassCopyMode.FIX_META_INF, remapper);
				remapper.readInputs(mcInput);
				remapper.apply(outputConsumer);
			} else {
				outputConsumer.addNonClassFiles(forgeJar, NonClassCopyMode.FIX_META_INF, remapper);
				InputTag mcTag = remapper.createInputTag();
				InputTag forgeTag = remapper.createInputTag();
				CompletableFuture.allOf(remapper.readInputsAsync(mcTag, mcInput), remapper.readInputsAsync(forgeTag, forgeJar, forgeUserdevJar)).join();
				remapper.apply(outputConsumer, mcTag);
				remapper.apply(outputConsumer, forgeTag);
			}
		} finally {
			remapper.finish();
		}

		if (!options.completeForgeInput()) {
			copyUserdevFiles(Objects.requireNonNull(forgeUserdevJar), mcOutput);
		}

		remapCoreMods(mcOutput, Objects.requireNonNull(options.mappings(), "coremod 重映射需要映射树"),
				options.coreModNamespace(), options.runtimeMojang());
		applyLoomPatchVersion(mcOutput);
	}

	private static void remapCoreMods(Path patchedJar, MappingTree mappings, String sourceNamespace, boolean runtimeMojang) throws Exception {
		CoreModClassRemapper.remapJar(runtimeMojang, patchedJar, mappings, sourceNamespace);
	}

	/** {@return 合包 Forge 已解析的 installer；其它车道返回 null}. */
	private @Nullable Path runtimeInstaller() {
		final LoomGradleExtension extension = getExtension();
		return type == Type.MERGED && extension.isForge() && extension.isModernForgeLike()
				? extension.getPatchProvider().getRuntimeInstaller() : null;
	}

	/**
	 * 生产打补丁后的中间产物.
	 *
	 * <p>{@code output} 是本次独占的临时文件：binpatcher 与后续的类改写全部作用在它上面，
	 * 完整生成后才由调用方原子落位到共享缓存路径。
	 */
	private void producePatchedIntermediate(Path output) throws Exception {
		producePatchedIntermediateForTask(
				minecraftIntermediateJar,
				output,
				type.patches.apply(getExtension().getPatchProvider(), getExtension().getForgeUserdevProvider()),
				createBinpatcherTool(),
				runtimeInstaller(),
				getExtension().isForgeLikeAndNotOfficial() && !getExtension().isUnobfuscatedForge(),
				logger::lifecycle,
				logger::info);
	}

	/** zip 条目路径去掉前导斜杠与 .class 后缀. */
	private static String entryName(Path path) {
		String name = path.toString().replace('\\', '/');

		while (name.startsWith("/")) {
			name = name.substring(1);
		}

		return name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : name;
	}

	/**
	 * 检测补丁产物里「被指令引用、但类条目不存在」的内部类，返回缺失条目.
	 *
	 * <p>为什么需要这一步：Forge 的 userdev 包只发布 <b>merged</b> 补丁（{@code joined.lzma}，
	 * 实测 1.21.11 + Forge 61.2.5 共 7270 条，其中<b>零</b>条是含 {@code $} 的内部类），
	 * 而官方 installer 的运行时补丁（{@code data/client.lzma}、{@code data/server.lzma}）
	 * 各自含 810 / 809 条内部类补丁。也就是说补丁并非天生缺类，是 <b>userdev 这一路的材料不完整</b>：
	 * 用 userdev 自建 dev 环境时，{@code Items.class} 会被打上活跃的 {@code new Items$1} 与
	 * {@code invokespecial Items$1.<init>}，但 {@code Items$1.class} 无从产生。
	 * 普通安装与服务端走运行时补丁，不受影响。
	 *
	 * <p>后果：运行到该类初始化时才抛 {@code NoClassDefFoundError}，栈上看到的却是触发方
	 * （例如 {@code ComposterBlock.boot_strap} 里的 {@code getstatic Items.XXX}），
	 * 与实际缺陷点相距很远，极难定位。所以在构建期就查出来，并交给
	 * {@link RuntimeBinpatchExtractor} 从运行时补丁补齐。
	 *
	 * <h4>判据为什么以「指令引用」为准，而不是 {@code InnerClasses} 属性</h4>
	 *
	 * <p>{@code InnerClasses} 会大量引用<b>不在本 jar 内</b>的外部类（{@code java/util/Map$Entry}、
	 * guava 的 {@code ImmutableList$Builder} 等），把它们算作缺失会产生成百上千条误报
	 * （实测 166 条里 156 条属于此类）。而「被 {@code new}/{@code getstatic} 等指令实际引用」
	 * 的类必然要在运行时被解析，缺失即为真缺陷。两个条件同时收窄到本 jar 应自带的命名空间。
	 *
	 * @return 被引用但条目缺失的类名集合（{@code net/minecraft/...} 形式）；为空表示自洽
	 */
	public static Set<String> verifyNoDanglingInnerClassReferences(Path patchedJar, Consumer<String> info) throws IOException {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(patchedJar)) {
			final Path root = fs.get().getPath("/");
			final Set<String> present = new TreeSet<>();
			final List<Path> classes = new ArrayList<>();
			try (var paths = Files.walk(root)) {
				paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).forEach(path -> {
					present.add(entryName(path));
					classes.add(path);
				});
			}

			// 缺失类 -> 引用它的类（去重后按名字排序，报错信息稳定可比对）。
			final Map<String, Set<String>> missing = new java.util.TreeMap<>();

			for (Path path : classes) {
				final String own = entryName(path);

				final List<String> refs = new ArrayList<>();

				try {
					ClassNode node = new ClassNode();
					new ClassReader(Files.readAllBytes(path)).accept(node, 0);

					for (MethodNode method : node.methods) {
						for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
							// 只收「运行时会去解析某个类」的指令。InnerClasses 属性靠不住：
							// 匿名类那条的 outer_class_info_index 是 0，按 outer 过滤会漏掉 Items$1 这类目标，
							// 而 new/invokespecial 是实打实要加载该类的。
							if (insn instanceof TypeInsnNode typeInsn) {
								switch (typeInsn.getOpcode()) {
								case Opcodes.NEW:
								case Opcodes.CHECKCAST:
								case Opcodes.ANEWARRAY:
								case Opcodes.INSTANCEOF:
									refs.add(typeInsn.desc);
									break;
								default:
									break;
								}
							} else if (insn instanceof MethodInsnNode methodInsn) {
								refs.add(methodInsn.owner);
							} else if (insn instanceof FieldInsnNode fieldInsn) {
								refs.add(fieldInsn.owner);
							}
						}
					}
				} catch (RuntimeException e) {
					// 解析失败的类不参与判定：这里的目标是查缺失，不是校验字节码合法性。
					continue;
				}

				for (String ref : refs) {
					final String cls = ref.startsWith("[") ? org.objectweb.asm.Type.getType(ref).getElementType().getInternalName() : ref;

					if (cls.startsWith("net/minecraft/") && cls.contains("$") && !present.contains(cls)) {
						missing.computeIfAbsent(cls, k -> new TreeSet<>()).add(own);
					}
				}
			}

			if (missing.isEmpty()) {
				info.accept(":no dangling inner-class references in patched jar");
				return Set.of();
			}

			return missing.keySet();
		}
	}

	/**
	 * 从 {@code new} 之后顺找构造调用，判据是 owner 相同且方法名为 {@code <init>}.
	 *
	 * @return 找不到时返回 {@code null}
	 */
	@Nullable
	private static MethodInsnNode findInitCall(TypeInsnNode start, String owner) {
		AbstractInsnNode cursor = start.getNext();
		int guard = 0;

		while (cursor != null && guard++ < 512) {
			if (cursor instanceof MethodInsnNode methodInsn && "<init>".equals(methodInsn.name)
					&& methodInsn.getOpcode() == Opcodes.INVOKESPECIAL && methodInsn.owner.equals(owner)) {
				return methodInsn;
			}

			// 遇到同一层级的另一个 new 说明已经越过本次实例化的范围。
			if (cursor instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) {
				return null;
			}

			cursor = cursor.getNext();
		}

		return null;
	}

	/** 读一个类里所有 {@code <init>} 的参数描述符. */
	private static Set<String> readConstructorSigs(byte[] classBytes) {
		final Set<String> sigs = new HashSet<>();

		try {
			final ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

			for (MethodNode method : node.methods) {
				if ("<init>".equals(method.name)) {
					sigs.add(method.desc);
				}
			}
		} catch (RuntimeException e) {
			return Set.of();
		}

		return sigs;
	}

	/**
	 * 把 binpatch 产物里错位的匿名类引用纠正回 pre-patch 的编号，并<b>逐处校验构造器签名</b>.
	 *
	 * <p>Forge 的 {@code .binpatch} 在重新编译宿主类时，会把 {@code <clinit>}（及普通方法）里
	 * 「{@code new 匿名类}」的编号整体错开，而<b>构造器的调用签名沿用了旧编号</b>。实测
	 * 1.21.11 + Forge 61.2.5 的 {@code ByteBufCodecs}：
	 *
	 * <pre>
	 * patch 后：new ByteBufCodecs$19 ; invokespecial ByteBufCodecs$19.&lt;init&gt;(I)V
	 * 但       ByteBufCodecs$19 实际只有 &lt;init&gt;()          ← 同类内部就错配
	 * patch 前：new ByteBufCodecs$11 ; invokespecial ByteBufCodecs$11.&lt;init&gt;(I)V   ← 自洽
	 * </pre>
	 *
	 * <p>运行到该类初始化即抛 {@code NoSuchMethodError: ByteBufCodecs$19: method 'void &lt;init&gt;()' not found}。
	 * 匿名类文件本身是好的（{@code $11} 两侧都只有 {@code (int)}、{@code $19} 两侧都只有 {@code ()}），
	 * 错的只是宿主类里的引用编号。
	 *
	 * <p>三种可能的判据，只有第三种可靠：
	 * <ul>
	 *   <li><b>按字段名</b>——混淆/重映射会改写字段名，两侧对不上（实测不可用）；</li>
	 *   <li><b>按构造器签名反查</b>——签名不唯一：{@code (int)} 对应 3 个类、{@code ()} 对应 18 个，
	 *       无法定位到具体哪个；</li>
	 *   <li><b>按序位提议，再用签名校验</b>——序位给出唯一候选，签名给出「这个候选是否讲得通」的
	 *       硬判据。实测 {@code ByteBufCodecs} 的 34 处配对里，pre 侧类的构造器签名与调用签名
	 *       <b>34/34 全部一致</b>，而 post 侧大量不一致。这正是缺陷的特征。</li>
	 * </ul>
	 *
	 * <p>校验是本方法安全的核心：<b>只有「替换后签名对得上、替换前对不上」才动手</b>。
	 * 两边 {@code new} 数不同则跳过该方法，
	 * 解析失败的构造器也不提供改写依据。签名一致不等于语义等价。
	 *
	 * @param prePatchJar binpatch 的输入 jar，提供「正确的编号 → 类」与各匿名类的构造器签名
	 * @param patchedJar  binpatch 的产物，原地改写
	 * @param info        日志出口
	 * @return 实际纠正的引用处数
	 */
	public static int realignInnerClassNewSites(Path prePatchJar, Path patchedJar, Consumer<String> info) throws IOException {
		// 前提校验：两侧必须处于同一命名空间。本方法的做法是「把 pre 侧第 N 个 new 的目标名
		// 抄到产物侧」，一旦两侧命名空间不同（例如 pre 是混淆名、产物是重映射后的 srg 名），
		// 就会把已正确重映射的类名改回旧名，产物里根本没有那个包，运行时直接
		// NoClassDefFoundError。这个坑真实发生过（见 RemapMinecraftTask 里的注释）。
		// 判据：产物里的类名必须能在 pre 侧找到同名条目；比例过低说明不是同一命名空间。
		if (!sameNamespace(prePatchJar, patchedJar, info)) {
			return 0;
		}

		// 类名 -> 构造器参数描述符集合（一个匿名类可能有多份重载构造器）。
		final Map<String, Set<String>> preCtors = new HashMap<>();
		// pre 侧「方法签名 -> 该方法里匿名类 new 的目标序列」，提供序位候选。
		final Map<String, List<String>> preMethodNewTargets = new HashMap<>();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(prePatchJar)) {
			final List<Path> files = new ArrayList<>();
			Files.walk(fs.get().getPath("/")).filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".class")).forEach(files::add);

			for (Path path : files) {
				final String name = entryName(path);

				final byte[] bytes = Files.readAllBytes(path);

				if (name.contains("$")) {
					final Set<String> sigs = readConstructorSigs(bytes);

					if (!sigs.isEmpty()) {
						preCtors.put(name, sigs);
					}
				}

				// 每个类都要收「方法 -> 匿名类 new 序列」，纠正时按它提议候选。
				collectMethodNewTargets(bytes, preMethodNewTargets);
			}
		}

		int fixed = 0;

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(patchedJar, false)) {
			final Path root = fs.get().getPath("/");
			final List<Path> classes = new ArrayList<>();
			try (var paths = Files.walk(root)) {
				paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).forEach(classes::add);
			}

			// 完整索引后再改写，避免 ZIP 遍历次序决定扫描结果。
			final Map<String, Set<String>> patchedCtors = new HashMap<>();

			for (Path path : classes) {
				final String name = entryName(path);

				if (name.contains("$")) {
					final Set<String> signatures = readConstructorSigs(Files.readAllBytes(path));

					if (!signatures.isEmpty()) {
						patchedCtors.put(name, signatures);
					}
				}
			}

			for (Path path : classes) {
				byte[] bytes;

				try {
					bytes = Files.readAllBytes(path);
				} catch (IOException e) {
					continue;
				}

				final ClassNode node = new ClassNode();

				try {
					new ClassReader(bytes).accept(node, 0);
				} catch (RuntimeException e) {
					continue; // 解析不了就不碰
				}

				final int n = realignClass(node, preMethodNewTargets, preCtors, patchedCtors);

				if (n > 0) {
					final ClassWriter writer = new ClassWriter(0);
					node.accept(writer);
					Files.write(path, writer.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
					fixed += n;
				}
			}
		}

		if (fixed > 0) {
			info.accept(":纠正 " + fixed + " 处错位的匿名类引用");
		}

		return fixed;
	}

	/**
	 * 判断两个 jar 是否处于同一命名空间，用作 {@link #realignInnerClassNewSites} 的前置闸门.
	 *
	 * <p>为什么需要它：realign 的判据建立在「两侧同名同编号位置指向同一个类」之上。若两侧
	 * 命名空间不同（重映射前后、混淆名与 srg 名），序位候选会把产物里的正确类名改成 pre 侧的
	 * 另一个名字，产物里根本没有那个包，运行时在类初始化处 {@code NoClassDefFoundError}。
	 *
	 * <p>判据取「产物类名能在 pre 侧找到同名条目的比例」。同一命名空间下这个比例接近 1
	 * （补丁只改名少部分类）；跨命名空间时几乎为 0。取保守阈值 {@value #SAME_NAMESPACE_THRESHOLD}，
	 * 宁可不动也不误改——不动只是保留原始缺陷（由警告暴露），误改则会让能跑的地方也崩掉。
	 *
	 * @return 同一命名空间（可以继续）时为 {@code true}
	 */
	private static boolean sameNamespace(Path prePatchJar, Path patchedJar, Consumer<String> info) throws IOException {
		final Set<String> preNames = new HashSet<>();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(prePatchJar)) {
			Files.walk(fs.get().getPath("/")).filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".class"))
					.forEach(p -> preNames.add(entryName(p)));
		}

		if (preNames.isEmpty()) {
			return false;
		}

		int total = 0;
		int matched = 0;

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(patchedJar)) {
			final List<Path> classes = new ArrayList<>();
			Files.walk(fs.get().getPath("/")).filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".class")).forEach(classes::add);

			for (Path p : classes) {
				total++;

				if (preNames.contains(entryName(p))) {
					matched++;
				}
			}
		}

		if (total == 0) {
			return false;
		}

		final double ratio = (double) matched / total;

		if (ratio < SAME_NAMESPACE_THRESHOLD) {
			info.accept(":跳过匿名类引用纠正：产物与 pre-patch 的类名重合率仅 "
					+ String.format("%.1f%%", ratio * 100)
					+ "，两侧不在同一命名空间，强行对齐会把类名改坏");
			return false;
		}

		return true;
	}

	private static int realignClass(ClassNode node, Map<String, List<String>> preMethodNewTargets,
			Map<String, Set<String>> preCtors, Map<String, Set<String>> patchedCtors) {
		int fixed = 0;

		for (MethodNode method : node.methods) {
			// 序位 -> 该位置在 pre-patch 侧对应的类名。只统计「带 $ 的 new」，两侧同一口径。
			final List<String> wanted = preMethodNewTargets.get(node.name + "." + method.name + method.desc);

			if (wanted == null || wanted.size() != Stream.of(method.instructions.toArray())
					.filter(insn -> insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.contains("$")).count()) {
				continue;
			}

			int index = 0;

			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof TypeInsnNode typeInsn) || typeInsn.getOpcode() != Opcodes.NEW
						|| !typeInsn.desc.contains("$")) {
					continue;
				}

				if (index >= wanted.size()) {
					break;
				}

				final String candidate = wanted.get(index);
				index++;

				if (candidate.equals(typeInsn.desc)) {
					continue;
				}

				// 顺找构造调用（中间可能隔着任意多条参数求值指令）。
				final MethodInsnNode init = findInitCall(typeInsn, typeInsn.desc);

				if (init == null) {
					continue;
				}

				final String callSig = init.desc;
				final Set<String> preCandidate = preCtors.get(candidate);
				final Set<String> patchedCandidate = patchedCtors.get(candidate);
				final Set<String> currentCtors = patchedCtors.get(typeInsn.desc);

				// 两边的候选都须支持此签名；当前类无法解析时不猜测。
				if (preCandidate == null || !preCandidate.contains(callSig)
						|| patchedCandidate == null || !patchedCandidate.contains(callSig)
						|| currentCtors == null || currentCtors.contains(callSig)) {
					continue;
				}

				typeInsn.desc = candidate;
				init.owner = candidate;
				fixed++;
			}
		}

		return fixed;
	}

	/** 收集一个类里「方法签名 -> 该方法内匿名类 new 的目标序列」. */
	private static void collectMethodNewTargets(byte[] classBytes, Map<String, List<String>> sink) {
		final ClassNode node = new ClassNode();

		try {
			new ClassReader(classBytes).accept(node, 0);
		} catch (RuntimeException e) {
			return;
		}

		for (MethodNode method : node.methods) {
			List<String> sites = null;

			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.contains("$")) {
					if (sites == null) {
						sites = new ArrayList<>();
					}

					sites.add(t.desc);
				}
			}

			if (sites != null) {
				// 键必须带类名：{@code <clinit>()V} 每个类都有，只按签名索引会被后扫描的类覆盖，
				// 于是 ByteBufCodecs.<clinit> 会拿到别的类的序列，校验必然失败而被跳过（实测如此）。
				sink.put(node.name + "." + method.name + method.desc, sites);
			}
		}
	}

	/**
	 * 给「被 Forge 改名成 private」的方法补一个同签名的桥接方法，让旧调用点继续可用.
	 *
	 * <p><b>要解决的问题。</b>Forge 36.2.42（1.16.5）为了让 FG6 不做 reobf，把
	 * {@code TransformationMatrix} 的 {@code func_227987_b_} / {@code func_227985_a_} 改名为
	 * {@code inverseVanilla} / {@code composeVanilla} 并**降为 private**，同时在
	 * {@code IForgeTransformationMatrix} 上提供公开的 {@code inverse()} / {@code compose()} 默认方法。
	 * 配套的 {@code patches/UVTransformationUtil.java.patch} 本应把调用点改成接口方法，
	 * 但该文本补丁在 userdev 的 binpatch 链里没有对应条目（实测 578 个文本补丁中唯它缺失），
	 * 于是产物里出现「类已改名、调用点仍指旧名」的错位。
	 *
	 * <p>后果不是编译期能发现的：{@code BlockMath.<clinit>} 一初始化就抛
	 * {@code NoSuchMethodError: Transformation.func_227987_b_}，客户端在资源重载阶段崩溃，
	 * 栈上看不到 Forge 的补丁材料，极难归因。
	 *
	 * <p><b>为什么补方法而不是改调用点。</b>先试过把调用点改指接口默认方法
	 * （{@code invokeinterface IForgeTransformationMatrix.inverse}），但映射表里
	 * {@code func_227987_b_ → inverse} 的终点与接口默认方法同名同签名，重映射阶段会把这条
	 * 调用内联回原样，改调用点等于与重映射对抗。补方法则完全不动调用点：旧名重新存在，
	 * 链路自洽。
	 *
	 * <p><b>为什么在重映射之后补。</b>同上的名字冲突也发生在**方法定义**上：srg 阶段补出的
	 * {@code func_227987_b_} 在重映射时又被映射成 {@code inverse}，与接口默认方法撞名后被丢弃。
	 * 因此在最终 named 产物上补，名字不再经过映射。
	 *
	 * <p><b>判据必须条件触发。</b>只有「旧方法名不存在、且改名后的目标方法存在」时才补；
	 * 否则会把本来正确的产物改坏（例如尚未改名的新版本 Forge）。判据取自产物本身，不读版本号。
	 *
	 * <p>两个命名空间都要认：srg 阶段类名是 {@code net/minecraft/util/math/vector/TransformationMatrix}，
	 * named 阶段是 {@code com/mojang/math/Transformation}。
	 *
	 * @param patchedJar 产物（就地改写）
	 * @param info       日志出口
	 * @return 补出的桥接方法数
	 */
	public static int bridgeRenamedVanillaMethods(Path patchedJar, Consumer<String> info) throws IOException {
		final String srgOwner = "net/minecraft/util/math/vector/TransformationMatrix";
		final String namedOwner = "com/mojang/math/Transformation";

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(patchedJar, false)) {
			final Path root = fs.get().getPath("/");
			int added = 0;

			for (String owner : List.of(srgOwner, namedOwner)) {
				final Path targetPath = root.resolve(owner + ".class");

				if (!Files.isRegularFile(targetPath)) {
					continue;
				}

				added += bridgeOnClass(targetPath, owner, info);
			}

			return added;
		}
	}

	/** 在单个目标类上补桥接；不适用时返回 0. */
	private static int bridgeOnClass(Path targetPath, String targetOwner, Consumer<String> info) throws IOException {
		final String selfType = "L" + targetOwner + ";";
		// 旧方法名 -> {改名后的私有方法名, 描述符}
		final Map<String, String[]> bridges = new LinkedHashMap<>();
		bridges.put("func_227987_b_", new String[] {"inverseVanilla", "()" + selfType});
		bridges.put("func_227985_a_", new String[] {"composeVanilla", "(" + selfType + ")" + selfType});

		final ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(targetPath)).accept(node, 0);

		if (!targetOwner.equals(node.name) || (node.access & Opcodes.ACC_INTERFACE) != 0) {
			return 0;
		}

		final Map<String, MethodNode> byName = new HashMap<>();
		node.methods.forEach(m -> byName.putIfAbsent(m.name + m.desc, m));

		int added = 0;

		for (Map.Entry<String, String[]> entry : bridges.entrySet()) {
			final String oldName = entry.getKey();
			final String renamed = entry.getValue()[0];
			final String desc = entry.getValue()[1];

			// 旧名已在：无需补，也不能补（会重复定义）。
			if (byName.containsKey(oldName + desc)) {
				continue;
			}

			// 改名后的私有方法不在：该产物不是这个缺陷形态，什么都不做。
			final MethodNode target = byName.get(renamed + desc);

			if (target == null || (target.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != Opcodes.ACC_PRIVATE) {
				continue;
			}

			final boolean takesArgument = org.objectweb.asm.Type.getArgumentTypes(desc).length == 1;
			final MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, oldName, desc, null, null);
			bridge.visitCode();
			bridge.visitVarInsn(Opcodes.ALOAD, 0);

			if (takesArgument) {
				bridge.visitVarInsn(Opcodes.ALOAD, 1);
			}

			// 走 invokespecial 调同类私有方法：不经过虚分派，语义与原实现一致。
			bridge.visitMethodInsn(Opcodes.INVOKESPECIAL, targetOwner, renamed, desc, false);
			bridge.visitInsn(Opcodes.ARETURN);
			bridge.visitMaxs(0, 0);
			bridge.visitEnd();

			node.methods.add(bridge);
			// 同步进索引，避免同一轮里另一条桥接误判。
			byName.put(oldName + desc, bridge);
			added++;
		}

		if (added > 0) {
			final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			Files.write(targetPath, writer.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
			info.accept(":为 " + added + " 个被改名成 private 的宿主方法补回旧名桥接"
					+ "（Forge 36.2.42 的补丁材料缺对应 binpatch 条目）");
		}

		return added;
	}

	/**
	 * 打补丁后的中间产物的生产实现（配置期路径与执行期任务共用）.
	 *
	 * <p>binpatcher 打补丁 → 补出 pre-patch jar 里存在而补丁结果里缺失的类
	 * → 删除 vignette 参数名 → （非 official 的 Forge 系）修补参数注解 → 检查并补入缺失内部类。
	 *
	 * <p>静态：任务对象要能被配置缓存序列化，因此生产不能经由 provider 实例（见 {@link ProductionOptions}）。
	 *
	 * <p>{@code output} 是调用方给的落位路径（配置期与任务路径都是「本次独占的临时文件」），
	 * 本方法不做原子落位——落位由调用方负责。
	 *
	 * @param prePatchJar pre-patch jar，即 binpatcher 的 {@code {clean}} 与补出缺失类的来源
	 * @param output 本次落位路径
	 * @param patches 补丁 jar，即 binpatcher 的 {@code {patch}}
	 * @param binpatcherTool binpatcher 的声明式选项
	 * @param fixParameterAnnotations 是否修补参数注解
	 * @param lifecycle lifecycle 日志出口（配置期是项目 logger，执行期是任务 logger）
	 * @param info info 日志出口（同上）
	 * @param runtimeInstaller Forge 官方 installer jar（用于补齐缺失内部类）；{@code null} 表示本车道不运行补齐
	 */
	public static void producePatchedIntermediateForTask(Path prePatchJar, Path output, Path patches,
			ForgeExternalToolService.Options binpatcherTool, @Nullable Path runtimeInstaller,
			boolean fixParameterAnnotations,
			Consumer<String> lifecycle, Consumer<String> info) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		lifecycle.accept(":patching jars");

		try (var serviceFactory = new ScopedServiceFactory()) {
			runBinpatcher(binpatcherTool, prePatchJar, output, patches, serviceFactory);
		}

		copyMissingClassesStatic(prePatchJar, output);

		// 只在同一 binpatch 流程内对齐，不能在重映射后复制旧命名空间的类名。
		if (runtimeInstaller != null) {
			realignInnerClassNewSites(prePatchJar, output, info);
		}

		deleteParameterNames(output, info);

		if (fixParameterAnnotations) {
			fixParameterAnnotation(output, info);
		}

		if (runtimeInstaller != null) {
			final Set<String> dangling = verifyNoDanglingInnerClassReferences(output, info);

			if (!dangling.isEmpty()) {
				fillDanglingInnerClasses(output, dangling, runtimeInstaller, info);
			}
		}

		lifecycle.accept(":patched jars in " + stopwatch.stop());
	}

	/**
	 * 用官方 installer 的运行时补丁补齐缺失的内部类；缺条目只警告，读取失败仍传播.
	 *
	 * <p>警告而非中断：这是 Forge userdev 材料的上游缺口（见
	 * {@link #verifyNoDanglingInnerClassReferences} 的说明），中断会把所有 Forge 开发环境卡死，
	 * 而运行时产物走的是另一套完整补丁，实际部署不受影响。补齐失败时把事实讲清楚即可。
	 *
	 * @param patchedJar 待补齐的补丁产物
	 * @param missing    缺失的类名集合
	 * @param info       日志出口
	 */
	private static void fillDanglingInnerClasses(Path patchedJar, Set<String> missing, Path installer,
			Consumer<String> info) throws IOException {
		if (Files.notExists(installer)) {
			info.accept(":警告：补丁产物缺 " + missing.size() + " 个内部类，且没有可用的 Forge installer 可用于补齐。\n"
					+ "  Forge 的 userdev 补丁（joined.lzma）不含内部类条目，官方 installer 的运行时补丁才是完整的。\n"
					+ "  运行到这些类会抛 NoClassDefFoundError。缺失清单：" + RuntimeBinpatchExtractor.sorted(missing));
			return;
		}

		final Path tempDir = Files.createTempDirectory("loom-binpatch-fill");

		try {
			final int filled = RuntimeBinpatchExtractor.fillMissingInnerClasses(
					installer, patchedJar, missing, tempDir, info);

			if (filled < missing.size()) {
				final Set<String> still = verifyNoDanglingInnerClassReferences(patchedJar, info);

				if (!still.isEmpty()) {
					info.accept(":警告：仍有 " + still.size() + " 个内部类无法补齐（installer 的运行时补丁里没有）："
							+ RuntimeBinpatchExtractor.sorted(still));
				}
			}
		} finally {
			deleteRecursively(tempDir);
		}
	}

	private static void deleteRecursively(Path dir) {
		try (var paths = Files.walk(dir)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// 临时目录清理失败不影响构建结果。
				}
			});
		} catch (IOException ignored) {
			// 同上。
		}
	}

	/**
	 * 对给定 jar 执行 Forge 的 binary patch.
	 *
	 * <p>工具的 classpath、主类与参数模板都由 {@link ForgeExternalToolService.Options} 声明：
	 * classpath 是惰性的（配置期只接线，取值推迟到真正执行时），发起调用也只依赖 {@link ServiceFactory}。
	 * 因此本方法既能在配置期路径里调用，也能在把它连同调用点搬进任务之后于执行期调用——
	 * 那条路径上不再有任何需要 {@code Project} 的同步求值。
	 */
	protected void patchJars(Path clean, Path output, Path patches) throws Exception {
		try (var serviceFactory = new ScopedServiceFactory()) {
			runBinpatcher(createBinpatcherTool(), clean, output, patches, serviceFactory);
		}
	}

	/**
	 * {@return binpatcher 工具的声明式选项}.
	 *
	 * <p>配置期调用：只做接线。依赖坐标与参数模板都是从 userdev 配置读来的纯值，工具 classpath 是一个
	 * 惰性的 {@code FileCollection}（{@code DependencyDownloader} 只建 detached configuration，
	 * 不解析），主类由工具 jar 清单派生也是惰性的。因此这里既不解析依赖、也不发起进程。
	 *
	 * <p>任务侧要用同一份声明时，把返回值挂成任务的 {@code @Nested} 输入即可。
	 */
	public ForgeExternalToolService.Options createBinpatcherTool() {
		final UserdevConfig.BinaryPatcherConfig config = getExtension().getForgeUserdevProvider().getConfig().binpatcher();
		final FileCollection classpath = DependencyDownloader.download(project, config.dependency());
		final ForgeExternalToolService.Options tool = ForgeExternalToolService
				.createOptionsFromManifest(project, classpath).get();
		tool.getArgsTemplate().set(config.args());
		return tool;
	}

	/**
	 * 执行 binpatcher：把参数模板里的 {@code {clean}}／{@code {output}}／{@code {patch}} 按本次调用的
	 * 路径展开后交给工具服务.
	 *
	 * <p>静态：任务侧（执行期）不持有 provider，也要能用同一份实现发起这次调用，故只认选项与服务工厂。
	 *
	 * @param tool binpatcher 的声明式选项（见 {@link #createBinpatcherTool()}）
	 * @param clean 待打补丁的 Minecraft jar
	 * @param output 本次调用的目标 jar
	 * @param patches 补丁 jar
	 * @param serviceFactory 服务工厂
	 */
	public static void runBinpatcher(ForgeExternalToolService.Options tool, Path clean, Path output, Path patches,
			ServiceFactory serviceFactory) {
		final ForgeExternalToolService binpatcher = serviceFactory.get(tool);
		binpatcher.exec(Map.of(
				"{clean}", clean.toAbsolutePath().toString(),
				"{output}", output.toAbsolutePath().toString(),
				"{patch}", patches.toAbsolutePath().toString()
		));
	}

	static void walkFileSystems(Path source, Path target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
			throws IOException {
		// source 只读、target 要写入：source 走独立文件系统（不登记，避免被 JDK-8291712 毒化），
		// target 必须保留共享文件系统的写入路线。
		try (FileSystemUtil.Delegate sourceFs = FileSystemUtil.getReadOnlyJarFileSystem(source);
				FileSystemUtil.Delegate targetFs = FileSystemUtil.getJarFileSystem(target, false)) {
			for (Path sourceDir : toWalk.apply(sourceFs.get())) {
				Path dir = sourceDir.toAbsolutePath();
				if (!Files.exists(dir)) continue;
				Files.walk(dir)
						.filter(Files::isRegularFile)
						.filter(filter)
						.forEach(it -> {
							boolean root = dir.getParent() == null;

							try {
								Path relativeSource = root ? it : dir.relativize(it);
								Path targetPath = targetFs.get().getPath(relativeSource.toString());
								action.accept(sourceFs.get(), targetFs.get(), it, targetPath);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		}
	}

	public static void walkFileSystems(Path source, Path target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	protected void copyMissingClasses(Path source, Path target) throws IOException {
		copyMissingClassesStatic(source, target);
	}

	/** 与 {@link #copyMissingClasses(Path, Path)} 同一处实现；静态供执行期任务复用. */
	public static void copyMissingClassesStatic(Path source, Path target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	private static void copyNonClassFiles(Path source, Path target) throws IOException {
		Predicate<Path> filter = file -> {
			String s = file.toString();
			return !s.endsWith(".class") && !s.startsWith("/META-INF");
		};

		walkFileSystems(source, target, filter, MinecraftPatchedProvider::copyReplacing);
	}

	public static void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void copyUserdevFiles(Path source, Path target) throws IOException {
		// Removes the Forge name mapping service definition so that our own is used.
		// If there are multiple name mapping services with the same "understanding" pair
		// (source -> target namespace pair), modlauncher throws a fit and will crash.
		// To use our YarnNamingService instead of MCPNamingService, we have to remove this file.
		Predicate<Path> filter = file -> !file.toString().endsWith(".class") && !file.toString().equals(NAME_MAPPING_SERVICE_PATH);

		walkFileSystems(source, target, filter, fs -> Collections.singleton(fs.getPath("inject")), (sourceFs, targetFs, sourcePath, targetPath) -> {
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	public static void applyLoomPatchVersion(Path target) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(target, false)) {
			Path manifestPath = delegate.get().getPath("META-INF/MANIFEST.MF");

			Check.require(Files.exists(manifestPath), "META-INF/MANIFEST.MF does not exist in patched srg jar!");
			Manifest manifest = new Manifest();

			if (Files.exists(manifestPath)) {
				try (InputStream stream = Files.newInputStream(manifestPath)) {
					manifest.read(stream);
					manifest.getMainAttributes().putValue(LOOM_PATCH_VERSION_KEY, CURRENT_LOOM_PATCH_VERSION);
				}
			}

			try (OutputStream stream = Files.newOutputStream(manifestPath, StandardOpenOption.CREATE)) {
				manifest.write(stream);
			}
		}
	}

	private static void createEmptyJarManifest(Path manifestPath) throws IOException {
		if (Files.exists(manifestPath)) {
			return;
		}

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

		Files.createDirectories(manifestPath.getParent());

		try (OutputStream out = Files.newOutputStream(manifestPath)) {
			manifest.write(out);
		}
	}

	public McpExecutorBuilder createMcpExecutor(Path cache) {
		McpConfigProvider provider = getExtension().getMcpConfigProvider();
		return new McpExecutorBuilder(project, minecraftProvider, cache, provider, type.mcpId);
	}

	public Path getMinecraftIntermediateJar() {
		return minecraftIntermediateJar;
	}

	public Path getMinecraftPatchedIntermediateJar() {
		return minecraftPatchedIntermediateJar;
	}

	public Path getMinecraftPatchedIntermediateAtJar() {
		return minecraftPatchedIntermediateAtJar;
	}

	public Path getMinecraftPatchedJar() {
		return minecraftPatchedJar;
	}

	/**
	 * Checks whether the provider's state is dirty (regenerating jars).
	 */
	public boolean isDirty() {
		return dirty;
	}

	public enum Type {
		CLIENT_ONLY("client", "client", (patch, userdev) -> patch.extractClientPatches()),
		SERVER_ONLY("server", "server", (patch, userdev) -> patch.extractServerPatches()),
		MERGED("merged", "joined", (patch, userdev) -> userdev.getJoinedPatches());

		// 对子类开放：legacy 实现需要按 type 区分产物命名
		protected final String id;
		protected final String mcpId;
		protected final BiFunction<PatchProvider, ForgeUserdevProvider, Path> patches;

		Type(String id, String mcpId, BiFunction<PatchProvider, ForgeUserdevProvider, Path> patches) {
			this.id = id;
			this.mcpId = mcpId;
			this.patches = patches;
		}

		// The value for Minecraft-Dists
		private String getNeoForgeDistsAttribute() {
			return this == MERGED ? "client server" : id;
		}
	}
}
