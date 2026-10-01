/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2022 FabricMC
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

package net.fabricmc.loom.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.slf4j.Logger;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.task.service.LorenzMappingService;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.loom.util.service.ServiceFactory;

public class SourceRemapper {
	/**
	 * 「配置期 mod 源码重映射因 MC jar 未就位而整批跳过」的日志前缀.
	 *
	 * <p>公开是为了让测试按同一份字面量断言：跳过是**预期行为**（见 {@link #remapAll()}），
	 * 但它是「静默失败」的反面——必须能从构建日志里确认它确实发生了。
	 */
	public static final String SKIPPED_LOG_MARKER = "Skipping configuration-phase mod sources remapping";

	private final Project project;
	private final ServiceFactory serviceFactory;
	private String from;
	private String to;
	private final List<Consumer<ProgressLogger>> remapTasks = new ArrayList<>();

	private Mercury mercury;

	public SourceRemapper(Project project, ServiceFactory serviceFactory, boolean toNamed) {
		this(project, serviceFactory, toNamed ? LoomGradleExtension.get(project).getProductionNamespace().get() : "named", !toNamed ? LoomGradleExtension.get(project).getProductionNamespace().get() : "named");
	}

	public SourceRemapper(Project project, ServiceFactory serviceFactory, String from, String to) {
		this.project = project;
		this.serviceFactory = serviceFactory;
		this.from = from;
		this.to = to;
	}

	public void scheduleRemapSources(File source, File destination, boolean reproducibleFileOrder, boolean preserveFileTimestamps, Runnable completionCallback) {
		remapTasks.add((logger) -> {
			try {
				logger.progress("remapping sources - " + source.getName());
				Files.deleteIfExists(destination.toPath());
				remapSourcesInner(source, destination);

				if (reproducibleFileOrder || !preserveFileTimestamps) {
					ZipReprocessorUtil.reprocessZip(destination.toPath(), reproducibleFileOrder, preserveFileTimestamps);
				}

				// Set the remapped sources creation date to match the sources if we're likely succeeded in making it
				destination.setLastModified(source.lastModified());
				completionCallback.run();
			} catch (Exception e) {
				// Failed to remap, lets clean up to ensure we try again next time
				destination.delete();
				throw new RuntimeException("Failed to remap sources for " + source, e);
			}
		});
	}

