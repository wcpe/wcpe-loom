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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract sealed class SingleJarMinecraftProvider extends MinecraftProvider permits SingleJarMinecraftProvider.Server, SingleJarMinecraftProvider.Client {
	private final MappingsNamespace officialNamespace;
	private Path minecraftEnvOnlyJar;

	private SingleJarMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
		super(metadataProvider, configContext);
		this.officialNamespace = officialNamespace;
	}

	public static SingleJarMinecraftProvider.Server server(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		return new SingleJarMinecraftProvider.Server(metadataProvider, configContext, getOfficialNamespace(metadataProvider, true));
	}

	public static SingleJarMinecraftProvider.Client client(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		return new SingleJarMinecraftProvider.Client(metadataProvider, configContext, getOfficialNamespace(metadataProvider, false));
	}

	private static MappingsNamespace getOfficialNamespace(MinecraftMetadataProvider metadataProvider, boolean server) {
		// Some versions before 1.3 don't have a common namespace, so use side specific namespaces.
		if (metadataProvider.getVersionMeta().isLegacySplitOfficialNamespaceVersion()) {
			return server ? MappingsNamespace.SERVER_OFFICIAL : MappingsNamespace.CLIENT_OFFICIAL;
		}

		return MappingsNamespace.OFFICIAL;
	}

	@Override
	protected void initFiles() {
		super.initFiles();

		minecraftEnvOnlyJar = path("minecraft-%s-only.jar".formatted(type()));
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftEnvOnlyJar);
	}

	@Override
	public void provide() throws Exception {
		super.provide();

		// Server only JARs are supported on any version, client only JARs are pretty much useless after 1.3.
		if (provideClient() && !isLegacyVersion()) {
			getProject().getLogger().warn("Using `clientOnlyMinecraftJar()` is not recommended for Minecraft versions 1.3 or newer.");
		}

		// 无锁快路径：env-only jar 已就绪且未要求刷新时不获取文件锁
		boolean requiresRefresh = getExtension().refreshDeps() || Files.notExists(minecraftEnvOnlyJar);

		if (!requiresRefresh) {
			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
		final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, cacheKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：可能已被他人在等锁期间生产完成
			if (!getExtension().refreshDeps() && Files.exists(minecraftEnvOnlyJar)) {
				return null;
			}

			final Path inputJar = getInputJar(this);

			TinyRemapper remapper = null;

			try {
				remapper = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE).build();

				Files.deleteIfExists(minecraftEnvOnlyJar);

				final TinyRemapper effectiveRemapper = remapper;
				// 原子发布：remapper 先写到同目录唯一临时 jar，完整后再原子 move 到 minecraftEnvOnlyJar
				AtomicFiles.publish(minecraftEnvOnlyJar, tmpJar -> {
					// Pass through tiny remapper to fix the meta-inf
					try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(tmpJar).build()) {
						outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, effectiveRemapper);
						effectiveRemapper.readInputs(inputJar);
						effectiveRemapper.apply(outputConsumer);
					}
				});
			} catch (Exception e) {
				Files.deleteIfExists(minecraftEnvOnlyJar);
				throw new RuntimeException("Failed to process %s only jar".formatted(type()), e);
			} finally {
				if (remapper != null) {
					remapper.finish();
				}
			}

			return null;
		});
	}

	// 区分 server/client env-only jar，避免 legacy-merged 下两个 single jar provider 用同一把锁互相串扰
	@Override
	protected String cacheKey() {
		return "minecraft:" + minecraftVersion() + ":" + type();
	}

	public Path getMinecraftEnvOnlyJar() {
		return minecraftEnvOnlyJar;
	}

	@Override
	public MappingsNamespace getOfficialNamespace() {
		return officialNamespace;
	}

	abstract SingleJarEnvType type();

	abstract Path getInputJar(SingleJarMinecraftProvider provider) throws Exception;

	public static final class Server extends SingleJarMinecraftProvider {
		private Server(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
			super(metadataProvider, configContext, officialNamespace);
		}

		@Override
		public SingleJarEnvType type() {
			return SingleJarEnvType.SERVER;
		}

		@Override
		public Path getInputJar(SingleJarMinecraftProvider provider) {
			BundleMetadata serverBundleMetadata = provider.getServerBundleMetadata();

			if (serverBundleMetadata == null) {
				return provider.getMinecraftServerJar().toPath();
			}

			return provider.getMinecraftExtractedServerJar().toPath();
		}

		@Override
		protected boolean provideServer() {
			return true;
		}

		@Override
		protected boolean provideClient() {
			return false;
		}
	}

	public static final class Client extends SingleJarMinecraftProvider {
		private Client(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
			super(metadataProvider, configContext, officialNamespace);
		}

		@Override
		public SingleJarEnvType type() {
			return SingleJarEnvType.CLIENT;
		}

		@Override
		public Path getInputJar(SingleJarMinecraftProvider provider) throws Exception {
			return provider.getMinecraftClientJar().toPath();
		}

		@Override
		protected boolean provideServer() {
			return false;
		}

		@Override
		protected boolean provideClient() {
			return true;
		}
	}
}
