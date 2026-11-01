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

package net.fabricmc.loom.util.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.util.AttributeHelper;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.FileSystemUtil;

/**
 * 「这件（批）产物已经由**这批输入**生成过」的判据：把生成时的输入指纹记在产物上，下次开工前比一遍.
 *
 * <h2>解决的是什么问题</h2>
 * 流水线里有一批任务的产物落在跨项目共享目录（{@code <userCache>/<mcVersion>/}）。对其中一个项目来说，
 * 产物是「本项目从来没生产过的文件」，因此 Gradle 的 up-to-date 判定与构建缓存都帮不上忙
 * （对它们而言是 unknown output）——每个新项目首次构建都要把这些产物从头重算一遍，
 * 哪怕它们就完好地躺在盘上。本类提供的判据是这些任务 action 的**前置判据**：输入没变且产物完好时直接返回。
 *
 * <h2>为什么不能只看「文件存在」</h2>
 * 判据失效的方向是不对称的：多算一次只是慢，而「误判可复用」会拿陈旧产物一路传给后续环节，
 * 形成「标记是新的、内容是旧的」的静默损坏。所以判据必须能**证明输入没变**，而不只是证明产物在场。
 *
 * <h2>判据的三层</h2>
 * <ol>
 *   <li><b>产物齐备且完好</b>：交给 {@link JarReusability#isReusable(Path)}——与共享缓存其余就绪判据
 *       同一把尺子（能作为 zip 打开且至少一个条目）。一批里有任何一个产物不完好即整体重跑。</li>
 *   <li><b>产出规则未换代</b>：{@code recipe} 是调用方给的产出规则身份（形如 {@code merge-jars/v1}）。
 *       产出**实现**变了而输入没变也绝不能复用，这一层靠纪律而非校验守住：
 *       <b>改了产出规则就必须递增自己那个 recipe 的版本号</b>。</li>
 *   <li><b>输入与产物都没变</b>：见下面「两套身份，先廉价后精确」一层。</li>
 * </ol>
 *
 * <h2>两套身份，先廉价后精确</h2>
 * 一次构建的热路径不允许把输入 jar 整包读一遍（客户端 jar 20MB 级，实测 sha256 约 100ms），
 * 而这条判据本身就发生在「本来要重算几秒到几十秒」之前。因此每一项都记两套身份：
 * <ul>
 *   <li>记录里存下 {@code (size, lastModified, fileKey)} 与内容 {@code sha256}；</li>
 *   <li>判定时先比 stat 三元组。**全等就认定是同一份内容，不再算摘要**——暖路径因此只有几次 stat
 *       与一次小属性读，不读任何 jar；</li>
 *   <li>三元组有任一不等就真的比 {@code sha256}：内容仍相同（例如下载走了 304 刷新了 mtime、
 *       或同一份内容被另一次原子落位重新发布）照旧可复用，内容不同则判为不可复用。</li>
 * </ul>
 * 三元组全等为何能等同于「同一份内容」：本判据服务的文件都位于共享缓存，它们的**每一次**内容换代都经
 * {@link AtomicFiles}（或 {@code Download} 的 part 文件）原子落位——{@code rename(2)} 之后目标路径换成了
 * 临时文件那个 inode（{@code fileKey} 必变）。也就是说，同一 inode 上的内容不会被就地改写换代。
 * 反过来，若某处开始「就地重写这些 jar」，快速通道就会失效；那时唯一还能兜住的是慢通道的 sha256
 * ——它虽然只在三元组不等时才跑，但就地重写必然改 mtime，所以仍然会真的比一次摘要。
 * 两层合起来：「跳过摘要」只在不该变的文件确实没变时才发生。
 *
 * <h2>标记存在哪里</h2>
 * 用 {@link AttributeHelper}（{@code LoomHash} / {@code ETag} / {@code loom-target} 的同一套载体）：
 * 支持的平台上写用户扩展属性，不支持时自动退化为同目录 {@code <jar>.<属性名>.att} 旁车文件。
 * 两种形态都跟着产物走，因此跨进程、跨 daemon、跨项目都读得到。标记写在**锚点产物**上
 * （一批产物里的第一个，通常是消费侧最先读的那件）。
 *
 * <h2>与原子发布的关系</h2>
 * 本类只写「标记」这层元数据，从不删改产物内容，也从不就地重写：
 * <ul>
 *   <li>{@link #isReusable} 全程只读（不删、不改、不创建）；</li>
 *   <li>{@link #record} 必须在全部产物内容已经完整落位之后调用（即 {@link AtomicFiles#publish}
 *       与 {@code AtomicFiles.move} 都返回之后）。内容先原子落位、标记后落位，中断只会留下
 *       「有内容、没标记」的产物 → 下次判为不可复用并重算，方向是安全的。</li>
 * </ul>
 * 写标记不改产物内容也不改其 mtime（扩展属性或独立的旁车文件），因此不影响产物自身的指纹与输出快照。
 */
