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

package dev.architectury.loom.forge.minecraft;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import lzma.sdk.lzma.Decoder;
import lzma.streams.LzmaInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loom.util.FileSystemUtil;

/**
 * 用 Forge 官方 installer 的运行时补丁包补齐 userdev 产物里缺失的内部类.
 *
 * <h2>为什么需要它</h2>
 *
 * <p>Forge 的 userdev 包只发布合并后的补丁 {@code joined.lzma}，其中<b>不含任何内部类/匿名类的
 * 补丁条目</b>（实测 1.21.11 + 61.2.5：7270 条里 {@code $} 条目为 0）。而官方 installer 的运行时
 * 补丁 {@code data/client.lzma} / {@code data/server.lzma} 是完整的（分别含 810 / 809 条）。
 * 于是用 userdev 自建开发环境时，{@code Items.class} 会引用 {@code Items$1} 却没有该类，
 * 运行到类初始化就抛 {@code NoClassDefFoundError}，而栈顶指向的常是无关的触发方
 * （例如 {@code ComposterBlock.boot_strap} 里的 {@code getstatic Items.XXX}）。
 *
 * <p>两份补丁锚定同一份原版 jar，命名空间一致（均为官方名），因此运行时补丁里的类体可以
 * 直接搬进 userdev 产物，无需任何映射转换。这也是本方案安全的前提：不做名字推断、不做语义改写，
 * 只是把本该存在、却没被 userdev 补丁覆盖的类原样补上。
 *
 * <h2>只补缺失的</h2>
 *
 * <p>唯一被改写的是「产物里没有该条目」的类。已存在的一律不动——userdev 产物里那些类可能
 * 已被重新编译过（参数名删除等），覆盖只会引入差异。
 */
public final class RuntimeBinpatchExtractor {
	private static final byte[] CLASS_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

	private RuntimeBinpatchExtractor() {
	}

	/**
	 * 从 installer 中取出运行时补丁包，补齐 {@code patchedJar} 里缺失的内部类.
	 *
	 * @param installer   Forge 官方 installer jar（含 {@code data/client.lzma} 与 {@code data/server.lzma}）
	 * @param patchedJar  待补齐的补丁产物，原地改写
	 * @param missing     需要补的类名（{@code net/minecraft/...} 形式，不含 {@code .class}）
	 * @param tempDir     解压 lzma 用的临时目录
	 * @param info        日志出口
	 * @return 实际补入的类数
	 */
	public static int fillMissingInnerClasses(Path installer, Path patchedJar, Set<String> missing,
			Path tempDir, Consumer<String> info) throws IOException {
		if (missing.isEmpty()) {
			return 0;
		}

		// 类名 -> 类体。两份补丁可能都含同名类（client/server 各有侧重），先到先得即可：
		// 同一版本的 client 与 server 补丁对同一个类产出的字节码是一致的。
		final Map<String, byte[]> bodies = new TreeMap<>();

		try (ZipFile installerZip = new ZipFile(installer.toFile())) {
			for (String entry : new String[] {"data/client.lzma", "data/server.lzma"}) {
				final ZipEntry zipEntry = installerZip.getEntry(entry);

				if (zipEntry == null) {
					continue;
				}

				final byte[] lzma = installerZip.getInputStream(zipEntry).readAllBytes();
				collectFromPatchBundle(lzma, missing, bodies, tempDir);
			}
		}

		if (bodies.isEmpty()) {
			info.accept(":installer 的运行时补丁里没有找到任何待补的类");
			return 0;
		}

		return writeIntoJar(patchedJar, bodies, info);
	}

