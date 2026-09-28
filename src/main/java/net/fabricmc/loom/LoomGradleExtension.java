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
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.ApiStatus;

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

	void setMappingConfiguration(MappingConfiguration mappingConfiguration);

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

	FileCollection getMinecraftJarsCollection(MappingsNamespace mappingsNamespace);

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
