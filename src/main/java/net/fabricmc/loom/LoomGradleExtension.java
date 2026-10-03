/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

package net.fabricmc.loom;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import dev.architectury.loom.forge.dependency.DependencyProviders;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.dependency.ForgeRunsProvider;
import dev.architectury.loom.forge.dependency.ForgeUniversalProvider;
import dev.architectury.loom.forge.dependency.ForgeUserdevProvider;
import dev.architectury.loom.forge.dependency.PatchProvider;
import dev.architectury.loom.forge.dependency.SrgProvider;
import dev.architectury.loom.mcpconfig.McpConfigProvider;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.InstallerData;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.library.LibraryProcessorManager;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MojangMappedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.SrgMinecraftProvider;
import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.extension.RemapperExtensionHolder;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.download.DownloadBuilder;

@ApiStatus.Internal
public interface LoomGradleExtension extends LoomGradleExtensionAPI {
	/**
	 * 取项目上的 Loom 扩展.
	 *
	 * <p>判据只做 {@code instanceof} 而不直接强转：当 Loom 被两个 classloader 各加载一份时（settings 与
	 * project classpath 各一份、buildSrc/约定插件与 plugins 块各一份、复合构建），「loom」这个名字查得到，
	 * 但对象是由另一份 Loom 创建的，裸强转会抛不带任何业务信息的 ClassCastException。
	 * 跨 classloader 复用对方实例在语言层面不可能（同名类不是同一个 Class），因此退化为可诊断的失败，
	 * 明确指出占用者实际类型与其 classloader，并给出可能成因。
	 *
	 * @param project 目标项目
	 * @return 该项目上的 Loom 扩展
	 * @throws org.gradle.api.UnknownDomainObjectException 项目上没有名为「loom」的扩展（项目未应用 Loom）
	 * @throws GradleException 扩展存在但由另一份 classloader 的 Loom 创建
	 */
	static LoomGradleExtension get(Project project) {
		final Object extension = project.getExtensions().getByName("loom");

		if (extension instanceof LoomGradleExtension loomExtension) {
			return loomExtension;
		}

		throw new GradleException(String.format(
				"项目 %s 上的「loom」扩展由另一份 classloader 的 Loom 创建：扩展实际类型为 %s（由 classloader %s 加载），"
						+ "当前 Loom 期望 %s。跨 classloader 无法复用对方实例，常见成因是 Loom 被两个 classloader 各加载了一份"
						+ "（settings 与项目 classpath 各一份、buildSrc/约定插件与 plugins 块各一份、复合构建），"
						+ "请把 Loom 统一到单一来源，或改用不遍历其它项目的隔离模式路径。",
				project.getPath(), extension.getClass().getName(), extension.getClass().getClassLoader(),
				LoomGradleExtension.class.getName()));
	}

	LoomFiles getFiles();

	ConfigurableFileCollection getUnmappedModCollection();

	void setInstallerData(InstallerData data);

	InstallerData getInstallerData();

	MinecraftMetadataProvider getMetadataProvider();

	void setMetadataProvider(MinecraftMetadataProvider metadataProvider);

	MinecraftProvider getMinecraftProvider();

	void setMinecraftProvider(MinecraftProvider minecraftProvider);

	MappingConfiguration getMappingConfiguration();

	/**
	 * {@return 已建立的映射配置；本工程不使用映射（{@code disableObfuscation} / unobfuscated Forge）
	 * 或尚未 setup 时为 {@code null}}.
	 *
	 * <p>与 {@link #getMappingConfiguration()} 的差别只有一个：不抛异常。任务侧需要「有就接线、没有就跳过」
	 * 的语义（见 {@link #addPlatformMappingsDependency(Task)}），在那里抛异常会把一个可选的接线点变成崩溃点。
	 */
	@Nullable
	MappingConfiguration findMappingConfiguration();

	void setMappingConfiguration(MappingConfiguration mappingConfiguration);