	public void remapAll() {
		// 无待办则直接返回：暖缓存下没有任何源码需要重映射（scheduleRemapSources 受 isCacheInvalid 守卫），
		// 因此根本不会走到下面取锁——同一 checkout 的并发构建在暖缓存下互不阻塞。
		if (remapTasks.isEmpty()) {
			return;
		}

		final List<Path> missingMinecraftJars = missingMinecraftClassPathEntries();

		// ── 冷缓存闸门：MC jar 还没落位时，整批不做，且一个产物都不写 ──
		// 本路径在**配置期**执行，而它需要 MC jar 的类信息（见 getMercuryInstance 往 Mercury 的
		// classpath 里放的是什么）。改造后 MC jar 由 RemapMinecraftTask /
		// ProcessMinecraftJarTask 在**执行期**落位，配置期只保证「产出任务已登记」，
		// 产物本身要等任务跑完。冷缓存下这里取到的路径都还不存在。
		//
		// 为什么不能像原来那样「过滤掉不存在的条目，剩下什么就用什么」：Mercury 少了 MC 这一大块
		// classpath 后仍会跑完，产出的是**错误但看起来正常**的源码，而且结果会经 completionCallback
		// 写进共享的 remapped mods 缓存；此后只按「文件存在且可读」判定复用，永远不会被修正——
		// 这正是本改造最危险的失效模式（静默产出错误结果）。
		//
		// 为什么不在配置期强制求值产出任务：那会让配置缓存在每个含 mod 依赖的项目上失效，
		// 与「把产出移出配置期」的目标相悖，代价远高于本路径延后一次。
		//
		// 因此这里显式拒绝整批：本次不写任何产物（sources 缓存的判据仍是「缺失」），
		// 下一次构建／IDE 同步在产物就位后会自动重做，无需人工清理。
		if (!missingMinecraftJars.isEmpty()) {
			// 日志文案只用英文并与 SKIPPED_LOG_MARKER 保持同一份字面量，避免测试断言与实现文案各自漂移。
			project.getLogger().warn(SKIPPED_LOG_MARKER + ": {} minecraft jar(s) are not available yet (e.g. {}). "
					+ "These jars are produced by tasks at execution time (RemapMinecraftTask / ProcessMinecraftJarTask), "
					+ "so they cannot be read while configuring. Remapping sources without them would silently produce "
					+ "broken output that is then cached, so nothing is written this time; the next build (or IDE sync) "
					+ "after the jars are produced will redo it automatically.",
					missingMinecraftJars.size(), missingMinecraftJars.getFirst());
			return;
		}

		// 写 LOCAL 共享 remapped_mods 源码缓存：用与二进制 mod 重映射相同的 mod-deps 锁串行化，
		// 避免同一 checkout 的多个并发构建（如同时跑 client/server）踩踏。
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final Path lockRoot = extension.getFiles().getCacheLocks().toPath();
		final String lockKey = LoomCacheService.modDepsKey(project.getRootDir(), extension.getMappingConfiguration().mappingsIdentifier);

		try {
			LoomCacheService.get(project).get().runExclusive(lockRoot, lockKey, LoomCacheService.defaultTimeout(), () -> {
				project.getLogger().lifecycle(":remapping sources (Mercury, {} -> {})", from, to);

				ProgressLoggerFactory progressLoggerFactory = ((ProjectInternal) project).getServices().get(ProgressLoggerFactory.class);
				ProgressLogger progressLogger = progressLoggerFactory.newOperation(SourceRemapper.class.getName());
				progressLogger.start("Remapping dependency sources", "sources");

				remapTasks.forEach(consumer -> consumer.accept(progressLogger));

				progressLogger.completed();

				// TODO: FIXME - WORKAROUND https://github.com/FabricMC/fabric-loom/issues/45
				System.gc();
				return null;
			});
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to remap dependency sources", e);
		}
	}

	private void remapSourcesInner(File source, File destination) throws Exception {
		project.getLogger().info(":remapping source jar");
		Mercury mercury = getMercuryInstance();

		if (source.equals(destination)) {
			if (source.isDirectory()) {
				throw new RuntimeException("Directories must differ!");
			}

			source = new File(destination.getAbsolutePath().substring(0, destination.getAbsolutePath().lastIndexOf('.')) + "-dev.jar");

			try {
				Files.move(destination.toPath(), source.toPath());
			} catch (IOException e) {
				throw new RuntimeException("Could not rename " + destination.getName() + "!", e);
			}
		}

		Path srcPath = source.toPath();
		boolean isSrcTmp = false;

		if (!source.isDirectory()) {
			// create tmp directory
			isSrcTmp = true;
			srcPath = Files.createTempDirectory("fabric-loom-src");
			ZipUtils.unpackAll(source.toPath(), srcPath);
		}

		if (!destination.isDirectory() && destination.exists()) {
			if (!destination.delete()) {
				throw new RuntimeException("Could not delete " + destination.getName() + "!");
			}
		}

		// 目标 jar 的父目录必须先存在：这里的产物落在 remapped_working 之类「只被拼出路径、没人创建」的
		// 目录里，而 zipfs 的 create=true 只建**文件**——父目录缺失时它抛的是 NoSuchFileException，
		// 表现成「Failed to remap sources for ...」，看上去像是输入有问题，与父目录毫无关系。
		if (destination.getParentFile() != null) {
			Files.createDirectories(destination.getParentFile().toPath());
		}

		FileSystemUtil.Delegate dstFs = destination.isDirectory() ? null : FileSystemUtil.getJarFileSystem(destination, true);
		Path dstPath = dstFs != null ? dstFs.get().getPath("/") : destination.toPath();

		try {
			mercury.rewrite(srcPath, dstPath);
		} catch (Exception e) {
			project.getLogger().warn("Could not remap " + source.getName() + " fully!", e);
		}

		copyNonJavaFiles(srcPath, dstPath, project.getLogger(), source.toPath());

		if (dstFs != null) {
			dstFs.close();
		}

		if (isSrcTmp) {
			Files.walkFileTree(srcPath, new DeletingFileVisitor());
		}
	}

