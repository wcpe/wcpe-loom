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
	 * <p>本方法是原始缓存解析器；配置期调用须经 {@link BundleMetadataValueSource}，
	 * 将缓存查询、重算与写回一起隔离，只观察可用状态和有效元数据。
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

		final Optional<CachedMetadata> cached = loadCached(store, artifactSha1);

		if (cached.isPresent()) {
			return cached.get().metadata();
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

	/**
	 * {@return 该制品是否已有 bundle 元数据缓存}.
	 *
	 * <p>缓存必须包含有效元数据；精确空字符串表示已确认的 legacy。损坏条目按未命中处理。
	 * 本方法不打开 jar；配置期须经 {@link BundleMetadataValueSource} 观察该语义结果，
	 * 不直接把派生 JSON 的排版等维护信息记录为配置输入。
	 *
	 * @param store L2 规格存储
	 * @param artifactSha1 该制品在 version json 中声明的 sha1，作为缓存身份；为空时必然未命中
	 */
	public static boolean isCached(SpecStore store, @Nullable String artifactSha1) {
		return loadCached(store, artifactSha1).isPresent();
	}

	// 同时供可用性判定和实际读取使用，避免两条入口对损坏缓存作出不同结论。
	static Optional<CachedMetadata> loadCached(SpecStore store, @Nullable String artifactSha1) {
		if (artifactSha1 == null) {
			return Optional.empty();
		}

		final Optional<String> cached = store.load(NAMESPACE_BUNDLE_METADATA, artifactSha1, "server-bundle");

		if (cached.isEmpty()) {
			return Optional.empty();
		}

		if (cached.get().isEmpty()) {
			// 只有精确空字符串才是已确认的 legacy；JSON null 与纯空白都属于损坏缓存。
			return Optional.of(new CachedMetadata(null));
		}

		try {
			final BundleMetadata metadata = LoomGradlePlugin.GSON.fromJson(cached.get(), BundleMetadata.class);

			if (metadata == null || metadata.mainClass() == null || metadata.mainClass().isBlank()
					|| !validEntries(metadata.libraries()) || !validEntries(metadata.versions())) {
				return Optional.empty();
			}

			return Optional.of(new CachedMetadata(metadata));
		} catch (RuntimeException e) {
			LOGGER.debug("Corrupt bundle metadata cache for sha1 {}, treating as cache miss", artifactSha1, e);
			return Optional.empty();
		}
	}

	private static boolean validEntries(@Nullable List<Entry> entries) {
		return entries != null && entries.stream().allMatch(entry -> entry != null
				&& entry.sha1() != null && !entry.sha1().isBlank()
				&& entry.name() != null && !entry.name().isBlank()
				&& entry.path() != null && !entry.path().isBlank());
	}

	// 包装实例表示缓存可用；其中 metadata 为空则是有效的 legacy 结论。
	record CachedMetadata(@Nullable BundleMetadata metadata) { }

	/**
	 * 从 bootstrap jar 中抽取一个内嵌条目到目标位置.
	 *
	 * <p>抽取规则（原子发布 + 目标文件上的 {@code LoomHash} 标记）由这里唯一持有：配置期路径
	 * （{@link Entry#unpackEntry(Path, Path, Project)}）与执行期任务
	 * （{@code ExtractMinecraftServerJarTask}）都走本方法，避免出现第二套抽取逻辑——
	 * 两套逻辑产出的 jar 只要差一点，「任务产物」与「配置期产物」就会在同一个共享目录里互相判为不可复用。
	 *
	 * @param jar 内嵌条目所在的 bootstrap jar
	 * @param dest 抽取目标
	 * @param path 条目在 jar 中的路径
	 * @param sha1 条目声明的 sha1，作为「已抽取」标记写入目标文件
	 * @param refreshDeps 为真时忽略既有目标、强制重新抽取
	 */
	public static void unpackEntry(Path jar, Path dest, String path, String sha1, boolean refreshDeps) throws IOException {
		if (!refreshDeps && Files.exists(dest)) {
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
				Files.copy(fs.get().getPath(path), tmp, StandardCopyOption.REPLACE_EXISTING);
			}

			writeHash(tmp, sha1);
		});
	}

	/** {@return 目标文件上记录的 hash 标记} 读取失败时为空（视为「标记不可用」，不打断构建）. */
	private static Optional<String> readHash(Path output) {
		try {
			return AttributeHelper.readAttribute(output, "LoomHash");
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	/** 把 sha1 写进目标文件的 {@code LoomHash} 标记，供下次抽取时判定「已抽取且内容正确」. */
	private static void writeHash(Path output, String eTag) {
		try {
			AttributeHelper.writeAttribute(output, "LoomHash", eTag);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write hash to (%s)".formatted(output), e);
		}
	}

	public record Entry(String sha1, String name, String path) {
		public void unpackEntry(Path jar, Path dest, Project project) throws IOException {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);
			BundleMetadata.unpackEntry(jar, dest, path, sha1, extension.refreshDeps());
		}
	}
}
