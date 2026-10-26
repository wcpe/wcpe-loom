/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom;

import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactRepositoryContainer;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.PluginAware;
import org.gradle.api.plugins.PluginContainer;

import net.fabricmc.loom.configuration.providers.minecraft.library.processors.LWJGL2UpgradeLibraryProcessor;
import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.internal.LoomGradleSharedData;
import net.fabricmc.loom.util.MirrorUtil;

public class LoomRepositoryPlugin implements Plugin<PluginAware> {
	/** 本 fork 的插件 id. */
	public static final String NAME = "top.wcpe.loom-repositories";
	/** 上一层分叉（architectury）的插件 id，作为兼容别名保留. */
	public static final String LEGACY_NAME = "dev.architectury.loom-repositories";
	private static final List<String> FORGE_GROUPS = List.of(
			"net.minecraftforge",
			"cpw.mods",
			"de.oceanlabs",
			"net.jodah",
			"org.mcmodlauncher"
	);

	/**
	 * 构建是否已由 settings 侧声明过仓库.
	 *
	 * <p>判据必须与 classloader 无关：当 Loom 被两个 classloader 各加载一份时
	 * （settings 与 project classpath 各一份、buildSrc/约定插件与 plugins 块各一份、复合构建），
	 * {@code hasPlugin(LoomRepositoryPlugin.class)} 走的是类型身份比较，对本 classloader 而言恒判 false，
	 * marker 静默失效，项目侧会重复声明整套仓库并二次重排（不报错，只是静默地做了两遍）。
	 * 插件 id 是字符串，Gradle 在注册表内部按「注册者那一侧的 class」比对，天然跨 classloader 一致，
	 * 与 {@link LoomGradlePlugin#isApplied(PluginAware)} 同理。
	 *
	 * @param gradle 目标构建
	 * @return 构建上是否已有 Loom 的仓库声明 marker
	 */
	public static boolean isRepositoriesDeclared(Gradle gradle) {
		PluginContainer plugins = gradle.getPlugins();
		return plugins.hasPlugin(NAME) || plugins.hasPlugin(LEGACY_NAME);
	}

	@Override
	public void apply(PluginAware target) {
		if (target instanceof Settings settings) {
			LoomGradleSharedData.beforeProject(settings.getGradle());
			declareRepositories(settings.getDependencyResolutionManagement().getRepositories(), LoomFiles.create(settings), settings);

			// leave a marker so projects don't try to override these
			settings.getGradle().getPluginManager().apply(LoomRepositoryPlugin.class);
		} else if (target instanceof Project project) {
			LoomGradleSharedData.beforeProject(project.getGradle());
			LoomGradleSharedData.beforeProject(project, LoomGradleSharedData.get(project));

			if (isRepositoriesDeclared(project.getGradle())) {
				return;
			}

			declareRepositories(project.getRepositories(), LoomFiles.create(project), project);
		} else if (target instanceof Gradle) {
			return;
		} else {
			throw new IllegalArgumentException("Expected target to be a Project or Settings, but was a " + target.getClass());
		}
	}

