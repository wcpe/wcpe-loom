/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2025 FabricMC
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

package net.fabricmc.loom.extension;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.inject.Inject;

import dev.architectury.loom.forge.dependency.DependencyProviders;
import dev.architectury.loom.forge.dependency.ForgeRunsProvider;
import org.gradle.api.Project;
import org.gradle.api.configuration.BuildFeatures;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomNoRemapGradlePlugin;
import net.fabricmc.loom.api.ForgeExtensionAPI;
import net.fabricmc.loom.api.NeoForgeExtensionAPI;
import net.fabricmc.loom.api.mappings.intermediate.IntermediateMappingsProvider;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.InstallerData;
import net.fabricmc.loom.configuration.LoomDependencyManager;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.configuration.providers.mappings.IntermediaryMappingsProvider;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.library.LibraryProcessorManager;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MojangMappedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.SrgMinecraftProvider;
import net.fabricmc.loom.internal.LoomGradleSharedData;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Lazy;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.gradle.GradleUtils;

public abstract class LoomGradleExtensionImpl extends LoomGradleExtensionApiImpl implements LoomGradleExtension {
	private final Project project;
	private final MixinExtension mixinApExtension;
	private final LoomFiles loomFiles;
	private final ConfigurableFileCollection unmappedMods;

	/** 各命名空间下由任务产出的 MC jar 集合；接线前为空，{@code getMinecraftJarsCollection} 会回退到配置期落盘的文件. */
	private final Map<MappingsNamespace, FileCollection> minecraftJarTaskOutputs = new EnumMap<>(MappingsNamespace.class);

	/**
	 * 每个命名空间「同一份」可配置文件集合：消费方取走的是它，产出方登记时也把任务产出并进它.
	 *
	 * <p>必须是同一份实例：消费方（{@code ValidateAccessWidenerTask}、{@code TinyRemapperService} 等）
	 * 通常在**自己的任务注册期**就调用 {@code getMinecraftJarsCollection}，而产出方的登记发生在更晚的
	 * {@code afterEvaluate}。若每次返回新实例，消费方手里那份永远是「尚未登记」的快照——里面是裸文件、
	 * 不携带任何任务依赖，接线会静默失效（表现为冷缓存下消费方读不到还不存在的 jar）。
	 */
	private final Map<MappingsNamespace, ConfigurableFileCollection> minecraftJarCollections = new EnumMap<>(MappingsNamespace.class);

	private final List<AccessWidenerFile> transitiveAccessWideners = new ArrayList<>();

	private LoomDependencyManager dependencyManager;
	private MinecraftMetadataProvider metadataProvider;
	private MinecraftProvider minecraftProvider;
	private MappingConfiguration mappingConfiguration;
	private NamedMinecraftProvider<?> namedMinecraftProvider;
	private IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider;
	private SrgMinecraftProvider<?> srgMinecraftProvider;
	private MojangMappedMinecraftProvider<?> mojangMappedMinecraftProvider;
	private InstallerData installerData;
	private boolean refreshDeps;
	private final ListProperty<LibraryProcessorManager.LibraryProcessorFactory> libraryProcessorFactories;
	private final boolean configurationCacheActive;
	private final boolean isolatedProjectsActive;
	private final boolean isCollectingDependencyVerificationMetadata;
	private final Property<Boolean> disableObfuscation;
	private final Property<Boolean> dontRemap;

	// +-------------------+
	// | Architectury Loom |
	// +-------------------+
	private DependencyProviders dependencyProviders;
	private ForgeRunsProvider forgeRunsProvider;
	private final Supplier<ForgeExtensionAPI> forgeExtension;
	private final Supplier<NeoForgeExtensionAPI> neoForgeExtension;

	@Inject
	protected abstract BuildFeatures getBuildFeatures();

