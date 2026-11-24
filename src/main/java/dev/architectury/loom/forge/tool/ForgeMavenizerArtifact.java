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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

/** 已按模块元数据核实的同一套官方重编译产物. */
public record ForgeMavenizerArtifact(Path classesJar, Path metadataZip, Path sourcesJar, Path officialToSrgMappings) {
	private static final String GAME = "net/minecraft/client/Minecraft";
	private static final String FORGE_API = "net/minecraftforge/common/MinecraftForge";

	public static ForgeMavenizerArtifact resolve(Path repository, String forgeVersion, String minecraftVersion, String mcpVersion) throws IOException {
		for (String version : new String[]{forgeVersion, minecraftVersion, mcpVersion}) {
			check(version != null && version.matches("[a-zA-Z0-9][a-zA-Z0-9._+-]*"), "Invalid Maven version: " + version);
		}

		Path root = repository.toRealPath();
		Path dir = root.resolve("net/minecraftforge/forge").resolve(forgeVersion);
		Path module = checkedPath(root, dir.resolve("forge-" + forgeVersion + ".module"));

		try (var reader = Files.newBufferedReader(module)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			JsonObject gav = json.getAsJsonObject("component");
			check(is(gav, "group", "net.minecraftforge") && is(gav, "module", "forge") && is(gav, "version", forgeVersion), "Wrong component: " + gav);
			JsonObject classes = null;
			JsonObject metadata = null;
			JsonObject sources = null;

			for (JsonElement element : json.getAsJsonArray("variants")) {
				JsonObject variant = element.getAsJsonObject();
				JsonObject attrs = variant.getAsJsonObject("attributes");

				if (is(attrs, "org.gradle.usage", "metadata")) {
					metadata = sharedFile(metadata, variant);
				} else if (is(attrs, "org.gradle.usage", "java-runtime") && is(attrs, "org.gradle.libraryelements", "jar")
						&& is(attrs, "net.minecraftforge.mappings.channel", "official") && is(attrs, "net.minecraftforge.mappings.version", minecraftVersion)) {
					if (is(attrs, "org.gradle.category", "library")) {
						check(hasMappingsDependency(variant, mcpVersion), "Unexpected mappings_official dependency in " + variant.get("name"));
						classes = sharedFile(classes, variant);
					} else if (is(attrs, "org.gradle.category", "documentation") && is(attrs, "org.gradle.docstype", "sources")) {
						sources = sharedFile(sources, variant);
					}
				}
			}

			Path classesJar = artifactFile(dir, classes, ".jar");
			Path metadataZip = artifactFile(dir, metadata, "-metadata.zip");
			Path sourcesJar = artifactFile(dir, sources, "-sources.jar");
			Path mappings = checkedPath(root, root.resolve("net/minecraft/mappings_official").resolve(mcpVersion).resolve("mappings_official-" + mcpVersion + "-map2srg.tsrg.gz"));

			try (JarFile jar = new JarFile(classesJar.toFile()); ZipFile meta = new ZipFile(metadataZip.toFile()); ZipFile src = new ZipFile(sourcesJar.toFile()); BufferedReader map = mappingsReader(mappings)) {
				// 官方 stubify 保留可供编译的类签名，却会删除运行期资源。
				check(jar.getManifest() != null && jar.getEntry("META-INF/mods.toml") != null
						&& jar.getEntry(GAME + ".class") != null && jar.getEntry(FORGE_API + ".class") != null, "Stub or incomplete Forge runtime: " + classesJar);
				check(meta.getEntry("launcher/runs.json") != null && meta.getEntry("minecraft/version.json") != null, "Incomplete metadata: " + metadataZip);
				check(src.getEntry(GAME + ".java") != null && src.getEntry(FORGE_API + ".java") != null, "Incomplete sources: " + sourcesJar);
				// map2obf 也叫 left/right，但它把 Minecraft 本身改成了混淆类名。
				check("tsrg2 left right".equals(map.readLine()) && map.lines().anyMatch((GAME + " " + GAME)::equals), "Unexpected map2srg direction: " + mappings);
			}

			return new ForgeMavenizerArtifact(classesJar, metadataZip, sourcesJar, mappings);
		} catch (RuntimeException e) {
			throw new IOException("Malformed Mavenizer module: " + module, e);
		}
	}

