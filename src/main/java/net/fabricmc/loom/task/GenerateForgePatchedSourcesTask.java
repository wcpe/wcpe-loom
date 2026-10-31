/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2026 FabricMC
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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import codechicken.diffpatch.cli.CliOperation;
import codechicken.diffpatch.cli.PatchOperation;
import codechicken.diffpatch.util.LoggingOutputStream;
import codechicken.diffpatch.util.PatchMode;
import dev.architectury.loom.accesstransformer.AccessTransformerService;
import dev.architectury.loom.forge.ForgeSourcesService;
import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.forge.tool.ForgeTools;
import dev.architectury.loom.mcpconfig.McpExecutor;
import dev.architectury.loom.mcpconfig.McpExecutorBuilder;
import dev.architectury.loom.mcpconfig.steplogic.ConstantLogic;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.Stopwatch;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.SourceRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;

/**
 * 生成与打补丁后一致的 Forge 可调试源码（{@code genForgePatchedSources}）.
 *
 * <h2>以前为什么不能缓存</h2>
 * 四件事各自都足以让缓存失效，且都是真问题：只删注解不但不会命中，还会掩盖正确性缺口。
 * <ol>
 *   <li>{@code @DisableCachingByDefault} 与构造里的 {@code getOutputs().upToDateWhen($ -> false)}：
 *       前者让 Gradle 既不查构建缓存也不写条目，后者让 up-to-date 判定永远失败。两者叠加，
 *       结果就是每次构建重跑整条 MCP 链（含反编译，最贵的一步）。</li>
 *   <li>声明的产物跑完就不存在：{@code sideAnnotationStrippedMinecraftJar} 是 {@code @OutputFile}，
 *       却被放在构造期新开的随机临时目录里，并在执行结束时随 {@code TempFiles#close} 被删掉。</li>
 *   <li>随机路径进了缓存键：同一个随机临时路径经 MCP 链 {@code rename} 步的 {@code ConstantLogic}
 *       （{@code @Input Property<String>}）成为任务输入。路径每次构建都变，键也跟着变，
 *       即便标上 {@code @CacheableTask} 也永远撞不到同一个键。</li>
 *   <li>{@code TempFiles} 藏在任务状态里：{@code @Internal Property<TempFiles>} 是一个持有随机目录、
 *       且在执行期自我删除的可变对象。它既不可序列化（配置缓存存不下条目），又把 AT 文件变成
 *       「配置期抽进临时目录、执行期被删掉」的东西——配置缓存复用时的第二次构建会因此少传
 *       {@code --atFile}，AT 静默失效。</li>
 * </ol>
 *
 * <h2>现在的形状</h2>
 * <ul>
 *   <li>中间产物落在构建目录内的固定位置（{@link #WORK_DIRECTORY}）：AT+SAS 之后的中间 jar 因此
 *       有了稳定路径，它既是真产物，也是 {@code rename} 步稳定的输入。</li>
 *   <li>AT 文件改到执行期从 userdev jar 抽出（工具信息仍是配置期解析好的纯值），
 *       配置期不再产生任何临时文件。</li>
 *   <li>随机临时目录只留给执行期内部的短命中间件，不再进入任何任务属性。</li>
 * </ul>
 *
 * <h2>输入是否完整</h2>
 * 逐项对应执行期真正读入的东西：pre-patch jar（{@code inputJar}）、named 运行时 jar（{@code runtimeJar}）、
 * AT 工具与规则位置、MCP 链的步骤与配置、Forge 工具设置、SAS 数据、补丁前缀，以及 Forge 源码注入与
 * 源码重映射各自的 {@code @Nested} 选项。Forge 源码注入读的源码包、预解压目录与映射文件由
 * {@code ForgeSourcesService#getSourcesCacheKey} 单独覆盖（它自己的注释说明为什么必须计入）。
 *
 * <h2>产物路径与 genSources 撞车（旧有问题，不是本改动引入）</h2>
 * 输出源码包走的是「named MC jar 旁的 {@code -sources.jar}」这条约定，而 {@code genSources}
 * （{@code GenerateSourcesTask}）用的是同一个约定和同一个 named jar（见 {@code SingleJarDecompileConfiguration}）
 * ——两个任务写同一路径，内容不同。保留原语义：后跑者胜。对缓存的影响仅是「对方改写了产物 →
 * 本任务判为过期 → 重新执行或由缓存恢复」；缓存里存的始终是本任务自己那份产物，不会把别人的内容当自己的。
 *
 * <h2>NeoForge 缺口（调研结论，不是「顺手加一个分支」的量）</h2>
 * 注册处 {@code SingleJarDecompileConfiguration} 用 {@code extension.isForge()}（platform == FORGE）把 NeoForge
 * 挡在外面。要放 NeoForge 进来，机械改动只有三处（注册判据、跳过 SAS、源码重映射的源命名空间），
 * 但其中后两处是语义问题，只能靠在 NeoForge 上真跑一遍并核对产出来定：
 * <ol>
 *   <li><b>SAS 是 Forge 独占的</b>：{@code sass} 来自 Forge 的 userdev 配置，剥离工具是
 *       {@code net.minecraftforge:mergetool}（见 {@code ForgeTools#SIDE_STRIPPER}）。NeoForge 的 neoform
 *       没有这一步，所以「AT+SAS 后的 jar 冒充 rename 步产物」这个手法对它没有对应物，得改成
 *       「AT 后的 jar 直接冒充」——本身是一行 if，但要连带确认补丁前缀与 AT 规则的命名空间仍对得上。</li>
 *   <li><b>源码重映射的命名空间写死成 srg → named</b>（见构造器里 {@code getSourceRemapperOptions} 的接线）。
 *       NeoForge 的中间命名空间是 mojang（unobfuscated 形态是 official），见
 *       {@code MinecraftPatchedProvider} 里 {@code intermediateNamespace} 的注释；写死 srg 会让 NeoForge
 *       的源码重映射找不到 srg 列而整批失败。</li>
 *   <li><b>neoform 的 patch 步语义未验证</b>：Forge 车道上本任务把 userdev 里的 Forge 补丁（srg）叠在
 *       MCP 已打过补丁的源码上，再整体 srg→named；NeoForge 的 pre-patch jar 有 installer tools /
 *       rename / preProcessJar 三条分支（见 {@code MinecraftPatchedProvider#registerPatchedChainTasks}），
 *       它们是否同样留下「未打 NeoForge 补丁的 mojang 源码」只能实测。</li>
 * </ol>
 * 现有 neoforge 用例（{@code NeoForge*Test}）只跑 {@code build}，没有一条跑到 {@code genSources} 或本任务，
 * 因此没有「产出的源码是对的」这条底线保障；在没有那套验证之前，这里只记录缺口而不硬接。
 *
 * <h2>构建缓存何时真正生效（Gradle 的 unknown output 规则）</h2>
 * 源码包产物落在跨项目共享的 loom 缓存里（named MC jar 旁的 {@code -sources.jar}）。对「执行前就已存在、
 * 又不是本任务创建的」输出，Gradle 会直接关掉该任务的构建缓存，原文：
 * {@code Gradle does not know how file ... was created (output property ...). Task output caching requires
 * exclusive access to output paths to guarantee correctness}。所以：
 * <ul>
 *   <li>up-to-date 判定不受影响（它只要求输入与输出都没变）；</li>
 *   <li>构建缓存要等本任务在某个工程里成功跑过一次（Gradle 记下「这个文件是本任务造的」）才开始存条目，
 *       此后输入变化、或产物被删，才会走缓存而不是重跑整条链；</li>
 *   <li>同一条规则也解释了 {@code downloadMinecraftClientJar} / {@code generateForgePrePatchJar} 等
 *       「产物在共享缓存里」的任务的 {@code Caching disabled} 日志，不是本任务特有的问题。</li>
 * </ul>
 * 集成用例因此只断言 up-to-date（SUCCESS + 重跑 UP_TO_DATE）：FROM-CACHE 需要「先把产物预删干净」
 * 的三段验法，而那个手法本地未跑通（原因未定位），故不在用例里断言，理由与实测证据记在
 * {@code PatchedDecompileTest} 的注释里。
 */
