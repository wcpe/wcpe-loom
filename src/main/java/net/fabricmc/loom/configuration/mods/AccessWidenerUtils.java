/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.commons.Remapper;

import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.ClassTweakerWriter;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.ModEnvironment;
import net.fabricmc.loom.util.fmj.ModMetadataFabricModJson;

public class AccessWidenerUtils {
	/**
	 * Remap a mods access widener from intermediary to named, so that loader can apply it in our dev-env.
	 */
	public static byte[] remapAccessWidener(byte[] input, Remapper remapper, String fromNamespace, String toNamespace) {
		int version = ClassTweakerReader.readVersion(input);

		ClassTweakerWriter writer = ClassTweakerWriter.create(version);
		ClassTweakerRemapperVisitor awRemapper = new ClassTweakerRemapperVisitor(
				writer,
				remapper,
				fromNamespace,
				toNamespace
		);
		ClassTweakerReader reader = ClassTweakerReader.create(awRemapper);
		reader.read(input);
		return writer.getOutput();
	}

	/**
	 * 读取一个 mod jar 里声明的**全部** class tweaker（access widener）.
	 *
	 * <p>返回空列表表示「这个 jar 不需要处理」——不是 mod jar、Forge 系上不是 mods.toml 形态，
	 * 或压根没有声明。调用方因此不必再区分 null 与空集合，只需判断是否为空。
	 *
	 * <p>为什么是列表而不是单条：schema v2 的 {@code classTweakers} 本来就是
	 * 「jar 内路径 → 生效环境」的映射，一个 mod 声明多条是合法的（见
	 * {@code FabricModJsonV2#getClassTweakers}）。把多条压成一条只有两种下场：要么丢掉后面的
	 * 文件（它们的声音直接被忽略），要么把内容拼成一份（环境随之丢失，只在客户端生效的规则
	 * 会跑到服务端去）。所以这里逐条返回，环境随条目一起带出。
	 *
	 * @param inputJar 待读取的 mod jar
	 * @param platform 平台；决定「什么算 mod jar」以及是否接受 fabric.mod.json
	 * @return 各条声明；有序（按 jar 内路径），无声明时为空列表
	 */
	public static List<AccessWidenerData> readAccessWideners(Path inputJar, ModPlatform platform) throws IOException {
		if (!FabricModJsonFactory.isModJar(inputJar, platform)) {
			return List.of();
		}

		final FabricModJson fabricModJson = FabricModJsonFactory.createFromZip(inputJar);

		if (platform.isForgeLike() && !(fabricModJson instanceof ModMetadataFabricModJson)) {
			// Ignore actual fabric.mod.json files on NeoForge and Forge.
			// See https://github.com/architectury/architectury-loom/issues/165.
			return List.of();
		}

		return readClassTweakers(fabricModJson);
	}

	/**
	 * 把元数据里的 {@code classTweakers} 声明逐条展开成「路径 + 环境 + 头 + 内容」.
	 *
	 * <p>结果按 jar 内路径排序：schema v2 的声明表是 {@code HashMap}（见
	 * {@code FabricModJsonV2#getConditionalConfigs}），按迭代顺序处理会让同一份元数据在不同
	 * JVM 上得到不同顺序——写回 jar 的结果虽然等价，但任何按顺序展示或比较的输出都会抖动。
	 *
	 * <p>刻意保持 public：读取逻辑是本次改动的主体，单元测试要能在不落地真实 mod jar 的
	 * 前提下直接覆盖它（与 {@code RemapModsTask#collectRemapClasspath} 同一种做法）。
	 *
	 * @param fabricModJson 已解析的元数据
	 * @return 各条声明；有序（按 jar 内路径），无声明时为空列表
	 */
	public static List<AccessWidenerData> readClassTweakers(FabricModJson fabricModJson) throws IOException {
		final Map<String, ModEnvironment> classTweakers = fabricModJson.getClassTweakers();

		if (classTweakers.isEmpty()) {
			return List.of();
		}

		final List<AccessWidenerData> declarations = new ArrayList<>(classTweakers.size());

		for (Map.Entry<String, ModEnvironment> classTweaker : classTweakers.entrySet()) {
			final String path = classTweaker.getKey();
			final byte[] content = fabricModJson.getSource().read(path);
			final ClassTweakerReader.Header header = ClassTweakerReader.readHeader(content);
			declarations.add(new AccessWidenerData(path, classTweaker.getValue(), header, content));
		}

		declarations.sort(Comparator.comparing(AccessWidenerData::path));
		return List.copyOf(declarations);
	}

	/**
	 * 单个 class tweaker 声明.
	 *
	 * <p>{@code environment} 是元数据里给这条声明写的生效环境（只写路径时缺省为
	 * {@link ModEnvironment#UNIVERSAL}）。它不参与重映射——规则文件只是名字改写，与环境无关
	 * ——但它决定这条规则该在哪一侧生效，因此必须原样带出来，不能在读取时（{@code keySet()}）丢掉。
	 *
	 * @param path 规则文件在 jar 内的路径，也是写回 jar 时的落点
	 * @param environment 该声明生效的环境
	 * @param header 规则文件头（命名空间与版本）
	 * @param content 规则文件内容
	 */
	public record AccessWidenerData(String path, ModEnvironment environment, ClassTweakerReader.Header header, byte[] content) {
	}
}
