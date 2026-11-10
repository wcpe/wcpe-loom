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

package net.fabricmc.loom.configuration.providers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.spec.SpecStore;

/**
 * 服务端 bundle 缓存的配置输入边界，只暴露可用状态与解析结果.
 *
 * <p>未命中返回空值，精确空字符串表示 legacy，bundler 返回规范化的 record JSON。
 * 缓存排版与读后修复不会单独形成配置输入；冷暖可用状态改变仍须重配，以保持任务投影正确。
 */
public abstract class BundleMetadataValueSource implements ValueSource<String, BundleMetadataValueSource.Parameters> {
	public static boolean isCached(ProviderFactory providers, Path userCache, @Nullable String artifactSha1) {
		return create(providers, userCache, null, artifactSha1, false).isPresent();
	}

	public static @Nullable BundleMetadata fromJarCached(ProviderFactory providers, Path userCache, Path jar, @Nullable String artifactSha1) {
		final String value = create(providers, userCache, jar, artifactSha1, true).get();
		return value.isEmpty() ? null : LoomGradlePlugin.GSON.fromJson(value, BundleMetadata.class);
	}

	private static Provider<String> create(ProviderFactory providers, Path userCache, @Nullable Path jar, @Nullable String artifactSha1, boolean resolveMissing) {
		return providers.of(BundleMetadataValueSource.class, spec -> {
			final Parameters parameters = spec.getParameters();
			parameters.getUserCache().set(userCache.toFile());
			parameters.getArtifactSha1().set(artifactSha1);
			parameters.getResolveMissing().set(resolveMissing);

			if (jar != null) {
				parameters.getServerJar().set(jar.toFile());
			}
		});
	}

	@Override
	public @Nullable String obtain() {
		final Parameters parameters = getParameters();
		final var store = new SpecStore(parameters.getUserCache().get().getAsFile().toPath());
		final String sha1 = parameters.getArtifactSha1().getOrNull();
		final BundleMetadata metadata;

		if (parameters.getResolveMissing().get()) {
			try {
				metadata = BundleMetadata.fromJarCached(store, parameters.getServerJar().get().getAsFile().toPath(), sha1);
			} catch (IOException e) {
				throw new UncheckedIOException("读取服务端 bundle 元数据失败", e);
			}
		} else {
			final var cached = BundleMetadata.loadCached(store, sha1);

			if (cached.isEmpty()) {
				return null;
			}

			metadata = cached.get().metadata();
		}

		return metadata == null ? "" : LoomGradlePlugin.GSON.toJson(metadata);
	}

	public interface Parameters extends ValueSourceParameters {
		DirectoryProperty getUserCache();
		RegularFileProperty getServerJar();
		Property<String> getArtifactSha1();
		Property<Boolean> getResolveMissing();
	}
}
