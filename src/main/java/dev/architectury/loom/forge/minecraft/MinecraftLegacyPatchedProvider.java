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

package dev.architectury.loom.forge.minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import dev.architectury.loom.accesstransformer.AccessTransformerService;
import dev.architectury.loom.forge.CoreModManagerTransformer;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.dependency.PatchProvider;
import dev.architectury.loom.forge.tool.ForgeExternalToolService;
import dev.architectury.loom.util.Stopwatch;
import dev.architectury.loom.util.ThreadingUtils;
import org.gradle.api.Project;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.pipeline.AccessTransformForgeJarTask;
import net.fabricmc.loom.pipeline.GenerateLegacyForgeJarTask;
import net.fabricmc.loom.pipeline.MergeLegacyPatchedJarTask;
import net.fabricmc.loom.pipeline.PatchLegacyMinecraftJarsTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.stitch.merge.JarMerger;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class MinecraftLegacyPatchedProvider extends MinecraftPatchedProvider {
	// Step 1: Binary Patch
	private Path minecraftClientPatchedJar;
	private Path minecraftServerPatchedJar;
	// Step 2: Merge (global)
	private Path minecraftMergedPatchedJar;
	// Step 4: Access Transform (global or project)
	private Path minecraftPatchedAtJar;

	private Path forgeJar;

	/** forge.jar 是否由执行期任务产出（投影形态）；false 时本类在配置期就地生产. */
	private boolean forgeJarProjected;

	public MinecraftLegacyPatchedProvider(Project project, MinecraftProvider minecraftProvider, Type type) {
		super(project, minecraftProvider, type);
	}

	private LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(project);
	}

	@Override
	public void provide() throws Exception {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = ForgeProvider.getForgeCache(project);
		String patchId = "forge-" + forgeVersion + "-";
		minecraftProvider.setJarPrefix(patchId);

		minecraftClientPatchedJar = forgeWorkingDir.resolve("client-patched.jar");
		minecraftServerPatchedJar = forgeWorkingDir.resolve("server-patched.jar");
		minecraftMergedPatchedJar = forgeWorkingDir.resolve("merged-patched.jar");
		minecraftPatchedAtJar = forgeWorkingDir.resolve(type.id + "-at-patched.jar");
		forgeJar = forgeWorkingDir.resolve("forge.jar");

		// forge.jar 能被投影成执行期任务：它的全链（重映射 → 删 binpatches/签名 → 补 log4j 辅助类 →
		// 改造 CoreModManager → 写补丁标记）只读输入 jar，不依赖配置期产物。
		// 投影后本类不再在配置期碰它，下游阶段按路径读（任务依赖由消费方接线）。
		forgeJarProjected = registerLegacyForgeJarTask();

		// 无锁快路径：产物齐备且补丁版本最新时，本次仅做内存配置，不触碰共享缓存，不取锁
		if (!needsWork()) {
			dirty = false;
			return;
		}

		withPatchedLock(() -> {
			// 锁内二次确认：等锁期间其它进程可能已完成生产。本方法不删除任何共享产物，
			// 需要重建时由 checkCache 的返回值表达，缺失或损坏的产物由 remapJar 的可复用性判定按件补齐
			dirty = checkCache();
			return null;
		});
	}

	/**
	 * {@return 是否需要重建 legacy patched jar}.
	 *
	 * <p>产物缺失或 Loom 补丁版本过期即需要工作。manifest 读取遇到损坏文件（多进程并发下的
	 * 半截产物）时按需要工作处理，进入锁内走完整判定。
	 */
	private boolean needsWork() {
		if (getExtension().refreshDeps() || Stream.of(getGlobalCaches()).anyMatch(jar -> !isReusableJar(jar))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(minecraftPatchedAtJar) || !isPatchedJarUpToDate(forgeJar);
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * 锁内二次确认：判定现有产物能否复用，并返回本次是否需要整链重建.
	 *
	 * <p>与父类一致，本方法不再删除共享产物：删除会让锁外只做存在性判定的读方拿到缺失文件，
	 * 也会让其它工作树互相触发重建。legacy 链没有单一版本标记产物，任一件缺失即无法确认
	 * 其余产物与当前算法同代，因此按整链重建处理（各件仍以「临时文件 + 原子落位」重新生成）。
	 *
	 * @return 是否需要整链重建
	 */
	@Override
	protected boolean checkCache() throws IOException {
		if (getExtension().refreshDeps()) {
			// 显式刷新（--refresh-dependencies / -Dloom.refresh）：强制整链重建
			return true;
		}

		if (Stream.of(getGlobalCaches()).anyMatch(jar -> !isReusableJar(jar))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(minecraftPatchedAtJar) || !isPatchedJarUpToDate(forgeJar);
		} catch (IOException e) {
			// manifest 读不出来：共享产物被外部破坏，同样按整链重建处理
			return true;
		}
	}

	protected Path[] getGlobalCaches() {
		List<Path> files = new ArrayList<>(Arrays.asList(
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftMergedPatchedJar,
				minecraftPatchedAtJar,
				forgeJar
		));

		// 与父类按 providesClientJar() 过滤 minecraftClientExtra 同一个道理：minecraftMergedPatchedJar
		// 只在 type == MERGED 时生成（见 remapJar 与 minecraftPatchedAtJar 的 switch），
		// 若它始终参与 needsWork()/checkCache() 判定，非 MERGED 配置下「该产物永远不存在」会让判定恒为真、
		// 每轮都整链重建。当前唯一构造点始终传 MERGED，故这里是防御性对齐，不改变现有行为。
		if (type != Type.MERGED) {
			files.remove(minecraftMergedPatchedJar);
		}

		return files.toArray(Path[]::new);
	}

	@Override
	protected void produceIntermediateJarIfMissing() throws Exception {
		// legacy 链的产物（forge.jar / client-patched.jar / server-patched.jar 等）在 remapJar 阶段生成，
		// provide() 只做路径初始化与「是否需要重建」的判定，因此这里两步都要走一遍（均为幂等快路径）
		provide();

		try (var serviceFactory = new ScopedServiceFactory()) {
			remapJar(serviceFactory);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>legacy 的 named forge jar 由 {@code patchForge} 内的 srg→named 重映射产出，
	 * 位置固定为 {@code forgeWorkingDir/forge.jar}，在 {@link #provide()} 阶段就已写入字段
	 * （与重映射是否已执行无关）。因此本方法只负责给路径，**是否真的产出**由调用方用
	 * {@code Files.isRegularFile} 自行判断——基类的按路径推导版本也是同样的契约。
	 */
	@Override
	public Path getNamedForgeJar() {
		return forgeJar;
	}

	@Override
	public void remapJar(ServiceFactory serviceFactory) throws Exception {
		// 整链已投成执行期任务（见 registerLegacyForgeJarTask）：配置期不再生产、也不在配置期读这批产物。
		// 任务图负责顺序与就绪，下游消费方经 registerTaskProducedArtifact 接上任务依赖。
		if (forgeJarProjected) {
			return;
		}

		// 无锁快路径：provide 已判定产物齐备（needsWork=false → dirty=false）时，本方法的全链
		// 可复用性判定（存在 + 不是空 zip／截断文件）都会短路，无需取锁；仅当 provide 判定需要工作才进锁。
		// 注意必须带上 !dirty：显式刷新与「补丁版本过期」不再通过删除产物表达，
		// 若这里只判存在性，就会把 provide 设好的强制重建信号清掉。
		if (!dirty
				&& Stream.of(forgeJar, minecraftClientPatchedJar, minecraftServerPatchedJar).allMatch(MinecraftLegacyPatchedProvider::isReusableJar)
				&& (type != Type.MERGED || isReusableJar(minecraftMergedPatchedJar))
				&& isReusableJar(minecraftPatchedAtJar)) {
			return;
		}

		withPatchedLock(() -> {
			// 锁内二次确认：等锁期间其它进程可能已完成生产；所有产物均以「临时文件 + 原子落位」生成
			if (forgeJarProjected) {
				// 投影形态：forge.jar 由执行期任务产出，本方法不在配置期读或写它。
				// 下游阶段（patchJars/mergeJars/accessTransform）仍留在配置期，它们按路径读该产物，
				// 任务依赖在消费方那边统一接线（见 registerLegacyForgeJarTask）。
			} else if (dirty || !isReusableJar(forgeJar)) {
				dirty = true;
				publishAtomically(forgeJar, output -> patchForge(output, serviceFactory));
			}

			if (dirty || !isReusableJar(minecraftClientPatchedJar) || !isReusableJar(minecraftServerPatchedJar)) {
				dirty = true;
				patchJars();
			}

			if (type == Type.MERGED && (dirty || !isReusableJar(minecraftMergedPatchedJar))) {
				dirty = true;
				publishAtomically(minecraftMergedPatchedJar, this::mergeJars);
			}

			if (dirty || !isReusableJar(minecraftPatchedAtJar)) {
				dirty = true;
				Path minecraftPatchedJar = switch (type) {
				case CLIENT_ONLY -> minecraftClientPatchedJar;
				case SERVER_ONLY -> minecraftServerPatchedJar;
				case MERGED -> minecraftMergedPatchedJar;
				};
				// AT、Forge 文件复制与补丁版本标记全部在临时文件上完成，最后原子落位
				publishAtomically(minecraftPatchedAtJar, output -> {
					accessTransform(minecraftPatchedJar, output);
					walkFileSystems(forgeJar, output, (path) -> true, MinecraftPatchedProvider::copyReplacing);
					applyLoomPatchVersion(output);
				});
			}

			return null;
		});
	}

	/**
	 * 把 {@code forge.jar} 的生产登记成执行期任务.
	 *
	 * <p>与配置期 {@code patchForge} 共用同一条链（重映射 → 删 binpatches/签名 → 补 log4j 辅助类 →
	 * 改造 CoreModManager → 写补丁标记），产物路径也完全一致；区别只在文件什么时候写出。
	 *
	 * <p>本方法只做「配置期已知量 → 任务输入」的映射，不写任何产物。CURRENT_LOOM_PATCH_VERSION 和
	 * forge universal jar 都是配置期就确定的纯值 / 已解析路径；唯一可能由执行期产出的是 vanilla jar
	 * （client/server）与 srg tiny，两者都按路径声明并显式接线依赖。
	 *
	 * @return 是否登记成功（投影形态）
	 */
	private boolean registerLegacyForgeJarTask() {
		final var extension = getExtension();

		final String taskPath = RemapMinecraftTaskRegistry.claim(project, forgeJar, Map.of(
				"stage", "legacy-forge-jar",
				"forgeVersion", extension.getForgeProvider().getVersion().getCombined(),
				"unobfuscated", Boolean.toString(extension.isUnobfuscatedForge())
		), () -> project.getTasks().register(GenerateLegacyForgeJarTask.NAME, GenerateLegacyForgeJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Produces the named legacy Forge jar for %s".formatted(
					extension.getMinecraftProvider().minecraftVersion()));
			task.getOutputJar().set(forgeJar.toFile());
			task.getForgeUniversalJar().set(extension.getForgeUniversalProvider().getForge().toPath().toFile());

			// 四列 tiny（official/srg/intermediary/named），方向 official -> named。
			// unobfuscated Forge 没有映射配置，与配置期 remapForgeToNamed 的早返回同判据。
			if (!extension.isUnobfuscatedForge()) {
				task.getMappings().set(extension.getMappingConfiguration().tinyMappingsWithSrg.toFile());
				// 映射产物由迁移任务/合并产出，按路径声明输入不带任务依赖，必须显式接线
				extension.getMappingConfiguration().addMappingsProducerDependency(task);
			}

			// remap 的外部 classpath：未打补丁的干净 vanilla jar（只读成员签名，不输出）
			Arrays.stream(minecraftClassPath()).forEach(task.getMinecraftClasspath()::from);

			// 旧版 Forge 依赖 log4j-core 2.0-beta9 的 helpers 包；不在依赖里时不设置
			project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES).getFiles().stream()
					.map(File::toPath)
					.filter(it -> it.getFileName().toString().equals("log4j-core-2.0-beta9.jar"))
					.findAny()
					.ifPresent(jar -> task.getLog4jBeta9Jar().set(jar.toFile()));

			task.getPatchVersion().set(MinecraftPatchedProvider.CURRENT_LOOM_PATCH_VERSION);
		})).taskPath();

		if (taskPath == null) {
			return false;
		}

		// 下游阶段（patchJars / mergeJars / accessTransform）仍在配置期，它们按路径读 forge.jar：
		// 登记成本 provider 的产物，消费方经 addProducerDependency 接上同一条任务依赖
		minecraftProvider.registerTaskProducedArtifact(forgeJar, new RemapMinecraftTaskRegistry.Producer(forgeJar, taskPath));
		project.getLogger().lifecycle("legacy Forge 的 forge.jar 由执行期任务承担：{}", taskPath);

		// 后续三段：client/server 打补丁 → 合并 → AT（含合入 forge.jar 与写就绪标记）。
		// 它们以前留在配置期、按路径读 forge.jar；现在整链都是任务，靠任务依赖串起来。
		final String[] patchedPaths = registerClientServerPatchedTasks(taskPath);
		final String mergedPath = registerMergeTask(patchedPaths[0], patchedPaths[1]);
		registerAccessTransformTask(mergedPath, taskPath);

		return true;
	}

	/**
	 * 登记 client / server 两件打补丁产物（两个任务实例，同一个类型）.
	 *
	 * @param forgeJarTaskPath forge.jar 的产出任务；两件产物都要依赖它（remap 的 classpath 就是它重映射后的结果）
	 * @return {client, server} 两个产出任务路径；合并任务必须两个都依赖，
	 *         否则 server 侧不会被调度，合并时会因输入不存在而失败
	 */
	private String[] registerClientServerPatchedTasks(String forgeJarTaskPath) {
		final var extension = getExtension();
		final PatchProvider patchProvider = extension.getPatchProvider();
		final ForgeExternalToolService.Options binpatcher = createBinpatcherTool();
		final Path serverJar = extension.getMinecraftProvider().getMinecraftServerJar().toPath();
		final Path clientJar = extension.getMinecraftProvider().getMinecraftClientJar().toPath();

		final String serverTask = claimPatchedTask(PatchLegacyMinecraftJarsTask.NAME_SERVER, minecraftServerPatchedJar,
				serverJar, patchProvider.extractServerPatches(), binpatcher, forgeJarTaskPath);
		final String clientTask = claimPatchedTask(PatchLegacyMinecraftJarsTask.NAME_CLIENT, minecraftClientPatchedJar,
				clientJar, patchProvider.extractClientPatches(), binpatcher, forgeJarTaskPath);

		minecraftProvider.registerTaskProducedArtifact(minecraftServerPatchedJar,
				new RemapMinecraftTaskRegistry.Producer(minecraftServerPatchedJar, serverTask));
		minecraftProvider.registerTaskProducedArtifact(minecraftClientPatchedJar,
				new RemapMinecraftTaskRegistry.Producer(minecraftClientPatchedJar, clientTask));

		project.getLogger().lifecycle("legacy Forge 的 patched client/server jar 由执行期任务承担：{} / {}", clientTask, serverTask);
		return new String[] {clientTask, serverTask};
	}

	private String claimPatchedTask(String taskName, Path outputJar, Path cleanJar, Path patches,
			ForgeExternalToolService.Options binpatcher, String forgeJarTaskPath) {
		return RemapMinecraftTaskRegistry.claim(project, outputJar, Map.of(
				"stage", "legacy-patched-jar",
				"taskName", taskName,
				"clean", cleanJar.toAbsolutePath().normalize().toString()
		), () -> project.getTasks().register(taskName, PatchLegacyMinecraftJarsTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Patches the legacy Minecraft jar (%s)".formatted(taskName));
			task.getOutputJar().set(outputJar.toFile());
			task.getCleanJar().set(cleanJar.toFile());
			task.getPatches().set(patches.toFile());
			task.getBinpatcherTool().set(binpatcher);
			task.dependsOn(forgeJarTaskPath);
		})).taskPath();
	}

	/** 登记合并任务（client-patched + server-patched → merged-patched）.
	 *
	 * @param clientTaskPath client 侧产出任务
	 * @param serverTaskPath server 侧产出任务（两者都要依赖：合并同时读这两件产物）
	 * @return 合并产物的产出任务路径
	 */
	private String registerMergeTask(String clientTaskPath, String serverTaskPath) {
		final String taskPath = RemapMinecraftTaskRegistry.claim(project, minecraftMergedPatchedJar, Map.of(
				"stage", "legacy-merged-patched-jar"
		), () -> project.getTasks().register(MergeLegacyPatchedJarTask.NAME, MergeLegacyPatchedJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Merges the legacy patched Minecraft jars");
			task.getOutputJar().set(minecraftMergedPatchedJar.toFile());
			task.getClientPatchedJar().set(minecraftClientPatchedJar.toFile());
			task.getServerPatchedJar().set(minecraftServerPatchedJar.toFile());
			// 两个产出方都要接：只接 client 会让 server 侧不被调度（实测：合并时报 server-patched.jar 不存在）。
			// 用 project.provider 惰性取 TaskProvider：字符串 dependsOn 对「非根项目里的任务」
			// 找不到时是静默忽略（实测 server 侧的字符串依赖没生效，dry-run 里该任务根本没入图）。
			// 惰性取值：本回调在 claim 里执行，两个产出任务可能尚未注册完，直接 named() 会抛未知任务
			task.dependsOn(task.getProject().provider(() -> List.of(clientTaskPath, serverTaskPath)));
		})).taskPath();

		minecraftProvider.registerTaskProducedArtifact(minecraftMergedPatchedJar,
				new RemapMinecraftTaskRegistry.Producer(minecraftMergedPatchedJar, taskPath));
		project.getLogger().lifecycle("legacy Forge 的 merged patched jar 由执行期任务承担：{}", taskPath);
		return taskPath;
	}

	/** 登记 AT 任务（merged-patched + AT + 合入 forge.jar + 写标记 → at-patched）.
	 *
	 * @param mergedTaskPath 合并产物任务
	 * @param forgeJarTaskPath forge.jar 任务（要合进最终产物）
	 */
	private void registerAccessTransformTask(String mergedTaskPath, String forgeJarTaskPath) {
		final var extension = getExtension();
		final AccessTransformerService.Tool atTool = AccessTransformerService.resolveTool(project);

		final String taskPath = RemapMinecraftTaskRegistry.claim(project, minecraftPatchedAtJar, Map.of(
				"stage", "legacy-at-patched-jar",
				"type", type.id
		), () -> project.getTasks().register(AccessTransformForgeJarTask.NAME + "Legacy", AccessTransformForgeJarTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Access transforms the legacy patched Minecraft jar");
			task.getOutputJar().set(minecraftPatchedAtJar.toFile());
			task.getPatchedIntermediateJar().set(minecraftMergedPatchedJar.toFile());
			task.getForgeUserdevJar().set(extension.getForgeUserdevProvider().getUserdevJar().toPath().toFile());

			// legacy 的 AT 规则写 SRG 名，而作用对象是 notch 后的合并 jar：必须翻译
			task.getMappings().set(extension.getMappingConfiguration().tinyMappingsWithSrg.toFile());
			task.getAtSourceNamespace().set("srg");
			task.getAtTargetNamespace().set("official");
			extension.getMappingConfiguration().addMappingsProducerDependency(task);

			extension.getForgeUserdevProvider().getConfig().ats().visit(directory -> {
				task.getAtDirectory().set(directory);
				return null;
			}, paths -> {
				task.getAtPaths().set(paths);
				return null;
			});

			task.getAtMainClass().set(atTool.mainClass());
			task.getAtClasspath().from(atTool.classpath());
			task.getAtJavaExecutable().set(atTool.javaExecutable());
			task.getAtVerboseStdout().set(atTool.verboseStdout());
			task.getAtVerboseStderr().set(atTool.verboseStderr());

			// legacy 专用：合入 forge.jar 并写就绪标记（该 jar 不再经下游 remap 任务）
			task.getMergeForgeJar().set(forgeJar.toFile());
			task.getPatchVersion().set(MinecraftPatchedProvider.CURRENT_LOOM_PATCH_VERSION);

			task.dependsOn(mergedTaskPath, forgeJarTaskPath);
		})).taskPath();

		minecraftProvider.registerTaskProducedArtifact(minecraftPatchedAtJar,
				new RemapMinecraftTaskRegistry.Producer(minecraftPatchedAtJar, taskPath));
		project.getLogger().lifecycle("legacy Forge 的 at-patched jar 由执行期任务承担：{}", taskPath);
	}

	private void patchForge(Path output, ServiceFactory serviceFactory) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching forge");

		// output 是本次独占的临时文件：复制与全部加工都在它上面完成，最后原子落位
		Files.copy(getExtension().getForgeUniversalProvider().getForge().toPath(), output);

		// legacy Forge 的 universal jar 是按 SRG 名发布的（例如 GameData$ClearableObjectIntIdentityMap
		// 的字节码是 `extends fd`）。而 dev 运行期 CoreModManager.handleLaunch 用
		// getClassBytes("net.minecraft.world.World") 能否命中来判定 deobfuscatedEnvironment ；
		// loom 提供的 Minecraft jar 类名是 named(MCP) 名，故必然判定为 true，于是
		// DeobfuscationTransformer.shouldTransform 对 net.minecraft.* 与 net.minecraftforge.* 全部豁免
		// （EXEMPT_DEV），运行期完全不做反混淆。若 forge.jar 仍保持 SRG 名，则：
		//   - `extends fd` 无人翻译 → IllegalAccessError；
		//   - AccessTransformer 的 `field_148749_a` 与 named 名 `identityMap` 逐字比较不等 → AT 静默失效；
		//   - DeobfuscationTransformer.unmapClassName 反查出 SRG 名后 getClassBytes 取不到字节
		//     → defineClass(null) 在 LaunchClassLoader 抛 NPE，该类被钉进 invalidClasses。
		// 因此这里必须先把 forge.jar 从 SRG 重映射到 named，与 Minecraft jar 的命名体系对齐。
		remapForgeToNamed(output, serviceFactory);

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(output, false)) {
			// For the development environment, we need to remove the binpatches, otherwise forge will try to re-apply them
			Files.delete(fs.get().getPath("binpatches.pack.lzma"));

			// We also need to remove Forge's jar signature data
			// Based off https://github.com/FabricMC/tiny-remapper/blob/7f341dcbf1bae754ba992f0b0f127566f347f37f/src/main/java/net/fabricmc/tinyremapper/MetaInfFixer.java
			Files.walkFileTree(fs.get().getPath("META-INF"), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
					String fileName = path.getFileName().toString();

					if (fileName.endsWith(".SF") || fileName.endsWith(".DSA") || fileName.endsWith(".RSA") || fileName.endsWith(".EC") || fileName.startsWith("SIG-")) {
						Files.delete(path);
					} else if (path.getNameCount() == 2 && fileName.equals("MANIFEST.MF")) {
						Manifest manifest = new Manifest();

						try (InputStream is = Files.newInputStream(path)) {
							manifest.read(is);
						}

						for (Iterator<Attributes> it = manifest.getEntries().values().iterator(); it.hasNext(); ) {
							Attributes attrs = it.next();

							for (Iterator<Object> it2 = attrs.keySet().iterator(); it2.hasNext(); ) {
								Attributes.Name attrName = (Attributes.Name) it2.next();
								String name = attrName.toString();

								if (name.endsWith("-Digest") || name.contains("-Digest-") || name.equals("Magic")) {
									it2.remove();
								}
							}

							if (attrs.isEmpty()) it.remove();
						}

						try (OutputStream os = Files.newOutputStream(path)) {
							manifest.write(os);
						}
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}

		// Older versions of Forge rely on utility classes from log4j-core 2.0-beta9 but we'll upgrade the runtime to a
		// release version (so we can use the LoggerNamePatternSelector from fabric-log4j-util) where some of those
		// classes have been moved from a `helpers` to a `utils` package.
		// To allow Forge to work regardless, we'll re-package those helper classes into the forge jar.
		Path log4jBeta9 = project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES)
				.getFiles()
				.stream()
				.map(File::toPath)
				.filter(it -> it.getFileName().toString().equals("log4j-core-2.0-beta9.jar"))
				.findAny()
				.orElse(null);
		if (log4jBeta9 != null) {
			Predicate<Path> isHelper = path -> path.startsWith("/org/apache/logging/log4j/core/helpers");
			walkFileSystems(log4jBeta9, output, isHelper, MinecraftPatchedProvider::copyReplacing);
		}

		// While Forge will discover mods on the classpath, it won't do the same for ATs, coremods or tweakers.
		// ForgeGradle "solves" this problem using a huge, gross hack (GradleForgeHacks and related classes), and only
		// for ATs and coremods, not tweakers.
		// No clue why FG went the hack route when it's the same project and they could have just added first-party
		// support for loading both from the classpath right into Forge (it's even really simply to do).
		// We'll have none of those hacks and instead patch first-party support into Forge.
		ZipUtils.transform(output, Stream.of(new Pair<>(CoreModManagerTransformer.FILE, original -> {
			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new CoreModManagerTransformer(writer), 0);
			return writer.toByteArray();
		})));

		// 补丁版本标记是就绪标记：必须在原子落位之前写入临时文件，读方才能「见到即完整」
		applyLoomPatchVersion(output);

		logger.lifecycle(":patched forge in " + stopwatch.stop());
	}

	/**
	 * 把 legacy Forge 的 universal jar 重映射到 named(MCP) 名.
	 *
	 * <p>dev 运行期 {@code CoreModManager.handleLaunch} 以「{@code getClassBytes("net.minecraft.world.World")}
	 * 是否命中」判定 {@code deobfuscatedEnvironment}；loom 的 Minecraft jar 类名是 named 名，
	 * 故必然判定为 true，此时 {@code DeobfuscationTransformer.shouldTransform} 对
	 * {@code net.minecraft.*} 与 {@code net.minecraftforge.*} 全部豁免（{@code EXEMPT_DEV}），
	 * 运行期不再做任何反混淆。因此 forge.jar 若不事先对齐到 named 名，它对这些 MC 符号的引用
	 * 就无人翻译：类引用报 {@code IllegalAccessError}，字段引用报 {@code NoSuchFieldError}
	 * （例如 {@code ForgeHooksClient} 引用 {@code DefaultVertexFormats} 的 notch 字段 {@code o}）。
	 *
	 * <p>映射用四列 tiny（{@code official / srg / intermediary / named}），方向 {@code official -> named}。
	 * 只用 srg→named 的 V1 srg 不够——它没有 notch 列，翻不出 Forge 字节码里的 notch 字段名。
	 *
	 * @param forgeJar 本次独占的临时产物（可原地替换）
	 */
	private void remapForgeToNamed(Path forgeJar, ServiceFactory serviceFactory) throws Exception {
		if (getExtension().isUnobfuscatedForge()) {
			// unobfuscated Forge 没有映射配置，其 universal jar 本就是 named 名，无需重映射
			logger.lifecycle(":skip forge remap (unobfuscated forge)");
			return;
		}

		// 映射源必须用四列 tiny（official/srg/intermediary/named），不能用 srg→named 的 V1 srg：
		// Forge universal 的字节码里既引用 srg 名（方法/字段的 field_xxx 形式），也引用 notch 名
		// （如 ForgeHooksClient 引用 DefaultVertexFormats 的字段 `o`）。V1 srg 里没有 notch 列，
		// `o` 翻不到 named 名，运行期就抛 NoSuchFieldError: o。四列 tiny 能 notch→srg→named 链式翻译。
		final Path tinyMappings = getExtension().getMappingConfiguration().tinyMappingsWithSrg;

		if (tinyMappings == null || !Files.exists(tinyMappings)) {
			logger.lifecycle(":skip forge remap (srg tiny mappings unavailable)");
			return;
		}

		logger.lifecycle(":remapping forge (TinyRemapper, official -> named), mappings=" + tinyMappings.getFileName());
		Stopwatch stopwatch = Stopwatch.createStarted();

		// 重映射结果先写同目录私有副本，完整后再原子替换 forgeJar：
		// TinyRemapper 不能读写同一个文件，且中途失败时不能破坏已复制好的 forge.jar 内容。
		final Path remapped = forgeJar.resolveSibling(forgeJar.getFileName() + ".srg2named.tmp");

		boolean moved = false;

		try {
			// 关键：先把源 jar 复制成目标文件。OutputConsumerPath 对「不存在的路径」会建目录
			// （它把 jar 当 zipfs 挂载），随后 Files.move 就会把一个目录搬到 forgeJar 位置，
			// 外层 AtomicFiles 再落位时抛 DirectoryNotEmptyException（实测）。预创建成文件即可避开。
			Files.copy(forgeJar, remapped, StandardCopyOption.REPLACE_EXISTING);

			TinyRemapper remapper = TinyRemapper.newRemapper()
					.withMappings(TinyRemapperHelper.create(tinyMappings, "official", "named", true))
					// Forge jar 只含 Forge 自己的类，它引用的 MC 类（如 DefaultVertexFormats）
					// 不在输入里。默认 TinyRemapper 只按「输入类的成员」建映射视图，
					// 外部类的成员引用不落到 named 名上——实测表现为类名与描述符都翻对了、
					// 只有成员名仍是 notch 名（ForgeHooksClient 引用 cdy.o，运行期 NoSuchFieldError: o）。
					.resolveMissing(true)
					.keepInputData(true)
					// 字段映射按「类名+字段名」匹配、不比较描述符：Forge 字节码里字段描述符
					// 仍是 notch 形式（Lceb;），与映射表里的描述符列不一致时会被判为不匹配而跳过。
					//
					// 放宽描述符会让人担心「同类里两个同名字段被互相误译」。这在 MCP/srg 体系下不会发生：
					// 实测 1.12.2 / 1.20.1 / 1.21.1 的 srg 映射中，「同类同名字段但描述符不同」的组数
					// 均为 0（字段名在混淆表里本就唯一）。若将来换到不保证这一点的映射源，这个开关要重新评估。
					// 注意：本方法只在 legacy 车道上被调用，注释化的放宽不应被其它重映射复用。
					.ignoreFieldDesc(true)
					.renameInvalidLocals(true)
					.rebuildSourceFilenames(true)
					.build();

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(remapped).build()) {
				outputConsumer.addNonClassFiles(forgeJar, NonClassCopyMode.FIX_META_INF, remapper);

				// 关键：把 Minecraft jar 作为 classpath 交给 remapper（不输出）。
				// Forge jar 只含 Forge 自己的类，它引用的 MC 类（DefaultVertexFormats 等）不在输入里；
				// 没有这些类的定义，TinyRemapper 建不出它们的方法/字段继承视图，于是只翻类名与描述符、
				// 不翻成员名——实测表现为 `cdy.o` → `DefaultVertexFormats.o`（字段名仍是 notch 名），
				// 运行期抛 NoSuchFieldError: o。readClassPath 正是为「外部依赖」准备的入口。
				remapper.readClassPath(minecraftClassPath());

				InputTag inputTag = remapper.createInputTag();
				remapper.readInputsAsync(inputTag, forgeJar);
				remapper.apply(outputConsumer, inputTag);
			} finally {
				remapper.finish();
			}

			Files.move(remapped, forgeJar, StandardCopyOption.REPLACE_EXISTING);
			moved = true;
		} finally {
			// 只在「未成功落位」时清理临时副本：move 成功后该路径已不存在，
			// 再动它只会把一个成功的重映射变成失败（实测 DirectoryNotEmptyException）。
			if (!moved) {
				deleteRecursivelyQuietly(remapped);
			}
		}

		logger.lifecycle(":remapped forge in " + stopwatch.stop());
	}

	/**
	 * {@return 供重映射解析外部类用的 Minecraft jar 列表}.
	 *
	 * <p>这里给的是**未被 Forge 打过补丁**的干净 MC jar：重映射发生在 {@code patchForge} 阶段，
	 * 彼时 {@code client-patched.jar} / {@code server-patched.jar} 尚未产出（它们在随后的
	 * {@code patchJars} 里才生成）。而重映射只需要 MC 类的成员签名，干净 jar 已足够。
	 */
	private Path[] minecraftClassPath() {
		final MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		return Stream.of(
						minecraftProvider.getMinecraftClientJar(),
						minecraftProvider.getMinecraftServerJar())
				.filter(Objects::nonNull)
				.map(jar -> jar.toPath())
				.toArray(Path[]::new);
	}

	/**
	 * 尽力清理一个临时路径；失败只记日志，不向外抛.
	 *
	 * <p>重映射产物可能已被 zipfs 打开成目录形态，直接 {@code Files.deleteIfExists} 会因
	 * 「目录非空」失败。这里按「是目录就逐层删」处理，且任何异常都不影响主流程——
	 * 清理失败最多留下一个临时文件，不能让成功的重映射因此变成失败。
	 */
	private void deleteRecursivelyQuietly(Path path) {
		try {
			if (!Files.exists(path)) {
				return;
			}

			if (Files.isDirectory(path)) {
				try (Stream<Path> walk = Files.walk(path)) {
					walk.sorted(Comparator.reverseOrder()).forEach(child -> {
						try {
							Files.deleteIfExists(child);
						} catch (IOException ignored) {
							// 尽力而为：单个子项失败不阻断其余清理
						}
					});
				}
			} else {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			logger.warn("清理临时重映射产物失败（不影响构建结果）：{}", path, e);
		}
	}

	private void patchJars() throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		// 两件产物各自「临时文件 + 原子落位」：任一件失败都不会在共享缓存里留下半截 jar，
		// 也不会破坏另一件已发布的有效产物
		publishAtomically(minecraftServerPatchedJar, output -> patchJars(minecraftProvider.getMinecraftServerJar().toPath(), output, patchProvider.extractServerPatches()));
		publishAtomically(minecraftClientPatchedJar, output -> patchJars(minecraftProvider.getMinecraftClientJar().toPath(), output, patchProvider.extractClientPatches()));

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	@Override
	protected void patchJars(Path clean, Path output, Path patches) throws Exception {
		super.patchJars(clean, output, patches);

		// Patching only preserves affected classes, everything else we need to copy manually
		copyMissingClasses(clean, output);
		walkFileSystems(clean, output, file -> !file.toString().endsWith(".class"), MinecraftPatchedProvider::copyReplacing);

		// Workaround Forge patches apparently violating the JVM spec (see ParameterAnnotationsFixer for details)
		modifyClassesStatic(output, ParameterAnnotationsFixer::new);
	}

	private void mergeJars(Path output) throws Exception {
		logger.info(":merging jars");
		Stopwatch stopwatch = Stopwatch.createStarted();

		// output 是本次独占的临时文件，合并与后续类改写都在它上面完成，最后原子落位
		try (JarMerger jarMerger = new JarMerger(minecraftClientPatchedJar.toFile(), minecraftServerPatchedJar.toFile(), output.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		// The JarMerger adds Sided annotations but so do the Forge patches. The latter doesn't require extra
		// dependencies beyond Forge, so we'll keep those and convert any non-redundant Fabric ones.
		modifyClassesStatic(output, SideAnnotationMerger::new);

		logger.info(":merged jars in " + stopwatch);
	}

	/**
	 * 对合并产物应用 {@code SideAnnotationMerger}（供执行期任务复用）.
	 *
	 * <p>合并后的 jar 会同时带 JillMerger 写的 Fabric 风格 Sided 注解与 Forge 补丁自己写的，
	 * 前者中非冗余的要转换成 Forge 形式、冗余的直接丢弃（详见该 visitor 的说明）。
	 *
	 * @param jarFile 合并后的 jar（就地修改）
	 */
	public static void modifySideAnnotations(Path jarFile) throws Exception {
		modifyClassesStatic(jarFile, SideAnnotationMerger::new);
	}

	/**
	 * 对打完补丁的 jar 应用 {@code ParameterAnnotationsFixer}（供执行期任务复用）.
	 *
	 * <p>Forge 补丁产出的 class 里，{@code Runtime[In]VisibleParameterAnnotation.num_parameters}
	 * 可能小于方法形参个数（最后一个参数无注解时），会让编译器读到表尾而报错。
	 * 这个 visitor 忽略原值、让 ASM 按签名重算（详见内部类的说明）。
	 *
	 * @param jarFile 打完补丁的 jar（就地修改）
	 */
	public static void modifyParameterAnnotations(Path jarFile) throws Exception {
		modifyClassesStatic(jarFile, ParameterAnnotationsFixer::new);
	}

	/**
	 * 用 ASM 逐类改写一个 jar 内的 class（与配置期 {@code modifyClasses} 同一条实现）.
	 *
	 * <p>是静态且不读项目模型：它只看 jar 文件与 visitor 工厂，因此执行期任务与配置期路径
	 * 可以共用同一处实现，不会出现两份逐类改写逻辑。
	 *
	 * @param jarFile 待改写的 jar（就地修改）
	 * @param func 每个类构造 visitor 的函数
	 */
	public static void modifyClassesStatic(Path jarFile, Function<ClassVisitor, ClassVisitor> func) throws Exception {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			// best-effort：逐类改写注解，单个类失败时保留该类原样即可，不应该让整个合并流程失败。
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter().tolerateFailures();

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] original = Files.readAllBytes(file);

					ClassReader reader = new ClassReader(original);
					ClassWriter writer = new ClassWriter(reader, 0);
					reader.accept(func.apply(writer), 0);

					byte[] modified = writer.toByteArray();

					if (!Arrays.equals(original, modified)) {
						Files.write(file, modified, StandardOpenOption.TRUNCATE_EXISTING);
					}
				});
			}

			completer.completeToleratingFailures("class modifications in " + jarFile.toAbsolutePath());
		}
	}

	@Override
	public Path getMinecraftIntermediateJar() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Path getMinecraftPatchedIntermediateJar() {
		return forgeJar; // TODO current used only for ATs
	}

	@Override
	public Path getMinecraftPatchedJar() {
		return minecraftPatchedAtJar;
	}

	/**
	 * It seems that Forge patches produce class files which are in violation of the JVM spec. Specifically, the value
	 * of Runtime[In]VisibleParameterAnnotation.num_parameters is by SE8 spec required to match the number of formal
	 * parameters of the method (and may be ignored in favor of directly looking at the method arguments, which is
	 * indeed what the OpenJDK 8 compiler does). Using a smaller value (possible if e.g. the last parameter has no
	 * annotations) will cause the compiler to read past the end of the table, throwing an exception and therefore being
	 * unable to read the class file.
	 * <br>
	 * This class visitor fixes that by ignoring the original num_parameters value, letting the MethodVisitor compute a
	 * new value based on its signature. This will at first produce an invalid count when there are synthetic parameters
	 * but later, during mergeJars, those will be properly offset (enableSyntheticParamsOffset).
	 * <br>
	 * SE8 JVM spec: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.18
	 * Example method affected: RenderGlobal.ContainerLocalRenderInformation(RenderChunk, EnumFacing, int)
	 */
	private static class ParameterAnnotationsFixer extends ClassVisitor {
		private ParameterAnnotationsFixer(ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

			// This issue has so far only been observed with constructors, so we can skip everything else
			if (name.equals("<init>")) {
				methodVisitor = new MethodFixer(methodVisitor);
			}

			return methodVisitor;
		}

		private static class MethodFixer extends MethodVisitor {
			private MethodFixer(MethodVisitor methodVisitor) {
				super(Opcodes.ASM9, methodVisitor);
			}

			@Override
			public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
				// Not calling visitAnnotableParameterCount will cause it to compute its value from the method signature
			}
		}
	}

	private static class SideAnnotationMerger extends ClassVisitor {
		private static final String FABRIC_ANNOTATION_DESCRIPTOR = "Lnet/fabricmc/api/Environment;";
		private static final String FORGE_ANNOTATION_DESCRIPTOR = "Lnet/minecraftforge/fml/relauncher/SideOnly;";
		private static final String FORGE_SIDE_DESCRIPTOR = "Lnet/minecraftforge/fml/relauncher/Side;";

		private static boolean isSideAnnotation(String descriptor) {
			return FABRIC_ANNOTATION_DESCRIPTOR.equals(descriptor) || FORGE_ANNOTATION_DESCRIPTOR.equals(descriptor);
		}

		private boolean visitedAnnotation;

		private SideAnnotationMerger(ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (isSideAnnotation(descriptor)) {
				if (visitedAnnotation) {
					return null;
				}

				visitedAnnotation = true;
				return new FabricToForgeConverter(super.visitAnnotation(FORGE_ANNOTATION_DESCRIPTOR, true));
			}

			return super.visitAnnotation(descriptor, visible);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			return new FieldSideAnnotationMerger(super.visitField(access, name, descriptor, signature, value));
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			return new MethodSideAnnotationMerger(super.visitMethod(access, name, descriptor, signature, exceptions));
		}

		private static class FieldSideAnnotationMerger extends FieldVisitor {
			private boolean visitedAnnotation;

			private FieldSideAnnotationMerger(FieldVisitor fieldVisitor) {
				super(Opcodes.ASM9, fieldVisitor);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (isSideAnnotation(descriptor)) {
					if (visitedAnnotation) {
						return null;
					}

					visitedAnnotation = true;
					return new FabricToForgeConverter(super.visitAnnotation(FORGE_ANNOTATION_DESCRIPTOR, true));
				}

				return super.visitAnnotation(descriptor, visible);
			}
		}

		private static class MethodSideAnnotationMerger extends MethodVisitor {
			private boolean visitedAnnotation;

			private MethodSideAnnotationMerger(MethodVisitor methodVisitor) {
				super(Opcodes.ASM9, methodVisitor);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (isSideAnnotation(descriptor)) {
					if (visitedAnnotation) {
						return null;
					}

					visitedAnnotation = true;
					return new FabricToForgeConverter(super.visitAnnotation(FORGE_ANNOTATION_DESCRIPTOR, true));
				}

				return super.visitAnnotation(descriptor, visible);
			}
		}

		private static class FabricToForgeConverter extends AnnotationVisitor {
			private FabricToForgeConverter(AnnotationVisitor annotationVisitor) {
				super(Opcodes.ASM9, annotationVisitor);
			}

			@Override
			public void visitEnum(String name, String descriptor, String value) {
				super.visitEnum(name, FORGE_SIDE_DESCRIPTOR, value);
			}
		}
	}
}
