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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
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
import net.fabricmc.loom.configuration.processors.JsrAnnotationRemapperProcessor;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.processors.ModJavadocProcessor;
import net.fabricmc.loom.configuration.processors.speccontext.DebofConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.task.service.ClasspathGroupService;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.loom.util.gradle.daemon.DaemonUtils;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;

public abstract class CompileConfiguration implements Runnable {
	private static final String LOCK_PROPERTY_KEY = "fabric.loom.internal.global.lock";

	// 共享缓存锁文件名：所有共享同一 loom 缓存目录的项目使用同一把 NIO 文件锁
	private static final String SHARED_CACHE_LOCK_FILE = ".shared-cache.lock";

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
				// 跨 daemon 共享缓存锁：基于 NIO FileChannel.lock() 的 OS 级文件锁。
				// 保护共享缓存目录中的 minecraft jar 文件（下载/合并/remap 产物），
				// 防止多个 Gradle daemon 并发操作导致文件损坏。
				// NIO FileLock 在进程崩溃时由 OS 自动回收，消除 stale lock 问题。
				final Path cacheLockFile = getCacheLockFile();
				final FileChannel lockChannel = FileChannel.open(cacheLockFile,
						StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);

				try {
					final FileLock fileLock = acquireFileLockWithTimeout(lockChannel, getDefaultTimeout());

					try {
						// 同一 daemon 内的 JVM 级锁：序列化同 JVM 下多个项目的配置阶段
						synchronized (getGlobalLockObject()) {
							setupMinecraft(configContext);
						}

						var dependencyManager = new LoomDependencyManager(getProject(), serviceFactory, extension);
						dependencyManager.handleDependencies();
					} finally {
						if (fileLock != null && fileLock.isValid()) {
							fileLock.release();
						}
					}
				} finally {
					lockChannel.close();
				}
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

		if (getProject().getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			// If loom is applied after kapt, then kapt will use the AP arguments too early for loom to pass the arguments we need for mixin.
			throw new IllegalArgumentException("fabric-loom must be applied BEFORE kapt in the plugins { } block.");
		}
	}

	// 获取共享缓存锁文件路径：所有共享同一 loom 缓存目录的项目使用同一把锁
	private Path getCacheLockFile() {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final Path cacheDirectory = extension.getFiles().getUserCache().toPath();

		if (!Files.exists(cacheDirectory)) {
			try {
				Files.createDirectories(cacheDirectory);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		return cacheDirectory.resolve(SHARED_CACHE_LOCK_FILE);
	}

	// 带超时的 NIO 文件锁获取：通过轮询 tryLock() 实现超时等待
	@SuppressWarnings("BusyWait")
	private FileLock acquireFileLockWithTimeout(FileChannel channel, Duration timeout) throws IOException {
		final long timeoutMs = timeout.toMillis();
		final Logger logger = Logging.getLogger("loom_acquireFileLock");
		long waitedMs = 0;

		while (true) {
			final FileLock lock = channel.tryLock();

			if (lock != null) {
				return lock;
			}

			if (waitedMs == 0) {
				logger.lifecycle("Waiting for shared loom cache lock...");
			}

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for cache lock", e);
			}

			waitedMs += 100;

			if (waitedMs >= 1000 * 60 && waitedMs % (1000 * 60) == 0L) {
				logger.lifecycle("Have been waiting for shared loom cache lock for {} minute(s).", waitedMs / 1000 / 60);
			}

			if (waitedMs >= timeoutMs) {
				throw new GradleException("Timed out waiting for shared loom cache lock after %s ms.".formatted(timeoutMs));
			}
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
		extension.setMinecraftProvider(minecraftProvider);
		minecraftProvider.provide();

		if (!extension.disableObfuscation()) {
			// Realise the dependencies without actually resolving them, this forces any lazy providers to be created, populating the layered mapping factories.
			project.getConfigurations().getByName(Configurations.MAPPINGS).getDependencies().toArray();

			// Created any layered mapping files.
			LayeredMappingsFactory.afterEvaluate(configContext);

			// Resolve the mapping files from the configuration
			final DependencyInfo mappingsDep = DependencyInfo.create(getProject(), Configurations.MAPPINGS);
			final MappingConfiguration mappingConfiguration = MappingConfiguration.create(getProject(), configContext.serviceFactory(), mappingsDep, minecraftProvider);
			extension.setMappingConfiguration(mappingConfiguration);
			mappingConfiguration.applyToProject(getProject(), mappingsDep);
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

		final var provideContext = new AbstractMappedMinecraftProvider.ProvideContext(true, extension.refreshDeps(), configContext);

		if (intermediaryMinecraftProvider != null) {
			extension.setIntermediaryMinecraftProvider(intermediaryMinecraftProvider);
			intermediaryMinecraftProvider.provide(provideContext);
		}

		extension.setNamedMinecraftProvider(namedMinecraftProvider);
		namedMinecraftProvider.provide(provideContext);
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

	private static Duration getDefaultTimeout() {
		if (System.getenv("CI") != null) {
			// Set a small timeout on CI, as it's unlikely going to unlock.
			return Duration.ofMinutes(1);
		}

		return Duration.ofHours(1);
	}

	private void finalizedBy(String a, String b) {
		getTasks().named(a).configure(task -> task.finalizedBy(getTasks().named(b)));
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

	// This is a nasty piece of work, but seems to work quite nicely.
	// We need a lock that works across classloaders, a regular synchronized method will not work here.
	// We can abuse system properties as a shared object store that we know for sure will be on the same classloader regardless of what Gradle does to loom.
	// This allows us to ensure that all instances of loom regardless of classloader get the same object to lock on.
	private static Object getGlobalLockObject() {
		if (!System.getProperties().contains(LOCK_PROPERTY_KEY)) {
			// The .intern resolves a possible race where two difference value objects (remember not the same classloader) are set.
			//noinspection StringOperationCanBeSimplified
			System.getProperties().setProperty(LOCK_PROPERTY_KEY, LOCK_PROPERTY_KEY.intern());
		}

		return Objects.requireNonNull(System.getProperty(LOCK_PROPERTY_KEY));
	}
}
