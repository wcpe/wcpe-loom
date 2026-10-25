/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024-2026 FabricMC
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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContextImpl;
import net.fabricmc.loom.configuration.processors.MappingProcessorContextImpl;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/// Provides mappings for decompilation (MC *source* code).
/// This also works in projects with disabled obfuscation
/// where the mappings are just based on javadocs.
public class SourceMappingsService extends Service<SourceMappingsService.Options> {
	public static final ServiceType<Options, SourceMappingsService> TYPE = new ServiceType<>(Options.class, SourceMappingsService.class);
	private static final Logger LOGGER = LoggerFactory.getLogger(SourceMappingsService.class);

	public interface Options extends Service.Options {
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getMappings();

		@Input
		@Optional
		Property<String> getProcessorHash(); // the hash of the processors applied to the mappings
	}

	public static Provider<Options> create(Project project) {
		final Property<String> hash = project.getObjects().property(String.class);

		// 路径与 hash 都必须在配置期算出来：getMappings / getProcessorHash 都是 @Input(File)，
		// Gradle 要在执行前拿它们做输入指纹。若随生成动作一起延迟，快照时二者恒为空 →
		// 指纹不稳定 → 任务每轮重跑（实测：连续两次构建都不 UP-TO-DATE）。
		//
		// 二者都只依赖「补丁器 cacheValue + 扩展的缓存目录」，不读 MC jar，配置期求值安全。
		final Path mappings = resolveMappingsPath(project, hash);

		// 只有「写 mappings 文件」这一步延迟到执行期：它内部会经 createMappings →
		// MinecraftJarProcessorManager.processMappings → TransitiveAccessWidenerMappingsProcessor
		// 读 MC jar（依赖自带的传递性 AW 需要 remapper 翻译命名空间）。而在「MC jar 生产迁到
		// 执行期任务」之后，配置期这些 jar 还不存在，ContextImplHelper.minecraftClassPath 会抛
		// 「尚不存在，无法作为 remapper 的 classpath」——表现为任务创建阶段就失败。
		//
		// 缓存命中时不会走到该路径（见 ensureMappingsGenerated），所以本缺陷只在冷缓存下暴露。
		final Provider<Path> resolvedMappings =
				project.provider(() -> {
					ensureMappingsGenerated(project, mappings);
					return mappings;
				});

		return TYPE.create(project, options -> {
			options.getMappings().fileProvider(resolvedMappings.map(project::file));
			options.getProcessorHash().set(hash);
		});
	}

	/**
	 * 在配置期求出 mappings 文件路径（不含生成动作）.
	 *
	 * <p>同时把补丁器 hash 填进 {@code hashProperty}；无补丁器时留空。
	 */
	private static Path resolveMappingsPath(Project project, Property<String> hashProperty) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftJarProcessorManager jarProcessor = MinecraftJarProcessorManager.create(project);
		final Path dir = extension.getFiles().getProjectPersistentCache().toPath().resolve("source_mappings");
		final boolean disableObf = extension.disableObfuscation();

		if (jarProcessor == null) {
			if (!disableObf) {
				LOGGER.info("No jar processor found, not creating source mappings, using project mappings");
				return extension.getMappingConfiguration().tinyMappings;
			} else {
				LOGGER.info("No jar processor found, using empty source mappings");
				return dir.resolve("empty.tiny");
			}
		}