	/**
	 * 构造用于 sources 重映射的 Mercury 实例.
	 *
	 * <p>不依赖 {@link Project}，映射集与 classpath 全部由调用方备妥。
	 *
	 * <p>这是 sources 重映射的计算核心。原实现从 {@code LoomGradleExtension} 取
	 * mappings、mapped MC jar、unmapped mod 集合，并在配置期解析 detachedConfiguration；
	 * 拆分后这些都在调用方一次性备好，本方法只做「构造 Mercury 并装配 processor」。
	 */
	public static Mercury createMercury(MappingSet mappings, Iterable<Path> classPathFiles) {
		// createMercuryWithClassPath 已按「存在且为文件」过滤并装配 classpath，此处不再重复添加
		Mercury mercury = createMercuryWithClassPath(classPathFiles);
		// Always use the latest version
		mercury.setSourceCompatibilityFromRelease(Integer.MAX_VALUE);

		mercury.getProcessors().add(MercuryRemapper.create(mappings));
		return mercury;
	}

	private Mercury getMercuryInstance() {
		if (this.mercury != null) {
			return this.mercury;
		}

		LoomGradleExtension extension = LoomGradleExtension.get(project);
		MappingConfiguration mappingConfiguration = extension.getMappingConfiguration();

		LorenzMappingService lorenzMappingService = serviceFactory.get(LorenzMappingService.createOptions(
				project,
				mappingConfiguration,
				Objects.requireNonNull(MappingsNamespace.of(from)),
				Objects.requireNonNull(MappingsNamespace.of(to))));
		MappingSet mappings = lorenzMappingService.getMappings();

		// ── 项目模型只在本段被读取，之后全部走显式参数 ──
		final boolean toNamed = MappingsNamespace.of(to) == MappingsNamespace.NAMED;
		Mercury mercury = createMercuryWithClassPath(project, toNamed);

		// 收集所有额外的 classpath 条目：unmapped mods、mapped MC jar、注解包
		final List<Path> extraClassPath = new ArrayList<>();

		for (File file : extension.getUnmappedModCollection()) {
			extraClassPath.add(file.toPath());
		}

		// MC jar 与闸门（remapAll）取的是同一处：两者一旦分叉，「闸门放行、这里却拿不到 jar」的
		// 静默缺 classpath 就会重新出现
		extraClassPath.addAll(minecraftClassPathEntries());

		Set<File> files = project.getConfigurations()
				.detachedConfiguration(project.getDependencies().create(LoomVersions.JETBRAINS_ANNOTATIONS.mavenNotation()))
				.resolve();

		for (File file : files) {
			extraClassPath.add(file.toPath());
		}

		// 与 createMercuryWithClassPath 同样只接受「存在且为普通文件」的条目。
		// 注意：这里**不再**是「静默」过滤——MC jar 是否齐备由 remapAll 的冷缓存闸门负责（它取同一份
		// MC jar 列表），走到这里就说明 MC jar 都在；仍被过滤掉的条目会在下面留痕，便于日后定位。
		for (Path path : extraClassPath) {
			if (Files.isRegularFile(path)) {
				mercury.getClassPath().add(path);
			} else {
				project.getLogger().info("Skipping missing classpath entry {} while remapping sources", path);
			}
		}

		mercury.setSourceCompatibilityFromRelease(Integer.MAX_VALUE);
		mercury.getProcessors().add(MercuryRemapper.create(mappings));

		this.mercury = mercury;
		return this.mercury;
	}

