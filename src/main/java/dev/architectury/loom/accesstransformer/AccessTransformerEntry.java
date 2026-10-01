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

package dev.architectury.loom.accesstransformer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import net.fabricmc.loom.util.fmj.FabricModJson;

public interface AccessTransformerEntry {
	Reader openReader() throws IOException;

	record Standalone(Path path, String hash) implements AccessTransformerEntry {
		@Override
		public Reader openReader() throws IOException {
			return Files.newBufferedReader(path);
		}

		@Override
		public String toString() {
			return path.toString();
		}
	}

	/**
	 * 来自 mod 自带的 {@code META-INF/accesstransformer.cfg}.
	 *
	 * <h2>为什么这里不持有 {@link FabricModJson}</h2>
	 * 它是 {@code AccessTransformerJarProcessor.Spec} 的元素，而 spec 会作为
	 * {@code ProcessMinecraftJarTask} 的任务状态写进配置缓存。{@link FabricModJson} 的两个部分都不可序列化：
	 * 规则内容的来源可能是源码集（{@code SourceSetSource} 持有 {@code Project} 与 {@code SourceSet}），
	 * 元数据本体是 Gson 的 {@code JsonObject}。实测（Gradle 9.5，{@code --configuration-cache}）：
	 * 带 project 自带 accesstransformer.cfg 的 Forge 工程在存储阶段直接以
	 * {@code cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject'} 失败。
	 * 规则内容本来就已在 {@code buildSpec} 里读过一遍（为了算 hash），故这里直接持有它。
	 *
	 * <h2>为什么身份是显式写出来的</h2>
	 * 改造前的身份来自 record 自动生成的实现（对 [{@code FabricModJson.hashCode()}, hash] 做
	 * <b>从 0 开始</b>的 31 折），{@code spec.hashCode()} 是处理产物路径与缓存值的来源，
	 * 不能因为改造而漂移（{@code AccessTransformerJarProcessorTest} 钉住了这个值）。
	 * 显式写法必须与 record 的合并方式一致：{@code Objects.hash(...)} 是「从 1 开始」，会得到另一个数。
	 *
	 * @param modId mod 的 id（原 {@code FabricModJson.getId()}）
	 * @param modSchemaVersion mod 元数据的 schema 版本（原 {@code FabricModJson.getVersion()}，只参与身份）
	 * @param data 规则文件内容
	 * @param hash 规则内容的 sha256（只参与身份，与改造前同源）
	 */
	record Mod(String modId, int modSchemaVersion, byte[] data, String hash) implements AccessTransformerEntry {
		@Override
		public Reader openReader() throws IOException {
			return new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8);
		}

		/** 身份与改造前逐位一致（合并方式见 record 的注释：从 0 开始的 31 折）. */
		@Override
		public int hashCode() {
			int result = Objects.hash(modId, modSchemaVersion);
			result = 31 * result + (hash == null ? 0 : hash.hashCode());
			return result;
		}

		/** 与 {@link #hashCode()} 同源的等值判定：只比身份，不比规则文件字节. */
		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}

			if (!(o instanceof Mod other)) {
				return false;
			}

			return modSchemaVersion == other.modSchemaVersion
					&& Objects.equals(modId, other.modId)
					&& Objects.equals(hash, other.hash);
		}

		@Override
		public String toString() {
			return "AccessTransformerEntry.Mod[%s, hash=%s]".formatted(modId, hash);
		}
	}
}
