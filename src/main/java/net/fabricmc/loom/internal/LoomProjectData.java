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
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.architectury.loom.metadata.ModMetadataFile;
import dev.architectury.loom.metadata.ModMetadataFiles;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.FabricModJsonSource;
import net.fabricmc.loom.util.fmj.FabricModJsonHelpers;
import net.fabricmc.loom.util.fmj.ModMetadataFabricModJson;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

/**
 * 跨项目传递的最小项目数据，只包含基础类型和可序列化集合.
 *
 * <p>相等性只由 {@link #projectPath()} 决定。同一次构建里，同一个项目的数据可能来自内存共享表
 * （{@link LoomGradleSharedData} 中的既有实例）、直接读取依赖项目导出的 {@code project-data.json}
 * （{@link #fromDependency}）或两者的组合，内容相同但实例不同。消费方用
 * {@code distinct()} 与 {@code contains} 判断「是不是同一个依赖项目」，因此身份必须落在项目路径上；
 * 否则文件回退路径会把同一个项目看成两个不同的项目，使交集为空（依赖项目的 mod 被静默丢弃）
 * 或 {@code distinct()} 失效（同一个 mod 被处理两次）。
 */
public final class LoomProjectData implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String DATA_ELEMENTS_CONFIGURATION = "loomProjectDataElements";
	private static final Logger LOGGER = Logging.getLogger(LoomProjectData.class);

	private final String projectPath;
	private final List<ModData> mods;
	private final String mappingId;
	/** 生产命名空间；当前仓库内没有消费方，仅为保持跨项目数据形态稳定而保留，不要假定它已被使用. */
	private final String productionNamespace;
	/** 是否拆分环境源集；当前仓库内没有消费方，保留原因同 {@link #productionNamespace}. */
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

	/**
	 * 相等性只看 {@link #projectPath()}：该数据是项目自身状态的投影，一个项目在一次构建里只对应一份数据，
	 * 不同来源（内存共享表、导出文件）得到的对象必须被视为同一个项目，见类注释.
	 */
	@Override
	public boolean equals(@Nullable Object obj) {
		return this == obj || obj instanceof LoomProjectData other && projectPath.equals(other.projectPath);
	}

	@Override
	public int hashCode() {
		return projectPath.hashCode();
	}

	@Override
	public String toString() {
		return "LoomProjectData[" + projectPath + "]";
	}

	public List<FabricModJson> createMods() {
		return mods.stream().map(ModData::createMod).toList();
	}

	public static LoomProjectData fromProject(Project project) {
		return new LoomProjectData(
				project.getPath(),
				FabricModJsonHelpers.getModsInProject(project).stream().map(ModData::fromMod).toList(),
				readMappingId(project),
				readProductionNamespace(project),
				LoomGradleExtension.get(project).areEnvironmentSourceSetsSplit(),
				readMixinMappingFiles(project)
		);
	}

	/**
	 * 读取生产命名空间，与 {@link #fromProject(Project)} 打包进 DTO 的值同源.
	 *
	 * <p>供 {@code exportLoomProjectData} 把它登记为任务输入；未配置时返回 {@code null}。
	 *
	 * @param project 目标项目
	 * @return 生产命名空间名称，或 {@code null} 表示当前构建里还拿不到
	 */
	@Nullable
	public static String readProductionNamespace(Project project) {
		try {
			return LoomGradleExtension.get(project).getProductionNamespaceEnum().get().toString();
		} catch (RuntimeException e) {
			// 忽略：生产命名空间未配置时退化为默认值
			LOGGER.debug("Production namespace is not available in {}", project.getPath(), e);
			return null;
		}
	}

	/**
	 * 读取项目内全部 mod 的元数据 JSON，与 {@link #fromProject(Project)} 写入 DTO 的 mod 列表同源.
	 *
	 * <p>供 {@code exportLoomProjectData} 登记输入指纹：mod 元数据是导出内容的一部分，
	 * 它的变化必须让导出文件失效。
	 *
	 * @param project 目标项目
	 * @return 每个 mod 一份元数据 JSON
	 */
	public static List<String> readModJson(Project project) {
		return FabricModJsonHelpers.getModsInProject(project).stream()
				.map(ModData::fromMod)
				.map(ModData::json)
				.toList();
	}

	/**
	 * 读取当前项目的映射标识，与 {@link #fromProject(Project)} 打包进 DTO 的值同源.
	 *
	 * <p>供 {@code exportLoomProjectData} 把它登记为任务输入；映射尚未就绪或未启用混淆时返回
	 * {@code null}（此时任务侧的输入属性为空），而不是抛异常。
	 *
	 * @param project 目标项目
	 * @return 映射标识，或 {@code null} 表示当前构建里还拿不到
	 */
	@Nullable
	public static String readMappingId(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		try {
			if (!extension.disableObfuscation()) {
				return extension.getMappingConfiguration().mappingsIdentifier;
			}
		} catch (RuntimeException e) {
			// 忽略：映射尚未就绪时退化为不带映射标识
			LOGGER.debug("Mapping identifier is not available in {}", project.getPath(), e);
		}

		return null;
	}

	/**
	 * 读取项目内全部 Mixin AP 映射文件路径，与 {@link #fromProject(Project)} 打包进 DTO 的列表同源.
	 *
	 * <p>供 {@code exportLoomProjectData} 登记输入指纹。注意 DTO 里保存的是<b>路径</b>，
	 * 消费方直接按路径读取生产者项目的文件，因此映射文件本身的内容不属于导出内容的一部分。
	 *
	 * @param project 目标项目
	 * @return 映射文件的绝对路径，顺序与源集顺序一致
	 */
	public static List<String> readMixinMappingFiles(Project project) {
		final List<String> files = new ArrayList<>();

		for (SourceSet sourceSet : SourceSetHelper.getSourceSets(project)) {
			try {
				files.add(AnnotationProcessorInvoker.getMixinMappingsForSourceSet(project, sourceSet).getAbsolutePath());
			} catch (RuntimeException e) {
				// 忽略：没有 Mixin AP 配置的源集不产出映射文件
				LOGGER.debug("No mixin mappings for source set {} in {}", sourceSet.getName(), project.getPath(), e);
			}
		}

		return List.copyOf(files);
	}

	public static List<LoomProjectData> getDependencies(Project project) {
		Map<String, LoomProjectData> result = new LinkedHashMap<>();

		for (String configurationName : List.of(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)) {
			Configuration configuration = project.getConfigurations().findByName(configurationName);

			if (configuration == null) {
				continue;
			}

			for (ProjectDependency dependency : configuration.getAllDependencies().withType(ProjectDependency.class)) {
				fromDependency(project, dependency).ifPresent(data -> result.put(data.projectPath(), data));
			}
		}

		return List.copyOf(result.values());
	}

	/**
	 * 读取依赖项目的跨项目数据，并让三种结果在 API 层面互不混淆.
	 *
	 * <ul>
	 *     <li>返回非空：依赖项目是 Loom 项目，且数据可用；
	 *     <li>返回 {@link Optional#empty()}：依赖项目没有暴露 {@link #DATA_ELEMENTS_CONFIGURATION}，
	 *         即「本来就不是 Loom 项目」，只记 debug 日志，不产生噪音；
	 *     <li>抛出异常：依赖项目确实暴露了数据文件，但文件读取或解析失败。这是「真失败」，
	 *         必须上抛——若静默降级成空结果，调用方会把「跨项目数据损坏」当成「该依赖不是 Loom 项目」，
	 *         进而静默丢掉依赖项目的 mod 与 mixin 映射，产出不完整的 remap 结果。
	 * </ul>
	 *
	 * @param project 发起查询的项目
	 * @param dependency 待查询的项目依赖
	 * @return 依赖项目的跨项目数据；空表示该依赖不是 Loom 项目
	 */
	public static Optional<LoomProjectData> fromDependency(Project project, ProjectDependency dependency) {
		LoomGradleSharedData sharedData = LoomGradleSharedData.get(project);
		LoomProjectData data = sharedData.getProject(dependency.getPath());

		if (data != null) {
			return Optional.of(data);
		}

		final Path path;

		try {
			Configuration configuration = project.getConfigurations().detachedConfiguration(
					project.getDependencies().project(Map.of(
						"path", dependency.getPath(),
						"configuration", DATA_ELEMENTS_CONFIGURATION
					)));
			configuration.setTransitive(false);
			path = configuration.getSingleFile().toPath();
		} catch (RuntimeException e) {
			// 「本来就没有」：依赖不是 Loom 项目、没有该可消费配置，或数据尚未登记。
			// 这类情况在混用普通 jar 依赖的项目里很常见，因此只记 debug，不打断构建。
			LOGGER.debug("Dependency {} does not expose Loom project data", dependency.getPath(), e);
			return Optional.empty();
		}

		// 解析成功只说明「依赖项目声明了这个产物路径」，**不代表文件已产出**：冷启动（或该依赖
		// 从未构建过）时 exportLoomProjectData 尚未执行，这里必然拿到一个不存在的路径。
		// 因此「文件不存在」属于常规情形，直接返回空，绝不能升级成失败——那会在配置期把冷启动
		// 打死：配置本项目的 afterEvaluate → 需要该数据文件 → 文件需对端任务产出 → 任务需所有
		// 项目配置完成 → 回到起点。
		// 文件存在却读不出来仍由 read 抛出（见其 javadoc），损坏不会被伪装成「没有数据」。
		return read(path);
	}

	/**
	 * 读取跨项目数据文件.
	 *
	 * <p>空结果与失败被刻意区分开：文件不存在属于「该项目还没有产出数据」的常规情形，
	 * 返回 {@link Optional#empty()} 且不写日志；文件存在却读不动或解析不了时，写一条
	 * 默认可见（{@code warn}）的日志并抛出异常，避免把损坏的数据伪装成「没有数据」。
	 *
	 * @param path {@code exportLoomProjectData} 产出的 {@code project-data.json} 路径
	 * @return 解析出的项目数据；文件不存在时为空
	 * @throws UncheckedIOException 文件存在但读取失败
	 * @throws JsonSyntaxException 文件存在但内容不是可用的项目数据
	 */
	public static Optional<LoomProjectData> read(Path path) {
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}

		final String json;

		try {
			json = Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Failed to read Loom project data from {}", path, e);
			throw new UncheckedIOException("无法读取跨项目数据: " + path, e);
		}

		final LoomProjectData data;

		try {
			data = LoomGradlePlugin.GSON.fromJson(json, LoomProjectData.class);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to parse Loom project data from {}", path, e);
			throw new JsonSyntaxException("跨项目数据无法解析: " + path, e);
		}

		if (data == null) {
			// 文件为空（例如写入过程中被中断）时 Gson 直接返回 null。
			LOGGER.warn("Loom project data at {} is empty", path);
			throw new JsonSyntaxException("跨项目数据为空: " + path);
		}

		if (data.projectPath() == null || data.mods() == null || data.mixinMappingFiles() == null) {
			// 字段缺失通常意味着文件由其它版本/其它项目写入：必须显式失败，不能带着 null 继续跑。
			LOGGER.warn("Loom project data at {} is incomplete: {}", path, data);
			throw new JsonSyntaxException("跨项目数据缺少必要字段: " + path);
		}

		return Optional.of(data);
	}

	public void write(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, LoomGradlePlugin.GSON.toJson(this), StandardCharsets.UTF_8);
	}

	/**
	 * 单个模组的跨项目数据.
	 *
	 * @param json 模组元数据 JSON，保持源对象的原始键（不补 {@code schemaVersion}，仅在缺失时补 id/version 便于识别）
	 * @param resources 需要在依赖方复现的资源（mixin 配置、AT/AW、javadoc 等）及其内容
	 * @param metadataFileName 源对象的元数据文件名；仅当源对象来自
	 *         {@link ModMetadataFabricModJson}（Forge/NeoForge 的 mods.toml、architectury.common.json 等
	 *         非 FMJ 形态）时非空，用于在依赖方重建同类视图
	 */
	public record ModData(String json, Map<String, byte[]> resources, @Nullable String metadataFileName) implements Serializable {
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

		/**
		 * 供只关心元数据与资源的调用方（含既有测试）使用的便捷构造方法.
		 */
		public ModData(String json, Map<String, byte[]> resources) {
			this(json, resources, null);
		}

		static ModData fromMod(FabricModJson mod) {
			JsonObject jsonObject = null;

			try {
				jsonObject = ((JsonObject) JSON_OBJECT.get(mod)).deepCopy();
			} catch (IllegalAccessException e) {
				// 忽略：无法读取内部 JSON 时退化为一个仅含默认字段的模组数据
				LOGGER.debug("Failed to read the raw JSON of mod {}", safeId(mod), e);
			}

			if (jsonObject == null) {
				jsonObject = new JsonObject();
			}

			// 这里刻意不补 schemaVersion：补出来的 1 会让依赖方把非 FMJ 的模组（mods.toml 等）重建成
			// FabricModJsonV1，类型与语义都变（getVersion() 从 -1 变成 1，getCustom()/mixin 列表改从
			// FMJ 的键里读），于是同一个 mod 经「jar 元数据」和「DTO」两条路进来时身份不一致，
			// 无法去重、javadoc/AT/AW 处理器会重复套用。缺失的 schemaVersion 本来就是 V0 的合法形态。
			addStringIfAbsent(jsonObject, "id", safeId(mod));
			addStringIfAbsent(jsonObject, "version", safeVersion(mod));

			// 非 FMJ 形态无法靠 JSON 复原，必须记下原始元数据文件，依赖方才能重建同类视图。
			final String metadataFileName = mod instanceof ModMetadataFabricModJson metadataBacked
					? metadataBacked.getModMetadata().getFileName()
					: null;
			Set<String> resourcePaths = new LinkedHashSet<>();
			resourcePaths.addAll(mod.getMixinConfigurations());
			resourcePaths.addAll(mod.getClassTweakers().keySet());
			JsonElement javadoc = mod.getCustom(Constants.CustomModJsonKeys.PROVIDED_JAVADOC);

			if (javadoc != null && javadoc.isJsonPrimitive()) {
				resourcePaths.add(javadoc.getAsString());
			}

			if (metadataFileName != null) {
				resourcePaths.add(metadataFileName);
			}

			Map<String, byte[]> resources = new LinkedHashMap<>();

			for (String resourcePath : resourcePaths) {
				try {
					resources.put(resourcePath, mod.getSource().read(resourcePath));
				} catch (IOException e) {
					// 忽略：缺失的可选资源不参与跨项目数据交换
					LOGGER.debug("Optional resource {} of mod {} is missing from the project data", resourcePath, safeId(mod), e);
				}
			}

			return new ModData(LoomGradlePlugin.GSON.toJson(jsonObject), resources, metadataFileName);
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

			if (metadataFileName != null) {
				// 非 FMJ 形态：按原始元数据文件重建同类视图，才能保持 getId()/getCustom()/mixin 列表语义，
				// 也才能与依赖方从 jar 元数据读到的同名模组保持一致的 getVersion()（两者都用于 hashCode）。
				final byte[] metadataBytes = copiedResources.get(metadataFileName);

				if (metadataBytes != null) {
					final ModMetadataFile metadataFile = ModMetadataFiles.fromBytes(metadataFileName, metadataBytes);

					if (metadataFile != null) {
						return FabricModJsonFactory.createFromModMetadata(metadataFile, source);
					}
				}

				// 退化为 JSON 视图会丢掉元数据里的 mixin/AT 等信息，属于降级，必须默认可见。
				LOGGER.warn("Cannot restore mod metadata {} from Loom project data, falling back to the raw JSON view", metadataFileName);
			}

			return FabricModJsonFactory.create(jsonObject, source);
		}
	}
}