public final class ArtifactReuse {
	/**
	 * 诊断日志.
	 *
	 * <p>与 {@link JarReusability} 同样取 LIFECYCLE 级别：Gradle 默认控制台级别就是 LIFECYCLE，
	 * 用 INFO 写的诊断在默认构建里等于不存在。
	 */
	private static final Logger LOGGER = Logging.getLogger("loom_artifactReuse");

	/**
	 * 记在产物上的属性名.
	 *
	 * <p>与 {@code LoomHash}（抽取产物）、{@code ETag}（下载产物）、{@code loom-target}（mod 产物）并列，
	 * 是同一套「产物自述身份」机制里的又一个键，互不覆盖。
	 */
	private static final String ATTRIBUTE_KEY = "loom-artifact-inputs";

	/**
	 * 标记的格式版本.
	 *
	 * <p>标记的字段组成或语义一旦变化就递增它：旧标记随即整体判为不可复用，不需要人工清理共享目录。
	 */
	private static final String FORMAT = "loom-artifact-inputs-v1";

	/** 记录内的行分隔符：文件名里不可能出现换行，用它分段最不容易与内容撞. */
	private static final String NEWLINE = "\n";

	/** 记录内的字段分隔符. */
	private static final String FIELD = "|";

	private static final String RECIPE_ROW = "recipe";
	private static final String OUTPUT_ROW = "output";
	private static final String INPUT_ROW = "input";

	private ArtifactReuse() {
	}

	/**
	 * {@return 这（批）产物是否可以原样复用（输入没变 + 产物齐备且完好）}.
	 *
	 * <p>所有「不可信」都归到 {@code false}，由调用方照常执行生产：任一产物缺失、损坏或被截断、锚点上没有标记、
	 * 标记格式不认识、{@code recipe} 已换代、产物大小/内容与记录不符、输入数量或顺序变了、任一输入的摘要对不上。
	 * 判 {@code true} 只在**全部**证据都指向「就是这一批」时成立。
	 *
	 * <p>本方法只读：不删、不改、不创建任何文件。除「本 JVM 之后再也打不开该路径」这一条
	 * （{@link FileSystemUtil.UnrecoverableZipException}，与 {@link JarReusability} 一致地向上抛，
	 * 否则上层会陷入「重建 → 仍然打不开」的循环）外，任何异常都归到 {@code false}。
	 *
	 * @param anchor 标记所在的产物；必须属于 {@code outputs}
	 * @param recipe 产出规则的身份（形如 {@code merge-jars/v1}）；规则变了要递增版本号
	 * @param outputs 本次生产会写出的全部产物
	 * @param inputs 生成本批产物所依赖的全部输入；顺序必须与 {@link #record} 时一致
	 */
	public static boolean isReusable(Path anchor, String recipe, List<Path> outputs, List<Path> inputs) {
		requireAnchor(anchor, outputs);

		try {
			return check(anchor, recipe, outputs, inputs);
		} catch (FileSystemUtil.UnrecoverableZipException e) {
			// 与 JarReusability 同一处置：吞掉只会让上层「重建 → 仍然打不开」地空转，应当尽早暴露
			throw e;
		} catch (IOException | RuntimeException e) {
			// 判据是纯粹的优化：任何读不动的情形都退回「照常执行」，绝不把诊断信息的读取失败变成构建失败
			LOGGER.debug("无法完成复用判定，按「需要重新生成」处理：{}", anchor, e);
			return false;
		}
	}

