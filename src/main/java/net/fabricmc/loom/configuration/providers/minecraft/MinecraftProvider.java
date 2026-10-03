/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2025 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.configuration.providers.minecraft.verify.MinecraftJarVerification;
import net.fabricmc.loom.configuration.providers.minecraft.verify.SignatureVerificationFailure;
import net.fabricmc.loom.pipeline.DownloadArtifactTask;
import net.fabricmc.loom.pipeline.ExtractMinecraftServerJarTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.cache.JarReusability;
import net.fabricmc.loom.util.download.DownloadExecutor;
import net.fabricmc.loom.util.download.GradleDownloadProgressListener;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.gradle.ProgressGroup;

public abstract class MinecraftProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftProvider.class);

	private final MinecraftMetadataProvider metadataProvider;

	private File minecraftClientJar;
	// Note this will be the boostrap jar starting with 21w39a
	private File minecraftServerJar;
	// The extracted server jar from the boostrap, only exists in >=21w39a
	private File minecraftExtractedServerJar;
	@Nullable
	private BundleMetadata serverBundleMetadata;
	private String jarPrefix = "";

	private final ConfigContext configContext;

	/**
	 * 本次是否把产物生产（下载/抽取，子类再含合并/拆分）交给执行期任务.
	 *
	 * <p>只有 {@link #projectionBlocker()} 为 {@code null} 时才为真；为假时全部产物沿用改造前的
	 * 配置期路径。判定**按 provider 整批**：半批投影会留下「一部分产物由任务生产、一部分由配置期生产」
	 * 的混合状态，同一份共享缓存里出现两种就绪判据——正是本次改造要消灭的东西。
	 */
	private boolean taskProduction;

	/**
	 * 本次登记给执行期任务的生产产物：产物路径（绝对规范化）→ 生产者.
	 *
	 * <p>未投影时为空。消费方（mapped provider 的重映射任务、生产环境客户端任务）据此把
	 * 「我的输入就是你的产出」表达成任务依赖：输入若只按路径声明，Gradle 无从知道要先跑产出任务。
	 */
	private Map<Path, Producer> jarProducers = Map.of();

	public MinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		this.metadataProvider = metadataProvider;
		this.configContext = configContext;
	}

	protected boolean provideClient() {
		return true;
	}

	protected boolean provideServer() {
		return true;
	}

	public void provide() throws Exception {
		// 内存配置：无论缓存冷热都必须执行
		if (getExtension().shouldGenerateSrgTiny() && !getExtension().isForgeLike()) {
			getProject().getDependencies().add(Constants.Configurations.SRG, "de.oceanlabs.mcp:mcp_config:" + minecraftVersion());
		}

		initFiles();

		verifyJavaVersion();

		// 可投影则把产物生产交给执行期任务，否则整批沿用改造前的配置期路径（含留痕，见 projectionBlocker）
		if (canProjectToTasks()) {
			projectToTasks();
		} else {
			produceJarsInConfiguration();
		}

		// 内存配置：libraryProvider 每次都必须执行
		final MinecraftLibraryProvider libraryProvider = new MinecraftLibraryProvider(this, configContext.project());
		libraryProvider.provide();
	}

	/**
	 * {@return 本 provider 能否把产物生产投影成执行期任务}.
	 *
	 * <p>覆写点只有 {@link #projectionBlocker()} 一个：判定与「为什么不能投影」必须在同一处，
	 * 否则回退会变成静默的（看不到被拒的具体原因）。
	 */
	protected final boolean canProjectToTasks() {
		return taskProduction || projectionBlocker() == null;
	}

	/** {@return 本次是否已把产物生产交给执行期任务} 子 provider（合并/拆分）据此决定自己怎么生产. */
	protected final boolean isTaskProduction() {
		return taskProduction;
	}

	/**
	 * {@return 不能把产物生产投影成执行期任务的原因；可以投影时为 {@code null}}.
	 *
	 * <p>每一条都是一个「配置期必须真读产物 jar（或必须由配置期落盘）」的形态。投影后产物只在执行期落位，
	 * 这些配置期读者会读到不存在（或上一代）的文件，因此必须整批退回配置期生产——那个路径与改造前逐字一致。
	 *
	 * <ul>
	 *   <li><b>Forge 系</b>：patch 流程、MCP 映射合并、内部类名集合（
	 *       {@code InnerClassRemapper.readClassNames}）都在配置期读 vanilla jar。</li>
	 *   <li><b>disableObfuscation</b>：mapped 阶段自身整批回退到配置期生产，配置期会按路径读 vanilla jar。</li>
	 *   <li><b>签名校验开启</b>：校验要打开 jar 读签名与内容，本次未随链迁移（见
	 *       {@link #verificationEnabled()}）。</li>
	 *   <li><b>映射可能不是 tiny v2</b>：V1 映射的字段名补全在配置期读 merged jar，且只支持单 jar 形态。</li>
	 *   <li><b>bundle 元数据未进 L2 规格缓存</b>：服务端库的注入是配置期事实，元数据没有缓存就必须先
	 *       下载并打开 server jar——那正是本次要移出配置期的动作。</li>
	 * </ul>
	 */
	protected @Nullable String projectionBlocker() {
		if (getExtension().isForgeLike()) {
			// 这一条**不能**细化成「legacy Forge 才回退」：本方法执行得比 setupDependencyProviders 早得多
			// （{@code CompileConfiguration.setupMinecraft} 里 minecraftProvider.provide() 在
			// setupDependencyProviders 之前），而 isLegacyForge() 要读 ForgeUserdevProvider，
			// 那时 getDependencyProviders() 仍是 null，判据会以 NPE 打断配置。
			// 真要细化，必须先让依赖 provider 早于本方法建立、或把 vanilla jar 的生产决策整体推迟到那之后。
			return "Forge 系的 vanilla jar 在配置期就被真读（patch 流程、MCP 映射合并、内部类名集合）";
		}

		if (getExtension().disableObfuscation()) {
			return "disableObfuscation 下 mapped 阶段整批回退到配置期生产，配置期会按路径读 vanilla jar";
		}

		if (verificationEnabled()) {
			return "Minecraft jar 签名校验已启用（" + Constants.Properties.ENABLE_MINECRAFT_VERIFICATION + "），校验在配置期读 jar 内容";
		}

		if (!mappingsAreDeclaredV2()) {
			return "映射依赖未声明为 tiny v2（V1 映射的字段名补全在配置期读 merged jar）";
		}

		if (provideServer() && !isServerBundleMetadataCached()) {
			return "server bundle 元数据未命中 L2 规格缓存：服务端库注入是配置期事实，未缓存就必须先下载并读 server jar";
		}

		return null;
	}

	/** {@return 是否开启了 Minecraft jar 签名校验} 判据与 {@link #verifyJars()} 一致. */
	private boolean verificationEnabled() {
		return GradleUtils.getBooleanProperty(getProject(), Constants.Properties.ENABLE_MINECRAFT_VERIFICATION);
	}

	/**
	 * {@return 映射依赖是否可确认为 tiny v2}.
	 *
	 * <p>配置期读到 V1 映射时，{@code MappingConfiguration.storeMappings} 会打开 merged jar 补字段名，
	 * 因此「这次用的到底是 V1 还是 V2」必须在投影**之前**就有答案。映射 jar 的内容要等解析、抽取之后
	 * 才知道（那时产物早已按任务路径声明），所以这里只看声明处能拿到的信息：
	 *
	 * <ul>
	 *   <li><b>分层映射</b>（{@code loom:mappings:…}，见
	 *       {@link net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory}）恒为
	 *       tiny v2，但其坐标没有 classifier，必须按坐标单独认——{@code officialMojangMappings()} 等
	 *       全部走这条路，漏掉它会让绝大多数真实工程整批回退。</li>
	 *   <li><b>{@code …:v2} classifier</b>是 tiny v2 变体，认。</li>
	 *   <li>其余（无 classifier、未知层、非模块依赖）保守地当作「可能不是 v2」，退回配置期生产。</li>
	 * </ul>
	 *
	 * <p>判据偏保守只影响「迁移覆盖范围」，不影响行为——判错的代价是这次构建仍按改造前的路径跑。
	 */
	private boolean mappingsAreDeclaredV2() {
		final Configuration mappings = getProject().getConfigurations().findByName(Constants.Configurations.MAPPINGS);

		if (mappings == null || mappings.getDependencies().size() != 1) {
			// 无映射依赖（无需映射的配置）或形态未知（多层映射由别处展开）：保守回退
			return false;
		}

		final Dependency dependency = mappings.getDependencies().iterator().next();

		if (LayeredMappingsFactory.isLayeredMappingsDependency(dependency.getGroup(), dependency.getName())) {
			return true;
		}

		return "v2".equals(DependencyInfo.create(getProject(), dependency, mappings).getDeclaredClassifier());
	}

	/** {@return server bundle 元数据是否已能在配置期无 jar 读取地拿到} 见 {@link BundleMetadata#isCached}. */
	private boolean isServerBundleMetadataCached() {
		final MinecraftVersionMeta.Download serverDownload = getVersionInfo().download("server");
		final String sha1 = serverDownload != null ? serverDownload.sha1() : null;
		final var store = new net.fabricmc.loom.spec.SpecStore(getExtension().getFiles().getUserCache().toPath());
		return BundleMetadata.isCached(store, sha1);
	}

	/**
	 * 把本 provider 的产物投影成执行期任务.
	 *
	 * <p>这里只做「配置期已知量 → 任务输入」的映射，不写任何产物文件：产物有效性交给 Gradle 的
	 * up-to-date 判定与构建缓存，并发保护交给任务图，残骸恢复交给构建缓存。产物路径一律沿用既有位置
	 * （{@code <userCache>/<mcVersion>/}），否则消费侧按路径找产物的地方会全部悬空。
	 *
	 * <p>同一产物路径在本构建内只能有一个生产者，因此逐产物走
	 * {@link RemapMinecraftTaskRegistry#claim}：同一构建内两个配置相同的子项目不会各自建一个任务写同一个文件。
	 */
	private void projectToTasks() throws IOException {
		final Map<Path, Producer> producers = new LinkedHashMap<>();

		// bundle 元数据已由 projectionBlocker 保证命中 L2 缓存：这里读它不会打开 server jar
		if (provideServer()) {
			serverBundleMetadata = readServerBundleMetadata();
		}

		if (provideClient()) {
			registerDownloadTask(producers, "downloadMinecraftClientJar", "client", getVersionInfo().download("client"), minecraftClientJar);
		}

		if (provideServer()) {
			registerDownloadTask(producers, "downloadMinecraftServerJar", "server", getVersionInfo().download("server"), minecraftServerJar);

			if (serverBundleMetadata != null) {
				registerExtractTask(producers);
			}
		}

		// 子 provider 追加自己的产物（合并/拆分）：它们把「我的输入就是你的产出」登记成任务依赖，
		// 因此必须在下载/抽取登记之后、整张表冻结之前执行
		registerProviderTasks(producers);

		jarProducers = Map.copyOf(producers);
		taskProduction = true;
		// 登记任务产出：消费侧（重映射任务、生产环境客户端）据此拿到携带任务依赖的文件集合
		registerJarOutputs();
		// 用 Gradle 的 lifecycle 而不是 SLF4J 的 info：默认控制台级别是 LIFECYCLE，
		// 「本次到底走哪条生产路径」必须默认可见，否则回退是静默的
		getProject().getLogger().lifecycle("Minecraft {} 的 jar 生产由执行期任务承担：{}", minecraftVersion(),
				producers.values().stream().map(Producer::taskPath).distinct().toList());
	}

	/**
	 * 子 provider 在此注册自己的产物（合并/拆分），并把它们的输入登记成任务依赖.
	 *
	 * <p>默认什么也不做：产物就是下载/抽取出来的那些 jar。
	 *
	 * @param producers 本次已登记的产物（可写）：键是产物的绝对规范化路径
	 */
	protected void registerProviderTasks(Map<Path, Producer> producers) {
	}

	/**
	 * 把本 provider 的最终产物（{@link #getMinecraftJars()}）登记给消费侧.
	 *
	 * <p>登记的是**最终产物**而不是中间件（client/server/抽取产物）：配置期回退路径下
	 * {@code LoomGradleExtension.getMinecraftJars(OFFICIAL)} 给的也是最终产物，两者必须一致，
	 * 否则消费侧的文件集合会随「投影与否」变化。
	 */
	private void registerJarOutputs() {
		final ConfigurableFileCollection outputs = getProject().getObjects().fileCollection();

		for (Path jar : getMinecraftJars()) {
			outputs.from(jar.toFile());
			final String taskPath = productionTaskPath(jar);

			if (taskPath != null) {
				outputs.builtBy(taskPath);
			}
		}

		getExtension().setMinecraftJarsTaskOutputs(getOfficialNamespace(), outputs);
	}

	/**
	 * 登记一个由**本 provider 之外的接线方**（Forge 的 patch 链）产出的最终 jar.
	 *
	 * <p>这些 jar 同样出现在 {@link #getMinecraftJars()} 里，因此必须走同一条登记路径：
	 * 消费侧（{@code getMinecraftJarsCollection(OFFICIAL)}、mapped provider 的重映射任务）靠这里拿到
	 * 「我的输入由哪个任务产出」的任务依赖。只把产物当成路径交给它们，冷缓存下消费方就会在产物落位前开跑。
	 *
	 * <p>只能在 {@link #provide()} 之后调用：那时 {@link #getMinecraftJars()} 才有意义，
	 * 且首次 {@link #registerJarOutputs()} 已经把命名空间下的集合实例发出去，这里的重复登记是「并进」语义。
	 *
	 * @param artifact 产物路径（绝对规范化）
	 * @param producer 该产物的生产者（携带任务路径）
	 */
	public void registerTaskProducedArtifact(Path artifact, Producer producer) {
		final Map<Path, Producer> merged = new LinkedHashMap<>(jarProducers);
		merged.put(normalize(artifact), producer);
		jarProducers = Map.copyOf(merged);
		registerJarOutputs();
	}

	/** {@return 本批登记中该产物的生产者；未登记时为 {@code null}} 供子 provider 建任务依赖用. */
	protected static @Nullable Producer producerOf(Map<Path, Producer> producers, Path artifact) {
		return producers.get(normalize(artifact));
	}

	/**
	 * 把生产者登记成任务依赖；未投影（无任务）时什么也不做.
	 *
	 * <p>{@code dependsOn}/{@code builtBy} 都接受任务路径：产出方可能由另一份 Loom classloader 配置，
	 * 把对方的任务实例交过来会在使用处抛 {@code ClassCastException}。
	 */
	protected static void dependOn(Task task, @Nullable Producer producer) {
		final String taskPath = taskPathOf(producer);

		if (taskPath != null) {
			task.dependsOn(taskPath);
		}
	}

	/**
	 * 注册单个下载任务.
	 *
	 * <p>输入与配置期的 {@code extension.download(url)} 逐项同源：url、严格 sha1 校验、离线、
	 * 强制刷新。{@code useDefaultCache} 恒为假——配置期那条路径用的是 {@code sha1(...)} 而不是
	 * {@code defaultCache()}，两者的失效策略不同。
	 */
	private void registerDownloadTask(Map<Path, Producer> producers, String taskName, String label,
			MinecraftVersionMeta.Download download, File target) {
		final Project project = getProject();
		final boolean offline = project.getGradle().getStartParameter().isOffline();
		final boolean forceDownload = getExtension().manualRefreshDeps();

		producers.put(normalize(target.toPath()), RemapMinecraftTaskRegistry.claim(project, target.toPath(), Map.of(
				"stage", "download",
				"url", download.url(),
				"sha1", download.sha1(),
				"offline", Boolean.toString(offline),
				"forceDownload", Boolean.toString(forceDownload)
		), () -> project.getTasks().register(taskName, DownloadArtifactTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Downloads the Minecraft %s jar for %s".formatted(label, minecraftVersion()));
			task.getUrl().set(download.url());
			task.getSha1().set(download.sha1());
			task.getOffline().set(offline);
			task.getForceDownload().set(forceDownload);
			task.getUseDefaultCache().set(false);
			task.getOutputFile().set(target);
		})));
	}

	/**
	 * 注册从 bootstrap server jar 抽取内嵌 server jar 的任务.
	 *
	 * <p>抽取判据与配置期 {@link #extractBundledServerJar()} 逐项一致（条目数必须为 1、条目路径与
	 * sha1 取自 bundle 元数据），产出位置也一致：抽取产物是合并/拆分的输入，换路径会让下游读不到它。
	 */
	private void registerExtractTask(Map<Path, Producer> producers) {
		final BundleMetadata metadata = Objects.requireNonNull(serverBundleMetadata, "没有 bundle 元数据就没有可抽取的 server jar");

		if (metadata.versions().size() != 1) {
			throw new UnsupportedOperationException("Expected only 1 version in META-INF/versions.list, but got %d".formatted(metadata.versions().size()));
		}

		final Project project = getProject();
		final BundleMetadata.Entry entry = metadata.versions().get(0);
		final Path output = normalize(getMinecraftExtractedServerJar().toPath());
		final boolean refreshDeps = getExtension().refreshDeps();
		final Producer serverJarProducer = producers.get(normalize(minecraftServerJar.toPath()));

		producers.put(output, RemapMinecraftTaskRegistry.claim(project, output, Map.of(
				"stage", "extract-server-jar",
				"serverJar", normalize(minecraftServerJar.toPath()).toString(),
				"entryPath", entry.path(),
				"entrySha1", entry.sha1(),
				"refreshDeps", Boolean.toString(refreshDeps)
		), () -> project.getTasks().register("extractMinecraftServerJar", ExtractMinecraftServerJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Extracts the Minecraft server jar for %s from the bootstrap jar".formatted(minecraftVersion()));
			task.getServerJar().set(minecraftServerJar);
			task.getEntryPath().set(entry.path());
			task.getEntrySha1().set(entry.sha1());
			task.getRefreshDeps().set(refreshDeps);
			task.getOutputJar().set(output.toFile());
			// 输入 jar 由下载任务产出：按任务路径建依赖（产出方可能来自另一份 Loom classloader）
			dependOn(task, serverJarProducer);
		})));
	}

	/**
	 * {@return 产出该产物的任务路径；未投影时为 {@code null}}.
	 *
	 * <p>交给 {@code dependsOn}/{@code builtBy} 用的是任务**路径**而不是任务实例：产出方可能由另一份
	 * Loom classloader 配置（约定插件/included build 各自带一份 Loom），把对方的任务实例交过来会在
	 * 使用处抛 {@code ClassCastException}。
	 */
	private static @Nullable String taskPathOf(@Nullable Producer producer) {
		return producer == null ? null : producer.taskPath();
	}

	/**
	 * {@return 该产物的产出任务路径；本 provider 未投影或该路径不由本 provider 产出时为 {@code null}}.
	 *
	 * <p>供消费方把「我的输入就是你的产出」表达成任务依赖。
	 */
	public @Nullable String productionTaskPath(Path artifact) {
		return taskPathOf(jarProducers.get(normalize(artifact)));
	}

	/**
	 * {@return 该产物「按路径声明 + 携带产出任务依赖」的单文件集合}.
	 *
	 * <p>未投影时是裸文件，与改造前的语义一致（消费侧只需路径，产物早已在配置期落盘）。
	 */
	public FileCollection outputForTasks(Path artifact) {
		final ConfigurableFileCollection files = getProject().files(artifact.toFile());
		final String taskPath = taskPathOf(jarProducers.get(normalize(artifact)));

		if (taskPath != null) {
			files.builtBy(taskPath);
		}

		return files;
	}

	/** 把「本任务以该产物为输入」表达成任务依赖；未投影时什么也不做. */
	public void addProducerDependency(Task task, Path artifact) {
		dependOn(task, jarProducers.get(normalize(artifact)));
	}

	/** {@return 路径的绝对规范化形式} 生产者的键一律用它，避免「同一文件两种写法」查不到生产者. */
	protected static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}

	/**
	 * 配置期生产：本次改造前的路径，只对「无法安全投影」的形态保留.
	 *
	 * <p>整段语义与改造前逐字一致——无锁快路径、跨进程互斥、锁内二次确认、抽取、签名校验。
	 */
	private void produceJarsInConfiguration() throws Exception {
		final String blocker = projectionBlocker();

		if (blocker != null) {
			getProject().getLogger().lifecycle("Minecraft {} 的 jar 保持配置期生产：{}", minecraftVersion(), blocker);
		}

		// 无锁快路径：未要求刷新且所有产物已就绪时，不获取任何文件锁
		if (jarsRequireProduction()) {
			// 产文件部分（下载/抽取/校验）用 per-key 锁保护，按 mcVersion 串行、不同版本可并行
			final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
			final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

			cacheService.runExclusive(lockRoot, downloadLockKey(), LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：等锁期间可能已被其它进程/线程下载完成，避免重复下载/抽取
				if (!jarsRequireProduction()) {
					return null;
				}

				boolean didDownload = downloadJars();

				if (provideServer()) {
					// 锁内读取 bundle 元数据用于决定是否抽取（extractBundledServerJar 依赖该字段）
					serverBundleMetadata = readServerBundleMetadata();

					if (serverBundleMetadata != null) {
						extractBundledServerJar();
					}
				}

				if (didDownload) {
					verifyJars();
				}

				return null;
			});
		}

		// 暖路径补设字段：上面锁内可能整段跳过（缓存就绪时未进入生产），但 server jar 必已就绪，
		// 故在锁外对已存在的 server jar 再读取一次，保证暖缓存下 serverBundleMetadata 也正确。
		// 该读取走 L2 缓存（按制品 sha1），稳定命中时不会打开 jar，配置期也就不再观察它。
		if (provideServer() && serverBundleMetadata == null) {
			serverBundleMetadata = readServerBundleMetadata();
		}
	}

	/**
	 * 读取服务端制品的 bundle 元数据.
	 *
	 * <p>走 L2 规格缓存：身份取制品在 version json 中声明的 sha1，因此后续构建无需打开 jar。
	 * 这是把服务端下载移出配置期的先决条件——见
	 * {@link BundleMetadata#fromJarCached(net.fabricmc.loom.spec.SpecStore, Path, String)}。
	 */
	private BundleMetadata readServerBundleMetadata() throws IOException {
		final MinecraftVersionMeta.Download serverDownload = getVersionInfo().download("server");
		final String sha1 = serverDownload != null ? serverDownload.sha1() : null;
		final var store = new net.fabricmc.loom.spec.SpecStore(getExtension().getFiles().getUserCache().toPath());
		return BundleMetadata.fromJarCached(store, minecraftServerJar.toPath(), sha1);
	}

	// 下载/抽取产物的跨进程锁 key：始终按版本，确保同一 mcVersion 的所有 jar 配置共享同一把下载锁、只下载一次。
	// 不可用会被子类覆写的 cacheKey()，否则不同 jar 配置（如 server-only/client-only single jar）会用不同 key 并发下载同一批共享 jar。
	private String downloadLockKey() {
		return "minecraft:" + minecraftVersion();
	}

	// 跨进程互斥 key：用于子类自身产物（merge/split/env-only jar）的生产锁，子类可覆写以区分不同产物
	protected String cacheKey() {
		return "minecraft:" + minecraftVersion();
	}

	// 无锁快路径：判断下载/抽取产物是否需要生产。返回 false 即所有产物已就绪，可走无锁快路径。
	//
	// 就绪判据含内容校验（见 JarReusability.isReusable）：这三件 jar 都落在跨进程共享的
	// <userCache>/<mcVersion> 下，旧版本 loom 以最终路径为输出就地写，被中断会留下 0 字节或截断文件。
	// PR #8 移除了「残留锁 → 全量重建」这条兜底后，只判存在就会把这类残骸当成已下载产物一路用下去。
	// 判 true 进锁后由 downloadJars 按 sha1 兜底（sha1 不匹配即重新下载），不存在「每次都重建」的退化。
	private boolean jarsRequireProduction() {
		if (getExtension().refreshDeps()) {
			return true;
		}

		if (provideClient() && !JarReusability.isReusable(minecraftClientJar.toPath())) {
			return true;
		}

		if (provideServer()) {
			if (!JarReusability.isReusable(minecraftServerJar.toPath())) {
				return true;
			}

			// 该版本若使用 bundler，则抽取后的 server jar 也必须存在且可复用。
			// serverBundleMetadata 此时尚未读取，故直接读已下载的 server jar 判断是否为 bundler。
			try {
				if (BundleMetadata.fromJar(minecraftServerJar.toPath()) != null
						&& !JarReusability.isReusable(minecraftExtractedServerJar.toPath())) {
					return true;
				}
			} catch (IOException e) {
				// 读取失败视为需要重新生产
				return true;
			}
		}

		return false;
	}

	private void verifyJavaVersion() {
		if (configContext.extension().disableObfuscation()) {
			return;
		}

		// Verify that the current Gradle Java version is the same or higher than the required Java version for this Minecraft version.
		// This is required so the remappers can retrive the correct context of Java classes when remapping.

		final MinecraftVersionMeta.JavaVersion javaVersion = getVersionInfo().javaVersion();

		if (javaVersion != null) {
			final int requiredMajorJavaVersion = getVersionInfo().javaVersion().majorVersion();
			final JavaVersion requiredJavaVersion = JavaVersion.toVersion(requiredMajorJavaVersion);

			if (!JavaVersion.current().isCompatibleWith(requiredJavaVersion)) {
				throw new IllegalStateException("Minecraft " + minecraftVersion() + " requires Java " + requiredJavaVersion + " but Gradle is using " + JavaVersion.current());
			}
		}
	}

	protected void initFiles() {
		if (provideClient()) {
			minecraftClientJar = file("minecraft-client.jar");
		}

		if (provideServer()) {
			minecraftServerJar = file("minecraft-server.jar");
			minecraftExtractedServerJar = file("minecraft-extracted_server.jar");
		}
	}

	private void verifyJars() throws IOException, SignatureVerificationFailure {
		if (!GradleUtils.getBooleanProperty(getProject(), Constants.Properties.ENABLE_MINECRAFT_VERIFICATION)) {
			LOGGER.info("Skipping Minecraft jar verification!");
			return;
		}

		LOGGER.info("Verifying Minecraft jars");

		MinecraftJarVerification verification = getProject().getObjects().newInstance(MinecraftJarVerification.class, minecraftVersion());

		if (provideClient()) {
			verification.verifyClientJar(minecraftClientJar.toPath());
		}

		if (provideServer()) {
			if (serverBundleMetadata == null) {
				verification.verifyServerJar(minecraftServerJar.toPath());
			} else {
				verification.verifyServerJar(getMinecraftExtractedServerJar().toPath());
			}
		}

		LOGGER.info("Jar verification complete");
	}

	// Returns true when a file was downloaded
	private boolean downloadJars() throws IOException {
		AtomicBoolean didDownload = new AtomicBoolean(false);

		try (ProgressGroup progressGroup = new ProgressGroup(getProject(), "Download Minecraft jars");
				DownloadExecutor executor = new DownloadExecutor(2)) {
			if (provideClient()) {
				final MinecraftVersionMeta.Download client = getVersionInfo().download("client");
				getExtension().download(client.url())
						.sha1(client.sha1())
						.progress(new GradleDownloadProgressListener("Minecraft client", progressGroup::createProgressLogger))
						.downloadPathAsync(minecraftClientJar.toPath(), executor)
						.thenAccept(downloadResult -> {
							if (downloadResult.didDownload()) {
								didDownload.set(true);
							}
						});
			}

			if (provideServer()) {
				final MinecraftVersionMeta.Download server = getVersionInfo().download("server");
				getExtension().download(server.url())
						.sha1(server.sha1())
						.progress(new GradleDownloadProgressListener("Minecraft server", progressGroup::createProgressLogger))
						.downloadPathAsync(minecraftServerJar.toPath(), executor)
						.thenAccept(downloadResult -> {
							if (downloadResult.didDownload()) {
								didDownload.set(true);
							}
						});
			}
		}

		if (didDownload.get()) {
			LOGGER.info("Downloaded new Minecraft jars");
			return true;
		}

		LOGGER.info("Using cached Minecraft jars");
		return false;
	}

	public final void extractBundledServerJar() throws IOException {
		Check.require(provideServer(), "Not configured to provide server jar");
		Objects.requireNonNull(getServerBundleMetadata(), "Cannot bundled mc jar from none bundled server jar");

		LOGGER.info(":Extracting server jar from bootstrap");

		if (getServerBundleMetadata().versions().size() != 1) {
			throw new UnsupportedOperationException("Expected only 1 version in META-INF/versions.list, but got %d".formatted(getServerBundleMetadata().versions().size()));
		}

		getServerBundleMetadata().versions().get(0).unpackEntry(minecraftServerJar.toPath(), getMinecraftExtractedServerJar().toPath(), configContext.project());
	}

	public File workingDir() {
		return minecraftWorkingDirectory(configContext.project(), minecraftVersion());
	}

	public File dir(String path) {
		File dir = file(path);
		dir.mkdirs();
		return dir;
	}

	public File file(String path) {
		return new File(workingDir(), path);
	}

	public Path path(String path) {
		return file(path).toPath();
	}

	public File getMinecraftClientJar() {
		Check.require(provideClient(), "Not configured to provide client jar");
		return minecraftClientJar;
	}

	// May be null on older versions
	@Nullable
	public File getMinecraftExtractedServerJar() {
		Check.require(provideServer(), "Not configured to provide server jar");
		return minecraftExtractedServerJar;
	}

	// This may be the server bundler jar on newer versions prob not what you want.
	public File getMinecraftServerJar() {
		Check.require(provideServer(), "Not configured to provide server jar");
		return minecraftServerJar;
	}

	public String minecraftVersion() {
		return Objects.requireNonNull(metadataProvider, "Metadata provider not setup").getMinecraftVersion();
	}

	public MinecraftVersionMeta getVersionInfo() {
		return Objects.requireNonNull(metadataProvider, "Metadata provider not setup").getVersionMeta();
	}

	/**
	 * @return true if the minecraft version is older than 1.3.
	 */
	public boolean isLegacyVersion() {
		return getVersionInfo().isLegacyVersion();
	}

	/**
	 * Returns true if the minecraft version is between Beta 1.0 (inclusive) and 1.3 (exclusive),
	 * which splits the {@code official} mapping namespace into env-specific variants.
	 */
	public boolean isLegacySplitOfficialNamespaceVersion() {
		return getVersionInfo().isLegacySplitOfficialNamespaceVersion();
	}

	public String getJarPrefix() {
		return jarPrefix;
	}

	public void setJarPrefix(String jarSuffix) {
		this.jarPrefix = jarSuffix;
	}

	@Nullable
	public BundleMetadata getServerBundleMetadata() {
		return serverBundleMetadata;
	}

	public abstract List<Path> getMinecraftJars();

	public abstract MappingsNamespace getOfficialNamespace();

	protected Project getProject() {
		return configContext.project();
	}

	protected LoomGradleExtension getExtension() {
		return configContext.extension();
	}

	public boolean refreshDeps() {
		return getExtension().refreshDeps();
	}

	public static File minecraftWorkingDirectory(Project project, String version) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		File workingDir = new File(extension.getFiles().getUserCache(), version);
		workingDir.mkdirs();
		return workingDir;
	}
}