	@Inject
	public LoomGradleExtensionImpl(Project project, LoomFiles files) {
		super(project, files);
		LoomGradleSharedData.get(project);
		this.project = project;
		// Initiate with newInstance to allow gradle to decorate our extension
		this.mixinApExtension = project.getObjects().newInstance(MixinExtensionImpl.class, project);
		this.loomFiles = files;
		this.unmappedMods = project.files();
		this.forgeExtension = Lazy.of(() -> isForge() ? project.getObjects().newInstance(ForgeExtensionImpl.class, project, this) : null);
		this.neoForgeExtension = Lazy.of(() -> isNeoForge() ? project.getObjects().newInstance(NeoForgeExtensionImpl.class, project) : null);

		// Setup the default intermediate mappings provider.
		setIntermediateMappingsProvider(IntermediaryMappingsProvider.class, provider -> {
			provider.getIntermediaryUrl()
					.convention(getIntermediaryUrl())
					.finalizeValueOnRead();

			provider.getRefreshDeps().set(project.provider(() -> LoomGradleExtension.get(project).refreshDeps()));
		});

		refreshDeps = manualRefreshDeps();
		libraryProcessorFactories = project.getObjects().listProperty(LibraryProcessorManager.LibraryProcessorFactory.class);
		libraryProcessorFactories.addAll(LibraryProcessorManager.DEFAULT_LIBRARY_PROCESSORS);
		libraryProcessorFactories.finalizeValueOnRead();

		configurationCacheActive = getBuildFeatures().getConfigurationCache().getActive().get();
		isolatedProjectsActive = getBuildFeatures().getIsolatedProjects().getActive().get();
		isCollectingDependencyVerificationMetadata = !project.getGradle().getStartParameter().getWriteDependencyVerifications().isEmpty();
		disableObfuscation = project.getObjects().property(Boolean.class);
		dontRemap = project.getObjects().property(Boolean.class);

		if (LoomNoRemapGradlePlugin.isApplied(project)) {
			disableObfuscation.set(true);
			disableObfuscation.finalizeValue();
		} else {
			disableObfuscation.set(project.provider(() -> GradleUtils.getBooleanProperty(getProject(), Constants.Properties.DISABLE_OBFUSCATION)));
			disableObfuscation.finalizeValueOnRead();
		}

		dontRemap.set(disableObfuscation.map(notObfuscated -> notObfuscated || GradleUtils.getBooleanProperty(getProject(), Constants.Properties.DONT_REMAP)));
		dontRemap.finalizeValueOnRead();

		if (refreshDeps) {
			project.getLogger().lifecycle("Refresh dependencies is in use, loom will be significantly slower.");
		}

		if (isolatedProjectsActive) {
			project.getLogger().lifecycle("Isolated projects is enabled, Loom support is highly experimental, not all features will be enabled.");
		}
	}

	@Override
	protected Project getProject() {
		return project;
	}

	@Override
	public LoomFiles getFiles() {
		return loomFiles;
	}

	@Override
	public MinecraftMetadataProvider getMetadataProvider() {
		return Objects.requireNonNull(metadataProvider, "Cannot get MinecraftMetadataProvider before it has been setup");
	}

	@Override
	public void setMetadataProvider(MinecraftMetadataProvider metadataProvider) {
		this.metadataProvider = metadataProvider;
	}

	@Override
	public MinecraftProvider getMinecraftProvider() {
		return Objects.requireNonNull(minecraftProvider, "Cannot get MinecraftProvider before it has been setup");
	}

	@Override
	public void setMinecraftProvider(MinecraftProvider minecraftProvider) {
		this.minecraftProvider = minecraftProvider;
	}

	@Override
	public MappingConfiguration getMappingConfiguration() {
		if (disableObfuscation()) {
			project.getLogger().lifecycle("help", new RuntimeException());
			throw new UnsupportedOperationException("Cannot get mappings configuration in a non-obfuscated environment");
		}

		return Objects.requireNonNull(mappingConfiguration, "Cannot get MappingsProvider before it has been setup");
	}

	@Override
	public @Nullable MappingConfiguration findMappingConfiguration() {
		return mappingConfiguration;
	}

	@Override
	public void setMappingConfiguration(MappingConfiguration mappingConfiguration) {
		if (disableObfuscation()) {
			throw new UnsupportedOperationException("Cannot set mappings configuration in a non-obfuscated environment");
		}

		this.mappingConfiguration = mappingConfiguration;
	}

	@Override
	public NamedMinecraftProvider<?> getNamedMinecraftProvider() {
		return Objects.requireNonNull(namedMinecraftProvider, "Cannot get NamedMinecraftProvider before it has been setup");
	}

	@Override
	public IntermediaryMinecraftProvider<?> getIntermediaryMinecraftProvider() {
		return Objects.requireNonNull(intermediaryMinecraftProvider, "Cannot get IntermediaryMinecraftProvider before it has been setup");
	}

