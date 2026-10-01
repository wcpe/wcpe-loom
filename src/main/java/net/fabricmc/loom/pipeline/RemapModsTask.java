/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import dev.architectury.loom.accesstransformer.AtClassRemapper;
import dev.architectury.loom.forge.CoreModClassRemapper;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.configuration.mods.AccessWidenerAnalyzeVisitorProvider;
import net.fabricmc.loom.configuration.mods.AccessWidenerUtils;
import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.configuration.mods.JarSplitter;
import net.fabricmc.loom.configuration.mods.extension.ModProcessorExtension;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.core.ModJarPostProcess;
import net.fabricmc.loom.core.ModJarRemap;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.kotlin.KotlinClasspathService;
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * L3 流水线：把一个配置里的 mod jar 批量重映射到目标命名空间.
 *
 * <p>它取代 {@code ModConfigurationRemapper} 在配置期同步执行的 mod 重映射。与
 * {@link RemapMinecraftTask} 一起构成 L3 的切换对——二者必须同批接线，因为配置期的
 * mod 重映射要按路径读 mapped Minecraft jar，只切一个会立刻打断对方。
 *
 * <h2>为什么是「批量」而不是「一 jar 一任务」</h2>
 * mod 重映射需要**整组类型上下文**：字节码里的继承关系可能跨越多个 mod，逐个重映射会
 * 得到与既有实现不同的结果。因此这里沿用原实现的语义——先把所有输入加入 classpath，
 * 再逐个 apply。这也意味着不能把它拆成 Artifact Transform。
 *
 * <h2>产出落位与共享路径的所有权</h2>
 * 产物按 Maven 仓库布局写入**构建根下的共享仓库**（{@code .gradle/loom-cache/remapped_mods}：同一构建的
 * 所有项目、同一台机器的所有工作树共用同一个目录），路径由坐标与 cache key 决定。因此「每个项目各注册一份
 * 生产者」在这里是不成立的：同一条路径上只能有一个生产者，否则两个任务会写同一个文件（Gradle 9.5 对这种
 * 重叠既不报错也不排序，后完成的覆盖先完成的），而只要有消费方按该路径取产物，
 * 隐式依赖校验就会以「uses this output of task ... without declaring an explicit or implicit dependency」
 * 让整个构建失败——两个项目只是各自依赖自己那份产物时也会失败，因为冲突判定看的是**谁声明了该位置**。
 *
 * <p>唯一生产者由 {@link RemapMinecraftTaskRegistry#claimAll} 在同一构建内判定，且产物**逐条**声明：
 * 本任务声明的是「自己认领到的那些文件」（{@link #getOutputJars()}），绝不声明整个共享目录——声明目录会把
 * 别的项目、别的配置的产物一起纳入本任务的快照与清理范围。被别的项目认领的产物仍按完整批次读入
 * （{@link #getMods()} 不因认领结果被截断），因此类型上下文与「谁先登记」无关，每条产物的字节是确定的。
 *
 * <p>消费方按 {@code builtBy} 挂到**产出任务的任务路径**上（见 {@code RemappedModArtifacts}）：
 * 产出方可能是另一份 classloader 里的任务，任务路径是字符串，跨 classloader 可用。
 *
 * <h2>一条依赖可能有多条产物</h2>
 * 拆分依赖（{@code SplitModDependency}）的消费方读的是 {@code -common} / {@code -client} 两条带后缀的
 * 产物，而重映射产出的是整体 jar：这类 mod 的**两条**路径都要参与认领与声明，写出时先重映射出整体 jar，
 * 再拆成两半各自落位（见 {@link #publishSplit}）。只认领、只写出无后缀的那一条，消费方的两条路径就都成了
 * 没有生产者的悬空路径——构建全程不报错，症状是编译期少 jar。
 */
@CacheableTask
public abstract class RemapModsTask extends DefaultTask {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemapModsTask.class);

	/** 待重映射的 mod，各自携带自己的 Maven 坐标（决定产出路径）. */
	@Nested
	public abstract ListProperty<ModSpec> getMods();

	/**
	 * 重映射 classpath：其它 mod 的原始 jar，以及 mapped Minecraft jar.
	 *
	 * <p>必须包含整组输入的类型上下文，且不得包含本轮正在重映射的那些 jar 本身
	 * （它们由 {@link #getMods()} 提供）。
	 */
	@Classpath
	public abstract ConfigurableFileCollection getRemapClasspath();

	/** 映射服务配置；执行期据此取出映射树. */
	@Nested
	public abstract Property<TinyMappingsService.Options> getMappingsServiceOptions();

	/** 源命名空间（项目的生产命名空间）. */
	@Input
	public abstract Property<String> getSourceNamespace();

	/** 目标命名空间；mod 重映射固定为 {@code named}. */
	@Input
	public abstract Property<String> getTargetNamespace();

	/** 平台；用于读取各 jar 的 access widener. */
	@Input
	public abstract Property<ModPlatform> getPlatform();

	/**
	 * Kotlin 重映射支持；仅当项目应用了 Kotlin 插件时存在.
	 *
	 * <p>{@code Options} 本身就是一份任务输入（{@code @Classpath} 文件集 + {@code @Input} 版本号），
	 * 因此可直接作为嵌套输入携带，无需在配置期装配 kotlin 相关重映射。缺省表示项目不用 Kotlin。
	 */
	@Nested
	@Optional
	public abstract Property<KotlinClasspathService.Options> getKotlinOptions();

	/** 是否 Forge/NeoForge 系；决定是否执行 AT / CoreMod 加工. */
	@Input
	public abstract Property<Boolean> getForgeLike();

	/** 是否 NeoForge；决定 AT 是全量映射还是只映射类名. */
	@Input
	public abstract Property<Boolean> getNeoForge();

	/** Forge 运行时是否使用 Mojang 命名空间；决定 CoreMod 的映射基准. */
	@Input
	public abstract Property<Boolean> getRuntimeMojang();

	/** 已知的 indy BSM 集合（含各 mod 元数据里声明的）. */
	@Input
	public abstract SetProperty<String> getKnownIndyBsms();

	/**
	 * 本任务写出（也因此**声明**）的产物：批次里认领到的那部分，逐条文件.
	 *
	 * <p>刻意不是 {@code @OutputDirectory}：产出根是构建内所有项目共用的共享仓库，声明整个目录会把别人的
	 * 产物也算成自己的输出，于是「某个文件该由谁产出」这件事立刻变成冲突（见类注释）。同时它也是执行期
	 * 「哪些 mod 要写出」的唯一判据——写出集合与声明集合必须是同一个，才不会出现写了没声明的文件。
	 */
	@OutputFiles
	public abstract ConfigurableFileCollection getOutputJars();

	@TaskAction
	public void remap() throws IOException {
		final List<ModSpec> mods = getMods().get();

		if (mods.isEmpty()) {
			return;
		}

		try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
			final TinyMappingsService.Options mappingsOptions = getMappingsServiceOptions().get();
			final TinyMappingsService mappingsService = serviceFactory.get(mappingsOptions);

			remapAll(mods, ownedOutputs(), mappingsService.getMappingTree(), serviceFactory);
		}
	}

	/** {@return 本任务写出（也因此声明）的产物} 声明集合与写出集合是同一处：见 {@link #getOutputJars()}. */
	private Set<Path> ownedOutputs() {
		final Set<Path> owned = new HashSet<>();

		for (File file : getOutputJars().getFiles()) {
			owned.add(file.toPath().toAbsolutePath().normalize());
		}

		return owned;
	}

	/** {@return 该 mod 的产出文件（已归一化）} 与消费方按 maven 助手算出的路径是同一条，见 {@link ModSpec#getOutputJar()}. */
	private static Path outputPath(ModSpec mod) {
		return mod.getOutputJar().get().getAsFile().toPath().toAbsolutePath().normalize();
	}

	/** {@return 该 mod 的 client 半落位（已归一化）} 非拆分依赖没有这一半，返回 {@code null}，见 {@link ModSpec#getSplitClientJar()}. */
	private static @Nullable Path splitClientPath(ModSpec mod) {
		final RegularFile clientJar = mod.getSplitClientJar().getOrNull();
		return clientJar == null ? null : clientJar.getAsFile().toPath().toAbsolutePath().normalize();
	}

	/**
	 * {@return 本任务是否认领了该 mod 的产物}.
	 *
	 * <p>未认领的 mod 由别的项目的任务产出（见类注释），本任务既不写也不声明它。
	 *
	 * <p>拆分依赖有两条产物，它们由 {@code claimAll} 的**同一次**调用认领，因此只会同时归某个任务
	 * 或同时不归。若这里只看到 client 半归本任务，说明认领表被按单条路径改过：那时既不能把整体 jar
	 * 写上 common 落位（那是别人的产物），也无法凭空得到 client 半，只能失败而不是静默写错。
	 */
	private static boolean owns(Path output, @Nullable Path splitClient, Set<Path> owned) {
		if (owned.contains(output)) {
			return true;
		}

		if (splitClient != null && owned.contains(splitClient)) {
			throw new IllegalStateException(
					"拆分依赖的产物被拆开认领：本任务认领了 %s 但未认领同一条依赖的 %s，无法产出".formatted(splitClient, output));
		}

		return false;
	}

	/**
	 * 把整体 jar 拆成 common/client 两半，各自原子落位到消费方要读的路径.
	 *
	 * <p>为什么必须拆：拆分依赖（{@code SplitModDependency}）的消费方读的是带 {@code -common} /
	 * {@code -client} 后缀的两条产物，而重映射产出的是整体 jar——只写出整体 jar 等于把消费方的两条
	 * 路径都留成没有生产者的悬空路径（构建不会报错，编译期少 jar 才是症状）。
	 *
	 * <p>{@code commonJar} 里的文件此刻是整体 jar，拆分后被 common 半原子覆盖：整个过程只有本任务
	 * 在写这条路径（唯一生产者），消费方又都依赖本任务，因此没有谁能看到中间态；即便中途失败，
	 * 这次执行也不会被记为成功，下一次会重新写出。
	 *
	 * <p>两半先写**同目录**的临时文件再原子 move：跨文件系统时 move 不是原子操作。
	 */
	private static void publishSplit(Path commonJar, Path clientJar, Set<Path> owned) throws IOException {
		final Path commonTemp = temporaryJar(commonJar);
		final Path clientTemp = temporaryJar(clientJar);

		try {
			new JarSplitter(commonJar).split(commonTemp, clientTemp);

			// 只落位本任务声明（也因此拥有）的那些：声明集合与写出集合必须是同一个
			if (owned.contains(commonJar)) {
				publishAtomically(commonTemp, commonJar);
			}

			if (owned.contains(clientJar)) {
				publishAtomically(clientTemp, clientJar);
			}
		} finally {
			Files.deleteIfExists(commonTemp);
			Files.deleteIfExists(clientTemp);
		}
	}

	/** {@return 与最终产物同目录的临时 jar 路径} 同目录是为了让落位能用原子 move（见 {@link #publishAtomically}）. */
	private static Path temporaryJar(Path target) throws IOException {
		final Path directory = target.getParent();
		Files.createDirectories(directory);
		// 与最终产物同目录：落位用 move，跨文件系统时不是原子操作
		return directory.resolve("%s.part-%s.jar".formatted(target.getFileName(), UUID.randomUUID()));
	}

	/** 原子落位：读方要么看到落位前的文件，要么看到完整的新文件，不会读到半截. */
	private static void publishAtomically(Path temporaryJar, Path target) throws IOException {
		try {
			Files.move(temporaryJar, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			// 个别平台/文件系统不支持原子 move，退化为普通 move
			Files.move(temporaryJar, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void remapAll(List<ModSpec> mods, Set<Path> owned, MemoryMappingTree mappings, ServiceFactory serviceFactory) throws IOException {
		final String sourceNamespace = getSourceNamespace().get();
		final String targetNamespace = getTargetNamespace().get();

		// 反查表：builder lambda 会捕获它，而谓词要到 apply() 才求值，
		// 因此先声明、待阶段一填充是安全的。
		final Map<InputTag, ModSpec> byTag = new IdentityHashMap<>();

		// kotlin classloader 需在本方法末尾关闭，故用单元素持有者把创建结果带出 lambda
		final KotlinRemapperClassloader[] kotlinClassloaderHolder = new KotlinRemapperClassloader[1];

		// 扩展链：原先在配置期由 ModProcessor 装配。两项决策事实（mixin 重映射类型、是否内联
		// refmap）现已收成任务输入，故同一份契约可在执行期满足，无需把依赖对象带进来。
		final Map<ModSpec, ModProcessorExtension.ModInfo> modInfos = new HashMap<>();

		for (ModSpec mod : mods) {
			modInfos.put(mod, new ModProcessorExtension.ModInfo(
					mod.getInputJar().get().getAsFile().toPath(),
					mod.getMixinRemapType().get(),
					mod.getInlineRefmap().get()));
		}

		final List<ModProcessorExtension> activeExtensions = ModProcessorExtension.EXTENSIONS.stream()
				.filter(e -> modInfos.values().stream().anyMatch(e::appliesTo))
				.toList();
		final List<Path> mixinModJars = modInfos.values().stream()
				.filter(info -> info.mixinRemapType() == ArtifactMetadata.MixinRemapType.MIXIN)
				.map(ModProcessorExtension.ModInfo::inputJar)
				.toList();
		final ModProcessorExtension.Context extensionContext = new ModProcessorExtension.Context(sourceNamespace, targetNamespace, mixinModJars);

		// AW 分析器只需各 jar 内的 AW 内容，执行期可算
		final AccessWidenerAnalyzeVisitorProvider accessWidenerProvider = AccessWidenerAnalyzeVisitorProvider.createFromPaths(
				sourceNamespace,
				mods.stream().map(mod -> mod.getInputJar().get().getAsFile().toPath()).toList(),
				getPlatform().get()
		);

		// 构造路径固定在 L4，与 ModProcessor 共用同一条（见 ModJarRemap 的类注释）
		final TinyRemapper remapper = ModJarRemap.createRemapper(
				mappings,
				Set.copyOf(getKnownIndyBsms().get()),
				sourceNamespace,
				targetNamespace,
				accessWidenerProvider,
				builder -> {
					// 顺序必须保持「kotlin → mod processor 扩展」，extension 是链式的
					final KotlinClasspathService.Options kotlinOptions = getKotlinOptions().getOrNull();

					if (kotlinOptions != null) {
						kotlinClassloaderHolder[0] = KotlinRemapperClassloader.create(serviceFactory.get(kotlinOptions));
						builder.extension(kotlinClassloaderHolder[0].getTinyRemapperExtension());
					}

					for (ModProcessorExtension extension : activeExtensions) {
						LOGGER.info("Applying mod processor extension: {}", extension.getClass().getSimpleName());

						final Predicate<InputTag> applyPredicate = inputTag -> {
							final ModSpec mod = byTag.get(inputTag);
							return mod != null && extension.appliesTo(modInfos.get(mod));
						};

						builder.extension(extension.createExtension(extensionContext, applyPredicate));
					}
				}
		).build();

		// 产出路径：由坐标与 cache key 决定，与消费方按 maven 助手算出的路径同源（见 ModSpec.getOutputJar）
		final Map<ModSpec, Path> outputs = new HashMap<>();
		// 拆分依赖的另一半落位；只有 SPLIT 目标的 mod 在这张表里
		final Map<ModSpec, Path> splitClients = new HashMap<>();
		final Map<ModSpec, Pair<byte[], String>> accessWideners = new HashMap<>();

		for (ModSpec mod : mods) {
			outputs.put(mod, outputPath(mod));
			final Path splitClient = splitClientPath(mod);

			if (splitClient != null) {
				splitClients.put(mod, splitClient);
			}
		}

		try {
			// 阶段一：把所有输入与 classpath 加入类型上下文——**包括不属于本任务的产物**。
			// 输入集合不随认领结果变化是有意的：否则「谁先登记」会改变类型上下文，进而改变产物的字节。
			// 必须全部就位后才能 apply，否则跨 mod 的继承关系会解析不到。
			for (Path path : getRemapClasspath().getFiles().stream().map(File::toPath).toList()) {
				remapper.readClassPathAsync(path);
			}

			final Map<ModSpec, InputTag> tags = new HashMap<>();

			for (ModSpec mod : mods) {
				final Path input = mod.getInputJar().get().getAsFile().toPath();

				final InputTag tag = remapper.createInputTag();
				tags.put(mod, tag);
				byTag.put(tag, mod);
				remapper.readInputsAsync(tag, input);
			}

			// 阶段二：逐个 apply；只写出本任务认领到的那些（被别的项目认领的产物由对方产出，这里重复写会互相覆盖）
			for (ModSpec mod : mods) {
				final Path output = outputs.get(mod);

				if (!owns(output, splitClients.get(mod), owned)) {
					continue;
				}

				final Path input = mod.getInputJar().get().getAsFile().toPath();

				// 拆分依赖先写到 common 落位上，拆分阶段再以它为输入把两半落位（见 publishSplit）：
				// 重映射只能产出整体 jar，而消费方要的是两半
				Files.createDirectories(output.getParent());

				try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
					outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);

					final AccessWidenerUtils.AccessWidenerData awData = AccessWidenerUtils.readAccessWidenerData(input, getPlatform().get());

					if (awData != null) {
						accessWideners.put(mod, new Pair<>(
								AccessWidenerUtils.remapAccessWidener(awData.content(), remapper.getEnvironment().getRemapper(), sourceNamespace, targetNamespace),
								awData.path()));
					}

					remapper.apply(outputConsumer, tags.get(mod));
				}
			}
		} finally {
			remapper.finish();

			if (kotlinClassloaderHolder[0] != null) {
				kotlinClassloaderHolder[0].close();
			}
		}

		// 阶段三：后处理。纯步骤走 L4；依赖项目模型的步骤用显式参数的 L4 重载。同样只处理后本任务写出的那些。
		int remappedMods = 0;

		for (ModSpec mod : mods) {
			final Path output = outputs.get(mod);
			final Path splitClient = splitClients.get(mod);

			if (!owns(output, splitClient, owned)) {
				continue;
			}

			remappedMods++;
			ModJarPostProcess.replaceAccessWidener(output, accessWideners.get(mod));

			final ModProcessorExtension.ModInfo modInfo = modInfos.get(mod);

			for (ModProcessorExtension extension : activeExtensions) {
				if (extension.appliesTo(modInfo)) {
					extension.finalise(modInfo, output);
				}
			}

			ModJarPostProcess.stripNestedJars(output);
			ModJarPostProcess.writeMappingNamespace(output, targetNamespace);

			if (getForgeLike().get()) {
				if (getNeoForge().get()) {
					ModJarPostProcess.remapNeoForgeAts(output, mappings, sourceNamespace, targetNamespace);
				} else {
					// Forge：只映射类名，其余在运行时按 srg -> named 处理
					AtClassRemapper.remap(output, mappings, sourceNamespace);
				}

				CoreModClassRemapper.remapJar(getRuntimeMojang().get(), output, mappings, sourceNamespace);
			}

			// 后处理完成的整体 jar 拆成两半后各自落位（非拆分依赖没有这一步：它就是整体产物本身）
			if (splitClient != null) {
				publishSplit(output, splitClient, owned);
			}
		}

		// 计数按**条 mod**而不是按产物路径：拆分依赖一条 mod 有两条产物，按路径数会报出「2 of 1 mods」
		LOGGER.info(":remapped {} of {} mods ({} -> {})", remappedMods, mods.size(), sourceNamespace, targetNamespace);
	}

	/**
	 * 从 remap 配置的源文件里筛出应加入重映射 classpath 的路径.
	 *
	 * <p>两条必须保持的规则：**排除本轮正在重映射的输入**（它们由本任务自己写入产出，
	 * 不应再作为 classpath 上下文），且只接受**存在且为普通文件**的条目。
	 *
	 * <p>保留为 public static 而非内联进注册逻辑，是为了让这两条语义可被单元测试直接覆盖
	 * ——它们是静默错误的高发点（多收一个输入会让产物与配置期不等价）。
	 *
	 * @param remapConfigSourceFiles 各 remap 配置源配置下的文件
	 * @param inputsBeingRemapped 本轮正在重映射的输入
	 * @return 应加入重映射 classpath 的路径
	 */
	public static List<Path> collectRemapClasspath(Collection<File> remapConfigSourceFiles, Set<File> inputsBeingRemapped) {
		final List<Path> classpath = new ArrayList<>();

		for (File inputFile : remapConfigSourceFiles) {
			if (inputsBeingRemapped.contains(inputFile)) {
				continue;
			}

			final Path path = inputFile.toPath();

			if (Files.isRegularFile(path)) {
				classpath.add(path);
			}
		}

		return classpath;
	}

	/**
	 * 一个待重映射 mod 的描述.
	 *
	 * <p>坐标不是元数据冗余：产出路径由坐标与 cache key 决定，而 {@link #getOutputJar()} 携带的正是这条路径
	 * ——两者必须一致，否则写出的文件与消费方按 maven 助手换算出的是两条不同的路径。
	 */
	public interface ModSpec {
		/**
		 * 原始 mod jar.
		 *
		 * <p>每个属性都必须带输入/输出注解：{@code @Nested} 的嵌套类型上，Gradle 的工作校验会
		 * 要求**所有**属性可追踪，只写 {@code @Optional} 是不够的（它修饰的是可选性，不是输入性）
		 * ——漏注解不会在编译期暴露，而是等到某个工程真的依赖了文件型 mod 才在任务校验阶段失败。
		 */
		@InputFile
		@PathSensitive(PathSensitivity.RELATIVE)
		RegularFileProperty getInputJar();

		/** 产出坐标的组，决定产出路径. */
		@Input
		Property<String> getGroup();

		/** 产出坐标的名（含 cache key）. */
		@Input
		Property<String> getName();

		/** 产出坐标的版本. */
		@Input
		Property<String> getVersion();

		/** 产出坐标的分类器；缺省时产出路径不含分类器段. */
		@Optional
		@Input
		Property<String> getClassifier();

		/** jar 内容派生的 mixin 重映射类型；决定套用哪个扩展. */
		@Input
		Property<ArtifactMetadata.MixinRemapType> getMixinRemapType();

		/** 声明的 refmap 内联选项；决定是否套用 InlineRefmap. */
		@Input
		Property<Boolean> getInlineRefmap();

		/**
		 * 本 mod 重映射后的 jar 落在共享仓库里的哪一条路径.
		 *
		 * <p>路径只有一处来源——{@code LocalMavenHelper}（消费者的文件依赖也由它算出），注册时按同一条路径
		 * 同时喂给任务与登记表。刻意不在这里重算布局：快照版本的目录名与文件名不同源，重算就会分叉成
		 * 「任务写一条路径、消费方读另一条」的静默缺失。
		 *
		 * <p>{@code @Internal} 而不是 {@code @OutputFile}：本任务按批次共享产出，写出的集合是
		 * {@link #getOutputJars()}（只有认领到的那些），而这里的路径是**每条** mod 的落位——两者混用会把
		 * 不属于本任务的产物也声明成输出。
		 */
		@Internal
		RegularFileProperty getOutputJar();

		/**
		 * 拆分依赖的 client 半落位；非拆分依赖为空，此时 {@link #getOutputJar()} 就是全部产物.
		 *
		 * <p>有值表示这条依赖的产物流向消费方的两条路径（{@code -common} 与 {@code -client}，
		 * 见 {@code SplitModDependency}）：重映射产出的是整体 jar，因此执行期还要按 {@code JarSplitter}
		 * 把它拆成两半，再分别落位到这两条路径上——只写其中一条，另一条就是没有生产者的悬空路径。
		 *
		 * <p>来源与 {@link #getOutputJar()} 相同（同样由 {@code LocalMavenHelper} 算出，并参与登记表认领），
		 * 因此两条路径与消费方读的两条永远一致。
		 *
		 * <p>只标 {@code @Internal}：它与其它输入注解不能共存（属性既然被忽略，就不该再声明可选性）。
		 * 为空表示非拆分依赖，这一点与 {@code @Optional} 无关。
		 */
		@Internal
		RegularFileProperty getSplitClientJar();
	}
}
