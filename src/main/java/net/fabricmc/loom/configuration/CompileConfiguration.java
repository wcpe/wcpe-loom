/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2023 FabricMC
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

package net.fabricmc.loom.configuration;

import static net.fabricmc.loom.util.Constants.Configurations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import javax.inject.Inject;

import dev.architectury.loom.accesstransformer.AccessTransformerJarProcessor;
import dev.architectury.loom.forge.ForgeSourcesService;
import dev.architectury.loom.forge.dependency.DependencyProviders;
import dev.architectury.loom.forge.dependency.ForgeLibrariesProvider;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.dependency.ForgeRunsProvider;
import dev.architectury.loom.forge.dependency.ForgeUniversalProvider;
import dev.architectury.loom.forge.dependency.ForgeUserdevProvider;
import dev.architectury.loom.forge.dependency.PatchProvider;
import dev.architectury.loom.forge.dependency.SrgProvider;
import dev.architectury.loom.forge.minecraft.ForgeMinecraftProvider;
import dev.architectury.loom.mcpconfig.McpConfigProvider;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.InterfaceInjectionExtensionAPI;
import net.fabricmc.loom.build.mixin.GroovyApInvoker;
import net.fabricmc.loom.build.mixin.JavaApInvoker;
import net.fabricmc.loom.build.mixin.KaptApInvoker;
import net.fabricmc.loom.build.mixin.ScalaApInvoker;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor;
import net.fabricmc.loom.configuration.mods.ModConfigurationRemapper;
import net.fabricmc.loom.configuration.processors.JsrAnnotationRemapperProcessor;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.processors.ModJavadocProcessor;
import net.fabricmc.loom.configuration.processors.speccontext.DebofConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.GeneratedIntermediateMappingsProvider;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MojangMappedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.SrgMinecraftProvider;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.task.service.ClasspathGroupService;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.loom.util.gradle.daemon.DaemonUtils;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;

public abstract class CompileConfiguration implements Runnable {
	@Inject
	protected abstract Project getProject();

	@Inject
	protected abstract TaskContainer getTasks();

