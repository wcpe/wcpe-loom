/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.pipeline;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import dev.architectury.loom.forge.tool.ForgeExternalToolService;
import dev.architectury.loom.mcpconfig.McpExecutor;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

/**
 * L3 流水线：生产 Forge 的 pre-patch Minecraft jar（{@code minecraft-<type>-<ns>.jar}）.
 *
 * <p>它取代的是 {@code MinecraftPatchedProvider.provide()} 里那段配置期同步执行的
 * 「取跨进程锁 → 判断是否需要重建 → 选分支 → 跑 MCP rename 步（或 NeoForge installer tools）→ 原子落位」。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要生产</td><td>配置期 {@code needsWork()} 设的 {@code dirty} + 锁内二次确认</td>
 *       <td>Gradle up-to-date 检查（输入内容 + 输出快照）</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁</td><td>任务图 + 跨进程文件锁（产物仍在共享缓存）</td></tr>
 *   <tr><td>执行时机</td><td>配置期（{@code CompileConfiguration.setupMinecraft}）</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>生产逻辑不在这里</h2>
 * 真正的生产仍由 {@link MinecraftPatchedProvider#runMcpExecutor} 与
 * {@link ForgeExternalToolService#exec} 持有：配置期路径与本任务共用同一份实现，
 * 因此两条路径的 MCP 步进集合、命令行与产物逐字节一致。本任务另写一套「等价」的实现就会引入分叉。
 *
 * <h2>两条分支由配置期定死</h2>
 * {@link #getBranch()} 是配置期算好的纯值（判据见
 * {@code MinecraftPatchedProvider.shouldUseNeoForgeInstallerToolsToCreatePrePatchJar()}），
 * 执行期不再回读项目模型；两条分支各自的工具声明也只挂它自己那一个 {@code @Nested} 输入。
 *
 * <h2>MCP 工作目录为什么是任务自己的</h2>
 * 配置期路径用 {@code TempFiles} 造一个随机临时目录，本次调用结束即删；任务路径必须让目录活到执行期，
 * 因此改由 {@link #getMcpWorkDir()} 给出（{@code build/tmp/<任务名>/mcp}），由本任务在执行期创建、
 * 结束即删——对 MCP 执行链而言与配置期的 {@code TempFiles} 语义完全一致。
 * 目录路径不参与产物内容（旧路径每次都是随机路径而产物不变），故它是 {@code @Internal}。
 */
@CacheableTask
public abstract class GenerateForgePrePatchJarTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "generateForgePrePatchJar";

	/** 分支：跑 MCP 的 {@code rename} 步（现代 Forge / NeoForge 的常规形态）. */
	public static final String BRANCH_MCP_RENAME = "mcp-rename";

	/** 分支：NeoForge installer tools 的 {@code PROCESS_MINECRAFT_JAR}（见 NeoForge#2848 的绕行）. */
	public static final String BRANCH_NEOFORGE_INSTALLER_TOOLS = "neoforge-installer-tools";

	/** 本次走哪条分支（配置期算好的纯值）. */
	@Input
	public abstract Property<String> getBranch();

	/** MCP 执行器选项（{@link #BRANCH_MCP_RENAME}）；另一条分支下不设置. */
	@Nested
	@Optional
	public abstract Property<McpExecutor.Options> getMcpOptions();

	/** NeoForge installer tools 的声明式选项（{@link #BRANCH_NEOFORGE_INSTALLER_TOOLS}）；另一条分支下不设置. */
	@Nested
	@Optional
	public abstract Property<ForgeExternalToolService.Options> getInstallerTools();

	/** installer tools 分支要下载的 client 映射地址；另一条分支下不设置. */
	@Input
	@Optional
	public abstract Property<String> getClientMappingsUrl();

	/** 是否处于 Gradle 的离线模式（与配置期 {@code extension.download} 同判据）. */
	@Input
	public abstract Property<Boolean> getOffline();

	/** 是否显式要求刷新依赖（与配置期 {@code extension.download} 同判据）. */
	@Input
	public abstract Property<Boolean> getManualRefreshDeps();

	/**
	 * MCP 执行链的工作目录.
	 *
	 * <p>{@code @Internal}：它只决定中间文件放在哪里，不决定产物内容——配置期路径每次都用新的随机临时目录，
	 * 产物却逐字节相同（见类注释）。
	 */
	@Internal
	public abstract DirectoryProperty getMcpWorkDir();

	/** 产物：pre-patch jar（与配置期同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void generate() throws Exception {
		final Path output = getOutputJar().get().getAsFile().toPath();
		// 原子发布：产物位于跨 daemon 共享的 forge 缓存目录，配置期路径同样是「临时文件 + 原子落位」，
		// 否则锁外读方会看到半截 jar
		MinecraftPatchedProvider.publishAtomically(output, this::produce);
	}

	private void produce(Path output) throws Exception {
		final String branch = getBranch().get();

		try (var serviceFactory = new ScopedServiceFactory()) {
			if (BRANCH_MCP_RENAME.equals(branch)) {
				produceWithMcp(output, serviceFactory);
			} else if (BRANCH_NEOFORGE_INSTALLER_TOOLS.equals(branch)) {
				produceWithInstallerTools(output, serviceFactory);
			} else {
				throw new IllegalStateException("未知的 pre-patch 分支: " + branch);
			}
		}
	}

	private void produceWithMcp(Path output, ScopedServiceFactory serviceFactory) throws IOException {
		final Path workDir = getMcpWorkDir().get().getAsFile().toPath();

		try {
			Files.createDirectories(workDir);
			// 与配置期路径共用这一处实现（它只认选项、落位路径与服务工厂）
			MinecraftPatchedProvider.runMcpExecutor(getMcpOptions(), output, serviceFactory);
		} finally {
			deleteRecursively(workDir);
		}
	}

	private void produceWithInstallerTools(Path output, ScopedServiceFactory serviceFactory) throws IOException {
		try (var tempFiles = new TempFiles()) {
			// 本次下载的 client 映射：与配置期一致，交给 installer tools 作为 --input-mappings
			final Path mappings = tempFiles.file("mappings", ".txt");
			downloadClientMappings(mappings);

			final ForgeExternalToolService installerTools = serviceFactory.get(getInstallerTools());
			installerTools.exec(Map.of(
					"{mappings}", mappings.toAbsolutePath().toString(),
					"{output}", output.toAbsolutePath().toString()
			));
		}
	}

	/**
	 * 下载 client 映射.
	 *
	 * <p>与配置期 {@code extension.download(url)} 逐项同源：同一个地址、同样的离线与强制刷新判据。
	 * 刻意不做 sha1 校验——配置期那一处也没有（见 {@code createNeoForgeInstallerToolsPrePatchJar}）。
	 */
	private void downloadClientMappings(Path target) throws IOException {
		try {
			var builder = Download.create(getClientMappingsUrl().get());

			if (getOffline().get()) {
				builder.offline();
			}

			if (getManualRefreshDeps().get()) {
				builder.forceDownload();
			}

			builder.downloadPath(target);
		} catch (URISyntaxException e) {
			throw new IOException("无法为 client 映射创建下载器: " + getClientMappingsUrl().get(), e);
		}
	}

	/** 删除本次执行用过的临时目录；失败只记不抛——产物已经落位，清理不该让构建失败. */
	private void deleteRecursively(Path directory) {
		if (!Files.exists(directory)) {
			return;
		}

		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			getLogger().info("清理 MCP 工作目录 {} 失败（产物已落位，不影响本次构建）: {}", directory, e.toString());
		}
	}
}
