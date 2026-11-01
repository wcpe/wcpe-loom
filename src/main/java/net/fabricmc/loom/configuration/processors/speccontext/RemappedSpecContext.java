/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

package net.fabricmc.loom.configuration.processors.speccontext;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.internal.LoomProjectData;
import net.fabricmc.loom.util.AsyncCache;
import net.fabricmc.loom.util.Lazy;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;

/**
 * 重映射形态下交给 jar processor 的 {@link SpecContext} 实现.
 *
 * <h2>为什么 {@code modDependencies} 是惰性的</h2>
 * 它是本上下文里**唯一**需要把每个 remap 配置整体 {@code resolve()} 一遍、再逐个打开 mod jar
 * 读 {@code fabric.mod.json} 才能得出的字段（见 {@link #getDependentMods}）。实测一份只声明了
 * 一个聚合件（{@code fabric-api}）的消费方工程，该字段在配置期要 resolve 55 个 artifact、
 * 耗时 146~3545 ms（机器负载高时更差）；而 Loom 自带的三个 processor
 * （access widener / interface injection / mod javadoc）**没有一个读它**——它们只读
 * {@link #localMods()} 与 {@link #modDependenciesCompileRuntime()}。
 *
 * <p>把它做成 {@link Lazy} 之后：没有 processor 读它就完全不解析（默认路径，收益全额落袋）；
 * 第三方 processor 读到它时结果与改造前逐位相同，只是求值点从「构建上下文时」挪到了「第一次读时」。
 * 两者都发生在配置期，因此**不改变交给 processor 的输入集合**。
 *
 * <h2>为什么不能整体推迟到执行期</h2>
 * processor 的 spec 由 {@code MinecraftJarProcessorManager.create} 在配置期用本上下文构建，
 * 而 spec 的 {@code hashCode} 决定 processed jar 的产物目录名（{@code getJarHash()}）与
 * {@code SourceMappingsService} 的 {@code @Input} 哈希。产物路径在配置期就必须确定，因此
 * 「spec 内容 → 产物路径」这条链无法挪到执行期；能做的只有减少求值的工作量，而不是挪时机。
 *
 * <h2>副作用不丢</h2>
 * {@link #getDependentMods} 会经 {@link SpecContext#getDependentProjectData} 触发依赖项目的强制求值
 * 与跨项目数据接线。惰性化后这些副作用并未丢失：同样的读取也发生在
 * {@link #getCompileRuntimeProjectDependencies}，它由**非惰性**的 {@link #getCompileRuntimeMods}
 * 无条件调用，且两者启用条件（{@link RemappedProjectView#disableProjectDependantMods()}）完全一致。
 */
public final class RemappedSpecContext implements SpecContext {
	/** 依赖的外部 mod，第一次读时才解析. */
	private final Supplier<List<FabricModJson>> modDependencies;
	private final List<FabricModJson> localMods;
	private final List<ModHolder> compileRuntimeMods;
	private final MappingsNamespace productionNamespace;

	@VisibleForTesting
	RemappedSpecContext(Supplier<List<FabricModJson>> modDependencies, List<FabricModJson> localMods,
			List<ModHolder> compileRuntimeMods, MappingsNamespace productionNamespace) {
		// 不在这里做 null 校验：本类前身是 record，record 允许 null 字段，加校验会让原本能跑通的
		// 调用方（包括单元测试里的 mock 视图）反过来抛异常。
		this.modDependencies = modDependencies;
		this.localMods = localMods;
		this.compileRuntimeMods = compileRuntimeMods;
		this.productionNamespace = productionNamespace;
	}

	public static RemappedSpecContext create(Project project) {
		return create(new RemappedProjectView.Impl(project));
	}

	@VisibleForTesting
	public static RemappedSpecContext create(RemappedProjectView projectView) {
		AsyncCache<List<FabricModJson>> fmjCache = new AsyncCache<>();
		return new RemappedSpecContext(
				// 刻意不在这里求值：它要把所有 remap 配置 resolve 一遍，而自带的 processor 都不读它
				Lazy.of(() -> {
					System.err.println("[PROBE] getDependentMods EVALUATED at " + java.util.Arrays.stream(new Throwable().getStackTrace()).skip(1).limit(2).map(e -> e.getClassName().replaceAll(".*\\.", "") + "." + e.getMethodName()).toList());
					return getDependentMods(projectView, fmjCache);
				}),
				projectView.getMods(),
				getCompileRuntimeMods(projectView, fmjCache),
				projectView.getProductionNamespace()
		);
	}

