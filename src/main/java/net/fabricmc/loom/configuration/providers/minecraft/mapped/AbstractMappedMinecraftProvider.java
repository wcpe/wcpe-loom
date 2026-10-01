/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft.mapped;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.architectury.loom.forge.InnerClassRemapper;
import dev.architectury.loom.forge.minecraft.ForgeMinecraftProvider;
import dev.architectury.loom.mappings.MappingOption;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.core.MinecraftJarRemap;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.providers.mappings.IntermediaryMappingsProvider;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.SignatureFixerApplyVisitor;
import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.pipeline.RemapMinecraftTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.Strings;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.JarReusability;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract class AbstractMappedMinecraftProvider<M extends MinecraftProvider> implements MappedMinecraftProvider.ProviderImpl {
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMappedMinecraftProvider.class);

	protected final M minecraftProvider;
	private final Project project;
	protected final LoomGradleExtension extension;

	/**
	 * 本 provider 是否参与「把生产搬到任务」.
	 *
	 * <p>默认参与。唯一会关掉它的是 legacy merged（MC 1.3 之前）的两个委托 provider：它们的产物要在
	 * **配置期**被合并步骤读取（{@code NamedMinecraftProvider.LegacyMergedImpl.provide} 里紧跟着
	 * {@code MergedMinecraftProvider.mergeJars}），切到任务后文件在配置期还不存在，合并会直接失败。
	 * 关闭是显式的（由创建方在构造后立刻调用），不是靠形态探测——探测不出「谁会读我」这件事。
	 */
	private boolean taskProduction = true;

	/**
	 * 本次构建为本 provider 的产物登记到的生产位置，按 jar 类型索引.
	 *
	 * <p>供子 provider（例如 {@link ProcessedNamedMinecraftProvider} 的处理器链）把「我的输入就是你的产出」
	 * 这件事表达成任务依赖：输入若只按路径声明，Gradle 无从知道要先跑产出任务，链会在产物还没落位时开跑，
	 * 而且会撞上 Gradle 的隐式依赖校验。不可投影（或未启用任务生产）时为空，此时产物由配置期生产，
	 * 按路径声明即可。
	 *
	 * <p>值是 {@link Producer} 而不是 {@code TaskProvider}：产物落在跨项目共享的
	 * maven 仓库，同一构建内配置相同的多个项目会算出同一条路径，而它们可能由**不同的 Loom classloader** 配置
	 * （约定插件/included build 各自带一份 Loom），此时产出方是「另一个 classloader 里的任务」，
	 * 本 classloader 不能把它的任务实例当自己的 {@link RemapMinecraftTask} 用。
	 */
	private Map<MinecraftJar.Type, Producer> registeredRemapTasks = Map.of();

	public AbstractMappedMinecraftProvider(Project project, M minecraftProvider) {
		this.minecraftProvider = minecraftProvider;
		this.project = project;
		this.extension = LoomGradleExtension.get(project);
	}

	public abstract MappingsNamespace getTargetNamespace();

	/**
	 * @return A list of jars that should be remapped
	 */
	public abstract List<RemappedJars> getRemappedJars();

	/**
	 * @return A list of output jars that this provider generates
	 */
	public List<? extends OutputJar> getOutputJars() {
		return getRemappedJars();
	}

	// Returns a list of MinecraftJar.Type's that this provider exports to be used as a dependency
	public List<MinecraftJar.Type> getDependencyTypes() {
		return Collections.emptyList();
	}

	/**
	 * 供给本 provider 的产物：**生产已改由任务承担**，本方法不再在配置期写任何产物.
	 *
	 * <p>本次改造把「判断是否重建 → 取跨进程锁 → 重映射 → 落位」整段交给
	 * {@link RemapMinecraftTask}：产物有效性交给 Gradle 的 up-to-date 判定与构建缓存，并发保护交给
	 * 任务图，残骸恢复交给构建缓存。本方法只做三件事——逐 jar 注册任务（失败则整批回退，见下）、
	 * 把任务产出登记进 {@code getMinecraftJarsTaskOutputs}、以及把带任务依赖的文件集合注入消费配置。
	 *
	 * <h4>不可投影时整批回退到配置期生产，且必留痕</h4>
	 * {@link #verifyProjectable(RemappedJars)} 判为「无法安全投影」的形态（例如
	 * {@code disableObfuscation} 的直通形态）不进入任务路径，改由 {@link #produceInConfiguration}
	 * 走改造前的配置期路径。回退是**按 provider 整批**而不是按 jar 的：半批投影会留下
	 * 「一部分产物由任务生产、一部分由配置期生产」的混合状态，同一份缓存里出现两种就绪判据。
	 * 回退必定打日志（含被拒的具体原因），不是静默吞掉——静默吞掉会得到「以为切了任务、其实还在配置期写」。
	 */
	public List<MinecraftJar> provide(ProvideContext context) throws Exception {
		final List<RemappedJars> remappedJars = getRemappedJars();
		final List<MinecraftJar> minecraftJars = remappedJars.stream()
				.map(RemappedJars::outputJar)
				.toList();

		if (remappedJars.isEmpty()) {
			throw new IllegalStateException("No remapped jars provided");
		}

		final Map<MinecraftJar.Type, Producer> remapTasks;

		if (taskProduction) {
			remapTasks = registerRemapTasks(remappedJars, context);
		} else {
			LOGGER.debug("{} 未启用任务生产（产物需在配置期可读），保持配置期生产", getClass().getSimpleName());
			remapTasks = null;
		}

		if (remapTasks == null) {
			produceInConfiguration(context, remappedJars, minecraftJars);
		}

		if (context.applyDependencies()) {
			applyDependencies(remapTasks);
		}

		return minecraftJars;
	}

	/**
	 * 关闭本 provider 的任务生产（见 {@link #taskProduction}）.
	 *
	 * <p>只在创建 legacy merged 的委托 provider 时调用，且必须早于 {@code provide}。
	 */
	protected void disableTaskProduction() {
		this.taskProduction = false;
	}

	/**
	 * {@return 本 provider 为指定 jar 类型登记到的生产位置；未登记（不可投影或未启用任务生产）时为 {@code null}}.
	 *
	 * <p>子 provider 用它建立「本任务以该产物为输入」的任务依赖，而不是只按路径声明输入。
	 */
	protected @Nullable Producer getRemapTask(MinecraftJar.Type type) {
		return registeredRemapTasks.get(type);
	}

	/**
	 * 逐 jar 注册重映射任务，并把产出登记到对应命名空间.
	 *
	 * <p>先对全部 jar 做一次纯判定（{@link #verifyProjectable} 只读、无副作用），全部可通过才注册：
	 * 这样「整批回退」的判定不会因为注册顺序而变成「前一半已注册、后一半才发现不可投影」。
	 *
	 * @return 每个 jar 类型对应的生产位置；本 provider 不可投影时返回 {@code null}
	 */
	private @Nullable Map<MinecraftJar.Type, Producer> registerRemapTasks(List<RemappedJars> remappedJars, ProvideContext context) {
		for (RemappedJars remappedJar : remappedJars) {
			try {
				verifyProjectable(remappedJar);
			} catch (UnsupportedOperationException e) {
				LOGGER.warn("{} 的产物 {} 无法安全投影成任务，本 provider 保持配置期生产：{}",
						getClass().getSimpleName(), remappedJar.outputJarPath(), e.getMessage());
				return null;
			}
		}

		final Map<MinecraftJar.Type, Producer> producers = new LinkedHashMap<>();

		for (RemappedJars remappedJar : remappedJars) {
			producers.put(remappedJar.type(), registerRemapTask(remappedJar, context.configContext()));
		}

		// 登记任务产出：消费侧（ValidateAccessWidenerTask、TinyRemapperService 等）据此拿到
		// 携带任务依赖的文件集合。只登记实际切到任务的命名空间，未切的命名空间保持回退语义。
		registeredRemapTasks = Map.copyOf(producers);
		extension.setMinecraftJarsTaskOutputs(getTargetNamespace(), jarOutputs(producers.values()));
		return producers;
	}

	/** {@return 由一组产物的文件构成的文件集合} 其任务依赖随集合传播给消费方. */
	private ConfigurableFileCollection jarOutputs(Collection<Producer> producers) {
		final ConfigurableFileCollection outputs = getProject().getObjects().fileCollection();

		for (Producer producer : producers) {
			// 走到这里的产物一定有产出任务：不可投影的形态已在 registerRemapTasks 里整批回退。
			// 依赖按**任务路径**登记而不是任务实例：产出方可能来自另一个 classloader（见 registeredRemapTasks）
			final String taskPath = Objects.requireNonNull(producer.taskPath(),
					() -> "已投影成任务的产物缺少产出任务：" + producer.artifact());
			outputs.from(producer.artifact().toFile());
			outputs.builtBy(taskPath);
		}

		return outputs;
	}

	/**
	 * 配置期生产：本次改造前的路径，只对「不可安全投影」的形态保留.
	 *
	 * <p>整段语义与改造前逐字一致——无锁快路径、跨进程互斥、锁内二次确认、重映射、原子备份。
	 * 它没有被删除，是因为仍有形态（见 {@code verifyProjectable}）走这里；后续步骤会随这些形态的
	 * 投影方案一并处理。
	 */
	private void produceInConfiguration(ProvideContext context, List<RemappedJars> remappedJars, List<MinecraftJar> minecraftJars) throws Exception {
		// 无锁快路径：shouldRefreshOutputs 只做只读检查（产物存在且内容可复用），缓存就绪时不会进入下面的锁
		if (!shouldRefreshOutputs(context)) {
			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
		final Path lockRoot = extension.getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, cacheKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：可能已被其它进程/线程在我们等锁期间生产完成
			if (shouldRefreshOutputs(context)) {
				try {
					remapInputs(remappedJars, context.configContext());
					createBackupJars(minecraftJars);
				} catch (Throwable t) {
					throw new RuntimeException("Failed to remap minecraft", t);
				}
			}

			return null;
		});
	}

	/**
	 * 把「本项目编译/运行时会用到本 provider 产物」这件事注入对应配置.
	 *
	 * <h4>任务路径下注入的是文件而不是 maven 坐标</h4>
	 * 切到任务后，产物在**执行期**才落位（改造前在配置期就已存在），因此按坐标注入会在冷缓存上
	 * 直接解析失败——maven 仓库查询发生在文件存在之前，而坐标依赖不携带任何任务依赖，
	 * Gradle 无从知道要先跑产出任务。改为注入「产出任务的文件集合」：它携带任务依赖，
	 * 解析必然发生在产出之后；路径与坐标式解析得到的仍是同一个文件（{@code registerRemapTask}
	 * 已断言产物路径就是 maven 助手为该构件算出的路径），故编译期类路径的内容不变。
	 *
	 * <p>不可投影的形态仍走坐标注入（产物仍由配置期生产，坐标可解析），两条路径在同一处分支，
	 * 判定依据是「该 jar 类型有没有任务」，而不是「本 provider 有没有任务」。
	 */
	private void applyDependencies(@Nullable Map<MinecraftJar.Type, Producer> remapTasks) {
		final List<MinecraftJar.Type> dependencyTargets = getDependencyTypes();

		if (dependencyTargets.isEmpty()) {
			return;
		}

		MinecraftSourceSets.get(getProject()).applyDependencies(
				(configuration, type) -> getProject().getDependencies().add(configuration, dependencyNotation(type, remapTasks)),
				dependencyTargets
		);
	}

	/** {@return 该 jar 类型的依赖表示：有产出任务则是有任务依赖的文件集合，否则是 maven 坐标}. */
	private Object dependencyNotation(MinecraftJar.Type type, @Nullable Map<MinecraftJar.Type, Producer> remapTasks) {
		final Producer producer = remapTasks == null ? null : remapTasks.get(type);

		if (producer == null) {
			return getDependencyNotation(type);
		}

		final ConfigurableFileCollection files = getProject().files(producer.artifact().toFile());
		// 同 jarOutputs：依赖按任务路径登记，产出方可能来自另一个 classloader
		files.builtBy(Objects.requireNonNull(producer.taskPath(), () -> "已投影成任务的产物缺少产出任务：" + producer.artifact()));
		return files;
	}

	// 跨进程互斥 key：targetNamespace 区分 provider 实例（intermediary/named），getVersion 含 mcVersion+mappingsIdentifier，二者已唯一标识这批产物。
	// protected：legacy-merged 等覆写 provide() 的子类需要复用同一把锁保护其 GLOBAL 合并产物
	protected String cacheKey() {
		return getTargetNamespace().name() + ":" + getVersion();
	}

	// Create two copies of the remapped jar, the backup jar is used as the input of genSources
	public static Path getBackupJarPath(MinecraftJar minecraftJar) {
		final Path outputJarPath = minecraftJar.getPath();
		return outputJarPath.resolveSibling(outputJarPath.getFileName() + ".backup");
	}

	protected boolean requiresBackupJars() {
		return true;
	}

	protected void createBackupJars(List<MinecraftJar> minecraftJars) throws IOException {
		if (!requiresBackupJars()) {
			return;
		}

		for (MinecraftJar minecraftJar : minecraftJars) {
			// backup 是「最后产生」的就绪标志，必须原子落位，避免跨进程读到半写的 backup 误判就绪
			AtomicFiles.copy(minecraftJar.getPath(), getBackupJarPath(minecraftJar));
		}
	}

	public record ProvideContext(boolean applyDependencies, boolean refreshOutputs, ConfigContext configContext) {
		ProvideContext withApplyDependencies(boolean applyDependencies) {
			return new ProvideContext(applyDependencies, refreshOutputs(), configContext());
		}
	}

	@Override
	public Path getJar(MinecraftJar.Type type) {
		return getMavenHelper(type).getOutputFile(null);
	}

	public enum MavenScope {
		// Output files will be stored per project
		LOCAL(LoomFiles::getLocalMinecraftRepo),
		// Output files will be stored globally
		GLOBAL(LoomFiles::getGlobalMinecraftRepo);

		private final Function<LoomFiles, File> fileFunction;

		MavenScope(Function<LoomFiles, File> fileFunction) {
			this.fileFunction = fileFunction;
		}

		public Path getRoot(LoomGradleExtension extension) {
			return fileFunction.apply(extension.getFiles()).toPath();
		}
	}

	public abstract MavenScope getMavenScope();

	public LocalMavenHelper getMavenHelper(MinecraftJar.Type type) {
		return new LocalMavenHelper("net.minecraft", getName(type), getVersion(), null, getMavenScope().getRoot(extension));
	}

	protected String getName(MinecraftJar.Type type) {
		var sj = new StringJoiner("-");
		sj.add("minecraft");
		sj.add(type.toString());

		if (!extension.disableObfuscation()) {
			// Include the intermediate mapping name if it's not the default intermediary
			final String intermediateName = extension.getIntermediateMappingsProvider().getName();

			if (!intermediateName.equals(IntermediaryMappingsProvider.NAME)) {
				sj.add(intermediateName);
			}
		} else {
			sj.add("deobf");
		}

		if (getTargetNamespace() != MappingsNamespace.NAMED) {
			sj.add(getTargetNamespace().name());
		}

		return minecraftProvider.getJarPrefix() + sj.toString().toLowerCase(Locale.ROOT);
	}

	protected String getVersion() {
		if (extension.disableObfuscation()) {
			return extension.getMinecraftProvider().minecraftVersion();
		}

		return "%s-%s".formatted(extension.getMinecraftProvider().minecraftVersion(), extension.getMappingConfiguration().mappingsIdentifier());
	}

	protected String getDependencyNotation(MinecraftJar.Type type) {
		return "net.minecraft:%s:%s".formatted(getName(type), getVersion());
	}

	/**
	 * {@return 产物是否已就绪（无需重建）}.
	 *
	 * <p>供外部（如 {@code minecraft-provision} 事务锁的无锁快路径）在锁外做只读判定；
	 * 实现即 {@link #shouldRefreshOutputs} 的取反，判定产物存在且内容可复用（见下）。
	 */
	public boolean isUpToDate(ProvideContext context) {
		return !shouldRefreshOutputs(context);
	}

	protected boolean shouldRefreshOutputs(ProvideContext context) {
		if (context.refreshOutputs()) {
			LOGGER.info("Refreshing outputs for mapped jar, as refresh outputs was requested");
			return true;
		}

		final List<? extends OutputJar> outputJars = getOutputJars();

		if (outputJars.isEmpty()) {
			throw new IllegalStateException("No output jars provided");
		}

		// Architectury: regenerate jars if patches have changed.
		if (minecraftProvider instanceof ForgeMinecraftProvider withForge && withForge.getPatchedProvider().isDirty()) {
			return true;
		}

		for (OutputJar outputJar : outputJars) {
			// 就绪判据必须含内容校验：产物位于跨 daemon／跨 loom 版本共享的 maven 仓库，旧版本 loom 以最终路径
			// 为输出就地写，被中断会留下 0 字节或截断的 jar。只判存在会把这类残骸当成就绪产物，作为 Gradle
			// 依赖进入编译链，形成「标记是新的、内容是坏的」的静默损坏。
			if (!getMavenHelper(outputJar.type()).isReusable(null)) {
				LOGGER.info("Refreshing outputs for mapped jar, as {} is missing or not reusable", outputJar.outputJar());
				return true;
			}
		}

		if (requiresBackupJars()) {
			for (OutputJar outputJar : outputJars) {
				// backup 是 remapped jar 的逐字节副本（由 AtomicFiles.copy 落位），故它同样是 jar，
				// 用与上面同一条内容级判据。注意其文件名以 .backup 结尾、不是 .jar/.zip：
				// 内容损坏时 zipfs 抛的是运行时异常而非 IOException，JarReusability 已把该族异常
				// 一并归入「不可复用」（见其实现注释），否则这里会从「重建」变成构建崩溃。
				if (!JarReusability.isReusable(getBackupJarPath(outputJar.outputJar()))) {
					LOGGER.info("Refreshing outputs for mapped jar, as backup jar is missing or not reusable for {}", outputJar.outputJar());
					return true;
				}
			}
		}

		LOGGER.debug("All outputs are up to date");
		return false;
	}

	private void remapInputs(List<RemappedJars> remappedJars, ConfigContext configContext) throws IOException {
		for (RemappedJars remappedJar : remappedJars) {
			remapJar(remappedJar, configContext);
		}
	}

	protected void remapJar(RemappedJars remappedJars, ConfigContext configContext) throws IOException {
		if (extension.disableObfuscation()) {
			// TODO debof - can we skip this?
			// 原子复制：避免跨进程读到半写的输出 jar 误判就绪
			AtomicFiles.copy(remappedJars.inputJar(), remappedJars.outputJarPath());
			getMavenHelper(remappedJars.type()).savePom();
			return;
		}

		final MappingConfiguration mappingConfiguration = extension.getMappingConfiguration();
		final String fromM = remappedJars.sourceNamespace().toString();
		final String toM = getTargetNamespace().toString();

		final Set<String> classNames = extension.isForgeLike() ? InnerClassRemapper.readClassNames(remappedJars.inputJar()) : Set.of();
		final AnnotationsData remappedAnnotations = AnnotationsData.getRemappedAnnotations(getTargetNamespace(), mappingConfiguration, getProject(), configContext.serviceFactory(), toM);
		final Map<String, String> remappedSignatures = SignatureFixerApplyVisitor.getRemappedSignatures(getTargetNamespace() == MappingsNamespace.INTERMEDIARY, mappingConfiguration, getProject(), configContext.serviceFactory(), toM);
		final MinecraftVersionMeta.JavaVersion javaVersion = minecraftProvider.getVersionInfo().javaVersion();
		final boolean fixRecords = javaVersion != null && javaVersion.majorVersion() >= 16;

		// Arch: disable namespace validation for toM = intermediary when intermediate mappings are disabled.
		// See https://github.com/FabricMC/fabric-loom/issues/1576.
		final boolean validateTargetNamespace = !(getTargetNamespace() == MappingsNamespace.INTERMEDIARY && !extension.getUseIntermediateMappings().get());

		// object holder 改写只在 Forge 系「官方命名空间」下需要；目标命名空间是字面量 named，
		// 与本次重映射的目标命名空间无关
		final String objectHolderClassName;
		final String objectHolderSourceNamespace;

		if (extension.isForgeLikeAndOfficial()) {
			objectHolderClassName = extension.isNeoForge()
					? "net.neoforged.neoforge.registries.ObjectHolderRegistry"
					: "net.minecraftforge.registries.ObjectHolderRegistry";
			objectHolderSourceNamespace = extension.getProductionNamespace().get();
		} else {
			objectHolderClassName = null;
			objectHolderSourceNamespace = null;
		}

		// 主重映射与 object holder 改写用的是同一套按平台选出的映射树
		final MemoryMappingTree mappings = mappingConfiguration
				.getMappingsService(getProject(), configContext.serviceFactory(), MappingOption.forPlatform(extension))
				.getMappingTree();

		try {
			MinecraftJarRemap.run(new MinecraftJarRemap.Options(
					null,
					remappedJars.inputJar(),
					remappedJars.outputJarPath(),
					List.of(remappedJars.remapClasspath()),
					mappings,
					fromM,
					toM,
					extension.isForgeLike(),
					extension.isNeoForge(),
					fixRecords,
					validateTargetNamespace,
					classNames,
					extension.getKnownIndyBsms().get(),
					remappedAnnotations,
					remappedSignatures,
					objectHolderClassName,
					objectHolderSourceNamespace,
					"named",
					builder -> configureRemapper(remappedJars, builder)
			));
		} catch (RuntimeException e) {
			throw new RuntimeException("Failed to remap JAR " + remappedJars.inputJar() + " with mappings from " + mappingConfiguration.tinyMappings, e);
		}

		getMavenHelper(remappedJars.type()).savePom();
	}

	protected void configureRemapper(RemappedJars remappedJars, TinyRemapper.Builder tinyRemapperBuilder) {
	}

	// Configure the remapper to add the client @Environment annotation to all classes in the client jar.
	public static void configureSplitRemapper(RemappedJars remappedJars, TinyRemapper.Builder tinyRemapperBuilder) {
		final MinecraftJar outputJar = remappedJars.outputJar();
		assert !outputJar.isMerged();

		if (outputJar.includesClient()) {
			assert !outputJar.includesServer();
			tinyRemapperBuilder.extraPostApplyVisitor(SidedClassVisitor.CLIENT);
		}
	}

	/**
	 * {@code configureRemapper} 覆写在投影视角下的形态.
	 *
	 * <p>投影要复现的是「配置期行为」，不是「配置期行为是否正确」：覆写今天哪怕什么也不挂，
	 * 任务侧同样不挂就是精确复现，可以放行；覆写挂了什么而任务协议表达不了，才必须拒绝。
	 * 「什么也不挂」与「只挂 split visitor」因此都得能被显式声明，否则前者只能被当作未知覆写一并拒掉。
	 */
	public enum RemapperHookKind {
		/** 未覆写，或覆写被证明是空操作：任务侧恒不挂 visitor. */
		NO_OP,
		/** 覆写只等价于 configureSplitRemapper：任务侧按「非 merged 且含客户端」逐 jar 决定. */
		SPLIT_CLIENT_VISITOR_ONLY,
		/** 无法判定的覆写：拒绝投影（默认值，第三方未知覆写照旧走这里）. */
		UNKNOWN,
	}

	/**
	 * {@return 本 provider 对 {@link #configureRemapper} 的覆写在投影视角下的形态}.
	 *
	 * <p>这是一个显式声明，不是探测：钩子是实例方法，它的效果（往 {@link TinyRemapper.Builder} 上挂什么）
	 * 无法被序列化成任务输入，而反射只能确认「覆写了」、不能确认「覆写成什么」。因此默认值刻意保守：
	 * {@link RemapperHookKind#UNKNOWN}，即「不可假定」。不认识的覆写一律拒绝投影，而不是把它当作空实现——
	 * 后者会得到「任务成功、产物却比配置期少了钩子改动」的静默损坏。
	 *
	 * <h4>三种形态各自意味着什么</h4>
	 * <ul>
	 *   <li>{@link RemapperHookKind#NO_OP}：钩子什么也不挂，任务侧 {@code injectClientSidedVisitor = false}
	 *       就是精确复现。适用两种情形：「压根没有覆写」（基类的空实现，由 {@code overridesRemapperHook()}
	 *       判定，不需要声明），以及「覆写已被证明是空操作」。后者是对覆写体的一处断言，反射校验不了它，
	 *       只能靠证据支撑——把「为什么恒不挂」的证据写在声明处的注释里。</li>
	 *   <li>{@link RemapperHookKind#SPLIT_CLIENT_VISITOR_ONLY}：钩子的效果恰好是
	 *       {@link #configureSplitRemapper}——非 merged 且含客户端的 jar 挂 {@code SidedClassVisitor.CLIENT}，
	 *       其余 jar 什么也不挂。任务侧已经按 jar 承载了这个效果（{@code RemapMinecraftTask.getInjectClientSidedVisitor()}），
	 *       所以声明之后本 provider 的每个 jar 都能被投影：需要 visitor 的那个 jar 由任务挂上，
	 *       不需要的那个两边都不挂。声明是对「覆写体干了什么」的断言，代码本身无法校验它，因此只有
	 *       「覆写体就是一行 {@code configureSplitRemapper(remappedJars, tinyRemapperBuilder)}」这类
	 *       可以逐字核对的情形才该如此声明；覆写体的判据与 {@code configureSplitRemapper} 不同时
	 *       （例如按别的条件决定是否挂 visitor），即使「看起来是同一件事」也必须保持
	 *       {@code UNKNOWN}：任务侧的注入条件复刻的是 {@code configureSplitRemapper} 的判据，
	 *       判据不同就会在某个 jar 上分叉。</li>
	 *   <li>{@link RemapperHookKind#UNKNOWN}：无法判定，拒绝投影。第三方覆写只要不声明就落在这里。</li>
	 * </ul>
	 */
	protected RemapperHookKind remapperHookKind() {
		return RemapperHookKind.UNKNOWN;
	}

	/**
	 * 把一个 {@link RemappedJars} 投影成 L3 重映射任务，返回的任务引用可承载消费侧的文件依赖.
	 *
	 * <p>本方法是 {@link #provide} 里「remapInputs + createBackupJars」那段的替代路径的**注册侧**：
	 * 产物有效性、并发保护、损坏恢复分别改由 Gradle 的 up-to-date 判定、任务图与构建缓存承担，
	 * 取代 {@code shouldRefreshOutputs} + 跨进程锁 + {@code JarReusability} 这套手写协议。
	 *
	 * <p>本方法只做「配置期已知量 → 任务输入」的映射，不写任何产物文件。它现在是
	 * {@link #provide} 的生产路径：{@code provide} 对每个 jar 调用它，并把返回的任务登记进
	 * {@code setMinecraftJarsTaskOutputs} 与消费配置。与 jar processor 链同批接线——后者是
	 * {@code ProcessedNamedMinecraftProvider} 的输入（Named 的产出），只切一环会断链。
	 *
	 * <h4>输入映射</h4>
	 * <ul>
	 *   <li>{@code inputJar} / {@code outputJar} / {@code remapClasspath} ← {@link RemappedJars}
	 *       的同名字段，{@code fromNamespace} ← {@code sourceNamespace}</li>
	 *   <li>{@code toNamespace} ← {@link #getTargetNamespace()}（取 {@code toString()}，
	 *       与 {@code remapJar} 交给 L4 的字符串一致）</li>
	 *   <li>{@code fixRecords} / {@code validateTargetNamespace} / {@code innerClassNames} /
	 *       {@code objectHolder*} ← 与 {@code remapJar} 逐项同判据</li>
	 *   <li>{@code mappingsServiceOptions} ←
	 *       {@code MappingConfiguration.getMappingsServiceOptions(project, MappingOption.forPlatform(extension))}，
	 *       与配置期 {@code getMappingsService} 取的是同一份映射来源（换来源会产出不等价的 jar）</li>
	 * </ul>
	 *
	 * <h4>为什么 annotations / signatureFixes 只能在配置期算</h4>
	 * {@code AnnotationsData.getRemappedAnnotations} 与
	 * {@code SignatureFixerApplyVisitor.getRemappedSignatures} 都要一个
	 * {@link net.fabricmc.loom.util.service.ServiceFactory}，而 {@code configContext} 携带的那个是配置期
	 * 作用域（由调用方 try-with-resources 持有，配置结束后关闭），执行期已不可用。要让任务自己算，
	 * 必须把这两个 service 的 Options 也收进任务输入——那是协议扩展，本次不做，因此这里如实提前计算。
	 * 代价也如实记下：原路径只在「需要重建」时才算这两个量，投影后变成注册即计算（无注解数据 /
	 * 无签名修复时是 O(1) 早退，只有映射带 extras 时才有真实开销）。
	 *
	 * <h4>产物落位：三个具体文件，目录一律不声明</h4>
	 * 产物取自 {@code remappedJars.outputJarPath()}，即 {@link #getJar} 给出的 maven 仓库位置
	 * （{@link #getMavenScope} 决定落在全局仓库还是项目级仓库，布局由 {@link LocalMavenHelper} 决定）。
	 * 该构件目录里属于本任务的产物有三个，本方法逐个声明为 {@code @OutputFile}、由任务写出：
	 * <ol>
	 *   <li>jar —— 重映射产物本身；</li>
	 *   <li>{@code <name>-<version>.pom} —— 内容与落位都由 {@link LocalMavenHelper#savePom} 决定
	 *       （任务按坐标调用它，不另写一份模板）。缺它会让按坐标解析该构件的依赖直接失败；</li>
	 *   <li>{@code <jar>.backup} —— jar 的逐字节副本，由任务用 {@link AtomicFiles} 原子复制得到，
	 *       位置与 {@link #getBackupJarPath} 一致（消费者 genSources 按后者找它）。{@link #requiresBackupJars()}
	 *       为假的 provider 不声明该输出。</li>
	 * </ol>
	 * 三者都是文件，本方法绝不声明 {@code @OutputDirectory}：仓库根与版本目录里还有别的 provider、
	 * 别的 MC 版本的产物，把它们纳入本任务的快照与清理范围会让并发构建互相破坏。
	 *
	 * <h4>共享路径：同一条路径只有一个生产者</h4>
	 * {@link MavenScope#GLOBAL} 下产物路径跨项目、跨工作树共享（全局仓库位于 Gradle 用户目录，同一台机器上的
	 * 所有工作树共用），同一构建内两个配置相同的子项目会算出同一条路径。这件事交给
	 * {@link RemapMinecraftTaskRegistry}：同一路径的第一次登记创建任务，后续登记只取回生产者的任务路径，
	 * 因此不存在两个任务声明同一个 {@code @OutputFile}——实测 Gradle 9.5 对这种重叠既不报错也不排序，
	 * 两个任务都会写、后完成的覆盖先完成的，而一旦有消费方按该路径取产物，隐式依赖校验会直接让构建失败。
	 * 输入指纹不同的登记会被拒绝而不是共享：共享一份输入不同的产物，等于把别的配置的 jar 静默交给本项目。
	 * 指纹必须覆盖注解与签名修复（它们是产物内容的一部分），所以去重判定发生在这些量算完之后——
	 * 代价与非共享路径相同（见上面「注册即计算」的说明），共享的那一方会白算一次，但换来的是判定精确。
	 *
	 * <h4>本方法覆盖不到的 provider 形态</h4>
	 * 这两种形态在 {@link #verifyProjectable(RemappedJars)} 之后、更早的地方就拒绝被投影，属于本次未解决项：
	 * <ul>
	 *   <li>{@code LegacyMergedImpl}（MC 1.3 之前的合并 jar）：{@code getRemappedJars()} 直接抛
	 *       {@link UnsupportedOperationException}，它的生产过程是「分别重映射 client/server 再合并 jar」，
	 *       并不是一次重映射，本任务无从承载；需要为「合并」单列一个任务。</li>
	 *   <li>{@link ProcessedNamedMinecraftProvider}：落位是 {@link MavenScope#LOCAL}（同名目录由 jar processor
	 *       哈希区分），且它的 {@code getRemappedJars()} 与 {@code getJar()} 都抛异常——它的产物由 jar processor
	 *       链产生，不经本任务；因此 LOCAL 范围下的跨子项目共享不在本方法职责内。</li>
	 * </ul>
	 *
	 * @param remappedJars 待重映射的一对输入/输出 jar
	 * @param configContext 配置上下文；只用其 service factory（配置期作用域）
	 * @return 该产物的生产位置；同一构建内同一产物路径只对应一个生产者
	 * @throws IllegalStateException 产物路径与 maven 助手的构件路径不一致，或同一路径被以不同输入登记时
	 */
	public Producer registerRemapTask(RemappedJars remappedJars, ConfigContext configContext) {
		verifyProjectable(remappedJars);

		// 钩子形态：verifyProjectable 已先行调用过同一个归约并拒绝了 UNKNOWN 与自相矛盾的声明，
		// 故这里的第二次归约不可能再抛——放在这里只是为了把「已校验的值」显式交给下面的任务装配。
		final RemapperHookKind hookKind = resolveRemapperHookKind();

		final Project project = getProject();
		final MappingConfiguration mappingConfiguration = extension.getMappingConfiguration();
		final String fromNamespace = remappedJars.sourceNamespace().toString();
		final String toNamespace = getTargetNamespace().toString();

		// record 修复只在目标 Java 16+ 需要；旧版本的版本元数据可能没有 javaVersion 字段
		final MinecraftVersionMeta.JavaVersion javaVersion = minecraftProvider.getVersionInfo().javaVersion();
		final boolean fixRecords = javaVersion != null && javaVersion.majorVersion() >= 16;

		// Arch: 中间映射被关闭时不校验目标命名空间（与 remapJar 同一判据，见 fabric-loom#1576）
		final boolean validateTargetNamespace = !(getTargetNamespace() == MappingsNamespace.INTERMEDIARY && !extension.getUseIntermediateMappings().get());

		// Forge 系才需要内部类名集合；这一步要读输入 jar，配置期只能同步做
		final Set<String> innerClassNames = extension.isForgeLike()
				? InnerClassRemapper.readClassNames(remappedJars.inputJar())
				: Set.of();

		final String objectHolderClassName;
		final String objectHolderSourceNamespace;

		if (extension.isForgeLikeAndOfficial()) {
			objectHolderClassName = extension.isNeoForge()
					? "net.neoforged.neoforge.registries.ObjectHolderRegistry"
					: "net.minecraftforge.registries.ObjectHolderRegistry";
			objectHolderSourceNamespace = extension.getProductionNamespace().get();
		} else {
			objectHolderClassName = null;
			objectHolderSourceNamespace = null;
		}

		final AnnotationsData remappedAnnotations = AnnotationsData.getRemappedAnnotations(
				getTargetNamespace(), mappingConfiguration, project, configContext.serviceFactory(), toNamespace);
		final Map<String, String> remappedSignatures;

		try {
			remappedSignatures = SignatureFixerApplyVisitor.getRemappedSignatures(
					getTargetNamespace() == MappingsNamespace.INTERMEDIARY, mappingConfiguration, project, configContext.serviceFactory(), toNamespace);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to compute signature fixes for " + remappedJars.outputJarPath(), e);
		}

		// 构件落位：jar / pom / backup 三个文件同处一个 maven 构件目录，pom 与 backup 都由 maven 助手的
		// name/version 决定位置。消费侧据 getJar(type)（= 助手给出的 jar 路径）找产物，两者一旦不一致，
		// pom 就会写到与本项目的 jar 不同的构件目录，坐标式解析随之缺 pom——直接拒绝，而不是让产物分家。
		final LocalMavenHelper mavenHelper = getMavenHelper(remappedJars.type());
		final Path outputJar = remappedJars.outputJarPath();

		if (!mavenHelper.getOutputFile(null).equals(outputJar)) {
			throw new IllegalStateException("产物路径 %s 与 maven 助手为同一构件算出的路径 %s 不一致："
					.formatted(outputJar, mavenHelper.getOutputFile(null))
					+ "pom 与 backup 按助手的 name/version 落位，与 jar 分家后坐标式依赖解析会缺 pom。");
		}

		final Path outputPom = RemapMinecraftTask.pomPathFor(outputJar);
		final Path outputBackupJar = requiresBackupJars() ? getBackupJarPath(remappedJars.outputJar()) : null;

		// 输入指纹：同一构建内第二次登记同一路径时，必须证明两次请求会产出同一个 jar 才能共享同一个任务
		// （见 RemapMinecraftTaskRegistry）。这里只列本任务真正收到的输入——路径本身确定的量（MC 版本、
		// 映射标识、jar 类型、目标命名空间）已经在产物路径里，不必重复。
		final Map<String, String> identity = new LinkedHashMap<>();
		identity.put("inputJar", fingerprintPaths(remappedJars.inputJar()));
		identity.put("remapClasspath", fingerprintPaths(remappedJars.remapClasspath()));
		identity.put("fromNamespace", fromNamespace);
		identity.put("toNamespace", toNamespace);
		identity.put("fixRecords", Boolean.toString(fixRecords));
		identity.put("forgeLike", Boolean.toString(extension.isForgeLike()));
		identity.put("validateTargetNamespace", Boolean.toString(validateTargetNamespace));
		identity.put("innerClassNames", fingerprintValues(innerClassNames));
		identity.put("knownIndyBsms", fingerprintValues(extension.getKnownIndyBsms().get()));
		identity.put("signatureFixes", fingerprintEntries(remappedSignatures));
		identity.put("annotationsJson", remappedAnnotations == null ? "" : remappedAnnotations.toJson().toString());
		identity.put("objectHolderClassName", orEmpty(objectHolderClassName));
		identity.put("objectHolderSourceNamespace", orEmpty(objectHolderSourceNamespace));
		identity.put("objectHolderTargetNamespace", "named");
		identity.put("outputPom", outputPom.toString());
		identity.put("outputBackupJar", orEmpty(outputBackupJar));

		return RemapMinecraftTaskRegistry.claim(project, outputJar, Map.copyOf(identity), () ->
				project.getTasks().register(taskName(remappedJars), RemapMinecraftTask.class, task -> {
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.setDescription("Remaps the %s minecraft jar (%s -> %s)".formatted(remappedJars.type(), fromNamespace, toNamespace));

					// 产出沿用 maven 仓库里的既有路径：消费侧按坐标找它，换路径会让文件依赖悬空
					task.getInputJar().set(remappedJars.inputJar().toFile());
					task.getOutputJar().set(outputJar.toFile());
					// pom 与 backup 是该构件目录里另外两个属于本任务的产物：pom 由 savePom 写（缺它坐标解析失败），
					// backup 是 genSources 的 @Classpath 输入（缺它 genSources 直接抛错）
					task.getOutputPom().set(outputPom.toFile());
					task.getPomGroup().set(mavenHelper.group());
					task.getPomName().set(mavenHelper.name());
					task.getPomVersion().set(mavenHelper.version());
					task.getPomMavenRoot().set(mavenHelper.root().toFile());

					if (outputBackupJar != null) {
						task.getOutputBackupJar().set(outputBackupJar.toFile());
					}

					for (Path path : remappedJars.remapClasspath()) {
						task.getRemapClasspath().from(path);
					}

					task.getMappingsServiceOptions().set(mappingConfiguration.getMappingsServiceOptions(project, MappingOption.forPlatform(extension)));
					task.getFromNamespace().set(fromNamespace);
					task.getToNamespace().set(toNamespace);
					task.getFixRecords().set(fixRecords);
					task.getForgeLike().set(extension.isForgeLike());
					task.getValidateTargetNamespace().set(validateTargetNamespace);
					task.getInnerClassNames().set(innerClassNames);
					task.getKnownIndyBsms().set(extension.getKnownIndyBsms());
					task.getSignatureFixes().set(remappedSignatures);
					// object holder 改写只在「官方命名空间」下需要，其目标命名空间是字面量 named，
					// 与本 provider 的目标命名空间无关（与 remapJar 一致）
					task.getObjectHolderTargetNamespace().set("named");

					// 三个形态开关：必须与配置期同源，否则会在对应形态下**静默**产出不同的 jar
					// （它们的 convention 是 false，漏设不会报错，只会得到与配置期语义不同的产物）。
					//
					// 直通模式：配置期 remapJar 在 disableObfuscation 下不走重映射而是原子复制。
					task.getCopyOnly().set(extension.disableObfuscation());

					// NeoForge 的 mixin 扩展：与 isForgeLike 不是同一语义（NeoForge 是 forgeLike 的真子集，
					// 「Forge 但非 NeoForge」这一支两者取值不同），故用独立输入。
					task.getInjectMixinExtension().set(extension.isNeoForge());

					// 客户端侧 @Environment 访问者：配置期由 configureRemapper 覆写挂上，而其效果
					// （见 configureSplitRemapper）的判据就是「该 jar 非 merged 且含客户端」，是**按 jar**
					// 而非按 provider 的。故这里逐 jar 判定，与静态方法的条件逐字对应。
					//
					// 形态来自 hookKind（verifyProjectable 已先跑过：UNKNOWN 与自相矛盾的声明在那里就被拒了，
					// 到得了这里的只可能是 NO_OP 或 SPLIT_CLIENT_VISITOR_ONLY）：
					// - NO_OP：配置期钩子什么也不挂（或压根没覆写），任务侧恒不挂才是精确复现；
					// - SPLIT_CLIENT_VISITOR_ONLY：按 configureSplitRemapper 的判据逐 jar 决定。
					// 少了「声明」这一环就等于拿未知的钩子内容当空实现，会静默产出比配置期少了改动的 jar。
					final MinecraftJar visitorTargetJar = remappedJars.outputJar();
					task.getInjectClientSidedVisitor().set(
							hookKind == RemapperHookKind.SPLIT_CLIENT_VISITOR_ONLY
									&& !visitorTargetJar.isMerged() && visitorTargetJar.includesClient());

					if (remappedAnnotations != null) {
						task.getAnnotationsJson().set(remappedAnnotations.toJson().toString());
					}

					if (objectHolderClassName != null) {
						task.getObjectHolderClassName().set(objectHolderClassName);
						task.getObjectHolderSourceNamespace().set(objectHolderSourceNamespace);
					}
				}));
	}

	/**
	 * {@return 路径集合的指纹表示：逐个取绝对规范化路径、排序后用换行连接}.
	 *
	 * <p>排序是为了消掉「同一组路径以不同顺序传入」造成的假冲突；换行分隔则避免路径里可能出现的
	 * 分隔符（Windows 盘符里的冒号等）被当成字段分隔。
	 */
	private static String fingerprintPaths(Path... paths) {
		return Arrays.stream(paths)
				.map(path -> path.toAbsolutePath().normalize().toString())
				.sorted()
				.collect(Collectors.joining("\n"));
	}

	/** {@return 字符串集合的指纹表示：排序后用换行连接，理由同 {@link #fingerprintPaths}}. */
	private static String fingerprintValues(Collection<String> values) {
		return values.stream()
				.sorted()
				.collect(Collectors.joining("\n"));
	}

	/** {@return 映射表的指纹表示：先转成 {@code key=value} 再排序连接，理由同 {@link #fingerprintPaths}}. */
	private static String fingerprintEntries(Map<String, String> entries) {
		return fingerprintValues(entries.entrySet().stream()
				.map(entry -> entry.getKey() + "=" + entry.getValue())
				.toList());
	}

	/** {@return 参数的字符串表示；为 {@code null} 时用空串，使指纹里「未设置」只有一种写法}. */
	private static String orEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}

	/** {@return 参数的字符串表示；为 {@code null} 时用空串，理由同 {@link #orEmpty(String)}}. */
	private static String orEmpty(@Nullable Path value) {
		return value == null ? "" : value.toString();
	}

	/**
	 * 检查该 jar 的「配置期语义」是否全部能被当前的任务协议表达.
	 *
	 * <p>能表达的照常投影；不能表达的在这里明确拒绝，而不是静默丢语义——静默丢掉的语义会变成
	 * 「任务成功、产物与配置期不一致」的静默损坏，比构建失败更难查。
	 *
	 * <p>判定按 jar 而不是按 provider：{@code configureRemapper} 覆写的效果本来就是按 jar 的
	 * （split 形态下只有 client-only 那一个 jar 会被挂上 visitor，common jar 什么都不挂），
	 * 所以只要该 jar 上钩子的效果能被任务输入表达就放行，而不是因为「这个 provider 有人覆写过钩子」
	 * 把它所有的 jar 一起拒掉。
	 */
	private void verifyProjectable(RemappedJars remappedJars) {
		verifyRemapperHookProjectable(remappedJars);

		// disableObfuscation 下 remapJar 不改写字节码，而是把输入 jar 原子复制到产物位置（再写 pom）。
		// 任务侧已有对应入口且本次已接线（registerRemapTask 里 task.getCopyOnly().set(extension.disableObfuscation())），
		// 但该形态**没有取得等价性证据**：本次的验收基线只覆盖「official → named 的重映射」，
		// 直通形态（loom-no-remap 插件）既不在基线覆盖内，也没被本次实测过。因此仍拒绝投影，
		// 而不是凭「看起来等价」放行——直通形态的产物就是输入 jar 的副本，一旦投影写错（例如漏了 backup、
		// 或把 deobf 的 pom 写到了别处），产物仍是「构建成功」，静默损坏的代价远高于一次回退。
		// 解除条件：为 disableObfuscation 形态补一份与 RemapMinecraftTaskEquivalenceTest 同口径的对照。
		if (extension.disableObfuscation()) {
			throw new UnsupportedOperationException("disableObfuscation 下 remapJar 走的是「原子复制」而非重映射，"
					+ "该形态的投影（RemapMinecraftTask.getCopyOnly()）尚未取得等价性证据，本次不切换，保持配置期生产。");
		}

		// 曾经这里只警告 NeoForge（配置期把 isNeoForge() 当 injectMixinExtension 传给 L4，而任务侧固定传 false）。
		// 该缺口已关闭：registerRemapTask 用 independent 输入承载了它（task.getInjectMixinExtension()，与
		// isNeoForge() 同源），它不再是一个「无法表达」的语义，因此不再需要警告。
	}

	/**
	 * 检查该 jar 上 {@link #configureRemapper} 覆写的效果是否在任务协议的可表达范围内.
	 *
	 * <p>判定分两级：先归约出钩子的有效形态（{@code resolveRemapperHookKind()}，与事实矛盾的声明在那里被拒），
	 * 再问「这个 jar 是否落在该形态覆盖的范围内」。后者就是「按 jar」的那一半，且只有
	 * {@link RemapperHookKind#SPLIT_CLIENT_VISITOR_ONLY} 有范围问题——它只覆盖 {@code configureSplitRemapper}
	 * 的契约，而该契约开头就 {@code assert !isMerged()}；{@link RemapperHookKind#NO_OP} 的钩子在每个 jar 上
	 * 都不挂东西，没有范围可言。
	 */
	private void verifyRemapperHookProjectable(RemappedJars remappedJars) {
		final RemapperHookKind hookKind = resolveRemapperHookKind();

		if (hookKind != RemapperHookKind.SPLIT_CLIENT_VISITOR_ONLY) {
			// NO_OP：任务侧恒不挂 visitor，与配置期逐 jar 一致，任何 jar 都能投影
			return;
		}

		// 声明为 SPLIT_CLIENT_VISITOR_ONLY 时钩子的效果 = configureSplitRemapper，而它只在非 merged jar 上定义
		// （开头的 assert !isMerged()）。merged jar 上两侧会分叉：assert 不生效时配置期照样会给它挂 visitor，
		// 任务侧的注入条件却恒为 false；assert 生效时配置期直接失败、任务反而会「成功」——失败被丢掉同样是
		// 静默损坏，故这里仍拒绝。
		if (remappedJars.outputJar().isMerged()) {
			throw new UnsupportedOperationException(getClass().getSimpleName()
					+ " 声明了 configureRemapper 覆写等价于 configureSplitRemapper，但该 jar 是 merged 的（"
					+ remappedJars.outputJar() + "）：configureSplitRemapper 的判据只在非 merged jar 上有定义，"
					+ "任务侧的注入条件（非 merged 且含客户端）复现不了它在这个 jar 上的行为。");
		}
	}

	/**
	 * {@return 该 provider 上 {@link #configureRemapper} 钩子在投影视角下的有效形态}.
	 *
	 * <p>把「声明」与「事实」合起来归约，顺便拒绝与事实矛盾的声明：
	 * <ul>
	 *   <li>没有覆写（{@code overridesRemapperHook()} 为假）：基类的钩子是空实现，效果只能是
	 *       {@link RemapperHookKind#NO_OP}。基类默认声明 {@code UNKNOWN} 正是靠这条归约才不至于把
	 *       「压根没覆写」的 provider 也一并拒掉——默认值保守针对的是「有覆写却没声明」的第三方 provider。
	 *       唯一例外是声明 {@link RemapperHookKind#SPLIT_CLIENT_VISITOR_ONLY}：本 provider 压根没有钩子，
	 *       这个声明与事实矛盾，会凭空给任务挂上配置期没有的 visitor，故明确拒绝，而不是静默归约成
	 *       {@code NO_OP}（静默改写会盖掉一处写错的声明）。</li>
	 *   <li>有覆写：以子类的声明为准；声明仍是默认的 {@link RemapperHookKind#UNKNOWN} 即拒绝投影。
	 *       声明 {@link RemapperHookKind#NO_OP} 是对「覆写体什么也不挂」的断言——反射只能确认覆写存在、
	 *       确认不了它干了什么，所以这条断言只能靠覆写体的证据（例如判据恒假）支撑。</li>
	 * </ul>
	 *
	 * @throws UnsupportedOperationException 覆写无法判定（{@link RemapperHookKind#UNKNOWN}），
	 *         或声明「只挂 split visitor」但本 provider 并没有覆写钩子
	 */
	private RemapperHookKind resolveRemapperHookKind() {
		final boolean overridden = overridesRemapperHook();
		final RemapperHookKind declared = remapperHookKind();

		if (!overridden) {
			if (declared == RemapperHookKind.SPLIT_CLIENT_VISITOR_ONLY) {
				throw new UnsupportedOperationException(getClass().getSimpleName()
						+ " 声明 configureRemapper 覆写等价于 configureSplitRemapper（remapperHookKind() 返回 "
						+ "SPLIT_CLIENT_VISITOR_ONLY），但它并没有覆写 configureRemapper：基类钩子是空实现，"
						+ "配置期什么也不会挂，任务侧照该声明挂 visitor 会凭空产出配置期没有的 @Environment(CLIENT)。");
			}

			return RemapperHookKind.NO_OP;
		}

		if (declared == RemapperHookKind.UNKNOWN) {
			throw new UnsupportedOperationException(getClass().getSimpleName() + " 覆写了 configureRemapper，"
					+ "但未声明该覆写的形态（remapperHookKind() 返回 UNKNOWN），"
					+ "本方法无从确认投影不会丢掉覆写的内容，故拒绝而不是静默把它当作空实现。"
					+ "承载「给该 jar 挂 SidedClassVisitor.CLIENT」的入口已经存在："
					+ "RemapMinecraftTask.getInjectClientSidedVisitor()；逐字核对覆写体确认它确实只做这一件事后，"
					+ "在子类覆写 remapperHookKind() 返回 SPLIT_CLIENT_VISITOR_ONLY 即可放行该 jar"
					+ "（若覆写体已被证明是空操作，则返回 NO_OP，任务侧恒不挂 visitor 即为精确复现）。");
		}

		return declared;
	}

	/**
	 * {@return 本 provider 的具体类型是否覆写了 {@link #configureRemapper}}.
	 *
	 * <p>只能用反射判定：钩子是实例方法、基类默认实现是空操作，因此「默认实现未被替换」是配置期唯一
	 * 能确认的事实；钩子挂上了什么要调用它才知道，而那已经不是可序列化的值——后者由
	 * {@link #remapperHookKind()} 这个显式声明承担。
	 *
	 * <p>本方法仍有用武之地：它把「没有覆写」与「有覆写且被声明为空操作」两种情形区分开。
	 * 前者效果必然为空、无需任何声明；后者是对覆写体的一处断言，只有子类给出证据后才成立。
	 * 它也拦住反方向的矛盾声明——没有覆写却声明「只挂 split visitor」，见 {@code resolveRemapperHookKind()}。
	 */
	private boolean overridesRemapperHook() {
		for (Class<?> type = getClass(); type != null && type != AbstractMappedMinecraftProvider.class; type = type.getSuperclass()) {
			for (Method method : type.getDeclaredMethods()) {
				if (method.getName().equals("configureRemapper")
						&& method.getParameterCount() == 2
						&& method.getParameterTypes()[0] == RemappedJars.class) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * 任务名：{@code remapMinecraft<目标命名空间><jar 类型>}.
	 *
	 * <p>目标命名空间 + jar type 足以区分本项目内参与投影的各 provider（每个命名空间只由一个 provider
	 * 产出，每个 type 在每个 provider 内也只出现一次）。若今后真出现重名，Gradle 自己的
	 * 「task with that name already exists」会立刻暴露——那说明判别键不再唯一，要换更细的键，
	 * 而不是靠改名掩盖冲突。
	 */
	private String taskName(RemappedJars remappedJars) {
		return "remapMinecraft" + Strings.capitalize(getTargetNamespace().toString()) + Strings.capitalize(remappedJars.type().toString());
	}

	public Project getProject() {
		return project;
	}

	public M getMinecraftProvider() {
		return minecraftProvider;
	}

	public sealed interface OutputJar permits RemappedJars, SimpleOutputJar {
		MinecraftJar outputJar();

		default MinecraftJar.Type type() {
			return outputJar().getType();
		}
	}

	public record RemappedJars(Path inputJar, MinecraftJar outputJar, MappingsNamespace sourceNamespace, Path... remapClasspath) implements OutputJar {
		public Path outputJarPath() {
			return outputJar().getPath();
		}

		public String name() {
			return outputJar().getName();
		}
	}

	public record SimpleOutputJar(MinecraftJar outputJar) implements OutputJar {
	}
}
