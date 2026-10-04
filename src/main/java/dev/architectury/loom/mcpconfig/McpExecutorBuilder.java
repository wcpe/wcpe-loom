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

package dev.architectury.loom.mcpconfig;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.mcpconfig.steplogic.ConstantLogic;
import dev.architectury.loom.mcpconfig.steplogic.DownloadManifestFileLogic;
import dev.architectury.loom.mcpconfig.steplogic.FunctionLogic;
import dev.architectury.loom.mcpconfig.steplogic.InjectLogic;
import dev.architectury.loom.mcpconfig.steplogic.ListLibrariesLogic;
import dev.architectury.loom.mcpconfig.steplogic.NoOpLogic;
import dev.architectury.loom.mcpconfig.steplogic.PatchLogic;
import dev.architectury.loom.mcpconfig.steplogic.StepLogic;
import dev.architectury.loom.mcpconfig.steplogic.StripLogic;
import dev.architectury.loom.util.collection.CollectionUtil;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Lazy;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.service.Service;

/**
 * Builds an {@link McpExecutor}'s {@linkplain McpExecutor.Options options} from the project state
 * and enqueued steps.
 */
public final class McpExecutorBuilder {
	/**
	 * 解包目录被写入方替换时，读方等待其「回到原位」的预算.
	 *
	 * <p>窗口本身只有两次 rename 的时长（毫秒级），这里给足余量，以覆盖写方在替换前后做清理的时间。
	 */
	private static final long UNPACKED_SWAP_WAIT_MILLIS = 5_000;
	private static final long UNPACKED_SWAP_POLL_MILLIS = 25;

	private final Project project;
	private final MinecraftProvider minecraftProvider;
	private final Path cache;
	private final List<McpConfigStep> steps;
	private final DependencySet dependencySet;
	private final Map<String, McpConfigFunction> functions;
	private final Map<String, String> config = new HashMap<>();
	private final StepLogic.SetupContext setupContext = new SetupContextImpl();
	private StepLogic.@Nullable StepLogicProvider stepLogicProvider = null;

	public McpExecutorBuilder(Project project, MinecraftProvider minecraftProvider, Path cache, McpConfigProvider provider, String environment) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
		this.cache = cache;
		this.steps = provider.getData().steps().get(environment);
		this.functions = provider.getData().functions();
		this.dependencySet = new DependencySet(this.steps);
		this.dependencySet.skip(step -> isNoOp(step.type()));

