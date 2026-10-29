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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.function.Predicate;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;

import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.cache.AtomicFiles;

/**
 * 为 legacy Forge（FG2 形态的 userdev，MC 1.7-1.12.2）生产 named 命名的 {@code forge.jar}.
 *
 * <p>它取代的是 {@code MinecraftLegacyPatchedProvider.patchForge} 里那段配置期同步执行的逻辑：
 * 复制 Forge universal jar → 重映射到 named → 删 binpatches 与签名 → 补 log4j 辅助类 →
 * 改造 CoreModManager → 写补丁版本标记。
 *
 * <h2>为什么必须搬到执行期</h2>
 * 这段逻辑重映射 Forge universal jar 时需要读 <b>Minecraft 的 client/server jar</b> 作为 classpath
 * （见 {@code remapForgeToNamed}），而 Minecraft jar 如今由执行期任务产出。留在配置期就迫使
 * 整条 vanilla 链也退回配置期——这正是 {@code MinecraftProvider.projectionBlocker()} 里那条
 * legacy Forge 判据的来历。
 *
 * <h2>产物与配置期逐字节一致的做法</h2>
 * 加工顺序与配置期完全相同，全部落在调用方给的临时文件上，最后经 {@link AtomicFiles} 原子落位：
 * 中间态从不产生第二个文件名，也不在任何时刻读写同一路径的两份内容。
 */
