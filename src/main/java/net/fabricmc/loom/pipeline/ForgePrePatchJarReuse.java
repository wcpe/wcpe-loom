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

package net.fabricmc.loom.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.gradle.api.logging.Logger;

import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.cache.JarReusability;

/**
 * 「pre-patch jar 已就绪且与本次输入同代」的判据：命中即跳过整条 MCP 链.
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@code generateForgePrePatchJar} 的产物落在跨项目共享的 {@code <userCache>/<mcVer>/<platform>/<forgeVer>/}，
 * 属于「执行前就已存在、又不是本任务创建的」输出。Gradle 对这种输出的口径是
 * {@code Gradle does not know how file … was created}：构建缓存被直接关掉，up-to-date 也无法在跨工作树 / 跨
 * loom 版本的场景下稳定成立。而这条链是全仓库最贵的动作之一（{@code downloadClient} / {@code extractServer} /
 * {@code mergeMappings} / {@code rename} / {@code mergetool} / {@code binpatch} 等十余步外部进程，单步
 * {@code extractServer} 实测 3~4 分钟），于是「产物明明就在盘上、内容也对」时仍会重跑十几分钟。
 *
 * <p>本类就是那条 action 的前置判据：产物存在、可作为 jar 打开、且<b>产出它的输入与本次输入逐项相同</b>时
 * 直接返回。判据纯读（stat + 小文件的内容摘要），不打开、不解压、不改写产物。
 *
 * <h2>身份从哪来：旁车文件</h2>
 *
 * <p>MCP 链产出的 jar 里没有任何「我是用哪批输入做的」标记（{@code META-INF/MANIFEST.MF} 在绝大多数
 * 1.16+ 版本上根本不存在），而往产物里写清单会改变产物的字节——那正是本仓库逐字节等价性测试所钉住的东西，
 * 不能动。因此身份记在产物旁边的<b>旁车文件</b>里（{@code <产物目录>/.identity/<产物名>.key}），与
 * {@code MappingsMigratorCache} 对迁移器缓存的做法一致：旁车缺失、无法解析、键不符、产物与旁车不同代，
 * 一律落到「不可复用」——方向永远是安全的（最多多跑一次链），绝不会把别代的产物当成这代的。
 *
 * <p>旁车只由生产成功之后写入（{@link #record}），判据本身（{@link #tryReuse}）不写任何文件；
 * 因此「判据会不会悄悄改产物」这个问题在结构上不存在。
 *
 * <h2>为什么这个判据不会让用户拿到错误的 jar</h2>
 *
 * <p>键由两部分拼成，两者都必须逐字相等才复用：
 *
 * <ol>
 *   <li><b>身份前缀</b>（配置期算好的纯值）：链路算法版本（{@code Loom-Patch-Version}）、分支
 *       （{@code mcp-rename} / {@code mcp-unobfuscated} / {@code neoforge-installer-tools}）、平台、
 *       Minecraft 版本、Forge 版本、side（client/server/merged）、中间命名空间。这些量决定「该产出什么」；</li>
 *   <li><b>输入指纹</b>：决定产物内容的每一件输入文件（MCPConfig 的 zip 与解包出的 {@code config.json}、
 *       合并后的 srg 映射、vanilla client/server jar、installer tools 的工具 classpath）。</li>
 * </ol>
 *
 * <p>于是「换 Forge 版本但旧产物还在」被两道锁挡住：新版本的产物路径本身就不同
 * （{@code …/forge/<forgeVer>/minecraft-<type>-<ns>.jar}），而即便有人把旧产物搬到新路径上，身份前缀里的
 * {@code forge=} 与输入指纹（新版本的 vanilla jar / MCPConfig 与旧版本不是同一批文件）都不会相等，
 * 判据落回「照常执行」。同理，换 MC 版本、改 side、切 NeoForge、换 MCPConfig（步进集合或工具版本变了）都会
 * 使键失效。
 *
 * <p>反方向（漏判导致不该跳过时跳过）只有一个入口：某件输入<b>内容变了但 stat 与摘要都没变</b>。大文件
 * （vanilla jar、srg 映射）取「大小 + 最后修改时间」，小文件（{@code config.json} 这类决定步进集合与工具版本的
 * 清单）取内容摘要。这条取舍的依据与 {@code SpecStore.inputIdentity} 同源：链上的输入一律由下载器或
 * 原子落位写入，不会出现「大小与时间不变而内容改变」；而每次构建真去哈希 25~50MB 的 vanilla jar，等于把
 * 「便宜」这个前提丢掉。
 *
 * <h2>产物自身也被钉住</h2>
 *
 * <p>旁车同时记下写入那一刻产物的大小与修改时间。产物后来被别的进程（另一个 loom 版本、另一棵工作树）
 * 改写时两者不符，本判据判「不可复用」并照常执行——这是唯一会让复用失效的常见情形，代价是多跑一次链，
 * 换来的是一直站在「宁可重跑」这一侧。
 */
