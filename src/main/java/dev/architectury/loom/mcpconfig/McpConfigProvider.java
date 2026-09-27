/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2022 FabricMC
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

package dev.architectury.loom.mcpconfig;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.architectury.loom.forge.dependency.DependencyProvider;
import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.gradle.LoomCacheService;

public class McpConfigProvider extends DependencyProvider {
	private Path mcp;
	private Path configJson;
	private Path unpacked;
	private String version;
	private McpConfigData data;

	public McpConfigProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		init(dependency.getDependency().getVersion());

		if (getExtension().isLegacyForge()) {
			//CHECKSTYLE:OFF
			String json = """
{
  "version": "$version",
  "data": {
    "mappings": "TODO mappings"
  },
  "steps": {
    "joined": [
      {
        "type": "downloadClient"
      },
      {
        "type": "downloadServer"
      },
      {
        "name": "rename",
        "type": "downloadManifest"
      }
    ]
  },
  "functions": {}
}
					""".replace("$version", Objects.requireNonNull(dependency.getDependency().getVersion()));
			//CHECKSTYLE:ON

			data = McpConfigData.fromJson(new Gson().fromJson(json, JsonObject.class));
			return;
		}

		Path mcpZip = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve MCPConfig")).toPath();

		// 无锁快路径：zip 与解包目录都已就绪且未要求刷新时不触碰共享缓存，也就无需取锁
		if (needsZip() || needsUnpacked()) {
			produceWithLock(mcpZip);
		}

		JsonObject json;

		try (Reader reader = Files.newBufferedReader(configJson)) {
			json = new Gson().fromJson(reader, JsonObject.class);
		}

