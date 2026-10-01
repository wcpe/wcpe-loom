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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.util.DependencyDownloader;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.IvyArtifactRepository;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ZipUtils;

public class ForgeUserdevProvider extends DependencyProvider {
	/** FG2 形态 userdev jar 内的 AT 文件名，与 {@link #createLegacyAts()} 的条目保持一致. */
	private static final String LEGACY_AT_FILE = "merged_at.cfg";
	/** FG2 形态 userdev jar 内的源码包名，{@link #createLegacySources} 从这里取出并落位到本地 maven 仓库. */
	private static final String LEGACY_SOURCES_FILE = "sources.zip";

	/**
	 * userdev3 归一后使用的 libraries 清单.
	 *
	 * <p>取消费方实测可用的 7 条精简集合（构建与校验所需的公共库），而非 userdev3 自带的 22 条：
	 * 后者含 scala/akka 与 {@code net.minecraftforge:legacydev} 等只在 Gradle 启动器里用到的库，
	 * 其中 scala 系的 {@code *_mc} 版本已从 mavenCentral 下架，只有 forge 仓库的 artifact-only
	 * 形态才能解析。锁定这份固定清单可让产物字节不随上游 libraries 列表漂移。
	 */
	private static final List<String> LEGACY_LIBRARIES = List.of(
			"net.minecraft:launchwrapper:1.12",
			"org.ow2.asm:asm-debug-all:5.2",
			"lzma:lzma:0.0.1",
			"net.sf.jopt-simple:jopt-simple:5.0.3",
			"org.apache.maven:maven-artifact:3.5.3",
			"org.apache.logging.log4j:log4j-api:2.15.0",
			"org.apache.logging.log4j:log4j-core:2.15.0"
	);

	private File userdevJar;
	private JsonObject json;
	private UserdevConfig config;
	private Path joinedPatches;
	private Boolean isLegacyForge;

	public ForgeUserdevProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		userdevJar = new File(getExtension().getForgeProvider().getGlobalCache(), "forge-userdev.jar");
		joinedPatches = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("patches-joined.lzma");
		Path configJson = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("forge-config.json");

		// 缓存内保留 forge 发布的原始配置（userdev3 也原样保留），形态判定与 manifest 派生都以它为准
		byte[] configBytes = Files.notExists(configJson) ? null : Files.readAllBytes(configJson);
		UserdevForm form = configBytes == null ? null : UserdevForm.of(parse(configBytes));
		JsonObject rawJson;

		if (needsUserdevRework(form)) {
			File resolved = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve Forge userdev"));
			configBytes = readUserdevConfig(resolved.toPath());
			rawJson = parse(configBytes);
			form = UserdevForm.of(rawJson);
			Files.write(configJson, configBytes);

			if (form == UserdevForm.USERDEV3) {
				// userdev3 与 legacy 分支期待的 FG2 形态不兼容，必须在配置期归一——依赖解析本身就发生在
				// 配置期（afterEvaluate），任务级 dependsOn 一律晚于该时点
				json = createNormalizedUserdev3Jar(dependency, resolved.toPath(), rawJson);
			} else {
				Files.copy(resolved.toPath(), userdevJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
				json = createManifest(dependency, rawJson, form, userdevJar.toPath());
			}
		} else {
			// 命中缓存：manifest 由缓存的原始配置重新派生（legacy 形态的 sources 落位是幂等的）
			rawJson = parse(configBytes);
			json = createManifest(dependency, rawJson, form, userdevJar.toPath());
		}

		isLegacyForge = form.isLegacy();

		config = UserdevConfig.CODEC.parse(JsonOps.INSTANCE, json)
				.getOrThrow(false, msg -> getProject().getLogger().error("Couldn't read userdev config, {}", msg));

		addDependency(config.mcp(), Constants.Configurations.MCP_CONFIG);

		if (!getExtension().isNeoForge()) {
			addDependency(config.mcp(), Constants.Configurations.SRG);
		}

		addDependency(config.universal(), Constants.Configurations.FORGE_UNIVERSAL);

		if (!isLegacyForge && Files.notExists(joinedPatches)) {
			Files.write(joinedPatches, ZipUtils.unpack(userdevJar.toPath(), config.binpatches()));
		}
	}

