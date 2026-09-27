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
		 * 仍以归档形态提供的 Forge 源码包，仅在未接入声明式解压任务时作为回退.
		 */
		@Optional
		@InputFiles
		@PathSensitive(PathSensitivity.NONE)
		ConfigurableFileCollection getForgeSourceJars();

		/**
		 * 由声明式解压任务预先展开的 Forge 源码目录.
		 *
		 * <p>含目录本身的构建依赖（{@code getBuiltBy()}），因此任务指纹会追踪生产者，
		 * 调用方无需额外声明 {@code dependsOn}。使用 {@link PathSensitivity#RELATIVE}
		 * 以“目录内容”语义参与输入哈希，避免缓存目录绝对路径变化引起无谓重跑。
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
			service.addForgeSources(minecraftJar, sourcesJar);
		}
	}

	public void addForgeSources(@Nullable Path minecraftJar, Path sourcesJar) throws IOException {
		try (FileSystemUtil.Delegate inputFs = minecraftJar == null ? null : FileSystemUtil.getJarFileSystem(minecraftJar, true);
				FileSystemUtil.Delegate outputFs = FileSystemUtil.getJarFileSystem(sourcesJar, true)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

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
			});

			taskCompleter.complete();
		}
	}

	private void provideForgeSources(Predicate<String> classFilter, BiConsumer<String, byte[]> consumer) throws IOException {
		List<Path> forgeInstallerSources = new ArrayList<>();

		for (File file : getOptions().getForgeSourceJars()) {
			forgeInstallerSources.add(file.toPath());
			LOGGER.info("Found forge source jar: {}", file);
		}

		LOGGER.lifecycle(":found {} forge source jars", forgeInstallerSources.size());
		Map<String, byte[]> forgeSources;

		final List<Path> extractedDirectories = getOptions().getForgeSourceDirectories().getFiles().stream()
				.map(File::toPath)
				.filter(Files::isDirectory)
				.toList();

		if (!extractedDirectories.isEmpty()) {
			// 优先消费声明式解压任务的输出目录：归档解压已被 Gradle 缓存，这里只做目录遍历。
			LOGGER.lifecycle(":using {} pre-extracted forge source directories", extractedDirectories.size());
			forgeSources = readExtractedSources(extractedDirectories);
		} else {
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
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

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

			taskCompleter.complete();
		}

		if (failedToRemap.get() > 0) {
			// 少数源码（例如仅含注解、或 Mercury 无法重写的生成类）可能在重映射后被丢弃。
			// 这类文件不进最终源码包即可，若把它们当成致命错误会让整个 genSources 失败。
			LOGGER.warn("{} forge source files did not survive remapping and were skipped", failedToRemap.get());
		}
	}

	private static Map<String, byte[]> extractSources(List<Path> forgeInstallerSources) throws IOException {
		Map<String, byte[]> sources = new ConcurrentHashMap<>();
		ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

		for (Path path : forgeInstallerSources) {
			FileSystemUtil.Delegate system = FileSystemUtil.getReadOnlyJarFileSystem(path);
			taskCompleter.onComplete(stopwatch -> system.close());

			for (Path filePath : (Iterable<? extends Path>) Files.walk(system.get().getPath("/"))::iterator) {
				if (Files.isRegularFile(filePath) && filePath.getFileName().toString().endsWith(".java")) {
					taskCompleter.add(() -> sources.put(filePath.toString(), Files.readAllBytes(filePath)));
				}
			}
		}

		taskCompleter.complete();
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
		ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

		for (Path directory : extractedDirectories) {
			for (Path filePath : (Iterable<? extends Path>) Files.walk(directory)::iterator) {
				if (!Files.isRegularFile(filePath) || !filePath.getFileName().toString().endsWith(".java")) {
					continue;
				}

				final String key = "/" + directory.relativize(filePath).toString().replace(File.separatorChar, '/');
				taskCompleter.add(() -> sources.put(key, Files.readAllBytes(filePath)));
			}
		}

		taskCompleter.complete();
		return sources;
	}
}
