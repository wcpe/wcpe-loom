/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.api.processor;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;

import org.gradle.api.Named;
import org.gradle.api.model.ObjectFactory;
import org.jspecify.annotations.Nullable;

import net.fabricmc.mappingio.tree.MemoryMappingTree;

public interface MinecraftJarProcessor<S extends MinecraftJarProcessor.Spec> extends Named {
	@Nullable
	S buildSpec(SpecContext context);

	void processJar(Path jar, S spec, ProcessorContext context) throws IOException;

	@Nullable
	default MappingsProcessor<S> processMappings() {
		return null;
	}

	/**
	 * 返回一个描述符，用于在执行期重建本 processor 的实例.
	 *
	 * <p>配置期构造的 processor 实例常常持有无法放进任务状态的对象（例如 {@link org.gradle.api.Project}、
	 * Gradle 服务对象、任意的第三方实现），直接作为任务输入会破坏配置缓存。因此把重建所需的最小输入
	 * 提取成描述符中的纯值，由执行期的任务调用 {@link ProcessorDescriptor#createProcessor} 重建实例。
	 *
	 * <p>默认实现直接抛出异常，以保证对既有第三方实现的二进制与源码兼容：未实现本方法的 processor
	 * 依然可以正常参与配置期的处理，只是无法参与执行期重建。
	 */
	default ProcessorDescriptor<?> descriptor() {
		throw new UnsupportedOperationException(("Jar processor '%s' (%s) does not implement MinecraftJarProcessor#descriptor(), "
				+ "so it cannot be recreated at execution time. Implement descriptor() to return a configuration cache serializable "
				+ "ProcessorDescriptor that recreates this processor from its constructor inputs.")
				.formatted(getName(), getClass().getName()));
	}

	interface Spec {
		// Must make sure hashCode is correctly implemented.
	}

	interface MappingsProcessor<S> {
		boolean transform(MemoryMappingTree mappings, S spec, MappingProcessorContext context);
	}

	/**
	 * 描述如何在执行期重建一个 {@link MinecraftJarProcessor} 实例.
	 *
	 * <p>实现必须是配置缓存可序列化的：只允许持有字符串、基本类型、枚举以及它们的集合/映射这类纯值，
	 * 不得持有 {@link org.gradle.api.Project}、Gradle 服务、文件系统句柄或 processor 实例本身。
	 * 需要在重建时用到的 Gradle 服务由 {@link #createProcessor} 的调用方注入。
	 *
	 * @param <T> 被重建的 processor 类型
	 */
	interface ProcessorDescriptor<T extends MinecraftJarProcessor<?>> extends Serializable {
		/**
		 * 使用给定的 {@link ObjectFactory} 重建 processor 实例.
		 *
		 * @param objectFactory 用于实例化带 {@code @Inject} 构造函数的 processor，可安全地在执行期使用
		 * @return 重建出的 processor 实例
		 */
		T createProcessor(ObjectFactory objectFactory);
	}
}