	/**
	 * {@return 是否需要重新解析 forge userdev 制品并重建缓存}.
	 *
	 * <p>除缓存缺失与 {@code refreshDeps} 外还要覆盖一种情况：缓存里的 userdev3 尚未归一
	 * （或归一过程被中断）——此时 jar 内不会有 FG2 形态的 AT 与 sources 条目，必须重建。
	 */
	private boolean needsUserdevRework(@Nullable UserdevForm cachedForm) {
		if (cachedForm == null || !userdevJar.exists() || refreshDeps()) {
			return true;
		}

		return cachedForm == UserdevForm.USERDEV3
				&& (!ZipUtils.contains(userdevJar.toPath(), LEGACY_AT_FILE) || !ZipUtils.contains(userdevJar.toPath(), LEGACY_SOURCES_FILE));
	}

	/**
	 * 读取 userdev jar 内的原始配置：现代与 userdev3 形态在 {@code config.json}，FG2 形态在 {@code dev.json}.
	 */
	private static byte[] readUserdevConfig(Path userdevJar) throws IOException {
		try {
			return ZipUtils.unpack(userdevJar, "config.json");
		} catch (NoSuchFileException e) {
			// If we cannot find a modern config json, try the legacy/FG2-era one
			try {
				return ZipUtils.unpack(userdevJar, "dev.json");
			} catch (NoSuchFileException e1) {
				e.addSuppressed(e1);
				throw e;
			}
		}
	}

	private static JsonObject parse(byte[] configBytes) {
		return new Gson().fromJson(new String(configBytes, StandardCharsets.UTF_8), JsonObject.class);
	}

	/**
	 * 按形态派生 loom 内部使用的 manifest.
	 *
	 * @param userdevJarPath manifest 所引用的 userdev jar，legacy 形态的 sources 要从它内部取
	 */
	private JsonObject createManifest(DependencyInfo dependency, JsonObject rawJson, UserdevForm form, Path userdevJarPath) throws IOException {
		return switch (form) {
		case FORGE_GRADLE_2 -> createManifestFromForgeGradle2(dependency, rawJson, userdevJarPath);
		case USERDEV3 -> createManifestFromUserdev3(dependency, rawJson, userdevJarPath);
		case MODERN -> rawJson;
		};
	}

	/**
	 * userdev 配置的形态.
	 */
	private enum UserdevForm {
		/** FG2 时代（MC 1.7-1.12）的 userdev：配置在 {@code dev.json} 里，且没有 {@code mcp} 段. */
		FORGE_GRADLE_2,
		/** FG2.3 过渡形态：1.12.2 的 Forge build ≥ 2851 只发布这一种，配置里的 {@code libraries} 是纯字符串. */
		USERDEV3,
		/** 现代 Forge / NeoForge 形态. */
		MODERN;

		/** {@return 该形态是否要走 legacy（FG2）管线. } */
		boolean isLegacy() {
			return this != MODERN;
		}

		static UserdevForm of(JsonObject json) {
			if (isUserdev3(json)) {
				return USERDEV3;
			}

			return json.has("mcp") ? MODERN : FORGE_GRADLE_2;
		}

		/**
		 * {@return 是否为 userdev3 形态}.
		 *
		 * <p>不能只用「有没有 {@code mcp} 键」判定：userdev3 带 {@code mcp} 键，现代形态也带。
		 * 实测 1.12.2 的 14.23.5.2851/2860 的 userdev3 配置带 {@code notchObf: true} 与 {@code spec: 2}，
		 * 而 1.14-1.21 的现代 userdev 与 NeoForge 都不带 {@code notchObf} 字段，据此区分。
		 */
		private static boolean isUserdev3(JsonObject json) {
			final JsonElement notchObf = json.get("notchObf");
			final JsonElement spec = json.get("spec");

			return notchObf != null && notchObf.isJsonPrimitive() && notchObf.getAsBoolean()
					&& spec != null && spec.isJsonPrimitive() && spec.getAsInt() <= 2;
		}
	}

	private JsonObject createManifestFromForgeGradle2(DependencyInfo dependency, JsonObject fg2Json, Path userdevJarPath) throws IOException {
		JsonObject json = new JsonObject();

		addLegacyMCPRepo();
		String mcVersion = fg2Json.get("inheritsFrom").getAsString();
		json.addProperty("mcp", getMCPNotation(mcVersion));

		json.addProperty("universal", dependency.getDepString() + ":universal");
		json.addProperty("sources", createLegacySources(dependency, userdevJarPath));
		json.addProperty("patches", "");
		json.addProperty("binpatches", "");
		json.add("binpatcher", createLegacyBinpatcher());
		json.add("libraries", createLegacyLibs(fg2Json));
		json.add("runs", createLegacyRuns());
		json.add("ats", createLegacyAts());

		return json;
	}

