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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import dev.architectury.loom.forge.CoreModClassRemapper;
import dev.architectury.loom.forge.InnerClassRemapper;
import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.forge.dependency.DependencyProvider;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.dependency.ForgeUserdevProvider;
import dev.architectury.loom.forge.dependency.PatchProvider;
import dev.architectury.loom.forge.tool.ForgeExternalToolService;
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
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.pipeline.GenerateForgePatchedJarTask;
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
	private static final String CURRENT_LOOM_PATCH_VERSION = "10";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	// The version where the bug was introduced.
	private static final String MIN_NEOFORGE_MANUAL_CLEAN_JAR_CREATION_VERSION = "21.10.57-beta";
	// The version where the bug was fixed.
	private static final String MAX_NEOFORGE_MANUAL_CLEAN_JAR_CREATION_VERSION = "21.10.64";

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
	// Step 4: Remap Patched AT & Forge to official
	private Path minecraftPatchedJar;
	private Path minecraftClientExtra;

	// 对子类开放：legacy Forge 子类需要读写该标志，且 isDirty() 必须能反映子类的重建状态
	protected boolean dirty = false;

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

	private void initPatchedFiles() {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = ForgeProvider.getForgeCache(project);
		// Note: strings used instead of platform id since FML requires one of these exact strings
		// depending on the loader to recognise Minecraft.
		String patchId = (getExtension().isNeoForge() ? "neoforge" : "forge") + "-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		final String intermediateId = getExtension().isNeoForge()
				? (getExtension().isUnobfuscatedForge() ? "official" : "mojang")
				: "srg";
		minecraftIntermediateJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + ".jar");
		minecraftPatchedIntermediateJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + "-patched.jar");
		minecraftPatchedIntermediateAtJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + "-at-patched.jar");
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

		// 无锁快路径：产物齐备且补丁版本最新时，本次仅做内存配置，不触碰共享缓存，不取锁
		if (!needsWork()) {
			this.dirty = false;
			return;
		}

		withPatchedLock(this::providePatched);
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
		if (getExtension().refreshDeps() || Stream.of(getGlobalCaches()).anyMatch(jar -> !isReusableJar(jar))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(minecraftPatchedJar);
		} catch (IOException e) {
			return true;
		}
	}

	private Void providePatched() throws Exception {
		// 锁内二次确认：等锁期间其它进程可能已完成生产。本方法不删除任何共享产物，
		// 「整链重建」由 checkCache 的返回值表达，缺失或损坏的产物则由下面的可复用性判定按件补齐
		final boolean forceRebuild = checkCache();
		this.dirty = forceRebuild;

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

		if (isReusableJar(jar)) {
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
		if (!isReusableJar(jar)) {
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
	 */
	protected void produceIntermediateJarIfMissing() throws Exception {
		provide();
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
	 *   <li><b>disableObfuscation / unobfuscated Forge</b>：最终 jar 走
	 *       {@code mergeUnobfuscatedPatchedJar}（把 userdev jar 合进 AT 后的 jar）且映射树为空，
	 *       同样不在本次迁移的形态之内。</li>
	 * </ul>
	 */
	protected @Nullable String remapJarProjectionBlocker() {
		if (getExtension().isLegacyForge()) {
			return "legacy Forge（1.8-1.16）的 patched jar 是 FG2 工作目录下的一组产物，生产链与 modern 不同";
		}

		if (getExtension().disableObfuscation()) {
			return "disableObfuscation / unobfuscated Forge 的最终 jar 走 userdev 合并分支，形态不同";
		}

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
	private void registerPatchedJarTask() {
		final Path patchedJar = minecraftPatchedJar.toAbsolutePath().normalize();
		final Path clientExtra = providesClientJar() ? minecraftClientExtra.toAbsolutePath().normalize() : null;
		final var extension = getExtension();
		final var mappingConfiguration = extension.getMappingConfiguration();
		// 与 buildRemapper / remapCoreMods / generateNeoForgeDistManifest 取映射树用的是同一个映射选项
		final var mappingsOptions = mappingConfiguration.getMappingsServiceOptions(project, MappingOption.forPlatform(extension));
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
			task.getForgeJar().set(getForgeJar());
			task.getForgeUserdevJar().set(getForgeUserdevJar());
			task.getMappingsServiceOptions().set(mappingsOptions);
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
				}
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
			Path forgeJar,
			Path forgeUserdevJar,
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
				getForgeJar().toPath(),
				getForgeUserdevJar().toPath(),
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
	protected static void publishAtomically(Path target, ThrowingProducer producer) throws IOException {
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
	protected interface ThrowingProducer {
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
		McpExecutorBuilder builder = createMcpExecutor(tempFiles.directory("loom-mcp"));
		builder.enqueue("rename");
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

	private MemoryMappingTree getMappingTree(ServiceFactory serviceFactory) {
		final MappingOption mappingOption = MappingOption.forPlatform(getExtension());
		TinyMappingsService mappingsService = getExtension().getMappingConfiguration().getMappingsService(project, serviceFactory, mappingOption);
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

	private void fixParameterAnnotation(Path jarFile) throws Exception {
		logger.info(":fixing parameter annotations for " + jarFile.toAbsolutePath());
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

		logger.info(":fixed parameter annotations for " + jarFile.toAbsolutePath() + " in " + stopwatch.stop());
	}

	private void deleteParameterNames(Path jarFile) throws Exception {
		logger.info(":deleting parameter names for " + jarFile.toAbsolutePath());
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

		logger.info(":deleted parameter names for " + jarFile.toAbsolutePath() + " in " + stopwatch.stop());
	}

	private File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
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
			runAccessTransformer(createAccessTransformerOptions(tempFiles), input, target, serviceFactory);
		}

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
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
	 * @param target AT 结果落位路径（调用方负责原子落位；本方法先删除它，与改造前逐字一致）
	 */
	public static void runAccessTransformer(Provider<AccessTransformerService.Options> options, Path input, Path target,
			ServiceFactory serviceFactory) throws IOException {
		final AccessTransformerService service = serviceFactory.get(options);
		Files.deleteIfExists(target);
		service.execute(input, target);
	}

	private static void remapPatchedJar(ProductionOptions options, Path mcOutput, Consumer<String> lifecycle) throws Exception {
		lifecycle.accept(":remapping minecraft (TinyRemapper, %s -> official)".formatted(options.remapNamespace()));
		Path mcInput = options.atPatchedJar();
		Path forgeJar = options.forgeJar();
		Path forgeUserdevJar = options.forgeUserdevJar();
		// mcOutput 是本次独占的临时文件（尚不存在），tiny-remapper 自行创建；无需先删任何共享产物

		TinyRemapper remapper = buildRemapper(options, mcInput);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			outputConsumer.addNonClassFiles(forgeJar, NonClassCopyMode.FIX_META_INF, remapper);

			InputTag mcTag = remapper.createInputTag();
			InputTag forgeTag = remapper.createInputTag();
			List<CompletableFuture<?>> futures = new ArrayList<>();
			futures.add(remapper.readInputsAsync(mcTag, mcInput));
			futures.add(remapper.readInputsAsync(forgeTag, forgeJar, forgeUserdevJar));
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			remapper.apply(outputConsumer, mcTag);
			remapper.apply(outputConsumer, forgeTag);
		} finally {
			remapper.finish();
		}

		copyUserdevFiles(forgeUserdevJar, mcOutput);
		remapCoreMods(mcOutput, Objects.requireNonNull(options.mappings(), "coremod 重映射需要映射树"),
				options.coreModNamespace(), options.runtimeMojang());
		applyLoomPatchVersion(mcOutput);
	}

	private static void remapCoreMods(Path patchedJar, MappingTree mappings, String sourceNamespace, boolean runtimeMojang) throws Exception {
		CoreModClassRemapper.remapJar(runtimeMojang, patchedJar, mappings, sourceNamespace);
	}

	/**
	 * 生产打补丁后的中间产物.
	 *
	 * <p>{@code output} 是本次独占的临时文件：binpatcher 与后续的类改写全部作用在它上面，
	 * 完整生成后才由调用方原子落位到共享缓存路径。
	 */
	private void producePatchedIntermediate(Path output) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");
		patchJars(minecraftIntermediateJar, output, type.patches.apply(getExtension().getPatchProvider(), getExtension().getForgeUserdevProvider()));

		copyMissingClasses(minecraftIntermediateJar, output);
		deleteParameterNames(output);

		if (getExtension().isForgeLikeAndNotOfficial() && !getExtension().isUnobfuscatedForge()) {
			fixParameterAnnotation(output);
		}

		logger.lifecycle(":patched jars in " + stopwatch.stop());
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
		try (FileSystemUtil.Delegate sourceFs = FileSystemUtil.getJarFileSystem(source, false);
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

	static void walkFileSystems(Path source, Path target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	protected void copyMissingClasses(Path source, Path target) throws IOException {
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

	static void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
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

	static void applyLoomPatchVersion(Path target) throws IOException {
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
