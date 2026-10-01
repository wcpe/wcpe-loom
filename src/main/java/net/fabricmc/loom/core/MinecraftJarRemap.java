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

package net.fabricmc.loom.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.architectury.loom.forge.RemapObjectHolderVisitor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.minecraft.AnnotationsApplyVisitor;
import net.fabricmc.loom.configuration.providers.minecraft.SignatureFixerApplyVisitor;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * L4 计算核心：把一个 jar 从源命名空间重映射到目标命名空间.
 *
 * <p>这是从 {@code AbstractMappedMinecraftProvider.remapJar} 抽出的计算部分。抽取的判据是
 * 「是否触碰项目模型」：本类**不认识** {@code Project}、{@code LoomGradleExtension}
 * 或任何 Gradle 类型，全部输入由调用方备妥。
 *
 * <p>调用方仍需负责两件依赖项目模型的事，它们刻意留在上层：
 * <ul>
 *   <li>{@code AnnotationsData.getRemappedAnnotations} 与
 *       {@code SignatureFixerApplyVisitor.getRemappedSignatures} 需要 mappings service，
 *       而 service 的构造依赖 {@code Project}。它们的结果作为参数传入。</li>
 *   <li>产物落位后的 maven pom 写出属于仓库维护，不属于重映射计算。</li>
 * </ul>
 *
 * <h2>为什么参数这么多是合理的</h2>
 * 这些参数原本藏在 {@code LoomGradleExtension} 的各个字段里，调用方需要「知道去哪拿」。
 * 显式列出后，它们变成可序列化、可在执行期装配的值——这正是本操作能被任务包裹、
 * 从而脱离配置期执行的前提。参数多不是设计缺陷，而是把隐式依赖显式化的结果。
 *
 * <h2>原子性契约（必须保持）</h2>
 * 输出经由 {@link AtomicFiles#publish} 落位：先写同目录唯一临时文件，全部加工（含
 * Forge/NeoForge 的 object holder 改写）都在临时文件上完成，最后原子替换。
 * 发布出去的必须是终态——否则共享仓库里的 jar 会有一段「已落位但未加工完」的中间态
 * 被读方看到，而内容校验只能发现截断、发现不了「完整但没加工完」。
 */
public final class MinecraftJarRemap {
	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftJarRemap.class);

	private MinecraftJarRemap() {
	}

	/**
	 * 执行一次 jar 重映射.
	 *
	 * @param options 全部输入，见 {@link Options}
	 * @throws IOException 读写失败
	 */
	public static void run(Options options) throws IOException {
		final Set<String> innerClassNames = options.innerClassNames();
		final AnnotationsData annotations = options.annotations();
		final Map<String, String> signatures = options.signatures();

		final Consumer<TinyRemapper.Builder> configure = builder -> {
			if (annotations != null) {
				builder.extraPostApplyVisitor(new AnnotationsApplyVisitor(annotations));
			}

			builder.extraPostApplyVisitor(new SignatureFixerApplyVisitor(signatures));

			if (options.injectMixinExtension()) {
				builder.extension(new MixinExtension(inputTag -> true));
			}

			if (options.extraRemapperConfig() != null) {
				options.extraRemapperConfig().accept(builder);
			}
		};

		// remapper 可由调用方提供（此时其配置已包含注解/签名/扩展，本类的 configure 不再适用），
		// 否则由本类按显式参数自行构建。前者服务于「由 TinyRemapperService 统一装配」的场景，
		// 后者服务于纯计算场景。
		final boolean externallyBuilt = options.prebuiltRemapper() != null;

		if (!externallyBuilt && options.mappings() == null) {
			throw new IllegalArgumentException("mappings is required when no prebuilt remapper is supplied");
		}

		final TinyRemapper remapper = externallyBuilt
				? options.prebuiltRemapper()
				: TinyRemapperHelper.getTinyRemapper(
						options.mappings(),
						options.fromNamespace(),
						options.toNamespace(),
						options.fixRecords(),
						options.validateTargetNamespace(),
						configure,
						innerClassNames,
						options.isForgeLike(),
						options.knownIndyBsms()
				);

		try {
			AtomicFiles.publish(options.outputJar(), tmpJar -> {
				try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(tmpJar).build()) {
					outputConsumer.addNonClassFiles(options.inputJar());

					for (Path path : options.remapClasspath()) {
						remapper.readClassPath(path);
					}

					remapper.readInputs(options.inputJar());
					remapper.apply(outputConsumer);
				}

				// object holder 的类名字符串同样要在落位前改写，理由见类注释的原子性契约
				if (options.objectHolderClassName() != null
						&& options.objectHolderSourceNamespace() != null
						&& options.objectHolderTargetNamespace() != null) {
					RemapObjectHolderVisitor.remapObjectHolder(
							tmpJar,
							options.objectHolderClassName(),
							options.mappings(),
							options.objectHolderSourceNamespace(),
							options.objectHolderTargetNamespace()
					);
				}
			});
		} catch (Exception e) {
			LOGGER.error("Failed to remap {} to namespace {}", options.inputJar(), options.toNamespace(), e);
			throw new RuntimeException("Failed to remap JAR " + options.inputJar(), e);
		} finally {
			if (!externallyBuilt) {
				remapper.finish();
			}
		}
	}

	/**
	 * 一次重映射所需的全部输入.
	 *
	 * @param prebuiltRemapper 已装配好的重映射器；为空时由本类按其余参数自行构建
	 * @param inputJar 待重映射的 jar
	 * @param outputJar 产物位置
	 * @param remapClasspath 重映射 classpath（不含 inputJar 本身）
	 * @param mappings 已解析的映射树；仅自建 remapper 或需要 object holder 改写时必需
	 * @param fromNamespace 源命名空间
	 * @param toNamespace 目标命名空间
	 * @param isForgeLike 是否 Forge/NeoForge 系（影响冲突忽略策略与内部类映射）
	 * @param injectMixinExtension 是否注入 mixin 扩展（仅 NeoForge）
	 * @param fixRecords 是否修复 record 组件（Java 16+ 目标需要）
	 * @param validateTargetNamespace 是否校验目标命名空间存在
	 * @param innerClassNames Forge 系内部类重映射所需的类名集合
	 * @param knownIndyBsms 已知的 indy BSM 集合
	 * @param annotations 已算好的注解重映射数据；无则为空
	 * @param signatures 已算好的签名修复表
	 * @param objectHolderClassName 需要做 object holder 改写的类名；不需要则为空
	 * @param objectHolderSourceNamespace object holder 改写所用的源命名空间；不需要则为空
	 * @param objectHolderTargetNamespace object holder 改写所用的目标命名空间；不需要则为空
	 * @param extraRemapperConfig 子类追加的 remapper 配置（例如 split jar 的策略）
	 */
	public record Options(
			@Nullable TinyRemapper prebuiltRemapper,
			Path inputJar,
			Path outputJar,
			List<Path> remapClasspath,
			@Nullable MemoryMappingTree mappings,
			String fromNamespace,
			String toNamespace,
			boolean isForgeLike,
			boolean injectMixinExtension,
			boolean fixRecords,
			boolean validateTargetNamespace,
			Set<String> innerClassNames,
			Set<String> knownIndyBsms,
			@Nullable AnnotationsData annotations,
			Map<String, String> signatures,
			@Nullable String objectHolderClassName,
			@Nullable String objectHolderSourceNamespace,
			@Nullable String objectHolderTargetNamespace,
			@Nullable Consumer<TinyRemapper.Builder> extraRemapperConfig
	) {
		public Options {
			remapClasspath = List.copyOf(remapClasspath);
			innerClassNames = Set.copyOf(innerClassNames);
			knownIndyBsms = Set.copyOf(knownIndyBsms);
			signatures = Map.copyOf(signatures);
		}
	}
}
