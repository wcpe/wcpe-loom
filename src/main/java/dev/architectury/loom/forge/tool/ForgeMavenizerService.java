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

package dev.architectury.loom.forge.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import dev.architectury.loom.util.TempFiles;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecResult;

import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.CacheEntryLock;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/** 以隔离的官方工具重编译 Forge，并原子发布 Loom 所需的 SRG 中间产物. */
public final class ForgeMavenizerService extends Service<ForgeMavenizerService.Options> {
	public static final ServiceType<Options, ForgeMavenizerService> TYPE = new ServiceType<>(Options.class, ForgeMavenizerService.class);
	private static final String TOOL_VERSION = "0.4.24";
	private static final String TOOL_JAR = "minecraft-mavenizer-" + TOOL_VERSION + ".jar";
	private static final String TOOL_URL = "https://maven.minecraftforge.net/net/minecraftforge/minecraft-mavenizer/"
			+ TOOL_VERSION + "/" + TOOL_JAR;
	private static final String TOOL_SHA256 = "4659310b6c97938ad80f8d569ec9ca76620533feaa3ff46f7a05d7133d29d700";
	private static final String MAIN_CLASS = "net.minecraftforge.mcmaven.cli.Main";
	private static final String RECIPE = "forge-mavenizer/v1|official-to-srg|jdk=8,21,25|sha256=" + TOOL_SHA256;
	private static final Duration LOCK_TIMEOUT = Duration.ofMinutes(45);

	public interface Options extends Service.Options {
		@Input
		Property<String> getForgeVersion();

		@Input
		Property<String> getMinecraftVersion();

		@Input
		Property<String> getMcpVersion();

		@Input
		Property<String> getRecipe();

		@Internal
		Property<Boolean> getOffline();

		@Internal
		Property<Boolean> getRefresh();

		@Internal
		DirectoryProperty getCacheDirectory();

		@Input
		Property<String> getJava8Home();

		@Input
		Property<String> getJava21Home();

		@Input
		Property<String> getJava25Home();

		@Nested
		Property<ForgeToolService.Options> getTool();
	}

	public static Provider<Options> createOptions(Project project, String forgeVersion, String minecraftVersion,
			String mcpVersion, Path cacheDirectory) {
		final ObjectFactory objects = project.getObjects();
		final Provider<JavaLauncher> java25 = JavaExecutableFetcher.launcherFor(project, 25);
		final ForgeToolExecutor.Settings settings = objects.newInstance(ForgeToolExecutor.Settings.class);
		settings.getExecutable().set(java25.map(launcher -> launcher.getExecutablePath().getAsFile().getAbsolutePath()));
		// 失败时保留官方工具诊断，不沿用消费工程的 JVM 或抑制错误输出。
		settings.getShowVerboseStdout().set(ForgeToolExecutor.shouldShowVerboseStdout(project));
		settings.getShowVerboseStderr().set(true);
		settings.getMainClass().set(MAIN_CLASS);
		settings.getProgramArgs();
		settings.getJvmArgs();
		settings.getExecClasspath();
		final ForgeToolService.Options tool = ServiceType.createOptions(objects, ForgeToolService.TYPE,
				options -> options.getBaseSettings().set(settings));
		final Options options = ServiceType.createOptions(objects, TYPE, value -> {
			value.getForgeVersion().set(forgeVersion);
			value.getMinecraftVersion().set(minecraftVersion);
			value.getMcpVersion().set(mcpVersion);
			value.getRecipe().set(RECIPE);
			value.getCacheDirectory().fileValue(cacheDirectory.toFile());
			value.getOffline().set(project.getGradle().getStartParameter().isOffline());
			value.getRefresh().set(project.getGradle().getStartParameter().isRefreshDependencies() || Boolean.getBoolean("loom.refresh"));
			value.getJava8Home().set(JavaExecutableFetcher.launcherFor(project, 8).map(ForgeMavenizerService::javaHome));
			value.getJava21Home().set(JavaExecutableFetcher.launcherFor(project, 21).map(ForgeMavenizerService::javaHome));
			value.getJava25Home().set(java25.map(ForgeMavenizerService::javaHome));
			value.getTool().set(tool);
		});
		return project.provider(() -> options);
	}

	public ForgeMavenizerService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	private static String javaHome(JavaLauncher launcher) {
		return launcher.getMetadata().getInstallationPath().getAsFile().getAbsolutePath();
	}

	public static Path receipt(Path output) {
		return output.resolveSibling(output.getFileName() + ".mavenizer-receipt");
	}

