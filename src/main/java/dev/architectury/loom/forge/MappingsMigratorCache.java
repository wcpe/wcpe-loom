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
 * 迁移器缓存的**内容键**与「内容 + 旁车键」的读写.
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
 * <p>读方只在内容键**逐字相等**时才采信内容文件，否则一律当作未命中、走冷分支重算。键由「决定迁移结果的量」
 * 构成（见 {@link #identity}）：ns 开关 + 原始 mappings 的摘要 + 各 jar 的摘要。缺失键、键不符两种情况都落到
 * 「重算」这一侧，方向是安全的——键不符最多多算一次，绝不会把别代的迁移结果当成本代结果。
 *
 * <p>代价是暖分支也要把输入读一遍算摘要（15-16MB 的 jar 在几十 ms 级），而冷分支要做的是「解开 jar、
 * 扫全部 class、解析整棵映射树」，高出一个数量级，因此这份开销换来「产物只由声明输入决定」是划算的。
 *
 * <h2>键为什么放在旁车文件，而不是内容文件里</h2>
 *
 * <p>内容文件（{@code migrated-fields.json} / {@code method-inheritance-migrator.json}）位于**跨版本共享**的
 * 缓存目录，旧版 loom（1.17.11 及以前）会把它整份当作扁平数据解析：前者是 {@code Map<String,String>}，
 * 后者是 {@code Pair} 数组。把键与内容包成 {@code {"key":…,"entries":…}} 信封会让旧版在**配置期硬失败**
 * （{@code JsonSyntaxException: Expected a string but was BEGIN_OBJECT}），也就是「把 loom 版本回退一档」这种
 * 正常运维动作会把仓库直接打瘫——新旧版对缓存格式的容忍度是**不对称**的：新版能读旧格式（键缺失即重算），
 * 旧版读不了新格式。因此键改放独立的旁车文件 {@code <内容文件名>.key}：内容文件**永远保持旧版认识的扁平形状**，
 * 旧版读它一切照旧（语义仍是旧的、有缺陷，但那是旧版自己的事）；新版靠旁车键决定命中与否，未命中即重算并
 * 同时刷新两者。旧版写下的缓存没有旁车键 → 新版视为未命中 → 重算，方向同样安全。
 *
 * <p>旁车键里还记着内容文件的摘要：键与内容是两次独立的原子落位，跨 daemon 并发下可能读到「新键 + 旧内容」
 * 的组合，摘要不符即判未命中，把这次竞态也挡在「重算」这一侧。
 */
final class MappingsMigratorCache {
	/**
	 * 键的格式标记.
	 *
	 * <p>键的组成或旁车语义一旦变化就递增它：旧缓存随即全部失效并自动重算，无需人工清理共享目录。
	 */
	private static final String FORMAT = "loom-forge-migrator-cache-v1";

	/** 旁车键文件名后缀，追加在内容文件名之后（{@code migrated-fields.json} → {@code migrated-fields.json.key}）. */
	private static final String KEY_SUFFIX = ".key";

	private static final String KEY_FIELD = "key";
	private static final String ENTRIES_SHA256_FIELD = "entriesSha256";

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

	/** {@return 内容文件对应的旁车键文件路径}. */
	static Path keyFile(Path cacheFile) {
		return cacheFile.resolveSibling(cacheFile.getFileName() + KEY_SUFFIX);
	}

	/**
	 * 读缓存：旁车键与本代输入逐字相等、且内容摘要自洽时返回内容文件的根节点，其余情形一律返回 {@code null}
	 * （视为未命中，由调用方重算）.
	 *
	 * <p>每一种「不可信」都归到这里，且都留日志，避免「缓存被静默忽略」难以排查：内容文件或旁车键缺失、
	 * 旁车键无法解析、键与当前输入不符、内容摘要与旁车键记录的不符（并发换代）、内容文件无法解析。
	 *
	 * @param cacheFile 内容文件（保持旧版认识的扁平形状）
	 * @param expectedKey 当前输入算出的内容键
	 * @param logger 日志出口（调用方自己的 logger，配置期与执行期各写各的）
	 * @return 内容文件解析出的根节点；{@code null} 表示未命中
	 */
	static @Nullable JsonElement read(Path cacheFile, String expectedKey, Logger logger) throws IOException {
		if (Files.notExists(cacheFile)) {
			return null;
		}

		final Path keyFile = keyFile(cacheFile);

		if (Files.notExists(keyFile)) {
			// 旧版 loom 写下的缓存没有旁车键：宁可重算，也不能把来源不明的内容当成本代结果
			logger.info("迁移器缓存没有内容键旁车文件（{}），按未命中处理", keyFile);
			return null;
		}

		final JsonObject keyRoot;

		try {
			final JsonElement parsed = JsonParser.parseString(Files.readString(keyFile));

			if (parsed == null || !parsed.isJsonObject()) {
				logger.info("迁移器缓存的内容键不是对象（{}），按未命中处理", keyFile);
				return null;
			}

			keyRoot = parsed.getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			logger.info("迁移器缓存的内容键无法解析（{}），按未命中处理", keyFile);
			return null;
		}

		final JsonElement key = keyRoot.get(KEY_FIELD);

		if (key == null || !key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()) {
			logger.info("迁移器缓存的内容键字段缺失或形状不符（{}），按未命中处理", keyFile);
			return null;
		}

		if (!expectedKey.equals(key.getAsString())) {
			logger.info("迁移器缓存的内容键与当前输入不符（{}），按未命中处理", cacheFile);
			return null;
		}

		final JsonElement entriesSha256 = keyRoot.get(ENTRIES_SHA256_FIELD);

		if (entriesSha256 == null || !entriesSha256.isJsonPrimitive() || !entriesSha256.getAsJsonPrimitive().isString()) {
			logger.info("迁移器缓存的内容摘要字段缺失或形状不符（{}），按未命中处理", keyFile);
			return null;
		}

		final String payload = Files.readString(cacheFile);

		if (!Checksum.of(payload).sha256().matchesStr(entriesSha256.getAsString())) {
			// 键与内容是两次独立的原子落位：跨 daemon 并发下可能读到「新键 + 旧内容」的组合
			logger.info("迁移器缓存的内容与内容键不同代（{}），按未命中处理", cacheFile);
			return null;
		}

		try {
			return JsonParser.parseString(payload);
		} catch (JsonParseException | IllegalStateException e) {
			logger.info("迁移器缓存无法解析（{}），按未命中处理", cacheFile);
			return null;
		}
	}

	/**
	 * 原子发布「内容 + 旁车键」.
	 *
	 * <p>缓存位于跨 daemon 共享目录，就地覆盖会让读方看到半截文件，故两份文件都沿用既有的
	 * 「一次性临时文件 + 原子 move」。先落内容、后落键：键一旦就位就说明它描述的那份内容已经在盘上，
	 * 剩下的并发窗口由旁车键里的内容摘要兜住。
	 *
	 * <p><b>内容文件写的是 {@code entries} 本身，不带任何信封</b>——旧版 loom 要能整份读它，见类注释。
	 *
	 * @param cacheFile 内容文件
	 * @param key 写内容时用的内容键（下一次读它的人会重新算一遍并比对）
	 * @param entries 迁移结果（字段迁移器是对象，方法迁移器是数组）
	 */
	static void publish(Path cacheFile, String key, JsonElement entries) throws IOException {
		final String payload = entries.toString();
		AtomicFiles.publish(cacheFile, tmp -> Files.writeString(tmp, payload));

		final JsonObject keyRoot = new JsonObject();
		keyRoot.addProperty(KEY_FIELD, key);
		keyRoot.addProperty(ENTRIES_SHA256_FIELD, Checksum.of(payload).sha256().hex());
		AtomicFiles.publish(keyFile(cacheFile), tmp -> Files.writeString(tmp, keyRoot.toString()));
	}
}
