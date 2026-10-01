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

package net.fabricmc.loom.spec;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;

/**
 * 环境规格：配置期唯一被读的「小数据」.
 *
 * <p>本类型是把环境供给从配置期搬到执行期的**接缝**。当前配置期存在的环是：
 *
 * <pre>
 *   配置本项目 → 需要 mapped MC jar（mod 重映射的 classpath）
 *              → jar 需任务产出 → 任务需所有项目配置完成 → 回到起点
 * </pre>
 *
 * <p>破环的办法不是「让配置期更快地生产 jar」，而是把「依赖图需要的」与「产物需要的」分开：
 * 前者只需要**配方**，后者才是重活。本类型承载前者。
 *
 * <h2>为什么 mappings 是配方而不是产物</h2>
 * {@link #mappings} 描述「用哪套基础映射、什么迁移规则、目标命名空间」，而不是算好的映射文件。
 * 所以它不需要任何产物存在就能构造——这正是环被切断的地方。
 *
 * <h2>不可序列化的部分</h2>
 * {@code versionMeta} 是已解析的版本元数据对象。它由版本清单与 {@code minecraft_info.json}
 * 派生，二者都通过 {@link SpecStore} 按内容身份缓存，因此重复构造的成本是一次 JSON 反序列化，
 * 而不是「网络 + 全文解析」。这也是本类型可以安全地被配置缓存序列化的前提。
 *
 * <h2>边界</h2>
 * 本类型**不含**产物路径、不含任务引用、不含 {@code Project}。产物由 L3 流水线的任务声明，
 * 通过 {@code Provider} 接线给下游，配置期不触碰文件系统。
 */
public record EnvironmentSpec(
		String minecraftVersion,
		MinecraftVersionMeta versionMeta,
		LoaderSpec loader,
		MappingDefinition mappings,
		List<PipelineStep> pipeline
) implements Serializable {
	public EnvironmentSpec {
		Objects.requireNonNull(minecraftVersion, "minecraftVersion");
		Objects.requireNonNull(versionMeta, "versionMeta");
		Objects.requireNonNull(loader, "loader");
		Objects.requireNonNull(mappings, "mappings");
		pipeline = List.copyOf(pipeline);
	}

	/**
	 * 加载器规格.
	 *
	 * @param platform 平台标识（fabric / forge / neoforge / quilt）
	 * @param version 加载器版本；无加载器概念的平台为空
	 */
	public record LoaderSpec(String platform, @Nullable String version) implements Serializable {
		public LoaderSpec {
			Objects.requireNonNull(platform, "platform");
		}
	}

	/**
	 * 映射配方：**怎么算**，而不是**算出来的东西**.
	 *
	 * @param baseCoordinates 基础映射的坐标（用户声明，例如 {@code net.fabricmc:yarn:1.20.1+build.10:v2}）
	 * @param targetNamespace 产物要落在哪个命名空间（{@code named} / {@code intermediary} / {@code srg} …）
	 * @param migrateForPlatform 是否需要在基础映射之上做平台特有的迁移
	 *                          （Forge 需要：迁移依赖补丁后的中间产物，属于 L3 的独立节点）
	 */
	public record MappingDefinition(
			@Nullable String baseCoordinates,
			String targetNamespace,
			boolean migrateForPlatform
	) implements Serializable {
		public MappingDefinition {
			Objects.requireNonNull(targetNamespace, "targetNamespace");
		}
	}

	/**
	 * 流水线步骤：平台差异的表达处.
	 *
	 * <p>把「Fabric 直接 remap」与「Forge 先补丁、再迁移映射、再 remap」的差异收敛成
	 * 一份可序列化的步骤列表，而不是散落在各 provider 的 {@code if (isForgeLike())} 里。
	 */
	public enum PipelineStep {
		/** 下载 vanilla 制品. */
		DOWNLOAD_VANILLA,
		/** 合并客户端与服务端 jar（仅需要合并的版本配置）. */
		MERGE_MINECRAFT,
		/** 对 vanilla 应用加载器补丁（Forge / NeoForge）. */
		APPLY_LOADER_PATCHES,
		/** 在基础映射之上做平台特有的映射迁移（仅 Forge 系，且依赖上一步的产物）. */
		MIGRATE_MAPPINGS,
		/** 重映射到目标命名空间. */
		REMAP_MINECRAFT,
		/** 应用项目级处理（AW / AT / 接口注入）. */
		PROCESS_PROJECT_JAR,
	}

	/**
	 * {@return 该平台是否需要在重映射之前先做映射迁移}.
	 *
	 * <p>Forge 系的最终映射依赖「打补丁后的中间产物」，因此迁移是一个独立的流水线节点，
	 * 它的输入是 {@link PipelineStep#APPLY_LOADER_PATCHES} 的输出——这是 DAG，不是环。
	 */
	public boolean needsMappingMigration() {
		return pipeline.contains(PipelineStep.MIGRATE_MAPPINGS);
	}

	/**
	 * {@return 流水线中最后一个产出可被依赖的 jar 的步骤}.
	 *
	 * <p>供 L1 接线时判断「应当把哪个步骤的输出挂进 configuration」。
	 */
	public Optional<PipelineStep> finalJarStep() {
		if (pipeline.contains(PipelineStep.PROCESS_PROJECT_JAR)) {
			return Optional.of(PipelineStep.PROCESS_PROJECT_JAR);
		}

		if (pipeline.contains(PipelineStep.REMAP_MINECRAFT)) {
			return Optional.of(PipelineStep.REMAP_MINECRAFT);
		}

		return Optional.empty();
	}
}