	/**
	 * 把「本任务以平台映射文件为输入」表达成任务依赖.
	 *
	 * <p>平台映射文件（Forge 下是迁移产物 {@code mappings-srg-migrated.tiny} / {@code mappings-mojang-migrated.tiny}）
	 * 在投影后只在执行期落位。它的消费者散布在各个任务里（{@code remapJar}、{@code remapSourcesJar}、
	 * {@code genSources}、{@code genForgePatchedSources}、{@code generateDLIConfig}……），
	 * 而它作为 {@code @InputFile} 只按路径声明、不带任务依赖，因此每一处都必须显式接线：
	 * 漏接一处 = 任务在产物落位前开跑，随后以「输入文件不存在」失败。
	 *
	 * <p>映射由配置期产出、或本工程根本不使用映射时，本方法什么也不做。
	 */
	default void addPlatformMappingsDependency(Task task) {
		// 依赖边必须**惰性**求值，不能用「构造期立刻查询映射配置」的写法：
		// remapJar 由 RemapTaskConfiguration 在插件 apply 期就 eager 创建，那时 mappings 阶段还没跑
		// （映射配置为 null），立刻查询会静默地接不上。Gradle 在任务图计算时才解析 dependsOn 的 Provider，
		// 那时映射配置必定已建立。
		task.dependsOn(task.getProject().provider(() -> {
			final MappingConfiguration mappings = findMappingConfiguration();

			if (mappings == null) {
				return List.of();
			}

			final String taskPath = mappings.mappingsProducerTaskPath();
			return taskPath == null ? List.of() : List.of(taskPath);
		}));
	}

	NamedMinecraftProvider<?> getNamedMinecraftProvider();

	IntermediaryMinecraftProvider<?> getIntermediaryMinecraftProvider();

	void setNamedMinecraftProvider(NamedMinecraftProvider<?> namedMinecraftProvider);

	void setIntermediaryMinecraftProvider(IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider);

	Provider<MappingsNamespace> getProductionNamespaceEnum();

	Provider<ArtifactMetadata.MixinRemapType> getDefaultMixinRemapTypeEnum();

	SrgMinecraftProvider<?> getSrgMinecraftProvider();

	void setSrgMinecraftProvider(SrgMinecraftProvider<?> srgMinecraftProvider);

	MojangMappedMinecraftProvider<?> getMojangMappedMinecraftProvider();

	void setMojangMappedMinecraftProvider(MojangMappedMinecraftProvider<?> srgMinecraftProvider);

	default List<Path> getMinecraftJars(MappingsNamespace mappingsNamespace) {
		return switch (mappingsNamespace) {
		case NAMED -> getNamedMinecraftProvider().getMinecraftJarPaths();
		case INTERMEDIARY -> getIntermediaryMinecraftProvider().getMinecraftJarPaths();
		case OFFICIAL, CLIENT_OFFICIAL, SERVER_OFFICIAL -> getMinecraftProvider().getMinecraftJars();
		case SRG -> {
			ModPlatform.assertPlatform(this, ModPlatform.FORGE, () -> "SRG jars are only available on Forge.");
			yield getSrgMinecraftProvider().getMinecraftJarPaths();
		}
		case MOJANG -> {
			if (!this.isForgeLike() || !this.getForgeProvider().usesMojangAtRuntime()) {
				throw new GradleException("Mojang-mapped jars are only available on NeoForge / Forge 50+.");
			}

			yield getMojangMappedMinecraftProvider().getMinecraftJarPaths();
		}
		};
	}

	/**
	 * 取某个命名空间下的 Minecraft jar 集合.
	 *
	 * <p>已登记任务产出（见 {@link #setMinecraftJarsTaskOutputs}）时，返回的集合派生自产出任务，
	 * 携带任务依赖；未登记时回退到配置期已经落盘的文件，此时集合里是裸 {@link java.io.File}，
	 * 不携带任何任务依赖。
	 *
	 * <h4>同一命名空间返回的是同一份集合实例</h4>
	 * 消费方通常在**自己的任务注册期**就调用本方法，而产出方的登记发生在更晚（{@code afterEvaluate}
	 * 里的 {@code provide}）。因此登记不是「换一份新集合」，而是把任务产出并进已发出的那一份
	 * （见 {@link #setMinecraftJarsTaskOutputs}）；否则先取走引用的消费方手里永远是未登记时的快照，
	 * 任务依赖会静默丢失，冷缓存下消费方就会读到一个还不存在的 jar。
	 *
	 * @param mappingsNamespace 目标命名空间
	 * @return 该命名空间下的 Minecraft jar 集合
	 */
	FileCollection getMinecraftJarsCollection(MappingsNamespace mappingsNamespace);

