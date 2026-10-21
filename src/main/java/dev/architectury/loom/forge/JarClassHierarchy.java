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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;

/**
 * 从 jar 里读出类继承关系，供 {@link AccessTransformerRuleTranslator} 做继承回退.
 *
 * <p>AT 允许把规则写在子类上、而成员声明在父类；翻译时必须沿继承链找到声明类。
 * 映射表只按「声明类」收录成员，这个索引补的正是映射表没有的那部分信息。
 *
 * <h2>为什么从 jar 读而不是从映射读</h2>
 * tiny 映射不含父类关系，只含类与成员的名字对应。继承关系必须以字节码为准，
 * 因此这里直接读待处理 jar 的 class 头部（只取 this/super/interfaces，不碰指令体）。
 *
 * <p>名称一律保持 jar 里的原始形态（notch 名、斜杠分隔），与映射树的 official 列一致，
 * 命名空间换算交给翻译器，本类不做任何名称翻译。
 */
public final class JarClassHierarchy implements AccessTransformerRuleTranslator.ClassHierarchy {
	private static final List<String> NO_PARENTS = List.of();

	/** 类名（notch、斜杠）→ 直接父类型（父类在前，接口在后）. */
	private final Map<String, List<String>> parents;

	private JarClassHierarchy(Map<String, List<String>> parents) {
		this.parents = parents;
	}

	/**
	 * 读取若干 jar 的继承关系.
	 *
	 * <p>多个 jar 合并成一张表：MC 的 client/server/merged jar 与 forge jar 会分头提供一部分类，
	 * 继承链可能跨越它们。同名类以先出现者为准（与「同一份产物只该有一个定义」一致）。
	 *
	 * @param jars 待扫描的 jar；不存在或无法打开的直接跳过
	 * @return 继承关系索引
	 * @throws IOException 读 jar 失败（文件存在但内容损坏）
	 */
	public static JarClassHierarchy read(Iterable<Path> jars) throws IOException {
		final Map<String, List<String>> parents = new HashMap<>();

		for (Path jar : jars) {
			if (jar == null || !java.nio.file.Files.isRegularFile(jar)) {
				continue;
			}

			try (ZipFile zip = new ZipFile(jar.toFile())) {
				final var entries = zip.entries();

				while (entries.hasMoreElements()) {
					final ZipEntry entry = entries.nextElement();
					final String name = entry.getName();

					if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
						continue;
					}

					try (InputStream in = zip.getInputStream(entry)) {
						readClass(in, parents);
					} catch (RuntimeException e) {
						// 单个类的头部解析失败不该让整个索引失败：该类最多没有父类型
					}
				}
			}
		}

		return new JarClassHierarchy(parents);
	}

	/** 读一个 class 的头部，把它的父类型记进表. */
	private static void readClass(InputStream in, Map<String, List<String>> parents) throws IOException {
		final ClassReader reader = new ClassReader(in);
		final String self = reader.getClassName();
		final String superName = reader.getSuperName();
		final String[] interfaces = reader.getInterfaces();
		final List<String> result = new ArrayList<>(1 + interfaces.length);

		if (superName != null) {
			result.add(superName);
		}

		Collections.addAll(result, interfaces);
		parents.putIfAbsent(self, List.copyOf(result));
	}

	@Override
	public List<String> parentsOf(String className) {
		return parents.getOrDefault(className, NO_PARENTS);
	}
}
