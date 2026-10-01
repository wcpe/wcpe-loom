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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

/**
 * L3 流水线：把一个远程制品下载到指定位置.
 *
 * <p>这是 L3 的第一个节点，也是「产物有效性交给 Gradle」的最小完整示范。对照被它取代的旧路径
 * （{@code MinecraftProvider.provide()} 内的下载分支）：
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要下载</td><td>配置期 {@code Files.exists} 判定（配置缓存输入）</td>
 *       <td>Gradle up-to-date 检查</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td>
 *       <td>Gradle 任务图</td></tr>
 *   <tr><td>产物损坏恢复</td><td>手写内容校验（开 zip 列举条目）</td>
 *       <td>依赖 sha1 输入 + 构建缓存重新恢复</td></tr>
 *   <tr><td>执行时机</td><td>配置期（{@code afterEvaluate}）</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>为什么输入里没有 Project</h2>
 * 旧路径通过 {@code extension.download(url)} 构造下载器，该方法做的事只有三件：
 * 建下载器、按命令行是否离线设置 offline、按是否刷新依赖设置 forceDownload。
 * 三者都可以表示为任务输入，因此本任务不需要触碰任何项目模型——
 * 这正是它能在执行期安全运行、且配置缓存友好的原因。
 *
 * <h2>为什么输出可以位于用户级共享目录</h2>
 * 产物刻意落在跨项目共享的位置（如 {@code ~/.gradle/caches/fabric-loom/<mcVersion>/}）：
 * 同一版本被多个项目使用时，让它们指向同一份文件比各自持有一份更省空间与带宽。
 * Gradle 的 up-to-date 检查以「输入指纹」为准，多个项目实例对同一路径的判定是一致的，
 * 因此共享输出不会造成互相踩踏——这与旧实现需要跨进程锁的情形不同。
 */
@CacheableTask
public abstract class DownloadArtifactTask extends DefaultTask {
	/** 远程制品地址. */
	@Input
	public abstract Property<String> getUrl();

	/** 期望的 sha1；为空时按 maxAge 缓存策略处理. */
	@Input
	@Optional
	public abstract Property<String> getSha1();

	/** 命令行是否要求离线；离线且产物缺失时应当明确失败而不是尝试联网. */
	@Input
	public abstract Property<Boolean> getOffline();

	/** 是否强制重新下载（对应 {@code --refresh-dependencies}）. */
	@Input
	public abstract Property<Boolean> getForceDownload();

	/** 走 {@code maxAge} 缓存策略还是严格按 sha1 校验. */
	@Input
	public abstract Property<Boolean> getUseDefaultCache();

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	/**
	 * 下载产物所在位置，供诊断输出使用（不参与缓存键）.
	 */
	@Internal
	public Path getOutputPath() {
		return getOutputFile().get().getAsFile().toPath();
	}

	@TaskAction
	public void download() throws IOException {
		final DownloadBuilder builder = createBuilder();
		final Path target = getOutputPath();

		Files.createDirectories(target.getParent());
		builder.downloadPath(target);
	}

	private DownloadBuilder createBuilder() {
		final DownloadBuilder builder;

		try {
			builder = Download.create(getUrl().get());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid download url: " + getUrl().get(), e);
		}

		if (getSha1().isPresent()) {
			builder.sha1(getSha1().get());
		} else if (getUseDefaultCache().get()) {
			builder.defaultCache();
		}

		if (getOffline().get()) {
			builder.offline();
		}

		if (getForceDownload().get()) {
			builder.forceDownload();
		}

		return builder;
	}
}
