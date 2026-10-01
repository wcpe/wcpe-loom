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

package net.fabricmc.loom.configuration.providers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.spec.SpecStore;
import net.fabricmc.loom.util.AttributeHelper;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.cache.AtomicFiles;

public record BundleMetadata(List<Entry> libraries, List<Entry> versions, String mainClass) {
	/** L2 规格层命名空间：服务端 bundle 元数据缓存. */
	private static final String NAMESPACE_BUNDLE_METADATA = "bundle-metadata";

	private static final Logger LOGGER = LoggerFactory.getLogger(BundleMetadata.class);

	private static final String LIBRARIES_LIST_PATH = "META-INF/libraries.list";
	private static final String VERSIONS_LIST_PATH = "META-INF/versions.list";
	private static final String MAINCLASS_PATH = "META-INF/main-class";

	@Nullable
	public static BundleMetadata fromJar(Path jar) throws IOException {
		final List<Entry> libraries;
		final List<Entry> versions;
		final String mainClass;

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(jar)) {
			if (!Files.exists(fs.get().getPath(VERSIONS_LIST_PATH))) {
				// Legacy jar
				return null;
			}

			libraries = readEntries(fs.readString(LIBRARIES_LIST_PATH), "META-INF/libraries/");
			versions = readEntries(fs.readString(VERSIONS_LIST_PATH), "META-INF/versions/");
			mainClass = fs.readString(MAINCLASS_PATH).trim();
		}

		return new BundleMetadata(libraries, versions, mainClass);
	}

	/**
	 * 读取服务端制品的 bundle 元数据，结果按制品的 {@code sha1} 落盘复用.
	 *
	 * <p>本方法存在的理由是一个配置期约束：服务端库的注入（
	 * {@code MinecraftLibraryProvider.provideServerLibraries}）依赖这里读出的库列表，
	 * 而库注入只能在配置期做，因此配置期**必须**拿到 bundle 元数据。
	 *
	 * <p>但元数据本身是「下载 url + sha1」的纯函数——这两个值来自 version json，
	 * 不需要碰磁盘。于是把结果按 {@code sha1} 缓存后，后续构建可以完全跳过「打开服务端 jar
	 * 读内部清单」这一步，配置期也就不再观察该 jar。这是把服务端下载搬到执行期的**先决条件**。
	 *
	 * <p>首次构建仍须真实读取（此时无缓存），并因此付出一次配置期文件观察；
	 * 之后稳定命中。
	 *
	 * @param store L2 规格存储
	 * @param jar 服务端 jar
	 * @param artifactSha1 该制品在 version json 中声明的 sha1，作为缓存身份
	 * @return bundle 元数据；非 bundler 的旧版服务端 jar 返回空
	 */
	public static @Nullable BundleMetadata fromJarCached(SpecStore store, Path jar, @Nullable String artifactSha1) throws IOException {
		if (artifactSha1 == null) {
			// 没有稳定身份就无法安全复用，退回直接读取
			return fromJar(jar);
		}

		final Optional<String> cached = store.load(NAMESPACE_BUNDLE_METADATA, artifactSha1, "server-bundle");

		if (cached.isPresent()) {
			// 空内容表示「上次已判定为旧版 jar（无 bundle）」，是结论而非缺失
			if (cached.get().isEmpty()) {
				return null;
			}

			try {
				return LoomGradlePlugin.GSON.fromJson(cached.get(), BundleMetadata.class);
			} catch (RuntimeException e) {
				LOGGER.debug("Corrupt bundle metadata cache for sha1 {}, re-reading jar", artifactSha1, e);
			}
		}

		final BundleMetadata metadata = fromJar(jar);
		store.store(NAMESPACE_BUNDLE_METADATA, artifactSha1, "server-bundle",
				metadata != null ? LoomGradlePlugin.GSON.toJson(metadata) : "");

		return metadata;
	}

	private static List<Entry> readEntries(String content, String pathPrefix) {
		List<Entry> entries = new ArrayList<>();

		for (String entry : content.split("\n")) {
			if (entry.isBlank()) {
				continue;
			}

			String[] split = entry.split("\t");

			if (split.length != 3) {
				continue;
			}

			entries.add(new Entry(split[0], split[1], pathPrefix + split[2]));
		}

		return Collections.unmodifiableList(entries);
	}

	public record Entry(String sha1, String name, String path) {
		public void unpackEntry(Path jar, Path dest, Project project) throws IOException {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			if (!extension.refreshDeps() && Files.exists(dest)) {
				final String hash = readHash(dest).orElse("");

				if (hash.equals(sha1)) {
					// File exists with expected hash
					return;
				}
			}

			// 原子发布：先抽取到同目录唯一临时文件并写入 hash 标记，完整后再原子 move 到 dest。
			// 避免跨进程的无锁存在性检查在抽取期间看到半写的 server jar 误判就绪。
			AtomicFiles.publish(dest, tmp -> {
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(jar)) {
					Files.copy(fs.get().getPath(path()), tmp, StandardCopyOption.REPLACE_EXISTING);
				}

				writeHash(tmp, sha1);
			});
		}

		private Optional<String> readHash(Path output) {
			try {
				return AttributeHelper.readAttribute(output, "LoomHash");
			} catch (IOException e) {
				return Optional.empty();
			}
		}

		private void writeHash(Path output, String eTag) {
			try {
				AttributeHelper.writeAttribute(output, "LoomHash", eTag);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to write hash to (%s)".formatted(output), e);
			}
		}
	}
}
