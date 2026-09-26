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

package dev.architectury.loom.forge.dependency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.architectury.loom.forge.ModDirTransformerDiscovererPatch;
import dev.architectury.loom.forge.RemapObjectHolderVisitor;
import dev.architectury.loom.mappings.ForgeMappingsMerger;
import dev.architectury.loom.mappings.MappingOption;
import dev.architectury.loom.neoforge.StringConstantPatcher;
import dev.architectury.loom.util.ClassVisitorUtil;
import dev.architectury.loom.util.PropertyUtil;
import dev.architectury.loom.util.Version;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingContext;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.mods.ModConfigurationRemapper;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.providers.mappings.GradleMappingContext;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class ForgeLibrariesProvider {
	private static final String FML_PATCH_VERSION = "-loom-patch-v1";

	private static final String FML_LOADER_GROUP = "net.minecraftforge";
	private static final String FML_LOADER_NAME = "fmlloader";
	private static final String FANCYML_LOADER_GROUP = "net.neoforged.fancymodloader";
	private static final String FANCYML_LOADER_NAME = "loader";
	private static final Version FANCYML_LOADER_UNPROTECT_BACKEND_VERSION = Version.parse("10.0.14");

	private static final String FORGE_OBJECT_HOLDER_FILE = "net/minecraftforge/fml/common/asm/ObjectHolderDefinalize.class";
	private static final String FORGE_MOD_DIR_TRANSFORMER_DISCOVERER_FILE = "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer.class";
	private static final String NEOFORGE_OBJECT_HOLDER_FILE = "net/neoforged/fml/common/asm/ObjectHolderDefinalize.class";
	private static final String NEOFORGE_LAUNCH_HANDLER_FILE = "net/neoforged/fml/loading/targets/CommonUserdevLaunchHandler.class";
	private static final String NEOFORGE_LOADER_FILE = "net/neoforged/fml/loading/FMLLoader.class";
	private static final String NEOFORGE_GAME_LOCATOR_FILE = "net/neoforged/fml/loading/moddiscovery/locators/GameLocator.class";
	private static final String NEOFORGE_REQUIRED_SYSTEM_FILES_FILE = "net/neoforged/fml/loading/moddiscovery/locators/RequiredSystemFiles.class";

	/**
	 * 动态版本钉死表：key 为 {@code group:name}，value 为「解析结果与之相同的固定版本」.
	 *
	 * <p>只收录「动态声明必然落选」的坐标，即 forge userdev 已用固定版本声明了同一构件，
	 * 且固定版本不低于该动态声明能解析到的任何候选。此时把动态声明换成固定值，Gradle 的
	 * 版本冲突解决输入与输出都不变，仅省掉了「列举版本列表」这一步网络往返。
	 *
	 * <p>反面例子（刻意不收录）：{@code dev.architectury:mixin-patched:0.8.5.+}。
	 * 该坐标只有动态声明、没有固定声明与之竞争，动态版本是真实生效的——仓库里同时存在
	 * 0.8.5.10 与 0.8.5.12，将来还会发布 0.8.5.13。钉死它会让构建永远停在当前版本，
	 * 属功能退化，故保持动态。
	 */
	private static final Map<String, String> DYNAMIC_FORGE_DEPENDENCY_PINS = Map.of(
			// userdev 声明 cpw.mods:modlauncher:10.0.9；eventbus 的 10.0.+ 与 coremods 的 9.0.+
			// 均不可能超过 10.0.9（9.x 更低、10.0.+ 的候选上限即 10.0.x），故替换后结果不变
			"cpw.mods:modlauncher", "10.0.9",
			// userdev 声明 net.jodah:typetools:0.6.3；forgespi 的 0.6.+ 候选上限即 0.6.3
			"net.jodah:typetools", "0.6.3"
	);

	public static void provide(@Nullable MappingConfiguration mappingConfiguration, Project project) throws Exception {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		final List<Dependency> dependencies = new ArrayList<>();

		// Collect all dependencies with possible relocations, such as Mixin.
		for (String lib : extension.getForgeUserdevProvider().getConfig().libraries()) {
			String dep = null;

			if (lib.startsWith("org.spongepowered:mixin:")) {
				// Don't apply custom mixin on NeoForge.
				if (extension.isForge() && PropertyUtil.getAndFinalize(extension.getForge().getUseCustomMixin())) {
					if (lib.contains("0.8.2")) {
						dep = "net.fabricmc:sponge-mixin:0.8.2+build.24";
					} else {
						String version = lib.substring(lib.lastIndexOf(":"));
						// Used for the file extension, for example @jar
						int atIndex = version.indexOf('@');

						if (atIndex >= 0) {
							// Strip the file extension away
							version = version.substring(0, atIndex);
						}

						dep = "dev.architectury:mixin-patched" + version + ".+";
					}
				}
			}

			if (lib.startsWith("net.minecraftforge:bootstrap:")) {
				if (extension.isForge() && extension.getForgeProvider().getVersion().getMajorVersion() >= Constants.Forge.MIN_BOOTSTRAP_DEV_VERSION) {
					String version = lib.substring(lib.lastIndexOf(":"));
					dependencies.add(project.getDependencies().create("net.minecraftforge:bootstrap-dev" + version));
				}
			}

			if (dep == null) {
				dep = lib;
			}

			dependencies.add(project.getDependencies().create(dep));
		}

		// Resolve all files. We just add the dependencies manually unless it's FML.
		// We're transforming the files manually instead of using Gradle's mechanism because
		// we can target the individual files to be transformed instead of creating new copies of all the libraries.
		final Configuration detached = project.getConfigurations()
				.detachedConfiguration(dependencies.toArray(new Dependency[0]));
		pinDynamicForgeLibraryVersions(detached);
		final ResolvedConfiguration config = detached.getResolvedConfiguration();

		boolean isFancyModLoader10OrNewer = false;

		for (ResolvedArtifact artifact : config.getResolvedArtifacts()) {
			final ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
			final Object dep;
			final boolean isFML = FML_LOADER_GROUP.equals(id.getGroup()) && FML_LOADER_NAME.equals(id.getName());
			final boolean isFancyML = FANCYML_LOADER_GROUP.equals(id.getGroup()) && FANCYML_LOADER_NAME.equals(id.getName());

			if (isFancyML && extension.isNeoForge() && Version.parse(id.getVersion()).compareTo(FANCYML_LOADER_UNPROTECT_BACKEND_VERSION) >= 0) {
				// Note: check extension.isNeoForge() to prevent this check triggering on legacy "47.x" versions of FML
				// from before Neo replaced the versioning scheme.
				isFancyModLoader10OrNewer = true;
			}

			if ((isFML || isFancyML) && mappingConfiguration != null) {
				// If FML, remap it.
				try (var serviceFactory = new ScopedServiceFactory()) {
					if (isFML) {
						project.getLogger().info(":remapping FML loader");
					} else if (isFancyML) {
						project.getLogger().info(":remapping FancyML loader");
					}

					dep = remapFmlLoader(project, serviceFactory, artifact, mappingConfiguration);
				} catch (IOException e) {
					throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Could not remap FML", e);
				}
			} else if (isFML) {
				try {
					project.getLogger().info(":remapping FML loader (non-obfuscated)");
					// non-obfuscated FML still need to be transformed to fix UnionFS related crash in dev
					dep = transformFmlLoader(project, artifact);
				} catch (IOException e) {
					throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Could not remap FML", e);
				}
			} else {
				dep = project.getDependencies().create(getDependencyNotation(artifact));

				if (dep instanceof ModuleDependency md) {
					// We've already resolved the transitive deps, and we don't want both a transformed one
					// and an untransformed one on the classpath.
					md.setTransitive(false);
				}
			}

			DependencyProvider.addDependency(project, dep, Constants.Configurations.FORGE_DEPENDENCIES);
		}

		// Excluded on legacy forge because it pulls in a log4j-api version newer than what forge wants and we don't
		// need it anyway
		if (extension.isModernForgeLike() && !extension.disableObfuscation()) {
			LoomVersions unprotect = isFancyModLoader10OrNewer ? LoomVersions.UNPROTECT_FANCYMODLOADER10 : LoomVersions.UNPROTECT_MODLAUNCHER;
			DependencyProvider.addDependency(project, unprotect.mavenNotation(), Constants.Configurations.FORGE_EXTRA);
		}
	}

	// Returns a Gradle dependency notation.
	private static Object remapFmlLoader(Project project, ServiceFactory serviceFactory, ResolvedArtifact artifact, MappingConfiguration mappingConfiguration) throws IOException {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		// A hash of the current mapping configuration. The transformations only need to be done once per mapping set.
		// While the mappings ID is definitely valid in file names, splitting MC versions parts into nested directories
		// isn't good.
		final String mappingHash = Checksum.of(mappingConfiguration.mappingsIdentifier() + FML_PATCH_VERSION).sha256().hex();

		// Resolve the inputs and outputs.
		final ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
		final LocalMavenHelper mavenHelper = new LocalMavenHelper(
				id.getGroup() + "." + mappingHash,
				id.getName(),
				id.getVersion(),
				artifact.getClassifier(),
				extension.getFiles().getForgeDependencyRepo().toPath()
		);
		final Path inputJar = artifact.getFile().toPath();
		final Path outputJar = mavenHelper.getOutputFile(null);

		final TinyMappingsService mappingsService = mappingConfiguration.getMappingsService(project, serviceFactory, MappingOption.DEFAULT);
		final MappingTree mappings = mappingsService.getMappingTree();

		// Modify jar.
		if (!Files.exists(outputJar) || extension.refreshDeps()) {
			mavenHelper.copyToMaven(inputJar, null);

			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(outputJar, false)) {
				Path path = fs.get().getPath("META-INF/services/cpw.mods.modlauncher.api.INameMappingService");
				Files.deleteIfExists(path);

				if (Files.exists(fs.get().getPath(FORGE_OBJECT_HOLDER_FILE))) {
					remapObjectHolder(project, outputJar, mappingConfiguration);
				}

				if (Files.exists(fs.getPath(FORGE_MOD_DIR_TRANSFORMER_DISCOVERER_FILE))) {
					ClassVisitorUtil.rewriteClassFile(fs.getPath(FORGE_MOD_DIR_TRANSFORMER_DISCOVERER_FILE), true, ModDirTransformerDiscovererPatch::new);
				}

				if (Files.exists(fs.getPath(NEOFORGE_OBJECT_HOLDER_FILE))) {
					remapNeoForgeObjectHolder(project, outputJar, mappingConfiguration);
				}

				if (Files.exists(fs.getPath(NEOFORGE_LAUNCH_HANDLER_FILE))) {
					ClassVisitorUtil.rewriteClassFile(fs.getPath(NEOFORGE_LAUNCH_HANDLER_FILE), StringConstantPatcher::forUserdevLaunchHandler);
				}

				if (Files.exists(fs.getPath(NEOFORGE_LOADER_FILE))) {
					ClassVisitorUtil.rewriteClassFile(fs.getPath(NEOFORGE_LOADER_FILE), next -> StringConstantPatcher.forFmlLoader(next, mappings));
				}

				if (Files.exists(fs.getPath(NEOFORGE_GAME_LOCATOR_FILE))) {
					ClassVisitorUtil.rewriteClassFile(fs.getPath(NEOFORGE_GAME_LOCATOR_FILE), next -> StringConstantPatcher.forGameLocator(next, mappings));
				}

				if (Files.exists(fs.getPath(NEOFORGE_REQUIRED_SYSTEM_FILES_FILE))) {
					ClassVisitorUtil.rewriteClassFile(fs.getPath(NEOFORGE_REQUIRED_SYSTEM_FILES_FILE), next -> StringConstantPatcher.forRequiredSystemFiles(next, mappings));
				}
			}

			// Copy sources when not running under CI.
			if (!ModConfigurationRemapper.isCIBuild()) {
				final Map<ResolvedArtifact, Path> sourcesByArtifact = ModConfigurationRemapper.downloadAllSources(project, Set.of(artifact));
				final Path sourcesJar = sourcesByArtifact.get(artifact);

				if (sourcesJar != null) {
					mavenHelper.copyToMaven(sourcesJar, "sources");
				}
			}
		}

		return mavenHelper.getNotation();
	}

	// Returns a Gradle dependency notation. (unobfuscated versions)
	private static Object transformFmlLoader(Project project, ResolvedArtifact artifact) throws IOException {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		// use the same prefix, since there is no mapping involved
		final String postfix = "transformed" + FML_PATCH_VERSION;

		// Resolve the inputs and outputs.
		final ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
		final LocalMavenHelper mavenHelper = new LocalMavenHelper(
				id.getGroup() + "." + postfix,
				id.getName(),
				id.getVersion(),
				artifact.getClassifier(),
				extension.getFiles().getForgeDependencyRepo().toPath()
		);
		final Path inputJar = artifact.getFile().toPath();
		final Path outputJar = mavenHelper.getOutputFile(null);

		// Modify jar.
		if (!Files.exists(outputJar) || extension.refreshDeps()) {
			mavenHelper.copyToMaven(inputJar, null);

			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(outputJar, false)) {
				if (Files.exists(fs.getPath(FORGE_MOD_DIR_TRANSFORMER_DISCOVERER_FILE))) {
					ClassVisitorUtil.rewriteClassFile(fs.getPath(FORGE_MOD_DIR_TRANSFORMER_DISCOVERER_FILE), true, ModDirTransformerDiscovererPatch::new);
				}
			}
		}

		return mavenHelper.getNotation();
	}

	private static void remapObjectHolder(Project project, Path outputJar, MappingConfiguration mappingConfiguration) throws IOException {
		try {
			// Merge SRG mappings. The real SRG mapping file hasn't been created yet since the usual SRG merging
			// process occurs after all Forge libraries have been provided.
			// Forge libs are needed for MC, which is needed for the mappings.
			final ForgeMappingsMerger.ExtraMappings extraMappings = ForgeMappingsMerger.ExtraMappings.ofMojmapTsrg(MappingConfiguration.getMojmapSrgFileIfPossible(project));
			final MemoryMappingTree mappings = ForgeMappingsMerger.mergeSrg(MappingConfiguration.getRawSrgFile(project), mappingConfiguration.tinyMappings, extraMappings, true);

			// Remap the object holders.
			RemapObjectHolderVisitor.remapObjectHolder(
					outputJar, "net.minecraftforge.fml.common.asm.ObjectHolderDefinalize", mappings,
					MappingsNamespace.SRG.toString(), MappingsNamespace.NAMED.toString()
			);
		} catch (IOException e) {
			throw new IOException("Could not remap object holders in " + outputJar, e);
		}
	}

	private static void remapNeoForgeObjectHolder(Project project, Path outputJar, MappingConfiguration mappingConfiguration) throws IOException {
		try {
			// Merge Mojang mappings. The real Mojang mapping file hasn't been created yet since the usual Mojang merging
			// process occurs after all Forge libraries have been provided.
			// Forge libs are needed for MC, which is needed for the mappings.
			final MappingContext context = new GradleMappingContext(project, "tmp-neoforge-libs");
			final MemoryMappingTree mappings = ForgeMappingsMerger.mergeMojang(context, mappingConfiguration.tinyMappings, null, true);

			// Remap the object holders.
			RemapObjectHolderVisitor.remapObjectHolder(
					outputJar, "net.neoforged.fml.common.asm.ObjectHolderDefinalize", mappings,
					MappingsNamespace.MOJANG.toString(), MappingsNamespace.NAMED.toString()
			);
		} catch (IOException e) {
			throw new IOException("Could not remap object holders in " + outputJar, e);
		}
	}

	/**
	 * 把 Forge 库传递依赖里的「必然落选的动态版本」替换为等值固定版本.
	 *
	 * <p>背景：Forge 自家 POM 用动态版本声明部分传递依赖（如 eventbus:6.0.5 声明
	 * {@code cpw.mods:modlauncher:10.0.+}、coremods:5.0.1 声明 {@code modlauncher:9.0.+}）。
	 * Gradle 解析动态版本必须对每个仓库列举版本列表，而本构建的仓库清单有 20 余个，
	 * 多数不含这些构件，每次都要串行走一遍慢 404；实测单次配置阶段因此多耗 1.5~5.4 秒。
	 *
	 * <p>这些动态声明实际从不影响结果：userdev 已用固定版本声明同一构件
	 * （modlauncher 10.0.9 / typetools 0.6.3），Gradle 的版本冲突解决总是选中较高者，
	 * 动态声明解析出的候选全部落选。换成等值固定版本可跳过版本列举，解析结果逐项不变。
	 *
	 * <p>仅处理 {@link #DYNAMIC_FORGE_DEPENDENCY_PINS} 收录的坐标；其余坐标（含真实生效的
	 * 动态版本）保持原样，不做任何隐式改动。
	 */
	private static void pinDynamicForgeLibraryVersions(Configuration configuration) {
		configuration.getResolutionStrategy().eachDependency(details -> {
			final String key = details.getRequested().getGroup() + ":" + details.getRequested().getName();
			final String pin = DYNAMIC_FORGE_DEPENDENCY_PINS.get(key);

			if (pin == null) {
				return;
			}

			final String requested = details.getRequested().getVersion();
			final boolean isDynamic = requested != null
					&& (requested.indexOf('+') >= 0 || requested.indexOf('[') >= 0 || requested.indexOf('(') >= 0);

			// 只处理动态版本；已是固定版本（含 userdev 的精确声明）保持原样，避免多余干预
			if (!isDynamic) {
				return;
			}

			details.useVersion(pin);
		});
	}

	/**
	 * Reconstructs the dependency notation of a resolved artifact.
	 * @param artifact the artifact
	 * @return the notation
	 */
	private static String getDependencyNotation(ResolvedArtifact artifact) {
		final ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
		String notation = "%s:%s:%s".formatted(id.getGroup(), id.getName(), id.getVersion());

		if (artifact.getClassifier() != null) {
			notation += ":" + artifact.getClassifier();
		}

		return notation;
	}
}
