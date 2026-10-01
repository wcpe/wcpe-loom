/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.internal;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.FabricModJsonSource;
import net.fabricmc.loom.util.fmj.FabricModJsonHelpers;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

/**
 * 跨项目传递的最小项目数据，只包含基础类型和可序列化集合.
 */
public final class LoomProjectData implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String DATA_ELEMENTS_CONFIGURATION = "loomProjectDataElements";

	private final String projectPath;
	private final List<ModData> mods;
	private final String mappingId;
	private final String productionNamespace;
	private final boolean splitEnvironmentSourceSets;
	private final List<String> mixinMappingFiles;

	public LoomProjectData(String projectPath, List<ModData> mods, @Nullable String mappingId, @Nullable String productionNamespace,
			boolean splitEnvironmentSourceSets, List<String> mixinMappingFiles) {
		this.projectPath = projectPath;
		this.mods = List.copyOf(mods);
		this.mappingId = mappingId;
		this.productionNamespace = productionNamespace;
		this.splitEnvironmentSourceSets = splitEnvironmentSourceSets;
		this.mixinMappingFiles = List.copyOf(mixinMappingFiles);
	}

	public String projectPath() {
		return projectPath;
	}

	public List<ModData> mods() {
		return mods;
	}

	@Nullable
	public String mappingId() {
		return mappingId;
	}

	@Nullable
	public String productionNamespace() {
		return productionNamespace;
	}

	public boolean splitEnvironmentSourceSets() {
		return splitEnvironmentSourceSets;
	}

	public List<String> mixinMappingFiles() {
		return mixinMappingFiles;
	}

	public List<FabricModJson> createMods() {
		return mods.stream().map(ModData::createMod).toList();
	}

	public static LoomProjectData fromProject(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		String mappingId = null;
		String productionNamespace = null;

		try {
			if (!extension.disableObfuscation()) {
				MappingConfiguration mappingConfiguration = extension.getMappingConfiguration();
				mappingId = mappingConfiguration.mappingsIdentifier;
			}
		} catch (RuntimeException ignored) {
			// 忽略：映射尚未就绪时退化为不带映射标识
		}

		try {
			productionNamespace = extension.getProductionNamespaceEnum().get().toString();
		} catch (RuntimeException ignored) {
			// 忽略：生产命名空间未配置时退化为默认值
		}

		List<String> mixinMappingFiles = new ArrayList<>();

		for (SourceSet sourceSet : SourceSetHelper.getSourceSets(project)) {
			try {
				mixinMappingFiles.add(AnnotationProcessorInvoker.getMixinMappingsForSourceSet(project, sourceSet).getAbsolutePath());
			} catch (RuntimeException ignored) {
				// 忽略：没有 Mixin AP 配置的源集不产出映射文件
			}
		}

		return new LoomProjectData(
				project.getPath(),
				FabricModJsonHelpers.getModsInProject(project).stream().map(ModData::fromMod).toList(),
				mappingId,
				productionNamespace,
				extension.areEnvironmentSourceSetsSplit(),
				mixinMappingFiles
		);
	}

	public static List<LoomProjectData> getDependencies(Project project) {
		Map<String, LoomProjectData> result = new LinkedHashMap<>();

		for (String configurationName : List.of(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)) {
			Configuration configuration = project.getConfigurations().findByName(configurationName);

			if (configuration == null) {
				continue;
			}

			for (ProjectDependency dependency : configuration.getAllDependencies().withType(ProjectDependency.class)) {
				LoomProjectData data = fromDependency(project, dependency);

				if (data != null) {
					result.put(data.projectPath(), data);
				}
			}
		}

		return List.copyOf(result.values());
	}

	@Nullable
	public static LoomProjectData fromDependency(Project project, ProjectDependency dependency) {
		LoomGradleSharedData sharedData = LoomGradleSharedData.get(project);
		LoomProjectData data = sharedData.getProject(dependency.getPath());

		if (data != null) {
			return data;
		}

		try {
			Configuration configuration = project.getConfigurations().detachedConfiguration(
					project.getDependencies().project(Map.of(
						"path", dependency.getPath(),
						"configuration", DATA_ELEMENTS_CONFIGURATION
					)));
			configuration.setTransitive(false);
			return read(configuration.getSingleFile().toPath());
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	@Nullable
	public static LoomProjectData read(Path path) {
		try {
			if (!Files.isRegularFile(path)) {
				return null;
			}

			return LoomGradlePlugin.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), LoomProjectData.class);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public void write(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, LoomGradlePlugin.GSON.toJson(this), StandardCharsets.UTF_8);
	}

	public record ModData(String json, Map<String, byte[]> resources) implements Serializable {
		private static final long serialVersionUID = 1L;

		private static final Field JSON_OBJECT;

		static {
			try {
				JSON_OBJECT = FabricModJson.class.getDeclaredField("jsonObject");
				JSON_OBJECT.setAccessible(true);
			} catch (ReflectiveOperationException e) {
				throw new ExceptionInInitializerError(e);
			}
		}

		static ModData fromMod(FabricModJson mod) {
			JsonObject jsonObject = null;

			try {
				jsonObject = ((JsonObject) JSON_OBJECT.get(mod)).deepCopy();
			} catch (IllegalAccessException ignored) {
				// 忽略：无法读取内部 JSON 时退化为一个仅含默认字段的模组数据
			}

			if (jsonObject == null) {
				jsonObject = new JsonObject();
			}

			if (!jsonObject.has("schemaVersion")) {
				jsonObject.addProperty("schemaVersion", 1);
			}

			addStringIfAbsent(jsonObject, "id", safeId(mod));
			addStringIfAbsent(jsonObject, "version", safeVersion(mod));

			Set<String> resourcePaths = new LinkedHashSet<>();
			resourcePaths.addAll(mod.getMixinConfigurations());
			resourcePaths.addAll(mod.getClassTweakers().keySet());
			JsonElement javadoc = mod.getCustom(Constants.CustomModJsonKeys.PROVIDED_JAVADOC);

			if (javadoc != null && javadoc.isJsonPrimitive()) {
				resourcePaths.add(javadoc.getAsString());
			}

			Map<String, byte[]> resources = new LinkedHashMap<>();

			for (String resourcePath : resourcePaths) {
				try {
					resources.put(resourcePath, mod.getSource().read(resourcePath));
				} catch (IOException ignored) {
					// 忽略：缺失的可选资源不参与跨项目数据交换
				}
			}

			return new ModData(LoomGradlePlugin.GSON.toJson(jsonObject), resources);
		}

		private static void addStringIfAbsent(JsonObject jsonObject, String key, @Nullable String value) {
			if (!jsonObject.has(key) && value != null) {
				jsonObject.addProperty(key, value);
			}
		}

		@Nullable
		private static String safeId(FabricModJson mod) {
			try {
				return mod.getId();
			} catch (RuntimeException e) {
				return null;
			}
		}

		@Nullable
		private static String safeVersion(FabricModJson mod) {
			try {
				return mod.getModVersion();
			} catch (RuntimeException e) {
				return null;
			}
		}

		FabricModJson createMod() {
			JsonObject jsonObject = LoomGradlePlugin.GSON.fromJson(json, JsonObject.class);
			Map<String, byte[]> copiedResources = new LinkedHashMap<>(resources);
			FabricModJsonSource source = path -> {
				byte[] bytes = copiedResources.get(path);

				if (bytes == null) {
					throw new IOException("找不到项目数据中的资源：" + path);
				}

				return bytes;
			};
			return FabricModJsonFactory.create(jsonObject, source);
		}
	}
}