	/** 仅检查已发布的产物身份，不解析 Mavenizer 或 JDK. */
	public static boolean isReusable(Path output, String forgeVersion, String minecraftVersion, String mcpVersion) {
		final Path receipt = receipt(output);

		try {
			if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
				return false;
			}

			final List<String> lines = Files.readAllLines(receipt);
			return lines.size() == 4 && lines.get(0).equals(identity(forgeVersion, minecraftVersion, mcpVersion))
					&& lines.get(1).matches("[a-f0-9]{64}") && lines.get(2).matches("[a-f0-9]{64}")
					&& Checksum.of(output).sha256().matchesStr(lines.get(3));
		} catch (IOException | UncheckedIOException e) {
			return false;
		}
	}

	static String runtimeRecipe() {
		return RECIPE;
	}

	private static String identity(String forgeVersion, String minecraftVersion, String mcpVersion) {
		return RECIPE + "|forge=" + forgeVersion + "|minecraft=" + minecraftVersion + "|mcp=" + mcpVersion;
	}

	public void produce(Path output) throws IOException {
		final Path target = output.toAbsolutePath().normalize();
		final Path cache = getOptions().getCacheDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
		final String forge = getOptions().getForgeVersion().get();
		final String minecraft = getOptions().getMinecraftVersion().get();
		final String mcp = getOptions().getMcpVersion().get();
		final boolean refresh = getOptions().getRefresh().get();

		try {
			withRuntimeLock(target, () -> {
				if (!refresh && isReusable(target, forge, minecraft, mcp)) {
					return null;
				}

				// 同一工具缓存串行读写；仓库由每次运行独占，不能把上一次输出误判成成功。
				return CacheEntryLock.withLock(cache.resolve("locks"), "mavenizer-cache", LOCK_TIMEOUT, () -> {
					produceLocked(target, cache, forge, minecraft, mcp);
					return null;
				});
			});
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			throw new IOException("Forge Mavenizer " + TOOL_VERSION + " 无法为 " + forge + " 生成 SRG JAR：" + e.getMessage(), e);
		}
	}

	private void produceLocked(Path output, Path cache, String forge, String minecraft, String mcp) throws IOException {
		export(cache, forge, minecraft, mcp, getOptions().getRefresh().get(), false, artifact ->
				publish(output, forge, minecraft, mcp, artifact.classesJar(), artifact.officialToSrgMappings(), artifact::writeSrgJar));
	}

	/** 为已发布的运行时补齐同批源码；热命中不会再次调用工具. */
	public void prepareSources(Path runtimeSrgJar, Path runtimeReceipt, Path outputBundle) throws IOException {
		final Path target = runtimeSrgJar.toAbsolutePath().normalize();
		final Path stamp = runtimeReceipt.toAbsolutePath().normalize();
		final Path bundle = outputBundle.toAbsolutePath().normalize();
		final Path cache = getOptions().getCacheDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
		final String forge = getOptions().getForgeVersion().get();
		final String minecraft = getOptions().getMinecraftVersion().get();
		final String mcp = getOptions().getMcpVersion().get();

		if (bundle.equals(target) || bundle.equals(stamp) || !stamp.equals(receipt(target))) {
			throw new IOException("Mavenizer 运行时、收据或源码包路径不匹配：" + target + " / " + stamp + " / " + bundle);
		}

		try {
			withRuntimeLock(target, () -> {
				final var runtime = ForgeMavenizerSourcesBundle.readRuntime(target, stamp);

				if (!runtime.recipe().equals(identity(forge, minecraft, mcp))) {
					throw new IOException("Mavenizer 当前运行时收据与请求的 Forge/Minecraft/MCP 版本不符：" + stamp);
				}

				if (ForgeMavenizerSourcesBundle.isReusable(bundle, runtime)) {
					return null;
				}

				return CacheEntryLock.withLock(cache.resolve("locks"), "mavenizer-cache", LOCK_TIMEOUT, () -> {
					// 补齐源码只读对照现有运行时身份，不能把刷新当作绕过配对校验的方式。
					exportSources(getOptions().getOffline().get(), cacheOnly ->
							export(cache, forge, minecraft, mcp, false, cacheOnly, artifact ->
									ForgeMavenizerSourcesBundle.publish(bundle, target, stamp, runtime, artifact)));
					return null;
				});
			});
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}

			throw new IOException("Forge Mavenizer " + TOOL_VERSION + " 无法为 " + forge + " 补齐同批源码：" + e.getMessage(), e);
		}
	}

	private static <T> T withRuntimeLock(Path target, Callable<T> action) throws Exception {
		return CacheEntryLock.withLock(target.getParent().resolve(".loom-locks"), "forge-mavenizer:" + target.getFileName(), LOCK_TIMEOUT, action);
	}

	static void exportSources(boolean offline, AtomicFiles.IOConsumer<Boolean> export) throws IOException {
		if (!offline) {
			export.accept(false);
			return;
		}

		try {
			export.accept(true);
		} catch (MavenizerCommandException first) {
			try {
				// 首次只读已有流水线缓存；缓存路径迁移导致未命中时才尝试正常离线执行。
				export.accept(false);
			} catch (MavenizerCommandException second) {
				final IOException failure = new IOException("Mavenizer 本机离线缓存不足：两种离线导出均失败。请联网补齐同批源码缓存；当前运行时保持不变", second);
				failure.addSuppressed(first);
				throw failure;
			} catch (IOException validation) {
				validation.addSuppressed(first);
				throw validation;
			}
		}
	}

	private static final class MavenizerCommandException extends IOException {
		private MavenizerCommandException(RuntimeException cause) {
			super("Mavenizer 外部进程启动或执行失败：" + cause.getMessage(), cause);
		}
	}

	static IOException commandFailure(RuntimeException failure) {
		return new MavenizerCommandException(failure);
	}

	private void export(Path cache, String forge, String minecraft, String mcp, boolean refresh, boolean cacheOnly,
			AtomicFiles.IOConsumer<ForgeMavenizerArtifact> consumer) throws IOException {
		final boolean offline = getOptions().getOffline().get();
		final Path toolJar = resolveTool(cache, offline);
		final Path workCache = Files.createDirectories(cache.resolve("cache"));
		final Path jdkCache = Files.createDirectories(cache.resolve("jdk-cache"));
		final ForgeToolService tool = getServiceFactory().get(getOptions().getTool());

		try (var tmp = new TempFiles()) {
			final Path repository = tmp.directory("loom-mavenizer-repo-");
			final List<String> args = cacheOnly ? sourceArguments(forge, workCache, jdkCache, repository)
					: arguments(forge, workCache, jdkCache, repository, offline, refresh);
			final String jdkPaths = javaInstallations(getOptions().getJava8Home().get(), getOptions().getJava21Home().get(),
					getOptions().getJava25Home().get());

			try {
				final ExecResult result = tool.exec(settings -> {
					settings.setClasspath(toolJar.toFile());
					settings.setArgs(args);
					settings.jvmArgs(jdkPaths);
				});
				result.rethrowFailure().assertNormalExitValue();
			} catch (RuntimeException failure) {
				// 只标记进程边界的失败；下面的模块、路径、摘要或收据验证绝不触发重试。
				throw commandFailure(failure);
			}

			consumer.accept(ForgeMavenizerArtifact.resolve(repository, forge, minecraft, mcp));
		}
	}

	static void publish(Path output, String forge, String minecraft, String mcp, Path classes, Path mappings,
			AtomicFiles.IOConsumer<Path> conversion) throws IOException {
		final Path jarStage = AtomicFiles.tempSibling(output);
		final String sourceDigest = Checksum.of(classes).sha256().hex();
		final String mappingsDigest = Checksum.of(mappings).sha256().hex();

		try {
			conversion.accept(jarStage);
			final String digest = Checksum.of(jarStage).sha256().hex();
			final List<String> stamp = List.of(identity(forge, minecraft, mcp), sourceDigest, mappingsDigest, digest);
			// 先落位 JAR，再提交摘要；中断若留下旧摘要，下次会因摘要不符而重新生产。
			AtomicFiles.publish(receipt(output), stage -> {
				Files.write(stage, stamp);
				AtomicFiles.move(jarStage, output);
			});
		} finally {
			Files.deleteIfExists(jarStage);
		}
	}

	static List<String> arguments(String forge, Path cache, Path jdkCache, Path output, boolean offline, boolean refresh) {
		final List<String> args = new ArrayList<>(List.of("--maven", "--artifact", "net.minecraftforge:forge", "--version", forge,
				"--mappings", "official", "--cache", cache.toString(), "--jdk-cache", jdkCache.toString(), "--output", output.toString()));

		if (offline) {
			args.add("--offline");
		}

		if (refresh) {
			args.add("--ignore-cache");
		}

		return args;
	}

	static List<String> sourceArguments(String forge, Path cache, Path jdkCache, Path output) {
		final List<String> args = arguments(forge, cache, jdkCache, output, true, false);
		args.add("--cache-only");
		return args;
	}

	static String javaInstallations(String java8, String java21, String java25) {
		final List<String> installations = List.of(java8, java21, java25);

		if (installations.stream().anyMatch(path -> path.contains(","))) {
			throw new IllegalArgumentException("Mavenizer 无法解析含逗号的 JDK 安装路径：" + installations);
		}

		return "-Dorg.gradle.java.installations.paths=" + String.join(",", installations);
	}

	static Path resolveTool(Path cache, boolean offline) throws IOException {
		final Path tool = cache.resolve(TOOL_JAR);

		if (Files.isRegularFile(tool, LinkOption.NOFOLLOW_LINKS) && Checksum.of(tool).sha256().matchesStr(TOOL_SHA256)) {
			return tool;
		}

		if (offline) {
			throw new IOException("离线模式缺少 SHA-256 校验通过的 Mavenizer " + TOOL_VERSION + "：" + tool);
		}

		AtomicFiles.publish(tool, tmp -> {
			try {
				Download.create(TOOL_URL).downloadPath(tmp);
			} catch (URISyntaxException e) {
				throw new IOException("Mavenizer 官方下载地址无效", e);
			}

			final String actual = Checksum.of(tmp).sha256().hex();

			if (!TOOL_SHA256.equals(actual)) {
				throw new IOException("Mavenizer " + TOOL_VERSION + " SHA-256 不匹配：期望 " + TOOL_SHA256 + "，实际 " + actual);
			}
		});
		return tool;
	}
}
