/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024-2025 FabricMC
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

package dev.architectury.loom.forge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import dev.architectury.loom.mappings.MappingOption;
import dev.architectury.loom.util.Stopwatch;
import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.pipeline.ManipulateForgeMappingsTask;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public final class ForgeMigratedMappingConfiguration extends MappingConfiguration {
	/**
	 * 就绪标记的「失效」哨兵值.
	 *
	 * <p>重建期间标记必须处于不可用状态，而删除标记文件会让并发读方在「存在性检查 → 读取」之间
	 * 撞上 {@code NoSuchFileException}（{@link #isOutdated} 正是这样分两步读的），故改为原子写入
	 * 一个不可能是合法 hash 的值：合法标记只可能是 {@code Long.toString(hash)} 的十进制表示，
	 * 而 {@link #isOutdated} 用的是字符串比较，因此哨兵必然被判为「需重建」，判定自洽。
	 */
	private static final String INVALIDATED_HASH = "invalidated";
	private final List<MappingsMigrator> migrators = List.of(new FieldMappingsMigrator(), new MethodInheritanceMappingsMigrator());
	private Path hashPath;
	private Path rawTinyMappings;
	private Path rawTinyMappingsWithSrg;
	private Path rawTinyMappingsWithMojang;
	private long hash;

	/** 迁移产物的产出任务路径；未投影（配置期生产）时为 {@code null}. */
	@Nullable
	private String manipulateTaskPath;

	public ForgeMigratedMappingConfiguration(String mappingsIdentifier, Path mappingsWorkingDir) {
		super(mappingsIdentifier, mappingsWorkingDir);
	}

	/**
	 * {@return 不能把 mappings 迁移投影成执行期任务的原因；可以投影时为 {@code null}}.
	 *
	 * <p>与 {@code MinecraftPatchedProvider.provideProjectionBlocker()} 同一风格：每一条都是一个
	 * 「配置期有确定的读者会真读迁移产物」的形态，命中即整段退回配置期路径（那条路径与改造前逐字一致），
	 * 并留下 lifecycle 留痕。
	 *
	 * <p><b>判据必须是 {@code LoomGradleExtension} 的纯函数</b>：同一个判据还要在
	 * {@code MinecraftProvider.projectionBlocker()} 里使用，而后者执行得比 mappings 阶段早得多
	 * （那时 {@code MappingConfiguration} 还不存在）。两处一旦不一致，就会出现「vanilla jar 投影了、
	 * 迁移却留在配置期」这类组合——配置期读者随后会按路径读到执行期才落位的文件。
	 *
	 * <ul>
	 *   <li><b>legacy mixin AP</b>：{@code AnnotationProcessorInvoker.passMixinArguments} 在配置期调用
	 *       {@code MappingConfiguration.getReplacedTarget}，它按内容判据校验平台映射文件
	 *       （Forge 下即 {@code mappings-srg-migrated.tiny}）并读它派生出 {@code mappings-mixin-<ns>.tiny}
	 *       ——那个文件在配置期就要交给注解处理器的编译参数。投影后平台映射文件只在执行期落位，
	 *       该读者会直接以 {@code IllegalStateException} 中断构建（本仓库实测：{@code forge/simpleMixinAp}
	 *       的 {@code mappings-mixin-srg.tiny} 正是配置期写出来的）。</li>
	 * </ul>
	 */
	public static @Nullable String mappingsMigrationProjectionBlocker(LoomGradleExtension extension) {
		if (extension.isLegacyForge()) {
			// legacy Forge 的迁移本就在 manipulateMappings 开头整段返回（补丁在官方命名空间里），
			// 单独列出只是为了让「这一形态不投影」在日志里可见
			return "legacy Forge（1.8-1.16）不做映射迁移";
		}

		if (extension.getMixin().getUseLegacyMixinAp().get()) {
			return "legacy mixin AP 开启：getReplacedTarget 在配置期读平台映射文件并派生 mappings-mixin-<ns>.tiny";
		}

		return null;
	}

	/**
	 * {@return 迁移产物的产出任务路径；未投影时为 {@code null}}.
	 *
	 * <p>供消费方把「我的输入就是你的产出」表达成任务依赖。返回任务**路径**而不是任务实例：
	 * 产出方可能由另一份 Loom classloader 配置（约定插件/included build 各自带一份 Loom），
	 * 把对方的任务实例交过来会在使用处抛 {@link ClassCastException}。
	 */
	public @Nullable String getManipulateMappingsTaskPath() {
		return manipulateTaskPath;
	}

	/**
	 * {@return 迁移产物的产出任务路径；未投影时为 {@code null}}.
	 *
	 * <p>三个映射选项（{@code DEFAULT} / {@code WITH_SRG} / {@code WITH_MOJANG}）在本配置下都指向
	 * 迁移产物，故它们共用同一个产出任务。
	 */
	@Override
	public @Nullable String mappingsProducerTaskPath() {
		return manipulateTaskPath;
	}

	/**
	 * {@return 该映射选项指向的迁移产物是否由执行期任务产出}.
	 *
	 * <p>未投影时（legacy Forge 与 legacy mixin AP）产物在配置期已落位，
	 * {@link MappingConfiguration#getMappingsPath} 的存在性校验照旧生效。
	 */
	@Override
	protected boolean isTaskProducedMappings(MappingOption mappingOption) {
		return manipulateTaskPath != null;
	}

	@Override
	protected void manipulateMappings(Project project, Path mappingsJar) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		// 产出任务路径必须按**本次调用**重新判定，先清空：
		//
		// 本实例经 {@code MappingConfiguration.SHARED_INSTANCES} / {@code SHARED_EARLY} 在 daemon 内跨项目、
		// 跨构建复用（见 {@code MappingConfiguration.create}：命中时直接返回实例、不再调用 {@code setup}，
		// 但调用方随后仍会对它调用 {@code setupPost} → 本方法），而复用的键里**不含**决定「能不能投影」的量
		// （{@code isLegacyForge()} 与 {@code mixin.useLegacyMixinAp}）。于是同一个实例可能先以可投影的形态
		// 注册了任务、再被一个不可投影的项目复用：那条路径在下面**提前 return**，不会重新赋值。
		// 旧值一旦留下，{@code mappingsProducerTaskPath()} 就会把上一次的产出任务报给本次的消费方——
		// 跨构建复用时那个任务在本构建里**并不存在**，消费方的 {@code dependsOn} 会以「找不到任务」失败；
		// {@code isTaskProducedMappings()} 还会连带跳过映射文件的存在性校验。清空后两条路径各自给出自己的答案：
		// 可投影时在下面重新登记（同一构建内重复调用会由 claim 返回同一个任务路径，幂等），不可投影时为 null。
		this.manipulateTaskPath = null;

		if (extension.isLegacyForge()) {
			// Legacy forge patches are in official namespace, so if the type of a field is changed by them, then that
			// is effectively a new field and not traceable to any mapping. Therefore this does not apply to it.
			return;
		}

		final Path forgeCache = ForgeProvider.getForgeCache(project);
		Files.createDirectories(forgeCache);

		boolean hasSrg = extension.shouldGenerateSrgTiny();
		boolean hasMojang = extension.isNeoForge();

		// 状态文件必须与它守护的产物同域：产物都落在 mappings 工作目录下，而 forgeCache 按 MC+Forge
		// 版本共享；若状态文件放在 forgeCache，同版本下不同 mappings 配置会互相覆盖哈希，
		// 导致 shouldMigrate() 与错误的基准比较——要么空跑迁移，要么把过期映射当成有效复用。
		this.hashPath = mappingsWorkingDir().resolve("mappings-migrated.hash");
		this.hash = 1;

		// 只在**首次**调用时捕获「原始 mappings 路径」。
		//
		// 本实例经 {@code MappingConfiguration.SHARED_INSTANCES} 在同一 daemon 内跨构建复用，
		// 而下面会把 tinyMappings* 重写成迁移产物的路径：第二次调用起 this.tinyMappings 已经不是原始
		// mappings，再照抄一次就会把**迁移产物自己**当成输入（迁移器读自己的输出，任务声明的输入也随之
		// 指向错误的文件）。本仓库实测：同一 daemon 内第三次构建起 rawTinyMappings 变成
		// mappings-migrated.tiny，任务输入随即错位。
		if (this.rawTinyMappings == null) {
			this.rawTinyMappings = this.tinyMappings;
			this.rawTinyMappingsWithSrg = this.tinyMappingsWithSrg;
			this.rawTinyMappingsWithMojang = this.tinyMappingsWithMojang;
		}

		Path rawTinyMappingsWithNs = hasSrg ? this.rawTinyMappingsWithSrg : hasMojang ? this.rawTinyMappingsWithMojang : this.rawTinyMappings;

		// 产物路径在两条路径下都必须重写成迁移后的位置：消费方（getMappingsPath / getPlatformMappingFile）
		// 读的是这两个字段，无论迁移由谁生产
		this.tinyMappings = mappingsWorkingDir().resolve("mappings-migrated.tiny");
		this.tinyMappingsWithSrg = mappingsWorkingDir().resolve("mappings-srg-migrated.tiny");
		this.tinyMappingsWithMojang = mappingsWorkingDir().resolve("mappings-mojang-migrated.tiny");
		Path tinyMappingsWithNs = hasSrg ? this.tinyMappingsWithSrg : hasMojang ? this.tinyMappingsWithMojang : this.tinyMappings;

		final String blocker = mappingsMigrationProjectionBlocker(extension);

		if (blocker == null) {
			// 可投影：配置期只做接线，产物由执行期任务写出
			projectManipulateMappingsToTasks(project, extension, forgeCache, this.rawTinyMappings, rawTinyMappingsWithNs, tinyMappingsWithNs, hasSrg, hasMojang);
			return;
		}

		project.getLogger().lifecycle("Forge 的 mappings 迁移保持配置期生产：{}", blocker);

		// 配置期路径：判据、跨进程锁、锁内二次确认、原子发布都与改造前逐字一致。
		// 走到这里时 patched 中间产物必然已在配置期落位——投影判据与本判据同源，
		// 二者非空时 {@code MinecraftPatchedProvider.provide()} 同样走配置期路径。
		final Path patchedIntermediateJar = MinecraftPatchedProvider.get(project).getOrProduceMinecraftPatchedIntermediateJar();

		// 迁移器的中间缓存位于共享的 forge 缓存目录，各自原子发布；其内容只由「原始 mappings + 补丁 jar」
		// 决定，同一输入下结果一致，故无锁写入最多互相覆盖成等价内容，不会让读方看到半截文件。
		// 迁移器返回值参与 hash 计算，构成迁移产物的就绪标记。
		final MappingsMigrator.Inputs inputs = new MappingsMigrator.Inputs(forgeCache, rawTinyMappingsWithNs,
				patchedIntermediateJar, extension.getForgeUniversalProvider().getForge().toPath(),
				extension.getForgeUserdevProvider().getUserdevJar().toPath(), hasSrg, hasMojang,
				extension.refreshDeps(), project.getLogger()::info);

		for (MappingsMigrator migrator : this.migrators) {
			hash = hash * 31 + migrator.setup(inputs);
		}

		// 无锁快路径：就绪标记与各迁移产物齐备且 hash 一致时，本次不含任何共享缓存写入，不取锁。
		// 重建方在整个重建期间把标记置为失效值（见 produceMigratedMappings），故此处不会读到
		// 「标记有效、产物却只迁移了一半」的错位状态。
		if (!isOutdated(extension, hasSrg, hasMojang)) {
			project.getLogger().info(":manipulated {} mappings are up to date", extension.getPlatform().get().id());
			return;
		}

		withMappingsLock(project, extension, () -> {
			// 锁内二次确认：等期间其它进程可能已产出同一 hash 的迁移结果，此时直接复用
			if (isOutdated(extension, hasSrg, hasMojang)) {
				produceMigratedMappings(this.migrators, this.rawTinyMappings, rawTinyMappingsWithNs, this.tinyMappings,
						tinyMappingsWithNs, this.hashPath, this.hash, extension.getPlatform().get().id(), project.getLogger()::info);
			}

			return null;
		});
	}

	/**
	 * 把 mappings 迁移投影成执行期任务.
	 *
	 * <p>配置期只做「配置期已知量 → 任务输入」的映射，不写产物文件：产物有效性交给 Gradle 的 up-to-date
	 * 判定与构建缓存，并发保护交给任务图。产物路径沿用既有位置（{@code <userCache>/<mappingsIdentifier>/}），
	 * 否则 {@code getMappingsPath} / {@code getPlatformMappingFile} 给出的路径会全部悬空。
	 *
	 * <p>输入里的 {@code patchedIntermediateJar} 由补丁链的中间产物任务产出——这正是本步要消灭的那条
	 * 「配置期按路径读执行期产物」依赖：迁移器缓存未命中时读它，配置期读不到，故改为任务输入。
	 */
	private void projectManipulateMappingsToTasks(Project project, LoomGradleExtension extension, Path forgeCache,
			Path rawTinyMappings, Path rawTinyMappingsWithNs, Path tinyMappingsWithNs, boolean hasSrg, boolean hasMojang) {
		final Path migratedMappings = this.tinyMappings.toAbsolutePath().normalize();
		final Path migratedMappingsWithNs = tinyMappingsWithNs.toAbsolutePath().normalize();
		final Path readyMarker = this.hashPath.toAbsolutePath().normalize();
		final Path patchedIntermediateJar = MinecraftPatchedProvider.get(project).getMinecraftPatchedIntermediateJar();
		final Path forgeJar = extension.getForgeUniversalProvider().getForge().toPath();
		final Path userdevJar = extension.getForgeUserdevProvider().getUserdevJar().toPath();

		// 同一产物路径在本构建内只能有一个生产者：同一构建内两个配置相同的子项目会算出同一条路径。
		// 指纹只放**决定内容**的量，refreshDeps 只影响「这轮要不要重建」故不参与。
		this.manipulateTaskPath = RemapMinecraftTaskRegistry.claim(project, migratedMappings, Map.of(
				"stage", "forge-migrated-mappings",
				"mappings", rawTinyMappingsWithNs.toAbsolutePath().normalize().toString(),
				"patchedIntermediateJar", patchedIntermediateJar.toAbsolutePath().normalize().toString(),
				"hasSrg", Boolean.toString(hasSrg),
				"hasMojang", Boolean.toString(hasMojang)
		), () -> project.getTasks().register(ManipulateForgeMappingsTask.NAME, ManipulateForgeMappingsTask.class, task -> {
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.setDescription("Migrates the %s mappings for %s".formatted(
					extension.getPlatform().get().id(), mappingsIdentifier()));
			task.getRawMappings().set(rawTinyMappings.toFile());
			task.getRawMappingsWithNs().set(rawTinyMappingsWithNs.toFile());
			task.getMigratedMappings().set(migratedMappings.toFile());
			task.getMigratedMappingsWithNs().set(migratedMappingsWithNs.toFile());
			task.getReadyMarker().set(readyMarker.toFile());
			task.getMigratorCacheDir().set(forgeCache.toAbsolutePath().toString());
			task.getPatchedIntermediateJar().set(patchedIntermediateJar.toFile());
			task.getForgeJar().set(forgeJar.toFile());
			task.getForgeUserdevJar().set(userdevJar.toFile());
			task.getHasSrg().set(hasSrg);
			task.getHasMojang().set(hasMojang);
			task.getRefreshDeps().set(extension.refreshDeps());
			task.getPlatformId().set(extension.getPlatform().get().id());

			// 迁移器缓存未命中时要读 patched 中间产物：它由补丁链的中间产物任务产出，按路径声明输入
			// 不会带任务依赖，必须显式接线（产出方可能来自另一份 Loom classloader，故用任务路径）
			extension.getMinecraftProvider().addProducerDependency(task, patchedIntermediateJar);
		})).taskPath();

		// 用 Gradle 的 lifecycle 而不是 SLF4J 的 info：默认控制台级别是 LIFECYCLE，
		// 「本次到底走哪条生产路径」必须默认可见，否则回退是静默的
		project.getLogger().lifecycle("Forge 的 mappings 迁移由执行期任务承担：{}", manipulateTaskPath);
	}

	/**
	 * 一次完整的迁移生产：跑迁移器 + 复制/改写 + 落就绪标记.
	 *
	 * <p><b>配置期路径与执行期任务共用这一份实现</b>，因此两条路径的复制顺序、迁移器调用顺序、
	 * 原子落位方式与就绪标记内容逐字一致——否则同一份输入在「投影」与「回退」下会产出不同的 mappings，
	 * 而这种差异不会让任何一方失败，只会静默地换一套映射。
	 *
	 * <p>复制与迁移结果一律原子发布，就绪标记先失效、最后才落位。旧写法直接以共享产物为输出
	 * （先原地覆盖、迁移器再读回并就地重写），读方会看到半截 mappings；而 hash 就绪标记先于内容落位时，
	 * 读方还会读到「标记在、内容半截」的错位状态。
	 *
	 * <p>「单文件原子」不等于「集合原子」：多件产物逐个落位，过程中盘上必然存在「部分新、部分旧」
	 * 的混合集合，此时旧标记（内容与重建前的 hash 相同）看起来依然合法。带 refresh 的重建尤其危险——
	 * 它是唯一「hash 未变却重写全部产物」的路径，其它 {@code refresh=false} 的进程会在整个重建时长内
	 * 通过 {@link #isOutdated} 的 hash 比较直接判定「已就绪」，并据此读入只迁移了一半的 mappings。
	 * 因此重建的第一步就是让标记失效，全部产物落位后才发布真实 hash，维持
	 * 「标记有效 ⟹ 所有产物已就绪」这一不变式；若中途失败，标记留在失效态，下次构建会重建（安全方向）。
	 * 执行期任务路径下并发由任务图保证，但标记照旧写：它位于跨 daemon 共享的缓存目录，
	 * 另一棵工作树若仍在跑改造前的 loom，会读它判断「迁移产物是否与当前输入同代」。
	 *
	 * @param migrators 迁移器（顺序即应用顺序，两条路径必须一致）
	 * @param hash 迁移器 {@code setup} 的返回值算出的哈希（见 {@link #manipulate} 与 {@code manipulateMappings}）
	 */
	private static void produceMigratedMappings(List<MappingsMigrator> migrators, Path rawTinyMappings,
			Path rawTinyMappingsWithNs, Path tinyMappings, Path tinyMappingsWithNs, Path hashPath, long hash,
			String platformId, Consumer<String> info) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		// 先失效标记再碰任何产物：保证重建期间没有读方能凭旧标记把半迁移产物当成就绪产物
		invalidateMigratedMappingsMarker(hashPath);
		AtomicFiles.copy(rawTinyMappings, tinyMappings);
		AtomicFiles.copy(rawTinyMappingsWithNs, tinyMappingsWithNs);

		for (MappingsMigrator migrator : migrators) {
			// 临时文件与产物同目录：跨文件系统的 move 会退化成「复制 + 删除」而不再原子
			final Path path = AtomicFiles.tempSibling(tinyMappings);
			final Path pathWithNs = AtomicFiles.tempSibling(tinyMappingsWithNs);

			try {
				Files.copy(tinyMappings, path);
				Files.copy(tinyMappingsWithNs, pathWithNs);

				List<MappingsMigrator.MappingsEntry> entries = List.of(new MappingsMigrator.MappingsEntry(path), new MappingsMigrator.MappingsEntry(pathWithNs));
				migrator.migrate(entries, info);

				AtomicFiles.move(path, tinyMappings);
				AtomicFiles.move(pathWithNs, tinyMappingsWithNs);
			} finally {
				Files.deleteIfExists(path);
				Files.deleteIfExists(pathWithNs);
			}
		}

		// 就绪标记最后落位：标记先于内容落位会让读方读到「标记在、内容半截」
		AtomicFiles.publish(hashPath, tmp -> Files.writeString(tmp, Long.toString(hash), StandardCharsets.UTF_8));

		info.accept(":manipulated " + platformId + " mappings in " + stopwatch.stop());
	}

	/**
	 * 让迁移产物的就绪标记失效，直到本轮产物全部落位.
	 *
	 * <p>用原子发布写哨兵值而不是删除标记文件：删除会在「存在性检查 → 读取」之间给并发读方
	 * 留下 {@code NoSuchFileException} 窗口，原子写入则保证标记始终存在，内容要么是哨兵、要么是完整 hash。
	 */
	private static void invalidateMigratedMappingsMarker(Path hashPath) throws IOException {
		AtomicFiles.publish(hashPath, tmp -> Files.writeString(tmp, INVALIDATED_HASH, StandardCharsets.UTF_8));
	}

	/**
	 * 执行期任务的入口：跑迁移器准备中间数据，再产出迁移后的 mappings.
	 *
	 * <p>与配置期路径的差别只有「谁触发、谁保证并发」：这里由 Gradle 的任务图保证顺序、
	 * 由 up-to-date 判定与构建缓存保证就绪，故不再取跨进程锁、也不再看就绪标记。
	 * 产物的字节与配置期路径逐字一致（同一份 {@link #produceMigratedMappings} 实现）。
	 *
	 * <p>迁移器的中间缓存仍落在共享的 forge 缓存目录：它们只是「由输入决定的中间数据」的记忆化，
	 * 不参与本任务的 up-to-date 判定（判定只看真实输入与输出），因此无需声明成任务输出。
	 */
	public static void manipulate(MappingsMigrator.Inputs inputs, Path rawTinyMappings, Path tinyMappings,
			Path tinyMappingsWithNs, Path hashPath, String platformId, Consumer<String> info) throws IOException {
		final List<MappingsMigrator> migrators = List.of(new FieldMappingsMigrator(), new MethodInheritanceMappingsMigrator());
		long hash = 1;

		for (MappingsMigrator migrator : migrators) {
			hash = hash * 31 + migrator.setup(inputs);
		}

		produceMigratedMappings(migrators, rawTinyMappings, inputs.rawMappings(), tinyMappings, tinyMappingsWithNs, hashPath, hash, platformId, info);
	}

	/**
	 * 在跨进程锁保护下生产迁移后的 mappings.
	 *
	 * <p>产物位于跨 daemon 共享的缓存目录（不按项目隔离），key 与 lockRoot 和
	 * {@link MappingConfiguration} 生产原始 mappings 时使用的完全一致
	 * （{@code "mappings:" + mappingsIdentifier} + {@code userCache/.locks}），
	 * 因此「重写原始 mappings」与「迁移 mappings」不会交错——否则迁移方可能把刚被覆盖的原始文件复制成迁移产物。
	 */
	private void withMappingsLock(Project project, LoomGradleExtension extension, Callable<Void> action) throws IOException {
		final Path lockRoot = extension.getFiles().getCacheLocks().toPath();
		final String key = "mappings:" + mappingsIdentifier;

		try {
			LoomCacheService.get(project).get().runExclusive(lockRoot, key, LoomCacheService.defaultTimeout(), action);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Could not manipulate mappings for " + mappingsIdentifier, e);
		}
	}

	/**
	 * 迁移产物是否需要重建.
	 *
	 * <p>判据与「标记有效 ⟹ 所有产物已就绪」的不变式自洽：标记缺失、内容为失效哨兵
	 * （见 {@link #INVALIDATED_HASH}，说明有进程正在重建）或与本次算出的 hash 不一致，都判为需重建。
	 */
	private boolean isOutdated(LoomGradleExtension extension, boolean hasSrg, boolean hasMojang) throws IOException {
		if (extension.refreshDeps()) return true;
		if (Files.notExists(this.tinyMappings)) return true;
		if (hasSrg && Files.notExists(this.tinyMappingsWithSrg)) return true;
		if (hasMojang && Files.notExists(this.tinyMappingsWithMojang)) return true;
		if (Files.notExists(this.hashPath)) return true;
		String hashStr = Files.readString(hashPath, StandardCharsets.UTF_8);
		return !Long.toString(this.hash).equals(hashStr);
	}
}
