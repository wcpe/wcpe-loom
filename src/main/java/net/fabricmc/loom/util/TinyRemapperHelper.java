/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2023 FabricMC
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

package net.fabricmc.loom.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import dev.architectury.loom.forge.InnerClassRemapper;
import dev.architectury.loom.mappings.MappingException;
import dev.architectury.loom.mappings.MappingOption;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * Contains shortcuts to create tiny remappers using the mappings accessibly to the project.
 */
public final class TinyRemapperHelper {
	public static final Map<String, String> JSR_TO_JETBRAINS = Map.of(
				"javax/annotation/Nullable", "org/jetbrains/annotations/Nullable",
				"javax/annotation/Nonnull", "org/jetbrains/annotations/NotNull",
				"javax/annotation/concurrent/Immutable", "org/jetbrains/annotations/Unmodifiable"
			);

	/**
	 * Matches the new local variable naming format introduced in 21w37a.
	 */
	private static final Pattern MC_LV_PATTERN = Pattern.compile("\\$\\$\\d+");

	private TinyRemapperHelper() {
	}

	public static TinyRemapper getTinyRemapper(Project project, ServiceFactory serviceFactory, String fromM, String toM) throws IOException {
		return getTinyRemapper(project, serviceFactory, fromM, toM, false, true, (builder) -> { }, Set.of());
	}

	/**
	 * 构建重映射器，全部输入由调用方备妥.
	 *
	 * <p>不依赖 {@link Project}，这是把重映射算法从「依赖装满运行状态的 {@code LoomGradleExtension}」改造为
	 * 「输入文件 + 显式参数 → 结果」的落点。任务化之后，重映射可以在执行阶段
	 * 用已经解析好的参数构造，而不必在配置阶段触碰项目模型。
	 *
	 * @param mappingTree 已解析好的映射树（不经过 Project 获取）
	 * @param isForgeLike 是否 Forge/NeoForge 系（影响冲突忽略策略与内部类映射）
	 * @param knownIndyBsms 已知的 indy BSM 集合
	 */
	public static TinyRemapper getTinyRemapper(MemoryMappingTree mappingTree, String fromM, String toM,
			boolean fixRecords, boolean validateTargetNamespace,
			Consumer<TinyRemapper.Builder> builderConsumer, Set<String> fromClassNames,
			boolean isForgeLike, Set<String> knownIndyBsms) throws IOException {
		int intermediaryNsId = mappingTree.getNamespaceId(MappingsNamespace.INTERMEDIARY.toString());
		int fromNsId = mappingTree.getNamespaceId(fromM);

		TinyRemapper.Builder builder = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE)
				.ignoreConflicts(isForgeLike)
				.threads(Runtime.getRuntime().availableProcessors())
				.withMappings(create(mappingTree, fromM, toM, true, validateTargetNamespace))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.invalidLvNamePattern(MC_LV_PATTERN)
				.inferNameFromSameLvIndex(true)
				.withKnownIndyBsm(knownIndyBsms)
				.extraPreApplyVisitor((cls, next) -> {
					if (fixRecords && !cls.isRecord() && "java/lang/Record".equals(cls.getSuperName())) {
						return new RecordComponentFixVisitor(next, mappingTree, fromNsId, intermediaryNsId);
					}

					return next;
				});

		if (isForgeLike) {
			if (!fromClassNames.isEmpty()) {
				builder.withMappings(InnerClassRemapper.of(fromClassNames, mappingTree, fromM, toM));
			}
		} else {
			builder.withMappings(out -> TinyRemapperHelper.JSR_TO_JETBRAINS.forEach(out::acceptClass));
		}

		builderConsumer.accept(builder);
		return builder.build();
	}

	public static TinyRemapper getTinyRemapper(Project project, ServiceFactory serviceFactory, String fromM, String toM, boolean fixRecords, boolean validateTargetNamespace, Consumer<TinyRemapper.Builder> builderConsumer, Set<String> fromClassNames) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MappingOption mappingOption = MappingOption.forPlatform(extension);
		MemoryMappingTree mappingTree = extension.getMappingConfiguration().getMappingsService(project, serviceFactory, mappingOption).getMappingTree();

		// 委托给不依赖 Project 的重载：项目模型只在这里被读取一次，
		// 之后的重映射构造完全由显式参数驱动。
		return getTinyRemapper(mappingTree, fromM, toM, fixRecords, validateTargetNamespace,
				builderConsumer, fromClassNames, extension.isForgeLike(), extension.getKnownIndyBsms().get());
	}

	private static IMappingProvider.Member memberOf(String className, String memberName, String descriptor) {
		return new IMappingProvider.Member(className, memberName, descriptor);
	}

	public static IMappingProvider create(Path mappings, String from, String to, boolean remapLocalVariables) throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(mappings, mappingTree);
		return create(mappingTree, from, to, remapLocalVariables, true);
	}

	public static IMappingProvider create(MappingTree mappings, String from, String to, boolean remapLocalVariables, boolean validateTargetNamespace) {
		return (acceptor) -> {
			final int fromId = mappings.getNamespaceId(from);
			final int toId = mappings.getNamespaceId(to);

			if (validateTargetNamespace && toId == MappingTreeView.NULL_NAMESPACE_ID) {
				throw new MappingException(
						"Trying to remap from '%s' (id: %d) to unknown namespace '%s'. Available namespaces: [%s -> %s]"
								.formatted(from, fromId, to, mappings.getSrcNamespace(), String.join(", ", mappings.getDstNamespaces()))
				);
			}

			for (MappingTree.ClassMapping classDef : mappings.getClasses()) {
				String className = classDef.getName(fromId);

				if (className == null) {
					continue;
				}

				String dstClassName = classDef.getName(toId);

				if (dstClassName == null) {
					// Unsure if this is correct, should be better than crashing tho.
					dstClassName = className;
				}

				acceptor.acceptClass(className, dstClassName);

				for (MappingTree.FieldMapping field : classDef.getFields()) {
					String fieldName = field.getName(fromId);

					if (fieldName == null) {
						continue;
					}

					String dstFieldName = field.getName(toId);

					if (dstFieldName == null) {
						dstFieldName = fieldName;
					}

					acceptor.acceptField(memberOf(className, fieldName, field.getDesc(fromId)), dstFieldName);
				}

				for (MappingTree.MethodMapping method : classDef.getMethods()) {
					String methodName = method.getName(fromId);

					if (methodName == null) {
						continue;
					}

					String dstMethodName = method.getName(toId);

					if (dstMethodName == null) {
						dstMethodName = methodName;
					}

					IMappingProvider.Member methodIdentifier = memberOf(className, methodName, method.getDesc(fromId));
					acceptor.acceptMethod(methodIdentifier, dstMethodName);

					if (remapLocalVariables) {
						for (MappingTree.MethodArgMapping parameter : method.getArgs()) {
							String name = parameter.getName(toId);

							if (name == null) {
								continue;
							}

							acceptor.acceptMethodArg(methodIdentifier, parameter.getLvIndex(), name);
						}

						for (MappingTree.MethodVarMapping localVariable : method.getVars()) {
							acceptor.acceptMethodVar(methodIdentifier, localVariable.getLvIndex(),
									localVariable.getStartOpIdx(), localVariable.getLvtRowIndex(),
									localVariable.getName(toId));
						}
					}
				}
			}
		};
	}
}