	private static boolean hasMappingsDependency(JsonObject variant, String version) {
		JsonArray dependencies = variant.getAsJsonArray("dependencies");

		if (dependencies != null) {
			for (JsonElement entry : dependencies) {
				JsonObject dep = entry.getAsJsonObject();

				if (is(dep, "group", "net.minecraft") && is(dep, "module", "mappings_official") && is(dep.getAsJsonObject("version"), "requires", version)) {
					return true;
				}
			}
		}

		return false;
	}

	private static boolean is(JsonObject object, String key, String value) {
		return object != null && object.has(key) && value.equals(object.get(key).getAsString());
	}

	private static JsonObject sharedFile(JsonObject previous, JsonObject variant) throws IOException {
		JsonArray files = variant.getAsJsonArray("files");
		check(files != null && files.size() == 1, "Expected exactly one file in " + variant.get("name"));
		JsonObject next = files.get(0).getAsJsonObject();
		// 不按列表顺序猜宿主 OS：所有候选必须严格共用同一个文件声明（含摘要）。
		check(previous == null || previous.equals(next), "Ambiguous Mavenizer files in " + variant.get("name"));
		return next;
	}

	private static Path artifactFile(Path dir, JsonObject file, String suffix) throws IOException {
		check(file != null, "Missing Mavenizer variant for " + suffix + " in " + dir);
		String name = file.get("name").getAsString();
		check(is(file, "url", name) && name.matches("[a-zA-Z0-9][a-zA-Z0-9._+-]*") && name.endsWith(suffix), "Invalid artifact name or URL: " + file);
		Path path = checkedPath(dir, dir.resolve(name));
		check(file.get("size").getAsLong() == Files.size(path) && is(file, "sha256", sha256(path)), "Artifact size or SHA-256 mismatch: " + path);
		return path;
	}

	private static Path checkedPath(Path root, Path path) throws IOException {
		Path real = path.toRealPath();
		check(real.startsWith(root) && real.equals(path) && Files.isRegularFile(real), "Invalid Mavenizer path: " + path);
		return real;
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");

			try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
				input.transferTo(java.io.OutputStream.nullOutputStream());
			}

			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static BufferedReader mappingsReader(Path path) throws IOException {
		InputStream input = Files.newInputStream(path);

		try {
			return new BufferedReader(new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8));
		} catch (IOException e) {
			input.close();
			throw e;
		}
	}

	private static void check(boolean valid, String message) throws IOException {
		if (!valid) {
			throw new IOException(message);
		}
	}

	/** 只变换一次命名空间；Forge 已应用的 AT 与其他资源保留在包内，不重复处理. */
	public void writeSrgJar(Path output) throws IOException {
		// CREATE_NEW 既防止覆盖也取得唯一的清理责任，临时文件不要求有 .jar 后缀。
		Files.createFile(output);
		boolean complete = false;

		try (var reader = mappingsReader(officialToSrgMappings)) {
			try (var seed = new ZipOutputStream(Files.newOutputStream(output))) {
				seed.finish();
			}

			TinyRemapper remapper = TinyRemapper.newRemapper()
					.withMappings(TinyUtils.createTinyMappingProvider(reader, "left", "right"))
					// 同一 Mojang 方法可能覆写两个各有 SRG 名的接口，优先采用声明所有者的直接映射。
					.ignoreConflicts(true)
					.ignoreFieldDesc(true)
					.build();

			try (var consumer = new OutputConsumerPath.Builder(output).assumeArchive(true).build()) {
				remapper.readInputs(classesJar);
				remapper.apply(consumer);
				consumer.addNonClassFiles(classesJar, NonClassCopyMode.FIX_META_INF, remapper);
			} finally {
				remapper.finish();
			}

			complete = true;
		} catch (RuntimeException e) {
			throw new IOException("Failed to remap " + classesJar + " with " + officialToSrgMappings, e);
		} finally {
			if (!complete) {
				Files.deleteIfExists(output);
			}
		}
	}
}
