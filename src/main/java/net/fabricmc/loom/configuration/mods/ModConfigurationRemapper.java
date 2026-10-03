/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019-2023 FabricMC
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

package net.fabricmc.loom.configuration.mods;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import dev.architectury.loom.mappings.MappingOption;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.MutableVersionConstraint;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.query.ArtifactResolutionQuery;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.jvm.JvmLibrary;
import org.gradle.language.base.artifact.SourcesArtifact;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.RemapConfigurations;
import net.fabricmc.loom.configuration.mods.dependency.ModDependency;
import net.fabricmc.loom.configuration.mods.dependency.ModDependencyFactory;
import net.fabricmc.loom.configuration.mods.dependency.ModDependencyOptions;
import net.fabricmc.loom.configuration.mods.dependency.RemappedModArtifacts;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.pipeline.RemapModsTask;
import net.fabricmc.loom.util.AsyncCache;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.loom.util.kotlin.KotlinClasspathService;
import net.fabricmc.loom.util.service.ServiceFactory;

@SuppressWarnings("UnstableApiUsage")
public class ModConfigurationRemapper {
	// This is a placeholder that is used when the actual group is missing (null or empty).
	// This can happen when the dependency is a FileCollectionDependency or from a flatDir repository.
	public static final String MISSING_GROUP = "unspecified";

	private static final Logger LOGGER = LoggerFactory.getLogger(ModConfigurationRemapper.class);
	private static final String ARTIFACT_METADATA_CACHE = "artifactMetadata";

