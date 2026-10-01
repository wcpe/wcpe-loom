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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * 补出 maven 构件目录里「不属于 jar processor 链」的两个文件：pom 与 backup.
 *
 * <p>{@link ProcessMinecraftJarTask} 只产出 jar，且这是刻意的设计（见其类注释的「本任务不产出的东西」）：
 * 它按「处理器链就地改 jar」建模，而 pom 是坐标的纯函数、backup 是链跑完之后才该出现的副本。
 * 但配置期那条路径在 {@code copyToMaven} 与 {@code createBackupJars} 里顺带产生了这两个文件，
 * 缺它们会直接破坏下游：缺 pom 让按坐标解析该构件的依赖失败，缺 backup 让 {@code genSources} 抛
 * 「Input minecraft jar not found」。因此切换生产路径时必须由别处继续产出它们——就是本任务。
 *
 * <h2>为什么单独一个任务，而不是塞进处理任务</h2>
 * 两者的就绪前提不同：pom 与 backup 都必须在链**跑完之后**产生（backup 更是 jar 的逐字节副本，
 * 早于链完成就复制等于复制了一份半成品）。拆开之后 {@link ProcessMinecraftJarTask} 保持原样，
 * 顺序由 Gradle 从本任务对 jar 的 {@code @InputFile} 依赖推出。
 *
 * <h2>落位必须原子</h2>
 * 两个文件都位于跨进程、跨 daemon 共享的 maven 仓库，且配置期就已是原子落位
 * （{@code savePom} 的唯一临时文件 + 原子 move，{@code createBackupJars} 的 {@code AtomicFiles.copy}）。
 * 本任务沿用同一条契约：否则读方会读到半写的 pom / backup，而「内容判定」发现不了这类残骸。
 */
@CacheableTask
public abstract class WriteMinecraftJarSidecarsTask extends DefaultTask {
	/** 已由处理任务落位的 jar；本任务只为它补出同构件目录里的 pom 与 backup. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getJar();

	/**
	 * pom 的输出位置，由接线侧按 {@link LocalMavenHelper} 的 name/version 算出.
	 *
	 * <p>未设置 = 本次不产出 pom（例如调用方按文件而不是坐标消费该构件）。
	 */
	@Optional
	@OutputFile
	public abstract RegularFileProperty getPom();

	/** 写 pom 所需坐标之一；未设置 {@link #getPom()} 时无意义. */
	@Optional
	@Input
	public abstract Property<String> getPomGroup();

	/** 写 pom 所需坐标之一；未设置 {@link #getPom()} 时无意义. */
	@Optional
	@Input
	public abstract Property<String> getPomName();

	/** 写 pom 所需坐标之一；未设置 {@link #getPom()} 时无意义. */
	@Optional
	@Input
	public abstract Property<String> getPomVersion();

	/** 写 pom 所需坐标之一（maven 仓库根）；未设置 {@link #getPom()} 时无意义. */
	@Optional
	@Input
	public abstract Property<String> getPomMavenRoot();

	/**
	 * jar 的逐字节副本的输出位置.
	 *
	 * <p>未设置 = 本 provider 不需要 backup（中间映射产物不会被反编译）。
	 */
	@Optional
	@OutputFile
	public abstract RegularFileProperty getBackupJar();

	@TaskAction
	public void write() throws IOException {
		final Path jar = getJar().get().getAsFile().toPath().toAbsolutePath().normalize();

		if (getPom().isPresent()) {
			writePom(jar);
		}

		if (getBackupJar().isPresent()) {
			writeBackupJar(jar);
		}
	}