@CacheableTask
public abstract class GenerateForgePatchedSourcesTask extends AbstractLoomTask {
	/** 中间产物目录（构建目录内的固定位置，见类注释）. */
	public static final String WORK_DIRECTORY = "loom/genForgePatchedSources";

	/** MCP 步进链自己的中间目录（相对 {@link #WORK_DIRECTORY}）. */
	private static final String MCP_CACHE_DIRECTORY = "mcp-cache";

	/** AT+SAS 之后的中间 jar（相对 {@link #WORK_DIRECTORY}）. */
	private static final String SIDE_ANNOTATION_STRIPPED_JAR = "side-annotation-stripped.jar";

	/**
	 * The SRG Minecraft file produced by the MCP executor.
	 */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	/**
	 * The runtime Minecraft file.
	 */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRuntimeJar();

	/**
	 * The source jar.
	 */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	/**
	 * AT 与 SAS 之后的 Minecraft jar，同时也是 MCP 链 {@code rename} 步的输出.
	 *
	 * <p>本任务自产自销（既是产物又是链路中间件），因此它的**路径**必须稳定：该路径会作为
	 * {@code rename} 步 {@code ConstantLogic} 的字符串输入参与缓存键（见类注释第 3 条）。
	 */
	@OutputFile
	protected abstract RegularFileProperty getSideAnnotationStrippedMinecraftJar();