	public static void supplyModConfigurations(Project project, ServiceFactory serviceFactory, String mappingsSuffix, LoomGradleExtension extension, SourceRemapper sourceRemapper) {
		final DependencyHandler dependencies = project.getDependencies();
		// The configurations where the source and remapped artifacts go.
		// key: source, value: target
		final Map<Provider<? extends Configuration>, NamedDomainObjectProvider<? extends Configuration>> configsToRemap = new LinkedHashMap<>();
		// Client remapped dep collectors for split source sets. Same keys and values.
		final Map<Provider<? extends Configuration>, NamedDomainObjectProvider<? extends Configuration>> clientConfigsToRemap = new HashMap<>();

		/*
		 * Hack fix/improvement for https://github.com/FabricMC/fabric-loom/issues/1012
		 * Ensure that modImplementation is processed first, so any installer.json on that configuration takes priority.
		 */
		final List<RemapConfigurationSettings> remapConfigurationSettings = extension.getRemapConfigurations()
				.stream()
				.sorted(Comparator.comparing(setting -> !setting.getName().equals("modImplementation")))
				.toList();

		for (RemapConfigurationSettings entry : remapConfigurationSettings) {
			// key: true if runtime, false if compile
			final Map<Boolean, Boolean> envToEnabled = Map.of(
					false, entry.getOnCompileClasspath().get(),
					true, entry.getOnRuntimeClasspath().get()
			);

			envToEnabled.forEach((runtime, enabled) -> {
				if (!enabled) return;

				final NamedDomainObjectProvider<? extends Configuration> target = RemapConfigurations.getOrRegisterCollectorConfiguration(project, entry, runtime);
				// We copy the source with the desired usage type to get only the runtime or api jars, not both.
				Provider<? extends Configuration> sourceCopy = entry.getSourceConfiguration().map(source -> {
					Configuration copy = source.copyRecursive();
					Usage usage = project.getObjects().named(Usage.class, runtime ? Usage.JAVA_RUNTIME : Usage.JAVA_API);
					copy.attributes(attributes -> attributes.attribute(Usage.USAGE_ATTRIBUTE, usage));
					return copy;
				});
				configsToRemap.put(sourceCopy, target);

				// If our remap configuration entry targets the client source set as well,
				// let's set up a collector for it too.
				if (entry.getClientSourceConfigurationName().isPresent()) {
					final SourceSet clientSourceSet = SourceSetHelper.getSourceSetByName(MinecraftSourceSets.Split.CLIENT_ONLY_SOURCE_SET_NAME, project);
					final NamedDomainObjectProvider<? extends Configuration> clientTarget = RemapConfigurations.getOrRegisterCollectorConfiguration(project, clientSourceSet, runtime);
					clientConfigsToRemap.put(sourceCopy, clientTarget);
				}
			});

			// Export to other projects.
			if (entry.getTargetConfigurationName().get().equals(JavaPlugin.API_CONFIGURATION_NAME)) {
				// Note: legacy (pre-1.1) behavior is kept for this remapping since
				// we don't have a modApiElements/modRuntimeElements kind of configuration.
				// TODO: Expose API/runtime usage attributes for namedElements to make it work like normal project dependencies.
				final NamedDomainObjectProvider<? extends Configuration> remappedConfig = project.getConfigurations().register(entry.getRemappedConfigurationName(), config -> config.setTransitive(false));
				project.getConfigurations().named(Constants.Configurations.NAMED_ELEMENTS).configure(config -> config.extendsFrom(remappedConfig));
				configsToRemap.put(entry.getSourceConfiguration(), remappedConfig);
			}
		}

		final ModDependencyOptions modDependencyOptions = ModDependencyOptions.create(project, ModDependencyOptions.class, options -> {
			options.getMappings().set(mappingsSuffix);
			options.getInlineRefmap().set(extension.getMixin().getInlineDependencyRefmaps());
		});

		if (LOGGER.isInfoEnabled()) {
			LOGGER.info("Mod dependency options: {}", modDependencyOptions.getJson());
		}

		// Round 1: Discovery
		// Go through all the configs to find artifacts to remap and
		// the installer data. The installer data has to be added before
		// any mods are remapped since remapping needs the dependencies provided by that data.
		final Map<Provider<? extends Configuration>, List<ModDependency>> dependenciesBySourceConfig = new HashMap<>();
		AsyncCache<ArtifactMetadata> metaCache = getSharedMetaCache(project);
		configsToRemap.forEach((sourceConfig, remappedConfig) -> {
			/*
			sourceConfig - The source configuration where the intermediary named artifacts come from. i.e "modApi"
			remappedConfig - The target configuration where the remapped artifacts go
			 */
			final NamedDomainObjectProvider<? extends Configuration> clientRemappedConfig = clientConfigsToRemap.get(sourceConfig);
			List<ArtifactRef> artifactRefs = resolveArtifacts(project, sourceConfig);
			Map<ArtifactRef, ArtifactMetadata> metadataMap = getMetadata(artifactRefs, metaCache, project, extension.getPlatform().get(), extension.getDefaultMixinRemapTypeEnum().get());
			final List<ModDependency> modDependencies = new ArrayList<>();

			for (ArtifactRef artifact : artifactRefs) {
				final ArtifactMetadata artifactMetadata = Objects.requireNonNull(metadataMap.get(artifact), "Failed to find metadata for artifact");

				if (artifactMetadata.installerData() != null) {
					if (extension.getInstallerData() != null) {
						project.getLogger().info("Found another installer JSON in ({}), ignoring", artifact.path());
					} else {
						project.getLogger().info("Applying installer data from {}", artifact.path());
						artifactMetadata.installerData().applyToProject(project);
					}
				}

				if (!artifactMetadata.shouldRemap()) {
					// Note: not applying to any type of vanilla Gradle target config like
					// api or implementation to fix https://github.com/FabricMC/fabric-loom/issues/572.
					artifact.applyToConfiguration(project, remappedConfig);
					continue;
				}

				final ModDependency modDependency = ModDependencyFactory.create(artifact, artifactMetadata, remappedConfig, clientRemappedConfig, modDependencyOptions, project);
				scheduleSourcesRemapping(project, sourceRemapper, modDependency);
				modDependencies.add(modDependency);
			}

			dependenciesBySourceConfig.put(sourceConfig, modDependencies);
		});

		// Round 2: Remapping
		// Remap all discovered artifacts.
		configsToRemap.forEach((sourceConfig, remappedConfig) -> {
			final List<ModDependency> modDependencies = dependenciesBySourceConfig.get(sourceConfig);

			if (modDependencies.isEmpty()) {
				// Nothing else to do
				return;
			}

			final NamedDomainObjectProvider<? extends Configuration> clientRemappedConfig = clientConfigsToRemap.get(sourceConfig);

			// 任务接管**整批**依赖，而非仅「缓存失效的那些」：任务拥有产出目录，未纳入本批的产出
			// 不会被写出，消费方的文件依赖就会悬空。产物有效性改由 Gradle 的 up-to-date 与构建
			// 缓存判定，isCacheInvalid / refreshDeps 这套手写判据随之退休（见架构 §5.1）。
			//
			// 性能影响**未测**：曾有一次 AllinCore 对照（2m01s→14m14s）看似回退，但随后发现
			// 该机同时有 8 个 Gradle daemon 并发、同一指标在 41s~28m51s 间飘，故那次对照无效。
			// 需在安静机器或 CI 上做 A/B 才能定论。
			final RemappedModArtifacts remappedArtifacts = registerRemapTask(
					project, sourceConfig.get().getName(), modDependencies, extension.getMappingConfiguration());

			// Add all of the remapped mods onto the config
			for (ModDependency info : modDependencies) {
				info.applyToProject(project, remappedArtifacts);
				createConstraints(info.getInputArtifact(), remappedConfig, sourceConfig, dependencies);

				if (clientRemappedConfig != null) {
					createConstraints(info.getInputArtifact(), clientRemappedConfig, sourceConfig, dependencies);
				}
			}
		});
	}