	/**
	 * 写出 pom，并把「声明出来的输出」与「{@link LocalMavenHelper#savePom} 实际写入的位置」对齐校验.
	 *
	 * <p>内容不在这里生成：模板与替换都由 {@code savePom} 负责。本方法只做三件事——校验声明位置就是
	 * 它会写入的位置、保证构件目录存在、确认写出的确实是本任务声明的那个文件。不校验的话，
	 * 坐标式解析会在该位置长期读到「缺 pom」或「别处来的 pom」——后者更糟：解析成功但内容不是这一份。
	 */
	private void writePom(Path jar) throws IOException {
		if (!getPomGroup().isPresent() || !getPomName().isPresent() || !getPomVersion().isPresent() || !getPomMavenRoot().isPresent()) {
			throw new IllegalStateException("声明了 pom 输出 %s，但缺少生成它所需的坐标（pomGroup=%s, pomName=%s, pomVersion=%s, pomMavenRoot=%s）。"
					.formatted(getPom().get().getAsFile(),
							getPomGroup().getOrNull(), getPomName().getOrNull(), getPomVersion().getOrNull(), getPomMavenRoot().getOrNull()));
		}

		final Path pom = getPom().get().getAsFile().toPath().toAbsolutePath().normalize();
		final LocalMavenHelper mavenHelper = new LocalMavenHelper(
				getPomGroup().get(), getPomName().get(), getPomVersion().get(), null,
				Path.of(getPomMavenRoot().get()));

		// 用 LocalMavenHelper 自己的公开接口复算落位：group 的点号展开、name/version 的拼接仍只由它负责。
		// 两侧都必须一致：与 savePom 的落位一致，才能保证写出的就是声明的那个文件；
		// 与 jar 的落位一致（同一构件目录），才能保证 pom 与 jar 成对。
		final Path expectedPom = RemapMinecraftTask.pomPathFor(
				mavenHelper.root().resolve(mavenHelper.getRelativeArtifactPath(null)).toAbsolutePath().normalize());

		if (!expectedPom.equals(pom) || !RemapMinecraftTask.pomPathFor(jar).equals(pom)) {
			throw new IllegalStateException("声明的 pom 输出 %s 与 LocalMavenHelper 会写入的 pom %s 不是同一个文件"
					.formatted(pom, expectedPom)
					+ "（或与 jar %s 不同目录/不同名）。".formatted(jar)
					+ "pom 的位置由 LocalMavenHelper 按 group/name/version 展开，本任务的 pom 输出必须与之一致，"
					+ "否则坐标式依赖解析会缺 pom，或拿到不在构件目录里的那份。");
		}

		// savePom 不建目录：它把内容写进目标同目录的临时文件，目录不存在会直接失败
		Files.createDirectories(pom.getParent());
		mavenHelper.savePom();

		if (Files.notExists(pom)) {
			throw new IllegalStateException("savePom 之后本任务声明的 pom 输出 %s 仍不存在：说明声明的坐标/仓库根与实际落位不一致。"
					.formatted(pom));
		}
	}

	/**
	 * 写出 jar 的逐字节副本（backup）.
	 *
	 * <p>顺序上是最后一步：backup 是配置期用来表示「产物齐了」的就绪标志，消费者
	 * （{@code GenerateSourcesTask}）也按 {@code <jar>.backup} 找它，因此它必须在链与 pom 都落位之后才出现。
	 */
	private void writeBackupJar(Path jar) throws IOException {
		final Path backup = getBackupJar().get().getAsFile().toPath().toAbsolutePath().normalize();

		// 消费者按固定规则找这个文件，声明的位置必须与之一致：否则会写出一份没人读的副本，
		// 而 genSources 会在真正的路径上找不到输入
		if (!(jar.getFileName() + ".backup").equals(backup.getFileName().toString()) || !jar.getParent().equals(backup.getParent())) {
			throw new IllegalStateException("声明的 backup 输出 %s 必须与 jar %s 同目录且名为 %s。"
					.formatted(backup, jar, jar.getFileName() + ".backup"));
		}

		// 副本与正本同处共享 maven 仓库，必须原子落位，避免读方读到半写的 backup 而误判产物就绪
		AtomicFiles.copy(jar, backup);
	}
}
