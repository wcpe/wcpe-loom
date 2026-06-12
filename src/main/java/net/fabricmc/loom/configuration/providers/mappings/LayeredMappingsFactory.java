/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingContext;
import net.fabricmc.loom.api.mappings.layered.MappingLayer;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsLayer;
import net.fabricmc.loom.configuration.providers.mappings.extras.unpick.UnpickLayer;
import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.configuration.providers.mappings.utils.AddConstructorMappingVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public record LayeredMappingsFactory(LayeredMappingSpec spec) {
	private static final String GROUP = "loom";
	private static final String MODULE = "mappings";
	private static final Logger LOGGER = LoggerFactory.getLogger(LayeredMappingsFactory.class);

	/*
	As we no longer have SelfResolvingDependency we now always create the mappings file after evaluation.
	This works in a similar way to how remapped mods are handled.
	 */
	public static void afterEvaluate(ConfigContext configContext) {
		for (LayeredMappingsFactory layeredMappingFactory : configContext.extension().getLayeredMappingFactories()) {
			try {
				layeredMappingFactory.evaluate(configContext);
			} catch (Exception e) {
				throw new UncheckedIOException("Failed to setup layered mappings: %s".formatted(layeredMappingFactory.mavenNotation()), new IOException(e));
			}
		}
	}

	private void evaluate(ConfigContext configContext) throws Exception {
		LOGGER.info("Evaluating layer mapping: {}", mavenNotation());

		final Path mavenRepoDir = configContext.extension().getFiles().getGlobalMinecraftRepo().toPath();
		final LocalMavenHelper maven = new LocalMavenHelper(GROUP, MODULE, spec().getVersion(), null, mavenRepoDir);
		final boolean refresh = configContext.extension().refreshDeps();

		// 无锁快路径（关键）：全局仓库已存在该 layered mapping 产物且未要求刷新时，直接返回——
		// 既不取跨进程锁，也不重写全局产物。
		// layered mapping 产物按内容寻址（版本号含 hash），一旦存在即不会变化；
		// 这是暖缓存下多 daemon 并发构建同一版本时「明明只是读取，却互相抢 layered-mappings 锁、
		// 且不断把 mappings jar 重写进全局仓库、导致另一 daemon 的依赖解析冲突」的根因，必须在锁外短路。
		if (!refresh && maven.exists(null)) {
			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(configContext.project()).get();
		final Path lockRoot = configContext.extension().getFiles().getCacheLocks().toPath();

		// 冷/刷新路径：写 GLOBAL 仓库（跨 daemon 共享），用跨进程 per-key 锁串行化首次产出，
		// 避免多个 daemon 同时首次产出同一 layered mapping spec 时互相踩踏。
		cacheService.runExclusive(lockRoot, "layered-mappings:" + spec().getVersion(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：等锁期间可能已被其它进程产出，避免重复写
			if (!refresh && maven.exists(null)) {
				return null;
			}

			final Path jar = resolve(configContext.project());
			maven.copyToMaven(jar, null);
			return null;
		});
	}

	public Path resolve(Project project) throws IOException {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MappingContext mappingContext = new GradleMappingContext(project, spec.getVersion().replace("+", "_").replace(".", "_"));
		final Path mappingsDir = mappingContext.minecraftProvider().dir("layered").toPath();
		final Path mappingsZip = mappingsDir.resolve(String.format("%s.%s-%s.jar", GROUP, MODULE, spec.getVersion()));

		if (Files.exists(mappingsZip) && !mappingContext.refreshDeps()) {
			return mappingsZip;
		}

		boolean useIntermediateMappings = extension.getUseIntermediateMappings().get();
		var processor = new LayeredMappingsProcessor(spec, !useIntermediateMappings);
		List<MappingLayer> layers = processor.resolveLayers(mappingContext);

		Files.deleteIfExists(mappingsZip);

		writeMapping(processor, layers, mappingsZip, useIntermediateMappings);
		writeAnnotationData(processor, layers, mappingsZip);
		writeSignatureFixes(processor, layers, mappingsZip);
		writeUnpickData(processor, layers, mappingsZip);

		return mappingsZip;
	}

	public Dependency createDependency(Project project) {
		return project.getDependencies().create(mavenNotation());
	}

	public String mavenNotation() {
		return String.format("%s:%s:%s", GROUP, MODULE, spec.getVersion());
	}

	private void writeMapping(LayeredMappingsProcessor processor, List<MappingLayer> layers, Path mappingsFile, boolean useIntermediateMappings) throws IOException {
		MemoryMappingTree mappings = processor.getMappings(layers);

		try (Writer writer = new StringWriter()) {
			var tiny2Writer = new Tiny2FileWriter(writer, false);

			MappingDstNsReorder nsReorder = new MappingDstNsReorder(tiny2Writer, useIntermediateMappings ? List.of(MappingsNamespace.NAMED.toString(), MappingsNamespace.OFFICIAL.toString()) : List.of(MappingsNamespace.NAMED.toString()));
			MappingSourceNsSwitch nsSwitch = new MappingSourceNsSwitch(nsReorder, useIntermediateMappings ? MappingsNamespace.INTERMEDIARY.toString() : MappingsNamespace.OFFICIAL.toString(), true);
			AddConstructorMappingVisitor addConstructor = new AddConstructorMappingVisitor(nsSwitch);
			mappings.accept(addConstructor);

			Files.deleteIfExists(mappingsFile);
			ZipUtils.add(mappingsFile, "mappings/mappings.tiny", writer.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private void writeAnnotationData(LayeredMappingsProcessor processor, List<MappingLayer> layers, Path mappingsFile) throws IOException {
		List<AnnotationsData> annotationsData = processor.getAnnotationsData(layers);

		if (annotationsData.isEmpty()) {
			return;
		}

		byte[] data = AnnotationsData.GSON.toJson(AnnotationsData.listToJson(annotationsData)).getBytes(StandardCharsets.UTF_8);

		ZipUtils.add(mappingsFile, AnnotationsLayer.ANNOTATIONS_PATH, data);
	}

	private void writeSignatureFixes(LayeredMappingsProcessor processor, List<MappingLayer> layers, Path mappingsFile) throws IOException {
		Map<String, String> signatureFixes = processor.getSignatureFixes(layers);

		if (signatureFixes == null) {
			return;
		}

		byte[] data = LoomGradlePlugin.GSON.toJson(signatureFixes).getBytes(StandardCharsets.UTF_8);

		ZipUtils.add(mappingsFile, "extras/record_signatures.json", data);
	}

	private void writeUnpickData(LayeredMappingsProcessor processor, List<MappingLayer> layers, Path mappingsFile) throws IOException {
		UnpickLayer.UnpickData unpickData = processor.getUnpickData(layers);

		if (unpickData == null) {
			return;
		}

		byte[] data = UnpickMetadata.toJson(unpickData.metadata()).getBytes(StandardCharsets.UTF_8);

		ZipUtils.add(mappingsFile, UnpickMetadata.UNPICK_DEFINITIONS_PATH, unpickData.definitions());
		ZipUtils.add(mappingsFile, UnpickMetadata.UNPICK_METADATA_PATH, data);
	}
}