	/**
	 * 为一批 mod 依赖注册 L3 重映射任务，返回「产物路径 → 生产位置」的解析器.
	 *
	 * <p>取代原先在配置期同步执行的 {@code ModProcessor.processMods}。三处刻意的不同：
	 *
	 * <ul>
	 *   <li>接管**整批**依赖，而非「缓存失效的那些」。任务拥有产出目录，未纳入本批的产出不会
	 *       被写出，消费方的文件依赖就会悬空；产物有效性的判据因此从 {@code isCacheInvalid}
	 *       交给 Gradle 的 up-to-date 与构建缓存（见架构 §5.1）。</li>
	 *   <li>依赖在此被投影成纯数据 {@link RemapModsTask.ModSpec}。任务输入里不含任何项目对象，
	 *       才可能被配置缓存序列化——这正是本改造的目的。</li>
	 *   <li>产物路径经 {@link RemapMinecraftTaskRegistry#claimAll} 逐条认领，任务只写出并声明自己
	 *       认领到的那些：产物落在构建内所有项目共用的 {@code remapped_mods} 仓库，同一条路径上
	 *       不能有两个生产者（见 {@link RemapModsTask}）。</li>
	 * </ul>
	 *
	 * @return 本批每条产物的生产位置；消费方据此建依赖边，产出方未必是本项目的任务
	 */
	private static RemappedModArtifacts registerRemapTask(
			Project project,
			String configName,
			List<ModDependency> modDependencies,
			MappingConfiguration mappingConfiguration) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final String sourceNamespace = extension.getProductionNamespaceEnum().get().toString();
		final String targetNamespace = MappingsNamespace.NAMED.toString();

