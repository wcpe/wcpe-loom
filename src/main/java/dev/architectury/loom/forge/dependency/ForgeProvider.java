/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2023 FabricMC
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

package dev.architectury.loom.forge.dependency;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ModPlatform;

public class ForgeProvider extends DependencyProvider {
	private static final String USERDEV_CLASSIFIER = "userdev";
	private static final String USERDEV3_CLASSIFIER = "userdev3";

	/**
	 * 1.12.2 上 {@code userdev3} 分类器的起始 Forge build 号.
	 *
	 * <p>依据是对 maven.minecraftforge.net 制品列表的实测：14.23.5.2847 及以下只发布
	 * {@code :userdev}，14.23.5.2851 起只发布 {@code :userdev3}（2848-2850、2853 两者皆缺），
	 * 其余所有 MC 版本一律只有 {@code :userdev}。因此只有 1.12.2 且 build 号不小于该值时
	 * 才改用 {@code userdev3}。
	 */
	private static final int USERDEV3_MIN_FORGE_BUILD = 2851;

	/** userdev3 的 MC 版本，即上述分界规则唯一适用的 Minecraft 版本. */
	private static final String USERDEV3_MINECRAFT_VERSION = "1.12.2";

	private final ModPlatform platform;
	private ForgeVersion version = new ForgeVersion(null);
	private File globalCache;

	public ForgeProvider(Project project) {
		super(project);
		platform = getExtension().getPlatform().get();
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		version = new ForgeVersion(dependency.getResolvedVersion());
		addDependency(dependency.getDepString() + ":" + getForgeUserdevClassifier(), Constants.Configurations.FORGE_USERDEV);
		addDependency(dependency.getDepString() + ":installer", Constants.Configurations.FORGE_INSTALLER);
	}

	/**
	 * {@return 该 Forge 版本对应的 userdev 分类器}.
	 *
	 * <p>Forge 在 1.12.2 的 14.23.5.2851 前后更换了 userdev 的发布格式：旧 build 发布 FG2 形态的
	 * {@code :userdev}，新 build 发布 FG2.3 过渡形态的 {@code :userdev3}（两者不并存，见
	 * {@link #USERDEV3_MIN_FORGE_BUILD}）。build 号解析失败时保守回退 {@code :userdev}。
	 */
	private String getForgeUserdevClassifier() {
		if (!USERDEV3_MINECRAFT_VERSION.equals(version.getMinecraftVersion())
				|| parseForgeBuild(version.getForgeVersion()) < USERDEV3_MIN_FORGE_BUILD) {
			return USERDEV_CLASSIFIER;
		}

		return USERDEV3_CLASSIFIER;
	}

	/**
	 * 解析 Forge 版本末段的 build 号（如 {@code 14.23.5.2860} → 2860）.
	 *
	 * @return build 号，无法解析时返回 -1
	 */
	private static int parseForgeBuild(String forgeVersion) {
		final int dotIndex = forgeVersion.lastIndexOf('.');

		if (dotIndex == -1) {
			return -1;
		}

		try {
			return Integer.parseInt(forgeVersion.substring(dotIndex + 1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public ForgeVersion getVersion() {
		return version;
	}

	public boolean usesMojangAtRuntime() {
		return platform == ModPlatform.NEOFORGE || version.getMajorVersion() >= Constants.Forge.MIN_USE_MOJANG_NS_VERSION;
	}

	public File getGlobalCache() {
		if (globalCache == null) {
			Objects.requireNonNull(version.getCombined(), "Forge provider version is null when trying to get project directory");
			globalCache = getMinecraftProvider().dir(platform.id() + "/" + version.getCombined());
			globalCache.mkdirs();
		}

		return globalCache;
	}

	@Override
	public String getTargetConfig() {
		return platform == ModPlatform.NEOFORGE ? Constants.Configurations.NEOFORGE : Constants.Configurations.FORGE;
	}

	/**
	 * {@return the Forge cache directory}.
	 *
	 * @param project the project
	 */
	public static Path getForgeCache(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final ModPlatform platform = extension.getPlatform().get();
		final String version = extension.getForgeProvider().getVersion().getCombined();
		Objects.requireNonNull(version, "Forge provider version is null when trying to get project directory");
		return LoomGradleExtension.get(project).getMinecraftProvider()
				.dir(platform.id() + "/" + version).toPath();
	}

	public static final class ForgeVersion {
		private final String combined;
		private final String minecraftVersion;
		private final String forgeVersion;
		private final int majorVersion;

		public ForgeVersion(String combined) {
			this.combined = combined;

			if (combined == null) {
				this.minecraftVersion = "NO_VERSION";
				this.forgeVersion = "NO_VERSION";
				this.majorVersion = -1;
				return;
			}

			int hyphenIndex = combined.indexOf('-');

			if (hyphenIndex != -1) {
				this.minecraftVersion = combined.substring(0, hyphenIndex);
				this.forgeVersion = combined.substring(hyphenIndex + 1);
			} else {
				this.minecraftVersion = "NO_VERSION";
				this.forgeVersion = combined;
			}

			int dotIndex = forgeVersion.indexOf('.');
			int major;

			try {
				if (dotIndex >= 0) {
					major = Integer.parseInt(forgeVersion.substring(0, dotIndex));
				} else {
					major = Integer.parseInt(forgeVersion);
				}
			} catch (NumberFormatException e) {
				major = -1;
			}

			this.majorVersion = major;
		}

		public String getCombined() {
			return combined;
		}

		public String getMinecraftVersion() {
			return minecraftVersion;
		}

		public String getForgeVersion() {
			return forgeVersion;
		}

		public int getMajorVersion() {
			return majorVersion;
		}
	}
}