public final class ForgePrePatchJarReuse {
	/**
	 * 判据的格式版本.
	 *
	 * <p>键的组成、旁车语义或「产品是否同代」的口径一旦变化就递增它：旧旁车随即全部失效并自动重跑一次，
	 * 无需人工清理共享缓存目录。
	 */
	public static final String FORMAT = "loom-forge-pre-patch-jar-v1";

	/** 旁车所在目录名（产物同目录下的隐藏子目录，避免与产物文件名相撞）. */
	private static final String SIDECAR_DIRECTORY = ".identity";

	/** 旁车文件后缀. */
	private static final String SIDECAR_SUFFIX = ".key";

	/**
	 * 「有输入不可读」时追加在键末尾的标记.
	 *
	 * <p>这类键既不会被比较通过，也不会被写进旁车：输入读不到时「相同」无从谈起，此时唯一正确的动作是照常执行。
	 */
	private static final String INCOMPLETE_MARKER = "|incomplete";

	/** 指纹取内容摘要的大小上限（超过则退化为大小 + 修改时间）.*/
	private static final long HASH_MAX_BYTES = 1024L * 1024L;

	/** 文件不可读时的指纹取值；它同时会让整条键带上 {@code incomplete} 标记. */
	private static final String MISSING = "missing";

	private static final String KEY_FIELD = "key=";
	private static final String PRODUCT_FIELD = "product=";

	private ForgePrePatchJarReuse() {
	}

	/**
	 * 把身份前缀与各输入文件的指纹拼成本次执行的键.
	 *
	 * <p>输入按绝对路径排序后参与拼接：调用方给的是集合语义（文件集合的迭代顺序不适合当键），而排序保证
	 * 同样的输入集合永远得到同样的键。
	 *
	 * @param identity 身份前缀（配置期算好的纯值，见类注释）
	 * @param inputs 决定产物内容的输入文件
	 * @return 本次执行的键；有输入不可读时带 {@code incomplete} 标记，永不与记录过的键相等
	 */
	public static String fingerprint(String identity, List<Path> inputs) {
		final List<Path> sorted = new ArrayList<>(inputs.size());

		for (Path input : inputs) {
			final Path normalized = input.toAbsolutePath().normalize();

			if (!sorted.contains(normalized)) {
				sorted.add(normalized);
			}
		}

		sorted.sort(Comparator.comparing(Path::toString));

		final StringBuilder key = new StringBuilder(FORMAT).append('|').append(identity);
		boolean complete = true;

		for (Path input : sorted) {
			final String fingerprint = fingerprintOf(input);

			if (MISSING.equals(fingerprint)) {
				complete = false;
			}

			key.append('|').append(input).append('=').append(fingerprint);
		}

		return complete ? key.toString() : key.append(INCOMPLETE_MARKER).toString();
	}

	/**
	 * 判据本体：产物是否已经就绪且与 {@code expectedKey} 同代.
	 *
	 * <p>纯读。命中时打一条 lifecycle 级日志（Gradle 默认可见）——「这一轮没花十几分钟」必须让用户看得见；
	 * 未命中且产物存在时同样打 lifecycle，因为「产物明明在，为什么又跑了整条链」正是最难自查的那一类问题
	 * （与 {@link JarReusability} 的播报同一口径）。
	 *
	 * @param output 产物（pre-patch jar）
	 * @param expectedKey 本次执行的键，见 {@link #fingerprint}
	 * @param logger 日志出口
	 * @return 可以复用（即本次 action 应当直接返回）时为 {@code true}
	 */
	public static boolean tryReuse(Path output, String expectedKey, Logger logger) {
		if (expectedKey.endsWith(INCOMPLETE_MARKER)) {
			// 输入读不到：连「能不能比」都不成立，直接照常执行，且不留诊断噪音
			logger.info("pre-patch jar 的输入不完整，本轮照常执行 MCP 链：{}", output);
			return false;
		}

		if (!Files.isRegularFile(output)) {
			// 首次构建的常态：没有产物就没有可复用的东西，不值得为它打日志
			return false;
		}

		if (!JarReusability.isReusable(output)) {
			// 残骸（空 zip / 截断 / 根本不是 zip）：JarReusability 自己已经播报过原因
			return false;
		}

		final Path sidecar = sidecar(output);
		final String recorded = readSidecar(sidecar, output, logger);

		if (recorded == null) {
			logger.lifecycle("pre-patch jar 已存在但还没有身份旁车，本轮执行整条 MCP 链并记录身份：{}", output);
			return false;
		}

		if (!recorded.startsWith(KEY_FIELD)) {
			logger.lifecycle("pre-patch jar 的身份旁车格式不符（{}），本轮执行整条 MCP 链：{}", sidecar, output);
			return false;
		}

		final String recordedKey = recorded.substring(KEY_FIELD.length());

		if (!recordedKey.equals(expectedKey)) {
			logger.lifecycle("pre-patch jar 的产出输入与本次不同（身份不符），本轮重新执行整条 MCP 链：{}", output);
			return false;
		}

		final String recordedProduct = readProductField(sidecar);
		final String product = fingerprintOf(output.toAbsolutePath().normalize());

		if (recordedProduct == null || !recordedProduct.equals(product)) {
			// 产物在旁车写入之后被别的进程改写：内容不可信，宁可重跑
			logger.lifecycle("pre-patch jar 在身份记录之后被改写（{} vs {}），本轮重新执行整条 MCP 链：{}",
					recordedProduct, product, output);
			return false;
		}

		logger.lifecycle("复用已有的 Forge pre-patch jar，跳过整条 MCP 链：{}（如需强制重建：--refresh-dependencies）", output);
		return true;
	}