		// classpath：其余 mod 的原始 jar 提供跨 mod 的类型上下文。
		//
		// 关键：这里的配置解析必须**惰性**。旧路径由 toRemap 过滤保护——processMods 只在存在
		// 缓存失效依赖时才被调用，暖构建时整段不执行。改成任务后若无条件在配置期解析
		// getSourceConfiguration().get().getFiles()，每次配置都要把所有 remap 配置连同其
		// *Copy 变体解析一遍（实测 MPMT 41s→4m37s、AllinCore 2m01s→14m14s）。
		// 用 provider 推迟到任务输入快照时刻：任务不执行（如 :help）就完全不解析。
		final Set<File> inputsBeingRemapped = modDependencies.stream()
				.map(dep -> dep.getInputFile().toFile())
				.collect(Collectors.toSet());

		// 已知 indy BSM：项目级声明 + 各 mod 元数据声明
		final Set<String> knownIndyBsms = new HashSet<>(extension.getKnownIndyBsms().get());

		for (ModDependency dep : modDependencies) {
			knownIndyBsms.addAll(dep.getMetadata().knownIdyBsms());
		}

		final Provider<KotlinClasspathService.Options> kotlinOptions = KotlinClasspathService.createOptions(project);
		final Map<String, String> identity = remapIdentity(extension, sourceNamespace, targetNamespace, knownIndyBsms, kotlinVersionOf(kotlinOptions));

		// 产出路径与消费方同源：两处都按 LocalMavenHelper 算，任务写出的就是消费方要读的那条路径。
		// 一条依赖的产物可能不止一条（拆分依赖是 common/client 两条），认领因此按**全部**落位进行：
		// 只认领其中一条，另一条就没有生产者（消费方读到一条永不写出的路径，而构建不会报错）。
		final List<Path> artifactPaths = new ArrayList<>();
		final List<RemapModsTask.ModSpec> specs = new ArrayList<>();

		for (ModDependency dependency : modDependencies) {
			final List<Path> artifacts = dependency.getCacheArtifactPaths(project);
			artifactPaths.addAll(artifacts);
			specs.add(toModSpec(project, dependency, artifacts));
		}

		final Map<Path, Producer> producers = RemapMinecraftTaskRegistry.claimAll(project, artifactPaths, identity,
				ownedArtifacts -> project.getTasks().register(taskName(configName), RemapModsTask.class, task -> {
					task.setGroup("loom");
					task.setDescription("Remaps the mods of '%s' to %s".formatted(configName, targetNamespace));

					// 只声明认领到的产物；声明集合同时就是写出集合（见 RemapModsTask.getOutputJars）
					task.getOutputJars().from(ownedArtifacts.stream().map(Path::toFile).toList());
					task.getRemapClasspath().from(project.provider(() -> {
						final List<File> remapConfigSourceFiles = new ArrayList<>();

						for (RemapConfigurationSettings entry : extension.getRemapConfigurations()) {
							remapConfigSourceFiles.addAll(entry.getSourceConfiguration().get().getFiles());
						}

						return RemapModsTask.collectRemapClasspath(remapConfigSourceFiles, inputsBeingRemapped);
					}));
					// MC jar 必须在重映射 classpath 上：覆写链的方法重命名靠它做类型层级解析。
					// 旧 ModProcessor 在配置期显式 readClassPath(getMinecraftJars(productionNamespace))，
					// 迁移到任务时漏了这一路，导致覆写传播的方法名（如 reload）保持源命名空间原名。
					// 取源命名空间的集合：已登记任务产出时携带任务依赖（同时保证 MC 先产出），
					// 未登记时回退为配置期文件，与旧语义一致。
					task.getRemapClasspath().from(extension.getMinecraftJarsCollection(extension.getProductionNamespaceEnum().get()));
					task.getMappingsServiceOptions().set(
							mappingConfiguration.getMappingsServiceOptions(project, MappingOption.forPlatform(extension)));
					// 映射树取自迁移产物：投影时它由迁移任务产出，按路径声明输入不带任务依赖，必须显式接线
					mappingConfiguration.addMappingsProducerDependency(task);
					task.getSourceNamespace().set(sourceNamespace);
					task.getTargetNamespace().set(targetNamespace);
					task.getPlatform().set(extension.getPlatform().get());
					task.getForgeLike().set(extension.isForgeLike());
					task.getNeoForge().set(extension.isNeoForge());
					task.getRuntimeMojang().set(extension.isForgeLike() && extension.getForgeProvider().usesMojangAtRuntime());
					task.getKnownIndyBsms().set(knownIndyBsms);
					task.getKotlinOptions().set(kotlinOptions);

					// 输入仍是**完整批次**：认领结果只决定写不写，不决定读什么（见 RemapModsTask 类注释）
					task.getMods().set(List.copyOf(specs));
				}));