	private void declareRepositories(RepositoryHandler repositories, LoomFiles files, ExtensionAware target) {
		declareLocalRepositories(repositories, files);
		LWJGL2UpgradeLibraryProcessor.legacyFabricLWJGL(repositories);

		repositories.maven(repo -> {
			repo.setName("Architectury");
			repo.setUrl("https://maven.architectury.dev/");
			repo.mavenContent(content -> {
				content.includeGroup("dev.architectury");
			});
		});
		repositories.maven(repo -> {
			repo.setName("Fabric");
			repo.setUrl(MirrorUtil.getFabricRepository(target));
		});

		MavenArtifactRepository mojangRepo = repositories.maven(repo -> {
			repo.setName("Mojang");
			repo.setUrl(MirrorUtil.getLibrariesBase(target));

			// Don't use the gradle module metadata. It has unintended side effects.
			repo.metadataSources(sources -> {
				sources.mavenPom();
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});
		});

		// Fallback to maven central for artifacts such as sources or javadocs that are not mirrored on Mojang's repo.
		// See: https://github.com/FabricMC/fabric-loom/issues/1032
		//
		// 这里用「紧随其后的独立仓库」而不是 Mojang 仓库上的 artifactUrls：后者已被 Gradle 标记为
		// 弃用（MavenArtifactRepository.artifactUrls 计划在 Gradle 10 移除），且没有同版本替代方法。
		// 语义上二者等价——artifactUrls 表达的就是「POM 找不到时再去这些地址找构件」，而把同一个
		// Maven Central 作为后一个仓库，解析顺序与失败回退都一致。
		//
		// 该仓库必须紧挨 Mojang 之后：Mojang 仓库在下方会按 issue #621 提前到项目自带的
		// mavenCentral 之前，这个回退仓库跟着一起提前，才能保持「Mojang → 回退 → 其它」的次序。
		MavenArtifactRepository mojangArtifactFallback = repositories.maven(repo -> {
			repo.setName("MojangArtifactFallback");
			repo.setUrl(ArtifactRepositoryContainer.MAVEN_CENTRAL_URL);

			// 与 Mojang 仓库保持同一套 metadataSources：回退只负责补上缺失的构件文件，
			// 不引入 Gradle module metadata（避免 Mojang 制品被 GMM 重定向）。
			repo.metadataSources(sources -> {
				sources.mavenPom();
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});
		});
		repositories.maven(repo -> {
			repo.setName("Forge");
			repo.setUrl("https://maven.minecraftforge.net/");

			repo.content(descriptor -> {
				// Only include these groups to avoid slowing down/hanging the build,
				// or downloading incorrect artifacts.
				for (String group : FORGE_GROUPS) {
					descriptor.includeGroupAndSubgroups(group);
				}
			});
			repo.metadataSources(sources -> {
				sources.mavenPom();
				// forge 上大量制品只有 jar/zip 没有 POM（如 de.oceanlabs.mcp 的 MCP 映射数据、
				// 各 classifier 产物），只查 POM 会漏掉它们
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});
		});

		// If a mavenCentral repo is already defined, remove the mojang repo and add it back before the mavenCentral repo so that it will be checked first.
		// See: https://github.com/FabricMC/fabric-loom/issues/621
		ArtifactRepository mavenCentral = repositories.findByName(ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME);

		if (mavenCentral != null) {
			// 先移出、再按「Mojang → 回退」的次序整体插回 mavenCentral 之前。
			// 只移动 Mojang 而把回退仓库留在原处会让次序变成「Mojang → …其它仓库… → 回退」，
			// 那就破坏了 issue #1032 要的「Mojang 查不到立刻去 Central 取构件」。
			repositories.remove(mojangArtifactFallback);
			repositories.remove(mojangRepo);

			final int index = repositories.indexOf(mavenCentral);
			repositories.add(index, mojangArtifactFallback);
			repositories.add(index, mojangRepo);
		}

		repositories.mavenCentral();
	}

	private void declareLocalRepositories(RepositoryHandler repositories, LoomFiles files) {
		repositories.maven(repo -> {
			repo.setName("LoomLocalRemappedMods");
			repo.setUrl(files.getRemappedModCache());
		});

		repositories.maven(repo -> {
			repo.setName("LoomGlobalMinecraft");
			repo.setUrl(files.getGlobalMinecraftRepo());
		});

		repositories.maven(repo -> {
			repo.setName("LoomLocalMinecraft");
			repo.setUrl(files.getLocalMinecraftRepo());
		});

		repositories.maven(repo -> {
			repo.setName("LoomTransformedForgeDependencies");
			repo.setUrl(files.getForgeDependencyRepo());
		});
	}

	public static void setupForLegacyVersions(RepositoryHandler repositories) {
		// 1.4.7 contains an LWJGL version with an invalid maven pom, set the metadata sources to not use the pom for this version.
		repositories.named("Mojang", MavenArtifactRepository.class, repo -> {
			repo.metadataSources(sources -> {
				// Only use the maven artifact and not the pom or gradle metadata.
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});
		});
	}

	public static void forceLWJGLFromMavenCentral(RepositoryHandler repositories) {
		if (repositories.findByName("MavenCentralLWJGL") != null) {
			// Already applied.
			return;
		}

		// Force LWJGL from central, as it contains all the platform natives.
		MavenArtifactRepository central = repositories.maven(repo -> {
			repo.setName("MavenCentralLWJGL");
			repo.setUrl(ArtifactRepositoryContainer.MAVEN_CENTRAL_URL);
			repo.content(content -> {
				content.includeGroup("org.lwjgl");
			});
		});

		repositories.exclusiveContent(repository -> {
			repository.forRepositories(central);
			repository.filter(filter -> {
				filter.includeGroup("org.lwjgl");
			});
		});
	}
}
