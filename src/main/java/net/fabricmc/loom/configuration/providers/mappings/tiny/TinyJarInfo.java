/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

package net.fabricmc.loom.configuration.providers.mappings.tiny;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;

public record TinyJarInfo(boolean v2, Optional<String> minecraftVersionId) {
	private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
	private static final String MANIFEST_VERSION_ID_ATTRIBUTE = "Minecraft-Version-Id";

	/**
	 * 进程内缓存：TinyJarInfo 只由 jar 内容决定，而同构项目（共用同一套 mappings）会反复查询同一个 jar。
	 * 键取「路径 + 大小 + 修改时间」，足以识别内容变化；同一 daemon 内所有项目共享.
	 */
	private static final Map<CacheKey, TinyJarInfo> CACHE = new ConcurrentHashMap<>();

	public static TinyJarInfo get(Path jar) {
		final CacheKey key = CacheKey.of(jar);

		if (key != null) {
			final TinyJarInfo cached = CACHE.get(key);

			if (cached != null) {
				return cached;
			}
		}

		final TinyJarInfo info = read(jar);

		if (key != null) {
			CACHE.put(key, info);
		}

		return info;
	}

	private static TinyJarInfo read(Path jar) {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getReadOnlyJarFileSystem(jar)) {
			return new TinyJarInfo(doesJarContainV2Mappings(delegate), getMinecraftVersionId(delegate));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read tiny jar info", e);
		}
	}

	private record CacheKey(String path, long size, long modified) {
		static CacheKey of(Path jar) {
			try {
				return new CacheKey(jar.toAbsolutePath().toString(), Files.size(jar), Files.getLastModifiedTime(jar).toMillis());
			} catch (IOException e) {
				// 取不到指纹时不缓存，退化为每次都读（保持原语义）
				return null;
			}
		}
	}

	private static boolean doesJarContainV2Mappings(FileSystemUtil.Delegate fs) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(fs.getPath("mappings", "mappings.tiny"))) {
			return MappingReader.detectFormat(reader) == MappingFormat.TINY_2_FILE;
		} catch (NoSuchFileException e) {
			return false;
		}
	}

	private static Optional<String> getMinecraftVersionId(FileSystemUtil.Delegate fs) throws IOException {
		final Path manifestPath = fs.getPath(MANIFEST_PATH);

		if (Files.exists(manifestPath)) {
			final var manifest = new Manifest();

			try (InputStream in = Files.newInputStream(manifestPath)) {
				manifest.read(in);
			}

			final String minecraftVersionId = manifest.getMainAttributes().getValue(MANIFEST_VERSION_ID_ATTRIBUTE);
			return Optional.ofNullable(minecraftVersionId);
		}

		return Optional.empty();
	}
}
