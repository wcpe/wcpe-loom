/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * 为 1.12.2 的 tiny 映射补全「实现类缺失的继承方法条目」.
 *
 * <h2>要解决什么</h2>
 * 1.12.2 的 MCP 映射只给「声明该方法的类」记录条目，子类里 override 的方法不再重复登记。
 * 例如 {@code ShapedRecipes implements IRecipe}：{@code IRecipe} 有
 * {@code b()Laip; -> getRecipeOutput}，而 {@code ShapedRecipes} 自身也声明了同签名方法却没有条目。
 *
 * <p>ForgeGradle 用的 SpecialSource 有 {@code fallbackInheritanceProvider}：查不到就沿继承链往上找，
 * 所以能补出名字。loom 用的 TinyRemapper 没有这个回退机制——成员映射按
 * {@code (声明类, 名称, 描述符)} 精确匹配，查不到就保持原名。于是 {@code ShapedRecipes.b()}
 * 不会被改成 {@code getRecipeOutput()}，下游 {@code RecipesMapExtending} 继承不到实现，
 * 运行期抛 {@code AbstractMethodError: getRecipeOutput is abstract}。
 *
 * <p>本任务把「SpecialSource 会往上找出来的结果」提前算好、显式写进映射，让 TinyRemapper 一查即中。
 *
 * <h2>为什么是独立任务而不是配置期计算</h2>
 * 补全需要读 class 的继承关系，且要产出约 5900 条新映射。
 * 放在配置期会让每次 Gradle 启动都重跑一遍，配置阶段显著变慢，也会拖累配置缓存。
 * 做成 {@link CacheableTask}：配置期只登记输入输出，执行期才计算，并可 up-to-date / 命中构建缓存。
 *
 * <h2>补全要迭代到不动点</h2>
 * 名字可能沿继承链隔层传播，单轮只看直接父类型会漏掉深层条目（1.12.2 实测单轮漏约 10%），
 * 详见 {@link TinyMappings#addInheritedMethods}。
 *
 * <h2>产物形态</h2>
 * 输出的 tiny 与输入**同格式**（tiny v2、同 namespace 列），只在缺失处追加方法行。
 * 输入里已有的行原样保留，顺序不变——增量 diff 因此只体现新增部分。
 */
@CacheableTask
public abstract class GenerateLegacyInheritedMappingsTask extends DefaultTask {
	/** 输入的 srg 命名空间 tiny（{@code mappings-srg.tiny}）. */
	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMappings();

	/**
	 * 原始 notch 命名空间的 Minecraft jar（可多个：client / server）.
	 *
	 * <p>只用于读取类继承关系与方法声明，不产出任何 class。
	 */
	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getMinecraftJars();

	/** 补全后的 tiny（与 {@link #getMappings()} 同格式，追加缺失的继承方法行）. */
	@OutputFile
	public abstract RegularFileProperty getOutputMappings();

	@TaskAction
	public void generate() throws IOException {
		final Path source = getMappings().get().getAsFile().toPath();
		final Path output = getOutputMappings().get().getAsFile().toPath();

		final TinyMappings parsed = TinyMappings.read(source);
		final Map<String, ClassMeta> metas = readClassMeta(parsed.classNames());

		final int added = parsed.addInheritedMethods(metas);
		getLogger().lifecycle(":completed {} inherited method mappings ({} classes scanned)",
				added, metas.size());

		AtomicFiles.publish(output, tmp -> parsed.write(tmp));
	}

	/**
	 * 读输入 jar 中**映射实际涉及**的类的元数据（父类、接口、声明的方法）.
	 *
	 * <p>只解析 {@code wanted} 里的类：1.12.2 的 client/server 并集有约 2 万个类，而 srg 映射只覆盖
	 * 约 3300 个（其余是纯服务端类，映射里没有条目）。补全只对映射里出现过的类有意义，
	 * 因此按 {@code wanted} 过滤是纯优化——不改变任何补全结果，只省掉约 83% 的解码开销。
	 *
	 * @param wanted 需要的类的内部名（{@code a/b/C} 形式，即 tiny 的第一列）
	 */
	private Map<String, ClassMeta> readClassMeta(Set<String> wanted) throws IOException {
		final Map<String, ClassMeta> metas = new HashMap<>();
		final var files = getMinecraftJars().getFiles();

		getLogger().lifecycle(":legacy inherited mappings, input jars = {}, classes needed = {}",
				files, wanted.size());

		int skipped = 0;

		for (var file : files) {
			if (!file.isFile()) {
				getLogger().lifecycle(":  skip (not a file): {}", file);
				continue;
			}

			try (ZipFile zip = new ZipFile(file)) {
				final var entries = zip.entries();

				while (entries.hasMoreElements()) {
					final ZipEntry entry = entries.nextElement();
					final String name = entry.getName();

					if (!name.endsWith(".class")) {
						continue;
					}

					final String internalName = name.substring(0, name.length() - ".class".length());

					if (!wanted.contains(internalName)) {
						skipped++;
						continue;
					}

					try (var in = zip.getInputStream(entry)) {
						final ClassMeta meta = ClassMeta.parse(in.readAllBytes());

						if (meta != null) {
							// client 与 server 有同名类但字节不同（1.12.2 实测 2250 个同名类里 454 个
							// 字节不一致），这里固定保留先读到的那个（输入顺序为 client、server）。
							// 取哪个都不影响正确性：补全只需要「该方法是否声明在这个类上」，
							// 而两边声明的方法签名在同一混淆体系下一致。
							metas.putIfAbsent(internalName, meta);
						}
					}
				}
			}
		}

		getLogger().lifecycle(":  skipped {} classes not covered by mappings", skipped);
		return metas;
	}

	/** 一个类的最小元数据：父类、直接接口、声明的方法（名称 + 描述符）. */
	private record ClassMeta(String superName, List<String> interfaces, List<String[]> methods) {
		private static final long CLASS_MAGIC = 0xCAFEBABEL;

		static ClassMeta parse(byte[] data) {
			try {
				return new Parser(data).parseClass();
			} catch (RuntimeException e) {
				// 单个类解析失败不该让整个补全任务失败：它最多让该类少补几条映射
				return null;
			}
		}

		/**
		 * 极简 class 文件解析：只取常量池里的 Utf8 / Class、父类、接口表与方法表的名称+描述符.
		 *
		 * <p>不引 ASM 是为了让本任务不依赖重映射器实现——它只做「读结构」这件事，
		 * 不需要访问指令体。
		 */
		private static final class Parser {
			private final byte[] data;
			private final Map<Integer, String> utf8 = new HashMap<>();
			private final Map<Integer, Integer> classRef = new HashMap<>();
			private int pos;

			Parser(byte[] data) {
				this.data = data;
			}

			ClassMeta parseClass() {
				if (u4(0) != CLASS_MAGIC) {
					return null;
				}

				pos = 8;
				readConstantPool();

				pos += 2; // access_flags
				pos += 2; // this_class
				final String superName = className(u2(pos));
				pos += 2;

				final int interfaceCount = u2(pos);
				pos += 2;
				final List<String> interfaces = new ArrayList<>(interfaceCount);

				for (int i = 0; i < interfaceCount; i++) {
					final String itf = className(u2(pos));
					pos += 2;

					if (itf != null) {
						interfaces.add(itf);
					}
				}

				skipMemberTable(); // fields
				final List<String[]> methods = readMethods();

				return new ClassMeta(superName, interfaces, methods);
			}

			private void readConstantPool() {
				final int count = u2(pos);
				pos += 2;

				for (int i = 1; i < count; i++) {
					final int tag = data[pos++] & 0xFF;

					switch (tag) {
					case 1 -> {
						final int len = u2(pos);
						pos += 2;
						utf8.put(i, new String(data, pos, len, StandardCharsets.UTF_8));
						pos += len;
					}
					case 7 -> {
						classRef.put(i, u2(pos));
						pos += 2;
					}
					case 8, 16, 19, 20 -> pos += 2;
					case 15 -> pos += 3;
					case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
					case 5, 6 -> {
						pos += 8;
						i++; // long / double 占两个槽
					}
					default -> throw new IllegalStateException("unknown constant pool tag " + tag);
					}
				}
			}

			private void skipMemberTable() {
				final int count = u2(pos);
				pos += 2;

				for (int i = 0; i < count; i++) {
					pos = skipAttributes(pos + 6);
				}
			}

			private List<String[]> readMethods() {
				final int count = u2(pos);
				pos += 2;
				final List<String[]> methods = new ArrayList<>(count);

				for (int i = 0; i < count; i++) {
					final String name = utf8.get(u2(pos + 2));
					final String desc = utf8.get(u2(pos + 4));
					pos = skipAttributes(pos + 6);

					if (name != null && desc != null) {
						methods.add(new String[] {name, desc});
					}
				}

				return methods;
			}

			private int skipAttributes(int from) {
				int p = from;
				final int count = u2(p);
				p += 2;

				for (int i = 0; i < count; i++) {
					p += 2;
					final int len = (int) u4(p);
					p += 4 + len;
				}

				return p;
			}

			private String className(int index) {
				final Integer nameIndex = classRef.get(index);
				return nameIndex == null ? null : utf8.get(nameIndex);
			}

			private int u2(int at) {
				return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
			}

			private long u4(int at) {
				return ((long) (data[at] & 0xFF) << 24)
						| ((data[at + 1] & 0xFF) << 16)
						| ((data[at + 2] & 0xFF) << 8)
						| (data[at + 3] & 0xFF);
			}
		}
	}

	/** tiny v2 文本映射：按类分块，块内保留原始行，仅追加缺失的方法行. */
	private static final class TinyMappings {
		private final String header;
		private final List<Block> blocks = new ArrayList<>();

		private TinyMappings(String header) {
			this.header = header;
		}

		static TinyMappings read(Path file) throws IOException {
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

			if (lines.isEmpty()) {
				throw new IOException("映射文件为空：" + file);
			}

			final TinyMappings tiny = new TinyMappings(lines.get(0));
			Block current = null;

			for (int i = 1; i < lines.size(); i++) {
				final String line = lines.get(i);

				if (line.startsWith("c\t")) {
					// columnData 保留 "c" 之后的**全部列**（official / srg / intermediary / named），
					// 写回时再补回 "c\t" 前缀。只取第一列会让类行退化成 2 列，
					// mapping-io 读时直接抛 "missing name columns"。
					current = new Block(line.substring("c\t".length()), new ArrayList<>());
					tiny.blocks.add(current);
				} else if (current != null) {
					current.lines.add(line);
				}
			}

			return tiny;
		}

		/**
		 * {@return 映射里出现过的全部类的内部名} 即每个类块的第一列.
		 *
		 * <p>供 {@code readClassMeta} 过滤输入 jar：只需解析这些类，其余（映射未覆盖的服务端类）
		 * 与补全无关。
		 */
		Set<String> classNames() {
			final Set<String> names = new LinkedHashSet<>();

			for (Block block : blocks) {
				names.add(block.notchName);
			}

			return names;
		}

		/**
		 * 为「自身声明了方法但缺映射条目」的类补上从父类型继承来的名字.
		 *
		 * <p><b>必须迭代到不动点</b>：补全会沿继承链逐层传播——{@code C extends B extends A}，
		 * {@code A} 声明 {@code foo} 且有映射，{@code B} 和 {@code C} 都重写了它。第一轮处理 {@code C}
		 * 时它只看直接父类 {@code B}，若那时 {@code B} 还没补上 {@code foo}，{@code C} 这一轮就漏掉。
		 * 实测 1.12.2 的单轮只补 5326 条，而迭代收敛后应为 5931 条（5326 / 565 / 40 / 0，四轮收敛）：
		 * 单轮会漏掉约 10%，那些方法因此保留 notch 名，运行期照旧抛 {@code AbstractMethodError}
		 * （漏掉的包括 {@code checkPermission}、{@code getTabCompletions}、{@code attackEntityFrom}
		 * 这类常用方法）。
		 *
		 * <p>每轮都是纯内存遍历，代价可忽略；轮数上限由继承深度决定，实际极小。
		 *
		 * @return 新增的映射条数
		 */
		int addInheritedMethods(Map<String, ClassMeta> metas) {
			final Map<String, Block> byClass = new LinkedHashMap<>();

			for (Block block : blocks) {
				block.index();
				byClass.put(block.notchName, block);
			}

			int total = 0;

			while (true) {
				final int added = addInheritedMethodsOnce(byClass, metas);

				if (added == 0) {
					return total;
				}

				total += added;
			}
		}

		/**
		 * 补全的一轮：只沿**直接**父类与直接接口查.
		 *
		 * <p>单轮不保证完备（见 {@link #addInheritedMethods}），由外层循环迭代到不动点。
		 * 本轮补入的条目会写回 {@link Block#methods}，因此下一轮既能看到它们、也不会重复添加。
		 */
		private int addInheritedMethodsOnce(Map<String, Block> byClass, Map<String, ClassMeta> metas) {
			int added = 0;

			for (Block block : blocks) {
				final ClassMeta meta = metas.get(block.notchName);

				if (meta == null) {
					continue;
				}

				// 该类在字节码里真正声明的方法（name+desc）
				final TreeSet<String> declared = new TreeSet<>();

				for (String[] m : meta.methods()) {
					declared.add(m[0] + m[1]);
				}

				final List<String> parents = new ArrayList<>();

				if (meta.superName() != null && !meta.superName().equals("java/lang/Object")) {
					parents.add(meta.superName());
				}

				parents.addAll(meta.interfaces());

				for (String parent : parents) {
					final Block parentBlock = byClass.get(parent);

					if (parentBlock == null) {
						continue;
					}

					for (var entry : parentBlock.methods.entrySet()) {
						final String key = entry.getKey();
						final String[] names = entry.getValue();

						// 父类型这条映射本身是否真的改了名（第 0 列与最后一列不同）
						if (names.length < 2 || names[0].equals(names[names.length - 1])) {
							continue;
						}

						// 子类声明了同签名方法、且自己没有条目 -> 补一条。
						// 「自己没有条目」同时承担去重：多个父类型可能给出同签名的不同名字
						// （实测出现两条 ()Ljava/lang/String; 分别叫 method_8996 / method_8997），
						// 重复写入会让 tiny 出现同签名双条目，MappingReader 直接解析失败。
						// appendMethod 会写回 methods，故同一 (类, 签名) 在同一轮内也只会补一次。
						if (declared.contains(key) && !block.methods.containsKey(key)) {
							block.appendMethod(key, names);
							added++;
						}
					}
				}
			}

			return added;
		}

		void write(Path file) throws IOException {
			try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				writer.write(header);
				writer.newLine();

				for (Block block : blocks) {
					writer.write("c\t" + block.columnData);
					writer.newLine();

					for (String line : block.outputLines) {
						writer.write(line);
						writer.newLine();
					}
				}
			}
		}

		private static final class Block {
			private final String notchName;
			private final String columnData;
			private final List<String> lines;
			private final List<String> outputLines = new ArrayList<>();
			private final Map<String, String[]> methods = new LinkedHashMap<>();

			Block(String columnData, List<String> lines) {
				this.columnData = columnData;
				// columnData 是整行剩余部分（"akw\tnet/minecraft/...\t..."），notch 名是其第一个字段
				final int tab = columnData.indexOf('\t');
				this.notchName = tab < 0 ? columnData : columnData.substring(0, tab);
				this.lines = lines;
			}

			int index() {
				if (!outputLines.isEmpty() || !methods.isEmpty()) {
					return methods.size();
				}

				for (String line : lines) {
					outputLines.add(line);

					// 方法行形如 "\tm\t()Laip;\tb\tfunc_xxx\tmethod_xxx\tgetRecipeOutput"
					// 去掉前导空字段后：[0]=m, [1]=desc, [2]=notch 名, [3..]=各命名空间名
					if (line.startsWith("\tm\t")) {
						final String[] parts = line.split("\t");

						if (parts.length >= 5) {
							final String desc = parts[2];
							final String name = parts[3];
							final String[] names = new String[parts.length - 4];
							System.arraycopy(parts, 4, names, 0, names.length);
							methods.put(name + desc, names);
						}
					}
				}

				return methods.size();
			}

			void appendMethod(String key, String[] names) {
				final int paren = key.indexOf('(');
				final String name = key.substring(0, paren);
				final String desc = key.substring(paren);

				final StringBuilder sb = new StringBuilder("\tm\t");
				sb.append(desc).append('\t').append(name);

				for (String n : names) {
					sb.append('\t').append(n);
				}

				outputLines.add(sb.toString());
				// 同时记进 methods：迭代补全时下一轮要能看到这条（作为其它类的父类型来源），
				// containsKey 检查也据此避免同一 (类, 签名) 被重复追加。
				methods.put(key, names);
			}
		}
	}
}