	@Override
	public void run() {
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		getTasks().named(JavaPlugin.JAVADOC_TASK_NAME, Javadoc.class).configure(javadoc -> {
			final SourceSet main = SourceSetHelper.getMainSourceSet(getProject());
			javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));
		});

		afterEvaluationWithService((serviceFactory) -> {
			final ConfigContext configContext = new ConfigContextImpl(getProject(), serviceFactory, extension);

			if (extension.disableObfuscation()) {
				DebofConfiguration.create(getProject());
			}

			MinecraftSourceSets.get(getProject()).afterEvaluate(getProject());

			final boolean previousRefreshDeps = extension.refreshDeps();

			// 这里曾有一把「配置期整段串行」的 per-project 粗锁（acquireProcessLockWaiting）。
			// 它包住整个 setupMinecraft，因此同一构建里 N 个应用 Loom 的项目（尤其开启 Isolated
			// Projects 后 Gradle 并行配置时）会各自在 afterEvaluate 里等自己的锁，而它们等的是
			// 同一件重活：在生产同一份共享 Minecraft 缓存。干净环境（CI 首次构建）下，第一个
			// 项目持锁要跑完 download→extract→merge→remap，远超锁的 1 分钟超时。
			//
			// 删掉它是安全的：它原本要保证的「同一共享缓存条目只被生产一次」已经由每个生产点各自的
			// per-key 跨进程锁（LoomCacheService.runExclusive / runExclusiveChecking）承担，键覆盖
			// mc 版本+映射标识、mappings、download、remap、processed、forge 补丁链等全部产出路径。
			// 而锁文件本身只剩「上次构建是否干净收尾」这一条诊断信息——它要求对本项目的缓存做
			// 独占判断，与真实用例（多项目共存于同一缓存）相矛盾：后到的项目永远看到前一个项目的
			// 活锁文件，本就会打出“assume canceled”的误导性日志。
			//
			// 这与更早那次删除 getGlobalLockObject() 是同一个论证：那把锁剩下的唯一作用只是把各项目
			// 的 setup 串成一条，而它恰好是把 Gradle 的项目状态转换拖进临界区、造成锁序反转的根源。

			try {
				// 刻意**不**在这里包一层「跨项目全局锁」。历史上这里是一把经系统属性共享的 String monitor，
				// 目的是让同一 JVM 内所有项目（含不同 classloader 的多份 Loom）串行化共享缓存的生产。
				// 它现在同时是 --parallel 下必然可复现的死锁源：
				//
				//   持锁线程正在 setupMinecraft 里往上走，而 setupMinecraft 内部会回到 Gradle 的项目配置机制
				//   （MinecraftJarProcessorManager.create → RemappedSpecContext → LoomProjectData.fromDependency
				//   → configuration.getSingleFile() → Gradle ensureConfigured → DefaultSynchronizer.takeOwnership，
				//   后者是阻塞调用，要求所有 worker lease 归还）；与此同时其余配置线程正各自持着 lease
				//   BLOCKED 在这把 monitor 上。锁序反转成立 → 整个 daemon 永久卡死（实测两个 daemon 各 7 个线程）。
				//
				// 删掉它是安全的：锁原本要保证的「同一共享缓存条目只被生产一次」已经由每个生产点各自的 per-key 跨进程锁
				// 承担（CacheEntryLock / LoomCacheService.runExclusive，键覆盖 mc 版本+映射标识、mappings、download、
				// remap、processed、forge 补丁链等全部产出路径，且同 JVM 内还叠加了按 key 的监视器）；
				// 该锁覆盖的静态可变状态也各自有锁（LoomGradleSharedData.REGISTRATION_LOCK、
				// IdeaDownloadSourcesRegistry.REGISTRATION_LOCK、RemapMinecraftTaskRegistry 对 extensions 的同步），
				// 落盘一律走 AtomicFiles.publish。剩下的唯一作用只是「把各项目的 setup 串成一条」，
				// 而这恰好就是把 Gradle 的项目状态转换拖进临界区、造出上述死锁的原因。
				setupMinecraft(configContext);

				var dependencyManager = new LoomDependencyManager(getProject(), serviceFactory, extension);
				dependencyManager.handleDependencies();
			} catch (Exception e) {
				ExceptionUtil.processException(e, DaemonUtils.Context.fromProject(getProject()));
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to setup Minecraft", e);
			}

			extension.setRefreshDeps(previousRefreshDeps);

			MixinExtension mixin = LoomGradleExtension.get(getProject()).getMixin();

			if (mixin.getUseLegacyMixinAp().get()) {
				setupMixinAp(mixin);
			}

			configureDecompileTasks(configContext);
			configureTestTask();

			if (extension.isForgeLike()) {
				if (extension.isDataGenEnabled()) {
					getProject().getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("main").resources(files -> {
						files.srcDir(getProject().file("src/generated/resources"));
					});
				}

				// TODO: Find a better place for this?
				//   This has to be after dependencyManager.handleDependencies() above
				//   because of https://github.com/architectury/architectury-loom/issues/72.
				if (!ModConfigurationRemapper.isCIBuild()) {
					try {
						ForgeSourcesService.addForgeSourcesDuringProjectConfiguration(getProject(), configContext.serviceFactory());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		});

		finalizedBy("eclipse", "genEclipseRuns");

		if (!extension.disableObfuscation()) {
			// Add the "dev" jar to the "namedElements" configuration
			getProject().artifacts(artifactHandler -> artifactHandler.add(Configurations.NAMED_ELEMENTS, getTasks().named("jar")));
		}

		// Ensure that the encoding is set to UTF-8, no matter what the system default is
		// this fixes some edge cases with special characters not displaying correctly
		// see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
		getTasks().withType(AbstractCopyTask.class).configureEach(abstractCopyTask -> abstractCopyTask.setFilteringCharset(StandardCharsets.UTF_8.name()));
		getTasks().withType(JavaCompile.class).configureEach(javaCompile -> javaCompile.getOptions().setEncoding(StandardCharsets.UTF_8.name()));

		if (extension.isForgeLike()) {
			// Create default mod from main source set
			extension.mods(mods -> {
				final SourceSet main = getProject().getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
				mods.create("main").sourceSet(main);
			});
		}

		if (getProject().getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			// If loom is applied after kapt, then kapt will use the AP arguments too early for loom to pass the arguments we need for mixin.
			throw new IllegalArgumentException("fabric-loom must be applied BEFORE kapt in the plugins { } block.");
		}
	}

	private void setupMinecraft(ConfigContext configContext) throws Exception {
		final Project project = configContext.project();
		final LoomGradleExtension extension = configContext.extension();

		final MinecraftMetadataProvider metadataProvider = MinecraftMetadataProvider.create(configContext);
		extension.setMetadataProvider(metadataProvider);

		var jarConfiguration = extension.getMinecraftJarConfiguration().get();

		// Provide the vanilla mc jars
		final MinecraftProvider minecraftProvider = jarConfiguration.createMinecraftProvider(metadataProvider, configContext);

		if (extension.isForgeLike() && !(minecraftProvider instanceof ForgeMinecraftProvider)) {
			throw new UnsupportedOperationException("Using %s with split jars is not supported!".formatted(extension.getPlatform().get().displayName()));
		}

		// TODO: Re-evaluate if isUnobfuscatedForge() should even exist, or if the checks below should be removed
		if (extension.isForgeLike() && extension.disableObfuscation() && !extension.isUnobfuscatedForge()) {
			throw new UnsupportedOperationException(("Architectury Loom: The top.wcpe.loom-no-remap plugin was applied, but the Minecraft version '%s' is obfuscated. "
					+ "Forge / NeoForge support for obfuscated Minecraft is through the regular top.wcpe.loom plugin instead.").formatted(metadataProvider.getMinecraftVersion()));
		}

		if (extension.isForgeLike() && !extension.disableObfuscation() && extension.isUnobfuscatedForge()) {
			throw new UnsupportedOperationException(("Architectury Loom: The Minecraft version '%s' is unobfuscated (no mappings). "
					+ "Forge / NeoForge support for unobfuscated Minecraft is through the top.wcpe.loom-no-remap plugin instead.").formatted(metadataProvider.getMinecraftVersion()));
		}

		extension.setMinecraftProvider(minecraftProvider);
		// 内存配置段：initFiles / java 版本校验 / 库注入。产物生产不在这里，见下面 provideMinecraftJars()
		minecraftProvider.provide();

		if (!extension.disableObfuscation()) {
			// Realise the dependencies without actually resolving them, this forces any lazy providers to be created, populating the layered mapping factories.
			project.getConfigurations().getByName(Configurations.MAPPINGS).getDependencies().toArray();

			// Created any layered mapping files.
			LayeredMappingsFactory.afterEvaluate(configContext);
		}

		// 依赖 provider 必须在库注入之后建立：库处理器会往仓库里加自定义源，而依赖解析要求仓库已定稿
		// （见 MinecraftLibraryProvider）；纯 disableObfuscation（非 unobfuscated Forge）不建立它们。
		if (!extension.disableObfuscation() || extension.isUnobfuscatedForge()) {
			setupDependencyProviders(project, extension);
		}

		// 产物生产段：必须在依赖 provider 建立之后——能否投影成执行期任务的判据要读 isLegacyForge()，
		// 而它经 extension.getDependencyProviders() 取 ForgeUserdevProvider（未解析时会抛异常）。
		minecraftProvider.provideMinecraftJars();

		if (!extension.disableObfuscation()) {
			if (extension.isLegacyForge()) {
				extension.setIntermediateMappingsProvider(GeneratedIntermediateMappingsProvider.class, provider -> {
					provider.minecraftProvider = minecraftProvider;
				});
			}

			// Resolve the mapping files from the configuration
			final DependencyInfo mappingsDep = DependencyInfo.create(getProject(), Configurations.MAPPINGS);
			final MappingConfiguration mappingConfiguration = MappingConfiguration.create(getProject(), configContext.serviceFactory(), mappingsDep, minecraftProvider);
			extension.setMappingConfiguration(mappingConfiguration);

			if (extension.isForgeLike()) {
				ForgeLibrariesProvider.provide(mappingConfiguration, project);
				((ForgeMinecraftProvider) minecraftProvider).getPatchedProvider().provide();
			}

			mappingConfiguration.setupPost(project);
			mappingConfiguration.applyToProject(getProject(), mappingsDep);
		} else if (extension.isUnobfuscatedForge()) {
			// Unobfuscated NeoForge: run the forge patch pipeline without requiring user-provided mappings.
			ForgeLibrariesProvider.provide(null, project);
			((ForgeMinecraftProvider) minecraftProvider).getPatchedProvider().provide();
		}

		if (extension.isForgeLike()) {
			extension.setForgeRunsProvider(ForgeRunsProvider.create(project));
		}

		if (minecraftProvider instanceof ForgeMinecraftProvider patched) {
			patched.getPatchedProvider().remapJar(configContext.serviceFactory());
		}

		// Provide the remapped mc jars
		IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider = extension.disableObfuscation() ? null : jarConfiguration.createIntermediaryMinecraftProvider(project);
		NamedMinecraftProvider<?> namedMinecraftProvider = jarConfiguration.createNamedMinecraftProvider(project);

		registerGameProcessors(configContext);
		MinecraftJarProcessorManager minecraftJarProcessorManager = MinecraftJarProcessorManager.create(getProject());

		if (minecraftJarProcessorManager != null) {
			// Wrap the named MC provider for one that will provide the processed jars
			namedMinecraftProvider = jarConfiguration.createProcessedNamedMinecraftProvider(namedMinecraftProvider, minecraftJarProcessorManager);
		}

		provideMappedMinecraftJars(extension, project, configContext, jarConfiguration, intermediaryMinecraftProvider, namedMinecraftProvider);
	}

	/**
	 * 跨进程供给已映射的 Minecraft jar.
	 *
	 * <p>共享缓存的生产互斥由每个生产点各自的 per-key 跨进程锁承担（见 {@link LoomCacheService}），
	 * 多个 daemon 并发构建同一版本时也不会互相踩踏；不同版本仍可并行。输出检查与重建必须同属一个事务，否则另一进程会在本项目读取 intermediary 时将它删除并重建。
	 */
	private void provideMappedMinecraftJars(LoomGradleExtension extension, Project project, ConfigContext configContext,
			MinecraftJarConfiguration<?, ?, ?> jarConfiguration,
			IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider,
			NamedMinecraftProvider<?> namedMinecraftProvider) throws Exception {
		final var lockRoot = extension.getFiles().getCacheLocks().toPath();
		final String mappingsIdentifier = extension.disableObfuscation() ? "deobf" : extension.getMappingConfiguration().mappingsIdentifier();
		final String key = "minecraft-provision:" + extension.getMinecraftProvider().minecraftVersion() + ":" + mappingsIdentifier;
		final LoomCacheService cacheService = LoomCacheService.get(project).get();

		// 无锁快路径：所有 mapped provider 的产物均已就绪（shouldRefreshOutputs 为纯只读的
		// 存在性/脏标志检查）时，本次供给不含任何共享缓存写入，内部 provide 各自的快路径
		// 也不会取锁，故整段事务可无锁执行。
		final var warmContext = new AbstractMappedMinecraftProvider.ProvideContext(true, extension.refreshDeps(), configContext);

		// 用双检锁形式供给 mapped jar：
		//
		// 干净环境（CI 首次构建）下，多个共享同一份 Minecraft 的项目会算出同一个 key；
		// 而 isMappedJarsWarm 在冷构建时全部为 false。若直接进 runExclusive，第 2..N 个项目
		// 必须先拿到锁才能做那次检查，于是就各自白等「第一个项目做完整条 download→remap」
		// 的时间（实测在 CI runner 上远超锁超时）。
		//
		// runExclusiveChecking 把就绪检查提到取锁之前，并在锁内重检：第一个项目持锁生产，
		// 其余项目只需等它做完，拿到锁后立刻发现产物已就绪而秒退，不会再重建一遍。
		//
		// 两次检查都是纯只读的（shouldRefreshOutputs / 存在性与脏标志），不含共享缓存写入，
		// 因此放在锁外安全；产物本身的写入仍由锁内的 provideMappedProviders 完成。
		cacheService.runExclusiveChecking(lockRoot, key, LoomCacheService.defaultTimeout(),
				() -> isMappedJarsWarm(extension, project, jarConfiguration, intermediaryMinecraftProvider,
						namedMinecraftProvider, warmContext),
				() -> {
					// 已就绪（无需生产）：照旧读已发布的产物。这一步不含共享缓存写入。
					provideMappedProviders(extension, project, jarConfiguration, intermediaryMinecraftProvider,
							namedMinecraftProvider, warmContext);
					return null;
				},
				() -> {
					final var provideContext = new AbstractMappedMinecraftProvider.ProvideContext(true, extension.refreshDeps(), configContext);
					provideMappedProviders(extension, project, jarConfiguration, intermediaryMinecraftProvider,
							namedMinecraftProvider, provideContext);
					return null;
				});
	}

	/**
	 * {@return 所有 mapped provider 的产物是否已就绪（无需重建）}.
	 *
	 * <p>shouldRefreshOutputs 仅做文件存在性与脏标志检查，不写入任何共享缓存，可安全在锁外调用。
	 */
	private boolean isMappedJarsWarm(LoomGradleExtension extension, Project project, MinecraftJarConfiguration<?, ?, ?> jarConfiguration,
			IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider,
			NamedMinecraftProvider<?> namedMinecraftProvider,
			AbstractMappedMinecraftProvider.ProvideContext context) {
		if (intermediaryMinecraftProvider != null && !intermediaryMinecraftProvider.isUpToDate(context)) {
			return false;
		}

		if (!namedMinecraftProvider.isUpToDate(context)) {
			return false;
		}

		if (extension.isForge()) {
			if (!jarConfiguration.createSrgMinecraftProvider(project).isUpToDate(context)) {
				return false;
			}
		}

		if (extension.isForgeLike() && extension.getForgeProvider().usesMojangAtRuntime() && !extension.isUnobfuscatedForge()) {
			if (!jarConfiguration.createMojangMappedMinecraftProvider(project).isUpToDate(context)) {
				return false;
			}
		}

		return true;
	}

	private void provideMappedProviders(LoomGradleExtension extension, Project project, MinecraftJarConfiguration<?, ?, ?> jarConfiguration,
			IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider,
			NamedMinecraftProvider<?> namedMinecraftProvider,
			AbstractMappedMinecraftProvider.ProvideContext provideContext) throws Exception {
		if (intermediaryMinecraftProvider != null) {
			extension.setIntermediaryMinecraftProvider(intermediaryMinecraftProvider);
			intermediaryMinecraftProvider.provide(provideContext);
		}

		extension.setNamedMinecraftProvider(namedMinecraftProvider);
		namedMinecraftProvider.provide(provideContext);

		if (extension.isForge()) {
			final SrgMinecraftProvider<?> srgMinecraftProvider = jarConfiguration.createSrgMinecraftProvider(project);
			extension.setSrgMinecraftProvider(srgMinecraftProvider);
			srgMinecraftProvider.provide(provideContext);
		}

		if (extension.isForgeLike() && extension.getForgeProvider().usesMojangAtRuntime() && !extension.isUnobfuscatedForge()) {
			final MojangMappedMinecraftProvider<?> mojangMappedMinecraftProvider = jarConfiguration.createMojangMappedMinecraftProvider(project);
			extension.setMojangMappedMinecraftProvider(mojangMappedMinecraftProvider);
			mojangMappedMinecraftProvider.provide(provideContext);
		}
	}

	private void registerGameProcessors(ConfigContext configContext) {
		final LoomGradleExtension extension = configContext.extension();

		final boolean enableTransitiveAccessWideners = extension.getEnableTransitiveAccessWideners().get();
		extension.addMinecraftJarProcessor(AccessWidenerJarProcessor.class, "fabric-loom:access-widener", enableTransitiveAccessWideners, extension.getAccessWidenerPath());

		if (extension.getEnableModProvidedJavadoc().get()) {
			extension.addMinecraftJarProcessor(ModJavadocProcessor.class, "fabric-loom:mod-javadoc");
		}

		final InterfaceInjectionExtensionAPI interfaceInjection = extension.getInterfaceInjection();

		if (interfaceInjection.isEnabled()) {
			extension.addMinecraftJarProcessor(InterfaceInjectionProcessor.class, "fabric-loom:interface-inject", interfaceInjection.getEnableDependencyInterfaceInjection().get());
		}

		if (!extension.getRemapJsrAnnotationsToJetBrains().get()) {
			extension.addMinecraftJarProcessor(JsrAnnotationRemapperProcessor.class, "fabric-loom:jsr-annotations");
		}

		if (extension.isForgeLike()) {
			FileCollection accessTransformers;

			if (extension.isNeoForge()) {
				accessTransformers = extension.getNeoForge().getAccessTransformers();
			} else {
				accessTransformers = extension.getForge().getAccessTransformers();
			}

			extension.addMinecraftJarProcessor(AccessTransformerJarProcessor.class, "loom:access-transformer", configContext.project(), accessTransformers);
		}
	}

	private void setupMixinAp(MixinExtension mixin) {
		mixin.init();

		// Disable some things used by log4j via the mixin AP that prevent it from being garbage collected
		System.setProperty("log4j2.disable.jmx", "true");
		System.setProperty("log4j.shutdownHookEnabled", "false");
		System.setProperty("log4j.skipJansi", "true");

		getProject().getLogger().info("Configuring compiler arguments for Java");

		new JavaApInvoker(getProject()).configureMixin();

		if (getProject().getPluginManager().hasPlugin("scala")) {
			getProject().getLogger().info("Configuring compiler arguments for Scala");
			new ScalaApInvoker(getProject()).configureMixin();
		}

		if (getProject().getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			getProject().getLogger().info("Configuring compiler arguments for Kapt plugin");
			new KaptApInvoker(getProject()).configureMixin();
		}

		if (getProject().getPluginManager().hasPlugin("groovy")) {
			getProject().getLogger().info("Configuring compiler arguments for Groovy");
			new GroovyApInvoker(getProject()).configureMixin();
		}
	}

	private void configureDecompileTasks(ConfigContext configContext) {
		final LoomGradleExtension extension = configContext.extension();

		extension.getMinecraftJarConfiguration().get()
				.createDecompileConfiguration(getProject())
				.afterEvaluation();
	}

	private void configureTestTask() {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		if (extension.getMods().isEmpty()) {
			return;
		}

		getProject().getTasks().named(JavaPlugin.TEST_TASK_NAME, Test.class, test -> {
			Provider<ClasspathGroupService.Options> optionsProvider = ClasspathGroupService.create(getProject());
			test.getInputs().property("LoomClassPathGroups", optionsProvider);
			test.getInputs().files(optionsProvider.map((ClasspathGroupService.Options::getExternalClasspathGroups)));

			test.doFirst(new Action<Task>() {
				@Override
				public void execute(Task task) {
					try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
						var options = (ClasspathGroupService.Options) task.getInputs().getProperties().get("LoomClassPathGroups");
						ClasspathGroupService classpathGroupService = serviceFactory.get(options);

						if (classpathGroupService.hasGroups()) {
							test.systemProperty("fabric.classPathGroups", classpathGroupService.getClasspathGroupsPropertyValue());
						}
					} catch (IOException e) {
						throw new UncheckedIOException("Failed to get classpath groups", e);
					}
				}
			});
		});
	}

	private void finalizedBy(String a, String b) {
		getTasks().named(a).configure(task -> task.finalizedBy(getTasks().named(b)));
	}

	public static void setupDependencyProviders(Project project, LoomGradleExtension extension) {
		DependencyProviders dependencyProviders = new DependencyProviders();
		extension.setDependencyProviders(dependencyProviders);

		if (extension.isForgeLike()) {
			dependencyProviders.addProvider(new ForgeProvider(project));
			dependencyProviders.addProvider(new ForgeUserdevProvider(project));
		}

		if (extension.shouldGenerateSrgTiny()) {
			dependencyProviders.addProvider(new SrgProvider(project));
		}

		if (extension.isForgeLike()) {
			dependencyProviders.addProvider(new ForgeUniversalProvider(project));
			dependencyProviders.addProvider(new McpConfigProvider(project));
			dependencyProviders.addProvider(new PatchProvider(project));
		}

		dependencyProviders.handleDependencies(project);
	}

	private void afterEvaluationWithService(Consumer<ServiceFactory> consumer) {
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> {
			try (var serviceFactory = new ScopedServiceFactory()) {
				consumer.accept(serviceFactory);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	// 这里曾有 getGlobalLockObject()：一把经系统属性共享、跨 classloader 的 String monitor，
	// 用来把同一 JVM 内各项目的 setupMinecraft 串行化。它已随 run() 里的调用点一起删除，
	// 原因是它在 --parallel 下与 Gradle 的项目状态转换构成锁序反转，会让 daemon 永久死锁；
	// 具体分析见 run() 中该调用点位置的注释。
}
