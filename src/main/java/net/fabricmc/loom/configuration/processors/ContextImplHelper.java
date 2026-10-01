/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

package net.fabricmc.loom.configuration.processors;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.util.LazyCloseable;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * 构造带 MC classpath 的 remapper 的共用实现.
 *
 * <p>调用方有两条：配置期的 jar processor（{@code ProcessorContextImpl} /
 * {@code MappingProcessorContextImpl}），以及执行期的 {@code SourceMappingsService}（genSources 用它
 * 生成源码映射）。两者都用 {@link ConfigContext} 取输入，因此这里只做「取 MC jar → 装配 remapper」。
 */
public final class ContextImplHelper {
	private ContextImplHelper() {
	}

	/**
	 * {@return 惰性构造的 remapper} 首次读取时才真正装配，并在此刻读入 MC classpath.
	 *
	 * @param configContext 配置上下文
	 * @param from 源命名空间，决定取哪个命名空间的 MC jar 做 classpath
	 * @param to 目标命名空间
	 */
	public static LazyCloseable<TinyRemapper> createRemapper(ConfigContext configContext, MappingsNamespace from, MappingsNamespace to) {
		return new LazyCloseable<>(() -> {
			try {
				TinyRemapper tinyRemapper = TinyRemapperHelper.getTinyRemapper(configContext.project(), configContext.serviceFactory(), from.toString(), to.toString());

				for (Path minecraftJar : minecraftClassPath(configContext, from)) {
					tinyRemapper.readClassPath(minecraftJar);
				}

				return tinyRemapper;
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to create tiny remapper", e);
			}
		}, TinyRemapper::finish);
	}

	/**
	 * {@return {@code from} 命名空间下作为 remapper classpath 的 MC jar}.
	 *
	 * <p>取的是 {@code getMinecraftJarsCollection} 而不是裸的 {@code getMinecraftJars}：两者路径相同，
	 * 但前者在产出已登记为任务时与任务产出是**同一份来源**，而且这里会把「产物还不存在」当场报成一条
	 * 可定位的错误，而不是把裸路径交给 tiny-remapper 让它抛更含糊的异常。
	 *
	 * <p>注意本方法**不**（也无法）替调用方声明任务依赖——任务依赖只能由调用方那条任务自己声明
	 * （执行期的那条链是 {@code GenerateSourcesTask}，它已按扩展登记的产出集合声明了 named jar 的依赖）。
	 * 因此这里的报错文案要把这件事说清楚：缺产物要么是「生产者的依赖没接上」，要么是「在生产者执行前
	 * 就被读了」。
	 *
	 * @param configContext 配置上下文
	 * @param from 源命名空间
	 * @throws IllegalStateException 该命名空间的 MC jar 已被登记为任务产出，但产物当前不存在
	 */
	private static List<Path> minecraftClassPath(ConfigContext configContext, MappingsNamespace from) {
		final Set<File> minecraftJars = configContext.extension().getMinecraftJarsCollection(from).getFiles();
		final List<Path> classPath = new ArrayList<>(minecraftJars.size());

		for (File minecraftJar : minecraftJars) {
			if (!minecraftJar.isFile()) {
				throw new IllegalStateException(("Minecraft jar %s（命名空间 %s）尚不存在，无法作为 remapper 的 classpath。"
						+ "该产物由任务在生产期落位（RemapMinecraftTask / ProcessMinecraftJarTask）："
						+ "若本处位于执行期，说明消费它的任务没有声明产出依赖；若位于配置期，说明产物尚未生产。")
						.formatted(minecraftJar, from));
			}

			classPath.add(minecraftJar.toPath());
		}

		return classPath;
	}
}