	@Override
	public void setNamedMinecraftProvider(NamedMinecraftProvider<?> namedMinecraftProvider) {
		this.namedMinecraftProvider = namedMinecraftProvider;
	}

	@Override
	public void setIntermediaryMinecraftProvider(IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider) {
		this.intermediaryMinecraftProvider = intermediaryMinecraftProvider;
	}

	@Override
	public void noIntermediateMappings() {
		getUseIntermediateMappings().set(false);
	}

	@Override
	public SrgMinecraftProvider<?> getSrgMinecraftProvider() {
		return Objects.requireNonNull(srgMinecraftProvider, "Cannot get SrgMinecraftProvider before it has been setup");
	}

	@Override
	public void setSrgMinecraftProvider(SrgMinecraftProvider<?> srgMinecraftProvider) {
		this.srgMinecraftProvider = srgMinecraftProvider;
	}

	@Override
	public MojangMappedMinecraftProvider<?> getMojangMappedMinecraftProvider() {
		return Objects.requireNonNull(mojangMappedMinecraftProvider, "Cannot get MojangMappedMinecraftProvider before it has been setup");
	}

	@Override
	public void setMojangMappedMinecraftProvider(MojangMappedMinecraftProvider<?> mojangMappedMinecraftProvider) {
		this.mojangMappedMinecraftProvider = mojangMappedMinecraftProvider;
	}

	@Override
	public FileCollection getMinecraftJarsCollection(MappingsNamespace mappingsNamespace) {
		return minecraftJarsCollection(mappingsNamespace);
	}

	/** {@return 该命名空间的集合实例} 见 {@link #minecraftJarCollections} 对「必须是同一份实例」的说明. */
	private ConfigurableFileCollection minecraftJarsCollection(MappingsNamespace mappingsNamespace) {
		// 同一命名空间只建一次：消费方在登记之前取走的引用，会在登记时被并进任务产出（见 setMinecraftJarsTaskOutputs）
		return minecraftJarCollections.computeIfAbsent(mappingsNamespace, namespace -> {
			final ConfigurableFileCollection collection = getProject().getObjects().fileCollection();

			// 未登记时的回退：配置期已经落盘的文件。外层 Provider 只做延迟解析，内层是裸 File 列表，
			// 不携带任何任务依赖——消费侧看不到「谁产出这些 jar」，也就不会在消费前先跑产出任务。
			// 登记之后本项会被 setFrom 整体替换掉，因此「未登记」与「已登记」不会同时生效。
			collection.from(getProject().provider(() ->
					getMinecraftJars(namespace).stream().map(Path::toFile).toList()
			));

			final FileCollection registered = minecraftJarTaskOutputs.get(namespace);

			if (registered != null) {
				// 本次调用发生在登记之后：直接把任务产出放进来
				collection.setFrom(registered);
			}

			return collection;
		});
	}

	@Override
	public void setMinecraftJarsTaskOutputs(MappingsNamespace mappingsNamespace, FileCollection taskOutputs) {
		Objects.requireNonNull(taskOutputs, "taskOutputs");
		minecraftJarTaskOutputs.put(mappingsNamespace, taskOutputs);
		// setFrom 而不是新建实例：消费方可能已经取走过集合引用，换实例会把任务依赖留在旧实例上。
		// setFrom 也顺带满足「同一命名空间重复登记以最后一次为准」——后一次登记（例如 processed provider
		// 覆盖未处理的 provider）整体替换掉前一次的内容，而不是把两份 jar 都留在集合里。
		// 任务依赖由入参自身携带（它派生自 TaskProvider），setFrom 会把它一并纳入。
		minecraftJarsCollection(mappingsNamespace).setFrom(taskOutputs);
	}

	@Override
	public ConfigurableFileCollection getUnmappedModCollection() {
		return unmappedMods;
	}

	public void setInstallerData(InstallerData object) {
		this.installerData = object;
	}

	@Override
	public InstallerData getInstallerData() {
		return installerData;
	}

	@Override
	public MixinExtension getMixin() {
		return this.mixinApExtension;
	}

	@Override
	public List<AccessWidenerFile> getTransitiveAccessWideners() {
		return transitiveAccessWideners;
	}

