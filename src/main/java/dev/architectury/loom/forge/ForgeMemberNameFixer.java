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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * 把最终 MC jar 里「对 Forge 成员的未翻译引用」改写成 Forge 侧的最终名字.
 *
 * <h2>为什么需要这一步</h2>
 * legacy Forge 的 binpatch 会把 Forge 自己的调用写进 MC 类。这些调用在补丁里以 <b>srg 名</b>
 * 出现（如 {@code ModelLoader.a()}），而 Forge universal jar 随后被重映射到 named
 * （同方法改叫 {@code setupModelRegistry}）。MC jar 的重映射表只含 MC 自身成员，
 * 查不到 Forge 成员，于是这些引用被原样保留，运行期抛
 * {@code NoSuchMethodError: ModelLoader.a()Lnet/minecraft/util/registry/IRegistry;}。
 *
 * <h2>映射从哪来</h2>
 * Forge 会同时提供 {@code forge-universal.jar}（srg 空间）与重映射后的 forge jar（named 空间）。
 * 两份 jar 由同一份源码编译，<b>同类内成员的出现顺序一致</b>，因此按顺序对齐即可得到
 * 「srg 名 → named 名」的对应关系。这条映射不依赖 MC 映射表，所以能覆盖 MC 表里没有的 Forge 成员。
 *
 * <h2>为什么按 owner+name 匹配而不是按描述符</h2>
 * 最终 jar 里这些引用的描述符可能已经是 named 形
 * （{@code ()Lnet/minecraft/util/registry/IRegistry;}），而映射键里的描述符是 srg 形
 * （{@code ()Lfm;}），两者不等。若按「名字+描述符」精确匹配会静默失效
 * （实测：仅 2 个 class 被改写且目标引用未变）。故这里忽略描述符、只按 owner + name 匹配。
 *
 * <p><b>该匹配方式的前提</b>：同一 owner 下的同名重载会映射到同一个目标名。
 * 对 1.12.2 的 Forge 实测结果正确（重载在 srg 与 named 两版间保持同序，且改名后不与其他成员冲突）。
 * 若将来 Forge 引入「同名重载被改成不同名字」的情况，这里会改错，需改为携带描述符的匹配。
 */
public final class ForgeMemberNameFixer {
	/** Forge 类的包前缀：只处理这些包下的成员，MC 自身成员不受影响. */
	private static final String FORGE_PACKAGE_PREFIX = "net/minecraftforge/";

	private ForgeMemberNameFixer() {
	}

	/**
	 * 改写 {@code targetJar} 中对 Forge 成员的 srg 名引用.
	 *
	 * <p>就地改写：读原文件、写同目录临时文件、成功后替换。任一步失败时原文件保持不动。
	 *
	 * @param targetJar 待改写的 jar（最终 MC jar）
	 * @param srgForgeJar Forge 的 universal jar（srg 空间）
	 * @param namedForgeJar 已重映射到 named 的 Forge jar
	 * @return 改写统计；两份 Forge jar 缺失或对不上时返回全零
	 * @throws IOException 读写失败
	 */
	public static Stats fix(Path targetJar, Path srgForgeJar, Path namedForgeJar) throws IOException {
		final MemberMapping mapping = buildMapping(srgForgeJar, namedForgeJar);

		if (mapping.isEmpty()) {
			// 映射为空说明两份 Forge jar 缺失或对不上：改写没有意义，跳过而不是产出半成品
			return new Stats(0, 0);
		}

		final Remapper remapper = new Remapper() {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return mapping.methods().getOrDefault(owner + "." + name, name);
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				// 只查字段表：字段与方法共用一张表时，同名的字段引用会被方法的目标名覆盖。
				// 实测 1.12.2：`World.initCapabilities` 读的是父类 `WorldSavedData.mapName`（notch 名 `a`），
				// 却命中了方法条目 `a -> readFromNBT`，运行期 NoSuchFieldError: readFromNBT。
				return mapping.fields().getOrDefault(owner + "." + name, name);
			}
		};

		final Path tmp = targetJar.resolveSibling(targetJar.getFileName() + ".forgemember.tmp");
		int changed = 0;

		try (ZipFile zip = new ZipFile(targetJar.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			// 用 level 5 写回：实测在 1-9 各级别里最接近原 jar 体积（原始 14,644,884，
			// level 5 得 14,596,114，差约 4.9 万字节；其余级别差得更多）。
			// 注：原 jar 是混合压缩，任何单一级别都无法做到字节一致，故这里只求尽量接近，
			// 不能依赖「体积一致」作为下游就绪判定。
			out.setLevel(5);
			final Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();
				byte[] data = readAll(zip.getInputStream(entry));

				if (entry.getName().endsWith(".class")) {
					final byte[] rewritten = rewrite(data, remapper);

					if (rewritten != null) {
						data = rewritten;
						changed++;
					}
				}

				// 逐条目复制原 ZipEntry 的元数据（压缩方法、时间戳、注释、额外字段）。
				// 不能只写 entry.getName()：那样会退回默认压缩级别，令整份 jar 的字节发生变化
				// （实测体积从 14,644,884 掉到 14,392,040）。产物字节一变，下游任何基于
				// jar 大小/哈希的就绪判定与缓存都会失配，表现为读不到类。
				final ZipEntry copy = new ZipEntry(entry);
				copy.setCompressedSize(-1);  // 交由 ZipOutputStream 重新计算
				out.putNextEntry(copy);
				out.write(data);
				out.closeEntry();
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}

		Files.move(tmp, targetJar, StandardCopyOption.REPLACE_EXISTING);
		return new Stats(mapping.size(), changed);
	}