		checkMinecraftVersion(provider);
		addDefaultFiles(provider, environment);
	}

	private void checkMinecraftVersion(McpConfigProvider provider) {
		final String expected = provider.getData().version();
		final String actual = minecraftProvider.minecraftVersion();

		if (!expected.equals(actual)) {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);
			final ForgeProvider forgeProvider = extension.getForgeProvider();
			final String message = "%s %s is not for Minecraft %s (expected: %s)."
					.formatted(
							extension.getPlatform().get().displayName(),
							forgeProvider.getVersion().getCombined(),
							actual,
							expected
					);

			if (GradleUtils.getBooleanProperty(project, Constants.Properties.ALLOW_MISMATCHED_PLATFORM_VERSION)) {
				project.getLogger().warn(message);
			} else {
				final String fullMessage = "%s\nYou can suppress this error by adding '%s = true' to gradle.properties."
						.formatted(message, Constants.Properties.ALLOW_MISMATCHED_PLATFORM_VERSION);
				throw new UnsupportedOperationException(fullMessage);
			}
		}
	}

	private void addDefaultFiles(McpConfigProvider provider, String environment) {
		for (Map.Entry<String, JsonElement> entry : provider.getData().data().entrySet()) {
			if (entry.getValue().isJsonPrimitive()) {
				addDefaultFile(provider, entry.getKey(), entry.getValue().getAsString());
			} else if (entry.getValue().isJsonObject()) {
				JsonObject json = entry.getValue().getAsJsonObject();

				if (json.has(environment) && json.get(environment).isJsonPrimitive()) {
					addDefaultFile(provider, entry.getKey(), json.getAsJsonPrimitive(environment).getAsString());
				}
			}
		}
	}

	/**
	 * 把 MCP 配置声明的输入文件加入执行参数.
	 *
	 * <p>解包目录位于跨 daemon 共享的 userCache，写入方替换整棵目录时（旧目录 rename 走、新目录 rename 就位）
	 * 存在一个「目录不在原位」的窗口。此处先短暂重试等待写方完成，超时仍不可见即抛出带路径与原因的错误。
	 *
	 * <p>不能像以前那样在文件缺失时静默跳过：撞上窗口会少传 {@code --data/--mappings} 等输入而构建照常成功，
	 * 产出的 patched jar 还会被 manifest 标记为最新并长期复用，正是要消灭的那种静默损坏。
	 */
	private void addDefaultFile(McpConfigProvider provider, String key, String value) {
		final Path unpacked = provider.getUnpackedZip().toAbsolutePath();
		Path path = unpacked.resolve(value).toAbsolutePath();

		if (!path.startsWith(unpacked)) {
			// This is probably not what we're looking for since it falls outside the directory.
			return;
		}

		awaitUnpackedInput(unpacked, path, key);
		addConfig(key, path.toString());
	}

	/**
	 * 等待解包目录与其声明的输入文件就位.
	 *
	 * @param unpacked 解包目录
	 * @param path     配置声明的输入文件
	 * @param key      配置键，仅用于日志与错误信息
	 */
	private void awaitUnpackedInput(Path unpacked, Path path, String key) {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(UNPACKED_SWAP_WAIT_MILLIS);
		boolean loggedWait = false;

		while (Files.notExists(path)) {
			if (System.nanoTime() >= deadline) {
				throw missingUnpackedInput(unpacked, path, key);
			}

			if (!loggedWait) {
				loggedWait = true;
				project.getLogger().info("MCPConfig 输入 {}（配置项 {}）暂不可见，等待解包目录 {} 替换完成", path, key, unpacked);
			}

			try {
				Thread.sleep(UNPACKED_SWAP_POLL_MILLIS);
			} catch (InterruptedException e) {
				// 保留中断状态：这里已经不可能继续等下去，直接把缺失输入报出去
				Thread.currentThread().interrupt();
				throw missingUnpackedInput(unpacked, path, key);
			}
		}
	}

	/**
	 * {@return 「解包目录未就位」的错误，带路径与原因}.
	 *
	 * <p>区分两种成因，便于定位是并发替换（稍后重试即可）还是缓存本身不完整（需要重建缓存）。
	 */
	private static UncheckedIOException missingUnpackedInput(Path unpacked, Path path, String key) {
		final String reason = Files.notExists(unpacked)
				? "解包目录不在原位（很可能正被其它进程替换）"
				: "解包目录存在，但其中没有该输入（缓存不完整）";

		return new UncheckedIOException(new NoSuchFileException(path.toString(), unpacked.toString(),
				"MCPConfig 配置项 %s 指向的输入在等待 %d 毫秒后仍不可用：%s；缺少该输入会让 MCP 执行链静默少传 --data/--mappings"
						.formatted(key, UNPACKED_SWAP_WAIT_MILLIS, reason)));
	}

	public void addConfig(String key, String value) {
		config.put(key, value);
	}

	/**
	 * {@return 该 URL 在下载缓存中的落位路径}.
	 *
	 * <p><b>刻意只算路径、不碰文件系统</b>：本方法在配置期（步进逻辑选项被配置缓存序列化时）被调用，
	 * 任何存在性判断都会被配置缓存记成文件系统输入指纹，而下载缓存位于任务自有工作目录内、
	 * 每次执行结束即删，其存在性逐次翻转，配置缓存将永久无法复用。目录与文件的落地都在执行期完成。
	 */
	private Path downloadCachePath(String url) {
		return cache.resolve("downloads").resolve(Checksum.of(url).sha256().hex(24));
	}

	/**
	 * Enqueues a step and its dependencies to be executed.
	 *
	 * @param step the name of the step
	 * @return this builder
	 */
	public McpExecutorBuilder enqueue(String step) {
		dependencySet.add(step);
		return this;
	}

	/**
	 * 判断当前 MCP 配置是否声明了指定步骤.
	 *
	 * @param step 步骤名称
	 * @return 配置中存在该步骤时为 true
	 */
	public boolean hasStep(String step) {
		return steps.stream().anyMatch(candidate -> candidate.name().equals(step));
	}

	/**
	 * Builds options for an executor that runs all queued steps and their dependencies.
	 *
	 * @return the options
	 */
	public Provider<McpExecutor.Options> build() throws IOException {
		SortedSet<String> stepNames = dependencySet.buildExecutionSet();
		dependencySet.clear();
		List<McpConfigStep> toExecute = new ArrayList<>();

		for (String stepName : stepNames) {
			McpConfigStep step = CollectionUtil.find(steps, s -> s.name().equals(stepName))
					.orElseThrow(() -> new NoSuchElementException("Step '" + stepName + "' not found in MCP config"));
			toExecute.add(step);
		}

		return McpExecutor.TYPE.create(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			for (McpConfigStep step : toExecute) {
				options.getStepLogicOptions().put(step.name(), getStepLogic(step.name(), step.type()));
			}

			options.getStepsToExecute().set(toExecute);

			if (extension.getMcpConfigProvider().hasMappings()) {
				options.getMappings().set(extension.getMcpConfigProvider().getMappings().toFile());
			}

			options.getInitialConfig().set(config);
			options.getOffline().set(project.getGradle().getStartParameter().isOffline());
			options.getManualRefreshDeps().set(extension.manualRefreshDeps());
			options.getToolServiceOptions().set(ForgeToolService.createOptions(project));
			options.getCache().set(cache.toFile());
		});
	}

	/**
	 * Sets the custom step logic provider of this executor.
	 *
	 * @param stepLogicProvider the provider, or null to disable
	 */
	public void setStepLogicProvider(StepLogic.@Nullable StepLogicProvider stepLogicProvider) {
		this.stepLogicProvider = stepLogicProvider;
	}

	private boolean isNoOp(String stepType) {
		return "downloadManifest".equals(stepType) || "downloadJson".equals(stepType);
	}

	private Provider<? extends Service.Options> getStepLogic(String name, String type) {
		if (stepLogicProvider != null) {
			final @Nullable Provider<? extends Service.Options> custom = stepLogicProvider.getStepLogic(setupContext, name, type);
			if (custom != null) return custom;
		}

		return switch (type) {
		case "downloadManifest", "downloadJson" -> NoOpLogic.createOptions(setupContext);
		case "downloadClient" -> ConstantLogic.createOptions(setupContext, () -> minecraftProvider.getMinecraftClientJar().toPath());
		case "downloadServer" -> ConstantLogic.createOptions(setupContext, () -> minecraftProvider.getMinecraftServerJar().toPath());
		case "strip" -> StripLogic.createOptions(setupContext);
		case "listLibraries" -> ListLibrariesLogic.createOptions(setupContext);
		case "downloadClientMappings" -> DownloadManifestFileLogic.createOptions(setupContext,
				Objects.requireNonNull(minecraftProvider.getVersionInfo().download("client_mappings"),
						"client_mappings download is not available for this Minecraft version"));
		case "downloadServerMappings" -> DownloadManifestFileLogic.createOptions(setupContext,
				Objects.requireNonNull(minecraftProvider.getVersionInfo().download("server_mappings"),
						"server_mappings download is not available for this Minecraft version"));
		case "inject" -> InjectLogic.createOptions(setupContext);
		case "patch" -> PatchLogic.createOptions(setupContext);
		default -> {
			if (functions.containsKey(type)) {
				yield FunctionLogic.createOptions(setupContext, functions.get(type));
			}

			throw new UnsupportedOperationException("MCP config step type: " + type);
		}
		};
	}

	private class SetupContextImpl implements StepLogic.SetupContext {
		@Override
		public Project project() {
			return project;
		}

		@Override
		public Path downloadCachePath(String url) {
			return McpExecutorBuilder.this.downloadCachePath(url);
		}

		@Override
		public Path downloadDependency(String notation) {
			final Dependency dependency = project.getDependencies().create(notation);
			final Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);
			configuration.setTransitive(false);
			return configuration.getSingleFile().toPath();
		}

		@Override
		public Provider<FileCollection> getMinecraftLibraries() {
			return project().provider(Lazy.of(() -> {
				project.getLogger().lifecycle(":downloading minecraft libraries, this may take a while...");
				// (1.2) minecraftRuntimeLibraries contains the compile-time libraries as well.
				final Set<File> files = project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_RUNTIME_LIBRARIES).resolve();
				return project.files(files);
			})::get);
		}
	}
}
