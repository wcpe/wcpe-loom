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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.mappingio.tree.MappingTree;

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
 *
 * <h2>字段引用：直接表 + 继承链</h2>
 * 上面两张表只含 Forge 类<b>自己声明</b>的成员。但 Forge 代码也会读<b>从 MC 父类继承</b>的字段，
 * 例如 {@code World.initCapabilities} 里的 {@code WorldCapabilityData.a}（= {@code WorldSavedData.mapName}）。
 * Forge 类不在 MC 映射表里，TinyRemapper 解析不到这类引用，名字会原样留成官方名。
 * 因此字段<b>引用</b>还额外沿类层次向上查 MC 映射表，详见 {@link FieldResolver}；
 * 字段<b>声明</b>则只查直接表，详见 {@link DeclarationSafeClassRemapper}。
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
	 * @param targetJar 待改写的 jar（最终 MC jar，已处于目标命名空间）
	 * @param srgForgeJar Forge 的 universal jar（srg 空间）
	 * @param namedForgeJar 已重映射到 named 的 Forge jar
	 * @param mappings MC 映射树；为 {@code null} 时不做继承字段解析（退化成「只改直接表」）
	 * @param fromNamespace 重映射的源命名空间——{@code targetJar} 里没被翻译的引用保持的就是这个空间的名字
	 * @param toNamespace 重映射的目标命名空间
	 * @return 改写统计；两份 Forge jar 缺失或对不上时返回全零
	 * @throws IOException 读写失败
	 */
	public static Stats fix(Path targetJar, Path srgForgeJar, Path namedForgeJar,
			@Nullable MappingTree mappings, String fromNamespace, String toNamespace) throws IOException {
		final MemberMapping mapping = buildMapping(srgForgeJar, namedForgeJar);

		if (mapping.isEmpty()) {
			// 映射为空说明两份 Forge jar 缺失或对不上：改写没有意义，跳过而不是产出半成品
			return new Stats(0, 0, 0);
		}

		final FieldResolver resolver = FieldResolver.create(targetJar, mapping.fields(), mappings, fromNamespace, toNamespace);

		final Remapper remapper = new Remapper() {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return mapping.methods().getOrDefault(owner + "." + name, name);
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				// 本回调只服务【引用】（方法体里的 FieldInsn、注解里的枚举常量）。
				// 字段【声明】走 DeclarationSafeClassRemapper.visitField，刻意不经过这里。
				final String direct = mapping.fields().get(owner + "." + name);

				if (direct != null) {
					return direct;
				}

				// 引用的是继承来的字段：owner 自身没有这条映射，要沿类层次往父类找。
				// 实测 1.12.2：`World.initCapabilities` 读的是父类 `WorldSavedData.mapName`（notch 名 `a`），
				// 而 `a` 在 srg 空间同时是 `WorldCapabilityData.readFromNBT` 的名字——旧版把两者混进一张表，
				// 于是这个字段引用被改成了方法名，运行期 NoSuchFieldError: readFromNBT。
				final String inherited = resolver.resolve(owner, name, descriptor);
				return inherited != null ? inherited : name;
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
					final byte[] rewritten = rewrite(data, remapper, mapping.fields());

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
		return new Stats(mapping.size(), changed, resolver.resolvedCount());
	}

	/** {@return 改写后的字节；未发生改动时返回 {@code null}} */
	private static byte[] rewrite(byte[] data, Remapper remapper, Map<String, String> declaredFieldNames) {
		try {
			final ClassReader reader = new ClassReader(data);
			final ClassWriter writer = new ClassWriter(0);
			reader.accept(new DeclarationSafeClassRemapper(writer, remapper, declaredFieldNames), 0);
			final byte[] result = writer.toByteArray();
			return Arrays.equals(data, result) ? null : result;
		} catch (RuntimeException e) {
			// 单个类解析失败不该让整个改写失败：保持原样，最多漏改该类
			return null;
		}
	}

	/**
	 * 与 {@link ClassRemapper} 相同，唯独把字段【声明】的名字解析换成「只查直接表」.
	 *
	 * <h2>为什么要分开</h2>
	 * ASM 的 {@link ClassRemapper} 对字段声明（{@code visitField}）与字段引用（{@code FieldInsn}）
	 * 走的是同一个 {@link Remapper#mapFieldName}。引用侧需要沿类层次解析（见 {@link FieldResolver}），
	 * 声明侧却绝对不能解析：子类自己声明的字段会拿到父类同名字段的目标名，同一个类里于是出现两个同名字段。
	 * 实测 1.12.2：{@code net/minecraft/world/chunk/Chunk} 因此报
	 * {@code ClassFormatError: Duplicate field name "queuedLightChecks"}。
	 *
	 * <h2>为什么不干脆重写整个 visitor</h2>
	 * 本类用的 {@link Remapper} 只覆写成员名（{@code mapDesc} / {@code mapType} / {@code mapSignature} /
	 * {@code mapValue} 全是恒等），所以 {@code visitField} 里跳过这几步类型重映射与走一遍
	 * {@code super.visitField} 等价；其余（方法体、注解、内部类、RecordComponent）仍交给
	 * {@link ClassRemapper}，行为与改造前逐字一致。
	 */
	private static final class DeclarationSafeClassRemapper extends ClassRemapper {
		/** 直接表：{@code owner.name -> named}，只含 Forge 类自身声明的字段. */
		private final Map<String, String> declaredFieldNames;

		DeclarationSafeClassRemapper(ClassVisitor classVisitor, Remapper remapper, Map<String, String> declaredFieldNames) {
			super(classVisitor, remapper);
			this.declaredFieldNames = declaredFieldNames;
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			if (cv == null) {
				return null;
			}

			final String newName = declaredFieldNames.getOrDefault(className + "." + name, name);
			final FieldVisitor fieldVisitor = cv.visitField(access, newName, descriptor, signature, value);
			return fieldVisitor == null ? null : createFieldRemapper(fieldVisitor);
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

	/**
	 * 沿类层次解析「引用到的继承字段」的名字.
	 *
	 * <h2>为什么需要它</h2>
	 * {@code targetJar} 里的引用可能指向一个<b>不是 owner 自己声明</b>的字段。典型：
	 * {@code getfield net/minecraftforge/common/util/WorldCapabilityData.a}——{@code a} 是父类
	 * {@code WorldSavedData.mapName} 的官方名。Forge 类不在 MC 映射表里，TinyRemapper 解析不到它，
	 * 于是名字原样留着；不改写就会在运行期 {@code NoSuchFieldError}。
	 *
	 * <h2>怎么解析</h2>
	 * 从 owner 出发沿<b>目标命名空间</b>的父类链上行，逐类问两件事：
	 * <ol>
	 *   <li>Forge 直接表里有没有 {@code 类.名字}（覆盖「父类声明、子类引用」的 Forge 字段）；</li>
	 *   <li>MC 映射表里这个类有没有「源命名空间名字 == 引用名字」的字段（覆盖 MC 字段）。</li>
	 * </ol>
	 * 都查不到就继续往父类走，走完还没有就返回 {@code null}（保持原样）。
	 *
	 * <h2>为什么不会误改已经翻译好的名字</h2>
	 * 第 2 步匹配的是<b>源命名空间</b>的名字，所以命中前必须先确认「这个名字在本 jar 里<b>解析不到</b>字段」——
	 * 能解析就说明它已经是目标命名空间的名字，不该再动。两个命名空间会撞名：实测 {@code Chunk} 的
	 * {@code public final int x}（官方名 {@code b}）与 {@code private int queuedLightChecks}（官方名 {@code x}）
	 * 就撞了，不先做这道确认会把本来正确的 {@code Chunk.x} 改成 {@code Chunk.queuedLightChecks}。
	 *
	 * <h2>为什么还要校验「目标名真的存在」</h2>
	 * 光看映射表不够：TinyRemapper 是按「名字 + 描述符」匹配的，而 Forge 会改字段类型。
	 * 实测 1.12.2 的 {@code PotionHelper$MixPredicate}：Forge 把 {@code a} / {@code c} 的类型从
	 * {@code Object} 改成了 {@code IRegistryDelegate}，于是映射表里的 {@code a -> input} 匹配不上，
	 * <b>声明</b>被原样留下（仍叫 {@code a}）。若只改引用，引用就指向一个不存在的 {@code input}，
	 * 运行期 {@code NoSuchFieldError: input}。所以命中映射后还要回到本 jar 核对：
	 * 那个类真的声明了目标名。核对不过就保持原样——名字丑，但与声明一致，不会崩。
	 */
	private static final class FieldResolver {
		/** 继承链上行深度上限：防御环形或异常深的层次. */
		private static final int MAX_DEPTH = 64;
		/**
		 * {@code mapping-io} 里 {@code getName} / {@code getDesc} 的下标口径.
		 *
		 * <p>非负数是<b>目标命名空间下标</b>，负数表示取源命名空间（{@code getSrcName} / {@code getSrcDesc}）。
		 * 注意它<b>不是</b>「源 + 目标」拼起来的整体下标——那样会整体偏移一位（实测报
		 * {@code Index 3 out of bounds for length 3}）。
		 */
		private static final int SOURCE_NAMESPACE = -1;

		private final Map<String, ClassShape> shapes;
		private final Map<String, String> forgeFields;
		private final Map<String, MappingTree.ClassMapping> classesByTargetName;
		private final int toNs;
		/** 同一个 owner + name + desc 在一个 jar 里会被反复问到，缓存一下. */
		private final Map<String, String> cache = new HashMap<>();
		private int resolvedCount;

		private FieldResolver(Map<String, ClassShape> shapes, Map<String, String> forgeFields,
				Map<String, MappingTree.ClassMapping> classesByTargetName, int toNs) {
			this.shapes = shapes;
			this.forgeFields = forgeFields;
			this.classesByTargetName = classesByTargetName;
			this.toNs = toNs;
		}

		static FieldResolver create(Path targetJar, Map<String, String> forgeFields,
				@Nullable MappingTree mappings, String fromNamespace, String toNamespace) throws IOException {
			final int toNs = mappings == null ? -1 : mappings.getNamespaceId(toNamespace);

			// 只有「树里能查到这个目标命名空间」且「未翻译的名字确实属于树的源命名空间」时，
			// 按源命名空间名字去比对才有意义；否则只查直接表，也不为它白白扫一遍整个 jar。
			if (toNs < 0 || !mappings.getSrcNamespace().equals(fromNamespace)) {
				return new FieldResolver(Map.of(), forgeFields, Map.of(), -1);
			}

			final Map<String, MappingTree.ClassMapping> byTargetName = new HashMap<>();

			for (MappingTree.ClassMapping classMapping : mappings.getClasses()) {
				final String targetName = classMapping.getName(toNs);

				if (targetName != null) {
					byTargetName.put(targetName, classMapping);
				}
			}

			return new FieldResolver(readClassShapes(targetJar), forgeFields, byTargetName, toNs);
		}

		@Nullable
		String resolve(String owner, String name, String descriptor) {
			final String key = owner + '.' + name + descriptor;

			if (cache.containsKey(key)) {
				return cache.get(key);
			}

			String result = null;
			String current = owner;

			// 先看这个名字在本 jar 里能不能直接解析到字段。能解析就说明它已经是目标命名空间的名字，
			// 绝不能再按「源命名空间名字」去映射——两个命名空间会撞名（见类注释）。
			if (!isResolvable(owner, name, descriptor)) {
				for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
					final String forge = forgeFields.get(current + "." + name);

					if (forge != null) {
						result = forge;
						break;
					}

					final MappingTree.ClassMapping classMapping = classesByTargetName.get(current);

					if (classMapping != null) {
						final String mapped = findField(classMapping, name, descriptor);

						// 映射说这个类的这个字段叫 mapped，那它在本 jar 里就必须真的叫 mapped。
						// 不成立的典型：Forge 把字段类型从 Object 改成了 IRegistryDelegate，
						// TinyRemapper 按「名字 + 描述符」匹配不上、跳过了【声明】，
						// 只改引用就会把引用指向一个不存在的名字（实测 NoSuchFieldError: input）。
						if (mapped != null && declaresField(current, mapped, descriptor)) {
							result = mapped;
							break;
						}
					}

					final ClassShape shape = shapes.get(current);

					if (shape == null) {
						break;
					}

					current = shape.superName();
				}
			}

			if (result != null) {
				resolvedCount++;
			}

			cache.put(key, result);
			return result;
		}

		/**
		 * {@return 该类里「源命名空间名字 == {@code srcName}」的字段在目标命名空间的名字，查不到返回 {@code null}}
		 *
		 * <p>混淆代码里同一个类可以有多个同名不同描述符的字段，所以优先按描述符区分；
		 * 名字唯一命中时即使描述符对不上也接受（描述符在两个命名空间里写法不同，硬比会漏）。
		 */
		@Nullable
		private String findField(MappingTree.ClassMapping classMapping, String srcName, String descriptor) {
			MappingTree.FieldMapping nameOnlyMatch = null;
			int nameMatches = 0;

			for (MappingTree.FieldMapping field : classMapping.getFields()) {
				if (!srcName.equals(field.getName(SOURCE_NAMESPACE))) {
					continue;
				}

				if (descriptor.equals(field.getDesc(toNs))) {
					return field.getName(toNs);
				}

				nameOnlyMatch = field;
				nameMatches++;
			}

			return nameMatches == 1 ? nameOnlyMatch.getName(toNs) : null;
		}

		/** {@return 这个引用在本 jar 的目标命名空间里是否已经能解析到字段} */
		private boolean isResolvable(String owner, String name, String descriptor) {
			final String fieldKey = name + ':' + descriptor;
			String current = owner;

			for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
				final ClassShape shape = shapes.get(current);

				if (shape == null) {
					return false;
				}

				if (shape.fieldKeys().contains(fieldKey)) {
					return true;
				}

				current = shape.superName();
			}

			return false;
		}

		/** {@return 该类在本 jar 里是否真的声明了「名字 + 描述符」都匹配的字段} */
		private boolean declaresField(String owner, String name, String descriptor) {
			final ClassShape shape = shapes.get(owner);
			return shape != null && shape.fieldKeys().contains(name + ':' + descriptor);
		}

		int resolvedCount() {
			return resolvedCount;
		}
	}

	/** {@return 目标 jar 里每个类的外形（直接父类 + 自己声明的字段名），供继承链上行与一致性校验用} */
	private static Map<String, ClassShape> readClassShapes(Path jar) throws IOException {
		final Map<String, ClassShape> result = new HashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			final Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();

				if (!entry.getName().endsWith(".class")) {
					continue;
				}

				try (InputStream in = zip.getInputStream(entry)) {
					final ClassReader reader = new ClassReader(readAll(in));
					final Set<String> fieldKeys = new HashSet<>();
					reader.accept(new ClassVisitor(Opcodes.ASM9) {
						@Override
						public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
							fieldKeys.add(name + ':' + descriptor);
							return null;
						}
					}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					result.put(reader.getClassName(), new ClassShape(reader.getSuperName(), fieldKeys));
				} catch (RuntimeException e) {
					// 单个类解析失败只跳过该类
				}
			}
		}

		return result;
	}

	/** 一个类在目标 jar 里的外形：直接父类名 + 自己声明的字段名. */
	private record ClassShape(@Nullable String superName, Set<String> fieldKeys) {
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
	 * @param resolvedInheritedFields 靠继承链解析翻译的字段引用处数
	 */
	public record Stats(int mappingCount, int changedClasses, int resolvedInheritedFields) {
	}
}
