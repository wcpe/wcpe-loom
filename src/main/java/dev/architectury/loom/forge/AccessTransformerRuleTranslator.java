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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;

/**
 * 把 Forge 的 access transformer 规则翻译到「目标 jar 所在的命名空间」.
 *
 * <h2>为什么必须翻译</h2>
 * legacy Forge 的 {@code forge_at.cfg} 用的是 <b>SRG 命名空间</b>：类名是 MCP 名
 * （{@code net.minecraft.util.ObjectIntIdentityMap}），成员名是 SRG 名
 * （{@code field_148749_a}）。而 AT 工具（{@code TransformerProcessor}）是纯 ASM 重写器，
 * 它按<b>字面量</b>匹配类名与成员名，自身不做任何名称翻译。
 *
 * <p>本仓库 legacy 链路上 AT 作用的对象是 {@code merged-patched.jar}，其内容是 <b>official（notch）名</b>
 * （{@code fd.class} 的字段 {@code a}）。把 SRG 形态的规则直接喂给 AT 工具，规则一条都匹配不上：
 * AT 工具不报错、产物照常生成，表现为「AT 静默失效」，最终在运行期炸成
 * {@code IllegalAccessError}（如 Forge 读 {@code ObjectIntIdentityMap.identityMap}）。
 *
 * <p>因此必须在 AT 执行前把规则翻译到目标 jar 的命名空间。翻译是纯查表，无副作用。
 *
 * <h2>规则形态</h2>
 * 每行形如 {@code <access> <类名> [成员名 [描述符]]}，{@code #} 之后是注释。翻译只改类名与成员名：
 * <ul>
 *   <li>类名：按 {@code 源命名空间 → 目标命名空间} 查类映射；</li>
 *   <li>成员名：在该类的成员表里按名字（方法的签名一并参与）查映射；</li>
 *   <li>描述符、修饰符关键字原样保留。</li>
 * </ul>
 *
 * <h2>失败即报错</h2>
 * 与「静默跳过」相反，本类对无法翻译的规则<b>直接抛异常</b>，并汇总全部失败行。
 * AT 失效在生产上极难诊断（症状出现在运行期的 {@code IllegalAccessError}，与构建毫无关联），
 * 因此宁可让构建在翻译阶段失败并列出具体规则，也不允许它带着残缺的 AT 继续产出 jar。
 */
public final class AccessTransformerRuleTranslator {
	private static final Logger LOGGER = LoggerFactory.getLogger(AccessTransformerRuleTranslator.class);

	private AccessTransformerRuleTranslator() {
	}

	/**
	 * 一次翻译所需的全部输入.
	 *
	 * @param rules 原始 AT 规则行（含注释行与空行，原样传入）
	 * @param mappings 映射树
	 * @param sourceNamespace 规则当前的命名空间（legacy Forge 为 {@code srg}）
	 * @param targetNamespace 目标 jar 所在的命名空间（notch jar 为 {@code official}）
	 * @param hierarchy 类继承关系；为 {@code null} 时不做继承回退（只有直接成员能翻译）
	 */
	public record Input(List<String> rules, MappingTree mappings, String sourceNamespace, String targetNamespace,
			@Nullable ClassHierarchy hierarchy) {
	}

	/**
	 * 类继承关系的查询入口.
	 *
	 * <p>AT 允许把规则写在子类上、而成员实际声明在父类（如
	 * {@code EntityPlayerSP} 的 {@code func_184816_a} 声明在 {@code EntityPlayer}）。
	 * 这种情况必须沿父类型上溯找到真正的声明类，并把规则改写到该类——
	 * 映射表按「声明类」收录成员，直接拿子类去查必然查不到。
	 *
	 * <p>实现由调用方提供：翻译器只做纯查表，不关心继承数据从哪个 jar 读出。
	 */
	public interface ClassHierarchy {
		/**
		 * {@return 该类的直接父类型（父类与接口）} 名称用斜杠形式（{@code net/minecraft/...}）；
		 * 无父类型时返回空列表。
		 */
		List<String> parentsOf(String className);
	}

