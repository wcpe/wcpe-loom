/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

package net.fabricmc.loom.configuration.mods;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.objectweb.asm.ClassVisitor;

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.visitor.ClassTweakerVisitor;
import net.fabricmc.classtweaker.visitors.ForwardingVisitor;
import net.fabricmc.loom.configuration.mods.dependency.ModDependency;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.tinyremapper.TinyRemapper;

public record AccessWidenerAnalyzeVisitorProvider(ClassTweaker accessWidener) implements TinyRemapper.AnalyzeVisitorProvider {
	/**
	 * 从若干 mod jar 合并出 access widener 分析器.
	 *
	 * <p>只读取各 jar 内的 AW 内容，不触碰 {@link ModDependency} 的任何缓存或项目状态，
	 * 因此可在执行期调用（L3 任务路径）。
	 */
	public static AccessWidenerAnalyzeVisitorProvider createFromPaths(String namespace, List<Path> modJars, ModPlatform platform) throws IOException {
		ClassTweaker accessWidener = ClassTweaker.newInstance();
		accessWidener.visitHeader(namespace);

		for (Path modJar : modJars) {
			final var accessWidenerData = AccessWidenerUtils.readAccessWidenerData(modJar, platform);

			if (accessWidenerData == null) {
				continue;
			}

			final var reader = ClassTweakerReader.create(new ClassTweakerRemapFilter(accessWidener));
			reader.read(accessWidenerData.content());
		}

		return new AccessWidenerAnalyzeVisitorProvider(accessWidener);
	}

	@Override
	public ClassVisitor insertAnalyzeVisitor(int mrjVersion, String className, ClassVisitor next) {
		return accessWidener.createClassVisitor(Constants.ASM_VERSION, next, null);
	}

	private static class ClassTweakerRemapFilter extends ForwardingVisitor {
		private ClassTweakerRemapFilter(ClassTweakerVisitor... visitors) {
			super(visitors);
		}

		@Override
		public void visitEnumExtension(String owner, String addedConstant, boolean transitive) {
			// Ignore enum extensions, as they are not required exist in the remapping context
		}
	}
}