	/**
	 * 把 userdev3 的配置归一为 legacy（FG2）形态的 manifest.
	 *
	 * <p>与 {@link #createManifestFromForgeGradle2} 并列，binpatcher / runs / AT / sources 都复用同一批辅助方法，
	 * 只替换 userdev3 特有的差异：
	 * <ul>
	 *     <li>配置里没有 {@code inheritsFrom}，MC 版本改由 Forge 版本推导</li>
	 *     <li>{@code libraries} 是纯字符串数组，且改用 {@link #LEGACY_LIBRARIES} 精简清单</li>
	 * </ul>
	 *
	 * @param userdevJarPath 已归一的 FG2 形态 jar（sources.zip 由 {@link #createNormalizedUserdev3Jar} 放进去）
	 */
	private JsonObject createManifestFromUserdev3(DependencyInfo dependency, JsonObject userdev3Json, Path userdevJarPath) throws IOException {
		JsonObject json = new JsonObject();

		addLegacyMCPRepo();
		final String mcVersion = new ForgeProvider.ForgeVersion(dependency.getResolvedVersion()).getMinecraftVersion();
		json.addProperty("mcp", getMCPNotation(mcVersion));

		json.addProperty("universal", dependency.getDepString() + ":universal");
		json.addProperty("sources", createLegacySources(dependency, userdevJarPath));
		json.addProperty("patches", "");
		json.addProperty("binpatches", "");
		json.add("binpatcher", createLegacyBinpatcher());
		JsonArray libraries = new JsonArray();
		LEGACY_LIBRARIES.forEach(libraries::add);
		json.add("libraries", libraries);
		json.add("runs", createLegacyRuns());
		json.add("ats", createLegacyAts());

		return json;
	}

	private static String getMCPNotation(String mcVersion) {
		return "de.oceanlabs.mcp:mcp:" + mcVersion + ":srg@zip";
	}

