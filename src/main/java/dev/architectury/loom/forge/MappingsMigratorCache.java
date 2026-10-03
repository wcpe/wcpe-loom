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

package dev.architectury.loom.forge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * 迁移器缓存的**内容键**与「键 + 内容」信封的读写.
 *
 * <h2>为什么需要内容键</h2>
 *
 * <p>两个迁移器（{@link FieldMappingsMigrator} / {@link MethodInheritanceMappingsMigrator}）把「由输入算出的
 * 中间数据」缓存在 forge 缓存目录下，而那是一条**跨工作树、跨 daemon 共享**的路径
 * （{@code <mcProvider>/<platform>/<forge 版本>}）：它既不含 mappings 标识，也不含 {@code hasSrg}/{@code hasMojang}，
 * 更不含各 jar 的内容。因此「缓存文件存在」完全不能说明盘上这份迁移结果出自当前这批输入——
 * 字段/方法迁移器的冷分支在 {@code hasSrg}/{@code hasMojang} 都为假时照样会写入（写的是空集），
 * 补丁中间产物换代、ns 开关变化、另一棵工作树跑另一套配置，都会改写同一份缓存：**谁最后写谁说了算**。
 * 于是同一份声明输入可以对应两种产物，而任务判 UP-TO-DATE（或命中构建缓存）时既不重算也不写缓存，
 * 盘上就留下与当前输入无关的陈旧产物。
 *
 * <h2>怎么解决</h2>
 *
 * <p>缓存写成 {@code {"key": <内容键>, "entries": <迁移结果>}}，读方只在键**逐字相等**时才采信 {@code entries}，
 * 否则一律当作未命中、走冷分支重算。键由「决定迁移结果的量」构成（见 {@link #identity}）：
 * ns 开关 + 原始 mappings 的摘要 + 各 jar 的摘要。缺失键、旧格式、键不符三种情况都落到「重算」这一侧，
 * 方向是安全的——键不符最多多算一次，绝不会把别代的迁移结果当成本代结果。
 *
 * <p>代价是暖分支也要把输入读一遍算摘要（15-16MB 的 jar 在几十 ms 级），而冷分支要做的是「解开 jar、
 * 扫全部 class、解析整棵映射树」，高出一个数量级，因此这份开销换来「产物只由声明输入决定」是划算的。
 */
final class MappingsMigratorCache {
	/**
	 * 键的格式标记.
	 *
	 * <p>键的组成或信封语义一旦变化就递增它：旧缓存随即全部失效并自动重算，无需人工清理共享目录。
	 */
	private static final String FORMAT = "loom-forge-migrator-cache-v1";

	private MappingsMigratorCache() {
	}

	/**
	 * 计算本代输入的内容键.
	 *
	 * <p>键必须覆盖一切会改变迁移结果的量：命名空间开关（决定读哪一个命名空间、以及是否产出空集）、
	 * 原始 mappings 的内容，以及冷分支会读的每一个 jar。用**内容摘要**而不是路径或时间戳：
	 * 路径相同而内容换代（补丁链重跑、jar 被重新产出）正是需要失效的场景。
	 *
	 * @param hasSrg 本配置是否生成 srg 命名空间的映射
	 * @param hasMojang 本配置是否为 NeoForge（mojang 命名空间）
	 * @param inputs 冷分支会读的文件（原始 mappings 与各 jar），顺序即调用方给出的顺序
	 * @return 内容键；键不相等即认为缓存不可信
	 */
	static String identity(boolean hasSrg, boolean hasMojang, List<Path> inputs) {
		final StringBuilder builder = new StringBuilder(FORMAT);
		builder.append("|srg=").append(hasSrg).append("|mojang=").append(hasMojang);

		for (Path input : inputs) {
			builder.append('|').append(input.getFileName()).append('=').append(Checksum.of(input).sha256().hex());
		}

		return builder.toString();
	}

	/**
	 * 读信封：只有键逐字相等时才返回 {@code entries}，其余情形一律返回 {@code null}（视为未命中，由调用方重算）.
	 *
	 * <p>三种「不可信」都归到这里，且都留日志，避免「缓存被静默忽略」难以排查：
	 * 文件无法解析或压根不是对象（半截内容、被别的写方换成了数组）、没有 {@code key} 字段（改造前写下的旧格式）、
	 * 键与当前输入不符（别代内容）。
	 *
	 * @param cacheFile 缓存文件
	 * @param expectedKey 当前输入算出的内容键
	 * @param logger 日志出口（调用方自己的 logger，配置期与执行期各写各的）
	 * @return 缓存里的迁移结果；{@code null} 表示未命中
	 */
	static @Nullable JsonElement read(Path cacheFile, String expectedKey, Logger logger) throws IOException {
		if (Files.notExists(cacheFile)) {
			return null;
		}

		final JsonElement root;

		try (BufferedReader reader = Files.newBufferedReader(cacheFile)) {
			root = JsonParser.parseReader(reader);
		} catch (JsonParseException | IllegalStateException e) {
			logger.info("迁移器缓存无法解析（{}），按未命中处理", cacheFile);
			return null;
		}

		if (root == null || !root.isJsonObject()) {
			logger.info("迁移器缓存不是「键 + 内容」信封（{}），按未命中处理", cacheFile);
			return null;
		}

		final JsonObject envelope = root.getAsJsonObject();
		final JsonElement key = envelope.get("key");
		final JsonElement entries = envelope.get("entries");

		if (key == null || !key.isJsonPrimitive() || entries == null) {
			logger.info("迁移器缓存没有内容键（{}），按未命中处理", cacheFile);
			return null;
		}

		if (!expectedKey.equals(key.getAsString())) {
			logger.info("迁移器缓存的内容键与当前输入不符（{}），按未命中处理", cacheFile);
			return null;
		}

		return entries;
	}

	/**
	 * 原子发布「内容键 + 迁移结果」.
	 *
	 * <p>缓存位于跨 daemon 共享目录，就地覆盖会让读方看到半截文件，故沿用既有的一次性临时文件 + 原子 move。
	 *
	 * @param cacheFile 缓存文件
	 * @param key 写内容时用的内容键（下一次读它的人会重新算一遍并比对）
	 * @param entries 迁移结果（字段迁移器是对象，方法迁移器是数组）
	 */
	static void publish(Path cacheFile, String key, JsonElement entries) throws IOException {
		final JsonObject envelope = new JsonObject();
		envelope.addProperty("key", key);
		envelope.add("entries", entries);
		AtomicFiles.publish(cacheFile, tmp -> Files.writeString(tmp, envelope.toString()));
	}
}
