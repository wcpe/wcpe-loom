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

package net.fabricmc.loom.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * L2 规格层的持久化存储.
 *
 * <p>配置期只允许读「小数据」，但读不等于可以反复解析：Mojang 的版本清单是全版本清单
 * （数百 KB ～ MB），而每次配置只需要其中一条；{@code minecraft_info.json} 每次配置都要
 * 重新做一次全文 SHA1 校验。这些工作在每次配置、每个子项目上重复，且不随构建结果变化。
 *
 * <p>本类把「解析结果」与「已验证」这两类状态按输入文件的内容身份落盘，使它们跨 daemon、
 * 跨工作树复用。键由 {@link #inputIdentity(Path)} 生成，取值来自文件的大小与最后修改时间——
 * 下载器的落位是原子的，文件不会出现「大小与时间不变而内容改变」的情形。
 *
 * <p>与 {@code ~/.gradle/caches} 下既有的手写就绪标记不同，本目录只存放**纯派生数据**：
 * 任何条目都可安全删除，删除后只是重新解析一次，不影响正确性。因此它不需要参与
 * 构建产物的有效性判定，也不会像产物标记那样在配置期形成「观察 → 填充 → 失效」的循环。
 */
public final class SpecStore {
	private static final Logger LOGGER = LoggerFactory.getLogger(SpecStore.class);

	/** 存储格式版本：内容结构变化时递增，使旧条目自然失效. */
	private static final String FORMAT_VERSION = "v1";

	private final Path root;

	public SpecStore(Path userCache) {
		this.root = userCache.resolve("spec");
	}

	/**
	 * 生成输入文件的内容身份串.
	 *
	 * <p>取大小与最后修改时间而非内容哈希：调用方关心的是「这个文件是不是上次那个」，
	 * 而计算内容哈希本身就是要省掉的成本。下载产物一律原子落位，故该组合足以区分代次。
	 *
	 * @param file 输入文件
	 * @return 形如 {@code size-mtime} 的身份串；文件不存在时返回空
	 */
	public static Optional<String> inputIdentity(Path file) {
		try {
			final BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			return Optional.of(attrs.size() + "-" + attrs.lastModifiedTime().toMillis());
		} catch (NoSuchFileException e) {
			return Optional.empty();
		} catch (IOException e) {
			LOGGER.debug("Failed to read attributes of {}, treating as cache miss", file, e);
			return Optional.empty();
		}
	}

	/**
	 * 读取已缓存的解析结果.
	 *
	 * @param namespace 逻辑分区（例如 {@code "versions-manifest"}），不同用途互不干扰
	 * @param identity 输入身份串，见 {@link #inputIdentity(Path)}
	 * @param discriminator 同一输入下区分不同查询的额外维度（例如目标 Minecraft 版本）
	 * @return 缓存内容；未命中时为空
	 */
	public Optional<String> load(String namespace, String identity, String discriminator) {
		final Path path = entryPath(namespace, identity, discriminator);

		try {
			return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
		} catch (NoSuchFileException e) {
			return Optional.empty();
		} catch (IOException e) {
			// 读不动一律按未命中处理：本目录只存派生数据，重算即可，不值得打断构建
			LOGGER.debug("Failed to read spec cache entry {}", path, e);
			return Optional.empty();
		}
	}

	/**
	 * 写入解析结果.
	 *
	 * <p>原子落位，且失败不抛异常：缓存写不进去只是下次多解析一次，
	 * 不应让它成为构建失败的原因。
	 *
	 * @param namespace 逻辑分区
	 * @param identity 输入身份串
	 * @param discriminator 额外维度
	 * @param content 待缓存内容
	 */
	public void store(String namespace, String identity, String discriminator, String content) {
		final Path path = entryPath(namespace, identity, discriminator);

		try {
			AtomicFiles.publish(path, tmp -> Files.writeString(tmp, content, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
		} catch (IOException e) {
			LOGGER.debug("Failed to write spec cache entry {}", path, e);
		}
	}

	private Path entryPath(String namespace, String identity, String discriminator) {
		final String fileName = FORMAT_VERSION + "-" + sanitize(discriminator) + ".json";
		return root.resolve(namespace).resolve(identity).resolve(fileName);
	}

	/** 把任意维度值收敛成安全文件名，避免路径分隔符与超长名字. */
	private static String sanitize(String value) {
		final String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");

		if (cleaned.length() <= 96) {
			return cleaned;
		}

		// 超长名字截断并附上稳定后缀，避免与其它截断结果相撞
		return cleaned.substring(0, 88) + "-" + Integer.toHexString(value.hashCode());
	}
}