@CacheableTask
public abstract class GenerateLegacyForgeJarTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "generateLegacyForgeJar";

	/** 输入：Forge universal jar（还没重映射到 named 的那一份）. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getForgeUniversalJar();

	/**
	 * 输入：四列 tiny（{@code official / srg / intermediary / named}），重映射方向 {@code official -> named}.
	 *
	 * <p>可选：unobfuscated Forge 没有映射配置，此时不设置、也不做重映射
	 * （与配置期 {@code isUnobfuscatedForge()} 早返回同判据）。
	 */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getMappings();

	/**
	 * 输入：Minecraft 的 client / server jar，作为重映射的 classpath（不输出）.
	 *
	 * <p>必须是<b>未被 Forge 打过补丁</b>的干净 jar：重映射发生在打补丁之前，
	 * 那时 patched jar 尚未存在；而重映射只需要 MC 类的成员签名，干净 jar 已足够。
	 */
	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getMinecraftClasspath();

	/**
	 * 输入：可选的 {@code log4j-core-2.0-beta9.jar}.
	 *
	 * <p>旧版 Forge 依赖它的 {@code org/apache/logging/log4j/core/helpers} 包，而运行期用的是
	 * 更晚版本的 log4j，那些类已移到 {@code utils}。为了两边都工作，把 helpers 重打包进 forge.jar。
	 * 该 jar 不在依赖里时不设置。
	 */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getLog4jBeta9Jar();

	/** 要写进产物 manifest 的 Loom 补丁版本（与配置期同一个常量）. */
	@Input
	public abstract Property<String> getPatchVersion();

	/** 产物：named 命名的 {@code forge.jar}（与配置期同一条路径）. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void generate() throws Exception {
		final Path output = getOutputJar().get().getAsFile().toPath();

		// 原子发布：产物位于跨 daemon 共享的 forge 缓存目录，配置期路径同样是「临时文件 + 原子落位」
		AtomicFiles.publish(output, tmp -> {
			try {
				produce(tmp);
			} catch (Exception e) {
				throw new RuntimeException("生成 legacy forge.jar 失败", e);
			}
		});
	}

	/**
	 * 在本次独占的临时文件上完成全部加工.
	 *
	 * <p>步骤与配置期 {@code patchForge} 逐条对应，顺序不得调整：重映射必须先于删 binpatches
	 * （后者会改 jar 内容），CoreModManager 改造必须晚于重映射（重映射会重建类）。
	 */
	private void produce(Path output) throws Exception {
		final Path forgeUniversal = getForgeUniversalJar().get().getAsFile().toPath();
		getLogger().lifecycle(":patching forge");

		Files.copy(forgeUniversal, output, StandardCopyOption.REPLACE_EXISTING);

		remapToNamed(output);
		stripBinpatchesAndSignatures(output);
		repackageLog4jHelpers(output);
		patchCoreModManager(output);

		// 补丁版本标记是就绪标记：必须在原子落位之前写入临时文件，读方才能「见到即完整」
		writePatchVersion(output);

		getLogger().lifecycle(":patched forge");
	}

	/**
	 * 把 Forge universal jar 从 SRG 名重映射到 named(MCP) 名，原地替换 {@code forgeJar}.
	 *
	 * <p>dev 运行期 {@code CoreModManager.handleLaunch} 以「{@code getClassBytes("net.minecraft.world.World")}
	 * 是否命中」判定 {@code deobfuscatedEnvironment}；loom 的 Minecraft jar 类名是 named 名，
	 * 故必然判定为 true，此时 {@code DeobfuscationTransformer.shouldTransform} 对
	 * {@code net.minecraft.*} 与 {@code net.minecraftforge.*} 全部豁免（{@code EXEMPT_DEV}），
	 * 运行期不再做任何反混淆。因此 forge.jar 若不事先对齐到 named 名，它对这些 MC 符号的引用
	 * 就无人翻译：类引用报 {@code IllegalAccessError}，字段引用报 {@code NoSuchFieldError}。
	 */
	private void remapToNamed(Path forgeJar) throws Exception {
		if (!getMappings().isPresent()) {
			// unobfuscated Forge：没有映射配置，universal jar 本就是 named 名
			getLogger().lifecycle(":skip forge remap (unobfuscated forge)");
			return;
		}

		final Path tinyMappings = getMappings().get().getAsFile().toPath();
		getLogger().lifecycle(":remapping forge (TinyRemapper, official -> named), mappings=" + tinyMappings.getFileName());

		// 重映射结果先写同目录私有副本，完整后再原子替换：TinyRemapper 不能读写同一个文件，
		// 且中途失败时不能破坏已复制好的 forge.jar 内容。
		final Path remapped = forgeJar.resolveSibling(forgeJar.getFileName() + ".srg2named.tmp");
		boolean moved = false;

		try {
			// 关键：先把源 jar 复制成目标文件。OutputConsumerPath 对「不存在的路径」会建目录
			// （它把 jar 当 zipfs 挂载），随后 Files.move 就会把一个目录搬到 forgeJar 位置，
			// 外层 AtomicFiles 再落位时抛 DirectoryNotEmptyException（实测）。预创建成文件即可避开。
			Files.copy(forgeJar, remapped, StandardCopyOption.REPLACE_EXISTING);

			net.fabricmc.tinyremapper.TinyRemapper remapper = net.fabricmc.tinyremapper.TinyRemapper.newRemapper()
					.withMappings(net.fabricmc.loom.util.TinyRemapperHelper.create(tinyMappings, "official", "named", true))
					// Forge jar 只含 Forge 自己的类，它引用的 MC 类不在输入里。默认 TinyRemapper 只按「输入类的成员」
					// 建映射视图，外部类的成员引用不落到 named 名上——实测表现为类名与描述符都翻对了、
					// 只有成员名仍是 notch 名（ForgeHooksClient 引用 cdy.o，运行期 NoSuchFieldError: o）。
					.resolveMissing(true)
					.keepInputData(true)
					// 字段映射按「类名+字段名」匹配、不比较描述符：Forge 字节码里字段描述符仍是 notch 形式，
					// 与映射表里的描述符列不一致时会被判为不匹配而跳过。
					// 本方法只在 legacy 车道上被调用，这个放宽不应被其它重映射复用。
					.ignoreFieldDesc(true)
					.renameInvalidLocals(true)
					.rebuildSourceFilenames(true)
					.build();

			try (net.fabricmc.tinyremapper.OutputConsumerPath outputConsumer =
					new net.fabricmc.tinyremapper.OutputConsumerPath.Builder(remapped).build()) {
				outputConsumer.addNonClassFiles(forgeJar, net.fabricmc.tinyremapper.NonClassCopyMode.FIX_META_INF, remapper);

				// 关键：把 Minecraft jar 作为 classpath 交给 remapper（不输出）。没有这些类的定义，
				// TinyRemapper 建不出它们的方法/字段继承视图，于是只翻类名与描述符、不翻成员名。
				remapper.readClassPath(getMinecraftClasspath().getFiles().stream().map(File::toPath).toArray(Path[]::new));

				final net.fabricmc.tinyremapper.InputTag inputTag = remapper.createInputTag();
				remapper.readInputsAsync(inputTag, forgeJar);
				remapper.apply(outputConsumer, inputTag);
			} finally {
				remapper.finish();
			}

			Files.move(remapped, forgeJar, StandardCopyOption.REPLACE_EXISTING);
			moved = true;
		} finally {
			// 只在「未成功落位」时清理临时副本：move 成功后该路径已不存在，
			// 再动它只会把一个成功的重映射变成失败（实测 DirectoryNotEmptyException）。
			if (!moved) {
				Files.deleteIfExists(remapped);
			}
		}

		getLogger().lifecycle(":remapped forge");
	}

	/** 删掉 binpatches 与 Forge 的签名数据：dev 环境不需要，且签名会在改动类之后失效. */
	private void stripBinpatchesAndSignatures(Path forgeJar) throws IOException {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(forgeJar, false)) {
			// For the development environment, we need to remove the binpatches, otherwise forge will try to re-apply them
			Files.delete(fs.get().getPath("binpatches.pack.lzma"));

			// We also need to remove Forge's jar signature data
			Files.walkFileTree(fs.get().getPath("META-INF"), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
					String fileName = path.getFileName().toString();

					if (fileName.endsWith(".SF") || fileName.endsWith(".DSA") || fileName.endsWith(".RSA")
							|| fileName.endsWith(".EC") || fileName.startsWith("SIG-")) {
						Files.delete(path);
					} else if (path.getNameCount() == 2 && fileName.equals("MANIFEST.MF")) {
						Manifest manifest = new Manifest();

						try (InputStream is = Files.newInputStream(path)) {
							manifest.read(is);
						}

						for (Iterator<Attributes> it = manifest.getEntries().values().iterator(); it.hasNext(); ) {
							Attributes attrs = it.next();

							for (Iterator<Object> it2 = attrs.keySet().iterator(); it2.hasNext(); ) {
								Attributes.Name attrName = (Attributes.Name) it2.next();
								String name = attrName.toString();

								if (name.endsWith("-Digest") || name.contains("-Digest-") || name.equals("Magic")) {
									it2.remove();
								}
							}

							if (attrs.isEmpty()) it.remove();
						}

						try (OutputStream os = Files.newOutputStream(path)) {
							manifest.write(os);
						}
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	/**
	 * 把旧版 log4j 的 {@code helpers} 包重打包进 forge.jar.
	 *
	 * <p>旧版 Forge 依赖 log4j-core 2.0-beta9 的 {@code org/apache/logging/log4j/core/helpers}，
	 * 而运行期升级到更晚的 log4j（为了用 fabric-log4j-util 的 LoggerNamePatternSelector），
	 * 那些类已移到 {@code utils}。重打包让 Forge 在两边都能工作。
	 */
	private void repackageLog4jHelpers(Path forgeJar) throws IOException {
		if (!getLog4jBeta9Jar().isPresent()) {
			return;
		}

		final Path log4jBeta9 = getLog4jBeta9Jar().get().getAsFile().toPath();
		final Predicate<Path> isHelper = path -> path.startsWith("/org/apache/logging/log4j/core/helpers");

		// 直接复用配置期那套 walkFileSystems：它把 source 开成只读文件系统（避开 JDK-8291712 的
		// 共享 FS 毒化）、target 保留可写路线。自写一份复制循环会丢掉这两条语义。
		MinecraftPatchedProvider.walkFileSystems(log4jBeta9, forgeJar, isHelper, MinecraftPatchedProvider::copyReplacing);
	}

	/**
	 * 改造 {@code CoreModManager}，让第一方支持从 classpath 加载 AT / coremod / tweaker.
	 *
	 * <p>Forge 能发现 classpath 上的 mod，却不会对 AT / coremod / tweaker 做同样的事。
	 * ForgeGradle 用一大坨 hack 绕过（GradleForgeHacks 之类），这里直接把支持补进 Forge 本身。
	 */
	private void patchCoreModManager(Path forgeJar) throws IOException {
		ZipUtils.transform(forgeJar, java.util.stream.Stream.of(new Pair<>(
				dev.architectury.loom.forge.CoreModManagerTransformer.FILE,
				original -> {
					org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(original);
					org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader, 0);
					reader.accept(new dev.architectury.loom.forge.CoreModManagerTransformer(writer), 0);
					return writer.toByteArray();
				})));
	}

	/** 把 Loom 补丁版本写进 manifest：它是本产物的就绪判据（见 {@code isPatchedJarUpToDate}）. */
	private void writePatchVersion(Path forgeJar) throws IOException {
		MinecraftPatchedProvider.applyLoomPatchVersion(forgeJar);
	}
}
