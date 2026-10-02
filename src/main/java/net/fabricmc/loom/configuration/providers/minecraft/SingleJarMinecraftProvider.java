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

import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.JarReusability;
import net.fabricmc.loom.util.gradle.LoomCacheService;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract class SingleJarMinecraftProvider extends MinecraftProvider {
	private final MappingsNamespace officialNamespace;
	private Path minecraftEnvOnlyJar;

	protected SingleJarMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
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

	/**
	 * env-only jar 的生产留在配置期，因此 vanilla 链也必须留在配置期.
	 *
	 * <p>{@link #processJar()} 在配置期把 vanilla jar 过一遍 TinyRemapper（{@code readInputs} 会读它的
	 * 全部类），产物才是本 provider 的 jar。若把下载/抽取搬到执行期，配置期就没有可读的输入。
	 * 「env-only 生产任务化」不在本次范围内（它是一次独立的重映射型任务，和 mapped 阶段同批更合适）。
	 *
	 * <p>legacy merged（MC 1.3 之前）经两个本类实例委托生产，因此同样被这条判据覆盖。
	 */
	@Override
	protected @Nullable String projectionBlocker() {
		return "env-only jar（clientOnly/serverOnly 形态）在配置期读 vanilla jar 做 TinyRemapper 透传";
	}

	@Override
	public void provide() throws Exception {
		super.provide();

		// Server only JARs are supported on any version, client only JARs are pretty much useless after 1.3.
		if (provideClient() && !isLegacyVersion()) {
			getProject().getLogger().warn("Using `clientOnlyMinecraftJar()` is not recommended for Minecraft versions 1.3 or newer.");
		}

		processJar();
	}

	protected void processJar() throws Exception {
		// 无锁快路径：env-only jar 已就绪（内容级判据，见 JarReusability.isReusable）且未要求刷新时不获取文件锁。
		// 不能只判存在：产物落在跨 daemon／跨工作树共享的 <userCache>/<mcVersion> 下，remap 被中断会留下
		// 0 字节或截断的 jar；PR #8 移除「残留锁 → 全量重建」兜底后，存在性判定会把它永久复用。
		boolean requiresRefresh = getExtension().refreshDeps() || !JarReusability.isReusable(minecraftEnvOnlyJar);

		if (!requiresRefresh) {
			return;
		}

		final LoomCacheService cacheService = LoomCacheService.get(getProject()).get();
		final Path lockRoot = getExtension().getFiles().getCacheLocks().toPath();

		cacheService.runExclusive(lockRoot, cacheKey(), LoomCacheService.defaultTimeout(), () -> {
			// 锁内二次确认：可能已被他人在等锁期间生产完成
			if (!getExtension().refreshDeps() && JarReusability.isReusable(minecraftEnvOnlyJar)) {
				return null;
			}

			final Path inputJar = getInputJar(this);

			TinyRemapper remapper = null;

			try {
				remapper = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE).build();

				final TinyRemapper effectiveRemapper = remapper;
				// 原子发布：remapper 先写到同目录唯一临时 jar，完整后再原子 move 到 minecraftEnvOnlyJar。
				// 这里不先 deleteIfExists(minecraftEnvOnlyJar)：那会制造「产物不存在」窗口，与上面无锁快路径的
				// 存在性检查直接冲突（读方误判为未就绪，于是争抢重建锁）；原子替换本身也不需要先删。
				AtomicFiles.publish(minecraftEnvOnlyJar, tmpJar -> {
					// Pass through tiny remapper to fix the meta-inf
					try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(tmpJar).build()) {
						outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, effectiveRemapper);
						effectiveRemapper.readInputs(inputJar);
						effectiveRemapper.apply(outputConsumer);
					}
				});
			} catch (Exception e) {
				// 失败路径不删除共享产物：旧的有效文件可能正被其它进程读取；
				// 本次未完成的中间结果只存在于临时文件里，由 publish 负责清理。
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

	protected abstract SingleJarEnvType type();

	protected abstract Path getInputJar(SingleJarMinecraftProvider provider) throws Exception;

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