	/**
	 * 翻译全部规则.
	 *
	 * <p>注释行、空行原样保留（保持产物可读且便于与原始 AT 对照），只有规则行会被改写。
	 *
	 * @param input 翻译输入
	 * @return 翻译后的规则行，与输入行数一一对应
	 * @throws AccessTransformerTranslationException 存在无法翻译的规则行时
	 */
	public static List<String> translate(Input input) {
		final List<String> output = new ArrayList<>(input.rules().size());
		final List<String> failures = new ArrayList<>();
		final List<String> skipped = new ArrayList<>();

		for (String line : input.rules()) {
			final String rule = stripComment(line).trim();

			if (rule.isEmpty()) {
				// 空行与纯注释行原样保留，避免产物与原始 AT 的行号错位
				output.add(line);
				continue;
			}

			if (line.trim().startsWith("#")) {
				output.add(line);
				continue;
			}

			final String translated = translateRule(rule, input, failures, skipped);

			if (translated != null) {
				output.add(translated);
			}
		}

		if (!skipped.isEmpty()) {
			// 用 warn 而不是 info：默认控制台级别是 LIFECYCLE，info 不可见，
			// 「有规则被跳过」必须默认可见，否则等于静默丢 AT。
			LOGGER.warn("有 {} 条 access transformer 规则指向此版本不存在的成员，已跳过（不影响构建，但该行的 AT 不会生效）：", skipped.size());

			for (String message : skipped) {
				LOGGER.warn("  {}", message);
			}
		}

		if (!failures.isEmpty()) {
			throw new AccessTransformerTranslationException(failures);
		}

		return output;
	}

	/**
	 * 翻译单条规则.
	 *
	 * @return 翻译结果；无法翻译时记入 {@code failures} 并返回 {@code null}
	 */
	private static @Nullable String translateRule(String rule, Input input, List<String> failures, List<String> skipped) {
		// 规则以空白切分：<access> <class> [member [descriptor]]
		final String[] parts = rule.split("\\s+");

		if (parts.length < 2) {
			failures.add("无法解析的 AT 规则（至少需要 <access> <class>）: " + rule);
			return null;
		}

		final String access = parts[0];
		final String sourceClass = parts[1];

		// 类名在 AT 里写点号形态，映射树用斜杠形态
		final String mappedClass = mapClass(sourceClass.replace('.', '/'), input);

		if (mappedClass == null) {
			failures.add("类无法翻译（%s 里找不到 %s）: %s"
					.formatted(input.sourceNamespace(), sourceClass, rule));
			return null;
		}

		final StringBuilder result = new StringBuilder();
		result.append(access).append(' ').append(mappedClass.replace('/', '.'));

		if (parts.length >= 3) {
			// 成员名与描述符之间没有空白：方法写 func_149752_b(F)Lnet/minecraft/block/Block;
			final String memberSpec = parts[2];
			final int parenIdx = memberSpec.indexOf('(');
			final String member = parenIdx >= 0 ? memberSpec.substring(0, parenIdx) : memberSpec;
			final String descriptor = parenIdx >= 0 ? memberSpec.substring(parenIdx) : null;

			// 通配符（* 或 *()）表达「全部成员」，无需也无法翻译，原样保留
			if (member.indexOf('*') >= 0) {
				result.append(' ').append(memberSpec);
				return result.toString();
			}

			// <init>/<clinit> 是构造器与静态初始化块，映射树里没有它们的条目（名字本就不参与混淆），
			// 只需翻译描述符即可；当成普通成员去查会永远查不到
			if (member.startsWith("<")) {
				result.append(' ').append(member);
				result.append(descriptor == null ? "" : translateDescriptorTo(descriptor, input, input.targetNamespace()));
				return result.toString();
			}

			// 成员可能声明在父类上（AT 允许把规则写在子类上），
			// 因此解析结果同时给出「真正声明它的类」和该成员的目标名
			final ResolvedMember resolved = resolveMember(sourceClass.replace('.', '/'), member, descriptor, input);

			if (resolved == null) {
				// 区分「翻译缺陷」与「上游坏条目」：成员名在整个映射树里一次都没出现过时，
				// 这条规则指向的名字在此版本根本不存在，任何映射都译不出来，强行译出也作用不到
				// 任何东西。这类降级为警告并原样跳过；其余失败照旧报错（见方法注释里的实测来源）。
				if (memberNameAbsentEverywhere(member, input)) {
					skipped.add("规则指向的成员在此版本不存在，已跳过（%s 的 %s 在 %s 里从未出现）: %s"
							.formatted(sourceClass, member, input.sourceNamespace(), rule));
					return null;
				}

				failures.add("成员无法翻译（%s 的 %s 在 %s → %s 里找不到，继承链上也未找到）: %s".formatted(
						sourceClass, member, input.sourceNamespace(), input.targetNamespace(), rule));
				return null;
			}

			// 规则改写到声明类：AT 只对声明处生效，写在子类上对父类声明无效
			if (!resolved.ownerNotch().equals(mappedClass)) {
				result.setLength(0);
				result.append(access).append(' ').append(resolved.ownerNotch().replace('/', '.'));
			}

			result.append(' ').append(resolved.memberName());

			if (descriptor != null) {
				// 描述符里的类型名同样来自 SRG 命名空间（如 Lnet/minecraft/block/Block;），
				// 只翻译成员名而不翻译描述符，带描述符的规则仍匹配不上目标 jar
				result.append(translateDescriptorTo(descriptor, input, input.targetNamespace()));
			}
		}

		return result.toString();
	}

