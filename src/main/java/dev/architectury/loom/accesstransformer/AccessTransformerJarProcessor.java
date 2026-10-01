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

package dev.architectury.loom.accesstransformer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dev.architectury.at.AccessTransformSet;
import dev.architectury.at.io.AccessTransformFormats;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MinecraftJarProcessor;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

public class AccessTransformerJarProcessor implements MinecraftJarProcessor<AccessTransformerJarProcessor.Spec> {
	private static final Logger LOGGER = Logging.getLogger(AccessTransformerJarProcessor.class);
	private final String name;
	private final Iterable<File> localAccessTransformers;
	private final ObjectFactory objectFactory;
	/** 仅配置期实例持有：执行期没有 {@link Project}，AT 工具信息由 {@link Descriptor} 带过来. */
	private final @Nullable Project project;
	/** 启动 AT 工具所需的信息，配置期惰性解析并缓存. */
	private AccessTransformerService.@Nullable Tool tool;

	/**
	 * 配置期构造器：AT 工具推迟到真正需要时（{@link #descriptor()} 或 {@link #processJar}）再解析.
	 */
	@Inject
	public AccessTransformerJarProcessor(String name, Project project, Iterable<File> localAccessTransformers, ObjectFactory objectFactory) {
		this.name = name;
		this.project = project;
		this.localAccessTransformers = localAccessTransformers;
		this.objectFactory = objectFactory;
	}

	/**
	 * 执行期重建用构造器：不依赖 {@link Project}，AT 工具信息与对象工厂都由描述符提供.
	 *
	 * <p>本构造器由 {@link Descriptor#createProcessor} 直接调用而不经 {@code ObjectFactory.newInstance}：
	 * 本类唯一的 {@code @Inject} 构造器需要一个 {@link Project}，执行期无法提供，而 Gradle 在存在
	 * {@code @Inject} 构造器时只会用它，传参形态再吻合也不会选到另一个构造器。
	 */
	public AccessTransformerJarProcessor(String name, Iterable<File> localAccessTransformers, AccessTransformerService.Tool tool, ObjectFactory objectFactory) {
		this.name = name;
		this.project = null;
		this.localAccessTransformers = localAccessTransformers;
		this.objectFactory = objectFactory;
		this.tool = tool;
	}

	@Override
	public AccessTransformerJarProcessor.@Nullable Spec buildSpec(SpecContext context) {
		final List<AccessTransformerEntry> entries = new ArrayList<>();

		for (File atFile : localAccessTransformers) {
			final Path atPath = atFile.toPath();
			final String hash = Checksum.of(atPath).sha256().hex();
			entries.add(new AccessTransformerEntry.Standalone(atPath, hash));
		}

		for (FabricModJson localMod : context.localMods()) {
			final byte[] bytes;

			try {
				// TODO: Shouldn't we check for the mods.toml AT list on Neo?
				bytes = localMod.getSource().read(Constants.Forge.ACCESS_TRANSFORMER_PATH);
			} catch (FileNotFoundException | NoSuchFileException e) {
				continue;
			} catch (IOException e) {
				throw ExceptionUtil.createDescriptiveWrapper(UncheckedIOException::new, "Could not read accesstransformer.cfg", e);
			}

			final String hash = Checksum.of(bytes).sha256().hex();
			// 规则内容随条目一起带走：mod 元数据的来源（源码集/jar）不能进任务状态，理由见 Mod 的注释
			entries.add(new AccessTransformerEntry.Mod(localMod.getId(), localMod.getVersion(), bytes, hash));
		}

		return !entries.isEmpty() ? new Spec(entries) : null;
	}

