/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2023 FabricMC
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.architectury.loom.mappings.MappingOption;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MinecraftJarProcessor;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.processors.LegacyJarProcessorWrapper;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.processors.ProcessorContextImpl;
import net.fabricmc.loom.configuration.providers.minecraft.LegacyMergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarEnvType;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.SplitMinecraftProvider;
import net.fabricmc.loom.pipeline.ExecutionProcessorContext;
import net.fabricmc.loom.pipeline.ProcessMinecraftJarTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.pipeline.WriteMinecraftJarSidecarsTask;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Strings;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public abstract class ProcessedNamedMinecraftProvider<M extends MinecraftProvider, P extends NamedMinecraftProvider<M>> extends NamedMinecraftProvider<M> {
	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessedNamedMinecraftProvider.class);

	/**
	 * 一个处理后的构件的生产位置：jar 由处理任务产出，同一构件目录里的 pom 与 backup 由伴随任务产出.
	 *
	 * <p>两者分开承载是因为它们是**两条**跨项目共享的产物路径，各自在
	 * {@link RemapMinecraftTaskRegistry} 里独立登记；但消费方要的是「整个构件目录都就位」，
	 * 所以它们成对传递。
	 */
	private record ProcessedArtifact(Producer jar, Producer sidecars) {
	}

	private final P parentMinecraftProvider;
	private final MinecraftJarProcessorManager jarProcessorManager;

	public ProcessedNamedMinecraftProvider(P parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
		super(parentMinecraftProvide.getProject(), parentMinecraftProvide.getMinecraftProvider());
		this.parentMinecraftProvider = parentMinecraftProvide;
		this.jarProcessorManager = Objects.requireNonNull(jarProcessorManager);
	}

	/**
	 * 供给「经 jar processor 链处理过」的命名 jar：**生产已改由任务承担**，本方法不再在配置期写任何产物.
	 *
	 * <p>链上每一步的语义与顺序都不变，变的只是执行时机与失效判定：配置期的
	 * 「存在性检查 → 取跨进程锁 → copyToMaven → 逐 processor 就地改写」整段交给
	 * {@link ProcessMinecraftJarTask}，构件目录里的另外两个文件（pom、backup）由
	 * {@link WriteMinecraftJarSidecarsTask} 在链跑完之后补出。
	 *
	 * <p>父 provider 先供给：它的产出是本任务链的输入，两者的任务依赖由
	 * {@link #registerJarProcessTask} 里声明输入 jar 的那一处建立。
	 *
	 * <h4>不可投影时整批回退，且必留痕</h4>
	 * 链上只要有一环无法在执行期重建（废弃 JarProcessor API 的包装、第三方 processor 未实现
	 * {@code descriptor()}），整条链都无法任务化——跳过某一环会让任务「成功」但产物与配置期不同。
	 * 这种情况整批回退到配置期（{@link #produceInConfiguration}），并打出被拒的具体原因。
	 */
	@Override
	public List<MinecraftJar> provide(ProvideContext context) throws Exception {
		final List<MinecraftJar> parentMinecraftJars = parentMinecraftProvider.getMinecraftJars();
		final Map<MinecraftJar, MinecraftJar> minecraftJarOutputMap = parentMinecraftJars.stream()
				.collect(Collectors.toMap(Function.identity(), this::getProcessedJar));
		final List<MinecraftJar> minecraftJars = List.copyOf(minecraftJarOutputMap.values());

		parentMinecraftProvider.provide(context.withApplyDependencies(false));

		final Map<MinecraftJar.Type, ProcessedArtifact> processTasks = registerJarProcessTasks(minecraftJarOutputMap);

		if (processTasks == null) {
			produceInConfiguration(context, minecraftJarOutputMap, minecraftJars);
		} else {
			extension.setMinecraftJarsTaskOutputs(getTargetNamespace(), processedJarOutputs(processTasks));
		}

		if (context.applyDependencies()) {
			applyDependencies(processTasks);
		}

		return List.copyOf(minecraftJarOutputMap.values());
	}

	/**
	 * 为每个命名 jar 注册「processor 链处理」任务，并注册补出 pom / backup 的伴随任务.
	 *
	 * <p>链的判定先于注册：只要有一环无法在执行期重建就整批回退（返回 {@code null}），
	 * 而不是把不可重建的那一环从链上摘掉——摘掉之后任务照样成功，产物却比配置期少了这一环的改动。
	 *
	 * @return 每个 jar 类型对应的生产位置；链不可任务化时返回 {@code null}
	 */
	private @Nullable Map<MinecraftJar.Type, ProcessedArtifact> registerJarProcessTasks(Map<MinecraftJar, MinecraftJar> minecraftJarOutputMap) {
		// disableObfuscation 下没有 MappingConfiguration：任务协议里的 mappingsServiceOptions 是必填的
		// {@code @Nested} 输入（执行期上下文靠它取映射树），装配不出来。这与父 provider 拒绝投影该形态
		// 是同一条理由的两面，故同样保持配置期生产，并打日志说明原因。
		// 注意本判定必须在取链之前：取链本身不依赖映射配置，但装配任务输入会。
		if (extension.disableObfuscation()) {
			LOGGER.warn("{} 处于 disableObfuscation 形态：该形态没有 MappingConfiguration，"
					+ "处理链的任务输入装配不出来，本 provider 保持配置期生产。", getClass().getSimpleName());
			return null;
		}

		final List<MinecraftJarProcessor<?>> processors = jarProcessorManager.getProcessors();
		final List<MinecraftJarProcessor.Spec> specs = jarProcessorManager.getSpecs();

		for (MinecraftJarProcessor<?> processor : processors) {
			try {
				requireDescriptor(processor);
			} catch (UnsupportedOperationException e) {
				LOGGER.warn("{} 的 jar processor 链无法投影成任务，本 provider 保持配置期生产：{}",
						getClass().getSimpleName(), e.getMessage());
				return null;
			}
		}

		final Map<MinecraftJar.Type, ProcessedArtifact> tasks = new LinkedHashMap<>();

		for (MinecraftJar parentJar : minecraftJarOutputMap.keySet()) {
			final Producer inputJar = parentJarInput(parentJar);
			final Map<String, String> identity = processedArtifactIdentity(parentJar, inputJar, processors, specs);
			final Producer processTask = registerJarProcessTask(parentJar, inputJar, identity, processors, specs);
			final Producer sidecarTask = registerSidecarTask(minecraftJarOutputMap.get(parentJar), processTask, identity);
			tasks.put(parentJar.getType(), new ProcessedArtifact(processTask, sidecarTask));
		}

		return tasks;
	}

	/**
	 * {@return 该命名 jar 作为处理任务输入时的生产位置}.
	 *
	 * <p>优先取父 provider 登记到的产出任务：这样「处理链的输入 = 重映射的产出」就是一条真正的任务依赖，
	 * 而不是两条各自按路径读同一个文件的独立任务。父 provider 未切到任务时（不可投影或 legacy merged）
	 * 退化为裸路径——那时产物确实由配置期生产，没有产出任务可依赖。
	 *
	 * <p>父 provider 的产物由**另一个 Loom classloader** 里的任务产出时（同一构建内多个项目各带一份 Loom
	 * 时会发生）本方法不需要另做区分：登记表给出的产出任务路径与产物路径都不带 classloader 身份，
	 * 因此这里照常拿到一条可用的依赖边（见 {@link RemapMinecraftTaskRegistry}）。
	 */
	private Producer parentJarInput(MinecraftJar parentJar) {
		final Producer parentProducer = parentMinecraftProvider.getRemapTask(parentJar.getType());

		if (parentProducer != null) {
			return parentProducer;
		}

		return new Producer(parentJar.getPath().toAbsolutePath().normalize(), null);
	}

	/**
	 * {@return 该处理构件（jar + pom + backup）的输入指纹}.
	 *
	 * <p>指纹是「同一路径只能有一个生产者」这件事的判据（见 {@link RemapMinecraftTaskRegistry}）：
	 * 产物路径本身已经含 MC 版本、映射标识、jar processor 哈希与 jar 类型，但仍有一批按项目设置的输入
	 * 不进路径（处理链本身、源/平台命名空间、remapper 的 classpath）。两个项目若在这些项上不同却算出同一条
	 * 路径，共享就会把 A 的产物当成 B 的产物，因此必须显式比对、不同即拒绝。
	 *
	 * @param parentJar 待处理的命名 jar
	 * @param inputJar 处理链的输入（父 provider 的产出）
	 * @param processors 有序的 processor 链
	 * @param specs 与 {@code processors} 一一对应的 spec
	 */
	private Map<String, String> processedArtifactIdentity(MinecraftJar parentJar, Producer inputJar,
			List<MinecraftJarProcessor<?>> processors, List<MinecraftJarProcessor.Spec> specs) {
		final Map<String, String> identity = new LinkedHashMap<>();
		identity.put("inputJar", inputJar.artifact().toString());
		identity.put("inputJarTask", inputJar.taskPath() == null ? "" : inputJar.taskPath());
		identity.put("outputJar", getProcessedJar(parentJar).getPath().toAbsolutePath().normalize().toString());
		identity.put("jarType", parentJar.getType().toString());
		// 取 JarConfigurationKind 而不是配置对象本身：后者的 toString 里含工厂 lambda 的身份哈希，
		// 两个项目即使配置完全相同也会算出不同的字符串，指纹会变成假冲突
		identity.put("jarConfiguration", ExecutionProcessorContext.JarConfigurationKind.of(extension.getMinecraftJarConfiguration().get()).name());
		identity.put("productionNamespace", extension.getProductionNamespaceEnum().get().toString());
		identity.put("intermediaryNamespace", IntermediaryNamespaces.intermediaryNamespace(getProject()).toString());
		identity.put("disableObfuscation", Boolean.toString(extension.disableObfuscation()));
		identity.put("requiresBackupJars", Boolean.toString(requiresBackupJars()));
		identity.put("processors", processors.stream().map(MinecraftJarProcessor::getName).toList().toString());
		identity.put("specFingerprints", specs.stream().map(spec -> Integer.toString(spec.hashCode())).toList().toString());
		// remapper 的 classpath 与配置期 ContextImplHelper.createRemapper 同源：都是「被处理的这批
		// MC jar」，也就是父 provider 的 jar。取配置路径而不是集合内容：后者要求文件已落位，
		// 冷缓存下配置期还取不到。
		//
		// 必须走父 provider 而不是 extension.getMinecraftJars(productionNamespace)：后者在 Forge 上
		// 会取 SRG 命名空间，而本方法跑在 provide() 的锁内、srg provider 尚未 setup，会直接 NPE
		// （实测：Forge 的 AccessTransformerTest 因此挂在 setupMinecraft）。
		identity.put("remapClasspath", parentMinecraftProvider.getMinecraftJars().stream()
				.map(jar -> jar.getPath().toAbsolutePath().normalize().toString())
				.sorted()
				.collect(Collectors.joining("\n")));
		return identity;
	}

	/**
	 * 注册补出 pom 与 backup 的伴随任务，并让它依赖处理任务.
	 *
	 * <p>两个文件的位置都不由本方法决定：pom 由 {@link LocalMavenHelper#savePom} 按坐标落位，
	 * backup 由 {@link AbstractMappedMinecraftProvider#getBackupJarPath} 按 jar 落位。本方法只把
	 * 这两处算出的路径声明成输出——声明错了会写出没人读的文件，而下游只会在找不到时失败。
	 *
	 * <p>与处理任务同因同理，本任务也走 {@link RemapMinecraftTaskRegistry}：它写出的 pom 与 backup 同样落在
	 * 构建根下的共享仓库里，同一条路径上也只允许有一个生产者。
	 *
	 * @param outputJar 处理后的 jar（伴随任务的产物与它同处一个构件目录）
	 * @param processTask 产出 {@code outputJar} 的处理任务
	 * @param identity 与处理任务同一份输入指纹（两者产出的是同一个构件目录）
	 * @return 该构件目录里 pom 与 backup 的生产位置
	 */
	private Producer registerSidecarTask(MinecraftJar outputJar, Producer processTask, Map<String, String> identity) {
		final Project project = getProject();
		final MinecraftJar.Type type = outputJar.getType();
		final LocalMavenHelper mavenHelper = getMavenHelper(type);
		final Path outputJarPath = outputJar.getPath();
		final Path outputPomPath = RemapMinecraftTask.pomPathFor(outputJarPath);
		final boolean backupJar = requiresBackupJars();

		return RemapMinecraftTaskRegistry.claim(project, outputPomPath, identity, () ->
				project.getTasks().register("writeMinecraftJarSidecars" + Strings.capitalize(type.toString()),
						WriteMinecraftJarSidecarsTask.class, task -> {
							task.setGroup(Constants.TaskGroup.FABRIC);
							task.setDescription("Writes the maven pom and backup jar for the %s minecraft jar".formatted(type));

							// 输入必须来自处理任务，否则本任务可能先于链执行，复制出一份半成品。
							// 依赖按任务路径声明：产出方可能是另一个 Loom classloader 里的任务
							task.getJar().set(processTask.artifact().toFile());

							if (processTask.taskPath() != null) {
								task.dependsOn(processTask.taskPath());
							}

							task.getPom().set(outputPomPath.toFile());
							task.getPomGroup().set(mavenHelper.group());
							task.getPomName().set(mavenHelper.name());
							task.getPomVersion().set(mavenHelper.version());
							// 声明成字符串而不是 File：maven 仓库根在不同机器/不同工作树上不同，
							// 而它是本任务身份的组成部分，必须是可序列化的值
							task.getPomMavenRoot().set(mavenHelper.root().toString());

							if (backupJar) {
								task.getBackupJar().set(AbstractMappedMinecraftProvider.getBackupJarPath(outputJar).toFile());
							}
						}));
	}

	/**
	 * {@return 由处理任务与伴随任务的产出构成的文件集合} 其任务依赖随集合传播给消费方.
	 *
	 * <p>集合里只有 jar 本身；pom 与 backup 通过 {@code builtBy} 挂在同一集合上——它们不是类路径条目，
	 * 但「构件目录里的三个文件要么都在、要么都不在」是配置期就成立的契约，消费方按文件消费该 jar 时
	 * 不该看到一个只有 jar 的构件目录。
	 */
	private ConfigurableFileCollection processedJarOutputs(Map<MinecraftJar.Type, ProcessedArtifact> processTasks) {
		final ConfigurableFileCollection outputs = getProject().getObjects().fileCollection();

		for (ProcessedArtifact artifact : processTasks.values()) {
			outputs.from(processedJarOutput(artifact));
		}

		return outputs;
	}

	/** {@return 单个 jar 的产出集合：jar 本身 + 补出 pom/backup 的伴随任务} 理由见 {@link #processedJarOutputs}. */
	private ConfigurableFileCollection processedJarOutput(ProcessedArtifact artifact) {
		final ConfigurableFileCollection output = getProject().getObjects().fileCollection();
		output.from(artifact.jar().artifact().toFile());
		// 依赖按任务路径声明，理由见 registerSidecarTask
		addProducerDependency(output, artifact.jar());
		addProducerDependency(output, artifact.sidecars());
		return output;
	}

	/** 把一条产物的产出任务挂到文件集合上；无产出任务（配置期生产）时什么也不挂. */
	private static void addProducerDependency(ConfigurableFileCollection files, Producer producer) {
		if (producer.taskPath() != null) {
			files.builtBy(producer.taskPath());
		}
	}

	/**
	 * 配置期生产：本次改造前的路径，只对「processor 链不可任务化」的形态保留.
	 *
	 * <p>整段语义与改造前逐字一致——无锁快路径、按 processor 哈希区分的跨进程锁、锁内二次确认、
	 * 原子落位、逐 processor 就地改写、原子备份。它没有被删除，是因为仍有形态走这里（见
	 * {@link #registerJarProcessTasks}）；后续步骤会随这些形态的投影方案一并处理。
	 */
	private void produceInConfiguration(ProvideContext context, Map<MinecraftJar, MinecraftJar> minecraftJarOutputMap, List<MinecraftJar> minecraftJars) throws Exception {
		// 无锁快路径：requiresProcessing 仅做存在性/处理判定，缓存就绪时不会进入下面的锁
		boolean requiresProcessing = shouldRefreshOutputs(context) || minecraftJarOutputMap.keySet().stream()
				.map(this::getProcessedPath)
				.anyMatch(jarProcessorManager::requiresProcessingJar);

		if (requiresProcessing) {
			final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
			final Path lockRoot = extension.getFiles().getCacheLocks().toPath();
			// 产物写入 LOCAL 仓库但跨同根多子项目共享（getName 含 jar processor 哈希），--parallel 下需 per-key 互斥。
			// key 含 processor 哈希以与父类 remap 锁区分；父 provide() 已在上面返回，此处非嵌套持锁。
			final String key = "processed:" + getTargetNamespace().name() + ":" + getVersion() + ":" + jarProcessorManager.getJarHash();

			cacheService.runExclusive(lockRoot, key, LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：可能已被其它进程/线程在等锁期间处理完成
				boolean stillRequiresProcessing = shouldRefreshOutputs(context) || minecraftJarOutputMap.keySet().stream()
						.map(this::getProcessedPath)
						.anyMatch(jarProcessorManager::requiresProcessingJar);

				if (stillRequiresProcessing) {
					processJars(minecraftJarOutputMap, context.configContext());
					createBackupJars(minecraftJars);
				}

				return null;
			});
		}
	}

	/**
	 * 把「本项目编译/运行时会用到本 provider 产物」这件事注入对应配置.
	 *
	 * <h4>任务路径下注入的是文件而不是 maven 坐标</h4>
	 * 与父 provider 同因同理（见 {@code AbstractMappedMinecraftProvider.applyDependencies}）：
	 * 产物改为执行期落位后，按坐标注入会在冷缓存上先于产出就失败。注入的仍是同一条路径上的文件，
	 * 因此编译期类路径的内容不变。
	 */
	private void applyDependencies(@Nullable Map<MinecraftJar.Type, ProcessedArtifact> processTasks) {
		final List<MinecraftJar.Type> dependencyTargets = getDependencyTypes();

		if (dependencyTargets.isEmpty()) {
			return;
		}

		MinecraftSourceSets.get(getProject()).applyDependencies(
				(configuration, type) -> getProject().getDependencies().add(configuration, dependencyNotation(type, processTasks)),
				dependencyTargets
		);
	}

	/** {@return 该 jar 类型的依赖表示：有处理任务则是有任务依赖的文件集合，否则是 maven 坐标}. */
	private Object dependencyNotation(MinecraftJar.Type type, @Nullable Map<MinecraftJar.Type, ProcessedArtifact> processTasks) {
		final ProcessedArtifact artifact = processTasks == null ? null : processTasks.get(type);

		if (artifact == null) {
			return getDependencyNotation(type);
		}

		// 与登记进消费侧的那个集合同源：拿到类路径条目的一方同时也拿到了「pom 与 backup 会就位」这件事
		return getProject().files(processedJarOutput(artifact));
	}

	@Override
	public List<? extends OutputJar> getOutputJars() {
		return parentMinecraftProvider.getMinecraftJars().stream()
				.map(this::getProcessedJar)
				.map(SimpleOutputJar::new)
				.toList();
	}

	@Override
	public MavenScope getMavenScope() {
		return MavenScope.LOCAL;
	}

	private void processJars(Map<MinecraftJar, MinecraftJar> minecraftJarMap, ConfigContext configContext) throws IOException {
		for (Map.Entry<MinecraftJar, MinecraftJar> entry : minecraftJarMap.entrySet()) {
			final MinecraftJar minecraftJar = entry.getKey();
			final MinecraftJar outputJar = entry.getValue();

			// 此处刻意不做任何「清理同目录旧产物」的删除，理由如下（历史上这里按文件名前缀扫删，已移除）：
			// 1. 产物目录名由 jarPrefix、type 与 jar processor 缓存值哈希共同决定（见 getName），
			//    映射与 MC 版本又在目录的 version 段，因此「同目录」等价于「同内容」，不会残留与本次产物不一致的陈旧 jar；
			// 2. 写入侧由 copyToMaven 以「唯一临时文件 + 原子 move」落位，目标 jar 被整体替换，无需先删；
			// 3. 按前缀扫描会误删同目录的别的共享文件：本构件自己的 .pom、作为 genSources @Classpath 输入的
			//    <jar>.backup，以及其它进程正在写的临时文件（copyToMaven/savePom 的 <名>+随机数+.tmp 与
			//    AtomicFiles.tempSibling 的 <名>.<uuid>.tmp.jar 都命中该前缀）。删掉别人的在途临时文件会让
			//    对方的原子 move 直接失败，这正是多工作树/多 daemon 共用缓存时互相破坏产物的来源。
			// 该删除原本用于清理「jar processor 变更后残留的 -sources.jar」（#560）；自产物名引入 processor
			// 哈希后配置变更会自然换目录，且 GenerateSourcesTask 的内容级输入判定会让 genSources 重跑，
			// 因此该清理已无必要，其唯一的实际效果是让并发构建方互相删除对方还在用的产物。
			final LocalMavenHelper mavenHelper = getMavenHelper(minecraftJar.getType());
			final Path outputPath = mavenHelper.copyToMaven(minecraftJar.getPath(), null);

			assert outputJar.getPath().equals(outputPath);

			jarProcessorManager.processJar(outputPath, new ProcessorContextImpl(configContext, minecraftJar));
		}
	}

	@Override
	public List<MinecraftJar.Type> getDependencyTypes() {
		return parentMinecraftProvider.getDependencyTypes();
	}

	@Override
	protected String getName(MinecraftJar.Type type) {
		final String jarPrefix = parentMinecraftProvider.getMinecraftProvider().getJarPrefix();
		// Hash the cache value so that we don't have to process the same JAR multiple times for many projects
		return jarPrefix + "minecraft-%s-%s".formatted(type.toString(), jarProcessorManager.getJarHash());
	}

	@Override
	public Path getJar(MinecraftJar.Type type) {
		// Something has gone wrong if this gets called.
		throw new UnsupportedOperationException();
	}

	@Override
	public List<RemappedJars> getRemappedJars() {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<MinecraftJar> getMinecraftJars() {
		return getParentMinecraftProvider().getMinecraftJars().stream()
				.map(this::getProcessedJar)
				.toList();
	}

	public P getParentMinecraftProvider() {
		return parentMinecraftProvider;
	}

	private Path getProcessedPath(MinecraftJar minecraftJar) {
		final LocalMavenHelper mavenHelper = getMavenHelper(minecraftJar.getType());
		return mavenHelper.getOutputFile(null);
	}

	public MinecraftJar getProcessedJar(MinecraftJar minecraftJar) {
		return minecraftJar.forPath(getProcessedPath(minecraftJar));
	}

	/**
	 * 为给定的命名 jar 注册「processor 链处理」任务.
	 *
	 * <h4>输入怎么来</h4>
	 * <ul>
	 *   <li>输入 jar ← 父 provider 的产出（{@link MinecraftJar#getPath()}）；产物 ← 本 provider 的
	 *       maven 位置（{@link #getProcessedJar(MinecraftJar)}），与配置期 {@code copyToMaven}
	 *       写入的路径逐位相同，消费侧按坐标找得到的仍是同一个文件。</li>
	 *   <li>链由 {@code processors} 与 {@code specs} 两张有序表给出：descriptor 由每个 processor 的
	 *       {@code descriptor()} 取出，spec 就是它本轮的 spec，指纹取 {@code spec.hashCode()}
	 *       （spec 不可序列化，理由见 {@link ProcessMinecraftJarTask} 的类注释）。</li>
	 *   <li>执行期上下文所需的可序列化值 ← 与 {@code ProcessorContextImpl} 同源：jar 配置身份取
	 *       {@code MinecraftJarConfiguration}、jar 类型取父 jar 的类型、是否禁用混淆取 extension、
	 *       生产与平台命名空间取 {@code getProductionNamespaceEnum()}/{@link IntermediaryNamespaces}。</li>
	 *   <li>两个服务配置 ← 映射取平台映射选项（与 {@code ProcessorContextImpl.getMappings()} 同一来源）；
	 *       remapper 取「生产命名空间 → {@code named}」，即 processor 请求的那一对。</li>
	 * </ul>
	 *
	 * <h4>remapper 装配有一处待核验的差异</h4>
	 * 配置期由 {@code ContextImplHelper.createRemapper} 装配：映射取平台映射选项、classpath 取
	 * {@code extension.getMinecraftJars(from)}、再经 {@code TinyRemapperHelper.getTinyRemapper} 构造
	 * （{@code fixRecords=false}、{@code validateTargetNamespace=true}）。这里沿用同一个 classpath 来源，
	 * 但 remapper 由 {@link TinyRemapperService} 构造——它没有上面两个开关，映射走
	 * {@code MappingsService.createOptionsWithProjectMappings}。两者是否逐位等价尚未验证，属于接线批次
	 * 必须核验的项（既有经验：remapper 换个装配路径就得到过与配置期不同的产物）。
	 *
	 * <h4>本任务不产出的文件</h4>
	 * 处理任务只产出 jar：{@code <name>-<version>.pom}（{@code LocalMavenHelper.savePom}）与
	 * {@code <jar>.backup}（genSources 的输入）都不在它的输出里，接线时必须由别处继续产出，
	 * 理由见 {@link ProcessMinecraftJarTask} 的类注释。
	 *
	 * <h4>链从哪来</h4>
	 * 本方法按「调用方给出有序的 (processor, spec) 链」设计：链的唯一权威是
	 * {@code MinecraftJarProcessorManager.processJar} 处理的那个列表（它已经调过
	 * {@code buildSpec} 并丢弃了 spec 为 null 的 processor），在别处重建会得到第二份语义。
	 * 接线处因此不给它立第二份来源，而是用该 manager 的只读访问器
	 * （{@code getProcessors()} / {@code getSpecs()}）取出同一张表。
	 *
	 * @param parentJar 待处理的命名 jar，须是父 provider 的产出之一
	 * @param inputJar 该 jar 的生产位置（见 {@link #parentJarInput(MinecraftJar)}）：只要它带产出任务，
	 *        本任务就会对该任务路径声明依赖（{@code dependsOn}）。只按路径声明输入且没有这条边时，
	 *        链可能在产物落位之前就开跑，冷缓存下直接失败，而且会撞上 Gradle 的隐式依赖校验。
	 *        父 provider 未切到任务（产物配置期已落盘）时才可以退化为裸路径。
	 * @param identity 该处理构件的输入指纹（见 {@link #processedArtifactIdentity}）：产物路径相同但指纹不同的
	 *        登记会被拒绝，而不是让两个项目往同一个路径上写各自配置的产物
	 * @param processors 有序的 processor 链；顺序即处理顺序，且须与 {@code specs} 一一对应
	 * @param specs 与 {@code processors} 一一对应的 spec
	 * @return 该 jar 的生产位置
	 * @throws UnsupportedOperationException 链上存在无法在执行期重建的 processor
	 */
	public Producer registerJarProcessTask(MinecraftJar parentJar, Producer inputJar, Map<String, String> identity,
			List<MinecraftJarProcessor<?>> processors, List<MinecraftJarProcessor.Spec> specs) {
		if (processors.isEmpty()) {
			throw new IllegalArgumentException("processor 链为空：没有可注册的处理任务");
		}

		// List.copyOf 顺带拒绝 null 元素：链上任何一环缺失都不该被静默跳过
		final List<MinecraftJarProcessor.Spec> chainSpecs = List.copyOf(specs);

		if (processors.size() != chainSpecs.size()) {
			throw new IllegalArgumentException("processor 链与 spec 数量不一致：processors=%d, specs=%d"
					.formatted(processors.size(), chainSpecs.size()));
		}

		final Project project = getProject();
		final List<MinecraftJarProcessor.ProcessorDescriptor<?>> descriptors = new ArrayList<>(processors.size());

		for (MinecraftJarProcessor<?> processor : processors) {
			descriptors.add(requireDescriptor(processor));
		}

		final MinecraftJar.Type jarType = parentJar.getType();
		final MappingsNamespace productionNamespace = extension.getProductionNamespaceEnum().get();
		final MappingsNamespace targetNamespace = getTargetNamespace();
		// remapper 的 classpath 与配置期 ContextImplHelper.createRemapper 同源（源命名空间的 MC jar）。
		// 走 getMinecraftJarsCollection 而不是 getMinecraftJars：前者在产出已登记为任务时携带产出任务，
		// 否则本任务可能先于上游 remap 执行（配置期不需要这个依赖，执行期需要）。
		final FileCollection remapClasspath = extension.getMinecraftJarsCollection(productionNamespace);

		final Path outputJar = getProcessedJar(parentJar).getPath();

		// 产物落在构建根下的共享仓库（MavenScope.LOCAL），同一条路径上只允许有一个生产者：
		// 同一构建内的每个 Loom 项目都会为同一份 MC + 同一批 jar processor 算出同一条路径
		return RemapMinecraftTaskRegistry.claim(project, outputJar, identity, () ->
				project.getTasks().register(taskName(jarType), ProcessMinecraftJarTask.class, task -> {
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.setDescription("Processes the %s minecraft jar with %d jar processor(s)".formatted(jarType, processors.size()));

					task.getInputJar().set(inputJar.artifact().toFile());

					// 依赖按**任务路径**而不是任务实例声明：产出方可能是另一个 Loom classloader 里的任务
					// （见 RemapMinecraftTaskRegistry 的类注释），实例过不来，任务路径可以
					if (inputJar.taskPath() != null) {
						task.dependsOn(inputJar.taskPath());
					}

					task.getOutputJar().set(outputJar.toFile());
					task.getProcessorDescriptors().set(descriptors);
					task.getProcessorSpecs().set(chainSpecs);
					task.getSpecFingerprints().set(chainSpecs.stream().map(spec -> spec.hashCode()).toList());
					task.getJarConfiguration().set(ExecutionProcessorContext.JarConfigurationKind.of(extension.getMinecraftJarConfiguration().get()));
					task.getJarType().set(jarType);
					task.getDisableObfuscation().set(extension.disableObfuscation());
					task.getProductionNamespace().set(productionNamespace);
					task.getIntermediaryNamespace().set(IntermediaryNamespaces.intermediaryNamespace(project));
					task.getMappingsServiceOptions().set(extension.getMappingConfiguration().getMappingsServiceOptions(project, MappingOption.forPlatform(extension)));
					// 映射树取自迁移产物：投影时它由迁移任务产出，按路径声明输入不带任务依赖，必须显式接线
					extension.getMappingConfiguration().addMappingsProducerDependency(task);
					task.getRemapperServiceOptions().set(TinyRemapperService.createSimple(project,
							project.provider(() -> productionNamespace.toString()),
							project.provider(() -> targetNamespace.toString()),
							remapClasspath));
				}));
	}

	/**
	 * 取出 processor 的执行期重建描述符；取不到时明确拒绝，而不是把它从链上悄悄摘掉.
	 *
	 * <p>拒绝而非静默丢弃是刻意的：少跑一环得到的仍然是「任务成功」，但产物与配置期不同——
	 * 这种静默损坏比构建失败难查得多。故此处一次性拒绝整条链，并在消息里说清为什么本链无法任务化。
	 *
	 * @param processor 链上的一环
	 * @return 它的描述符
	 * @throws UnsupportedOperationException 该 processor 无法在执行期重建
	 */
	private static MinecraftJarProcessor.ProcessorDescriptor<?> requireDescriptor(MinecraftJarProcessor<?> processor) {
		// 废弃 API 的包装：它持有任意的用户 delegate（第三方 JarProcessor），delegate 本身没有任何
		// 可序列化的重建信息，故这一环永远无法在执行期重建，整条链也就无法任务化
		if (processor instanceof LegacyJarProcessorWrapper) {
			throw new UnsupportedOperationException("processor '%s' 是废弃 JarProcessor API 的包装（LegacyJarProcessorWrapper），"
					+ "它持有任意的用户 delegate 且无法在执行期重建，因此整条 processor 链无法任务化。"
					+ "可选路径：把该 processor 迁移到 MinecraftJarProcessor API 并实现 descriptor()，"
					+ "或者这条链继续走配置期路径（不要为它注册处理任务）。拒绝而不是跳过它：跳过后任务会成功，"
					+ "但产物比配置期少了这一环的改动。".formatted(processor.getName()));
		}

		final MinecraftJarProcessor.ProcessorDescriptor<?> descriptor;

		try {
			descriptor = processor.descriptor();
		} catch (UnsupportedOperationException e) {
			// 第三方 processor 未实现 descriptor() 时走这里：默认实现抛异常，本处补上「整条链不可任务化」的上下文
			throw new UnsupportedOperationException("processor '%s' 无法在执行期重建，整条 processor 链无法任务化。"
					+ "拒绝而不是跳过它：跳过后任务会成功，但产物比配置期少了这一环的改动。".formatted(processor.getName()), e);
		}

		return Objects.requireNonNull(descriptor, () -> "processor '%s' 的 descriptor() 返回了 null".formatted(processor.getName()));
	}

	/** {@return 本 provider 处理指定 jar 类型的任务名} 与 remap 任务同构，按目标命名空间与 jar 类型区分. */
	private String taskName(MinecraftJar.Type type) {
		return "processMinecraft" + Strings.capitalize(getTargetNamespace().toString()) + Strings.capitalize(type.toString());
	}

	public static final class MergedImpl extends ProcessedNamedMinecraftProvider<MergedMinecraftProvider, NamedMinecraftProvider.MergedImpl> implements Merged {
		public MergedImpl(NamedMinecraftProvider.MergedImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvide, jarProcessorManager);
		}

		@Override
		public MinecraftJar getMergedJar() {
			return getProcessedJar(getParentMinecraftProvider().getMergedJar());
		}
	}

	public static final class LegacyMergedImpl extends ProcessedNamedMinecraftProvider<LegacyMergedMinecraftProvider, NamedMinecraftProvider.LegacyMergedImpl> implements Merged {
		public LegacyMergedImpl(NamedMinecraftProvider.LegacyMergedImpl parentMinecraftProvider, MinecraftJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvider, jarProcessorManager);
		}

		@Override
		public MinecraftJar getMergedJar() {
			return getProcessedJar(getParentMinecraftProvider().getMergedJar());
		}
	}

	public static final class SplitImpl extends ProcessedNamedMinecraftProvider<SplitMinecraftProvider, NamedMinecraftProvider.SplitImpl> implements Split {
		public SplitImpl(NamedMinecraftProvider.SplitImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvide, jarProcessorManager);
		}

		@Override
		public MinecraftJar getCommonJar() {
			return getProcessedJar(getParentMinecraftProvider().getCommonJar());
		}

		@Override
		public MinecraftJar getClientOnlyJar() {
			return getProcessedJar(getParentMinecraftProvider().getClientOnlyJar());
		}
	}

	public static final class SingleJarImpl extends ProcessedNamedMinecraftProvider<SingleJarMinecraftProvider, NamedMinecraftProvider.SingleJarImpl> implements SingleJar {
		private final SingleJarEnvType env;

		private SingleJarImpl(NamedMinecraftProvider.SingleJarImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager, SingleJarEnvType env) {
			super(parentMinecraftProvide, jarProcessorManager);
			this.env = env;
		}

		public static ProcessedNamedMinecraftProvider.SingleJarImpl server(NamedMinecraftProvider.SingleJarImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			return new ProcessedNamedMinecraftProvider.SingleJarImpl(parentMinecraftProvide, jarProcessorManager, SingleJarEnvType.SERVER);
		}

		public static ProcessedNamedMinecraftProvider.SingleJarImpl client(NamedMinecraftProvider.SingleJarImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			return new ProcessedNamedMinecraftProvider.SingleJarImpl(parentMinecraftProvide, jarProcessorManager, SingleJarEnvType.CLIENT);
		}

		@Override
		public MinecraftJar getEnvOnlyJar() {
			return getProcessedJar(getParentMinecraftProvider().getEnvOnlyJar());
		}

		@Override
		public SingleJarEnvType env() {
			return env;
		}
	}
}
