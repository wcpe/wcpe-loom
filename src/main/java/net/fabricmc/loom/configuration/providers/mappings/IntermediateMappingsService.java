/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.function.Supplier;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.intermediate.IntermediateMappingsProvider;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Lazy;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class IntermediateMappingsService extends Service<IntermediateMappingsService.Options> {
	public static final ServiceType<Options, IntermediateMappingsService> TYPE = new ServiceType<>(Options.class, IntermediateMappingsService.class);
	private static final Logger LOGGER = LoggerFactory.getLogger(IntermediateMappingsService.class);

	public interface Options extends Service.Options {
		@InputFile
		RegularFileProperty getIntermediaryTiny();
		@Input
		Property<String> getExpectedSrcNs();
		@Input
		Property<String> getMinecraftVersion();
	}

	private final Supplier<MemoryMappingTree> memoryMappingTree = Lazy.of(this::createMemoryMappingTree);

	public IntermediateMappingsService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public static Provider<Options> createOptions(Project project, MinecraftProvider minecraftProvider) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (!extension.getUseIntermediateMappings().get()) {
			throw new IllegalStateException("Intermediary mappings is disabled");
		}

		final IntermediateMappingsProvider intermediateProvider = extension.getIntermediateMappingsProvider();
		// 先取好名字：失败分支要拿它写日志，而从 provider 取名字是（可能由用户实现的）代码，
		// 不应在异常处理路径上再执行一次
		final String providerName = intermediateProvider.getName();
		final Path intermediaryTiny = minecraftProvider.file(providerName + ".tiny").toPath();

		try {
			if (intermediateProvider instanceof IntermediateMappingsProviderInternal internal) {
				internal.provide(intermediaryTiny, project);
			} else {
				intermediateProvider.provide(intermediaryTiny);
			}
		} catch (IOException e) {
			handleProvideFailure(providerName, intermediateProvider, intermediaryTiny, e);
			throw new UncheckedIOException("Failed to provide intermediate mappings", e);
		}

		return createOptions(project, minecraftProvider, intermediaryTiny);
	}

	/**
	 * 提供中间映射失败后的兜底处理.
	 *
	 * <p>内置 provider（{@link IntermediateMappingsProviderInternal} 与 {@link GeneratedIntermediateMappingsProvider}）
	 * 都改为「临时文件 + 原子 move」发布：失败时盘上要么是旧发布的完整产物、要么是本次发布的完整产物，
	 * 不会留下半截文件，因此不能再删除——产物位于跨工作树/daemon 共享的缓存目录
	 * （{@code <userCache>/<mcVersion>/}），无条件删除会删掉其它进程刚成功发布的完整产物，
	 * 使正在读它的进程遭遇 {@code NoSuchFileException}。这些 provider 会读取刷新标记，
	 * 产物真的损坏时可用 {@code --refresh-dependencies} 强制重建（刷新不再靠删除实现）。
	 *
	 * <p>用户自定义的 {@link IntermediateMappingsProvider} 相反：公开 API
	 * （{@code LoomGradleExtensionApiImpl#setIntermediateMappingsProvider}）只向它暴露 MC 版本、下载器与
	 * 命名空间，没有任何刷新信号，实现通常「存在即返回」并就地写目标路径。这种 provider 一旦写坏，
	 * 损坏文件会被永久复用，而 {@code --refresh-dependencies} 对它无效——故只对它保留原有的
	 * 「失败即删除」兜底，让下次构建重新生成。
	 */
	private static void handleProvideFailure(String providerName, IntermediateMappingsProvider provider, Path intermediaryTiny, IOException failure) {
		if (isAtomicProvider(provider)) {
			LOGGER.warn("[{}] 提供中间映射失败，保留已有产物（该 provider 采用原子发布）；若产物本身已损坏，可加 --refresh-dependencies 强制重建", providerName, failure);
			return;
		}

		try {
			Files.deleteIfExists(intermediaryTiny);
			LOGGER.warn("[{}] 提供中间映射失败，已删除可能损坏的产物 {}，下次构建会重新生成；自定义 provider 不受 --refresh-dependencies 影响", providerName, intermediaryTiny, failure);
		} catch (IOException deleteFailure) {
			// 删除失败不掩盖原始失败：原始异常仍会由调用方抛出并附带在异常链中
			LOGGER.warn("[{}] 提供中间映射失败，且删除产物 {} 也失败；请手动删除该文件后重试", providerName, intermediaryTiny, deleteFailure);
		}
	}

	/**
	 * 该 provider 是否属于「已知原子发布产物」的实现（失败时无需删除兜底）.
	 */
	private static boolean isAtomicProvider(IntermediateMappingsProvider provider) {
		return provider instanceof IntermediateMappingsProviderInternal
				|| provider instanceof GeneratedIntermediateMappingsProvider;
	}

	private static Provider<Options> createOptions(Project project, MinecraftProvider minecraftProvider, Path intermediaryTiny) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final IntermediateMappingsProvider intermediateProvider = extension.getIntermediateMappingsProvider();
		// When merging legacy versions there will be multiple named namespaces, so use intermediary as the common src ns
		// Newer versions will use intermediary as the src ns
		final String expectedSrcNs = minecraftProvider.isLegacySplitOfficialNamespaceVersion()
				? MappingsNamespace.INTERMEDIARY.toString() // >=beta 1.0 and <1.3
				: MappingsNamespace.OFFICIAL.toString(); // >=1.3 or <b1.0

		return TYPE.create(project, options -> {
			options.getIntermediaryTiny().set(intermediaryTiny.toFile());
			options.getExpectedSrcNs().set(expectedSrcNs);
			options.getMinecraftVersion().set(intermediateProvider.getMinecraftVersion());
		});
	}

	private MemoryMappingTree createMemoryMappingTree() {
		return createMemoryMappingTree(getIntermediaryTiny(), getOptions().getExpectedSrcNs().get());
	}

	@VisibleForTesting
	public static MemoryMappingTree createMemoryMappingTree(Path mappingFile, String expectedSrcNs) {
		final MemoryMappingTree tree = new MemoryMappingTree();

		try {
			MappingNsCompleter nsCompleter = new MappingNsCompleter(tree, Collections.singletonMap(MappingsNamespace.NAMED.toString(), MappingsNamespace.INTERMEDIARY.toString()), true);

			try (BufferedReader reader = Files.newBufferedReader(mappingFile, StandardCharsets.UTF_8)) {
				Tiny2FileReader.read(reader, nsCompleter);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read intermediary mappings", e);
		}

		if (!expectedSrcNs.equals(tree.getSrcNamespace())) {
			throw new RuntimeException("Invalid intermediate mappings: expected source namespace '" + expectedSrcNs + "' but found '" + tree.getSrcNamespace() + "\'");
		}

		return tree;
	}

	public MemoryMappingTree getMemoryMappingTree() {
		return memoryMappingTree.get();
	}

	public Path getIntermediaryTiny() {
		return getOptions().getIntermediaryTiny().get().getAsFile().toPath();
	}
}
