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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

/**
 * 以目标版本元数据的语义值作为配置输入，隔离下载与派生缓存的维护状态.
 *
 * <p>所有文件访问都在 obtain 内完成：派生文件从未命中到创建、304 更新时间戳以及其它版本
 * 清单变化都不单独进入配置指纹。Gradle 在复用前重新求值，仅当返回的目标元数据变化才失效。
 * 参数只保存路径、字符串与布尔值快照，不持有 Project、Loom 扩展或捕获项目的下载回调。
 */
public abstract class MinecraftMetadataValueSource implements ValueSource<String, MinecraftMetadataValueSource.Parameters> {
	public static Provider<String> create(ProviderFactory providers, MinecraftMetadataProvider.Options options, boolean offline, boolean manualRefresh) {
		final var names = new ArrayList<String>();
		final var urls = new ArrayList<String>();

		// 按原集合实际迭代顺序快照；不在本次修复中改变既有的多来源选择次序。
		for (ManifestLocations.ManifestLocation location : options.versionsManifests()) {
			names.add(location.name());
			urls.add(location.url());
		}

		return providers.of(MinecraftMetadataValueSource.class, spec -> {
			final Parameters parameters = spec.getParameters();
			parameters.getMinecraftVersion().set(options.minecraftVersion());
			parameters.getManifestNames().set(names);
			parameters.getManifestUrls().set(urls);
			parameters.getCustomManifestUrl().set(options.customManifestUrl());
			parameters.getUserCache().set(options.userCache().toFile());
			parameters.getWorkingDirectory().set(options.workingDir().toFile());
			parameters.getOffline().set(offline);
			parameters.getManualRefresh().set(manualRefresh);
		});
	}

	@Override
	public String obtain() {
		final Parameters parameters = getParameters();
		final var names = parameters.getManifestNames().get();
		final var urls = parameters.getManifestUrls().get();
		final var manifests = new ManifestLocations();

		for (int i = 0; i < names.size(); i++) {
			manifests.add(names.get(i), urls.get(i), i);
		}

		final var options = new MinecraftMetadataProvider.Options(
				parameters.getMinecraftVersion().get(), manifests, parameters.getCustomManifestUrl().getOrNull(),
				parameters.getUserCache().get().getAsFile().toPath(),
				parameters.getWorkingDirectory().get().getAsFile().toPath()
		);
		final boolean offline = parameters.getOffline().get();
		final boolean manualRefresh = parameters.getManualRefresh().get();
		final var resolver = new MinecraftMetadataProvider(options, url -> download(url, offline, manualRefresh));
		final MinecraftVersionMeta metadata = resolver.getVersionMeta();
		return LoomGradlePlugin.GSON.toJson(canonicalize(LoomGradlePlugin.GSON.toJsonTree(metadata)));
	}

	private static DownloadBuilder download(String url, boolean offline, boolean manualRefresh) {
		try {
			final DownloadBuilder builder = Download.create(url);

			if (offline) {
				builder.offline();
			}

			if (manualRefresh) {
				builder.forceDownload();
			}

			return builder;
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("无效的 Minecraft 元数据 URL：" + url, e);
		}
	}

	// 对象键顺序与空白不属于元数据语义；数组次序（如参数、库列表）保持原样。
	private static JsonElement canonicalize(JsonElement element) {
		if (element.isJsonObject()) {
			final var result = new JsonObject();
			element.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
					.forEach(entry -> result.add(entry.getKey(), canonicalize(entry.getValue())));
			return result;
		}

		if (element.isJsonArray()) {
			final var result = new JsonArray();
			element.getAsJsonArray().forEach(value -> result.add(canonicalize(value)));
			return result;
		}

		return element;
	}

	public interface Parameters extends ValueSourceParameters {
		Property<String> getMinecraftVersion();
		ListProperty<String> getManifestNames();
		ListProperty<String> getManifestUrls();
		Property<String> getCustomManifestUrl();
		DirectoryProperty getUserCache();
		DirectoryProperty getWorkingDirectory();
		Property<Boolean> getOffline();
		Property<Boolean> getManualRefresh();
	}
}
