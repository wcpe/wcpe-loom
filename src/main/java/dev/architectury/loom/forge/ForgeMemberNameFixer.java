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
import java.util.function.UnaryOperator;
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
 * 补译最终 Minecraft jar 内仍用官方混淆名引用的 Forge 成员.
 *
 * <p>Legacy Forge 的 binpatch 给 Minecraft 类加入了对 Forge 成员的调用。例如，原版调用
 * {@code ModelLoader.a()Lfm;}，而另行重映射的 Forge 已把声明改成 {@code setupModelRegistry}。
 * Minecraft 的映射表未记录 Forge 的这些方法名，只能翻译描述符，导致运行时找不到方法。
 *
 * <p>方法以两份 Forge 参考 jar 的声明为依据：将 universal 的描述符换成 named，先排除未改名的签名，
 * 只配对剩余的一对一签名。补译时按 owner、旧方法名和最终描述符查表，且必须在目标 jar 确认真正的声明。
 * 这样不会把已正确链接的同名重载、构造器或无法唯一配对的方法一起改名。
 *
 * <p>字段保持独立：Forge 类自己声明的字段使用直接表，字段引用还可沿父类查找 Minecraft 映射。
 * 详见 {@link FieldResolver} 和 {@link DeclarationSafeClassRemapper}。
 */
public final class ForgeMemberNameFixer {
	/** Forge 类的包前缀：只处理这些包下的成员，MC 自身成员不受影响. */
	private static final String FORGE_PACKAGE_PREFIX = "net/minecraftforge/";

	private ForgeMemberNameFixer() {
	}