		final String hash = jarProcessor.getSourceMappingsHash();
		hashProperty.set(hash);
		return dir.resolve(hash + ".tiny");
	}

	/** 执行期：mappings 文件不可复用时生成它，可复用或无需生成时直接返回. */
	private static void ensureMappingsGenerated(Project project, Path path) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftJarProcessorManager jarProcessor = MinecraftJarProcessorManager.create(project);
		final Path dir = extension.getFiles().getProjectPersistentCache().toPath().resolve("source_mappings");
		final Path emptyMappingsPath = dir.resolve("empty.tiny"); // empty base mappings for unobf
		final boolean disableObf = extension.disableObfuscation();

		if (disableObf) {
			ensureEmptyMappings(dir, emptyMappingsPath, extension);

			if (jarProcessor == null) {
				return; // 路径即 empty.tiny，已就绪
			}
		} else if (jarProcessor == null) {
			return; // 路径即项目 mappings，无需生成
		}

		// 就绪判据为内容级，与共享 mappings 产物同一把尺子（见 MappingConfiguration.isReusableMappingsText）：
		// 本文件是按补丁 hash 命名的纯文本 tiny，由 createMappings 用 Tiny2FileWriter 直接写最终路径
		// （先删后写、且没有取锁），被中断或与并发 daemon 交错时都会留下 0 字节残骸；
		// 只判存在会让这份残骸被永久复用，源码反编译静默地按空映射进行。
		if (MappingConfiguration.isReusableMappingsText(path) && !extension.refreshDeps()) {
			LOGGER.debug("Using cached source mappings");
			return;
		}

		LOGGER.info("Creating source mappings for hash {}", jarProcessor.getSourceMappingsHash());
		generateMappings(project, jarProcessor, disableObf ? emptyMappingsPath : extension.getPlatformMappingFile(), path);
	}

	/** 确保 unobf 形态的空 mappings 就位. */
	private static void ensureEmptyMappings(Path dir, Path emptyMappingsPath, LoomGradleExtension extension) {
		if (Files.exists(emptyMappingsPath) && !extension.refreshDeps()) {
			return;
		}

		try {
			Files.createDirectories(dir);
			Files.deleteIfExists(emptyMappingsPath);
			Files.writeString(emptyMappingsPath, "tiny\t2\t0\tofficial\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create empty source mappings", e);
		}
	}

	/** 写出 mappings 文件到 {@code path}. */
	private static void generateMappings(Project project, MinecraftJarProcessorManager jarProcessor, Path inputMappings, Path path) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final Path dir = extension.getFiles().getProjectPersistentCache().toPath().resolve("source_mappings");

		try {
			Files.createDirectories(dir);
			Files.deleteIfExists(path);
			createMappings(project, jarProcessor, inputMappings, path);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create source mappings", e);
		}
	}

	private static void createMappings(Project project, MinecraftJarProcessorManager jarProcessor, Path inputMappings, Path outputMappings) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		MemoryMappingTree mappingTree = new MemoryMappingTree();

		try (Reader reader = Files.newBufferedReader(inputMappings, StandardCharsets.UTF_8)) {
			MappingReader.read(reader, new MappingSourceNsSwitch(mappingTree, extension.getUseIntermediateMappings().get() ? MappingsNamespace.INTERMEDIARY.toString() : MappingsNamespace.OFFICIAL.toString()));
		}

		GenerateSourcesTask.MappingsProcessor mappingsProcessor = mappings -> {
			try (var serviceFactory = new ScopedServiceFactory()) {
				final var configContext = new ConfigContextImpl(project, serviceFactory, extension);
				return jarProcessor.processMappings(mappings, new MappingProcessorContextImpl(configContext));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};

		boolean transformed = mappingsProcessor.transform(mappingTree);

		if (!transformed) {
			LOGGER.info("No mappings processors transformed the mappings");
		}

		try (Writer writer = Files.newBufferedWriter(outputMappings, StandardCharsets.UTF_8)) {
			var tiny2Writer = new Tiny2FileWriter(writer, false);
			mappingTree.accept(new MappingSourceNsSwitch(tiny2Writer, extension.disableObfuscation() ? MappingsNamespace.OFFICIAL.toString() : MappingsNamespace.NAMED.toString()));
		}
	}

	public SourceMappingsService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public Path getMappingsFile() {
		return getOptions().getMappings().getAsFile().get().toPath();
	}

	public @Nullable String getProcessorHash() {
		return getOptions().getProcessorHash().getOrNull();
	}
}
