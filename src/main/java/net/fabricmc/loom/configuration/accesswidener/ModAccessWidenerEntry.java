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

package net.fabricmc.loom.configuration.accesswidener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.visitor.ClassTweakerVisitor;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.classtweaker.visitors.TransitiveOnlyFilter;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.LazyCloseable;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.ModEnvironment;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * {@link AccessWidenerEntry} implementation for a {@link FabricModJson}.
 *
 * <h2>为什么这里不持有 {@link FabricModJson}</h2>
 * 本 record 的实例会随 {@code AccessWidenerJarProcessor.Spec} 进入
 * {@code ProcessMinecraftJarTask} 的任务状态（spec 是该任务的 {@code @Internal} 属性，但 {@code @Internal}
 * 只免掉指纹，不免掉序列化）。任务状态整体必须能写进配置缓存，而 {@link FabricModJson} 持有的东西
 * 恰恰都不是配置缓存能序列化的值：
 * <ul>
 *   <li>元数据本体是 Gson 的 {@code JsonObject}，其内部映射类型是 {@code com.google.gson.internal.LinkedTreeMap}；</li>
 *   <li>资源来源形状不定——jar 路径、源码集，甚至跨项目数据重建出来的 lambda 闭包。</li>
 * </ul>
 * 实测（Gradle 9.5/9.6，{@code org.gradle.configuration-cache=true} 且 {@code problems=fail}）：
 * 带着依赖 mod 的 access widener 构建时，存储阶段直接以
 * {@code value '{...}' is not assignable to 'com.google.gson.internal.LinkedTreeMap'} 失败，
 * 整个构建无法通过配置缓存。因此这里只保留链真正需要的纯值：元数据身份（id 与 schema 版本）、
 * 规则文件路径、环境、以及「是否只取 transitive 规则」标志，外加**规则文件本身的字节**。
 *
 * <h2>为什么规则文件字节可以在配置期读</h2>
 * 依赖 mod 的元数据本来就是从同一份来源读出来的（jar 依赖读 jar 条目，项目依赖读
 * {@code LoomProjectData} 已复制的资源），所以「配置期读不到、只有执行期才读得到」的情形不存在；
 * 本包的 {@code LocalAccessWidenerEntry} 也早就在建 spec 时读文件并在缺失时直接失败。
 * 把字节在配置期取出来，链在执行期就不需要再碰任何一句「怎么读」的上下文。
 *
 * <h2>为什么身份是显式写出来的</h2>
 * {@code spec.hashCode()} 是 loom 既有协议里的链身份（缓存值与产物路径都由它派生，
 * 见 {@code MinecraftJarProcessorManager.getCacheValue()}），所以改造不能让它漂移。
 * 改造前的身份是
 * {@code Objects.hash(FabricModJson.hashCode() = Objects.hash(id, schemaVersion), path, environment, transitiveOnly)}，
 * 下面的 {@link #hashCode()} 逐位复现这一串。规则文件字节只是载荷、不进身份：把它算进去会让
 * 「同一个 mod 的同一份规则文件、内容不同」被当成两条链，而既有协议只看 mod 身份。
 *
 * @param modId mod 的 id（原 {@code FabricModJson.getId()}）
 * @param modSchemaVersion mod 元数据的 schema 版本（原 {@code FabricModJson.getVersion()}，只参与身份）
 * @param path 规则文件在 mod 内的路径
 * @param environment 规则适用的环境
 * @param transitiveOnly 是否只取 {@code transitive-} 规则
 * @param data 规则文件内容
 */
public record ModAccessWidenerEntry(String modId, int modSchemaVersion, String path, ModEnvironment environment,
		boolean transitiveOnly, byte[] data) implements AccessWidenerEntry {
	public static List<ModAccessWidenerEntry> readAll(FabricModJson modJson, boolean transitiveOnly) {
		var entries = new ArrayList<ModAccessWidenerEntry>();

		for (Map.Entry<String, ModEnvironment> entry : modJson.getClassTweakers().entrySet()) {
			entries.add(new ModAccessWidenerEntry(modJson.getId(), modJson.getVersion(), entry.getKey(), entry.getValue(),
					transitiveOnly, read(modJson, entry.getKey())));
		}

		return Collections.unmodifiableList(entries);
	}

	/** {@return 规则文件内容} 读不到时按「配置期就失败」处理，与 {@code LocalAccessWidenerEntry} 的缺失检查同源. */
	private static byte[] read(FabricModJson modJson, String path) {
		try {
			return modJson.getSource().read(path);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read access widener %s of mod %s".formatted(path, modJson.getId()), e);
		}
	}

	/**
	 * 身份与改造前逐位一致.
	 *
	 * <p>改造前的身份来自 record 自动生成的实现，合并方式是<b>从 0 开始</b>的 31 折：
	 * {@code result = 0; result = 31 * result + h(i)}，与 {@code Objects.hash(...)} 的<b>从 1 开始</b>
	 * （{@code result = 1; ...}）差着最前面一个 ×31。这里必须按前者的写法显式复现：
	 * 用 {@code Objects.hash(第一个组件, 第二个, ...)} 铺开整条链会得到另一个数，
	 * 而 {@code spec.hashCode()} 是产物路径与缓存值的来源（见类注释），差一个数就意味着所有工作区
	 * 都要重新处理一遍 Minecraft jar。
	 */
	@Override
	public int hashCode() {
		// 第一个组件是原来的 FabricModJson，它自己的 hashCode 是 Objects.hash(id, schemaVersion)
		int result = Objects.hash(modId, modSchemaVersion);
		result = 31 * result + (path == null ? 0 : path.hashCode());
		result = 31 * result + (environment == null ? 0 : environment.hashCode());
		result = 31 * result + Boolean.hashCode(transitiveOnly);
		return result;
	}

	/**
	 * 与 {@link #hashCode()} 同源的等值判定：只比身份，不比规则文件字节.
	 *
	 * <p>刻意与 {@link #hashCode()} 用同一组字段：record 自动生成的实现会把 {@code data} 按引用比较，
	 * 那既与身份不一致，又让两条内容相同的链永远不相等。
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (!(o instanceof ModAccessWidenerEntry other)) {
			return false;
		}

		return modSchemaVersion == other.modSchemaVersion
				&& transitiveOnly == other.transitiveOnly
				&& Objects.equals(modId, other.modId)
				&& Objects.equals(path, other.path)
				&& Objects.equals(environment, other.environment);
	}

	@Override
	public String toString() {
		// 默认实现会把字节打成 [B@1a2b3c4d（既不是内容也不可读），这里给出链上真正有意义的身份
		return "ModAccessWidenerEntry[%s:%s, environment=%s, transitiveOnly=%s, bytes=%d]"
				.formatted(modId, path, environment, transitiveOnly, data.length);
	}

	@Override
	public @Nullable String mappingId() {
		return transitiveOnly ? modId : null;
	}

	@Override
	public String getSortKey() {
		return modId + ":" + path;
	}

	@Override
	public void read(ClassTweakerVisitor visitor, LazyCloseable<TinyRemapper> remapper, MappingsNamespace productionNamespace) throws IOException {
		if (transitiveOnly) {
			// Filter for only transitive rules
			visitor = new TransitiveOnlyFilter(visitor);
		}

		final ClassTweakerReader.Header header = ClassTweakerReader.readHeader(data);

		if (!header.getNamespace().equals(MappingsNamespace.NAMED.toString())) {
			// Remap the AW if needed
			visitor = getRemapper(visitor, remapper.get(), productionNamespace);
		}

		var reader = ClassTweakerReader.create(visitor);
		reader.read(data);
	}

	@Override
	public void readOfficial(ClassTweakerVisitor visitor) throws IOException {
		if (transitiveOnly) {
			// Filter for only transitive rules
			visitor = new TransitiveOnlyFilter(visitor);
		}

		final ClassTweakerReader.Header header = ClassTweakerReader.readHeader(data);

		if (!header.getNamespace().equals(MappingsNamespace.OFFICIAL.toString())) {
			throw new IOException("Expected official namespace for access widener entry, found: " + header.getNamespace() + " in mod: " + modId);
		}

		var reader = ClassTweakerReader.create(visitor);
		reader.read(data);
	}

	private static ClassTweakerRemapperVisitor getRemapper(ClassTweakerVisitor visitor, TinyRemapper tinyRemapper, MappingsNamespace productionNamespace) {
		return new ClassTweakerRemapperVisitor(
				visitor,
				tinyRemapper.getEnvironment().getRemapper(),
				productionNamespace.toString(),
				MappingsNamespace.NAMED.toString()
		);
	}
}
