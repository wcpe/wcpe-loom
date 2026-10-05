/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.task.service;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.extension.RemapperExtensionHolder;
import net.fabricmc.loom.task.AbstractRemapJarTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.loom.util.ZipFsCloseRaceTestHook;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.kotlin.KotlinClasspathService;
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public class TinyRemapperService extends Service<TinyRemapperService.Options> implements TinyRemapperServiceInterface, Closeable {
	public static final ServiceType<Options, TinyRemapperService> TYPE = new ServiceType<>(Options.class, TinyRemapperService.class);

	public interface Options extends Service.Options {
		@Input
		Property<String> getFrom();
		@Input
		Property<String> getTo();
		@Nested
		ListProperty<MappingsService.Options> getMappings();
		@Input
		Property<Boolean> getUselegacyMixinAP();
		@Nested
		ListProperty<MixinAPMappingService.Options> getMixinApMappings();
		@Nested
		@Optional
		Property<KotlinClasspathService.Options> getKotlinClasspathService();
		@Classpath
		ConfigurableFileCollection getClasspath();
		@Input
		ListProperty<String> getKnownIndyBsms();
		@Input
		ListProperty<RemapperExtensionHolder> getRemapperExtensions();
	}

	public static Provider<Options> createOptions(AbstractRemapJarTask remapJarTask) {
		final Project project = remapJarTask.getProject();
		return TYPE.create(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);
			final ConfigurationContainer configurations = project.getConfigurations();
			final boolean legacyMixin = extension.getMixin().getUseLegacyMixinAp().get();
			final FileCollection classpath = remapJarTask.getClasspath()
					.minus(configurations.getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES))
					.minus(configurations.getByName(Constants.Configurations.MINECRAFT_RUNTIME_LIBRARIES));

			options.getFrom().set(remapJarTask.getSourceNamespace());
			options.getTo().set(remapJarTask.getTargetNamespace());
			options.getMappings().add(MappingsService.createForRemapTask(remapJarTask));

			if (legacyMixin) {
				options.getMixinApMappings().set(MixinAPMappingService.createOptions(project, options.getFrom(), options.getTo().map(to -> IntermediaryNamespaces.replaceMixinIntermediaryNamespace(project, to))));
			}

			options.getUselegacyMixinAP().set(legacyMixin);
			options.getKotlinClasspathService().set(KotlinClasspathService.createOptions(project));
			options.getClasspath().from(classpath);
			options.getKnownIndyBsms().set(extension.getKnownIndyBsms().get().stream().sorted().toList());
			options.getRemapperExtensions().set(extension.getRemapperExtensions());
		});
	}

	public static Provider<Options> createSimple(Project project, Provider<String> from, Provider<String> to, ClasspathLibraries classpathLibraries) {
		return TYPE.create(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);
			final FileCollection classpath = getRemapClasspath(project, from, classpathLibraries);

			options.getFrom().set(from);
			options.getTo().set(to);
			options.getMappings().add(MappingsService.createOptionsWithProjectMappings(project, options.getFrom(), options.getTo()));
			options.getUselegacyMixinAP().set(true);
			options.getClasspath().from(classpath);
			options.getKnownIndyBsms().set(extension.getKnownIndyBsms().get().stream().sorted().toList());
			options.getRemapperExtensions().set(extension.getRemapperExtensions());
		});
	}

	/**
	 * 与 {@link #createSimple(Project, Provider, Provider, ClasspathLibraries)} 相同，但 classpath 由调用方显式给出.
	 *
	 * <p>供「产物由任务产出」的场景使用：此时的 classpath 需要接线到上游任务的输出
	 * （一个由 {@code Provider} 支撑的 {@link FileCollection}），而不是从
	 * {@code extension.getMinecraftJars(...)} 读配置期已经落盘的文件。
	 *
	 * <p>其余输入仍来自项目模型——它们是纯值（映射配置、已知 BSM、remapper 扩展），
	 * 在配置期读取是允许的，且会被序列化进任务的输入。
	 */
	public static Provider<Options> createSimple(Project project, Provider<String> from, Provider<String> to, FileCollection classpath) {
		return TYPE.create(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			options.getFrom().set(from);
			options.getTo().set(to);
			options.getMappings().add(MappingsService.createOptionsWithProjectMappings(project, options.getFrom(), options.getTo()));
			options.getUselegacyMixinAP().set(true);
			options.getClasspath().from(classpath);
			options.getKnownIndyBsms().set(extension.getKnownIndyBsms().get().stream().sorted().toList());
			options.getRemapperExtensions().set(extension.getRemapperExtensions());
		});
	}

	private static FileCollection getRemapClasspath(Project project, Provider<String> from, ClasspathLibraries classpathLibraries) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final ConfigurationContainer configurations = project.getConfigurations();

		if (from.get().equals(MappingsNamespace.INTERMEDIARY.toString())) {
			// 走 getMinecraftJarsCollection：登记了任务产出时它会携带产出任务
			ConfigurableFileCollection files = project.files(extension.getMinecraftJarsCollection(MappingsNamespace.INTERMEDIARY));

			if (classpathLibraries == ClasspathLibraries.INCLUDE) {
				files = files.from(configurations.named(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
			}

			return files;
		}

		if (classpathLibraries == ClasspathLibraries.INCLUDE) {
			return project.files(configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME));
		}

		return project.files(configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
				.minus(project.files(configurations.named(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES)))
				.minus(project.files(configurations.named(Constants.Configurations.MINECRAFT_RUNTIME_LIBRARIES)));
	}

	public enum ClasspathLibraries {
		/**
		 * Default, in most cases the Minecraft libraries are not required as they are not obfuscated and do not need to be queried.
		 */
		EXCLUDE,

		/**
		 * Uses more memory, but provides a complete index of all the classes within the libraries.
		 */
		INCLUDE
	}

	private TinyRemapper tinyRemapper;
	@Nullable
	private KotlinRemapperClassloader kotlinRemapperClassloader;
	// zipfs 毒化诊断（JDK-8291712）必须让用户看见：Gradle 默认可见级别是 LIFECYCLE，
	// 用 SLF4J 的 info/warn 会被默认控制台级别吞掉。
	private static final Logger LOGGER = Logging.getLogger("loom_zipfs");
	/** zipfs 实现类的全限定名：NPE 栈里出现它，才说明空指针抛在 zipfs 内部（见 {@link #isClosedZipFileSystemNullPointer}）. */
	private static final String ZIP_FILE_SYSTEM_CLASS = "jdk.nio.zipfs.ZipFileSystem";
	/** 关闭 zipfs 后 {@code ZipFileSystem.close()} 会置空、随后在目录遍历时被解引用的字段名. */
	private static final String ZIP_FS_NULLED_FIELD = "inodes";
	private final Map<String, InputTag> inputTagMap = new HashMap<>();
	private final HashSet<Path> classpath = new HashSet<>();
	// 自愈产生的 classpath 私有快照：随着本服务一起结束（见 close()），不留给后续构建
	private final Set<Path> snapshots = new HashSet<>();
	// Set to true once remapping has started, once set no inputs can be read.
	private boolean isRemapping = false;

	public TinyRemapperService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
		tinyRemapper = createTinyRemapper();
		readClasspath();
	}

	private TinyRemapper createTinyRemapper() {
		TinyRemapper.Builder builder = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE)
				.withKnownIndyBsm(Set.copyOf(getOptions().getKnownIndyBsms().get()));

		for (MappingsService.Options options : getOptions().getMappings().get()) {
			MappingsService mappingsService = getServiceFactory().get(options);
			builder.withMappings(mappingsService.getMappingsProvider());
		}

		if (!getOptions().getUselegacyMixinAP().get()) {
			builder.extension(new MixinExtension());
		}

		if (getOptions().getKotlinClasspathService().isPresent()) {
			KotlinClasspathService kotlinClasspathService = getServiceFactory().get(getOptions().getKotlinClasspathService());
			kotlinRemapperClassloader = KotlinRemapperClassloader.create(kotlinClasspathService);
			builder.extension(kotlinRemapperClassloader.getTinyRemapperExtension());
		}

		for (RemapperExtensionHolder holder : getOptions().getRemapperExtensions().get()) {
			holder.apply(builder, getOptions().getFrom().get(), getOptions().getTo().get());
		}

		if (getOptions().getUselegacyMixinAP().get()) {
			for (MixinAPMappingService.Options options : getOptions().getMixinApMappings().get()) {
				MixinAPMappingService mixinAPMappingService = getServiceFactory().get(options);
				IMappingProvider provider = mixinAPMappingService.getMappingsProvider();

				if (provider != null) {
					builder.withMappings(provider);
				}
			}
		}

		return builder.build();
	}

	public InputTag getOrCreateTag(Path file) {
		InputTag tag = inputTagMap.get(file.toAbsolutePath().toString());

		if (tag == null) {
			tag = tinyRemapper.createInputTag();
			inputTagMap.put(file.toAbsolutePath().toString(), tag);
		}

		return tag;
	}

	@Override
	public TinyRemapper getTinyRemapperForRemapping() {
		isRemapping = true;
		return Objects.requireNonNull(tinyRemapper, "Tiny remapper has not been setup");
	}

	@Override
	public TinyRemapper getTinyRemapperForInputs() {
		if (isRemapping) {
			throw new IllegalStateException("Cannot read inputs as remapping has already started");
		}

		return tinyRemapper;
	}

	private void readClasspath() {
		List<Path> toRead = new ArrayList<>();

		for (File file : getOptions().getClasspath().getFiles()) {
			Path path = file.toPath();

			if (classpath.contains(path) || Files.notExists(path)) {
				continue;
			}

			toRead.add(path);
			classpath.add(path);
		}

		if (toRead.isEmpty()) {
			return;
		}

		// 测试专用注入点：不设 loom.test.zipfs.closeRaceEntry 时是空操作，
		// 用于把「读取期间共享实例被并发持有者关闭」这一竞态变成可确定性触发的场景（见该类注释）
		ZipFsCloseRaceTestHook.armForRead(toRead);

		try {
			readIntoRemapper(toRead.toArray(Path[]::new));
		} catch (RuntimeException e) {
			// 判据①（形态）：实例已关闭的「正面证据」齐全时，自愈路径确定无歧义，不必多走一次重读
			if (isClosedFileSystemFailure(e)) {
				if (recoverClasspathRead(e, toRead)) {
					return;
				}

				throw diagnoseClasspathReadFailure(e, toRead);
			}

			// 判据②（行为）：形态被 JVM 抹掉时（见 containsNullPointer）不再猜形态，改问「重读治不治得好」
			if (containsNullPointer(e) && recoverClasspathRead(e, toRead)) {
				// 重读治好 ⇒ 确实是「共享实例被并发关闭」：那样新开的实例是健康的，重读本就该成功
				return;
			}

			// 与 zipfs 状态无关的失败，以及「重读仍然以同样方式失败」的 NPE：原样抛出原始失败，
			// 类型/消息/栈逐字不动（这里抛的就是上面捕获到的那个对象，因此不可能被改写）
			throw e;
		}
	}

	/**
	 * 把 classpath 条目读进 tiny-remapper 的唯一出口.
	 *
	 * <p>首次读取、逐条重读、快照重读都走这里：一是让「重读」与「首读」确实是同一条路线
	 * （行为判别验证的就是「同一条路线能不能治好」），二是给测试留一个统一的注入点
	 * （见 {@link ZipFsCloseRaceTestHook#throwIfReadFailureArmed()}，不设属性时是空操作）.
	 */
	private void readIntoRemapper(Path[] paths) {
		ZipFsCloseRaceTestHook.throwIfReadFailureArmed();
		tinyRemapper.readClassPath(paths);
	}

	/**
	 * 失败路径自愈：把读不到的 classpath 条目改从私有快照副本重读一次.
	 *
	 * <p>为什么必须有这一层：受害 jar 未必在 loom 的控制范围内——Gradle 带 {@code --refresh-dependencies}
	 * 跑时会重写 {@code modules-2} 里的依赖 jar（先删后写），文件在那段时间里从盘上消失；任何此刻持有它的
	 * ZipFileSystem 关闭时都摘不掉进程级登记簿里的条目（JDK-8291712），该路径于是在本 daemon 内永久变成
	 * 「一打开就是已关闭实例」。loom 改不了 Gradle 的写法，只能保证自己侧不再因此失败：把受害 jar
	 * 复制成同目录下的私有快照，再从快照读一次。快照与任何并发持有者无关，因此单次重试即可生效。
	 *
	 * <p>两层递进；健康构建一次都不会走到这里（只在 {@link #readClasspath()} 已经失败之后调用）：
	 * <ol>
	 *     <li>登记簿探测（零副作用）能确定受害者时，只给受害者做快照并整批重读一次，代价最小；</li>
	 *     <li>探测不出受害者时（失败发生的那一刻文件可能正被重写，登记簿条目此刻根本查不到），
	 *         退化为「逐条重读，谁报 ClosedFileSystemException 就给谁做快照」，覆盖任何形态的坏实例。</li>
	 * </ol>
	 *
	 * <p>重读是幂等的：tiny-remapper 的 {@code addClass} 用 {@code putIfAbsent} 记录类并只合并
	 * input tag，重复读到同一个类不会改变任何语义。
	 *
	 * <p><b>重读同时是行为判别的本体</b>：调用方在形态不可辨认时（见 {@link #containsNullPointer}）也走这里，
	 * 此时「某一条重读仍然以同样方式失败」就是判据本身——共享实例被并发关闭时新开的实例是健康的、重读会成功，
	 * 因此重读失败即说明这次失败与关闭竞态无关；本方法随即返回 {@code false}，
	 * 由调用方原样抛出<b>原始</b>失败（不掩盖真实缺陷，也不改写异常内容）。
	 *
	 * @param failure 触发自愈的原始失败（只用于日志，不影响自愈行为）
	 * @param toRead 本次要读取的全部 classpath 路径
	 * @return {@code true} 表示已自愈并读完；{@code false} 表示重读治不好它，调用方应照旧报错
	 */
	private boolean recoverClasspathRead(RuntimeException failure, List<Path> toRead) {
		// 走到自愈分支必须留下痕迹：真实形态（读取期间共享实例被并发持有者关闭）下，逐条重读通常一次就成功、
		// 不会再进快照分支；若只在这里静默恢复，这次「本该失败却成功了」就没有任何可检索的证据。
		// 一并打出失败形态：CFSE 与「目录遍历撞上已置空的 inodes」是同一竞态的两个落点，
		// 出问题时必须能从日志分辨到底命中了哪一个。
		if (isClosedFileSystemFailure(failure)) {
			LOGGER.warn("读取 remap classpath 命中「共享 zipfs 实例已被关闭」（{}），开始自愈重读：{}",
					describeFailure(failure), toRead);
		} else {
			// 形态不可辨认的入口（见 containsNullPointer）：此处不下结论——是不是关闭竞态，由下面的重读回答
			LOGGER.warn("读取 remap classpath 失败于形态不可辨认的 NPE（{}：JIT 的 fast throw 会抹掉消息与栈），"
							+ "开始自愈重读并按「能不能治好」判别：{}",
					describeFailure(failure), toRead);
		}

		final List<Path> knownVictims = new ArrayList<>();

		for (Path path : toRead) {
			if (FileSystemUtil.findClosedRegistryEntry(path) != null) {
				knownVictims.add(path);
			}
		}

		if (!knownVictims.isEmpty()) {
			LOGGER.warn("remap classpath 有 {} 条路径已被进程级 zipfs 登记簿毒化（JDK-8291712），改用私有快照重读：{}",
					knownVictims.size(), knownVictims);

			if (retryClasspathReadWithSnapshots(toRead, knownVictims)) {
				return true;
			}
		}

		// 逐条兜底：登记簿探测不出受害者时，仍然可能拿到「已关闭」的共享实例（例如失败瞬间文件正被重写）。
		// 此时重读本身既是探测也是修复：谁报 ClosedFileSystemException 就给谁做快照。
		for (Path path : toRead) {
			try {
				readIntoRemapper(new Path[] {path});
			} catch (RuntimeException e) {
				if (!isClosedFileSystemFailure(e)) {
					// 重读仍然以同样方式失败 ⇒ 它不是「共享实例被并发关闭」（那样新开的实例是健康的、重读会成功）：
					// 放弃自愈，由调用方原样抛出原始失败，绝不把真实缺陷掩盖成一次成功的重试
					LOGGER.warn("自愈重读 classpath 条目 {} 仍然失败（{}），无法认定为关闭竞态，放弃自愈并原样抛出原始失败",
							path, describeFailure(e), e);
					return false;
				}

				// 重读期间又撞上关闭竞态（另一条受害路径的持有者此刻才关闭，或本路径的快照还没建立）：
				// 这里把形态一并打出来，日志才能分辨命中的是 CFSE 还是目录遍历的 inodes NPE
				LOGGER.warn("自愈重读 classpath 条目 {} 时再次命中「共享 zipfs 实例已被关闭」（{}），改用私有快照重读",
						path, describeFailure(e));

				final Path snapshot = snapshotForRetry(path);

				if (snapshot == null || !readSnapshot(snapshot, path)) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * 用「受害者=私有快照、其余=原路径」重读整批 classpath.
	 *
	 * @param toRead 本批全部路径
	 * @param victims 已确认被毒化的路径（会被替换成快照）
	 * @return 是否成功读完
	 */
	private boolean retryClasspathReadWithSnapshots(List<Path> toRead, List<Path> victims) {
		final Map<Path, Path> substitutions = new HashMap<>();
		final List<Path> substituted = new ArrayList<>(toRead.size());

		try {
			for (Path path : toRead) {
				if (!victims.contains(path)) {
					substituted.add(path);
					continue;
				}

				final Path snapshot = snapshotForRetry(path);

				if (snapshot == null) {
					discardSnapshots(substitutions.values());
					return false;
				}

				substitutions.put(path, snapshot);
				substituted.add(snapshot);
			}

			readIntoRemapper(substituted.toArray(Path[]::new));
			return true;
		} catch (RuntimeException e) {
			LOGGER.warn("用私有快照重读 classpath 仍然失败，继续尝试逐条自愈", e);
			return false;
		}
	}

	/**
	 * 把受害者复制成同目录下的私有快照.
	 *
	 * <p>刻意放在原文件所在目录：与受害 jar 同一文件系统，复制不必跨盘；且该目录本来就是这些 jar 的家，
	 * 不会把大文件写进容量未知的系统临时目录。名字用 {@link AtomicFiles#tempSibling} 生成（含 UUID，
	 * 保留 {@code .jar} 扩展名），因此既不会与并发写入者撞名，也不会被误当成产物。
	 *
	 * <p>快照的生命周期与本次服务一致：成功读取后由 {@link #close()} 删除（tiny-remapper 的
	 * {@code ClassInstance} 已经把类字节读进了内存，但保留到服务结束更稳妥——classpath jar 里还可能有
	 * 非 class 条目会在后续阶段被读）。失败时立刻删除。
	 *
	 * @return 快照路径；无法复制（例如文件此刻又消失了）时返回 {@code null}
	 */
	private @Nullable Path snapshotForRetry(Path victim) {
		final Path snapshot = AtomicFiles.tempSibling(victim);

		try {
			Files.copy(victim, snapshot);
		} catch (IOException e) {
			LOGGER.warn("无法为 {} 建立私有快照，放弃自愈", victim, e);
			return null;
		}

		snapshots.add(snapshot);
		return snapshot;
	}

	/** 从快照读一次；失败则删除该快照并返回 false. */
	private boolean readSnapshot(Path snapshot, Path victim) {
		try {
			readIntoRemapper(new Path[] {snapshot});
			LOGGER.warn("已自愈：{} 被并发持有者关闭后在本 daemon 内无法再打开，本次改从其私有快照 {} 读取", victim, snapshot);
			return true;
		} catch (RuntimeException e) {
			LOGGER.warn("从 {} 的私有快照 {} 重读仍然失败", victim, snapshot, e);
			discardSnapshot(snapshot);
			return false;
		}
	}

	private void discardSnapshot(Path snapshot) {
		snapshots.remove(snapshot);

		try {
			Files.deleteIfExists(snapshot);
		} catch (IOException e) {
			LOGGER.warn("清理 classpath 快照 {} 失败", snapshot, e);
		}
	}

	private void discardSnapshots(Collection<Path> toDiscard) {
		for (Path path : new ArrayList<>(toDiscard)) {
			discardSnapshot(path);
		}
	}

	/**
	 * 把 classpath 读取失败归一化成可读的诊断信息.
	 *
	 * <p>tiny-remapper 的 {@code readClassPath} 内部走 {@code read(...).join()}，裸 join 不拆包，
	 * 于是本 daemon 里真正的问题（JDK-8291712：路径被进程级 zipfs 登记簿毒化，条目是某个
	 * 「已关闭」的 FileSystem）只会以一个没有路径信息的 {@code ClosedFileSystemException} 冒出来。
	 * 这里补上「是哪条 classpath 路径被毒化」这个关键事实。
	 *
	 * <p>走到这里说明 {@link #recoverClasspathRead} 的自愈也没成功（例如快照本身也读不了）。
	 *
	 * @param failure 原始失败
	 * @param toRead 本次要读取的 classpath 路径（毒化探测只对这些路径做）
	 * @return 原样返回非毒化失败；毒化失败则返回带明确诊断的异常
	 */
	private RuntimeException diagnoseClasspathReadFailure(RuntimeException failure, List<Path> toRead) {
		if (!isClosedFileSystemFailure(failure)) {
			// 与 zipfs 登记簿无关的失败：原样抛出，保持既有行为
			return failure;
		}

		List<Path> poisoned = new ArrayList<>();

		for (Path path : toRead) {
			if (FileSystemUtil.findClosedRegistryEntry(path) != null) {
				poisoned.add(path);
			}
		}

		String paths = poisoned.isEmpty()
				? "（登记簿里的死实例此刻无法定位：文件的真实路径查不到对应条目，通常是该 jar 又被替换过）"
				: poisoned.stream().map(Path::toString).collect(Collectors.joining("\n  - ", "\n  - ", ""));
		String message = """
				读取 remap classpath 失败：%s
				已被进程级 zipfs 登记簿毒化的路径：%s
				成因（JDK-8291712）：jar 在某次打开期间被删除/替换，ZipFileSystem.close() 抛 IOException 之前
				来不及把实例从登记簿摘除，登记簿里于是留下一个「已关闭」的死实例；此后 tiny-remapper 的
				URI 路线（FileSystemReference.openJar 先查登记簿）只会拿到这个死实例。
				自愈已尝试但未成功：本服务会先把受害 jar 复制成同目录私有快照再读一次；走到这里说明快照也读不了
				（通常是该 jar 此刻内容不完整），或失败根本不是毒化引起的。
				规避办法：不要用 --refresh-dependencies 与构建并发重写依赖 jar；确已中毒的 daemon 需重启
				（重跑 --no-daemon 亦可），残留登记簿条目不会自行消失。""".formatted(failure, paths);

		LOGGER.error(message, failure);
		return new FileSystemUtil.UnrecoverableZipException(message, failure);
	}

	/**
	 * 判断失败链里是否出现「FileSystem 已关闭」这类 zipfs 毒化症状.
	 *
	 * <p>这是<b>形态判据</b>：它成立时失败与关闭竞态的因果关系是确定的，因此调用方直接走自愈快路径、
	 * 失败时给出诊断。{@link #readClasspath}、{@link #recoverClasspathRead}、
	 * {@link #diagnoseClasspathReadFailure} 都走它，因此必须只认「确由关闭的 zipfs 引起」的失败：
	 * 判宽了会把真实缺陷当成关闭竞态掩盖掉，判窄了本可自愈的构建仍会失败。
	 *
	 * <p>只收两种形态，各自都是「实例已关闭」的正面证据，而不是启发式猜测：
	 * <ol>
	 *     <li>{@link ClosedFileSystemException}：zipfs 内部 {@code ensureOpen()} 的标准症状；</li>
	 *     <li>{@link #isClosedZipFileSystemNullPointer} 认可的那一种 NPE：目录遍历走到
	 *         {@code ZipFileSystem.isDirectory}，而该方法<b>没有</b> ensureOpen 检查，
	 *         关闭后直接撞上已被 {@code close()} 置空的 {@code inodes} 字段（见该方法说明）。</li>
	 * </ol>
	 *
	 * <p>本判据<b>不成立</b>的异常不会因此被当成「与 zipfs 无关」而直接抛出：NPE 还有一条行为判别入口
	 * （见 {@link #containsNullPointer}），因为同一个落点的形态可能已被 JIT 抹掉。判据不成立与
	 * 「行为判别也不成立」两条路合起来，才与只认 CFSE 时的行为逐字一致。
	 */
	private static boolean isClosedFileSystemFailure(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof ClosedFileSystemException || isClosedZipFileSystemNullPointer(t)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 「已关闭的 zipfs」在目录遍历时抛出的那一种 NPE.
	 *
	 * <p>形态：{@code FileTreeWalker} 每进一个目录先取属性（{@code readAttributes} 有 ensureOpen，
	 * 关闭后报 CFSE），紧接着调 {@code Files.newDirectoryStream}，后者最终走到
	 * {@code ZipFileSystem.isDirectory}；该方法只做 {@code beginRead() + getInode()}，
	 * 没有 ensureOpen，于是当实例已被关闭（{@code close()} 收尾把 {@code inodes} 置为 {@code null}）时，
	 * 在 {@code inodes.get(...)} 上抛出：
	 * <pre>NullPointerException: Cannot invoke "java.util.LinkedHashMap.get(Object)" because "this.inodes" is null</pre>
	 * 这与 CFSE 是同一个竞态（共享实例被并发持有者关闭）的另一种落点，只是落点没有 ensureOpen 保护；
	 * 目录越深、这种「属性已取到、正要列目录」的窗口被撞上的机会越多。
	 *
	 * <p><b>为什么这条能安全纳入自愈判据</b>：{@code ZipFileSystem.inodes} 只在构造期被填充、在
	 * {@code close()} 收尾时被置为 {@code null}（JDK 21 里只有这两处写），因此「{@code this.inodes} 是
	 * null」等价于「该实例已经被关闭」——它是关闭的<b>充分证据</b>，不是「大概率的 NPE」。
	 * 再加两条限定，确保不会把别的空指针当成关闭竞态：
	 * <ul>
	 *     <li>异常消息必须指名 {@code inodes} 字段（JDK 15+ 默认开启「有帮助的 NPE 消息」才会带上）；</li>
	 *     <li>栈里必须有 {@code jdk.nio.zipfs.ZipFileSystem} 的帧，证明这个空指针确实抛在 zipfs 内部，
	 *         而不是某段业务代码里恰好也叫 inodes 的字段。</li>
	 * </ul>
	 *
	 * <p><b>本判据不成立不代表「不是关闭竞态」</b>：长命 JVM 里同一条隐式空指针被反复抛出后，
	 * HotSpot 会改用预分配的「fast throw」NPE（消息为 {@code null}、栈长为 0），上面两条特征都取不到，
	 * 于是它既不属于本判据、也无法与被 JIT 抹掉形态的其它 NPE 区分。形态既然不可信，这类失败就改由
	 * {@link #containsNullPointer} + {@link #recoverClasspathRead} 的行为判别处理（重读治不好即原样抛出原始失败）。
	 * 本判据因此只用于「形态齐全 ⇒ 连诊断一起给出」，不再承担「唯一入口」的职责。
	 *
	 * @param failure 失败链上的一个异常
	 * @return 是否是「带关闭特征」的 zipfs 内部 NPE
	 */
	private static boolean isClosedZipFileSystemNullPointer(Throwable failure) {
		if (!(failure instanceof NullPointerException)) {
			return false;
		}

		final String message = failure.getMessage();

		if (message == null || !message.contains(ZIP_FS_NULLED_FIELD)) {
			return false;
		}

		for (StackTraceElement frame : failure.getStackTrace()) {
			if (ZIP_FILE_SYSTEM_CLASS.equals(frame.getClassName())) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 失败链里是否出现空指针：zipfs 关闭竞态的第二个落点，也是唯一「形态会被 JVM 抹掉」的落点.
	 *
	 * <p>{@code ZipFileSystem.isDirectory} 没有 ensureOpen 检查，实例关闭后它抛 NPE；而 HotSpot 在同一个
	 * 隐式空指针被反复抛出后会改用预分配的「fast throw」异常——消息为 {@code null}、栈长为 0，
	 * 于是 {@link #isClosedZipFileSystemNullPointer} 的两条特征都取不到，异常<b>长什么样</b>不再可用。
	 * 既然不可辨，就不再猜：NPE 这个类型是该落点的全部可辨信息，剩下的交给行为判别
	 * （{@link #recoverClasspathRead} 逐条重读，治好 ⇒ 确是「共享实例被并发关闭」，因为那种情况下
	 * 新开的实例是健康的、重读本就该成功；治不好 ⇒ 调用方原样抛出原始失败）。
	 *
	 * <p><b>为什么只放宽到 NPE、不放宽到「任意异常」</b>：能落进 zipfs 关闭竞态的异常只有两种
	 * （{@code ensureOpen} 的 {@link ClosedFileSystemException}、{@code isDirectory} 的 NPE），
	 * 只有后者的形态会被 JVM 优化掉；再放宽只会让与 zipfs 无关的真实缺陷也白跑一遍逐条重读，
	 * 换不来任何自愈机会。放宽的安全阀是行为判别本身的结论：重读治不好就<b>原样抛出原始失败</b>
	 * （类型、消息、栈都不动），因此这里的放宽不会掩盖任何真实缺陷。
	 */
	private static boolean containsNullPointer(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof NullPointerException) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 把失败链上「真正让判据成立」的那个异常描述成一行日志.
	 *
	 * <p>只用于日志：{@code readClassPath} 冒出来的常常是包装过的异常（{@code read(...).join()} 的结果），
	 * 打最外层看不出形态；这里优先取失败链上第一个被判据认可的异常，日志因此能直接分辨
	 * CFSE 与「目录遍历撞上已置空的 inodes」这两个落点。判据都不成立时（例如形态被 JIT 抹掉的 NPE）
	 * 退回最外层异常的 {@code toString()}，形态不可辨这件事本身就是日志要说的事实。
	 */
	private static String describeFailure(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof ClosedFileSystemException || isClosedZipFileSystemNullPointer(t)) {
				return t.toString();
			}
		}

		return failure.toString();
	}

	@Override
	public void close() throws IOException {
		if (tinyRemapper != null) {
			tinyRemapper.finish();
			tinyRemapper = null;
		}

		if (kotlinRemapperClassloader != null) {
			kotlinRemapperClassloader.close();
		}

		// 自愈快照的生命周期与本次服务一致：任务结束即删，不污染缓存目录
		discardSnapshots(snapshots);
	}
}
