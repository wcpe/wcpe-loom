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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MinecraftJarProcessor;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

/**
 * L3 流水线：对「命名映射后的 Minecraft jar」执行 jar processor 链.
 *
 * <p>它取代 {@code ProcessedNamedMinecraftProvider.provide()} 里那段配置期同步执行的
 * 「存在性检查 → 取跨进程锁 → copyToMaven 落位 → processJar」：是否需要重跑交给 Gradle 的
 * up-to-date 判定与构建缓存，跨进程互斥交给任务图。与 {@link RemapMinecraftTask} 串联——
 * 后者的产物是本任务的输入，故两者必须同批接线。
 *
 * <p>链的执行语义与配置期逐项对齐：先原子落位（对应 {@code LocalMavenHelper.copyToMaven}），
 * 再由每个 processor 就地对产物路径做改写（processor 的契约就是「就地改 jar」），
 * 失败时与 {@code MinecraftJarProcessorManager.processJar} 一样尽力删除半成品。
 *
 * <h2>链的输入设计</h2>
 * 链是有序的，且顺序变了结果就变，因此三个与链有关的输入都是有序 {@code ListProperty}
 * （Gradle 对列表输入的指纹含顺序，故「换序」会正确地触发重跑），都不能退化为 {@code SetProperty}：
 * <ul>
 *   <li>{@link #getProcessorDescriptors()}：{@code @Input}。执行期凭它重建 processor 实例；
 *       {@code ProcessorDescriptor} 按接口契约是 {@code Serializable} 的纯值，可以直接作为值输入。</li>
 *   <li>{@link #getProcessorSpecs()}：{@code @Internal}。仅供执行期重建后的 processor 使用，不参与指纹。</li>
 *   <li>{@link #getSpecFingerprints()}：{@code @Input}。spec 的指纹，与链一一对应，参与指纹。</li>
 * </ul>
 *
 * <h3>为什么 spec 不是 {@code @Input}，而是 {@code @Internal} + 指纹</h3>
 * {@code MinecraftJarProcessor.Spec} 不继承 {@code Serializable}，因此 spec 不能作为值输入：
 * 实测（Gradle 9.5，{@code org.gradle.configuration-cache=true}）把一个 record 实现的 spec 放进
 * {@code @Input} 会直接失败——{@code Cannot fingerprint input property 'x': value '[Spec[...]]' cannot be serialized}。
 * spec 的身份在 loom 既有协议里就是 {@code spec.hashCode()}（{@code MinecraftJarProcessorManager.getCacheValue()}
 * 用它组成缓存值，产物目录名与 {@code getJarHash()} 均由该缓存值派生），故本任务沿用同一身份：
 * 对象本体用 {@code @Internal} 带到执行期，指纹用 {@code @Input} 参与判定。
 * 代价如实记下：{@code hashCode} 碰撞时任务不会重跑——这与既有协议同源（既有协议同样以该哈希命名产物），
 * 不是本任务新引入的风险；但 {@code hashCode} 实现不稳定（例如用身份哈希）的类型会让任务每次都重跑。
 *
 * <h3>为什么链不能用 {@code @Nested}</h3>
 * 实测结论（同一环境）：把 descriptor/spec 放进 {@code @Nested} 的 {@code ListProperty} 里，
 * 由于 record 的访问器不带 {@code get}/{@code is} 前缀，Gradle 内省不出任何属性，
 * 链的值就完全不进指纹——改了 descriptor 或 spec 的值，任务仍然报 {@code UP-TO-DATE}，
 * 也就是「任务成功、产物是旧的」这种静默损坏。相比之下 {@code @Nested} 一旦能内省出属性
 * （带 {@code get} 前缀的访问器）就会要求每个子属性自带输入注解，对第三方 descriptor 同样不可行。
 *
 * <h2>本任务不产出的东西</h2>
 * 配置期那条路径在落位时还顺带产生了两个本任务不管的文件，接线时必须由别处继续产出，
 * 否则会出现「任务成功但下游缺文件」：
 * <ul>
 *   <li>{@code <name>-<version>.pom}：由 {@code LocalMavenHelper.savePom()} 写出（内容是坐标的纯函数，
 *       与 jar 无关），缺失会让按坐标注入的依赖解析失败。</li>
 *   <li>{@code <jar>.backup}：由 {@code ProcessedNamedMinecraftProvider} 在链跑完后复制，是
 *       {@code GenerateSourcesTask} 的 {@code @Classpath} 输入，缺失会让 genSources 直接失败。
 *       它必须在链跑完之后产生，因此不能留在配置期同步执行。</li>
 * </ul>
 */