	/** 成员解析结果：声明它的类（目标命名空间）与该成员在目标命名空间里的名字. */
	private record ResolvedMember(String ownerNotch, String memberName) {
	}

	/**
	 * 解析成员的声明位置与目标名，必要时沿父类型上溯.
	 *
	 * <p>先在本类查；查不到再按广度优先遍历父类型（含接口），
	 * 因此「规则写在子类、成员声明在祖先」的写法也能落到正确的位置。
	 *
	 * @param sourceClass 规则里写的类（源命名空间、斜杠形式）
	 * @return 解析结果；本类与整条继承链都没有该成员时返回 {@code null}
	 */
	private static @Nullable ResolvedMember resolveMember(String sourceClass, String member, @Nullable String descriptor, Input input) {
		final Deque<String> queue = new ArrayDeque<>();
		final Set<String> visited = new HashSet<>();
		queue.add(sourceClass);

		while (!queue.isEmpty()) {
			final String current = queue.poll();

			if (!visited.add(current)) {
				continue;
			}

			final String mappedMember = mapMember(current, member, descriptor, input);

			if (mappedMember != null) {
				final String notch = mapClassBetween(current, input.sourceNamespace(), input.targetNamespace(), input);
				return notch == null ? null : new ResolvedMember(notch, mappedMember);
			}

			if (input.hierarchy() == null) {
				continue;
			}

			// 继承关系由 jar 提供，而 jar 的类名用哪个命名空间**随车道而变**：
			//   · legacy Forge（1.12.2）：AT 作用对象是 notch jar，类名在 official 命名空间；
			//   · 现代 Forge/NeoForge：AT 作用对象是 patched intermediate jar，类名已是 srg 名
			//     （1.17+ 起 srg 命名空间的类名沿用 mojang 名，只有成员是 m_/f_ 形式）。
			// 因此不能写死成某一个命名空间：先按目标命名空间查，查不到再按源命名空间查。
			// 只按目标命名空间查会让现代车道的继承链永远为空，进而漏掉「规则写在子类、
			// 成员声明在祖先」的那部分规则（实测 1.21.1 有 8 条，如 LocalPlayer 的 m_8088_
			// 实际声明在 Entity 上）。
			for (String candidateNs : new String[] { input.targetNamespace(), input.sourceNamespace() }) {
				final String candidate = mapClassBetween(current, input.sourceNamespace(), candidateNs, input);

				if (candidate == null) {
					continue;
				}

				final List<String> parentNotches = input.hierarchy().parentsOf(candidate);

				if (parentNotches.isEmpty()) {
					continue;
				}

				for (String parentNotch : parentNotches) {
					final String parentSource = mapClassBetween(parentNotch, candidateNs, input.sourceNamespace(), input);

					if (parentSource != null) {
						queue.add(parentSource);
					}
				}

				break;
			}
		}

		return null;
	}

