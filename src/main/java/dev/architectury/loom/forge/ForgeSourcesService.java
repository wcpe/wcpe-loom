package dev.architectury.loom.forge;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import dev.architectury.loom.forge.tool.ForgeToolExecutor;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.NullOutputStream;
import dev.architectury.loom.util.TempFiles;
import dev.architectury.loom.util.ThreadingUtils;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.task.ExtractArchiveFilesTask;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.SourceRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

public final class ForgeSourcesService extends Service<ForgeSourcesService.Options> {
	public static ServiceType<Options, ForgeSourcesService> TYPE = new ServiceType<>(Options.class, ForgeSourcesService.class);

	private static final Logger LOGGER = Logging.getLogger(ForgeSourcesService.class);

	public interface Options extends Service.Options {
		/**
		 * Forge 安装器源码包（归档形态）.
		 *
		 * <p>由 {@link #createOptions(Project)} 无条件声明，因此始终参与任务输入哈希，
		 * 并不是「只有未接入声明式解压时才存在」的可选项。归档只在两种情况下真正被解压：
		 * 没有可用的 {@link #getForgeSourceDirectories()}，或调用方明确要求走归档路径
		 * （例如配置期，见 {@link #addForgeSourcesDuringProjectConfiguration(Project, ServiceFactory)}）。
		 */
		@Optional
		@InputFiles
		@PathSensitive(PathSensitivity.NONE)
		ConfigurableFileCollection getForgeSourceJars();

		/**
		 * 由声明式解压任务（{@code extractForgeSources}）预先展开的 Forge 源码目录.
		 *
		 * <p>这里只是按构建目录约定声明出来的路径，<b>不含</b> {@code getBuiltBy()} 一类构建依赖：
		 * 任务依赖由 {@code genSources} / {@code genForgePatchedSources} 自己声明，
		 * 所以配置期调用方不能消费它（那时的目录只可能来自上一次构建），必须改走归档路径。
		 * 使用 {@link PathSensitivity#RELATIVE} 以「目录内容」语义参与输入哈希，
		 * 避免缓存目录绝对路径变化引起无谓重跑。
		 */
		@Optional
		@InputFiles
		@PathSensitive(PathSensitivity.RELATIVE)
		ConfigurableFileCollection getForgeSourceDirectories();

		@Optional
		@Nested
		Property<SourceRemapperService.Options> getSourceRemapperService();

		@Input
		Property<Boolean> getShouldShowVerboseStderr();
	}

