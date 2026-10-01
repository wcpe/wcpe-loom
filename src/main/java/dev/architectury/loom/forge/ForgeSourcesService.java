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
import java.util.StringJoiner;
import java.util.TreeMap;
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
import net.fabricmc.loom.util.Checksum;
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

	/**
	 * 「配置期 Forge 源码注入因 named MC jar 未就位而整批跳过」的日志前缀.
	 *
	 * <p>公开是为了让测试按同一份字面量断言：跳过是「输入尚未产出」时的**预期行为**
	 * （见 {@link #addForgeSourcesDuringProjectConfiguration(Project, ServiceFactory)}），
	 * 但它是静默损坏的反面——必须能从构建日志里确认它确实发生了。
	 */
	public static final String SKIPPED_LOG_MARKER = "Skipping configuration-phase forge sources injection";

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
					// 走 getMinecraftJarsCollection：登记了任务产出时它会携带产出任务，裸 Path 列表不会
					sro.getClasspath().from(extension.getMinecraftJarsCollection(sourceNamespace));
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

		// ── 冷缓存闸门：named MC jar 还没落位时整批跳过，一个文件都不写 ──
		// 本方法在**配置期**被调用，而它要读 named MC jar 的**内容**做类过滤（见 addForgeSources：
		// 源码是否保留取决于同名 class 是否存在于该 jar）。改造后 named MC jar 由
		// RemapMinecraftTask / ProcessMinecraftJarTask 在**执行期**落位，
		// 配置期只保证「产出任务已登记」，产物本身要等任务跑完。
		//
		// 为什么不能像原来那样直接往下走：拿不到输入 jar 时这条路径会写出「一个不包含任何 Forge 源码，
		// 或只包含错误子集」的 *-sources.jar，而下面的 Files.exists(sourcesJar) 守卫会让后续每次构建都
		// 认为「已经注入过」而不再重试——错误产物就长期留在那里，正是静默损坏。
		//
		// 跳过不会丢功能：注入 Forge 源码在执行期还有一条路径，且那条路径的输入是任务输入
		// （genSources 在 runDecompileJob 里用 classes jar 调 addForgeSources，见 GenerateSourcesTask）。
		// 本次不写任何产物，产物就位后的下一次配置期调用会自动重做。
		if (Files.notExists(minecraftJar)) {
			LOGGER.warn(SKIPPED_LOG_MARKER + ": minecraft jar {} is not available yet, so the forge sources cannot be "
					+ "filtered by its classes. These jars are produced by tasks at execution time "
					+ "(RemapMinecraftTask / ProcessMinecraftJarTask) and cannot be read while configuring. "
					+ "Nothing is written to {} this time; the sources jar is populated by genSources at execution "
					+ "time instead, and this step reruns automatically once the jar exists.",
					minecraftJar, sourcesJar);
			return;
		}

		if (!Files.exists(sourcesJar)) {
			final ForgeSourcesService service = serviceFactory.get(createOptions(project));
			// 配置期必须走归档路径：此时 extractForgeSources 还没有在本构建执行过（它是 genSources 的依赖），
			// build/loom/forgeSources 里若有内容，只可能是上一次构建、甚至另一个 Forge 版本遗留的目录。
			// 直接消费它会把旧版本的源码静默写进新生成的 *-sources.jar。
			service.addForgeSources(minecraftJar, sourcesJar, false);
		}
	}

	/**
	 * {@return 注入输入（Forge 源码包、预解压目录与源码重映射用的映射文件）的指纹，供反编译缓存键覆盖注入}
	 *
	 * <p>注入发生在缓存条目写出<strong>之前</strong>，也就是说缓存条目里存的是**注入后**的源码；
	 * 缓存键因此必须覆盖注入的输入。否则「换了 Forge 源码、而类字节没变」时缓存会全命中：
	 * 任务因输入变化确实重跑了，产物却与改动前逐字节相同，新源码被静默忽略。
	 *
	 * <p>三类输入都要计入：执行期优先读 {@link Options#getForgeSourceDirectories()}（声明式解压的产物），
	 * 目录不存在或为空时才回退到 {@link Options#getForgeSourceJars()}；两者都可能单独变化，
	 * 只记一个就会让另一个的变更失效。第三类是 {@link Options#getSourceRemapperService()} 用的映射
	 * （生产命名空间 → named，Forge 下即 srg → named）：注入的是**重映射之后**的源码文本，
	 * 同一份 Forge 源文件在这份映射变化后就是另一段文本。
	 *
	 * <p>映射同样只按**内容**入键（见 {@link #remapMappingsFingerprint()}），路径与文件名都不进键。
	 */
	public String getSourcesCacheKey() {
		final StringJoiner joiner = new StringJoiner(",");

		for (File file : getOptions().getForgeSourceJars()) {
			if (file.isFile()) {
				joiner.add(file.getName() + "=" + Checksum.of(file).sha256().hex());
			}
		}

		for (File directory : getOptions().getForgeSourceDirectories()) {
			if (directory.isDirectory()) {
				joiner.add(directory.getName() + "=" + fingerprint(directory.toPath()));
			}
		}

		joiner.add("remapMappings=" + remapMappingsFingerprint());

		return joiner.toString();
	}

	/**
	 * {@return 注入的 Forge 源码在重映射时所用映射文件的**内容**指纹}.
	 *
	 * <p>两类情况都返回空串：不做重映射（{@link Options#getSourceRemapperService()} 为空，即 unobfuscated 形态），
	 * 以及拿不到该文件。前者本来就没有这份输入；后者在真正重映射时会以「读映射失败」显式报错
	 * （{@code MappingsService} 读该文件），不会静默产出，因此让两者共用同一个空分量不引入静默失效。
	 * 空分量**仍然占位**：这样「本来要重映射、后来不重映射」（以及反向变化）都会改键，
	 * 而不是让按另一种语义写出的缓存条目继续被复用。
	 *
	 * <p>只取内容、不取路径：映射文件落在按 mappings 标识分目录的共享工作目录里，
	 * 路径变化（换 daemon、换工作树）不该让缓存失效，内容变化则必须让缓存失效。
	 */
	private String remapMappingsFingerprint() {
		if (getOptions().getSourceRemapperService().isPresent()) {
			final File mappings = getOptions().getSourceRemapperService().get()
					.getMappings().get().getMappingsFile().get().getAsFile();

			if (mappings.isFile()) {
				return Checksum.of(mappings).sha256().hex();
			}
		}

		return "";
	}

	/** {@return 目录内容的指纹：按相对路径排序后逐个取内容哈希，与绝对路径无关}. */
	private static String fingerprint(Path directory) {
		final TreeMap<String, String> hashes = new TreeMap<>();

		try (Stream<Path> stream = Files.walk(directory)) {
			for (Path path : (Iterable<? extends Path>) stream::iterator) {
				if (!Files.isRegularFile(path)) {
					continue;
				}

				final String relative = directory.relativize(path).toString().replace(File.separatorChar, '/');
				hashes.put(relative, Checksum.of(path).sha256().hex());
			}
		} catch (IOException e) {
			// 读不出输入就**不能**退化成「指纹为空」：那等于让缓存键看不见这份输入，正是要修的静默失效。
			throw new UncheckedIOException("Failed to fingerprint forge sources directory: " + directory, e);
		}

		final StringJoiner joiner = new StringJoiner(",");
		hashes.forEach((relative, hash) -> joiner.add(relative + "=" + hash));
		return Checksum.of(joiner.toString()).sha256().hex();
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
		// 输入 jar 只读打开：它只被用来查「同名 class 是否存在」。原来用 create=true 打开会在输入缺失时
		// **就地造出一个空 jar**——若那个路径正好是 maven 构件路径（配置期调用就是这样），
		// 就会凭空留下一份坏产物，且它看起来「存在」，足以骗过后续的存在性判定；同时过滤会因
		// 「所有 class 都不存在」而丢掉全部 Forge 源码，写出一份内容错误的源码包且不报错。
		// 只读打开在文件缺失时直接抛 NoSuchFileException，失败是可见的。
		try (FileSystemUtil.Delegate inputFs = minecraftJar == null ? null : FileSystemUtil.getReadOnlyJarFileSystem(minecraftJar);
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