	/**
	 * 登记某个命名空间下、由任务产出的 Minecraft jar 集合.
	 *
	 * <h4>为什么必须由产出方登记</h4>
	 * 消费方（{@code ValidateAccessWidenerTask}、{@code ValidateModProvidedJavadocTask} 等）
	 * 在注册自己的任务时看不到产出 MC jar 的那个 {@code TaskProvider}，无法自行接上依赖；
	 * 产出方在注册任务之后调用本方法，是唯一能把这份依赖传播给全部消费方的位置。
	 *
	 * <h4>对入参的要求</h4>
	 * {@code taskOutputs} 必须派生自 {@code TaskProvider}，例如
	 * {@code project.files(remapTask.flatMap(t -> t.getOutputJar()))}。
	 * 携带任务依赖的是 {@code Provider} 本身，裸 {@code File}/{@code Path} 不会携带——
	 * 传入裸文件等价于没有登记。
	 *
	 * <h4>登记是「并进」而不是「替换引用」</h4>
	 * 本方法把 {@code taskOutputs} 并进 {@link #getMinecraftJarsCollection} 为该命名空间返回的
	 * 那一份集合实例（尚未取过则现建一份），因此已经持有引用的消费方同样能拿到任务依赖。
	 * 同一命名空间重复登记时内容以最后一次为准——后一次会整体替换前一次的内容，
	 * 而不是把两份 jar 都留在集合里（例如 processed provider 覆盖未处理的 provider）。
	 *
	 * @param mappingsNamespace 该集合所属的命名空间
	 * @param taskOutputs 由任务产出的 jar 集合
	 */
	void setMinecraftJarsTaskOutputs(MappingsNamespace mappingsNamespace, FileCollection taskOutputs);

	@Override
	MixinExtension getMixin();

	List<AccessWidenerFile> getTransitiveAccessWideners();

	void addTransitiveAccessWideners(List<AccessWidenerFile> accessWidenerFiles);

	DownloadBuilder download(String url);

	boolean refreshDeps();

	void setRefreshDeps(boolean refreshDeps);

	ListProperty<LibraryProcessorManager.LibraryProcessorFactory> getLibraryProcessors();

	ListProperty<RemapperExtensionHolder> getRemapperExtensions();

	Collection<LayeredMappingsFactory> getLayeredMappingFactories();

	boolean isConfigurationCacheActive();

	boolean isProjectIsolationActive();

	/**
	 * @return true when '--write-verification-metadata` is set
	 */
	boolean isCollectingDependencyVerificationMetadata();

	/**
	 * When enabled do not remap the output jars.
	 */
	boolean dontRemapOutputs();

	/**
	 * When enabled disable all forms of remapping.
	 */
	boolean disableObfuscation();

	// ===================
	//  Architectury Loom
	// ===================
	default PatchProvider getPatchProvider() {
		return getDependencyProviders().getProvider(PatchProvider.class);
	}

	default McpConfigProvider getMcpConfigProvider() {
		return getDependencyProviders().getProvider(McpConfigProvider.class);
	}

	default boolean isDataGenEnabled() {
		return isForge() && !getForge().getDataGenMods().isEmpty();
	}

	default boolean isForgeLikeAndOfficial() {
		return isForgeLike() && getMcpConfigProvider().isOfficial();
	}

	default boolean isForgeLikeAndNotOfficial() {
		return isForgeLike() && !getMcpConfigProvider().isOfficial();
	}

	default boolean isUnobfuscatedForge() {
		return isForgeLike() && getProductionNamespace().get().equals(MappingsNamespace.OFFICIAL.toString());
	}

	DependencyProviders getDependencyProviders();

	void setDependencyProviders(DependencyProviders dependencyProviders);

	default SrgProvider getSrgProvider() {
		return getDependencyProviders().getProvider(SrgProvider.class);
	}

	default ForgeUniversalProvider getForgeUniversalProvider() {
		return getDependencyProviders().getProvider(ForgeUniversalProvider.class);
	}

	default ForgeUserdevProvider getForgeUserdevProvider() {
		return getDependencyProviders().getProvider(ForgeUserdevProvider.class);
	}

	/**
	 * 是否为 legacy Forge（1.8-1.16，ForgeGradle 2 时代）.
	 *
	 * <p>判定依据是 userdev 配置的形态（FG2 与 userdev3 都算 legacy），
	 * 因此必须在 userdev 解析完成后调用；未解析时 {@link ForgeUserdevProvider#isLegacyForge()} 会抛异常。
	 */
	default boolean isLegacyForge() {
		return isForge() && getForgeUserdevProvider().isLegacyForge();
	}

	/**
	 * 是否为 Forge 系（含 NeoForge）中的现代版本，即非 legacy Forge.
	 */
	default boolean isModernForgeLike() {
		return isForgeLike() && !isLegacyForge();
	}

	default ForgeProvider getForgeProvider() {
		return getDependencyProviders().getProvider(ForgeProvider.class);
	}

	ForgeRunsProvider getForgeRunsProvider();
	void setForgeRunsProvider(ForgeRunsProvider forgeRunsProvider);

	/**
	 * The mapping file that is specific to the platform settings.
	 * It contains SRG (Forge/common) or Mojang mappings (NeoForge) as needed.
	 *
	 * @return the platform mapping file path
	 */
	default Path getPlatformMappingFile() {
		return getMappingConfiguration().getPlatformMappingFile(this);
	}

	boolean manualRefreshDeps();
}
