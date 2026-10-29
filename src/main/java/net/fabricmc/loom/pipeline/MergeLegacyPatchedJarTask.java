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

import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.stitch.merge.JarMerger;

/**
 * 把 legacy Forge 的 client / server patched jar 合并成一份 merged jar.
 *
 * <p>它取代 {@code MinecraftLegacyPatchedProvider.mergeJars} 里那段配置期逻辑。
 *
 * <h2>两个易错点（与配置期逐字保持一致）</h2>
 * <ul>
 *   <li>{@code enableSyntheticParamsOffset()}：Forge 补丁产生的合成参数偏移要在这里补正，
 *       否则运行期参数注解的索引整体错位；</li>
 *   <li>合并后要过 {@code SideAnnotationMerger}：JarMerger 会写上 Fabric 风格的 Sided 注解，
 *       而 Forge 补丁自己也写了一套。保留后者（它不需要额外的 Forge 依赖），
 *       把前者中非冗余的转换成 Forge 形式。</li>
 * </ul>
 */
@CacheableTask
public abstract class MergeLegacyPatchedJarTask extends DefaultTask {
	/** 任务名：消费侧（本仓库的测试与诊断）按它引用产出任务. */
	public static final String NAME = "mergeLegacyPatchedJar";

	/** 输入：打完补丁的 client jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getClientPatchedJar();

	/** 输入：打完补丁的 server jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getServerPatchedJar();

	/** 产物：合并后的 patched jar（与配置期同一条路径）. */
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
				throw new RuntimeException("合并 legacy patched jar 失败", e);
			}
		});
	}

	private void produce(Path output) throws Exception {
		getLogger().lifecycle(":merging jars");

		// output 是本次独占的临时文件，合并与后续类改写都在它上面完成，最后原子落位
		try (JarMerger jarMerger = new JarMerger(
				getClientPatchedJar().get().getAsFile(),
				getServerPatchedJar().get().getAsFile(),
				output.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		// The JarMerger adds Sided annotations but so do the Forge patches. The latter doesn't require extra
		// dependencies beyond Forge, so we'll keep those and convert any non-redundant Fabric ones.
		dev.architectury.loom.forge.minecraft.MinecraftLegacyPatchedProvider
				.modifySideAnnotations(output);

		getLogger().lifecycle(":merged jars");
	}
}
