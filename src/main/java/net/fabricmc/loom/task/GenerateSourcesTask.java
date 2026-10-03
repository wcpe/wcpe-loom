/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2026 FabricMC
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

package net.fabricmc.loom.task;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import dev.architectury.loom.forge.ForgeSourcesService;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.gradle.workers.internal.WorkerDaemonClientsManager;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.decompilers.DecompilationMetadata;
import net.fabricmc.loom.api.decompilers.DecompilerOptions;
import net.fabricmc.loom.api.decompilers.LoomDecompiler;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider;
import net.fabricmc.loom.decompilers.ClassLineNumbers;
import net.fabricmc.loom.decompilers.LineNumberRemapper;
import net.fabricmc.loom.decompilers.cache.CachedData;
import net.fabricmc.loom.decompilers.cache.CachedFileStoreImpl;
import net.fabricmc.loom.decompilers.cache.CachedJarProcessor;
import net.fabricmc.loom.task.service.SourceMappingsService;
import net.fabricmc.loom.task.service.UnpickService;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.IOStringConsumer;
import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.SyncTaskBuildService;
import net.fabricmc.loom.util.gradle.ThreadedProgressLoggerConsumer;
import net.fabricmc.loom.util.gradle.ThreadedSimpleProgressLogger;
import net.fabricmc.loom.util.gradle.WorkerDaemonClientsManagerHelper;
import net.fabricmc.loom.util.gradle.daemon.DaemonUtils;
import net.fabricmc.loom.util.ipc.IPCClient;
import net.fabricmc.loom.util.ipc.IPCServer;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

@DisableCachingByDefault
public abstract class GenerateSourcesTask extends AbstractLoomTask {
	// v2：引入跨进程缓存锁与原子发布机制后 bump，使旧版本反编译缓存自然失效重建
	private static final String CACHE_VERSION = "v2";
	private final DecompilerOptions decompilerOptions;

	/**
	 * The jar name to decompile, {@link MinecraftJar#getName()}.
	 */
	@Input
	public abstract Property<String> getInputJarName();

	@Classpath
	protected abstract RegularFileProperty getClassesInputJar();

	@Classpath
	protected abstract ConfigurableFileCollection getClasspath();

	@Classpath
	protected abstract ConfigurableFileCollection getMinecraftCompileLibraries();

	@OutputFile
	public abstract RegularFileProperty getSourcesOutputJar();

	// Contains the remapped linenumbers
	@OutputFile
	protected abstract RegularFileProperty getClassesOutputJar();

	@Input
	@Option(option = "use-cache", description = "Use the decompile cache")
	@ApiStatus.Experimental
	public abstract Property<Boolean> getUseCache();

	@Input
	@Option(option = "reset-cache", description = "When set the cache will be reset")
	@ApiStatus.Experimental
	public abstract Property<Boolean> getResetCache();

	// Internal inputs
	@ApiStatus.Internal
	@Nested
	protected abstract Property<SourceMappingsService.Options> getMappings();

	// Internal outputs
	@ApiStatus.Internal
	@Internal
	protected abstract RegularFileProperty getDecompileCacheFile();

	@ApiStatus.Internal
	@Input
	protected abstract Property<Integer> getMaxCachedFiles();

	@ApiStatus.Internal
	@Input
	protected abstract Property<Integer> getMaxCacheFileAge();

	// Injects
	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	protected abstract ExecOperations getExecOperations();

	@Inject
	protected abstract WorkerDaemonClientsManager getWorkerDaemonClientsManager();

	@Inject
	protected abstract ProgressLoggerFactory getProgressLoggerFactory();

	@Nested
	protected abstract Property<DaemonUtils.Context> getDaemonUtilsContext();

	@ApiStatus.Internal
	@Internal
	protected abstract Property<String> getRuntimeNamespace();

	@Nested
	@Optional
	protected abstract Property<UnpickService.Options> getUnpickOptions();

