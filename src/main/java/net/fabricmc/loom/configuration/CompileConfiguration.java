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
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.internal.project.ProjectInternal;
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
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.GradleUtils;
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

			try {
				setupMinecraft(configContext);

				new LoomDependencyManager(getProject(), serviceFactory, extension).handleDependencies();
			} catch (Exception e) {
				ExceptionUtil.processException(e, DaemonUtils.Context.fromProject(getProject()));
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to setup Minecraft", e);
			} finally {
				extension.setRefreshDeps(previousRefreshDeps);
			}

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
				if (!ModConfigurationRemapper.isCIBuild() && !extension.disableObfuscation()) {
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
		// see http://yodaconditions.net/blog/fix-for-a-java-file-encoding-problem
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

		if (extension.isForgeLike() && extension.disableObfuscation()) {
			// TODO: Allow setting up Forge and NeoForge without obfuscation
			//throw new UnsupportedOperationException("Using %s without obfuscation is not supported!".formatted(extension.getPlatform().get().displayName()));
		}

		extension.setMinecraftProvider(minecraftProvider);
		minecraftProvider.provide();

		if (!extension.disableObfuscation()) {
			// Realise the dependencies without actually resolving them, this forces any lazy providers to be created, populating the layered mapping factories.
			project.getConfigurations().getByName(Configurations.MAPPINGS).getDependencies().toArray();

			// Created any layered mapping files.
			LayeredMappingsFactory.afterEvaluate(configContext);

			// This needs to run after MinecraftProvider.initFiles and MinecraftLibraryProvider.provide
			// but before MinecraftPatchedProvider.provide.
			setupDependencyProviders(project, extension);

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
				((ForgeMinecraftProvider) minecraftProvider).getPatchedProvider().provide(configContext.serviceFactory());
			}

			mappingConfiguration.setupPost(project);
			mappingConfiguration.applyToProject(getProject(), mappingsDep);
		} else {
			setupDependencyProviders(project, extension);

			if (extension.isForgeLike()) {
				ForgeLibrariesProvider.provide(null, project);

				((ForgeMinecraftProvider) minecraftProvider).getPatchedProvider().provide(configContext.serviceFactory());
			}
		}

		if (extension.isForgeLike()) {
			extension.setForgeRunsProvider(ForgeRunsProvider.create(project));
		}

		if (minecraftProvider instanceof ForgeMinecraftProvider patched) {
			patched.getPatchedProvider().remapJar(configContext.serviceFactory());
		}

		// Provide the remapped mc jars
		IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider = extension.getUseIntermediateMappings().get() ? jarConfiguration.createIntermediaryMinecraftProvider(project) : null;
		NamedMinecraftProvider<?> namedMinecraftProvider = jarConfiguration.createNamedMinecraftProvider(project);

		registerGameProcessors(configContext);
		MinecraftJarProcessorManager minecraftJarProcessorManager = MinecraftJarProcessorManager.create(getProject());

		if (minecraftJarProcessorManager != null) {
			// Wrap the named MC provider for one that will provide the processed jars
			namedMinecraftProvider = jarConfiguration.createProcessedNamedMinecraftProvider(namedMinecraftProvider, minecraftJarProcessorManager);
		}

		final var provideContext = new AbstractMappedMinecraftProvider.ProvideContext(true, extension.refreshDeps(), configContext);

		if (intermediaryMinecraftProvider != null) {
			extension.setIntermediaryMinecraftProvider(intermediaryMinecraftProvider);
		}

		extension.setNamedMinecraftProvider(namedMinecraftProvider);
		provideMappedMinecraftJars(project, extension, intermediaryMinecraftProvider, namedMinecraftProvider, provideContext);
	}

	private void provideMappedMinecraftJars(Project project, LoomGradleExtension extension, IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider, NamedMinecraftProvider<?> namedMinecraftProvider, AbstractMappedMinecraftProvider.ProvideContext provideContext) throws Exception {
		final String mappingsIdentifier = extension.disableObfuscation() ? "deobf" : extension.getMappingConfiguration().mappingsIdentifier();
		final String key = "minecraft-provision:" + extension.getMinecraftProvider().minecraftVersion() + ":" + mappingsIdentifier;
		final LoomCacheService cacheService = LoomCacheService.get(project).get();
		final var lockRoot = extension.getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, key, LoomCacheService.defaultTimeout(), () -> {
			// 输出检查与重建必须同属一个事务；否则另一项目会在本项目读取 intermediary 时删除并重建它。
			provideMappedMinecraftJarsLocked(project, extension, intermediaryMinecraftProvider, namedMinecraftProvider, provideContext);
			return null;
		});
	}

	private void provideMappedMinecraftJarsLocked(Project project, LoomGradleExtension extension, IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider, NamedMinecraftProvider<?> namedMinecraftProvider, AbstractMappedMinecraftProvider.ProvideContext provideContext) throws Exception {
		if (intermediaryMinecraftProvider != null) {
			intermediaryMinecraftProvider.provide(provideContext);
		}

		namedMinecraftProvider.provide(provideContext);

		if (extension.isForge()) {
			final SrgMinecraftProvider<?> srgMinecraftProvider = extension.getMinecraftJarConfiguration().get().createSrgMinecraftProvider(project);
			extension.setSrgMinecraftProvider(srgMinecraftProvider);
			srgMinecraftProvider.provide(provideContext);
		}

		if (extension.isForgeLike() && extension.getForgeProvider().usesMojangAtRuntime()) {
			final MojangMappedMinecraftProvider<?> mojangMappedMinecraftProvider = extension.getMinecraftJarConfiguration().get().createMojangMappedMinecraftProvider(project);
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
}