@CacheableTask
public abstract class ProcessMinecraftJarTask extends DefaultTask {
	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessMinecraftJarTask.class);

	/** 待处理的 jar（命名映射后的产物）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	/**
	 * 产物：processor 链就地对这个路径做改写.
	 *
	 * <p>它的位置沿用 maven 仓库里的既有路径（消费侧按坐标找它），不由本任务决定。
	 * 任务先把它替换为输入的原子副本，再交给链改写——输入 jar 因此保持只读，
	 * 任务可重复执行，缓存与 up-to-date 判定不会因为「输入被自己改过」而失真。
	 */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	/**
	 * processor 链的描述符，顺序即处理顺序.
	 *
	 * <p>{@code @Input}：{@code ProcessorDescriptor} 按契约是配置缓存可序列化的纯值，
	 * Gradle 对它的值指纹直接可用，无需额外指纹。
	 */
	@Input
	public abstract ListProperty<MinecraftJarProcessor.ProcessorDescriptor<?>> getProcessorDescriptors();

	/**
	 * 与 {@link #getProcessorDescriptors()} 一一对应的 spec，顺序一致.
	 *
	 * <p>{@code @Internal}：{@code Spec} 不保证可序列化（见类注释），故不参与指纹，
	 * 只随配置缓存带到执行期供重建后的 processor 使用。指纹由 {@link #getSpecFingerprints()} 承担。
	 */
	@Internal
	public abstract ListProperty<MinecraftJarProcessor.Spec> getProcessorSpecs();

	/**
	 * 与 {@link #getProcessorSpecs()} 一一对应的指纹，顺序一致.
	 *
	 * <p>取 {@code spec.hashCode()}：与 loom 既有的 spec 身份一致（见类注释）。
	 */
	@Input
	public abstract ListProperty<Integer> getSpecFingerprints();

	/** 本次构建的 jar 配置身份，供执行期上下文回答「是否 split 形态」. */
	@Input
	public abstract Property<ExecutionProcessorContext.JarConfigurationKind> getJarConfiguration();

	/** 本任务处理的 jar 类型，供执行期上下文回答「是否 merged / 含客户端 / 含服务端」. */
	@Input
	public abstract Property<MinecraftJar.Type> getJarType();

	/** 是否禁用混淆（与配置期的 {@code ProcessorContextImpl.disableObfuscation()} 同源）. */
	@Input
	public abstract Property<Boolean> getDisableObfuscation();

	/** 生产命名空间；processor 请求 remapper 时用的源命名空间. */
	@Input
	public abstract Property<MappingsNamespace> getProductionNamespace();

	/** 平台默认命名空间（例如 Forge 的 {@code srg}），供 processor 使用. */
	@Input
	public abstract Property<MappingsNamespace> getIntermediaryNamespace();

	/** 映射服务配置；执行期据此取出映射树. */
	@Nested
	public abstract Property<TinyMappingsService.Options> getMappingsServiceOptions();

	/**
	 * 重映射服务配置；执行期据此取出 remapper.
	 *
	 * <p>processor 请求的是「生产命名空间 → named」那一对（{@code context.createRemapper(context.getProductionNamespace(), MappingsNamespace.NAMED)}），
	 * 装配时必须与它一致：{@code ExecutionProcessorContext.createRemapper} 对不匹配的请求直接抛异常，
	 * 而不是返回一个配置不同的 remapper。
	 */
	@Nested
	public abstract Property<TinyRemapperService.Options> getRemapperServiceOptions();

	/** 重建 processor 实例所需的 Gradle 工厂；执行期由 Gradle 注入，进不了序列化状态. */
	@Inject
	protected abstract ObjectFactory getObjectFactory();

	@TaskAction
	public void process() throws IOException {
		final List<MinecraftJarProcessor.ProcessorDescriptor<?>> descriptors = getProcessorDescriptors().get();
		final List<MinecraftJarProcessor.Spec> specs = getProcessorSpecs().get();
		final List<Integer> fingerprints = getSpecFingerprints().get();

		if (descriptors.size() != specs.size() || descriptors.size() != fingerprints.size()) {
			throw new IllegalStateException("processor 链的三个输入长度不一致：descriptor=%d, spec=%d, fingerprint=%d"
					.formatted(descriptors.size(), specs.size(), fingerprints.size()));
		}

		final Path inputJar = getInputJar().get().getAsFile().toPath();
		final Path outputJar = getOutputJar().get().getAsFile().toPath();

		LOGGER.info("Processing {} with {} jar processor(s)", outputJar.getFileName(), descriptors.size());

		// 原子落位：等价于配置期 copyToMaven 的替换语义（同目录唯一临时文件 + 原子 move）。
		// 之后的链对 outputJar 原地改写，inputJar 始终不变。
		AtomicFiles.copy(inputJar, outputJar);

		// 执行期上下文所需的服务只在本次任务动作内存在：作用域服务工厂随 try-with-resources 关闭，
		// 与 RemapMinecraftTask 的做法一致（配置期的 ConfigContext 携带的 service factory 在配置结束后已关闭）。
		try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
			final ProcessorContext context = new ExecutionProcessorContext(
					serviceFactory,
					getJarConfiguration().get(),
					getJarType().get(),
					getDisableObfuscation().get(),
					getProductionNamespace().get(),
					getIntermediaryNamespace().get(),
					getMappingsServiceOptions().get(),
					getRemapperServiceOptions().get()
			);

			for (int i = 0; i < descriptors.size(); i++) {
				processStep(descriptors.get(i), specs.get(i), outputJar, context);
			}
		}
	}

	/**
	 * 重建第 {@code descriptor} 个 processor 并让它处理产物.
	 *
	 * <p>逐个重建而不是先全部重建：processor 实例可能持有只在处理期间才需要的资源，
	 * 且配置期也是「一个 entry 一个 processor」的顺序处理。
	 */
	private void processStep(MinecraftJarProcessor.ProcessorDescriptor<?> descriptor, MinecraftJarProcessor.Spec spec,
			Path jar, ProcessorContext context) throws IOException {
		final MinecraftJarProcessor<?> processor = Objects.requireNonNull(descriptor, "processor descriptor")
				.createProcessor(getObjectFactory());

		try {
			runProcessor(processor, spec, jar, context);
		} catch (IOException e) {
			// 与配置期 MinecraftJarProcessorManager.processJar 一致：失败后尽力删除半成品，
			// 避免跨进程的读方把「改了一半的 jar」当成有效产物（任务失败时 Gradle 也不会记录该产出为最新）
			try {
				Files.deleteIfExists(jar);
			} catch (IOException ioe) {
				LOGGER.error("Failed to delete jar after failed processing: {}", jar, ioe);
			}

			throw new IOException("Failed to process jar when running jar processor: %s - %s".formatted(processor.getName(), e.getMessage()), e);
		}
	}

	/**
	 * 以「同一对 spec/context」调用 processor 的处理入口.
	 *
	 * <p>助手方法只为消掉泛型：{@code ProcessorDescriptor<?>} 的类型参数是捕获类型，
	 * 无法在此处写成 {@code MinecraftJarProcessor<S>}，而配置期 {@code ProcessorEntry}
	 * 也做了同样的非检查转换——两边调用的是同一个方法签名。
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void runProcessor(MinecraftJarProcessor processor, MinecraftJarProcessor.Spec spec, Path jar, ProcessorContext context) throws IOException {
		processor.processJar(jar, spec, context);
	}
}