	/**
	 * 本任务的中间产物目录.
	 *
	 * <p>{@code @Internal}：它只决定中间产物放在哪里，不参与任何产物字节，因而不进缓存键。
	 */
	@Internal
	protected abstract DirectoryProperty getWorkDirectory();

	@Nested
	protected abstract Property<ForgeSourcesService.Options> getForgeSourcesOptions();

	@Nested
	protected abstract Property<McpExecutor.Options> getMcpExecutorOptions();

	@Nested
	protected abstract Property<ForgeToolService.Options> getToolServiceOptions();

	@Nested
	protected abstract Property<SourceRemapperService.Options> getSourceRemapperOptions();

	@Nested
	protected abstract Property<SasOptions> getSasOptions();

	/**
	 * AT 工具的主类.
	 *
	 * <p>AT 工具的信息（主类、classpath、JVM）只能在配置期取得（依赖解析 + Java 工具链），故解析成纯值后
	 * 逐项声明成输入；待应用的 AT 文件则在执行期从 userdev jar 抽出，见 {@link #createAccessTransformer}。
	 */
	@Input
	protected abstract Property<String> getAtMainClass();

	/** AT 工具及其依赖的 jar. */
	@Classpath
	protected abstract ConfigurableFileCollection getAtClasspath();

	/** 执行 AT 工具的 JVM；未设置表示沿用 Gradle 自身的 JVM. */
	@Input
	@Optional
	protected abstract Property<String> getAtJavaExecutable();

	/** 同 {@code AccessTransformForgeJarTask}：它只决定工具的标准输出接哪里，不影响产物字节. */
	@Internal
	protected abstract Property<Boolean> getAtVerboseStdout();

	/** 同 {@link #getAtVerboseStdout()}. */
	@Internal
	protected abstract Property<Boolean> getAtVerboseStderr();

	/** AT 文件在 userdev jar 里的目录形态（{@code ats} 声明为目录时设置）. */
	@Input
	@Optional
	protected abstract Property<String> getAtDirectory();

	/** AT 文件在 userdev jar 里的清单形态（{@code ats} 声明为文件列表时设置）. */
	@Input
	protected abstract ListProperty<String> getAtPaths();

	@Input
	protected abstract Property<String> getPatchPathInZip();

	@Input
	protected abstract Property<String> getPatchesOriginalPrefix();

	@Input
	protected abstract Property<String> getPatchesModifiedPrefix();