	/**
	 * 补译 {@code targetJar} 中未翻译的 Forge 成员引用.
	 *
	 * <p>就地改写：读原文件、写同目录临时文件、成功后替换。任一步失败时原文件保持不动。
	 *
	 * @param targetJar 已重映射、包含 Forge 类的最终 Minecraft jar
	 * @param universalForgeJar Forge 发布的 universal jar（official 命名空间）
	 * @param namedForgeJar 已重映射到 named 的 Forge jar
	 * @param mappings Minecraft 映射树；为 {@code null} 时仅按原始描述符匹配方法，不解析继承字段
	 * @param fromNamespace Minecraft 重映射的源命名空间，未翻译的字段名属于此空间
	 * @param toNamespace Minecraft 重映射的目标命名空间
	 * @return 改写统计；缺少参考 JAR 或没有可用映射时返回全零
	 * @throws IOException 读写失败
	 */
	public static Stats fix(Path targetJar, Path universalForgeJar, Path namedForgeJar,
			@Nullable MappingTree mappings, String fromNamespace, String toNamespace) throws IOException {
		if (!Files.isRegularFile(universalForgeJar) || !Files.isRegularFile(namedForgeJar)) {
			return new Stats(0, 0, 0);
		}

		final MemberMapping mapping = buildMapping(targetJar, universalForgeJar, namedForgeJar, mappings, toNamespace);
		final FieldResolver resolver = FieldResolver.create(targetJar, mapping.fields(), mappings, fromNamespace, toNamespace);

		// Forge 直接差异为空时，引用仍可能需要沿继承链查找 Minecraft 父类的字段映射。
		if (mapping.isEmpty() && resolver.classesByTargetName.isEmpty()) {
			return new Stats(0, 0, 0);
		}

		final Remapper remapper = new Remapper() {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return mapping.methods().getOrDefault(owner + '.' + name + descriptor, name);
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

	/** 建立 Forge 字段和方法的两张补译表，两者即使同名也不能混用. */
	private static MemberMapping buildMapping(Path targetJar, Path universalForgeJar, Path namedForgeJar,
			@Nullable MappingTree mappings, String toNamespace) throws IOException {
		final Map<String, ClassMembers> universalClasses = readMemberNames(universalForgeJar, descriptorMapper(mappings, "official", "named"));
		final Map<String, ClassMembers> namedClasses = readMemberNames(namedForgeJar, UnaryOperator.identity());
		final Map<String, ClassMembers> targetClasses = readMemberNames(targetJar, UnaryOperator.identity());
		final UnaryOperator<String> namedToTarget = descriptorMapper(mappings, "named", toNamespace);
		final Map<String, String> fields = new HashMap<>();
		final Map<String, String> methods = new HashMap<>();

		for (Map.Entry<String, ClassMembers> entry : universalClasses.entrySet()) {
			final String owner = entry.getKey();
			final ClassMembers universal = entry.getValue();
			final ClassMembers named = namedClasses.get(owner);

			if (named == null) {
				continue;
			}

			collect(universal.fieldNames(), named.fieldNames(), owner, fields);
			final ClassMembers target = targetClasses.get(owner);

			if (target != null) {
				collectMethods(universal.methods(), named.methods(), target.methods(), owner, namedToTarget, methods);
			}
		}

		return new MemberMapping(fields, methods);
	}

	private static UnaryOperator<String> descriptorMapper(@Nullable MappingTree mappings, String from, String to) {
		if (mappings == null || from.equals(to)) {
			return UnaryOperator.identity();
		}

		final int fromId = mappings.getNamespaceId(from);
		final int toId = mappings.getNamespaceId(to);

		if (fromId == MappingTree.NULL_NAMESPACE_ID || toId == MappingTree.NULL_NAMESPACE_ID) {
			return UnaryOperator.identity();
		}

		return descriptor -> mappings.mapDesc(descriptor, fromId, toId);
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

	/** 先排除同描述符下未改名的方法，只对两侧均唯一的剩余方法建立映射. */
	private static void collectMethods(Map<String, Set<String>> universal, Map<String, Set<String>> named,
			Map<String, Set<String>> target, String owner, UnaryOperator<String> namedToTarget, Map<String, String> out) {
		for (Map.Entry<String, Set<String>> entry : universal.entrySet()) {
			final String descriptor = entry.getKey();
			final Set<String> oldNames = entry.getValue();
			final Set<String> newNames = named.get(descriptor);

			if (newNames == null || oldNames.size() != newNames.size()) {
				continue;
			}

			final List<String> removed = oldNames.stream().filter(name -> !newNames.contains(name)).toList();

			if (removed.size() != 1) {
				continue;
			}

			final String oldName = removed.getFirst();
			final String newName = newNames.stream().filter(name -> !oldNames.contains(name)).findFirst().orElseThrow();
			final String targetDescriptor = namedToTarget.apply(descriptor);
			final Set<String> targetNames = target.get(targetDescriptor);

			// 已经能解析的签名与不存在目标声明的签名都不能改，否则可能重命名声明本身或制造新断链。
			if (targetNames != null && !targetNames.contains(oldName) && targetNames.contains(newName)) {
				out.put(owner + '.' + oldName + targetDescriptor, newName);
			}
		}
	}

	/** 一个 Forge 类的字段声明顺序及按方法描述符分组的名字. */
	private record ClassMembers(List<String> fieldNames, Map<String, Set<String>> methods) {
	}

	/** 字段与方法分开的两张映射表. */
	private record MemberMapping(Map<String, String> fields, Map<String, String> methods) {
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

	/** 读取 jar 中的 Forge 声明，按提供的转换统一方法描述符. */
	private static Map<String, ClassMembers> readMemberNames(Path jar, UnaryOperator<String> mapDescriptor) throws IOException {
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
					final Map<String, Set<String>> methods = new HashMap<>();

					reader.accept(new ClassVisitor(Opcodes.ASM9) {
						@Override
						public FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
							fields.add(fieldName);
							return null;
						}

						@Override
						public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
							if (!methodName.startsWith("<")) {
								methods.computeIfAbsent(mapDescriptor.apply(descriptor), unused -> new HashSet<>()).add(methodName);
							}

							return null;
						}
}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
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