	/**
	 * 把 userdev3 改写为 legacy 分支期待的 FG2 形态 jar，落盘到 {@link #userdevJar}.
	 *
	 * <p>两者在 1.12.2 上的差异（实测）：
	 * <ul>
	 *     <li>{@code config.json} 是 FG2.3 结构，缺 {@code inheritsFrom}，{@code libraries} 为纯字符串</li>
	 *     <li>AT 在 {@code ats/forge_at.cfg}，而 legacy 管线的 {@link #createLegacyAts()} 只认 jar 根目录的 {@code merged_at.cfg}</li>
	 *     <li>源码是远程 {@code :sources} 分类器产物，而 legacy 管线的 {@link #createLegacySources} 只认 jar 内的 {@code sources.zip}</li>
	 * </ul>
	 *
	 * <p>改写全程在临时文件上完成：既避免污染 Gradle 模块缓存里的原始制品，也避免失败时留下半写的缓存 jar。
	 * {@code config.json} 最后写入，因为 manifest 里的 sources 需要先把 {@code sources.zip} 放进 jar。
	 *
	 * @return 写入 jar 的 FG2 manifest
	 */
	private JsonObject createNormalizedUserdev3Jar(DependencyInfo dependency, Path sourceJar, JsonObject userdev3Json) throws IOException {
		final Path target = userdevJar.toPath();
		final Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");

		try {
			Files.copy(sourceJar, temp, StandardCopyOption.REPLACE_EXISTING);
			ZipUtils.add(temp, LEGACY_AT_FILE, ZipUtils.unpack(sourceJar, getUserdev3AtsPath(userdev3Json)));
			ZipUtils.add(temp, LEGACY_SOURCES_FILE, downloadUserdev3Sources(userdev3Json));

			final JsonObject manifest = createManifestFromUserdev3(dependency, userdev3Json, temp);
			ZipUtils.add(temp, "config.json", new Gson().toJson(manifest));

			move(temp, target);
			return manifest;
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * {@return userdev3 配置里声明的 AT 路径}.
	 *
	 * <p>ATTENTION：内容是 {@code ats/forge_at.cfg} 而落位到 {@code merged_at.cfg}，与 FG2 userdev
	 * 自带的 {@code merged_at.cfg} 是「语义近似」而非等价转换——实测 1.12.2 的 2860 与 2847 上两者
	 * 的有效指令（去掉空行与注释行后）完全一致，但字节不同（前者含更多空行与注释行）。
	 */
	private static String getUserdev3AtsPath(JsonObject userdev3Json) {
		final JsonArray ats = userdev3Json.getAsJsonArray("ats");

		if (ats == null || ats.isEmpty()) {
			throw new IllegalStateException("Forge userdev3 config does not declare any access transformers");
		}

		return ats.get(0).getAsString();
	}

	/**
	 * {@return userdev3 声明的远程源码包内容}.
	 *
	 * <p>userdev3 的 {@code sources} 是远程坐标，形如 {@code net.minecraftforge:forge:<v>:sources@jar}。
	 * 取非传递依赖，确保拿到的就是 sources 制品本身。
	 */
	private byte[] downloadUserdev3Sources(JsonObject userdev3Json) throws IOException {
		final String notation = userdev3Json.get("sources").getAsString();
		final File sourcesJar = DependencyDownloader.download(getProject(), notation, false, true).getSingleFile();

		return Files.readAllBytes(sourcesJar.toPath());
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static JsonObject createLegacyBinpatcher() {
		JsonObject binpatcher = new JsonObject();
		binpatcher.addProperty("version", "net.minecraftforge:binarypatcher:1.1.1:fatjar");
		JsonArray args = new JsonArray();
		List.of("--clean", "{clean}", "--output", "{output}", "--apply", "{patch}").forEach(args::add);
		binpatcher.add("args", args);
		return binpatcher;
	}

	private static JsonArray createLegacyLibs(JsonObject json) {
		JsonArray array = new JsonArray();

		for (JsonElement lib : json.getAsJsonArray("libraries")) {
			array.add(lib.getAsJsonObject().get("name"));
		}

		return array;
	}

	private static JsonObject createLegacyRuns() {
		JsonObject clientRun = new JsonObject();
		JsonObject serverRun = new JsonObject();
		clientRun.addProperty("name", "client");
		serverRun.addProperty("name", "server");
		clientRun.addProperty("main", Constants.LegacyForge.LAUNCH_WRAPPER);
		serverRun.addProperty("main", Constants.LegacyForge.LAUNCH_WRAPPER);
		JsonArray clientArgs = new JsonArray();
		JsonArray serverArgs = new JsonArray();
		clientArgs.add("--tweakClass");
		serverArgs.add("--tweakClass");
		clientArgs.add(Constants.LegacyForge.FML_TWEAKER);
		serverArgs.add(Constants.LegacyForge.FML_SERVER_TWEAKER);
		clientArgs.add("--accessToken");
		serverArgs.add("--accessToken");
		clientArgs.add("undefined");
		serverArgs.add("undefined");
		clientRun.add("args", clientArgs);
		serverRun.add("args", serverArgs);
		JsonObject runs = new JsonObject();
		runs.add("client", clientRun);
		runs.add("server", serverRun);
		return runs;
	}

	private static JsonArray createLegacyAts() {
		JsonArray array = new JsonArray();
		array.add(LEGACY_AT_FILE);
		return array;
	}

	private String createLegacySources(DependencyInfo dependency, Path userdevJarPath) throws IOException {
		Path sourceRepo = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("source-repo");
		String group = dependency.getDependency().getGroup();
		String name = dependency.getDependency().getName() + "_sources";
		String version = dependency.getResolvedVersion();
		LocalMavenHelper sourcesMaven = new LocalMavenHelper(group, name, version, "sources", sourceRepo);
		getProject().getRepositories().maven(repo -> {
			repo.setName("LoomFG2Source");
			repo.setUrl(sourceRepo);
		});

		if (!sourcesMaven.exists(null)) {
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(userdevJarPath, false)) {
				sourcesMaven.copyToMaven(fs.getPath(LEGACY_SOURCES_FILE), null);
			}
		}

		return sourcesMaven.getNotation();
	}

	private void addLegacyMCPRepo() {
		getProject().getRepositories().ivy(repo -> {
			// Old MCP data does not have POMs
			repo.setName("LegacyMCP");
			repo.setUrl("https://maven.minecraftforge.net/");
			repo.patternLayout(layout -> {
				layout.artifact("[orgPath]/[artifact]/[revision]/[artifact]-[revision](-[classifier])(.[ext])");
				// also check the zip so people do not have to explicitly specify the extension for older versions
				layout.artifact("[orgPath]/[artifact]/[revision]/[artifact]-[revision](-[classifier]).zip");
			});
			repo.content(descriptor -> {
				descriptor.includeGroup("de.oceanlabs.mcp");
			});
			repo.metadataSources(IvyArtifactRepository.MetadataSources::artifact);
		});
	}

	public boolean isLegacyForge() {
		if (isLegacyForge == null) {
			throw new IllegalArgumentException("Not yet resolved.");
		}

		return isLegacyForge;
	}

	public File getUserdevJar() {
		return userdevJar;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_USERDEV;
	}

	public JsonObject getJson() {
		return json;
	}

	public UserdevConfig getConfig() {
		return config;
	}

	public Path getJoinedPatches() {
		return joinedPatches;
	}
}