	/** 执行期实例化服务选项用（{@code Project} 在执行期不可用，见类注释）. */
	@Inject
	public abstract ObjectFactory getObjects();

	public GenerateForgePatchedSourcesTask() {
		// 输出源码包的路径由 runtimeJar 派生（与配置期约定一致：named MC jar 旁的 -sources.jar）。
		// 用 Property#map 而不是 project.provider：后者会把 Project 关进 provider，配置缓存无法序列化它。
		getOutputJar().fileProvider(getRuntimeJar().map(jar ->
				GenerateSourcesTask.getJarFileWithSuffix("-sources.jar", jar.getAsFile().toPath())));

		// 中间产物落在构建目录内的固定位置：既让「声明的产物跑完还在」，也让 rename 步拿到稳定的中间 jar 路径。
		getWorkDirectory().set(getProject().getLayout().getBuildDirectory().dir(WORK_DIRECTORY));
		getSideAnnotationStrippedMinecraftJar().set(getWorkDirectory().file(SIDE_ANNOTATION_STRIPPED_JAR));

		getForgeSourcesOptions().convention(ForgeSourcesService.createOptions(getProject()));

		// 提前展开 Forge 源码包（声明式、可缓存），避免每次执行都手工遍历归档。
		// 用 provider 延迟探测任务是否存在，避免在尚未注册时抛错。
		dependsOn(getProject().getProviders().provider(() ->
				getProject().getTasks().findByName(ExtractArchiveFilesTask.FORGE_SOURCES_TASK_NAME) == null
						? List.of()
						: List.of(ExtractArchiveFilesTask.FORGE_SOURCES_TASK_NAME)));

		getMcpExecutorOptions().convention(getProject().provider(() -> {
			MinecraftPatchedProvider patchedProvider = MinecraftPatchedProvider.get(getProject());
			// MCP 链的中间目录也落在构建目录内（{@code @Internal}，不进缓存键），每次执行前清空，见 run()。
			McpExecutorBuilder mcp = patchedProvider.createMcpExecutor(
					getWorkDirectory().dir(MCP_CACHE_DIRECTORY).get().getAsFile().toPath());
			mcp.setStepLogicProvider((setupContext, name, type) -> {
				if (name.equals("rename")) {
					return ConstantLogic.createOptions(setupContext,
							() -> getSideAnnotationStrippedMinecraftJar().get().getAsFile().toPath());
				}

				return null;
			});
			mcp.enqueue("decompile");
			mcp.enqueue("patch");

			try {
				return mcp.build();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}).flatMap(o -> o));

		// AT 工具信息只能在配置期解析（依赖解析 + Java 工具链），故解析成纯值后逐项声明成任务输入
		final AccessTransformerService.Tool atTool = AccessTransformerService.resolveTool(getProject());
		getAtMainClass().set(atTool.mainClass());
		getAtClasspath().from(atTool.classpath());
		getAtJavaExecutable().set(atTool.javaExecutable());
		getAtVerboseStdout().set(atTool.verboseStdout());
		getAtVerboseStderr().set(atTool.verboseStderr());

		// AT 文件的位置声明与配置期读的 UserdevConfig.ats 同源；文件本身在执行期抽到临时目录
		getExtension().getForgeUserdevProvider().getConfig().ats().visit(directory -> {
			getAtDirectory().set(directory);
			return null;
		}, paths -> {
			getAtPaths().set(paths);
			return null;
		});

		getToolServiceOptions().convention(ForgeToolService.createOptions(getProject()));

		final SasOptions sasOptions = getProject().getObjects().newInstance(SasOptions.class);
		sasOptions.getUserdevJar().set(getExtension().getForgeUserdevProvider().getUserdevJar());
		sasOptions.getSass().set(getExtension().getForgeUserdevProvider().getConfig().sass());
		sasOptions.getClasspath().from(DependencyDownloader.download(getProject(), ForgeTools.SIDE_STRIPPER, false, false));
		getSasOptions().set(sasOptions);

		getPatchPathInZip().set(getExtension().getForgeUserdevProvider().getConfig().patches());
		getPatchesOriginalPrefix().set(getExtension().getForgeUserdevProvider().getConfig().patchesOriginalPrefix().orElse("a/"));
		getPatchesModifiedPrefix().set(getExtension().getForgeUserdevProvider().getConfig().patchesModifiedPrefix().orElse("b/"));

		getSourceRemapperOptions().set(SourceRemapperService.TYPE.create(getProject(), sro -> {
			sro.getMappings().set(MappingsService.createOptionsWithProjectMappings(
					getProject(),
					getProject().provider(() -> "srg"),
					getProject().provider(() -> "named")
			));
			sro.getJavaCompileRelease().set(SourceRemapperService.getJavaCompileRelease(getProject()));
			sro.getClasspath().from(getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
		}));

		// 源码重映射的映射树取自平台映射文件（迁移产物）：投影时它由迁移任务产出，必须显式接线
		getExtension().addPlatformMappingsDependency(this);
	}

	@TaskAction
	public void run() throws IOException {
		final Path work = getWorkDirectory().get().getAsFile().toPath();
		final Path mcpCache = work.resolve(MCP_CACHE_DIRECTORY);
		final Path decompilation = work.resolve("decompilation");

		// 中间目录一律从干净状态开始：MCP 链没有「产物已存在就跳过」的判断，遗留的上一次中间产物
		// 只会让失败路径更难看清；本任务能否复用旧结果由输入哈希决定，不靠这些中间文件。
		deleteDirectory(mcpCache);
		deleteDirectory(decompilation);
		Files.createDirectories(decompilation);

		try (var tempFiles = new TempFiles(); var serviceFactory = new ScopedServiceFactory()) {
			// Transform game jar before decompiling
			Path accessTransformed = decompilation.resolve("access-transformed.jar");
			AccessTransformerService atService = createAccessTransformer(serviceFactory, tempFiles);
			atService.execute(getInputJar().get().getAsFile().toPath(), accessTransformed);
			Path sideAnnotationStripped = getSideAnnotationStrippedMinecraftJar().get().getAsFile().toPath();
			stripSideAnnotations(accessTransformed, sideAnnotationStripped, serviceFactory);

			// Step 1: decompile and patch with MCP patches
			Path rawDecompiled = decompileAndPatch(serviceFactory);
			// Step 2: patch with Forge patches
			getLogger().lifecycle(":applying Forge patches");
			Path patched = sourcePatch(decompilation, rawDecompiled);
			// Step 3: remap
			remap(patched, serviceFactory);
			// Step 4: add Forge's own sources
			final ForgeSourcesService sourcesService = serviceFactory.get(getForgeSourcesOptions());
			sourcesService.addForgeSources(null, getOutputJar().get().getAsFile().toPath());
		} finally {
			// 中间目录不进构建产物：它们可能装着整份反编译结果（数百 MB），而本任务的产物只有两个 jar
			deleteDirectory(mcpCache);
			deleteDirectory(decompilation);
		}
	}

	/**
	 * {@return 本次执行要用的 AT 服务，AT 文件已在这次执行里抽出}.
	 *
	 * <p>AT 文件必须在**执行期**抽：配置期抽进临时目录时，配置缓存条目会指向上一轮已被删除的文件，
	 * 第二次构建就只会少传 {@code --atFile}——AT 静默失效，构建看似成功而实际一条规则都没应用。
	 */
	private AccessTransformerService createAccessTransformer(ServiceFactory serviceFactory, TempFiles tempFiles) throws IOException {
		final Path userdevJar = getSasOptions().get().getUserdevJar().get().getAsFile().toPath();
		final List<String> atFiles = AccessTransformerService.extractLoaderAts(userdevJar, atLocation(), tempFiles);
		return serviceFactory.get(AccessTransformerService.createOptions(getObjects(), atTool(), atFiles));
	}

	/** {@return 本次的 AT 文件位置声明} 与配置期读的 {@code UserdevConfig.ats} 逐项同源. */
	private UserdevConfig.AccessTransformerLocation atLocation() {
		if (getAtDirectory().isPresent()) {
			return new UserdevConfig.AccessTransformerLocation.Directory(getAtDirectory().get());
		}

		return new UserdevConfig.AccessTransformerLocation.FileList(getAtPaths().get());
	}

	/** {@return 配置期解析出的 AT 工具信息} 执行期只认这些纯值. */
	private AccessTransformerService.Tool atTool() {
		final @Nullable String javaExecutable = getAtJavaExecutable().getOrNull();

		return new AccessTransformerService.Tool(
				getAtMainClass().get(),
				getAtClasspath().getFiles().stream().map(File::getAbsolutePath).toList(),
				javaExecutable,
				getAtVerboseStdout().get(),
				getAtVerboseStderr().get());
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (Files.exists(directory)) {
			DeletingFileVisitor.deleteDirectory(directory);
		}
	}

	private Path decompileAndPatch(ScopedServiceFactory serviceFactory) throws IOException {
		final McpExecutor executor = serviceFactory.get(getMcpExecutorOptions());
		return executor.execute();
	}

	private Path sourcePatch(Path target, Path rawDecompiled) throws IOException {
		String patchPathInZip = getPatchPathInZip().get();
		Path output = target.resolve("patched.jar");
		Path rejects = target.resolve("rejects");

		CliOperation.Result<PatchOperation.PatchesSummary> result = PatchOperation.builder()
				.logTo(new LoggingOutputStream(getLogger(), LogLevel.INFO))
				.basePath(rawDecompiled)
				.patchesPath(getSasOptions().get().getUserdevJar().get().getAsFile().toPath())
				.patchesPrefix(patchPathInZip)
				.outputPath(output)
				.mode(PatchMode.ACCESS)
				.rejectsPath(rejects)
				.aPrefix(getPatchesOriginalPrefix().get())
				.bPrefix(getPatchesModifiedPrefix().get())
				.build()
				.operate();

		if (result.exit != 0) {
			throw new RuntimeException("Could not patch " + rawDecompiled + "; rejects saved to " + rejects.toAbsolutePath());
		}

		return output;
	}

	private void remap(Path input, ServiceFactory serviceFactory) throws IOException {
		final SourceRemapperService remapperService = serviceFactory.get(getSourceRemapperOptions());
		remapperService.remapSourcesJar(input, getOutputJar().get().getAsFile().toPath());
	}

	private void stripSideAnnotations(Path input, Path output, ServiceFactory serviceFactory) throws IOException {
		final Stopwatch stopwatch = Stopwatch.createStarted();
		getLogger().lifecycle(":stripping side annotations");

		try (var tempFiles = new TempFiles()) {
			final List<String> sass = getSasOptions().get().getSass().get();
			final List<Path> sasPaths = new ArrayList<>();

			try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(getSasOptions().get().getUserdevJar().get().getAsFile().toPath())) {
				for (String sasPath : sass) {
					try {
						final Path from = fs.getPath(sasPath);
						final Path to = tempFiles.file(null, ".sas");
						Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
						sasPaths.add(to);
					} catch (IOException e) {
						throw new IOException("Could not extract SAS " + sasPath);
					}
				}
			}

			final ForgeToolService toolService = serviceFactory.get(getToolServiceOptions());
			toolService.exec(spec -> {
				spec.setClasspath(getSasOptions().get().getClasspath());
				spec.args(
						"--strip",
						"--input", input.toAbsolutePath().toString(),
						"--output", output.toAbsolutePath().toString()
				);

				for (Path sasPath : sasPaths) {
					spec.args("--data", sasPath.toAbsolutePath().toString());
				}
			});
		}

		getLogger().lifecycle(":side annotations stripped in " + stopwatch.stop());
	}

	public interface SasOptions {
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getUserdevJar();

		@Input
		ListProperty<String> getSass();

		@Classpath
		ConfigurableFileCollection getClasspath();
	}
}