		data = McpConfigData.fromJson(json);
	}

	/**
	 * {@return zip 是否需要（重新）发布}.
	 *
	 * <p>无锁快路径的一部分：产物已就绪且未要求刷新时无需重建，也就不取锁。该判定只读，可在锁外安全调用。
	 * 保持「存在即可用」口径而不额外比对输入尺寸：同一构件在不同镜像下 zip 尺寸可能不同，比对会让两个
	 * 使用不同镜像的进程互相覆盖，造成每次构建都重建。
	 */
	private boolean needsZip() {
		return refreshDeps() || Files.notExists(mcp);
	}

	/**
	 * {@return 解包目录是否需要（重新）构建}.
	 *
	 * <p>以 {@code config.json} 而非解包目录本身作为就绪标记：写入方先解包到同目录的临时目录再整体落位，
	 * 故 {@code config.json} 存在 ⟹ 整棵树完整；反过来，残缺目录（旧写法中途失败留下的）会被识别出来并重建。
	 */
	private boolean needsUnpacked() {
		return refreshDeps() || Files.notExists(configJson);
	}

	/**
	 * 在跨进程锁保护下生产 MCPConfig 的 zip 与解包目录，两者按件重建.
	 *
	 * <p>路径位于跨 daemon 共享的 {@code userCache}（不按项目隔离），同一 MC 版本 + MCPConfig 版本的多个
	 * 并发构建会写同一路径；由首个取得锁的进程写入，其余进程等待后直接复用（锁内二次确认）。
	 * 旧写法全程无锁：先就地覆盖 zip，再递归删除整个解包目录后重新解包，读方会看到「目录存在但内容不全」
	 * 的半成品，是 Forge 全链输入被破坏的主要来源。
	 */
	private void produceWithLock(Path mcpZip) {
		final String lockKey = "mcp-config:" + getMinecraftProvider().minecraftVersion() + ":" + version;
		final Path lockRoot = mcp.getParent().resolve(Constants.Cache.LOCKS_DIR);

		try {
			LoomCacheService.get(getProject()).get().runExclusive(lockRoot, lockKey, LoomCacheService.defaultTimeout(), () -> {
				// 锁内二次确认：等锁期间其它进程可能已发布就绪的产物，按件判定避免重复生产
				final boolean publishZip = needsZip();

				if (publishZip) {
					// 原子发布：读方要么看到完整 zip，要么看不到（旧写法就地覆盖会留下半截 zip）
					AtomicFiles.copy(mcpZip, mcp);
				}

				if (publishZip || needsUnpacked()) {
					publishUnpacked(publishZip);
				}

				return null;
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException("Could not unpack MCPConfig " + version, e);
		}
	}

	/**
	 * 解包 {@code mcp.zip} 到共享目录，整棵树按「先建临时目录、再整体落位」发布.
	 *
	 * <p>目录无法像文件那样原子覆盖（rename 到非空目录在多数平台直接失败），因此按两种情形分别处理：
	 * <ul>
	 *     <li>非强制重建且目标目录已完整：丢弃本次解包的临时目录，保持既有目录原样。读方全程可见完整目录，
	 *         且解包结果由 zip 唯一决定，保留旧目录与换成新目录等价，不换取那份「短暂缺失」的风险；</li>
	 *     <li>强制重建或目标目录缺失/残缺：把旧目录先用原子 rename 挪到唯一名字（不做原地递归删除：
	 *         读方可能正打开其中的文件，删掉正在读的文件比让它读到旧内容更糟），再把临时目录 rename 就位。
	 *         两步之间存在目标目录短暂缺失的窗口，但此时本就没有有效目录可保留，且只在显式刷新或目录残缺时发生；
	 *         若第二步失败，旧目录以 {@code *.old} 留在原地，下次构建按「目录残缺」重建，可自愈。</li>
	 * </ul>
	 *
	 * @param force 本次是否因输入变化而强制重建，为 true 时即使目标目录完整也替换
	 */
	private void publishUnpacked(boolean force) throws IOException {
		Files.createDirectories(unpacked.getParent());
		final Path temp = AtomicFiles.tempSibling(unpacked);

		try {
			Files.createDirectory(temp);
			ZipUtils.unpackAll(mcp, temp);

			if (!force && Files.exists(configJson)) {
				return;
			}

			// 唯一旧名：避免与其它进程残留的旧目录互相覆盖
			final Path stale = unpacked.resolveSibling(unpacked.getFileName() + "." + UUID.randomUUID() + ".old");

			if (Files.exists(unpacked)) {
				AtomicFiles.move(unpacked, stale);
			}

			AtomicFiles.move(temp, unpacked);
			// 清理失败只影响磁盘占用（新目录已就位），不应让构建失败
			deleteDirectoryQuietly(stale);
		} finally {
			deleteDirectoryQuietly(temp);
		}
	}

	/**
	 * 删除目录，失败仅告警.
	 *
	 * <p>仅用于清理「已 rename 走的旧目录」与「被丢弃的临时目录」：这些目录不再被任何读方使用，
	 * 删除失败最多留下垃圾文件，不影响产物正确性，故不向上抛出。
	 */
	private void deleteDirectoryQuietly(Path directory) {
		if (Files.notExists(directory)) {
			return;
		}

		try {
			DeletingFileVisitor.deleteDirectory(directory);
		} catch (IOException e) {
			getProject().getLogger().warn("删除 MCPConfig 缓存目录失败（仅影响磁盘占用）：{}", directory, e);
		}
	}

	private void init(String version) {
		this.version = version;
		String mcpName = getExtension().isNeoForge() ? "neoform" : "mcp";
		Path dir = getMinecraftProvider().dir(mcpName + "/" + version).toPath();
		mcp = dir.resolve("mcp.zip");
		unpacked = dir.resolve("unpacked");
		configJson = unpacked.resolve("config.json");
	}

	public boolean hasMappings() {
		return data.hasMappings();
	}

	public Path getMappings() {
		if (!hasMappings()) {
			throw new UnsupportedOperationException("MCP config has no mappings (spec 6+ unobfuscated)");
		}

		return unpacked.resolve(getMappingsPath());
	}

	public Path getUnpackedZip() {
		return unpacked;
	}

	public Path getMcp() {
		return mcp;
	}

	public boolean isOfficial() {
		return data.official();
	}

	public String getMappingsPath() {
		return data.mappingsPath();
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MCP_CONFIG;
	}

	public McpConfigData getData() {
		return data;
	}
}