	/**
	 * {@return 配置期重映射需要放进 Mercury classpath 的 Minecraft jar}.
	 *
	 * <p>生产命名空间与 named 各取一份：mod 源码引用的是 named 命名空间的 MC 类型，而 Forge 系下
	 * 生产命名空间另有其物（srg/mojang），两份都要在 classpath 上才是完整的类型解析环境。
	 *
	 * <p>Fabric 下两者是同一个命名空间，列表里会出现重复条目；Mercury 的 classpath 是列表，
	 * 重复条目无害，故只做保序拼接而不去重。
	 */
	private List<Path> minecraftClassPathEntries() {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final List<Path> jars = new ArrayList<>(extension.getMinecraftJars(extension.getProductionNamespaceEnum().get()));
		jars.addAll(extension.getMinecraftJars(MappingsNamespace.NAMED));
		return jars;
	}

	/**
	 * {@return {@link #minecraftClassPathEntries()} 中当前还不存在（或不是普通文件）的那些条目}.
	 *
	 * <p>判据刻意与 {@link #createMercuryWithClassPath(Iterable)} 的过滤逐字一致（「存在且为普通文件」）：
	 * 闸门放行的条目就是真能进 classpath 的条目，两边口径一旦分叉，就会出现「闸门放行、classpath 却缺一块」
	 * 的静默缺项——那正是本闸门要消灭的失效模式。
	 */
	private List<Path> missingMinecraftClassPathEntries() {
		return minecraftClassPathEntries().stream()
				.filter(path -> !Files.isRegularFile(path))
				.distinct()
				.toList();
	}

	public static void copyNonJavaFiles(Path from, Path to, Logger logger, Path source) throws IOException {
		Files.walk(from).forEach(path -> {
			Path targetPath = to.resolve(from.relativize(path).toString());

			if (!isJavaFile(path) && !Files.exists(targetPath)) {
				try {
					Files.copy(path, targetPath);
				} catch (IOException e) {
					logger.warn("Could not copy non-java sources '" + source + "' fully!", e);
				}
			}
		});
	}

	/**
	 * 构造带 classpath 的 Mercury 实例.
	 *
	 * <p>不依赖 {@link Project}，classpath 由调用方显式给出。
	 *
	 * <p>把「从哪里取 classpath」与「如何构造 Mercury」分开：前者是项目模型访问
	 * （只能在配置期做），后者是纯计算，可以移到执行阶段用已解析好的文件列表构造。
	 */
	public static Mercury createMercuryWithClassPath(Iterable<Path> classPathFiles) {
		Mercury m = new Mercury();
		m.setGracefulClasspathChecks(true);

		for (Path path : classPathFiles) {
			if (Files.exists(path)) {
				m.getClassPath().add(path);
			}
		}

		return m;
	}

	public static Mercury createMercuryWithClassPath(Project project, boolean toNamed) {
		final List<Path> classPath = new ArrayList<>();

		for (File file : project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES).getFiles()) {
			classPath.add(file.toPath());
		}

		if (!toNamed) {
			for (File file : project.getConfigurations().getByName("compileClasspath").getFiles()) {
				classPath.add(file.toPath());
			}
		} else {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			for (RemapConfigurationSettings entry : extension.getRemapConfigurations()) {
				for (File inputFile : entry.getSourceConfiguration().get().getFiles()) {
					classPath.add(inputFile.toPath());
				}
			}
		}

		// 委托给不依赖 Project 的重载：项目模型只在这里被读取一次
		return createMercuryWithClassPath(classPath);
	}

	private static boolean isJavaFile(Path path) {
		String name = path.getFileName().toString();
		// ".java" is not a valid java file
		return name.endsWith(".java") && name.length() != 5;
	}
}