	@Override
	public void addTransitiveAccessWideners(List<AccessWidenerFile> accessWidenerFiles) {
		transitiveAccessWideners.addAll(accessWidenerFiles);
	}

	@Override
	public DownloadBuilder download(String url) {
		DownloadBuilder builder;

		try {
			builder = Download.create(url);
		} catch (URISyntaxException e) {
			throw new RuntimeException("Failed to create downloader for: " + e);
		}

		if (project.getGradle().getStartParameter().isOffline()) {
			builder.offline();
		}

		if (manualRefreshDeps()) {
			builder.forceDownload();
		}

		return builder;
	}

	@Override
	public boolean manualRefreshDeps() {
		return project.getGradle().getStartParameter().isRefreshDependencies() || Boolean.getBoolean("loom.refresh");
	}

	@Override
	public boolean refreshDeps() {
		return refreshDeps;
	}

	@Override
	public void setRefreshDeps(boolean refreshDeps) {
		this.refreshDeps = refreshDeps;
	}

	@Override
	public ListProperty<LibraryProcessorManager.LibraryProcessorFactory> getLibraryProcessors() {
		return libraryProcessorFactories;
	}

	@Override
	public ListProperty<RemapperExtensionHolder> getRemapperExtensions() {
		return remapperExtensions;
	}

	@Override
	public Collection<LayeredMappingsFactory> getLayeredMappingFactories() {
		if (disableObfuscation()) {
			throw new UnsupportedOperationException("Cannot get layered mapping factories in a non-obfuscated environment");
		}

		hasEvaluatedLayeredMappings = true;
		return Collections.unmodifiableCollection(layeredMappingsDependencyMap.values());
	}

	@Override
	protected <T extends IntermediateMappingsProvider> void configureIntermediateMappingsProviderInternal(T provider) {
		provider.getMinecraftVersion().set(getProject().provider(() -> getMinecraftProvider().minecraftVersion()));
		provider.getMinecraftVersion().disallowChanges();

		provider.getDownloader().set(this::download);
		provider.getDownloader().disallowChanges();

		provider.getUseSplitOfficialNamespaces().set(getProject().provider(() -> getMinecraftProvider().isLegacySplitOfficialNamespaceVersion()));
		provider.getUseSplitOfficialNamespaces().disallowChanges();
	}

	@Override
	public boolean isConfigurationCacheActive() {
		return configurationCacheActive;
	}

	@Override
	public boolean isProjectIsolationActive() {
		return isolatedProjectsActive;
	}

	@Override
	public boolean isCollectingDependencyVerificationMetadata() {
		return isCollectingDependencyVerificationMetadata;
	}

	@Override
	public boolean dontRemapOutputs() {
		return dontRemap.get();
	}

	@Override
	public boolean disableObfuscation() {
		return disableObfuscation.get();
	}

	@Override
	public ForgeExtensionAPI getForge() {
		ModPlatform.assertPlatform(this, ModPlatform.FORGE);
		return forgeExtension.get();
	}

	@Override
	public NeoForgeExtensionAPI getNeoForge() {
		ModPlatform.assertPlatform(this, ModPlatform.NEOFORGE);
		return neoForgeExtension.get();
	}

	@Override
	public DependencyProviders getDependencyProviders() {
		return dependencyProviders;
	}

	@Override
	public void setDependencyProviders(DependencyProviders dependencyProviders) {
		this.dependencyProviders = dependencyProviders;
	}

	@Override
	public ForgeRunsProvider getForgeRunsProvider() {
		ModPlatform.assertForgeLike(this);
		return forgeRunsProvider;
	}

	@Override
	public void setForgeRunsProvider(ForgeRunsProvider forgeRunsProvider) {
		ModPlatform.assertForgeLike(this);
		this.forgeRunsProvider = forgeRunsProvider;
	}

	@Override
	public Provider<MappingsNamespace> getProductionNamespaceEnum() {
		return getProductionNamespace().map(s -> Objects.requireNonNull(MappingsNamespace.of(s), "Invalid production namespace"));
	}

	@Override
	public Provider<ArtifactMetadata.MixinRemapType> getDefaultMixinRemapTypeEnum() {
		return getDefaultMixinRemapType().map(ArtifactMetadata.MixinRemapType::valueOf);
	}
}