	/**
	 * 从一份运行时补丁包（lzma 压缩的 zip）里取出指定类的类体.
	 *
	 * <p>补丁包条目形如 {@code net.minecraft.world.item.Items$1.binpatch}——注意是点分全名，
	 * 且 {@code $} 属于类名而不是包分隔符，转路径时只能在 {@code $} 之前的点换成分隔符。
	 */
	private static void collectFromPatchBundle(byte[] lzma, Set<String> missing, Map<String, byte[]> bodies,
			Path tempDir) throws IOException {
		final Path archive = tempDir.resolve("runtime-patches-" + System.nanoTime() + ".zip");

		try {
			// Forge 的 .lzma 是 lzma1 容器，用与 PatchProvider 相同的库解。
			// 若输入本身已是 zip（例如测试或未来的未压缩发布形态），直接用，不去解 lzma。
			if (isZip(lzma)) {
				Files.write(archive, lzma);
			} else {
				try (LzmaInputStream in = new LzmaInputStream(new ByteArrayInputStream(lzma), new Decoder())) {
					Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
				}
			}

			try (ZipFile zip = new ZipFile(archive.toFile())) {
				final var entries = zip.entries();

				while (entries.hasMoreElements()) {
					final ZipEntry entry = entries.nextElement();
					final String name = entry.getName();

					if (!name.endsWith(".binpatch")) {
						continue;
					}

					final String className = binpatchNameToClassName(name);

					if (className == null || !missing.contains(className) || bodies.containsKey(className)) {
						continue;
					}

					final byte[] body = readClassBody(zip.getInputStream(entry).readAllBytes());

				// 条目名不能证明类体已经位于目标命名空间。
				if (body != null && className.equals(new ClassReader(body).getClassName())) {
					bodies.put(className, body);
				}
				}
			}
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	/**
	 * {@code net.minecraft.world.item.Items$1.binpatch} → {@code net/minecraft/world/item/Items$1}.
	 *
	 * <p>不能简单地把所有点换成斜杠：{@code $} 后的编号属于类名，若整体替换，
	 * {@code Items$1} 仍是 {@code Items$1}（该段内无点），但包名部分必须先转成斜杠。
	 * 这里按「最后一个 {@code $} 之后不再拆分」的规则处理即可满足 binpatch 的命名。
	 */
	private static @Nullable String binpatchNameToClassName(String entryName) {
		final String base = entryName.substring(0, entryName.length() - ".binpatch".length());
		final int firstDollar = base.indexOf('$');
		final String pkgPart = firstDollar >= 0 ? base.substring(0, firstDollar) : base;
		final String innerPart = firstDollar >= 0 ? base.substring(firstDollar) : "";

		return pkgPart.replace('.', '/') + innerPart;
	}

	/**
	 * 从 binpatch 负载里取出内嵌的 class 字节码.
	 *
	 * <p>格式（version 1，与 Forge {@code Patch.from(InputStream)} 逐字段对应）：
	 * {@code u1 版本, UTF 源名, UTF 目标名, boolean exists,
	 * [exists 时 u4 校验和], u4 数据长度, 数据}。
	 *
	 * <p><b>只有 {@code exists == false} 的条目可以安全取出。</b>该标志的语义是「补丁前，
	 * 原版 jar 里是否已有这个类」：
	 * <ul>
	 *   <li>{@code false}——原版没有此类，数据是完整类体，可直接用；</li>
	 *   <li>{@code true}——原版已有此类，数据是以原类为源的 GDIFF 差分，<b>不是</b>类文件。
	 *       要还原必须拿到那份原类，而它是官方混淆客户端，本仓库的产物链从未产生，
	 *       因此这里返回 {@code null}，由调用方跳过（宁可不补，也不补错）。</li>
	 * </ul>
	 *
	 * <p>实测：{@code Items$1} 等缺失类全是 {@code exists == false}；需要差分的那批
	 * （如 {@code BrewingStandMenu$PotionSlot}）本就不在缺失列表里。
	 *
	 * <p>即便标志为 {@code false}，仍用 ASM 复核一遍类结构：早期实现只扫描 {@code CAFEBABE}
	 * 魔数就返回，遇到差分补丁会把魔数之后的碎片当成类写进产物，直到运行时才暴露。
	 */
	private static byte @Nullable [] readClassBody(byte[] binpatch) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(binpatch))) {
			final int version = in.readUnsignedByte();

			if (version != 1) {
				return null;
			}

			skipString(in); // 源类名
			skipString(in); // 目标类名
			// exists：原版 jar 是否已有该类。为 true 时数据是 GDIFF 差分，无法在此还原。
			final boolean exists = in.readUnsignedByte() != 0;

			if (exists) {
				return null;
			}

			in.readInt(); // 数据长度；按流读到底即可，长度仅用于切分。

			final byte[] rest = in.readAllBytes();
			final int offset = indexOf(rest, CLASS_MAGIC);

			if (offset < 0) {
				return null;
			}

			final byte[] body = new byte[rest.length - offset];
			System.arraycopy(rest, offset, body, 0, body.length);
			return isLoadableClass(body) ? body : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** 用 ASM 复核：能完整读出类结构才算合法，避免把差分碎片当成类写进产物. */
	private static boolean isLoadableClass(byte[] body) {
		try {
			new ClassReader(body).accept(new ClassNode(), 0);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** 是否是 zip（PK 魔数）. */
	private static boolean isZip(byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B;
	}

	private static void skipString(DataInputStream in) throws IOException {
		final int length = in.readUnsignedShort();
		in.readNBytes(length);
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:

		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}

			return i;
		}

		return -1;
	}

	/** 把类体写进 jar；已存在的条目一律跳过，返回实际写入数. */
	private static int writeIntoJar(Path jar, Map<String, byte[]> bodies, Consumer<String> info) throws IOException {
		int written = 0;

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar, false)) {
			for (Map.Entry<String, byte[]> entry : bodies.entrySet()) {
				final String path = entry.getKey() + ".class";
				final Path target = fs.get().getPath(path);

				if (Files.exists(target)) {
					continue;
				}

				final Path parent = target.getParent();

				if (parent != null) {
					Files.createDirectories(parent);
				}

				Files.write(target, entry.getValue());
				written++;
			}
		}

		if (written > 0) {
			info.accept(":从 installer 的运行时补丁补入 " + written + " 个缺失内部类");
		}

		return written;
	}

	/** 与 {@link #fillMissingInnerClasses} 配套：把待补集合转成排序后的列表，便于稳定日志. */
	public static List<String> sorted(Set<String> classes) {
		return new ArrayList<>(new TreeSet<>(classes));
	}

	/** 仅供测试：直接解析单个 binpatch 的类体. */
	static byte @Nullable [] readClassBodyForTest(byte[] binpatch) {
		return readClassBody(binpatch);
	}

	/** 仅供测试：binpatch 条目名转类名. */
	static @Nullable String binpatchNameToClassNameForTest(String entryName) {
		return binpatchNameToClassName(entryName);
	}
}
