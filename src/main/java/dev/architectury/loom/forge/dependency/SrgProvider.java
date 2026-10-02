/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2025 FabricMC
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.architectury.loom.forge.tool.ForgeExternalToolService;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.Stopwatch;
import org.cadixdev.lorenz.io.srg.SrgReader;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgWriter;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingContext;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.mappings.GradleMappingContext;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class SrgProvider extends DependencyProvider {
	private static final String INSTALLER_TOOLS_MAIN_CLASS = "net.minecraftforge.installertools.ConsoleTool";

	private Path srg;
	private Boolean isTsrgV2;
	private Path mergedMojangRaw;
	private Path mergedMojangTrimmed;
	// 写入由 CacheEntryLock 串行化，故通常不会并发访问；用并发实现免去未来出现真并发时的隐患
	private static Map<String, Path> mojmapTsrg2Map = new ConcurrentHashMap<>();

	public SrgProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		init(dependency.getDependency().getVersion());

		// srg.tsrg 与 merged mojmap 产物位于跨 daemon 共享的 userCache（不按项目隔离），
		// 同一 MC 版本的多个并发构建会写同一路径；需要生产时取锁串行化（锁内二次确认）。
		if (needsSrgProduction()) {
			produceSrgWithLock(dependency);
		}

		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			isTsrgV2 = isTsrg2FirstLine(reader.readLine());
		}
	}

	/**
	 * {@return 该 tsrg 的首行是否标识 tsrg2 形态}.
	 *
	 * <p>先判空：{@code srg.tsrg} 为空文件（或读到一半被替换）时 {@code readLine()} 返回 null，
	 * 直接 {@code startsWith} 会抛 NPE 而不是走「按形态处理」的分支。
	 * null（含空文件、只剩行终止符的残骸）一律按「非 tsrg2」处理——这与
	 * {@link #needsSrgProduction()} 的口径一致，且是安全方向：
	 * 形态未知时既不去凭空生产 merged mojmap 产物，也不宣称它存在；
	 * 该空产物本身会被 {@link #isReusableTsrg(Path)} 拒绝，从而在下一轮触发重新生产。
	 */
	private static boolean isTsrg2FirstLine(@Nullable String firstLine) {
		return firstLine != null && firstLine.startsWith("tsrg2");
	}

	/**
	 * {@return 是否需要生产 srg 相关产物}.
	 *
	 * <p>无锁快路径：srg.tsrg 缺失或不可复用、要求刷新时直接判定需要；否则读 srg.tsrg 首行判断形态，
	 * 仅 tsrgV2（现代 MC）才存在 merged mojmap 产物，legacy 形态下其缺失属正常，无需生产。
	 *
	 * <p>所有判定都用「可复用」而非「存在」：这些文件位于跨 daemon 共享的 userCache，另一进程可能正在
	 * 就地重建（旧版本 loom）或被中断留下残骸，存在性判定会把残骸当成就绪产物继续用下去。
	 */
	private boolean needsSrgProduction() throws IOException {
		if (refreshDeps() || !isReusableTsrg(srg)) {
			return true;
		}

		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			final String firstLine = reader.readLine();

			if (firstLine == null || !firstLine.startsWith("tsrg2")) {
				return false;
			}
		}

		return !isReusableTsrg(mergedMojangRaw) || !isReusableTsrg(mergedMojangTrimmed);
	}

	/**
	 * {@return 该 tsrg 产物是否可作为输入复用}.
	 *
	 * <p>存在性判定不足以证明内容完整：这些文件位于跨 daemon 共享的 userCache，旧版本 loom 以最终路径为输出
	 * 就地写，进程被中断会留下 0 字节或写到一半的文本；而截断的 tsrg 未必让 {@code MappingReader} 报错，
	 * 结果是静默的错误映射。因此要求「非空、首行有内容、以行终止符结尾」：
	 *
	 * <ul>
	 *     <li>非空且首行有内容：排除「先删后写」中途被杀留下的空文件、只剩换行的残骸；</li>
	 *     <li>以行终止符结尾：tsrg 是逐行文本，写入方写完一行才会收尾该行，被截断的写入停在行中间的概率
	 *     远高于正好停在行尾，这条能拦下绝大部分截断产物。</li>
	 * </ul>
	 *
	 * <p>这三条对正常产物恒真（MCPConfig 的 {@code joined.tsrg}、lorenz 的 {@code TSrgWriter}、
	 * mapping-io 的 TSRG2 writer、InstallerTools 的 {@code MERGE_MAPPING} 产物均满足），
	 * 因此不会把正常产物永久判为不可用、把构建拖进「每次构建都重建」。
	 *
	 * <p>局限：截断恰好停在行边界、或只丢掉尾部若干完整记录时，这三条判据发现不了。要发现它只能做内容哈希
	 * 或全量解析，而 raw+trimmed 合计接近 10MB，每次暖构建都解析会带来秒级开销，故不在此处做。
	 */
	private static boolean isReusableTsrg(Path path) {
		try {
			if (Files.notExists(path) || Files.size(path) == 0) {
				return false;
			}

			try (BufferedReader reader = Files.newBufferedReader(path)) {
				final String firstLine = reader.readLine();

				if (firstLine == null || firstLine.isBlank()) {
					return false;
				}
			}

			final byte[] tail = new byte[1];

			try (InputStream input = Files.newInputStream(path)) {
				input.skipNBytes(Files.size(path) - 1);
				return input.read(tail) == 1 && tail[0] == '\n';
			}
		} catch (IOException e) {
			// 读取失败（例如文件正被其它进程删除/替换）同样按不可复用处理，让调用方重建
			return false;
		}
	}

	/**
	 * 在跨进程锁保护下生产 srg.tsrg 与 merged mojmap 产物.
	 *
	 * <p>缓存位于 {@code userCache}（不按项目隔离），同一 MC 版本的多个 daemon 会指向同一路径；
	 * 由首个取得锁的进程写入，其余进程等待后直接复用。锁内二次确认；srg.tsrg 原子落位，
	 * 保证「文件存在 ⟺ 内容完整」，锁外读方不会看到半截内容。
	 */
	private void produceSrgWithLock(DependencyInfo dependency) {
		final String lockKey = "forge-srg:" + getExtension().getMinecraftProvider().minecraftVersion();
		final Path lockRoot = srg.getParent().resolve(Constants.Cache.LOCKS_DIR);

		try {
			CacheEntryLock.withLock(lockRoot, lockKey, LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：等锁期间可能已被其它进程完成生产
				produceSrg(dependency);
				produceMergedMojang(dependency);
				return null;
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException("Could not produce SRG mappings", e);
		}
	}

	private void produceSrg(DependencyInfo dependency) throws IOException {
		// 锁内二次确认：等锁期间其它进程可能已产出可用产物（按「可复用」判定，避免把残骸当成果）
		if (!refreshDeps() && isReusableTsrg(srg)) {
			return;
		}

		Path srgZip = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve srg")).toPath();

		try {
			final byte[] tsrgBytes = ZipUtils.unpack(srgZip, "config/joined.tsrg");
			AtomicFiles.publish(srg, tmp -> Files.write(tmp, tsrgBytes));
		} catch (NoSuchFileException e) {
			try {
				// FG2-era MCP uses the older SRG format, convert it on the fly
				byte[] srgBytes = ZipUtils.unpack(srgZip, "joined.srg");

				AtomicFiles.publish(srg, tmp -> {
					try (Reader reader = new InputStreamReader(new ByteArrayInputStream(srgBytes)); Writer writer = Files.newBufferedWriter(tmp)) {
						new TSrgWriter(writer).write(new SrgReader(reader).read());
					}
				});
			} catch (NoSuchFileException e1) {
				e.addSuppressed(e1);
				throw e;
			}
		}
	}

	private void produceMergedMojang(DependencyInfo dependency) throws IOException {
		final boolean tsrgV2;

		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			// 判空后再判形态：srg.tsrg 为空文件时 readLine() 返回 null，此处原先会抛 NPE，
			// 使「按件重建」的路径根本走不到（详见 isTsrg2FirstLine 的说明）
			tsrgV2 = isTsrg2FirstLine(reader.readLine());
		}

		if (!tsrgV2) {
			// legacy srg 形态本就没有 merged mojmap 产物：形态未知（空文件）时同样按此处理，
			// 避免凭空生产、也避免把不可读的产物当作依据
			if (Files.size(srg) == 0) {
				getProject().getLogger().warn("{} 是空文件，无法判断 srg 形态；本轮跳过 merged mojmap 产物的生产，"
						+ "该文件会在下一次判定中按「不可复用」触发重建。", srg);
			}

			return;
		}

		// 按件判定：只重建缺失或不可复用的那一件，不因一件有问题就删掉另一件已发布的产物。
		// trimmed 由 raw 派生，因此 raw 不可复用时 trimmed 也要连带重建：否则会出现「只补下游件、
		// 读了截断的上游件」的静默错误映射（trimmed 自身看起来完全正常）。
		final boolean refresh = refreshDeps();
		final boolean needsRaw = refresh || !isReusableTsrg(mergedMojangRaw);
		final boolean needsTrimmed = needsRaw || !isReusableTsrg(mergedMojangTrimmed);

		if (!needsRaw && !needsTrimmed) {
			return;
		}

		Stopwatch stopwatch = Stopwatch.createStarted();
		getProject().getLogger().lifecycle(":merging mappings (InstallerTools, srg + mojmap)");

		if (needsRaw) {
			produceMergedMojangRaw();
		}

		if (needsTrimmed) {
			// 从已完整落位的 raw 产物派生：失败最多让 trimmed 缺失，下次只需重建这一件；
			// 已发布的 raw 不受影响（旧写法在开始前就把它删了，失败会连累读方）
			produceMergedMojangTrimmed();
		}

		getProject().getLogger().lifecycle(":merged mappings (InstallerTools, srg + mojmap) in " + stopwatch.stop());
	}

	/**
	 * 生产 raw 形态的 merged mojmap tsrg.
	 *
	 * <p>InstallerTools 自行创建 {@code --output} 指定的文件，因此先让它写「同目录唯一临时文件」，
	 * 成功后再原子落位，读方不会看到半截产物，也不再需要先删除既有产物。
	 */
	private void produceMergedMojangRaw() throws IOException {
		final Path temp = AtomicFiles.tempSibling(mergedMojangRaw);

		try (var serviceFactory = new ScopedServiceFactory()) {
			runMergeMapping(createMergeMappingTool(), temp, serviceFactory);
			AtomicFiles.move(temp, mergedMojangRaw);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * {@return InstallerTools 的 {@code MERGE_MAPPING} 调用声明（工具 classpath、主类与参数模板）}.
	 *
	 * <p>配置期只接线：依赖坐标与参数模板都是从项目状态读来的纯值，工具 classpath 是一个惰性的
	 * {@code FileCollection}（{@code DependencyDownloader} 只建 detached configuration，不解析）。
	 * 因此本方法既不解析依赖、也不发起进程，返回值可以挂成任务的 {@code @Nested} 输入。
	 *
	 * <p>参数模板里的取值分两类，口径与 {@code createNeoForgeInstallerTools} 一致：
	 * <ul>
	 *   <li><b>配置期就确定的纯值</b>——{@code --task}/{@code --left}/{@code --right}/{@code --classes}
	 *       ——就地定死在模板里，于是这条链搬进任务后不需要在执行期回读项目模型；</li>
	 *   <li><b>只有调用时才知道的路径</b>——{@code {output}}（本次调用的目标 tsrg）——留作占位符。</li>
	 * </ul>
	 *
	 * <p>参数顺序与改造前那串 {@code settings.args(...)} 逐条一致。
	 */
	public ForgeExternalToolService.Options createMergeMappingTool() throws IOException {
		final FileCollection classpath = DependencyDownloader.download(getProject(), LoomVersions.FORGE_INSTALLER_TOOLS.mavenNotation());
		final ForgeExternalToolService.Options tool = ForgeExternalToolService
				.createOptions(getProject(), classpath, INSTALLER_TOOLS_MAIN_CLASS).get();
		tool.getArgsTemplate().set(List.of(
				"--task",
				"MERGE_MAPPING",
				"--left",
				getSrg().toAbsolutePath().toString(),
				"--right",
				getMojmapTsrg2(getProject(), getExtension()).toAbsolutePath().toString(),
				"--classes",
				"--output",
				"{output}"
		));
		return tool;
	}

	/**
	 * 执行期求值：只认选项、本次调用的目标路径与服务工厂，不读项目模型.
	 *
	 * <p>与 {@link #createMergeMappingTool()} 配对，让配置期路径与「把这段搬进任务」之后的路径共用同一份
	 * 调用声明：实际命令行仍只由 {@link ForgeExternalToolService#settingsFor} 构造一处。
	 */
	public static void runMergeMapping(ForgeExternalToolService.Options tool, Path output, ServiceFactory serviceFactory) {
		final ForgeExternalToolService installerTools = serviceFactory.get(tool);
		installerTools.exec(Map.of("{output}", output.toAbsolutePath().toString()));
	}

	/**
	 * 从已发布的 raw 产物派生 trimmed 形态并原子发布.
	 */
	private void produceMergedMojangTrimmed() throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingVisitor visitor = new ArgDroppingVisitor(new FieldDescWrappingVisitor(tree));
		MappingReader.read(mergedMojangRaw, visitor);

		AtomicFiles.publish(mergedMojangTrimmed, temp -> {
			try (MappingWriter writer = MappingWriter.create(temp, MappingFormat.TSRG_2_FILE)) {
				tree.accept(writer);
			}
		});
	}

	// A visitor that drop all method args from srg
	private static final class ArgDroppingVisitor extends ForwardingMappingVisitor {
		ArgDroppingVisitor(MappingVisitor next) {
			super(next);
		}

		@Override
		public boolean visitMethodArg(int argPosition, int lvIndex, @Nullable String srcName) throws IOException {
			// skip
			return false;
		}
	}

	// Read mojmap and apply field descs to the tsrg2
	private class FieldDescWrappingVisitor extends ForwardingMappingVisitor {
		private final Map<FieldKey, String> fieldDescMap = new HashMap<>();
		private String lastClass;

		protected FieldDescWrappingVisitor(MappingVisitor next) throws IOException {
			super(next);
			MemoryMappingTree mojmap = new MemoryMappingTree();
			MappingReader.read(getMojmapTsrg2(getProject(), getExtension()), mojmap);

			for (MappingTree.ClassMapping classMapping : mojmap.getClasses()) {
				for (MappingTree.FieldMapping fieldMapping : classMapping.getFields()) {
					fieldDescMap.put(new FieldKey(classMapping.getSrcName(), fieldMapping.getSrcName()), fieldMapping.getSrcDesc());
				}
			}
		}

		@Override
		public boolean visitClass(String srcName) throws IOException {
			if (super.visitClass(srcName)) {
				this.lastClass = srcName;
				return true;
			} else {
				return false;
			}
		}

		@Override
		public boolean visitField(String srcName, String srcDesc) throws IOException {
			if (srcDesc == null) {
				srcDesc = fieldDescMap.get(new FieldKey(lastClass, srcName));
			}

			return super.visitField(srcName, srcDesc);
		}

		private record FieldKey(String owner, String name) {
		}
	}

	private void init(String version) {
		File dir = getMinecraftProvider().dir("srg/" + version);
		srg = new File(dir, "srg.tsrg").toPath();
		mergedMojangRaw = new File(dir, "srg-mojmap-merged-raw.tsrg").toPath();
		mergedMojangTrimmed = new File(dir, "srg-mojmap-merged-trimmed.tsrg").toPath();
	}

	public Path getSrg() {
		return srg;
	}

	public Path getMergedMojangRaw() {
		if (!isTsrgV2()) throw new IllegalStateException("May not access merged mojmap srg if not on modern Minecraft!");

		return mergedMojangRaw;
	}

	public Path getMergedMojangTrimmed() {
		if (!isTsrgV2()) throw new IllegalStateException("May not access merged mojmap srg if not on modern Minecraft!");

		return mergedMojangTrimmed;
	}

	public boolean isTsrgV2() {
		return isTsrgV2;
	}

	public static Path getMojmapTsrg2(Project project, LoomGradleExtension extension) throws IOException {
		String minecraftVersion = extension.getMinecraftProvider().minecraftVersion();
		if (mojmapTsrg2Map.containsKey(minecraftVersion)) return mojmapTsrg2Map.get(minecraftVersion);

		Path mojmapTsrg2 = extension.getMinecraftProvider().dir("forge").toPath().resolve("mojmap.tsrg2");

		// 就绪判据与同文件其它 tsrg 产物一致（见 isReusableTsrg）：mojmap.tsrg2 也是 tsrg2 文本，
		// 由 MappingWriter(TSRG_2_FILE) 经 java.io.Writer 逐行写出。只判存在会让 0 字节或截断的残骸
		// 进入 MERGE_MAPPING 的 --right 输入，静默产出缺少 mojmap 名字的 merged 映射。
		if (!isReusableTsrg(mojmapTsrg2) || extension.refreshDeps()) {
			// 该文件位于 userCache（不按项目隔离），同一 MC 版本的多个 daemon 会指向同一路径；
			// 生成结果只取决于目标版本，故由首个取得锁的进程写入，其余进程等待后直接复用。
			// 写入在临时文件上完成并原子替换，避免读取方看到半截内容。
			writeMojmapTsrg2WithLock(project, extension, mojmapTsrg2);
		}

		mojmapTsrg2Map.put(minecraftVersion, mojmapTsrg2);
		return mojmapTsrg2;
	}

	private static void writeMojmapTsrg2WithLock(Project project, LoomGradleExtension extension, Path mojmapTsrg2) {
		final String lockKey = "mojmap-tsrg2:" + extension.getMinecraftProvider().minecraftVersion();
		final Path lockRoot = mojmapTsrg2.getParent().resolve(Constants.Cache.LOCKS_DIR);

		try {
			CacheEntryLock.withLock(lockRoot, lockKey, LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：判据必须与锁外快路径一致（同为 isReusableTsrg），
				// 否则残骸会被锁内判为「已产出」而直接复用，快路径每轮进锁却永远修不好它
				if (isReusableTsrg(mojmapTsrg2) && !extension.refreshDeps()) {
					return null;
				}

				writeMojmapTsrg2(project, mojmapTsrg2);
				return null;
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException("Could not write " + mojmapTsrg2, e);
		}
	}

	private static void writeMojmapTsrg2(Project project, Path mojmapTsrg2) throws IOException {
		Files.createDirectories(mojmapTsrg2.getParent());
		final Path temp = Files.createTempFile(mojmapTsrg2.getParent(), "mojmap", ".tsrg2.tmp");

		try {
			try (MappingWriter writer = MappingWriter.create(temp, MappingFormat.TSRG_2_FILE)) {
				GradleMappingContext context = new GradleMappingContext(project, "tmp-mojmap");
				MemoryMappingTree tree = new MemoryMappingTree();
				visitMojangMappings(tree, context);
				tree.accept(writer);
			}

			move(temp, mojmapTsrg2);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void visitMojangMappings(MappingVisitor visitor, MappingContext context) {
		try {
			MojangMappingLayer layer = new MojangMappingsSpec(() -> true, true).createLayer(context);
			layer.visit(visitor);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.SRG;
	}
}