	/**
	 * 生产成功后把本次键与产物指纹写进旁车.
	 *
	 * <p>只在生产成功之后调用（调用点见 {@code GenerateForgePrePatchJarTask}）：判据是纯读的，写只发生在「这一轮
	 * 确实产出了产物」之后，于是旁车永远描述一份真实存在的产物。写入失败只记 info 不抛——旁车缺失的后果只是下次
	 * 多跑一遍链，不该让构建失败。
	 *
	 * @param output 产物（已落位）
	 * @param expectedKey 本次执行的键
	 * @param logger 日志出口
	 */
	public static void record(Path output, String expectedKey, Logger logger) {
		if (expectedKey.endsWith(INCOMPLETE_MARKER)) {
			logger.info("pre-patch jar 的输入不完整，本次不记录身份（下次构建将重新执行 MCP 链）：{}", output);
			return;
		}

		final String product = fingerprintOf(output.toAbsolutePath().normalize());

		if (MISSING.equals(product)) {
			logger.info("pre-patch jar 未落位或不可读，本次不记录身份：{}", output);
			return;
		}

		final String content = "# Loom Forge pre-patch jar 身份旁车；删除它即触发一次完整的 MCP 链重建\n"
				+ KEY_FIELD + expectedKey + '\n'
				+ PRODUCT_FIELD + product + '\n';

		try {
			AtomicFiles.publish(sidecar(output), tmp -> Files.writeString(tmp, content, StandardCharsets.UTF_8));
		} catch (IOException e) {
			logger.info("写出 pre-patch jar 的身份旁车失败（只影响下次构建能否复用）：{}", sidecar(output), e);
		}
	}

	/** {@return 产物对应的旁车文件路径}. */
	public static Path sidecar(Path output) {
		final Path parent = output.toAbsolutePath().normalize().getParent();
		return parent.resolve(SIDECAR_DIRECTORY).resolve(output.getFileName() + SIDECAR_SUFFIX);
	}

	/**
	 * 单件输入的指纹.
	 *
	 * <p>小文件取内容摘要（{@code config.json} 只有几 KB，而它决定整条链跑哪些步、用哪个工具版本，精确性在这里
	 * 最值钱）；大文件取「大小 + 最后修改时间」（vanilla jar 25~50MB，逐次哈希等于把便宜判据变成昂贵判据，
	 * 而这些文件一律由下载器或原子落位写入，见类注释）。
	 *
	 * @param file 输入文件（调用方已归一化）
	 * @return 指纹；读不到时为 {@link #MISSING}
	 */
	private static String fingerprintOf(Path file) {
		try {
			final BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);

			if (!attrs.isRegularFile()) {
				return MISSING;
			}

			if (attrs.size() <= HASH_MAX_BYTES) {
				return attrs.size() + "-sha256:" + Checksum.of(file).sha256().hex(16);
			}

			return attrs.size() + "-mtime:" + attrs.lastModifiedTime().toMillis();
		} catch (IOException | RuntimeException e) {
			// 读不到元数据、或摘要计算失败：当作「不可判定」⇒ 键带 incomplete 标记 ⇒ 照常执行
			return MISSING;
		}
	}

	/**
	 * 读旁车里的键行.
	 *
	 * @param sidecar 旁车文件
	 * @param output 产物（仅用于日志）
	 * @param logger 日志出口
	 * @return 键行原文（以 {@code key=} 开头）；旁车缺失或不可读时为 {@code null}
	 */
	private static String readSidecar(Path sidecar, Path output, Logger logger) {
		if (!Files.isRegularFile(sidecar)) {
			return null;
		}

		try {
			for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
				if (line.startsWith(KEY_FIELD)) {
					return line;
				}
			}

			return "";
		} catch (IOException e) {
			// 读不动就按「没有旁车」处理：方向安全，且不值得打断构建
			logger.info("读取 pre-patch jar 的身份旁车失败（按不可复用处理）：{}", sidecar, e);
			return null;
		}
	}

	/** {@return 旁车里记录的产物指纹；缺失时为 {@code null}} */
	private static String readProductField(Path sidecar) {
		try {
			for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
				if (line.startsWith(PRODUCT_FIELD)) {
					return line.substring(PRODUCT_FIELD.length());
				}
			}

			return null;
		} catch (IOException e) {
			return null;
		}
	}
}
