/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.internal.artifacts.repositories.resolver.MavenUniqueSnapshotComponentIdentifier;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.configuration.mods.ArtifactRef;

public abstract sealed class ModDependency permits SplitModDependency, SimpleModDependency {
	private final ArtifactRef artifact;
	private final ArtifactMetadata metadata;
	private final String group;
	private final String name;
	private final String version;
	@Nullable
	private final String classifier;
	private final ModDependencyOptions options;

	public ModDependency(ArtifactRef artifact, ArtifactMetadata metadata, ModDependencyOptions options) {
		this.artifact = artifact;
		this.metadata = metadata;
		this.group = artifact.group();
		this.name = artifact.name();
		this.version = artifact.version();
		this.classifier = artifact.classifier();
		this.options = options;
	}

	/**
	 * Returns true when the cache is invalid.
	 */
	public abstract boolean isCacheInvalid(Project project, @Nullable String variant);

	/**
	 * Write an artifact to the local cache.
	 */
	public abstract void copyToCache(Project project, Path path, @Nullable String variant) throws IOException;

	/**
	 * 把本依赖注入目标配置.
	 *
	 * @param artifacts 本批 remapped mod 产物各自的生产位置（见 {@link RemappedModArtifacts}）；
	 *         产出方未必是本项目的任务，故不传任务实例而传「按路径查生产者」的解析器
	 */
	public abstract void applyToProject(Project project, RemappedModArtifacts artifacts);

	/**
	 * {@return 本依赖的重映射产物落在共享仓库里的**全部**路径}.
	 *
	 * <p>与消费方找产物走的是同一处计算（{@link #createMavenHelper}），因为 L3 任务按这些路径写出、
	 * 消费方按这些路径读取：两处各自算一遍布局，会在快照版本这类「目录名与文件名不同源」的用例上分叉成
	 * 「任务写一条、消费方读另一条」的静默缺失。
	 *
	 * <p>返回列表而不是单条路径，是因为依赖的产物数不止一条：拆分依赖（{@link SplitModDependency}）
	 * 会产出 common/client 两条各自落位的变体（见 override）。生产与消费都按**这一处**结果走，
	 * 「谁产出某条路径」才不会漏掉其中一条。
	 *
	 * @param project 所在项目（决定共享仓库根）
	 */
	public List<Path> getCacheArtifactPaths(Project project) {
		return List.of(createMavenHelper(project, null).getOutputFile(null));
	}

	/**
	 * Create a maven helper for the local cache.
	 * @param type The jar type, e.g "common" or "client" for split dependencies.
	 */
	protected LocalMavenHelper createMavenHelper(Project project, @Nullable String type) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final Path root = extension.getFiles().getRemappedModCache().toPath();
		final String fullName = getName() + (type != null ? "-" + type : "");
		return new LocalMavenHelper(getGroup(), fullName, this.version, this.classifier, root, getSnapshotVersion());
	}

	private @Nullable String getSnapshotVersion() {
		if (artifact instanceof ArtifactRef.ResolvedArtifactRef resolvedArtifactRef) {
			ComponentIdentifier componentIdentifier = resolvedArtifactRef.artifact().getId().getComponentIdentifier();

			if (componentIdentifier instanceof MavenUniqueSnapshotComponentIdentifier mavenUniqueId) {
				return mavenUniqueId.getSnapshotVersion();
			}
		}

		return null;
	}

	public ArtifactRef getInputArtifact() {
		return artifact;
	}

	public ArtifactMetadata getMetadata() {
		return metadata;
	}

	/**
	 * 产出坐标名（含 cache key）；L3 任务据此定位产出路径，故为 public（见架构 §5.1.1）.
	 */
	public String getName() {
		return "%s-%s".formatted(name, options.getCacheKey());
	}

	/** 产出坐标组；同上. */
	public String getGroup() {
		return "remapped.%s".formatted(group);
	}

	/** 产出坐标版本；同上. */
	public String getVersion() {
		return version;
	}

	/** 产出坐标分类器；同上. */
	public @Nullable String getClassifier() {
		return classifier;
	}

	public Path getInputFile() {
		return artifact.path();
	}

	public Path getWorkingFile(Project project, @Nullable String classifier) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final String fileName = classifier == null ? String.format("%s-%s-%s.jar", getGroup(), getName(), version)
													: String.format("%s-%s-%s-%s.jar", getGroup(), getName(), version, classifier);

		return extension.getFiles().getProjectBuildCache().toPath().resolve("remapped_working").resolve(fileName);
	}

	public ModDependencyOptions getOptions() {
		return options;
	}

	@Override
	public String toString() {
		return "ModDependency{" + "group='" + group + '\'' + ", name='" + name + '\'' + ", version='" + version + '\'' + ", classifier='" + classifier + '\'' + '}';
	}
}