	/**
	 * 把「这（批）产物由这批输入生成」记到锚点产物上，供下次 {@link #isReusable} 判定.
	 *
	 * <p>**必须在全部产物内容已经完整落位之后调用**（{@link AtomicFiles#publish} 与 {@code AtomicFiles.move}
	 * 都返回之后）。顺序反过来的话，中断会让标记先就位、内容后缺席，下一次构建就会把一批残缺产物判为可复用。
	 *
	 * <p>只写标记（用户扩展属性或旁车文件），不改产物内容、不改其 mtime。标记写不进去时应当让它抛出来：
	 * 那样下次仍会重算（方向安全），但用户需要知道共享目录不可写。
	 *
	 * @param anchor 标记写到哪件产物上；必须属于 {@code outputs}
	 * @param recipe 产出规则的身份；与 {@link #isReusable} 传入的必须逐字相同
	 * @param outputs 本次生产写出的全部产物
	 * @param inputs 生成本批产物所依赖的全部输入；顺序参与指纹，两次调用必须一致
	 * @throws IOException 读不到产物或输入的元数据、或标记写不进去时
	 */
	public static void record(Path anchor, String recipe, List<Path> outputs, List<Path> inputs) throws IOException {
		requireAnchor(anchor, outputs);

		final StringBuilder builder = new StringBuilder(FORMAT).append(NEWLINE)
				.append(RECIPE_ROW).append(FIELD).append(recipe);

		for (Path output : outputs) {
			appendRow(builder, OUTPUT_ROW, output);
		}

		for (Path input : inputs) {
			appendRow(builder, INPUT_ROW, input);
		}

		AttributeHelper.writeAttribute(anchor, ATTRIBUTE_KEY, builder.toString());
	}

	/**
	 * 播报「复用已有产物、跳过重算」.
	 *
	 * <p>这行日志是本次优化的唯一观测点：没有它，「任务跑了但什么都没干」看起来与「任务根本没被调度」
	 * 一模一样。级别取 LIFECYCLE（Gradle 默认可见），与 {@link JarReusability} 播报「不可复用」的级别对称。
	 *
	 * @param anchor 被复用的锚点产物
	 * @param recipe 产出规则的身份
	 */
	public static void reportReused(Path anchor, String recipe) {
		LOGGER.lifecycle("复用已有产物，跳过重算：{}（{}；输入指纹、产物完好性与大小均未变）",
				anchor.toAbsolutePath(), recipe);
	}

	private static void requireAnchor(Path anchor, List<Path> outputs) {
		for (Path output : outputs) {
			if (output.equals(anchor)) {
				return;
			}
		}

		throw new IllegalArgumentException("锚点产物不在产物列表里：%s".formatted(anchor));
	}

	private static boolean check(Path anchor, String recipe, List<Path> outputs, List<Path> inputs) throws IOException {
		// 第一层：产物齐备且完好。不能只判存在——共享缓存里的产物会被中断的就地写留下 0 字节或截断文件
		for (Path output : outputs) {
			if (!JarReusability.isReusable(output)) {
				return false;
			}
		}

		final Recorded recorded = read(anchor);

		if (recorded == null) {
			// 没有标记、或标记读不出来：可能是旧版 loom 写的产物、也可能是标记落位前被中断。
			// 两种情形都只能重算——把来源不明的产物当成自己的，正是本判据要杜绝的事
			return false;
		}

		if (!recipe.equals(recorded.recipe())) {
			return false;
		}

		return filesMatch(recorded.outputs(), outputs) && filesMatch(recorded.inputs(), inputs);
	}

	/** {@return 逐个比对「廉价身份」或（必要时）内容摘要是否一致}. */
	private static boolean filesMatch(List<RecordedFile> recorded, List<Path> files) {
		if (recorded.size() != files.size()) {
			// 集合本身变了（例如某条支线多了一个参考 jar）：记录描述的不是这一批
			return false;
		}

		for (int i = 0; i < files.size(); i++) {
			final Path file = files.get(i);
			final RecordedFile entry = recorded.get(i);

			if (!nameOf(file).equals(entry.name())) {
				return false;
			}

			final BasicFileAttributes attributes;

			try {
				attributes = Files.readAttributes(file, BasicFileAttributes.class);
			} catch (IOException e) {
				// 读不到元数据：无法证明「还是那一份」，只能重算（生产侧会给出真正的失败原因）
				return false;
			}

			// 快速通道：stat 三元组全等即认为同一份内容，不读整包（见类注释「两套身份」）
			if (attributes.size() == entry.size()
					&& attributes.lastModifiedTime().toMillis() == entry.lastModified()
					&& fileKeyOf(attributes).equals(entry.fileKey())) {
				continue;
			}

			// 慢通道：stat 变了不代表内容变了（下载走 304 会刷新 mtime），必须真的比一次摘要
			if (!Checksum.of(file).sha256().hex().equals(entry.sha256())) {
				return false;
			}
		}

		return true;
	}

