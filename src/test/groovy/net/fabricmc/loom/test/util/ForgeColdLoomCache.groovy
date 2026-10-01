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

package net.fabricmc.loom.test.util

import org.apache.commons.io.FileUtils

import net.fabricmc.loom.test.LoomTestConstants

/**
 * 造一份「Forge 工具链热、被测 MC 产物冷」的 loom 共享缓存目录.
 *
 * <p>为什么需要：默认的测试 gradle home 里 named MC jar 是热的，「冷缓存」无从谈起；而把共享 home
 * 里的产物删掉会破坏同一台机器上并发运行的其它用例。被测构建因此用一个**独立的 loom 共享缓存**
 * （由 {@code -Dfabric.loom.cache.dir} 指定，见 {@code LoomFilesBaseImpl.getUserCache}），
 * 内容从默认 home **按需播种**：只复制重建代价高的部分（Forge 工具链、映射构件），
 * 并刻意剔除被测的那一份产物。
 *
 * <h4>为什么只隔离 loom 的缓存，不隔离整个 gradle home</h4>
 * Gradle 自身的缓存（{@code modules-2}、{@code jars-9}、发行版）与被测的冷缓存无关，
 * 复制它们只会把用例拖慢上 GB。被测构建仍用默认 home 解析依赖，只有 loom 的产物仓库被隔离。
 *
 * <h4>播种为什么必须复制而不是硬链接</h4>
 * 被测构建会往这个目录里**写**产物（named jar、backup、源码包、pom）。硬链接会让这些写入穿透到
 * 共享 home，静默污染并发运行的其它用例。
 */
final class ForgeColdLoomCache {
	private ForgeColdLoomCache() {
	}

	/**
	 * 播种一份隔离的 loom 共享缓存，并让 {@code mergedArtifact} 对应的产物变冷.
	 *
	 * <p>「变冷」只删被测的那份产物：named jar、它的 {@code .backup} 与 {@code -sources.jar}。
	 * {@code .pom} 留着——它是坐标元数据，产出任务自己会重写。同构件目录下的 srg / intermediary
	 * 变体是**另外的**产物（named jar 的上游输入），保留，这样用例只测「named jar 缺失」这一件事。
	 *
	 * @param mcVersion 形如 {@code 1.20.1}
	 * @param mergedArtifact 合并构件名，形如 {@code forge-1.20.1-47.2.1-minecraft-merged}
	 * @return 可直接作为 {@code fabric.loom.cache.dir} 的目录
	 */
	static File seed(String mcVersion, String mergedArtifact) {
		final File seed = new File(LoomTestConstants.TEST_DIR, "integration/gradle_home/caches/fabric-loom")
		final File target = File.createTempDir("loom-forge-cold-cache", "")
		final File targetMaven = new File(target, "minecraftMaven/net/minecraft")

		// 版本目录：官方 jar、映射、Forge 工具链的工作区
		copyIfExists(new File(seed, mcVersion), new File(target, mcVersion))
		// Forge 工具链的转换产物（重建代价最高的那一份）
		copyIfExists(new File(seed, "forge"), new File(target, "forge"))
		// 映射构件
		copyIfExists(new File(seed, "minecraftMaven/loom"), new File(target, "minecraftMaven/loom"))

		[
			mergedArtifact,
			"${mergedArtifact}-srg",
			"${mergedArtifact}-intermediary"
		].each { name ->
			copyIfExists(new File(seed, "minecraftMaven/net/minecraft/${name}"), new File(targetMaven, name))
		}

		makeCold(new File(targetMaven, mergedArtifact))
		return target
	}

	/** 删掉构件目录里被测的那三份产物（保留 {@code .pom}）. */
	private static void makeCold(File artifactDir) {
		if (!artifactDir.isDirectory()) {
			return
		}

		artifactDir.listFiles()?.findAll { it.isDirectory() }?.each { mappingsDir ->
			mappingsDir.listFiles()?.findAll { it.isFile() && !it.name.endsWith(".pom") }?.each { it.delete() }
		}
	}

	private static void copyIfExists(File source, File target) {
		if (!source.exists()) {
			return
		}

		if (source.isDirectory()) {
			target.mkdirs()
			FileUtils.copyDirectory(source, target)
		} else {
			target.parentFile.mkdirs()
			FileUtils.copyFile(source, target)
		}
	}
}