		return new RemappedModArtifacts(project, extension.getFiles().getRemappedModCache().toPath(), producers);
	}

	/**
	 * {@return 本批 mod 重映射的输入指纹}（见 {@link RemapMinecraftTaskRegistry}）.
	 *
	 * <p>用来判定「两个项目请求同一条产物路径时，它们要的是不是同一个 jar」。产物路径里已经确定的量
	 * **不重复**：坐标、映射标识与平台后缀都在 cache key 里（{@code ModDependencyOptions.getCacheKey()}），
	 * 因此两条 MC 车道、两套映射必然算不出同一条路径，不会互相复用。这里列的只是路径**看不到**、
	 * 却会改变产物字节的配置项：源/目标命名空间、平台、Forge 系的三个开关、扩展声明的 indy BSM 集合、
	 * Kotlin 版本（影响 kotlin 元数据的重映射）。
	 *
	 * <p>值一律是字符串，且全部取自枚举、布尔与字符串本身：**不含任何对象身份**——把配置对象
	 * （{@code RemapConfigurationSettings}、{@code Configuration} 之类）当指纹是本仓库已经踩过的坑，
	 * 它们的 {@code toString} 里带工厂 lambda 的身份哈希，两个配置完全相同的项目也会算出不同的字符串，
	 * 指纹于是变成假冲突（同类问题的处置见 {@code ProcessedNamedMinecraftProvider} 的
	 * {@code JarConfigurationKind}）。
	 *
	 * <p>刻意**不**列入的是重映射 classpath（其它 mod 的原始 jar）：解析它必须惰性（否则每次配置都要
	 * 把所有 remap 配置解析一遍，见 {@code registerRemapTask} 的性能记录），而且多模块工程里各模块的
	 * mod 集合本就不同——把它纳入判定会让正常工程被判成冲突。按路径复用是既有语义
	 * （配置期由 {@code LocalMavenHelper.isReusable} 决定），这里不改变它。
	 *
	 * @param extension 所在项目的 loom 扩展
	 * @param sourceNamespace 源命名空间（项目的生产命名空间）
	 * @param targetNamespace 目标命名空间
	 * @param knownIndyBsms 本批已知的 indy BSM 集合
	 * @param kotlinVersion 项目的 Kotlin 插件版本；不用 Kotlin 时为空串
	 */
	private static Map<String, String> remapIdentity(LoomGradleExtension extension, String sourceNamespace,
			String targetNamespace, Set<String> knownIndyBsms, String kotlinVersion) {
		final Map<String, String> identity = new LinkedHashMap<>();
		identity.put("sourceNamespace", sourceNamespace);
		identity.put("targetNamespace", targetNamespace);
		identity.put("platform", extension.getPlatform().get().id());
		identity.put("forgeLike", Boolean.toString(extension.isForgeLike()));
		identity.put("neoForge", Boolean.toString(extension.isNeoForge()));
		identity.put("runtimeMojang", Boolean.toString(extension.isForgeLike() && extension.getForgeProvider().usesMojangAtRuntime()));
		identity.put("knownIndyBsms", knownIndyBsms.stream().sorted().collect(Collectors.joining("\n")));
		identity.put("kotlinVersion", kotlinVersion);
		return Map.copyOf(identity);
	}

	/** {@return 项目所用的 Kotlin 插件版本} 不用 Kotlin 时为空串；只读一个已设好的属性，不解析任何配置. */
	private static String kotlinVersionOf(Provider<KotlinClasspathService.Options> kotlinOptions) {
		final KotlinClasspathService.Options options = kotlinOptions.getOrNull();
		return options == null ? "" : options.getKotlinVersion().getOrElse("");
	}

	/**
	 * 把依赖投影成任务输入.
	 *
	 * <p>坐标必须与产出路径一致——消费方按坐标换算出文件依赖的相对路径，不一致就会指向
	 * 一个任务永不写出的文件。
	 *
	 * @param artifacts 该依赖的产出路径，由 {@link ModDependency#getCacheArtifactPaths} 给出；
	 *         两条时（拆分依赖的 common + client）第一条是 common 半，第二条是 client 半
	 */
	private static RemapModsTask.ModSpec toModSpec(Project project, ModDependency dependency, List<Path> artifacts) {
		final RemapModsTask.ModSpec spec = project.getObjects().newInstance(RemapModsTask.ModSpec.class);
		spec.getInputJar().set(dependency.getInputFile().toFile());
		spec.getOutputJar().set(artifacts.get(0).toFile());

		if (artifacts.size() > 1) {
			spec.getSplitClientJar().set(artifacts.get(1).toFile());
		}

		spec.getGroup().set(dependency.getGroup());
		spec.getName().set(dependency.getName());
		spec.getVersion().set(dependency.getVersion());
		spec.getClassifier().set(dependency.getClassifier());
		spec.getMixinRemapType().set(dependency.getMetadata().mixinRemapType());
		spec.getInlineRefmap().set(dependency.getOptions().getInlineRefmap());
		return spec;
	}

	private static String taskName(String configName) {
		return "remapMods" + configName.substring(0, 1).toUpperCase(Locale.ENGLISH) + configName.substring(1);
	}

	/**
	 * 返回当前 Loom classloader 服务内的跨子项目共享 {@link AsyncCache}.
	 *
	 * <p>缓存仅存活于 Gradle build service 生命周期内，不会跨 daemon 构建泄漏；多个子项目
	 * 解析同一个 mod jar 时可复用同一个 future，同时不会跨 classloader 共享强类型对象。
	 */
	private static AsyncCache<ArtifactMetadata> getSharedMetaCache(Project project) {
		return LoomCacheService.get(project).get().getAsyncCache(ARTIFACT_METADATA_CACHE);
	}

	private static Map<ArtifactRef, ArtifactMetadata> getMetadata(List<ArtifactRef> artifacts, AsyncCache<ArtifactMetadata> cache, @Nullable Project project, ModPlatform platform, ArtifactMetadata.MixinRemapType defaultMixinRemapType) {
		var futures = new HashMap<ArtifactRef, CompletableFuture<ArtifactMetadata>>();

		for (ArtifactRef artifact : artifacts) {
			CompletableFuture<ArtifactMetadata> future = cache.get(artifact, () -> {
				try {
					return ArtifactMetadata.create(project, artifact, LoomGradlePlugin.LOOM_VERSION, platform, defaultMixinRemapType);
				} catch (IOException e) {
					throw ExceptionUtil.createDescriptiveWrapper(UncheckedIOException::new, "Failed to read metadata from " + artifact.path(), e);
				}
			});

			futures.put(artifact, future);
		}

		return AsyncCache.joinMap(futures);
	}

	private static void createConstraints(ArtifactRef artifact, NamedDomainObjectProvider<? extends Configuration> targetConfig, Provider<? extends Configuration> sourceConfig, DependencyHandler dependencies) {
		if (true) {
			// Disabled due to the gradle module metadata causing issues. Try the MavenProject test to reproduce issue.
			return;
		}

		if (artifact instanceof ArtifactRef.ResolvedArtifactRef mavenArtifact) {
			final String dependencyCoordinate = "%s:%s".formatted(mavenArtifact.group(), mavenArtifact.name());

			// Prevent adding the same un-remapped dependency to the target configuration.
			targetConfig.get().getDependencyConstraints().add(dependencies.getConstraints().create(dependencyCoordinate, constraint -> {
				constraint.because("configuration (%s) already contains the remapped module from configuration (%s)".formatted(
						targetConfig.getName(),
						sourceConfig.get().getName()
				));

				constraint.version(MutableVersionConstraint::rejectAll);
			}));
		}
	}

	private static List<ArtifactRef> resolveArtifacts(Project project, Provider<? extends Configuration> configuration) {
		final List<ArtifactRef> artifacts = new ArrayList<>();

		final Set<ResolvedArtifact> resolvedArtifacts = configuration.get().getResolvedConfiguration().getResolvedArtifacts();

		// sources 解析只服务于 IDE 附加源码，且是一次真实的 ArtifactResolutionQuery
		// （可能触发网络下载）。非 IDE 场景下其结果不会被消费，因此这里提前跳过，
		// 避免在配置阶段为「用不到的东西」做一次完整制品解析。
		// 守卫口径与 scheduleSourcesRemapping 保持一致。
		final Map<ResolvedArtifact, Path> sourcesMap = shouldRemapSourcesInConfigurationPhase(project)
				? downloadAllSources(project, resolvedArtifacts)
				: Map.of();

		for (ResolvedArtifact artifact : resolvedArtifacts) {
			Path sources = sourcesMap.get(artifact);
			artifacts.add(new ArtifactRef.ResolvedArtifactRef(artifact, sources));
		}

		// FileCollectionDependency (files/fileTree) doesn't resolve properly,
		// so we have to "resolve" it on our own. The naming is "abc.jar" => "unspecified:abc:unspecified".
		for (FileCollectionDependency dependency : configuration.get().getAllDependencies().withType(FileCollectionDependency.class)) {
			final String group = replaceIfNullOrEmpty(dependency.getGroup(), () -> MISSING_GROUP);
			final FileCollection files = dependency.getFiles();

			for (File artifact : files) {
				final String name = getNameWithoutExtension(artifact.toPath());
				final String version = replaceIfNullOrEmpty(dependency.getVersion(), () -> Checksum.of(artifact).sha256().hex(10));
				artifacts.add(new ArtifactRef.FileArtifactRef(artifact.toPath(), group, name, version));
			}
		}

		return artifacts;
	}

	private static String getNameWithoutExtension(Path file) {
		final String fileName = file.getFileName().toString();
		final int dotIndex = fileName.lastIndexOf('.');
		return (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);
	}

	public static Map<ResolvedArtifact, Path> downloadAllSources(Project project, Set<ResolvedArtifact> resolvedArtifacts) {
		if (isCIBuild()) {
			return Map.of();
		}

		final DependencyHandler dependencies = project.getDependencies();

		List<ComponentIdentifier> componentIdentifiers = resolvedArtifacts.stream()
				.map(ResolvedArtifact::getId)
				.map(ComponentArtifactIdentifier::getComponentIdentifier)
				.toList();

		//noinspection unchecked
		ArtifactResolutionQuery query = dependencies.createArtifactResolutionQuery()
				.forComponents(componentIdentifiers)
				.withArtifacts(JvmLibrary.class, SourcesArtifact.class);

		// Run a single query for all of the artifacts, this will allow them to be resolved in parallel before they are queried individually
		Set<ComponentArtifactsResult> resolvedSources = query.execute().getResolvedComponents();
		Map<ResolvedArtifact, Path> sources = new HashMap<>();

		for (ResolvedArtifact resolvedArtifact : resolvedArtifacts) {
			for (ComponentArtifactsResult sourceArtifact : resolvedSources) {
				if (sourceArtifact.getId().equals(resolvedArtifact.getId().getComponentIdentifier())) {
					Path sourcesPath = getSourcesPath(sourceArtifact);

					if (sourcesPath != null) {
						sources.put(resolvedArtifact, sourcesPath);
					}
				}
			}
		}

		return sources;
	}

	private static Path getSourcesPath(ComponentArtifactsResult sourceArtifact) {
		for (ArtifactResult srcArtifact : sourceArtifact.getArtifacts(SourcesArtifact.class)) {
			if (srcArtifact instanceof ResolvedArtifactResult) {
				return ((ResolvedArtifactResult) srcArtifact).getFile().toPath();
			}
		}

		return null;
	}

	private static void scheduleSourcesRemapping(Project project, SourceRemapper sourceRemapper, ModDependency dependency) {
		if (isCIBuild()) {
			return;
		}

		// remapped sources 只被 IDE 附加源码消费，不参与编译与打包。
		// 在配置阶段写出它是有害的：配置缓存会把「本次创建了该文件」记入指纹，
		// 下一次构建读到「文件已存在」即判定失效，于是每个含 mod 依赖的项目
		// 在产物落盘后都要多一轮完整重配置。故仅在真正需要时（IDE 同步，
		// 或用户显式开启）才在配置期加工。
		if (!shouldRemapSourcesInConfigurationPhase(project)) {
			return;
		}

		final Path sourcesInput = dependency.getInputArtifact().sources();

		if (sourcesInput == null || Files.notExists(sourcesInput)) {
			return;
		}

		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (dependency.isCacheInvalid(project, "sources") || extension.refreshDeps()) {
			final Path output = dependency.getWorkingFile(project, "sources");

			sourceRemapper.scheduleRemapSources(sourcesInput.toFile(), output.toFile(), false, true, () -> {
				try {
					dependency.copyToCache(project, output, "sources");
				} catch (IOException e) {
					throw new UncheckedIOException("Failed to apply sources to local cache for: " + dependency, e);
				}
			});
		}
	}

	public static String replaceIfNullOrEmpty(@Nullable String s, Supplier<String> fallback) {
		return s == null || s.isEmpty() ? fallback.get() : s;
	}

	public static boolean isCIBuild() {
		final String loomProperty = System.getProperty("fabric.loom.ci");

		if (loomProperty != null) {
			return loomProperty.equalsIgnoreCase("true");
		}

		// CI seems to be set by most popular CI services
		return System.getenv("CI") != null;
	}

	/**
	 * {@return 是否在配置阶段加工依赖的 remapped sources}.
	 *
	 * <p>remapped sources 的唯一消费者是 IDE 附加源码；编译、测试、打包都不需要它。
	 * 而配置阶段写出新文件会让配置缓存把「文件被创建」记入指纹，导致下一次构建
	 * 因「文件系统条目已改变」而失效——表现为产物落盘后仍要完整重配置一轮。
	 *
	 * <p>因此默认只在 IDE 同步时加工（此时 IDE 确实要拿源码去索引），
	 * 其余场景一律跳过。需要 sources 的非 IDE 场景可用
	 * {@code -Dfabric.loom.remapSources=true} 显式开启。
	 *
	 * <p>注意：本判断只决定「是否加工」，不改变 IDE 同步时的行为，
	 * 因此不影响既有 IDE 用例（见 IdeaDownloadSourcesHookTest）。
	 */
	public static boolean shouldRemapSourcesInConfigurationPhase(Project project) {
		if (Boolean.parseBoolean(System.getProperty("fabric.loom.remapSources", "false"))) {
			return true;
		}

		// 用与 SourceSetHelper 一致的 IDE 判定口径：IntelliJ 同步或 IDE 内运行。
		// 只判 idea.sync.active 会漏掉「IDE 内直接触发 Gradle 构建」的场景。
		return SourceSetHelper.isIdeDrivenBuild();
	}
}