	/**
	 * {@return 该成员名在整个映射树里是否一次都没出现过}.
	 *
	 * <p>用来区分两类「成员无法翻译」：
	 * <ul>
	 *   <li>成员名在映射里存在、只是不在当前类及其祖先上——可能是继承链信息不全，
	 *       属需要排查的翻译缺陷，仍应报错；</li>
	 *   <li>成员名在整棵树里一次都没有——规则指向的名字在此版本根本不存在，
	 *       任何映射都无法翻译成功。这类是上游 AT 文件的坏条目：即便强行译出，
	 *       AT 也作用不到任何东西。</li>
	 * </ul>
	 *
	 * <p>实测来源：NeoForge 20.2.93 的 accesstransformer.cfg 里
	 * {@code BlockEntityRenderDispatcher fontRenderer} 一行，`fontRenderer` 是注释文字被误写成了
	 * 成员名；该字段的正确名一直是 {@code font}（对照 1.20.1 同一条规则写的是 srg 名
	 * {@code f_112253_}，其 named 列即 {@code font}）。
	 *
	 * <p>只遍历方法名与字段名，不碰描述符：描述符不一致本身不会让名字消失，
	 * 而放宽到「描述符不符也算不存在」会把真正的翻译缺陷一起吞掉。
	 */
	private static boolean memberNameAbsentEverywhere(String member, Input input) {
		final int sourceId = namespaceId(input, input.sourceNamespace());

		if (sourceId == MappingTreeView.NULL_NAMESPACE_ID) {
			return false;
		}

		for (MappingTreeView.ClassMappingView classMapping : input.mappings().getClasses()) {
			for (MappingTreeView.MethodMappingView method : classMapping.getMethods()) {
				if (member.equals(method.getName(input.sourceNamespace()))) {
					return false;
				}
			}

			for (MappingTreeView.FieldMappingView field : classMapping.getFields()) {
				if (member.equals(field.getName(input.sourceNamespace()))) {
					return false;
				}
			}
		}

		return true;
	}

	/** {@return 目标命名空间下的类名；找不到时返回 {@code null}} */
	private static @Nullable String mapClass(String sourceClass, Input input) {
		final int sourceId = namespaceId(input, input.sourceNamespace());

		if (sourceId == MappingTreeView.NULL_NAMESPACE_ID) {
			return null;
		}

		final MappingTreeView.ClassMappingView classMapping = input.mappings().getClass(sourceClass, sourceId);

		if (classMapping == null) {
			return null;
		}

		return classMapping.getName(input.targetNamespace());
	}

	/** {@return 命名空间在映射树里的下标；不存在时返回 {@link MappingTreeView#NULL_NAMESPACE_ID}} */
	private static int namespaceId(Input input, String namespace) {
		return input.mappings().getNamespaceId(namespace);
	}

	/**
	 * {@return 目标命名空间下的成员名；找不到时返回 {@code null}}
	 *
	 * <p>方法与字段分开查：带描述符时按「名字 + 描述符」定位方法，否则在字段里查。
	 * 1.12.2 的 AT 规则在成员名后可能带方法描述符（如 {@code func_12345_a ()V}）。
	 */
	private static @Nullable String mapMember(String sourceClass, String member, @Nullable String descriptor, Input input) {
		final int sourceId = namespaceId(input, input.sourceNamespace());

		if (sourceId == MappingTreeView.NULL_NAMESPACE_ID) {
			return null;
		}

		final MappingTreeView.ClassMappingView classMapping = input.mappings().getClass(sourceClass, sourceId);

		if (classMapping == null) {
			return null;
		}

		if (descriptor != null) {
			// 映射树的描述符只有一个形态：源命名空间 + 斜杠（getSrcDesc() 形如 (F)Laow;），
			// 而 AT 的描述符是点号写法，因此统一成斜杠后再比对
			final String sourceDesc = translateDescriptorTo(descriptor, input, input.mappings().getSrcNamespace())
					.replace('.', '/');

			for (MappingTreeView.MethodMappingView method : classMapping.getMethods()) {
				if (!member.equals(method.getName(input.sourceNamespace()))) {
					continue;
				}

				if (!sourceDesc.equals(method.getSrcDesc())) {
					continue;
				}

				return method.getName(input.targetNamespace());
			}
		}

		for (MappingTreeView.FieldMappingView field : classMapping.getFields()) {
			if (member.equals(field.getName(input.sourceNamespace()))) {
				return field.getName(input.targetNamespace());
			}
		}

		// 无描述符的方法规则（如整体放开某个方法名）也要能翻译
		if (descriptor == null) {
			for (MappingTreeView.MethodMappingView method : classMapping.getMethods()) {
				if (member.equals(method.getName(input.sourceNamespace()))) {
					return method.getName(input.targetNamespace());
				}
			}
		}

		return null;
	}

