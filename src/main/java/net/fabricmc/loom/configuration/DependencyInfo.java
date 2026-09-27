/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.configuration;

import java.io.File;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;

public class DependencyInfo {
	final Project project;
	final Dependency dependency;
	final Configuration sourceConfiguration;

	private String resolvedVersion = null;

	public static DependencyInfo create(Project project, String configuration) {
		return create(project, project.getConfigurations().getByName(configuration));
	}

	public static DependencyInfo create(Project project, Configuration configuration) {
		DependencySet dependencies = configuration.getDependencies();

		if (dependencies.isEmpty()) {
			throw new IllegalArgumentException(String.format("Configuration '%s' has no dependencies", configuration.getName()));
		}

		if (dependencies.size() != 1) {
			throw new IllegalArgumentException(String.format("Configuration '%s' must only have 1 dependency", configuration.getName()));
		}

		return create(project, dependencies.iterator().next(), configuration);
	}

	public static DependencyInfo create(Project project, Dependency dependency, Configuration sourceConfiguration) {
		if (dependency instanceof FileCollectionDependency fileCollectionDependency) {
			return new FileDependencyInfo(project, fileCollectionDependency, sourceConfiguration);
		} else {
			return new DependencyInfo(project, dependency, sourceConfiguration);
		}
	}

	DependencyInfo(Project project, Dependency dependency, Configuration sourceConfiguration) {
		this.project = project;
		this.dependency = dependency;
		this.sourceConfiguration = sourceConfiguration;
	}

	public Dependency getDependency() {
		return dependency;
	}

	/**
	 * 返回声明依赖的 classifier，供未解析依赖前的缓存键使用.
	 */
	public String getDeclaredClassifier() {
		if (!(dependency instanceof ModuleDependency moduleDependency)) {
			return "";
		}

		return moduleDependency.getArtifacts().stream()
				.map(DependencyArtifact::getClassifier)
				.filter(Objects::nonNull)
				.sorted()
				.collect(Collectors.joining(","));
	}

	/**
	 * 返回声明依赖的 artifact 维度，避免不同扩展名或类型误用同一早缓存.
	 */
	public String getDeclaredArtifactDimensions() {
		if (!(dependency instanceof ModuleDependency moduleDependency)) {
			return "";
		}

		return moduleDependency.getArtifacts().stream()
				.map(artifact -> String.join(",",
						Objects.toString(artifact.getName(), ""),
						Objects.toString(artifact.getType(), ""),
						Objects.toString(artifact.getExtension(), ""),
						Objects.toString(artifact.getClassifier(), "")))
				.sorted(Comparator.naturalOrder())
				.collect(Collectors.joining(";"));
	}

	public String getResolvedVersion() {
		if (resolvedVersion != null) {
			return resolvedVersion;
		}

		for (ResolvedDependency rd : sourceConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
			if (rd.getModuleGroup().equals(dependency.getGroup()) && rd.getModuleName().equals(dependency.getName())) {
				resolvedVersion = rd.getModuleVersion();
				return resolvedVersion;
			}
		}

		resolvedVersion = dependency.getVersion();
		return resolvedVersion;
	}

	public Configuration getSourceConfiguration() {
		return sourceConfiguration;
	}

	private boolean matches(ComponentIdentifier identifier) {
		if (identifier instanceof ModuleComponentIdentifier moduleComponentIdentifier) {
			return moduleComponentIdentifier.getGroup().equals(dependency.getGroup())
					&& moduleComponentIdentifier.getModule().equals(dependency.getName())
					&& moduleComponentIdentifier.getVersion().equals(dependency.getVersion());
		}

		return false;
	}

	public Set<File> resolve() {
		return sourceConfiguration.getIncoming()
				.artifactView(view -> view.componentFilter(this::matches))
				.getFiles()
				.getFiles();
	}

	public Optional<File> resolveFile() {
		Set<File> files = resolve();

		if (files.isEmpty()) {
			return Optional.empty();
		} else if (files.size() > 1) {
			StringBuilder builder = new StringBuilder(this.toString());
			builder.append(" resolves to more than one file:");

			for (File f : files) {
				builder.append("\n\t-").append(f.getAbsolutePath());
			}

			throw new RuntimeException(builder.toString());
		} else {
			return files.stream().findFirst();
		}
	}

	@Override
	public String toString() {
		return getDepString();
	}

	public String getDepString() {
		final String classifier = getDeclaredClassifier();
		return dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion()
				+ (classifier.isEmpty() ? "" : ":" + classifier);
	}

	public String getResolvedDepString() {
		final String classifier = getDeclaredClassifier();
		return dependency.getGroup() + ":" + dependency.getName() + ":" + getResolvedVersion()
				+ (classifier.isEmpty() ? "" : ":" + classifier);
	}
}
