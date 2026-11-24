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

package dev.architectury.loom.mcpconfig.steplogic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;

import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * Downloads a file from the Minecraft version metadata.
 */
public final class DownloadManifestFileLogic extends StepLogic<DownloadManifestFileLogic.Options> {
	public static final ServiceType<Options, DownloadManifestFileLogic> TYPE = new ServiceType<>(Options.class, DownloadManifestFileLogic.class);

	public interface Options extends Service.Options {
		@Input
		Property<MinecraftVersionMeta.Download> getDownload();

		@Input
		Property<String> getSide();

		@Internal
		DirectoryProperty getVersionCache();
	}

	public static Provider<Options> createOptions(SetupContext context, MinecraftVersionMeta.Download download, Path versionCache, String side) {
		return TYPE.create(context.project(), options -> {
			options.getDownload().set(download);
			options.getVersionCache().set(versionCache.toFile());
			options.getSide().set(side);
		});
	}

	public DownloadManifestFileLogic(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	@Override
	public void execute(ExecutionContext context) throws IOException {
		final MinecraftVersionMeta.Download download = getOptions().getDownload().get();
		final String side = getOptions().getSide().get();
		final String expected = download.sha1();

		if (expected == null || !expected.matches("[0-9a-fA-F]{40}")) {
			throw new IOException("Invalid official SHA-1 for MCP " + side + " mappings: " + expected);
		}

		final String sha1 = expected.toLowerCase(Locale.ROOT);
		final Path versionCache = getOptions().getVersionCache().get().getAsFile().toPath();
		final String name = side + ".txt";
		final Path persistent = versionCache.resolve("mcp/official-mappings").resolve(sha1).resolve(name);
		final Path output = context.setOutput("output");
		final Path layered = versionCache.resolve("layered/working_dir");

		if (context.offline() || !context.refreshDownloads()) {
			if (matchesSha1(persistent, sha1)) {
				copyVerified(persistent, output, sha1);
				return;
			}

			if (Files.isDirectory(layered)) {
				try (Stream<Path> layers = Files.list(layered)) {
					for (Path layer : layers.sorted().toList()) {
						final Path candidate = layer.resolve("mojang").resolve(name);

						if (matchesSha1(candidate, sha1)) {
							copyVerified(candidate, persistent, sha1);
							copyVerified(persistent, output, sha1);
							return;
						}
					}
				}
			}
		}

		if (context.offline()) {
			throw new IOException("Official MCP " + side + " mappings unavailable in offline mode (SHA-1 " + sha1
					+ "): no matching file at " + persistent + " or under " + layered);
		}

		AtomicFiles.publish(persistent, temporary -> {
			context.downloadBuilder(download.url()).sha1(sha1).downloadPath(temporary);
			requireSha1(temporary, sha1);
		});
		copyVerified(persistent, output, sha1);
	}

	private static boolean matchesSha1(Path path, String sha1) throws IOException {
		if (!Files.isRegularFile(path)) {
			return false;
		}

		try {
			return Checksum.of(path).sha1().matchesStr(sha1);
		} catch (UncheckedIOException e) {
			if (e.getCause() instanceof NoSuchFileException) {
				return false;
			}

			throw e.getCause();
		}
	}

	private static void requireSha1(Path path, String sha1) throws IOException {
		if (!matchesSha1(path, sha1)) {
			throw new IOException("Official MCP mappings SHA-1 mismatch for " + path + " (expected " + sha1 + ")");
		}
	}

	private static void copyVerified(Path source, Path target, String sha1) throws IOException {
		AtomicFiles.publish(target, temporary -> {
			Files.copy(source, temporary);
			requireSha1(temporary, sha1);
		});
	}
}