	@Nested
	@Optional
	protected abstract Property<ForgeSourcesService.Options> getForgeSourcesOptions();

	// Prevent Gradle from running two gen sources tasks in parallel
	@ServiceReference(SyncTaskBuildService.NAME)
	abstract Property<SyncTaskBuildService> getSyncTask();

	@Inject
	public GenerateSourcesTask(DecompilerOptions decompilerOptions) {
		this.decompilerOptions = decompilerOptions;

		// 这里只**算路径**，刻意不做存在性检查：Gradle 会在**任务图计算阶段**查询本属性的值来收集
		// 依赖（任务依赖的值里含 inputs.files），那时产出任务还没跑、backup 自然还不存在
		// （改造后 backup 由任务在执行期落位）。把检查放在值提供者里，等于把「产物尚未生产」变成
		// 「Could not determine the dependencies of task ':genSources…'」的硬失败，产出任务连执行的
		// 机会都没有——冷缓存下必然踩到。检查与诊断改在执行期，见 getClassesInputJarPath()。
		getClassesInputJar().fileProvider(getInputJarName().map(minecraftJarName -> {
			final List<MinecraftJar> minecraftJars = getExtension().getNamedMinecraftProvider().getMinecraftJars();

			for (MinecraftJar minecraftJar : minecraftJars) {
				if (minecraftJar.getName().equals(minecraftJarName)) {
					return AbstractMappedMinecraftProvider.getBackupJarPath(minecraftJar).toFile();
				}
			}

			throw new IllegalStateException("Input minecraft jar not found: " + getInputJarName().get());
		}));
		getClassesOutputJar().fileProvider(getInputJarName().map(minecraftJarName -> {
			final List<MinecraftJar> minecraftJars = getExtension().getNamedMinecraftProvider().getMinecraftJars();

			for (MinecraftJar minecraftJar : minecraftJars) {
				if (minecraftJar.getName().equals(minecraftJarName)) {
					return minecraftJar.toFile();
				}
			}

			throw new IllegalStateException("Input minecraft jar not found: " + getInputJarName().get());
		}));

		getClasspath().from(decompilerOptions.getClasspath()).finalizeValueOnRead();
		dependsOn(decompilerOptions.getClasspath().getBuiltBy());

		// 反编译的输入是 named MC jar 的 backup（见上面的 getClassesInputJar），而 named jar 由任务在
		// **执行期**落位：这份依赖必须由本任务自己声明。
		//
		// 这里刻意不依赖「间接排序」：各 genSources 任务恰好都 dependsOn(validateAccessWidener)，而后者
		// 消费了扩展登记的 MC jar 集合（见 ValidateAccessWidenerTask 的构造器注释），于是产出任务今天确实
		// 会被排在前面。但那是两条各自独立的接线凑出来的巧合——validateAccessWidener 的输入一变，本任务的
		// 输入就会在产出任务之前被解析，报出的却是与「产物还没生产」混在一起的
		// 「Input minecraft jar not found at ...」硬失败。声明成显式依赖后，顺序不再依赖别人。
		//
		// 依赖取自扩展登记的产出集合（它派生自 TaskProvider，携带生产者的任务依赖）：
		// 未登记任务产出的命名空间（回退到配置期生产）里它是裸文件，此时不加任何依赖，行为与改造前一致。
		// MappingsNamespace.NAMED 而非其他命名空间：反编译的输入固定是 named 分支的 jar。
		dependsOn(getExtension().getMinecraftJarsCollection(MappingsNamespace.NAMED).getBuildDependencies());

		getMinecraftCompileLibraries().from(getProject().getConfigurations().named(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
		getDecompileCacheFile().set(getExtension().getFiles().getDecompileCache(CACHE_VERSION));

		getUseCache().convention(true);
		getResetCache().convention(getExtension().refreshDeps());

		getMappings().set(SourceMappingsService.create(getProject()));
		// 源码映射派生自平台映射文件（Forge 下是迁移产物）：投影时它由迁移任务产出，必须显式接线
		LoomGradleExtension.get(getProject()).addPlatformMappingsDependency(this);

		if (!LoomGradleExtension.get(getProject()).disableObfuscation()) {
			getUnpickOptions().set(UnpickService.createOptions(this));
			getRuntimeNamespace().set(MappingsNamespace.NAMED.toString());
		} else {
			getRuntimeNamespace().set(MappingsNamespace.OFFICIAL.toString());
		}

		getMaxCachedFiles().set(GradleUtils.getIntegerPropertyProvider(getProject(), Constants.Properties.DECOMPILE_CACHE_MAX_FILES).orElse(50_000));
		getMaxCacheFileAge().set(GradleUtils.getIntegerPropertyProvider(getProject(), Constants.Properties.DECOMPILE_CACHE_MAX_AGE).orElse(90));

		getDaemonUtilsContext().set(getProject().getObjects().newInstance(DaemonUtils.Context.class, getProject()));

		getForgeSourcesOptions().set(ForgeSourcesService.createOptions(getProject()));

		// 提前展开 Forge 源码包（声明式、可缓存），避免每次执行都手工遍历归档。
		// 用 provider 延迟探测任务是否存在，避免在尚未注册时抛错。
		dependsOn(getProject().getProviders().provider(() ->
				getProject().getTasks().findByName(ExtractArchiveFilesTask.FORGE_SOURCES_TASK_NAME) == null
						? java.util.List.of()
						: java.util.List.of(ExtractArchiveFilesTask.FORGE_SOURCES_TASK_NAME)));

		mustRunAfter(getProject().getTasks().withType(AbstractRemapJarTask.class));
	}

	@TaskAction
	public void run() throws IOException {
		final Platform platform = Platform.CURRENT;

		if (!platform.getArchitecture().is64Bit()) {
			throw new UnsupportedOperationException("GenSources task requires a 64bit JVM to run due to the memory requirements.");
		}

		try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
			if (!getUseCache().get()) {
				getLogger().info("Not using decompile cache.");

				try (var timer = new Timer("Decompiled sources")) {
					runWithoutCache(serviceFactory);
				} catch (Exception e) {
					ExceptionUtil.processException(e, getDaemonUtilsContext().get());
					throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to decompile", e);
				}

				return;
			}

			getLogger().info("Using decompile cache.");

			try (var timer = new Timer("Decompiled sources with cache")) {
				final Path cacheFile = getDecompileCacheFile().getAsFile().get().toPath();
				// 反编译缓存锁：缓存文件位于 <userCache>/decompile/<version>.zip，锁目录取 <userCache>/.locks。
				// 跨进程按缓存文件名加锁，避免多个 genSources 任务（不同子项目/不同 daemon）并发读写同一缓存 zip 导致损坏。
				final Path lockRoot = cacheFile.getParent().getParent().resolve(Constants.Cache.LOCKS_DIR);
				final String lockKey = "decompile:" + cacheFile.getFileName();

				CacheEntryLock.withLock(lockRoot, lockKey, LoomCacheService.defaultTimeout(), () -> {
					if (getResetCache().get()) {
						getLogger().warn("Resetting decompile cache");
						Files.deleteIfExists(cacheFile);
					}

					Files.createDirectories(cacheFile.getParent());

					if (Files.exists(cacheFile)) {
						try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(cacheFile, true)) {
							// Success, cache exists and can be read
						} catch (IOException e) {
							getLogger().warn("Discarding invalid decompile cache file: {}", cacheFile, e);
							Files.delete(cacheFile);
						}
					}

					try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(cacheFile, true)) {
						runWithCache(serviceFactory, fs.getRoot());
					}

					return null;
				});
			} catch (Exception e) {
				ExceptionUtil.processException(e, getDaemonUtilsContext().get());
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to decompile", e);
			}
		}
	}

	/**
	 * {@return 反编译的输入 jar 路径（named MC jar 的 backup）}
	 *
	 * <p><b>存在性检查放在这里，即执行期，不能放回 {@link #getClassesInputJar()} 的值提供者。</b>
	 * Gradle 会在任务图计算阶段查询属性的值来收集依赖，那时产出任务还没跑。职责分工是：
	 * 「backup 何时出现」由任务依赖保证（见构造器末尾声明的产出依赖），
	 * 「出现后是否真的存在」在这里检查。
	 *
	 * <p>走到本方法时输入仍然缺失，说明产出依赖没接上（例如命名空间没登记任务产出）。这时报一条
	 * 能直接定位的错误，而不是让反编译器读输入时才失败。
	 *
	 * @throws IllegalStateException 输入 jar 在执行期仍不存在
	 */
	private Path getClassesInputJarPath() {
		final Path path = getClassesInputJar().get().getAsFile().toPath();

		if (Files.notExists(path)) {
			throw new IllegalStateException("Input minecraft jar not found at: " + path
					+ "。named MC jar 及其 backup 由产出任务写出（RemapMinecraftTask 或 "
					+ "WriteMinecraftJarSidecarsTask）；执行期仍缺失说明本任务没拿到对应的产出依赖。");
		}

		return path;
	}

	private void runWithCache(ServiceFactory serviceFactory, Path cacheRoot) throws IOException {
		final Path classesInputJar = getClassesInputJarPath();
		final Path sourcesOutputJar = getSourcesOutputJar().get().getAsFile().toPath();
		final Path classesOutputJar = getClassesOutputJar().get().getAsFile().toPath();
		final var cacheRules = new CachedFileStoreImpl.CacheRules(getMaxCachedFiles().get(), Duration.ofDays(getMaxCacheFileAge().get()));
		final var decompileCache = new CachedFileStoreImpl<>(cacheRoot, CachedData.SERIALIZER, cacheRules);
		final String cacheKey = getCacheKey(serviceFactory);
		final CachedJarProcessor cachedJarProcessor = new CachedJarProcessor(decompileCache, cacheKey);
		final CachedJarProcessor.WorkRequest workRequest;

		getLogger().info("Decompile cache key: {}", cacheKey);
		getLogger().debug("Decompile cache rules: {}", cacheRules);

		try (var timer = new Timer("Prepare job")) {
			workRequest = cachedJarProcessor.prepareJob(classesInputJar);
		}

		final CachedJarProcessor.WorkJob job = workRequest.job();
		final CachedJarProcessor.CacheStats cacheStats = workRequest.stats();

		getLogger().lifecycle("Decompile cache stats: {} hits, {} misses", cacheStats.hits(), cacheStats.misses());

		ClassLineNumbers outputLineNumbers = null;

		if (job instanceof CachedJarProcessor.WorkToDoJob workToDoJob) {
			Path workInputJar = workToDoJob.incomplete();
			Path existingClasses = (job instanceof CachedJarProcessor.PartialWorkJob partialWorkJob) ? partialWorkJob.existingClasses() : null;

			if (usingUnpick()) {
				try (var timer = new Timer("Unpick")) {
					UnpickService unpick = serviceFactory.get(getUnpickOptions());
					workInputJar = unpick.unpickJar(workInputJar, existingClasses);
				}
			}

			try (var timer = new Timer("Decompile")) {
				outputLineNumbers = runDecompileJob(workInputJar, workToDoJob.output(), existingClasses);
				removeForgeInnerClassSources(workToDoJob.output());
				outputLineNumbers = filterForgeLineNumbers(outputLineNumbers);
			}

			if (Files.notExists(workToDoJob.output())) {
				throw new RuntimeException("Failed to decompile sources");
			}
		} else if (job instanceof CachedJarProcessor.CompletedWorkJob completedWorkJob) {
			// Nothing to do :)
		}

		// The final output sources jar
		Files.deleteIfExists(sourcesOutputJar);

		try (var timer = new Timer("Complete job")) {
			cachedJarProcessor.completeJob(sourcesOutputJar, job, outputLineNumbers);
		}

		getLogger().info("Decompiled sources written to {}", sourcesOutputJar);

		// Remap the line numbers with the new and existing numbers
		final ClassLineNumbers existingLinenumbers = workRequest.lineNumbers();
		final ClassLineNumbers lineNumbers = ClassLineNumbers.merge(existingLinenumbers, outputLineNumbers);

		applyLineNumbers(lineNumbers, classesInputJar, classesOutputJar);

		try (var timer = new Timer("Prune cache")) {
			decompileCache.prune();
		}
	}

	private void runWithoutCache(ServiceFactory serviceFactory) throws IOException {
		final Path classesInputJar = getClassesInputJarPath();
		final Path sourcesOutputJar = getSourcesOutputJar().get().getAsFile().toPath();
		final Path classesOutputJar = getClassesOutputJar().get().getAsFile().toPath();

		Path workClassesJar = classesInputJar;

		if (usingUnpick()) {
			try (var timer = new Timer("Unpick")) {
				UnpickService unpick = serviceFactory.get(getUnpickOptions());
				workClassesJar = unpick.unpickJar(workClassesJar, null);
			}
		}

		ClassLineNumbers lineNumbers;

		try (var timer = new Timer("Decompile")) {
			lineNumbers = runDecompileJob(workClassesJar, sourcesOutputJar, null);
			removeForgeInnerClassSources(sourcesOutputJar);
			lineNumbers = filterForgeLineNumbers(lineNumbers);
		}

		if (Files.notExists(sourcesOutputJar)) {
			throw new RuntimeException("Failed to decompile sources");
		}

		getLogger().info("Decompiled sources written to {}", sourcesOutputJar);

		applyLineNumbers(lineNumbers, classesInputJar, classesOutputJar);
	}

	private void applyLineNumbers(@Nullable ClassLineNumbers lineNumbers, Path classesInputJar, Path classesOutputJar) throws IOException {
		if (lineNumbers == null) {
			getLogger().info("No line numbers to remap, skipping remapping");
			return;
		}

		final Path tempJar = Files.createTempFile("loom", "linenumber-remap.jar");
		Files.delete(tempJar);

		try (var timer = new Timer("Remap line numbers")) {
			remapLineNumbers(lineNumbers, classesInputJar, tempJar);
		}

		Files.move(tempJar, classesOutputJar, StandardCopyOption.REPLACE_EXISTING);
	}

	private String getCacheKey(ServiceFactory serviceFactory) {
		var sj = new StringJoiner(",");
		sj.add(getDecompilerCheckKey());

		if (usingUnpick()) {
			UnpickService unpick = serviceFactory.get(getUnpickOptions());
			sj.add(unpick.getUnpickCacheKey());
		}

		SourceMappingsService mappingsService = serviceFactory.get(getMappings());
		String mappingsHash = mappingsService.getProcessorHash();

		if (mappingsHash != null) {
			sj.add(mappingsHash);
		}

		// Forge 源码是**执行期注入**进产物的，而注入发生在缓存条目被写出之前——缓存里存的是注入后的内容。
		// 因此缓存键必须覆盖注入的输入（Forge 源码包、预解压目录，以及重映射这些源码用的映射文件），
		// 否则「换了注入输入、类字节却没变」时会全命中：任务因输入变化重跑，产物却与改动前逐字节相同，
		// 新的注入内容被静默忽略（见 ForgeSourcesService.getSourcesCacheKey）。
		final @Nullable ForgeSourcesService forgeSourcesService = serviceFactory.getOrNull(getForgeSourcesOptions());

		if (forgeSourcesService != null) {
			sj.add(forgeSourcesService.getSourcesCacheKey());
		}

		getLogger().info("Decompile cache data: {}", sj);

		return Checksum.of(sj.toString()).sha256().hex();
	}

	private String getDecompilerCheckKey() {
		var sj = new StringJoiner(",");
		sj.add(decompilerOptions.getDecompilerClassName().get());
		sj.add(Checksum.of(decompilerOptions.getClasspath()).sha256().hex());

		for (Map.Entry<String, String> entry : decompilerOptions.getOptions().get().entrySet()) {
			sj.add(entry.getKey() + "=" + entry.getValue());
		}

		return sj.toString();
	}

	@Nullable
	private ClassLineNumbers runDecompileJob(Path inputJar, Path outputJar, @Nullable Path existingJar) throws IOException {
		final Platform platform = Platform.CURRENT;
		final Path lineMapFile = File.createTempFile("loom", "linemap").toPath();
		Files.delete(lineMapFile);

		if (!platform.supportsUnixDomainSockets()) {
			getLogger().warn("Decompile worker logging disabled as Unix Domain Sockets is not supported on your operating system.");

			doWork(null, inputJar, outputJar, lineMapFile, existingJar);

			// Inject Forge's own sources
			try (var serviceFactory = new ScopedServiceFactory()) {
				final @Nullable ForgeSourcesService service = serviceFactory.getOrNull(getForgeSourcesOptions());

				if (service != null) {
					service.addForgeSources(inputJar, outputJar);
				}
			}

			return readLineNumbers(lineMapFile);
		}

		// Set up the IPC path to get the log output back from the forked JVM
		final Path ipcPath = Files.createTempFile("loom", "ipc");
		Files.deleteIfExists(ipcPath);

		try (ThreadedProgressLoggerConsumer loggerConsumer = new ThreadedProgressLoggerConsumer(getLogger(), getProgressLoggerFactory(), decompilerOptions.getName(), "Decompiling minecraft sources");
				IPCServer logReceiver = new IPCServer(ipcPath, loggerConsumer)) {
			doWork(logReceiver, inputJar, outputJar, lineMapFile, existingJar);
		} catch (InterruptedException e) {
			throw new RuntimeException("Failed to shutdown log receiver", e);
		} finally {
			Files.deleteIfExists(ipcPath);
		}

		// Inject Forge's own sources
		try (var serviceFactory = new ScopedServiceFactory()) {
			final @Nullable ForgeSourcesService service = serviceFactory.getOrNull(getForgeSourcesOptions());

			if (service != null) {
				service.addForgeSources(inputJar, outputJar);
			}
		}

		return readLineNumbers(lineMapFile);
	}

	@Nullable
	private ClassLineNumbers filterForgeLineNumbers(@Nullable ClassLineNumbers lineNumbers) {
		if (lineNumbers == null) {
			return null;
		}

		if (getModPlatform().get().isForgeLike()) {
			// Remove Forge and NeoForge classes from linemap
			// TODO: We should instead not decompile Forge's classes at all
			var lineMap = new HashMap<String, ClassLineNumbers.Entry>();

			for (Map.Entry<String, ClassLineNumbers.Entry> entry : lineNumbers.lineMap().entrySet()) {
				String name = entry.getKey();

				if (!name.startsWith("net/minecraftforge/") && !name.startsWith("net/neoforged/")) {
					lineMap.put(name, entry.getValue());
				}
			}

			return new ClassLineNumbers(lineMap);
		} else {
			return lineNumbers;
		}
	}

	/**
	 * Some inner classes orders are messed up with forge recompilation, I don't know if that is why the decompiler
	 * would occasionally split out extra inner classes (where with normal fabric setups it doesn't happen),
	 * but this is a workaround for that.
	 */
	private void removeForgeInnerClassSources(Path sourcesJar) throws IOException {
		if (!getModPlatform().get().isForgeLike()) return;

		try (FileSystemUtil.Delegate outputFs = FileSystemUtil.getJarFileSystem(sourcesJar, false);
				Stream<Path> walk = Files.walk(outputFs.getRoot())) {
			Iterator<Path> iterator = walk.iterator();

			while (iterator.hasNext()) {
				final Path fsPath = iterator.next();

				if (fsPath.startsWith("/META-INF/")) {
					continue;
				}

				if (!Files.isRegularFile(fsPath)) {
					continue;
				}

				if (fsPath.toString().substring(outputFs.getRoot().toString().length()).indexOf('$') != -1) {
					Files.delete(fsPath);
				}
			}
		}
	}

	private void remapLineNumbers(ClassLineNumbers lineNumbers, Path inputJar, Path outputJar) throws IOException {
		Objects.requireNonNull(lineNumbers, "lineNumbers");
		final var remapper = new LineNumberRemapper(lineNumbers);
		remapper.process(inputJar, outputJar);

		final Path lineMap = inputJar.resolveSibling(inputJar.getFileName() + ".linemap.txt");

		try (BufferedWriter writer = Files.newBufferedWriter(lineMap)) {
			lineNumbers.write(writer);
		}

		getLogger().info("Wrote linemap to {}", lineMap);
	}

	private void doWork(@Nullable IPCServer ipcServer, Path inputJar, Path outputJar, Path linemapFile, @Nullable Path existingClasses) {
		final String jvmMarkerValue = UUID.randomUUID().toString();
		final WorkQueue workQueue = createWorkQueue(jvmMarkerValue);

		workQueue.submit(DecompileAction.class, params -> {
			params.getDecompilerOptions().set(decompilerOptions.toDto());

			params.getInputJar().set(inputJar.toFile());
			params.getOutputJar().set(outputJar.toFile());
			params.getLinemapFile().set(linemapFile.toFile());
			params.getMappings().set(getMappings());
			params.getRuntimeNamespace().set(getRuntimeNamespace());

			if (ipcServer != null) {
				params.getIPCPath().set(ipcServer.getPath().toFile());
			}

			params.getClassPath().setFrom(getMinecraftCompileLibraries());

			if (existingClasses != null) {
				params.getClassPath().from(existingClasses);
			}

			// Architectury
			params.getForge().set(getModPlatform().get().isForgeLike());
		});

		try {
			workQueue.await();
		} finally {
			if (ipcServer != null) {
				boolean stopped = WorkerDaemonClientsManagerHelper.stopIdleJVM(getWorkerDaemonClientsManager(), jvmMarkerValue);

				if (!stopped && ipcServer.hasReceivedMessage()) {
					getLogger().info("Failed to stop decompile worker JVM, it may have already been stopped?");
				}
			}
		}
	}

	private WorkQueue createWorkQueue(String jvmMarkerValue) {
		if (!useProcessIsolation()) {
			return getWorkerExecutor().classLoaderIsolation(spec -> {
				spec.getClasspath().from(getClasspath());
			});
		}

		return getWorkerExecutor().processIsolation(spec -> {
			spec.forkOptions(forkOptions -> {
				forkOptions.setMinHeapSize(String.format(Locale.ENGLISH, "%dm", Math.min(512, decompilerOptions.getMemory().get())));
				forkOptions.setMaxHeapSize(String.format(Locale.ENGLISH, "%dm", decompilerOptions.getMemory().get()));
				forkOptions.systemProperty(WorkerDaemonClientsManagerHelper.MARKER_PROP, jvmMarkerValue);
			});
			spec.getClasspath().from(getClasspath());
		});
	}

	private boolean useProcessIsolation() {
		// Useful if you want to debug the decompiler, make sure you run gradle with enough memory.
		return !Boolean.getBoolean("fabric.loom.genSources.debug");
	}

	private boolean usingUnpick() {
		return getUnpickOptions().isPresent();
	}

	public interface DecompileParams extends WorkParameters {
		Property<DecompilerOptions.Dto> getDecompilerOptions();

		RegularFileProperty getInputJar();
		RegularFileProperty getOutputJar();
		RegularFileProperty getLinemapFile();
		Property<SourceMappingsService.Options> getMappings();
		Property<String> getRuntimeNamespace();

		RegularFileProperty getIPCPath();

		ConfigurableFileCollection getClassPath();

		// Architectury
		Property<Boolean> getForge();
	}

	public abstract static class DecompileAction implements WorkAction<DecompileParams> {
		@Override
		public void execute() {
			if (!getParameters().getIPCPath().isPresent() || !Platform.CURRENT.supportsUnixDomainSockets()) {
				// Does not support unix domain sockets, print to sout.
				doDecompile(System.out::println);
				return;
			}

			final Path ipcPath = getParameters().getIPCPath().get().getAsFile().toPath();

			try (IPCClient ipcClient = new IPCClient(ipcPath)) {
				doDecompile(new ThreadedSimpleProgressLogger(ipcClient));
			} catch (Exception e) {
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to decompile", e);
			}
		}

		private void doDecompile(IOStringConsumer logger) {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path linemap = getParameters().getLinemapFile().get().getAsFile().toPath();
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();

			final DecompilerOptions.Dto decompilerOptions = getParameters().getDecompilerOptions().get();

			final LoomDecompiler decompiler;

			try {
				final String className = decompilerOptions.className();
				final Constructor<LoomDecompiler> decompilerConstructor = getDecompilerConstructor(className);
				Objects.requireNonNull(decompilerConstructor, "%s must have a no args constructor".formatted(className));

				decompiler = decompilerConstructor.newInstance();
			} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
				throw new RuntimeException("Failed to create decompiler", e);
			}

			try (var serviceFactory = new ScopedServiceFactory()) {
				final SourceMappingsService mappingsService = serviceFactory.get(getParameters().getMappings());
				final Path javaDocs = mappingsService.getMappingsFile();

				final var metadata = new DecompilationMetadata(
						decompilerOptions.maxThreads(),
						javaDocs,
						getLibraries(),
						logger,
						decompilerOptions.options(),
						getParameters().getRuntimeNamespace().get()
				);

				decompiler.decompile(
						inputJar,
						outputJar,
						linemap,
						metadata
				);

				// Close the decompile loggers
				try {
					metadata.logger().accept(ThreadedProgressLoggerConsumer.CLOSE_LOGGERS);
				} catch (IOException e) {
					throw new UncheckedIOException("Failed to close loggers", e);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		private Collection<Path> getLibraries() {
			return toPaths(getParameters().getClassPath());
		}

		static Collection<Path> toPaths(FileCollection files) {
			return files.getFiles().stream().map(File::toPath).collect(Collectors.toSet());
		}
	}

	public static File getJarFileWithSuffix(String suffix, Path runtimeJar) {
		final String path = runtimeJar.toFile().getAbsolutePath();

		if (!path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			throw new RuntimeException("Invalid mapped JAR path: " + path);
		}

		return new File(path.substring(0, path.length() - 4) + suffix);
	}

	static File getJarFileWithSuffix(RegularFileProperty runtimeJar, String suffix) {
		return getJarFileWithSuffix(suffix, runtimeJar.get().getAsFile().toPath());
	}

	@Nullable
	private static ClassLineNumbers readLineNumbers(Path linemapFile) throws IOException {
		if (Files.notExists(linemapFile)) {
			return null;
		}

		try (BufferedReader reader = Files.newBufferedReader(linemapFile, StandardCharsets.UTF_8)) {
			return ClassLineNumbers.readMappings(reader);
		} catch (Exception e) {
			throw new IOException("Failed to read line number map: " + linemapFile, e);
		}
	}

	private static Constructor<LoomDecompiler> getDecompilerConstructor(String clazz) {
		try {
			//noinspection unchecked
			return (Constructor<LoomDecompiler>) Class.forName(clazz).getConstructor();
		} catch (NoSuchMethodException e) {
			return null;
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	public interface MappingsProcessor {
		boolean transform(MemoryMappingTree mappings);
	}

	private final class Timer implements AutoCloseable {
		private final String name;
		private final long start;

		Timer(String name) {
			this.name = name;
			this.start = System.currentTimeMillis();
		}

		@Override
		public void close() {
			getLogger().info("{} took {}ms", name, System.currentTimeMillis() - start);
		}
	}
}