	@Override
	public void processJar(Path jar, Spec spec, ProcessorContext context) throws IOException {
		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			LOGGER.lifecycle(":applying project access transformers");
			final Path tempInput = tempFiles.file("input", ".jar");
			Files.copy(jar, tempInput, StandardCopyOption.REPLACE_EXISTING);
			final Path atPath = mergeAndRemapAccessTransformers(context, spec.accessTransformers(), tempFiles);

			final AccessTransformerService service = serviceFactory.get(AccessTransformerService.createOptions(objectFactory, getTool(), atPath.toAbsolutePath()));
			service.execute(tempInput, jar);
		} catch (IOException e) {
			throw ExceptionUtil.createDescriptiveWrapper(UncheckedIOException::new, "Could not access transform " + jar.toAbsolutePath(), e);
		}
	}

	/**
	 * 取得启动 AT 工具所需的信息：配置期实例惰性解析一次并缓存，执行期实例由描述符给出.
	 */
	private AccessTransformerService.Tool getTool() {
		if (tool == null) {
			if (project == null) {
				throw new IllegalStateException("AccessTransformerJarProcessor 既没有 Project，描述符里也没有 AT 工具信息");
			}

			tool = AccessTransformerService.resolveTool(project);
		}

		return tool;
	}

	private Path mergeAndRemapAccessTransformers(ProcessorContext context, List<AccessTransformerEntry> accessTransformers, TempFiles tempFiles) throws IOException {
		AccessTransformSet accessTransformSet = AccessTransformSet.create();

		for (AccessTransformerEntry entry : accessTransformers) {
			try (Reader reader = entry.openReader()) {
				accessTransformSet.merge(AccessTransformFormats.FML.read(reader));
			} catch (IOException e) {
				throw new IOException("Could not read access transformer " + entry, e);
			}
		}

		if (!context.disableObfuscation()) {
			accessTransformSet = accessTransformSet.remap(context.getMappings(), context.getIntermediaryNamespace().toString(), MappingsNamespace.NAMED.toString());
		}

		final Path accessTransformerPath = tempFiles.file("accesstransformer-merged", ".cfg");

		try {
			AccessTransformFormats.FML.write(accessTransformerPath, accessTransformSet);
		} catch (IOException e) {
			throw new IOException("Could not write access transformers to " + accessTransformerPath, e);
		}

		return accessTransformerPath;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Descriptor descriptor() {
		return new Descriptor(name, localAccessTransformerPaths(), getTool());
	}

	/**
	 * 本地 AT 文件的绝对路径，排序以保证描述符的取值稳定.
	 */
	private List<String> localAccessTransformerPaths() {
		final List<String> paths = new ArrayList<>();

		for (File file : localAccessTransformers) {
			paths.add(file.getAbsolutePath());
		}

		return paths.stream().sorted().toList();
	}

	/**
	 * 用于在执行期重建 {@link AccessTransformerJarProcessor} 的描述符.
	 *
	 * <p>AT 工具的信息必须随描述符一起走：它的 classpath 来自项目依赖解析、JVM 可执行文件来自
	 * Java 工具链，二者都只能在配置期取得。因此调用 {@link AccessTransformerJarProcessor#descriptor()}
	 * 时项目模型必须已经就绪（Minecraft provider 与 Java 工具链都已配置），否则会解析出错误的工具。
	 *
	 * @param name processor 名称
	 * @param localAccessTransformers 本地 AT 文件的绝对路径
	 * @param tool 启动 AT 工具所需的纯值
	 */
	public record Descriptor(String name, List<String> localAccessTransformers, AccessTransformerService.Tool tool)
			implements MinecraftJarProcessor.ProcessorDescriptor<AccessTransformerJarProcessor> {
		public Descriptor {
			localAccessTransformers = List.copyOf(localAccessTransformers);
		}

		@Override
		public AccessTransformerJarProcessor createProcessor(ObjectFactory objectFactory) {
			return new AccessTransformerJarProcessor(
					name,
					localAccessTransformers.stream().map(File::new).toList(),
					tool,
					objectFactory
			);
		}
	}

	@FunctionalInterface
	public interface AccessTransformerConfiguration {
		void apply(List<String> args) throws IOException;
	}

	public record Spec(List<AccessTransformerEntry> accessTransformers) implements MinecraftJarProcessor.Spec {
	}
}