	/**
	 * {@return 把描述符里的类型名从「AT 所在命名空间」翻译到指定命名空间}
	 *
	 * <p>AT 描述符里的类型名与规则同级，都是 SRG 命名空间（1.12.2 下类名等同 MCP 名）。
	 * 映射树里的描述符则写在源命名空间（official）。两者要对上，必须先统一命名空间。
	 */
	private static String translateDescriptorTo(String descriptor, Input input, String targetNamespace) {
		final StringBuilder result = new StringBuilder(descriptor.length());
		int i = 0;

		while (i < descriptor.length()) {
			final char c = descriptor.charAt(i);

			if (c != 'L') {
				result.append(c);
				i++;
				continue;
			}

			final int end = descriptor.indexOf(';', i);

			if (end < 0) {
				// 不合法的描述符：原样保留，交给 AT 工具自己报错
				result.append(descriptor, i, descriptor.length());
				break;
			}

			final String type = descriptor.substring(i + 1, end);
			// AT 文件里的描述符用点号写类名（Lnet.minecraft.block.Block;），
			// 而映射树一律用斜杠，两种写法都要认
			final String mapped = mapClassBetween(type.replace('.', '/'), input.sourceNamespace(), targetNamespace, input);
			// 输出沿用输入的分隔符风格，避免把 AT 改写成工具不认的形态
			final String rendered = mapped != null ? (type.indexOf('.') >= 0 ? mapped.replace('/', '.') : mapped) : type;
			result.append('L').append(rendered).append(';');
			i = end + 1;
		}

		return result.toString();
	}

	/** {@return 在任意两个命名空间之间翻译类名；找不到时返回 {@code null}} */
	private static @Nullable String mapClassBetween(String className, String from, String to, Input input) {
		final int fromId = namespaceId(input, from);

		if (fromId == MappingTreeView.NULL_NAMESPACE_ID) {
			return null;
		}

		final MappingTreeView.ClassMappingView classMapping = input.mappings().getClass(className, fromId);
		return classMapping == null ? null : classMapping.getName(to);
	}

	/** {@return 去掉 {@code #} 注释后的内容}；AT 的注释可以出现在行尾 */
	private static String stripComment(String line) {
		final int hash = line.indexOf('#');
		return hash >= 0 ? line.substring(0, hash) : line;
	}

	/** AT 规则翻译失败；{@code failures} 汇总全部失败行，而不是只报第一条. */
	public static final class AccessTransformerTranslationException extends RuntimeException {
		private final List<String> failures;

		AccessTransformerTranslationException(List<String> failures) {
			super(buildMessage(failures));
			this.failures = List.copyOf(failures);
		}

		public List<String> getFailures() {
			return failures;
		}

		private static String buildMessage(List<String> failures) {
			final StringBuilder message = new StringBuilder();
			message.append("有 ").append(failures.size()).append(" 条 access transformer 规则无法翻译到目标命名空间：");
			failures.forEach(failure -> message.append(System.lineSeparator()).append("  - ").append(failure));
			message.append(System.lineSeparator())
					.append("AT 规则若被静默丢弃，症状会推迟到运行期的 IllegalAccessError，因此这里直接失败。");
			return message.toString();
		}
	}
}
