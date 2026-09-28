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
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
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
import dev.architectury.loom.forge.tool.ForgeToolValueSource;
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
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
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

	public void remapJar(ServiceFactory serviceFactory) throws Exception {
		// 无锁快路径：provide 已判定无需重建（dirty=false）时，此处仅注册依赖（纯内存操作），不取锁
		if (!dirty) {
			registerExtraDependencies();
			return;
		}

		withPatchedLock(() -> remapPatchedJarWithDirty(serviceFactory));
	}

	private Void remapPatchedJarWithDirty(ServiceFactory serviceFactory) throws Exception {
		// 锁内二次确认：等锁期间其它进程可能已完成生产（dirty 为幂等判定）；本方法不删除共享产物
		// dirty 也可能只表示「client-extra 需要重建」（见 providePatched）：此时最终 jar 会被幂等地重发一次，
		// 而 client-extra 的修复不会被跳过
		if (dirty) {
			if (getExtension().isUnobfuscatedForge()) {
				publishAtomically(minecraftPatchedJar, this::mergeUnobfuscatedPatchedJar);
			} else {
				publishAtomically(minecraftPatchedJar, output -> remapPatchedJar(output, serviceFactory));
			}

			if (providesClientJar()) {
				publishAtomically(minecraftClientExtra, output -> fillClientExtraJar(serviceFactory, output));
			}
		}

		registerExtraDependencies();
		return null;
	}

	private void registerExtraDependencies() {
		if (getExtension().isUnobfuscatedForge()) {
			DependencyProvider.addDependency(project, getForgeJar(), Constants.Configurations.FORGE_EXTRA);
		}

		if (providesClientJar()) {
			DependencyProvider.addDependency(project, minecraftClientExtra, Constants.Configurations.FORGE_EXTRA);
		}
	}

	/**
	 * 在跨进程锁保护下执行 patched jar 生产.
	 *
	 * <p>产物位于跨 daemon 共享的 forge 缓存目录（不按项目隔离），且 {@code client-extra.jar} 在
	 * 各 Type 实例间共享同一路径；同一 MC+Forge 版本的多个并发构建会互相读写/删除同一批文件，
	 * 必须整段生产串行化。锁内判定均为幂等二次确认，等锁期间其它进程完成生产后直接复用。
	 */
	protected void withPatchedLock(Callable<Void> action) {
		final String lockKey = "forge-patched:" + getExtension().getMinecraftProvider().minecraftVersion()
				+ ":" + getExtension().getForgeProvider().getVersion().getCombined();
		final Path lockRoot = ForgeProvider.getForgeCache(project).resolve(Constants.Cache.LOCKS_DIR);

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

	private void mergeUnobfuscatedPatchedJar(Path output) throws IOException {
		logger.lifecycle(":merging userdev into minecraft");
		Path forgeUserdevJar = getForgeUserdevJar().toPath();

		// output 是本次独占的临时文件：整份写入后在它上面完成全部加工，再由调用方原子落位
		Files.copy(minecraftPatchedIntermediateAtJar, output);

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
			McpExecutorBuilder builder = createMcpExecutor(tempFiles.directory("loom-mcp"));
			builder.enqueue("rename");
			McpExecutor executor = serviceFactory.get(builder.build());
			Path result = executor.execute();
			// output 是本次独占的临时文件，写完后由调用方原子落位到共享缓存
			Files.copy(result, output);
		}
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
		try (var tempFiles = new TempFiles()) {
			final Path mappings = tempFiles.file("mappings", ".txt");

			getExtension().download(minecraftProvider.getVersionInfo().download("client_mappings").url())
					.downloadPath(mappings);

			ForgeToolValueSource.exec(project, settings -> {
				// todo: does it work without fatjar
				settings.getExecClasspath().from(DependencyDownloader.download(project, LoomVersions.NEOFORGE_INSTALLER_TOOLS.mavenNotation() + ":fatjar"));
				settings.getMainClass().set("net.neoforged.installertools.ConsoleTool");
				settings.args("--task", "PROCESS_MINECRAFT_JAR");

				switch (type) {
				case CLIENT_ONLY -> settings.args("--input", minecraftProvider.getMinecraftClientJar().getAbsolutePath());
				case SERVER_ONLY -> settings.args("--input", minecraftProvider.getMinecraftServerJar().getAbsolutePath());

				case MERGED -> {
					settings.args("--input", minecraftProvider.getMinecraftClientJar().getAbsolutePath());
					settings.args("--input", minecraftProvider.getMinecraftServerJar().getAbsolutePath());
				}
				}

				settings.args("--input-mappings", mappings.toAbsolutePath().toString());
				// 外部工具自行创建该文件：指向本次独占的临时文件，避免锁外读方看到半截产物
				settings.args("--output", output.toAbsolutePath().toString());
				settings.args("--neoform-data", getExtension().getMcpConfigProvider().getMcp().toAbsolutePath().toString());
			});
		}
	}

	private void fillClientExtraJar(ServiceFactory serviceFactory, Path output) throws IOException {
		// output 是本次独占的临时文件：原子发布要求生产者自行创建目标，故无需（也不应）先删除任何共享产物
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(output, true)) {
			Path manifestPath = fs.getPath("META-INF", "MANIFEST.MF");

			if (getExtension().isNeoForge()) {
				generateNeoForgeDistManifest(serviceFactory, manifestPath);
			} else if (getExtension().isForge()) {
				// Generates an empty manifest for forge client-extra jar.
				// In ForgeGradle, it copies the client manifest when generating client-extra.
				//
				// This will let UnionFS read this instead of the merged mapped jar in later launch process. (see ForgeUserdevLaunchHandler#getMinecraftPaths, Forge 1.21.1+)
				// Otherwise, it reads MANIFEST.MF of the merged minecraft jar which may have 'Automatic-Module-Name',
				// overriding "minecraft" mod id to it.
				createEmptyJarManifest(manifestPath);
			}
		}

		copyNonClassFiles(minecraftProvider.getMinecraftClientJar().toPath(), output);
	}

	// Generates the jar manifest for NeoForge client-extra jars.
	// The manifest includes a Minecraft-Dists attribute that specifies the dists in the current dev env,
	// as well as Minecraft-Dist attributes on every dist-only file.
	private void generateNeoForgeDistManifest(ServiceFactory serviceFactory, Path manifestPath) throws IOException {
		// For unobfuscated NeoForge the classes are already in official namespace; an empty tree is safe
		// because SidedJarIndexGenerator falls back to the original name when no mapping is found.
		MemoryMappingTree mappings = getExtension().isUnobfuscatedForge()
				? new MemoryMappingTree()
				: getMappingTree(serviceFactory);

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().putValue("Minecraft-Dists", type.getNeoForgeDistsAttribute());

		if (type == Type.MERGED) {
			Path clientJar = minecraftProvider.getMinecraftClientJar().toPath();
			Path serverJar = Objects.requireNonNullElse(
					minecraftProvider.getMinecraftExtractedServerJar(),
					minecraftProvider.getMinecraftServerJar()
			).toPath();
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

	private TinyRemapper buildRemapper(ServiceFactory serviceFactory, Path input) throws IOException {
		final String sourceNamespace = IntermediaryNamespaces.intermediary(project);
		MemoryMappingTree mappings = getMappingTree(serviceFactory);

		TinyRemapper.Builder builder = TinyRemapper.newRemapper()
				.withMappings(TinyRemapperHelper.create(mappings, sourceNamespace, "official", true, true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappings, sourceNamespace, "official"))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true);

		if (getExtension().isNeoForge()) {
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
			logger.lifecycle(":forge patched jars not up to date. current version: " + value);
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
			AccessTransformerService service = serviceFactory.get(AccessTransformerService.createOptionsForLoaderAts(project, tempFiles));
			Files.deleteIfExists(target);
			service.execute(input, target);
		}

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	private void remapPatchedJar(Path mcOutput, ServiceFactory serviceFactory) throws Exception {
		logger.lifecycle(":remapping minecraft (TinyRemapper, {} -> official)", IntermediaryNamespaces.intermediary(project));
		Path mcInput = minecraftPatchedIntermediateAtJar;
		Path forgeJar = getForgeJar().toPath();
		Path forgeUserdevJar = getForgeUserdevJar().toPath();
		// mcOutput 是本次独占的临时文件（尚不存在），tiny-remapper 自行创建；无需先删任何共享产物

		TinyRemapper remapper = buildRemapper(serviceFactory, mcInput);

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
		remapCoreMods(mcOutput, serviceFactory);
		applyLoomPatchVersion(mcOutput);
	}

	private void remapCoreMods(Path patchedJar, ServiceFactory serviceFactory) throws Exception {
		final MappingOption mappingOption = MappingOption.forPlatform(getExtension());
		final TinyMappingsService mappingsService = getExtension().getMappingConfiguration().getMappingsService(project, serviceFactory, mappingOption);
		final MappingTree mappings = mappingsService.getMappingTree();

		final boolean isRuntimeMojang = getExtension().getForgeProvider().usesMojangAtRuntime();
		CoreModClassRemapper.remapJar(project, isRuntimeMojang, patchedJar, mappings);
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

	protected void patchJars(Path clean, Path output, Path patches) throws Exception {
		ForgeToolValueSource.exec(project, spec -> {
			UserdevConfig.BinaryPatcherConfig config = getExtension().getForgeUserdevProvider().getConfig().binpatcher();
			final FileCollection download = DependencyDownloader.download(project, config.dependency());
			spec.classpath(download);
			spec.getMainClass().set(getMainClass(download));

			for (String arg : config.args()) {
				String actual = switch (arg) {
				case "{clean}" -> clean.toAbsolutePath().toString();
				case "{output}" -> output.toAbsolutePath().toString();
				case "{patch}" -> patches.toAbsolutePath().toString();
				default -> arg;
				};
				spec.args(actual);
			}
		});
	}

	private static String getMainClass(final Iterable<File> files) {
		String mainClass = null;
		IOException ex = null;

		for (File file : files) {
			if (file.getName().endsWith(".jar")) {
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(file.toPath())) {
					final Path mfPath = fs.getPath("META-INF/MANIFEST.MF");

					if (Files.exists(mfPath)) {
						try (InputStream in = Files.newInputStream(mfPath)) {
							mainClass = new Manifest(in).getMainAttributes().getValue("Main-Class");
						}
					}
				} catch (final IOException e) {
					if (ex == null) {
						ex = e;
					} else {
						ex.addSuppressed(e);
					}
				}

				if (mainClass != null) {
					break;
				}
			}
		}

		if (mainClass == null) {
			if (ex != null) {
				throw new UncheckedIOException(ex);
			} else {
				throw new RuntimeException("Failed to find main class");
			}
		}

		return mainClass;
	}

	private void walkFileSystems(Path source, Path target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
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

	protected void walkFileSystems(Path source, Path target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
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

	private void copyNonClassFiles(Path source, Path target) throws IOException {
		Predicate<Path> filter = file -> {
			String s = file.toString();
			return !s.endsWith(".class") && !s.startsWith("/META-INF");
		};

		walkFileSystems(source, target, filter, this::copyReplacing);
	}

	protected void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private void copyUserdevFiles(Path source, Path target) throws IOException {
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

	public void applyLoomPatchVersion(Path target) throws IOException {
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

	private void createEmptyJarManifest(Path manifestPath) throws IOException {
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
