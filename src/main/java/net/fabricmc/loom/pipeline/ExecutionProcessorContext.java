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

package net.fabricmc.loom.pipeline;

import java.util.Objects;
import java.util.function.Supplier;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.Lazy;
import net.fabricmc.loom.util.LazyCloseable;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * 执行期可用的 {@link ProcessorContext} 实现.
 *
 * <p>{@code ProcessorContextImpl} 只能在配置期使用：它持有 {@code ConfigContext}，并通过
 * {@code LoomGradleExtension} 读项目模型。本类把这些依赖整体换成两类输入——**可被配置缓存
 * 序列化的值**与**执行期由 {@link ServiceFactory} 取得的服务**，从而让 jar 处理器能在 Gradle 任务
 * 里运行。
 *
 * <h2>各项输入的来源</h2>
 * <table>
 *   <caption>输入与来源</caption>
 *   <tr><th>接口方法</th><th>本类输入</th><th>配置期对应物</th></tr>
 *   <tr><td>{@link #isSplit()}</td><td>{@link JarConfigurationKind}</td>
 *       <td>是否 {@code MinecraftJarConfiguration.SPLIT}</td></tr>
 *   <tr><td>{@link #isMerged()} / {@link #includesClient()} / {@link #includesServer()}</td>
 *       <td>{@link MinecraftJar.Type}</td><td>{@code MinecraftJar} 实例上的同名方法</td></tr>
 *   <tr><td>{@link #getMappings()}</td><td>{@link TinyMappingsService.Options}</td>
 *       <td>{@code MappingConfiguration.getMappingsService(...)}</td></tr>
 *   <tr><td>{@link #createRemapper(MappingsNamespace, MappingsNamespace)}</td>
 *       <td>{@link TinyRemapperService.Options}</td>
 *       <td>{@code TinyRemapperHelper.getTinyRemapper(project, ...)}</td></tr>
 *   <tr><td>{@link #disableObfuscation()} / {@link #getProductionNamespace()} /
 *       {@link #getIntermediaryNamespace()}</td>
 *       <td>布尔值 / 两个枚举</td><td>{@code LoomGradleExtension} 上的同名取值</td></tr>
 * </table>
 *
 * <h2>与原实现的语义差异</h2>
 * <ol>
 *   <li>{@code ProcessorContextImpl.createRemapper} 返回的 {@link LazyCloseable} 在 {@code close()} 时
 *       调用 {@code TinyRemapper.finish()}；本类返回的 remapper 归 {@link TinyRemapperService} 所有，
 *       由该服务在自身 {@code close()} 时 finish，故本类的 {@code close()} 不再重复 finish。
 *       这与 {@code MinecraftJarRemap} 对「外部装配的 remapper」的处理一致。</li>
 *   <li>{@code createRemapper} 只能提供任务已经装配好的那一对命名空间（见该方法说明）。</li>
 * </ol>
 */
public final class ExecutionProcessorContext implements ProcessorContext {
	private final ServiceFactory serviceFactory;
	private final JarConfigurationKind jarConfiguration;
	private final MinecraftJar.Type jarType;
	private final boolean disableObfuscation;
	private final MappingsNamespace productionNamespace;
	private final MappingsNamespace intermediaryNamespace;
	private final TinyMappingsService.Options mappingsOptions;
	private final TinyRemapperService.Options remapperOptions;
	private final Supplier<MemoryMappingTree> mappings;

	/**
	 * @param serviceFactory 执行期的作用域服务工厂，通常在一次任务动作内创建并随任务结束关闭
	 * @param jarConfiguration 本次构建的 jar 配置身份
	 * @param jarType 本上下文服务的那个 jar 的类型
	 * @param disableObfuscation 是否禁用混淆
	 * @param productionNamespace 生产命名空间
	 * @param intermediaryNamespace 平台默认命名空间，例如 Forge 的 {@code srg}
	 * @param mappingsOptions 取映射树所需的映射服务配置
	 * @param remapperOptions 取重映射器所需的重映射服务配置
	 */
	public ExecutionProcessorContext(ServiceFactory serviceFactory, JarConfigurationKind jarConfiguration,
			MinecraftJar.Type jarType, boolean disableObfuscation, MappingsNamespace productionNamespace,
			MappingsNamespace intermediaryNamespace, TinyMappingsService.Options mappingsOptions,
			TinyRemapperService.Options remapperOptions) {
		this.serviceFactory = Objects.requireNonNull(serviceFactory, "serviceFactory");
		this.jarConfiguration = Objects.requireNonNull(jarConfiguration, "jarConfiguration");
		this.jarType = Objects.requireNonNull(jarType, "jarType");
		this.disableObfuscation = disableObfuscation;
		this.productionNamespace = Objects.requireNonNull(productionNamespace, "productionNamespace");
		this.intermediaryNamespace = Objects.requireNonNull(intermediaryNamespace, "intermediaryNamespace");
		this.mappingsOptions = Objects.requireNonNull(mappingsOptions, "mappingsOptions");
		this.remapperOptions = Objects.requireNonNull(remapperOptions, "remapperOptions");
		// 映射树只在真正被读时才解析；处理器可能整轮都不需要它
		this.mappings = Lazy.of(() -> getMappingsService().getMappingTree());
	}

	private TinyMappingsService getMappingsService() {
		return serviceFactory.get(mappingsOptions);
	}

	private TinyRemapperService getRemapperService() {
		return serviceFactory.get(remapperOptions);
	}

	@Override
	public boolean isSplit() {
		return jarConfiguration == JarConfigurationKind.SPLIT;
	}

	@Override
	public boolean isMerged() {
		// 只有 merged 配置会产出 MinecraftJar.Merged
		return jarType == MinecraftJar.Type.MERGED;
	}

	@Override
	public boolean includesClient() {
		return jarType == MinecraftJar.Type.MERGED || jarType == MinecraftJar.Type.CLIENT || jarType == MinecraftJar.Type.CLIENT_ONLY;
	}

	@Override
	public boolean includesServer() {
		return jarType == MinecraftJar.Type.MERGED || jarType == MinecraftJar.Type.SERVER || jarType == MinecraftJar.Type.COMMON;
	}

	@Override
	public MemoryMappingTree getMappings() {
		return mappings.get();
	}

	@Override
	public boolean disableObfuscation() {
		return disableObfuscation;
	}

	@Override
	public MappingsNamespace getProductionNamespace() {
		return productionNamespace;
	}

	@Override
	public MappingsNamespace getIntermediaryNamespace() {
		return intermediaryNamespace;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>执行期的重映射器由 {@link TinyRemapperService} 统一装配，而它的 from/to 是任务输入的一部分，
	 * 不能被调用方任意改写。因此只有当请求的命名空间对与 {@link TinyRemapperService.Options} 声明的
	 * 那一对一致时才返回 remapper，否则抛出异常——现有处理器请求的都是
	 * {@code productionNamespace -> named}，任务按这一对装配即可。
	 */
	@Override
	public LazyCloseable<TinyRemapper> createRemapper(MappingsNamespace from, MappingsNamespace to) {
		final String expectedFrom = remapperOptions.getFrom().get();
		final String expectedTo = remapperOptions.getTo().get();
		final String requestedFrom = from.toString();
		final String requestedTo = to.toString();

		if (!expectedFrom.equals(requestedFrom) || !expectedTo.equals(requestedTo)) {
			throw new IllegalStateException("执行期 ProcessorContext 只装配了 %s -> %s 的 remapper，但被请求 %s -> %s".formatted(expectedFrom, expectedTo, requestedFrom, requestedTo));
		}

		// 不在此处 finish：remapper 的生命周期归 TinyRemapperService，它已在构造时读完 classpath，
		// 并会在自己关闭时 finish。重复 finish 会与服务的 close() 冲突。
		return new LazyCloseable<>(() -> getRemapperService().getTinyRemapperForInputs(), remapper -> { });
	}

	/**
	 * {@link MinecraftJarConfiguration} 的可序列化身份.
	 *
	 * <p>{@code MinecraftJarConfiguration} 本身进不了任务输入：它是持有 6 个工厂方法引用的 record，
	 * 其 {@code create*Provider} 方法都要 {@code Project}/{@code ConfigContext} 才能工作，只在配置期成立。
	 * 任务只需要记住「是哪一种配置」，执行期用它回答 {@link ProcessorContext#isSplit()}。
	 *
	 * <p>注意 {@code MERGED} 与 {@code LEGACY_MERGED} 的 jar 布局完全相同（都是单个 merged jar），
	 * 二者只在 provider 实现上不同；{@code SPLIT} 的两个 jar 也分别与 {@code SERVER_ONLY}、
	 * {@code CLIENT_ONLY} 的 jar 完全相同，只有配置身份能区分它们。
	 */
	public enum JarConfigurationKind {
		MERGED,

		LEGACY_MERGED,

		SERVER_ONLY,

		CLIENT_ONLY,

		SPLIT;

		/**
		 * 由配置期的配置常量得到可序列化身份.
		 *
		 * @param configuration 配置期的 jar 配置，须是 {@code MinecraftJarConfiguration} 的五个常量之一
		 * @return 对应的身份
		 * @throws IllegalArgumentException 传入的不是已知常量
		 */
		public static JarConfigurationKind of(MinecraftJarConfiguration<?, ?, ?> configuration) {
			Objects.requireNonNull(configuration, "configuration");

			if (configuration == MinecraftJarConfiguration.MERGED) {
				return MERGED;
			}

			if (configuration == MinecraftJarConfiguration.LEGACY_MERGED) {
				return LEGACY_MERGED;
			}

			if (configuration == MinecraftJarConfiguration.SERVER_ONLY) {
				return SERVER_ONLY;
			}

			if (configuration == MinecraftJarConfiguration.CLIENT_ONLY) {
				return CLIENT_ONLY;
			}

			if (configuration == MinecraftJarConfiguration.SPLIT) {
				return SPLIT;
			}

			throw new IllegalArgumentException("未知的 MinecraftJarConfiguration: " + configuration);
		}
	}
}
