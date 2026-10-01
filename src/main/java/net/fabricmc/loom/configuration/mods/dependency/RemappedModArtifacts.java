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

package net.fabricmc.loom.configuration.mods.dependency;

import java.nio.file.Path;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry;
import net.fabricmc.loom.pipeline.RemapMinecraftTaskRegistry.Producer;

/**
 * 一批 remapped mod 产物**各自的生产位置**，消费方据此把产物路径换成可注入配置的依赖表示.
 *
 * <h2>为什么消费方不能只依赖「本项目的那一个任务」</h2>
 * 产物落在构建内所有项目共用的 {@code remapped_mods} 仓库里，同一条路径只能有一个生产者，由
 * {@link RemapMinecraftTaskRegistry#claimAll} 在同一构建内判定（见 {@link net.fabricmc.loom.pipeline.RemapModsTask}）。
 * 本项目这一批里可能有若干条产物早已被**别的项目**认领——那些产物由对方的任务产出，本项目不会再写第二遍，
 * 因此消费方必须按**每一条产物**去挂依赖，而不是一律挂到本项目的任务上：后者会让构建因为
 * 「用了别的任务的产物却没声明依赖」而失败，或者更糟——声明了一条并不产出该文件的任务依赖。
 *
 * <h2>依赖按任务路径登记</h2>
 * 产出方可能来自另一份 Loom classloader（同一构建内多个项目各带一份 Loom 时会发生），任务实例过不来，
 * 任务路径是字符串、跨 classloader 可用，交给 {@code builtBy} 后由 Gradle 解析成真正的（可能是跨项目的）
 * 任务依赖。
 */
public final class RemappedModArtifacts {
	private final Project project;
	private final Path root;
	private final Map<Path, Producer> producers;

	/**
	 * @param project 消费方项目
	 * @param root 共享仓库根（{@code getRemappedModCache}）
	 * @param producers 本次登记判定的「产物路径 → 生产位置」；键为绝对规范化路径
	 */
	public RemappedModArtifacts(Project project, Path root, Map<Path, Producer> producers) {
		this.project = project;
		this.root = root;
		this.producers = Map.copyOf(producers);
	}

	/**
	 * {@return 该产物的依赖表示} 即「文件本身 + 产出它的任务」，可直接交给
	 * {@code DependencyHandler#add} 或 {@code ModSettings#getModFiles}.
	 *
	 * @param relativeArtifactPath 相对共享仓库根的产物路径（见 {@link LocalMavenHelper#getRelativeArtifactPath}）
	 */
	public Object dependency(String relativeArtifactPath) {
		final Path artifact = root.resolve(relativeArtifactPath).toAbsolutePath().normalize();
		final ConfigurableFileCollection files = project.files(artifact.toFile());
		final String taskPath = taskPathOf(artifact);

		if (taskPath != null) {
			files.builtBy(taskPath);
		}

		return files;
	}

	/**
	 * {@return 产出该产物的任务路径} 本批次的登记表里没有这条路径时为 {@code null}.
	 *
	 * <p>缺失表示该产物不由 L3 任务产出（例如与产物无关的普通文件依赖）：这里不替它编一条任务依赖
	 * ——那只会让构建声明一条并不产出该文件的依赖边。拆分依赖（{@link SplitModDependency}）的
	 * common/client 两条变体**不**属于这一类：它们与其它产物一样参与认领，因此同样能在表里查到生产者。
	 */
	private @Nullable String taskPathOf(Path artifact) {
		final Producer producer = producers.get(artifact);
		return producer == null ? null : producer.taskPath();
	}
}