	@Override
	public List<FabricModJson> modDependencies() {
		return modDependencies.get();
	}

	@Override
	public List<FabricModJson> localMods() {
		return localMods;
	}

	@Override
	public MappingsNamespace productionNamespace() {
		return productionNamespace;
	}

	// Returns a list of mods found on both the compile and/or runtime classpaths
	private static List<FabricModJson> getDependentMods(RemappedProjectView projectView, AsyncCache<List<FabricModJson>> fmjCache) {
		var futures = new ArrayList<CompletableFuture<List<FabricModJson>>>();

		for (RemapConfigurationSettings entry : projectView.getRemapConfigurations()) {
			final Set<File> artifacts = entry.getSourceConfiguration().get().resolve();

			for (File artifact : artifacts) {
				futures.add(fmjCache.get(artifact.toPath().toAbsolutePath().toString(), () -> {
					return FabricModJsonFactory.createFromZipOptional(artifact.toPath())
							.map(List::of)
							.orElseGet(List::of);
				}));
			}
		}

		if (!projectView.disableProjectDependantMods()) {
			// Add all the dependent projects
			for (LoomProjectData dependentProject : SpecContext.getDependentProjectData(projectView).toList()) {
				futures.add(fmjCache.get(dependentProject.projectPath(), dependentProject::createMods));
			}
		}

		return SpecContext.distinctSorted(AsyncCache.joinList(futures));
	}

	// Returns a list of mods that are on both to compile and runtime classpath
	private static List<ModHolder> getCompileRuntimeMods(RemappedProjectView projectView, AsyncCache<List<FabricModJson>> fmjCache) {
		var mods = new ArrayList<>(getCompileRuntimeModsFromRemapConfigs(projectView, fmjCache));

		for (LoomProjectData dependentProject : getCompileRuntimeProjectDependencies(projectView).toList()) {
			List<FabricModJson> projectMods = fmjCache.getBlocking(dependentProject.projectPath(), dependentProject::createMods);

			for (FabricModJson mod : projectMods) {
				mods.add(new ModHolder(mod));
			}
		}

		return Collections.unmodifiableList(mods);
	}

	// Returns a list of jar mods that are found on the compile and runtime remapping configurations
	private static List<ModHolder> getCompileRuntimeModsFromRemapConfigs(RemappedProjectView projectView, AsyncCache<List<FabricModJson>> fmjCache) {
		// 同一次构建里同一个 remap 配置可能被查多次（下面的 runtime 集合在启用拆分源集时会解析两遍），
		// 而每次解析都要 copyRecursive 一份配置再 resolve。这里按 (settings, usage) 记忆化，
		// 只影响解析次数，不影响解析结果，因而不改变 processor 的输入集合。
		final Function<RemapConfigurationSettings, Stream<Path>> runtimeArtifacts =
				memoize(projectView.resolveArtifacts(ProjectView.ArtifactUsage.RUNTIME));
		final Function<RemapConfigurationSettings, Stream<Path>> compileArtifacts =
				memoize(projectView.resolveArtifacts(ProjectView.ArtifactUsage.COMPILE));

		// A set of mod ids from all remap configurations that are considered for dependency transforms.
		final Set<String> runtimeModIds = getModIds(
				runtimeArtifacts,
				fmjCache,
				projectView.getRuntimeRemapConfigurations().stream()
						.filter(settings -> settings.getApplyDependencyTransforms().get())
		);

		// A set of mod ids that are found on one or more remap configurations that target the common source set.
		// Null when split source sets are not enabled, meaning all mods are common.
		final Set<String> commonRuntimeModIds = projectView.areEnvironmentSourceSetsSplit() ? getModIds(
				runtimeArtifacts,
				fmjCache,
				projectView.getRuntimeRemapConfigurations().stream()
						.filter(settings -> settings.getSourceSet().map(sourceSet -> !sourceSet.getName().equals(MinecraftSourceSets.Split.CLIENT_ONLY_SOURCE_SET_NAME)).get())
						.filter(settings -> settings.getApplyDependencyTransforms().get()))
				: null;

		Stream<FabricModJson> compileMods = getMods(
				compileArtifacts,
				fmjCache,
				projectView.getCompileRemapConfigurations().stream()
						.filter(settings -> settings.getApplyDependencyTransforms().get()));

		return compileMods
				// Only check based on the modid, as there may be differing versions used between the compile and runtime classpath.
				// We assume that the version used at runtime will be binary compatible with the version used to compile against.
				// It's not perfect but better than silently not supplying the mod, and this could happen with regular API that you compile against anyway.
				.filter(fabricModJson -> runtimeModIds.contains(fabricModJson.getId()))
				.sorted(Comparator.comparing(FabricModJson::getId))
				.map(fabricModJson -> new ModHolder(fabricModJson, commonRuntimeModIds == null || commonRuntimeModIds.contains(fabricModJson.getId())))
				.toList();
	}