	/** {@return 改写后的字节；未发生改动时返回 {@code null}} */
	private static byte[] rewrite(byte[] data, Remapper remapper) {
		try {
			final ClassReader reader = new ClassReader(data);
			final ClassWriter writer = new ClassWriter(0);
			reader.accept(new ClassRemapper(writer, remapper), 0);
			final byte[] result = writer.toByteArray();
			return Arrays.equals(data, result) ? null : result;
		} catch (RuntimeException e) {
			// 单个类解析失败不该让整个改写失败：保持原样，最多漏改该类
			return null;
		}
	}

	/**
	 * 按「同类内、同种类成员的顺序对齐」建立 srg 名 → named 名的映射.
	 *
	 * <p><b>字段与方法必须分表</b>：srg 空间里 Forge 自己的方法名也被 srg 化（`a` / `b` / `d`），
	 * 与 named 空间按下标对齐后会得到 `a → readFromNBT` 这类**方法**映射；若字段与方法共用一张表，
	 * 引用父类字段的 `getfield Owner.a` 会命中这张表并改写成方法名，运行期 NoSuchFieldError。
	 * （实测 1.12.2：`World.initCapabilities` 引用 `WorldSavedData.mapName`，被改成 `readFromNBT`。）
	 *
	 * <p>只收录名字确实发生变化的成员；名字相同的无需改写。
	 */
	private static MemberMapping buildMapping(Path srgForgeJar, Path namedForgeJar) throws IOException {
		if (!Files.isRegularFile(srgForgeJar) || !Files.isRegularFile(namedForgeJar)) {
			return MemberMapping.EMPTY;
		}

		final Map<String, ClassMembers> srgClasses = readMemberNames(srgForgeJar);
		final Map<String, ClassMembers> namedClasses = readMemberNames(namedForgeJar);
		final Map<String, String> fields = new HashMap<>();
		final Map<String, String> methods = new HashMap<>();

		for (Map.Entry<String, ClassMembers> entry : srgClasses.entrySet()) {
			final String owner = entry.getKey();
			final ClassMembers srg = entry.getValue();
			final ClassMembers named = namedClasses.get(owner);

			if (named == null) {
				continue;
			}

			// 字段只与字段对齐、方法只与方法对齐；两侧数量不等时整组跳过（宁可不改也不改错）
			collect(srg.fieldNames(), named.fieldNames(), owner, fields);
			collect(srg.methodNames(), named.methodNames(), owner, methods);
		}

		return new MemberMapping(fields, methods);
	}

	/** 把一组同种类成员按下标对齐后写入映射表；两侧数量不等时跳过该组. */
	private static void collect(List<String> srgNames, List<String> namedNames, String owner, Map<String, String> out) {
		if (srgNames.size() != namedNames.size()) {
			return;
		}

		for (int i = 0; i < srgNames.size(); i++) {
			final String srgName = srgNames.get(i);
			final String namedName = namedNames.get(i);

			if (!srgName.equals(namedName)) {
				out.put(owner + "." + srgName, namedName);
			}
		}
	}

	/** 一个类在两份 Forge jar 里的成员名（按 class 文件声明顺序，字段与方法分开）. */
	private record ClassMembers(List<String> fieldNames, List<String> methodNames) {
	}

	/** 字段与方法分开的两张映射表. */
	private record MemberMapping(Map<String, String> fields, Map<String, String> methods) {
		static final MemberMapping EMPTY = new MemberMapping(Map.of(), Map.of());

		boolean isEmpty() {
			return fields.isEmpty() && methods.isEmpty();
		}

		int size() {
			return fields.size() + methods.size();
		}
	}

	/** 读一个 jar 内全部 Forge 类的成员名（字段与方法分开，各自按 class 文件中的声明顺序）. */
	private static Map<String, ClassMembers> readMemberNames(Path jar) throws IOException {
		final Map<String, ClassMembers> result = new HashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			final Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();
				final String name = entry.getName();

				if (!name.endsWith(".class") || !name.startsWith(FORGE_PACKAGE_PREFIX)) {
					continue;
				}

				try (InputStream in = zip.getInputStream(entry)) {
					final ClassReader reader = new ClassReader(readAll(in));
					final List<String> fields = new ArrayList<>();
					final List<String> methods = new ArrayList<>();

					reader.accept(new ClassVisitor(Opcodes.ASM9) {
						@Override
						public FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
							fields.add(fieldName);
							return null;
						}

						@Override
						public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
							methods.add(methodName);
							return null;
						}
					}, 0);

					result.put(reader.getClassName(), new ClassMembers(fields, methods));
				} catch (RuntimeException e) {
					// 单个类解析失败只跳过该类
				}
			}
		}

		return result;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		try (in) {
			return in.readAllBytes();
		}
	}

	/**
	 * 改写统计.
	 *
	 * @param mappingCount 建立的映射条目数
	 * @param changedClasses 实际被改写的 class 数
	 */
	public record Stats(int mappingCount, int changedClasses) {
	}
}