	public static Provider<Options> createOptions(Project project) {
		return TYPE.maybeCreate(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			// Don't apply on other platforms
			if (!extension.isForgeLike()) {
				return false;
			}

			final String sourceDependency = extension.getForgeUserdevProvider().getConfig().sources();
			options.getForgeSourceJars().from(DependencyDownloader.download(project, sourceDependency));

			// 声明式解压任务的输出目录：路径由构建目录约定决定，无需在此解析任务，
			// 任务依赖由 genSources / genForgePatchedSources 显式声明。
			options.getForgeSourceDirectories().from(
					project.getLayout().getBuildDirectory().dir(ExtractArchiveFilesTask.FORGE_SOURCES_OUTPUT_DIRECTORY));

			if (!extension.isUnobfuscatedForge()) {
				options.getSourceRemapperService().set(SourceRemapperService.TYPE.create(project, sro -> {
					final MappingsNamespace sourceNamespace = extension.getProductionNamespaceEnum().get();
					final String targetNamespace = MappingsNamespace.NAMED.toString();

					sro.getMappings().set(MappingsService.createOptionsWithProjectMappings(
							project,
							project.provider(sourceNamespace::toString),
							project.provider(() -> targetNamespace)
					));
					sro.getJavaCompileRelease().set(SourceRemapperService.getJavaCompileRelease(project));
					sro.getClasspath().from(DependencyDownloader.download(project, LoomVersions.JETBRAINS_ANNOTATIONS.mavenNotation()));
					sro.getClasspath().from(extension.getMinecraftJars(sourceNamespace));
					sro.getClasspath().from(project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));

					TinyRemapperHelper.JSR_TO_JETBRAINS.forEach((from, to) -> {
						Pair<String, String> mapping = new Pair<>(from, to);
						sro.getAdditionalClassMappings().add(mapping);
					});
				}));
			}

			options.getShouldShowVerboseStderr().set(ForgeToolExecutor.shouldShowVerboseStderr(project));

			return true;
		});
	}

	public ForgeSourcesService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public static void addForgeSourcesDuringProjectConfiguration(Project project, ServiceFactory serviceFactory) throws IOException {
		List<Path> minecraftJars = LoomGradleExtension.get(project).getMinecraftJars(MappingsNamespace.NAMED);
		Path minecraftJar;

		if (minecraftJars.isEmpty()) {
			// ???
			throw new IllegalStateException("Could not find Minecraft jar for Forge sources");
		} else if (minecraftJars.size() > 1) {
			// Cannot add Forge sources to split jars
			return;
		} else {
			minecraftJar = minecraftJars.getFirst();
		}

		Path sourcesJar = GenerateSourcesTask.getJarFileWithSuffix("-sources.jar", minecraftJar).toPath();

		if (!Files.exists(sourcesJar)) {
			final ForgeSourcesService service = serviceFactory.get(createOptions(project));
			// 配置期必须走归档路径：此时 extractForgeSources 还没有在本构建执行过（它是 genSources 的依赖），
			// build/loom/forgeSources 里若有内容，只可能是上一次构建、甚至另一个 Forge 版本遗留的目录。
			// 直接消费它会把旧版本的源码静默写进新生成的 *-sources.jar。
			service.addForgeSources(minecraftJar, sourcesJar, false);
		}
	}

	/**
	 * 把 Forge 源码写入目标源码包，允许消费预解压目录.
	 *
	 * @param minecraftJar 用作类过滤依据的 Minecraft jar；为 {@code null} 时跳过「源码是否存在于输入 jar」的检查
	 * @param sourcesJar 目标源码包
	 * @throws IOException 读写源码包失败
	 */
	public void addForgeSources(@Nullable Path minecraftJar, Path sourcesJar) throws IOException {
		addForgeSources(minecraftJar, sourcesJar, true);
	}

	/**
	 * 把 Forge 源码写入目标源码包.
	 *
	 * @param minecraftJar 用作类过滤依据的 Minecraft jar；为 {@code null} 时跳过「源码是否存在于输入 jar」的检查
	 * @param sourcesJar 目标源码包
	 * @param usePreExtractedSources 是否允许消费 {@link Options#getForgeSourceDirectories()} 预解压目录。
	 *         只有<b>同一次构建里</b>已经声明并执行了生产者任务（{@code extractForgeSources}）的调用方才能传
	 *         {@code true}；配置期调用必须传 {@code false}，否则会读到上一次构建遗留的、属于旧 Forge 版本的源码。
	 * @throws IOException 读写源码包失败
	 */
	public void addForgeSources(@Nullable Path minecraftJar, Path sourcesJar, boolean usePreExtractedSources) throws IOException {
		try (FileSystemUtil.Delegate inputFs = minecraftJar == null ? null : FileSystemUtil.getJarFileSystem(minecraftJar, true);
				FileSystemUtil.Delegate outputFs = FileSystemUtil.getJarFileSystem(sourcesJar, true)) {
			// best-effort：目标是给 IDE 看的源码包，个别文件写不进去只会少一个源文件，
			// 不应该让整个 genSources 失败（与下面 failedToRemap 的处理保持一致）。
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter().tolerateFailures();

			provideForgeSources(path -> {
				Path inputPath = inputFs == null ? null : inputFs.get().getPath(path.replace(".java", ".class"));

				if (inputPath != null && Files.notExists(inputPath)) {
					LOGGER.info("Discarding forge source file {} as it does not exist in the input jar", path);
					return false;
				}

				return !path.contains("$");
			}, (path, bytes) -> {
				Path fsPath = outputFs.get().getPath(path);

				if (fsPath.getParent() != null) {
					try {
						Files.createDirectories(fsPath.getParent());
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}

				taskCompleter.add(() -> {
					LOGGER.info("Added forge source file {}", path);
					Files.write(fsPath, bytes, StandardOpenOption.CREATE);
				});
			}, usePreExtractedSources);

			taskCompleter.completeToleratingFailures("forge source files for " + sourcesJar);
		}
	}

	/**
	 * 产出 Forge 源码，逐个交给 {@code consumer}.
	 *
	 * @param classFilter 源码路径过滤器
	 * @param consumer 消费源码的处理器
	 * @param usePreExtractedSources 是否允许消费预解压目录。允许时由调用方保证生产者任务已在本构建执行过，
	 *         否则目录可能是上一次构建的遗留物（本方法只检查目录是否存在，无法判断它属于哪个 Forge 版本）。
	 */
	private void provideForgeSources(Predicate<String> classFilter, BiConsumer<String, byte[]> consumer, boolean usePreExtractedSources) throws IOException {
		List<Path> forgeInstallerSources = new ArrayList<>();

		for (File file : getOptions().getForgeSourceJars()) {
			forgeInstallerSources.add(file.toPath());
			LOGGER.info("Found forge source jar: {}", file);
		}

		LOGGER.lifecycle(":found {} forge source jars", forgeInstallerSources.size());
		Map<String, byte[]> forgeSources;

		final List<Path> extractedDirectories = usePreExtractedSources
				? getOptions().getForgeSourceDirectories().getFiles().stream()
						.map(File::toPath)
						.filter(Files::isDirectory)
						.toList()
				: List.of();

		if (!extractedDirectories.isEmpty()) {
			// 消费声明式解压任务的输出目录：归档解压已被 Gradle 缓存，这里只做目录遍历。
			LOGGER.lifecycle(":using {} pre-extracted forge source directories", extractedDirectories.size());
			forgeSources = readExtractedSources(extractedDirectories);
		} else {
			if (usePreExtractedSources) {
				LOGGER.info("No pre-extracted forge source directory found, falling back to extracting the source jars");
			} else {
				LOGGER.info("Reading forge sources from the source jars instead of the pre-extracted directories");
			}

			forgeSources = extractSources(forgeInstallerSources);
		}

		forgeSources.keySet().removeIf(classFilter.negate());
		LOGGER.lifecycle(":extracted {} forge source classes", forgeSources.size());

		if (getOptions().getSourceRemapperService().isPresent()) {
			try (var tempFiles = new TempFiles()) {
				remapSources(tempFiles, forgeSources);
			}
		}

		forgeSources.forEach(consumer);
	}

	private void remapSources(TempFiles tempFiles, Map<String, byte[]> sources) throws IOException {
		Path tmpInput = tempFiles.file("tmpInputForgeSources", ".jar");
		Files.delete(tmpInput);
		Path tmpOutput = tempFiles.file("tmpInputForgeSources", ".jar");
		Files.delete(tmpOutput);

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(tmpInput, true)) {
			// 这里必须致命：tmpInput 是重映射器的输入，少一个条目会让重映射结果整体不可信。
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
				Path path = delegate.get().getPath(entry.getKey());

				if (path.getParent() != null) {
					Files.createDirectories(path.getParent());
				}

				taskCompleter.add(() -> {
					Files.write(path, entry.getValue(), StandardOpenOption.CREATE);
				});
			}

			taskCompleter.complete();
		}

		final PrintStream out = System.out;
		final PrintStream err = System.err;
		final boolean verboseStderr = getOptions().getShouldShowVerboseStderr().get();

		try {
			if (!verboseStderr) {
				System.setOut(new PrintStream(NullOutputStream.INSTANCE));
				System.setErr(new PrintStream(NullOutputStream.INSTANCE));
			}

			final SourceRemapperService remapperService = getServiceFactory().get(getOptions().getSourceRemapperService());
			remapperService.remapSourcesJar(tmpInput, tmpOutput);
		} finally {
			if (!verboseStderr) {
				System.setOut(out);
				System.setErr(err);
			}
		}

		final AtomicInteger failedToRemap = new AtomicInteger();

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getReadOnlyJarFileSystem(tmpOutput)) {
			// best-effort：「重映射后消失」与「读不回来」都属于个别源文件的问题，
			// 少几个文件不影响整体源码包的有效性。
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter().tolerateFailures();

			for (Map.Entry<String, byte[]> entry : new HashSet<>(sources.entrySet())) {
				taskCompleter.add(() -> {
					Path path = delegate.get().getPath(entry.getKey());

					if (Files.exists(path)) {
						sources.put(entry.getKey(), Files.readAllBytes(path));
					} else {
						LOGGER.info("Forge source {} did not survive remapping, skipping it", entry.getKey());
						sources.remove(entry.getKey());
						failedToRemap.incrementAndGet();
					}
				});
			}

			taskCompleter.completeToleratingFailures("remapped forge source files");
		}

		if (failedToRemap.get() > 0) {
			// 少数源码（例如仅含注解、或 Mercury 无法重写的生成类）可能在重映射后被丢弃。
			// 这类文件不进最终源码包即可，若把它们当成致命错误会让整个 genSources 失败。
			LOGGER.warn("{} forge source files did not survive remapping and were skipped", failedToRemap.get());
		}
	}

	private static Map<String, byte[]> extractSources(List<Path> forgeInstallerSources) throws IOException {
		Map<String, byte[]> sources = new ConcurrentHashMap<>();
		// best-effort：逐个源文件读取，个别文件读不出来只应该少一个源文件。
		// 注意 onComplete 中关闭 jar 文件系统的失败依然是致命的，见 completeToleratingFailures。
		ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter().tolerateFailures();

		for (Path path : forgeInstallerSources) {
			FileSystemUtil.Delegate system = FileSystemUtil.getReadOnlyJarFileSystem(path);
			taskCompleter.onComplete(stopwatch -> system.close());

			for (Path filePath : (Iterable<? extends Path>) Files.walk(system.get().getPath("/"))::iterator) {
				if (Files.isRegularFile(filePath) && filePath.getFileName().toString().endsWith(".java")) {
					taskCompleter.add(() -> sources.put(filePath.toString(), Files.readAllBytes(filePath)));
				}
			}
		}

		taskCompleter.completeToleratingFailures("forge source files in the source jars");
		return sources;
	}

	/**
	 * 从声明式解压任务的输出目录读取 Forge 源码.
	 *
	 * <p>键的形态必须与归档内路径一致（以 {@code /} 开头、使用 {@code /} 分隔），
	 * 因为下游是按 jar 文件系统路径消费这些键的；这里显式归一化，避免平台分隔符差异。
	 */
	static Map<String, byte[]> readExtractedSources(List<Path> extractedDirectories) throws IOException {
		Map<String, byte[]> sources = new ConcurrentHashMap<>();
		// best-effort：与 extractSources 同理，个别源文件读不出来不应该让整个 genSources 失败。
		ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter().tolerateFailures();

		for (Path directory : extractedDirectories) {
			// Files.walk 持有目录句柄，必须显式关闭（这里用 try-with-resources），否则每次调用都会泄漏一个句柄。
			try (Stream<Path> stream = Files.walk(directory)) {
				for (Path filePath : (Iterable<? extends Path>) stream::iterator) {
					if (!Files.isRegularFile(filePath) || !filePath.getFileName().toString().endsWith(".java")) {
						continue;
					}

					final String key = "/" + directory.relativize(filePath).toString().replace(File.separatorChar, '/');
					taskCompleter.add(() -> sources.put(key, Files.readAllBytes(filePath)));
				}
			}
		}

		taskCompleter.completeToleratingFailures("pre-extracted forge source files");
		return sources;
	}
}