	/**
	 * 把一个「每个配置都重新算一遍」的解析函数包成「同一个配置只算一次」.
	 *
	 * <p>只去重、不改结果：同一个 {@link RemapConfigurationSettings} 的解析产物彼此完全相同
	 * （每次都是同一份 {@code copyRecursive} 配置加上同样的 usage 属性），因此复用第一次的结果
	 * 与重算一次逐位相同，processor 的输入集合不变。
	 *
	 * <p>命中的典型情形：启用拆分源集时，{@code runtimeModIds} 与 {@code commonRuntimeModIds}
	 * 两个集合都从 {@code getRuntimeRemapConfigurations()} 出发，重叠的配置原先会被各解析一遍。
	 * 键用 {@link IdentityHashMap}：{@code RemapConfigurationSettings} 是 Gradle 装饰对象，
	 * 拿它当普通 map 键会让每次查询重新计算哈希。
	 */
	private static Function<RemapConfigurationSettings, Stream<Path>> memoize(Function<RemapConfigurationSettings, Stream<Path>> resolver) {
		final Map<RemapConfigurationSettings, List<Path>> resolved = new IdentityHashMap<>();
		return settings -> resolved.computeIfAbsent(settings, s -> resolver.apply(s).map(Path::toAbsolutePath).toList())
				.stream()
				.map(Path::toAbsolutePath);
	}

	private static Stream<FabricModJson> getMods(Function<RemapConfigurationSettings, Stream<Path>> resolveArtifacts, AsyncCache<List<FabricModJson>> fmjCache, Stream<RemapConfigurationSettings> stream) {
		return stream.flatMap(resolveArtifacts)
				.map(modFromZip(fmjCache))
				.filter(Objects::nonNull);
	}

	private static Set<String> getModIds(Function<RemapConfigurationSettings, Stream<Path>> resolveArtifacts, AsyncCache<List<FabricModJson>> fmjCache, Stream<RemapConfigurationSettings> stream) {
		return getMods(resolveArtifacts, fmjCache, stream)
				.map(FabricModJson::getId)
				.collect(Collectors.toSet());
	}

	private static Function<Path, @Nullable FabricModJson> modFromZip(AsyncCache<List<FabricModJson>> fmjCache) {
		return zipPath -> {
			final List<FabricModJson> list = fmjCache.getBlocking(zipPath.toAbsolutePath().toString(), () -> {
				return FabricModJsonFactory.createFromZipOptional(zipPath)
						.map(List::of)
						.orElseGet(List::of);
			});
			return list.isEmpty() ? null : list.getFirst();
		};
	}

	// Returns a list of Loom Projects found in both the runtime and compile classpath
	private static Stream<LoomProjectData> getCompileRuntimeProjectDependencies(ProjectView projectView) {
		if (projectView.disableProjectDependantMods()) {
			return Stream.empty();
		}

		final Stream<LoomProjectData> runtimeProjects = projectView.getLoomProjectDataDependencies(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
		final List<LoomProjectData> compileProjects = projectView.getLoomProjectDataDependencies(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME).toList();

		return runtimeProjects
				.filter(compileProjects::contains); // Use the intersection of the two configurations.
	}

	@Override
	public List<FabricModJson> modDependenciesCompileRuntime() {
		return compileRuntimeMods.stream()
				.map(ModHolder::mod)
				.toList();
	}

	@Override
	public List<FabricModJson> modDependenciesCompileRuntimeClient() {
		return compileRuntimeMods.stream()
				.filter(modHolder -> !modHolder.common())
				.map(ModHolder::mod)
				.toList();
	}

	@Override
	public String toString() {
		// 刻意不碰 modDependencies：toString 出现在调试日志里，不该因此触发一次全量解析
		return "RemappedSpecContext[localMods=%d, compileRuntimeMods=%d, productionNamespace=%s]"
				.formatted(localMods == null ? -1 : localMods.size(),
						compileRuntimeMods == null ? -1 : compileRuntimeMods.size(), productionNamespace);
	}

	private record ModHolder(FabricModJson mod, boolean common) {
		ModHolder(FabricModJson mod) {
			this(mod, true);
		}
	}
}