	private static void appendRow(StringBuilder builder, String row, Path file) throws IOException {
		final BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
		builder.append(NEWLINE)
				.append(row).append(FIELD)
				.append(nameOf(file)).append(FIELD)
				.append(attributes.size()).append(FIELD)
				.append(attributes.lastModifiedTime().toMillis()).append(FIELD)
				.append(fileKeyOf(attributes)).append(FIELD)
				.append(Checksum.of(file).sha256().hex());
	}

	/** {@return 锚点上记录的输入指纹；缺失、读不出或格式不认识时为 {@code null}} */
	private static @Nullable Recorded read(Path anchor) {
		final String raw;

		try {
			raw = AttributeHelper.readAttribute(anchor, ATTRIBUTE_KEY).orElse(null);
		} catch (IOException | RuntimeException e) {
			// 属性读不出来（不支持用户属性、权限、损坏）一律按「没有标记」处理：宁可重算一次，
			// 也不能因为读诊断信息的失败而打断整个构建
			return null;
		}

		return raw == null ? null : parse(raw.strip());
	}

	/** {@return 解析出的记录；格式不认识时返回 {@code null}（调用方据此判不可复用）} */
	private static @Nullable Recorded parse(String raw) {
		final String[] lines = raw.split(NEWLINE);

		if (lines.length < 2 || !FORMAT.equals(lines[0])) {
			return null;
		}

		String recipe = null;
		final List<RecordedFile> outputs = new ArrayList<>();
		final List<RecordedFile> inputs = new ArrayList<>();

		for (int i = 1; i < lines.length; i++) {
			final String[] fields = lines[i].split(Pattern.quote(FIELD), -1);

			if (RECIPE_ROW.equals(fields[0]) && fields.length == 2) {
				recipe = fields[1];
			} else if ((OUTPUT_ROW.equals(fields[0]) || INPUT_ROW.equals(fields[0])) && fields.length == 6) {
				final RecordedFile entry = parseFile(fields);

				if (entry == null) {
					return null;
				}

				(OUTPUT_ROW.equals(fields[0]) ? outputs : inputs).add(entry);
			} else {
				// 出现未知行或字段数不符：这份标记是别的格式（或写坏了），一个字段都不能信
				return null;
			}
		}

		if (recipe == null || outputs.isEmpty()) {
			return null;
		}

		return new Recorded(recipe, List.copyOf(outputs), List.copyOf(inputs));
	}

	private static @Nullable RecordedFile parseFile(String[] fields) {
		final Long size = parseLong(fields[2]);
		final Long lastModified = parseLong(fields[3]);

		if (size == null || lastModified == null) {
			return null;
		}

		return new RecordedFile(fields[1], size, lastModified, fields[4], fields[5]);
	}

	private static @Nullable Long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String nameOf(Path path) {
		final Path name = path.getFileName();
		return name == null ? path.toString() : name.toString();
	}

	/** {@return 文件系统给的 inode 标识；不提供时为 {@code ""}（此时快速通道退化为「大小 + mtime」）} */
	private static String fileKeyOf(BasicFileAttributes attributes) {
		final Object fileKey = attributes.fileKey();
		return fileKey == null ? "" : fileKey.toString();
	}

	/** 一个产物或输入在记录里的那一行. */
	private record RecordedFile(String name, long size, long lastModified, String fileKey, String sha256) {
	}

	/** 一件锚点产物上记录下来的「由谁生成」. */
	private record Recorded(String recipe, List<RecordedFile> outputs, List<RecordedFile> inputs) {
	}
}
