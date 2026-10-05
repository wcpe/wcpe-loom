/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

package net.fabricmc.loom.configuration.providers.mappings.extras.annotations;

import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.TypeAnnotationNode;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.api.TrRemapper;

public record AnnotationsData(Map<String, ClassAnnotationData> classes, String namespace) {
	public static final Gson GSON = new GsonBuilder()
			.disableHtmlEscaping()
			.setFieldNamingStrategy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.enableComplexMapKeySerialization()
			.registerTypeAdapter(TypeAnnotationNode.class, new TypeAnnotationNodeSerializer())
			.registerTypeAdapter(AnnotationNode.class, new AnnotationNodeSerializer())
			.registerTypeAdapterFactory(new SkipEmptyTypeAdapterFactory())
			.create();
	private static final Type LIST_TYPE = new TypeToken<List<AnnotationNode>>() { }.getType();
	private static final int CURRENT_VERSION = 1;

	public AnnotationsData {
		if (namespace == null) {
			namespace = MappingsNamespace.NAMED.toString();
		}
	}

	public AnnotationsData(String namespace) {
		this(new LinkedHashMap<>(), namespace);
	}

	public AnnotationsData(AnnotationsData other) {
		this(copyMap(other.classes, ClassAnnotationData::new), other.namespace);
	}

	public static AnnotationsData read(Reader reader) {
		JsonObject json = GSON.fromJson(reader, JsonObject.class);
		checkVersion(json);
		return GSON.fromJson(json, AnnotationsData.class);
	}

	public static List<AnnotationsData> readList(Reader reader) {
		JsonObject json = GSON.fromJson(reader, JsonObject.class);
		checkVersion(json);
		JsonElement values = json.get("values");

		if (values == null || values.isJsonNull()) {
			return List.of(GSON.fromJson(json, AnnotationsData.class));
		}

		return GSON.fromJson(values, LIST_TYPE);
	}

	private static void checkVersion(JsonObject json) {
		if (!json.has("version")) {
			throw new JsonSyntaxException("Missing annotations version");
		}

		int version = json.getAsJsonPrimitive("version").getAsInt();

		if (version != CURRENT_VERSION) {
			throw new JsonSyntaxException("Invalid annotations version " + version + ". Try updating loom");
		}
	}

	public JsonObject toJson() {
		JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
		JsonObject result = new JsonObject();
		result.addProperty("version", CURRENT_VERSION);
		result.asMap().putAll(json.asMap());
		return result;
	}

	public static JsonObject listToJson(List<AnnotationsData> annotationsData) {
		if (annotationsData.size() == 1) {
			return annotationsData.getFirst().toJson();
		}

		JsonObject result = new JsonObject();
		result.addProperty("version", CURRENT_VERSION);
		result.add("values", GSON.toJsonTree(annotationsData));
		return result;
	}

	static <K, V> Map<K, V> copyMap(Map<K, V> map, UnaryOperator<V> valueCopier) {
		Map<K, V> result = LinkedHashMap.newLinkedHashMap(map.size());
		map.forEach((key, value) -> result.put(key, valueCopier.apply(value)));
		return result;
	}

	static List<AnnotationNode> copyAnnotations(List<AnnotationNode> annotations) {
		List<AnnotationNode> result = new ArrayList<>(annotations.size());

		for (AnnotationNode annotation : annotations) {
			AnnotationNode newAnnotation = new AnnotationNode(annotation.desc);
			annotation.accept(newAnnotation);
			result.add(newAnnotation);
		}

		return result;
	}

	static List<TypeAnnotationNode> copyTypeAnnotations(List<TypeAnnotationNode> annotations) {
		List<TypeAnnotationNode> result = new ArrayList<>(annotations.size());

		for (TypeAnnotationNode annotation : annotations) {
			TypeAnnotationNode newAnnotation = new TypeAnnotationNode(annotation.typeRef, annotation.typePath, annotation.desc);
			annotation.accept(newAnnotation);
			result.add(newAnnotation);
		}

		return result;
	}

	public AnnotationsData merge(AnnotationsData other) {
		if (!namespace.equals(other.namespace)) {
			throw new IllegalArgumentException("Cannot merge annotations from namespace " + other.namespace + " into annotations from namespace " + this.namespace);
		}

		Map<String, ClassAnnotationData> newClassData = new LinkedHashMap<>(classes);
		other.classes.forEach((key, value) -> newClassData.merge(key, value, ClassAnnotationData::merge));
		return new AnnotationsData(newClassData, namespace);
	}

	public AnnotationsData remap(TinyRemapper remapper, MappingTree mappingTree, String fromNamespace, String toNamespace, String newNamespace) {
		return new AnnotationsData(
				remapMap(
						classes,
						entry -> remapper.getEnvironment().getRemapper().map(entry.getKey()),
						entry -> entry.getValue().remap(entry.getKey(), remapper, mappingTree, namespace, toNamespace)
				),
				newNamespace
		);
	}

	/**
	 * {@return 成员名交给 {@code remapper} 自行解决的重映射}.
	 *
	 * <p>保留这个两参重载是<b>契约要求</b>，不是历史包袱：调用方可以自己
	 * {@code remapper.readClassPath(目标类)}，此时 tiny-remapper 认得那些成员，成员名就由它解决
	 * （{@code AnnotationsDataRemapTest} 覆盖的正是这条路径）。生产链路不这么做——它没有读 MC 的类，
	 * 于是走 {@link #remap(TinyRemapper, MappingTree, String, String, String)} 把映射树一并传进来。
	 */
	public AnnotationsData remap(TinyRemapper remapper, String newNamespace) {
		return remap(remapper, null, namespace, newNamespace, newNamespace);
	}

	/**
	 * {@return 成员在 {@code toNamespace} 下的名字；两处都答不出时原样返回}.
	 *
	 * <p><b>先问映射树</b>：{@code TrRemapper.mapMethodName(owner, name, desc)} 需要先「知道」owner 这个类的
	 * 成员——而生产链路上那个 remapper 是
	 * {@code TinyRemapperService.createSimple(..., ClasspathLibraries.EXCLUDE)} 建的，从未读过 Minecraft 的
	 * 类，于是对任何成员名都原样返回（实测：同一个 remapper 上 {@code mapMethodDesc} 正常、{@code mapMethodName}
	 * 不变）。要让它知道就得把整个 MC jar 读进索引——为几个成员名付这个代价不划算。映射树本来就是这条链的
	 * 权威来源（类名与描述符也在用它），成员名直接问它即可：既准确，又完全不读 MC jar。
	 *
	 * <p><b>remapper 仍是回退</b>：调用方若已读过目标类，它就答得出来——上文那个两参重载走的就是这条。
	 * 所以两边都不放弃，顺序是「树答得出用树，答不出再问 remapper」。
	 */
	static String mapMemberName(@Nullable TinyRemapper remapper, @Nullable MappingTree mappingTree, String fromNamespace, String toNamespace, String owner, String name, String desc, boolean method) {
		String fromTree = mapMemberNameFromTree(mappingTree, fromNamespace, toNamespace, owner, name, desc, method);

		if (fromTree != null) {
			return fromTree;
		}

		if (remapper == null) {
			return name;
		}

		TrRemapper trRemapper = remapper.getEnvironment().getRemapper();
		String fromRemapper = method
				? trRemapper.mapMethodName(owner, name, desc)
				: trRemapper.mapFieldName(owner, name, desc);
		return fromRemapper != null ? fromRemapper : name;
	}

	/**
	 * {@return 映射树给出的成员名；树里查不到（或没有树）时为 {@code null}}，
	 * 用 {@code null} 与「名字恰好不变」区分开，好让调用方决定是否回退到 remapper。
	 */
	@Nullable
	private static String mapMemberNameFromTree(@Nullable MappingTree mappingTree, String fromNamespace, String toNamespace, String owner, String name, String desc, boolean method) {
		if (mappingTree == null) {
			return null;
		}

		// 用 getNamespaceId 而不是按 getDstNamespaces 的下标猜：本树是 official 为 src、[intermediary, named]
		// 为 dst，而注解数据里的名字在 intermediary 命名空间——它在 dst 里，索引 0。按 src 名直接查
		// getClass(owner) 会落空（树按 src 名建索引），必须带上命名空间 id 查。
		int srcId = mappingTree.getNamespaceId(fromNamespace);
		int dstId = mappingTree.getNamespaceId(toNamespace);

		if (srcId == MappingTreeView.NULL_NAMESPACE_ID || dstId == MappingTreeView.NULL_NAMESPACE_ID) {
			return null;
		}

		MappingTree.ClassMapping classMapping = mappingTree.getClass(owner, srcId);

		if (classMapping == null) {
			return null;
		}

		// 必须带 srcId：不带命名空间的重载按 **src**（本树是 official，名字形如 a/b/c）匹配，
		// 而注解数据里的名字在 intermediary 命名空间，直接查会落空。
		MappingTree.MemberMapping memberMapping = method
				? classMapping.getMethod(name, desc, srcId)
				: classMapping.getField(name, desc, srcId);

		if (memberMapping == null) {
			return null;
		}

		return memberMapping.getDstName(dstId);
	}

	static AnnotationNode remap(AnnotationNode node, TinyRemapper remapper) {
		AnnotationNode remapped = new AnnotationNode(remapper.getEnvironment().getRemapper().mapDesc(node.desc));
		node.accept(remapper.createAnnotationRemapperVisitor(remapped, node.desc));
		return remapped;
	}

	static TypeAnnotationNode remap(TypeAnnotationNode node, TinyRemapper remapper) {
		TypeAnnotationNode remapped = new TypeAnnotationNode(node.typeRef, node.typePath, remapper.getEnvironment().getRemapper().mapDesc(node.desc));
		node.accept(remapper.createAnnotationRemapperVisitor(remapped, node.desc));
		return remapped;
	}

	static <K, V> Map<K, V> remapMap(Map<K, V> map, Function<Map.Entry<K, V>, K> keyRemapper, Function<Map.Entry<K, V>, V> valueRemapper) {
		Map<K, V> result = LinkedHashMap.newLinkedHashMap(map.size());

		for (Map.Entry<K, V> entry : map.entrySet()) {
			if (result.put(keyRemapper.apply(entry), valueRemapper.apply(entry)) != null) {
				throw new IllegalStateException("Remapping annotations resulted in duplicate key: " + keyRemapper.apply(entry));
			}
		}

		return result;
	}

	@Nullable
	public static AnnotationsData getRemappedAnnotations(MappingsNamespace targetNamespace, MappingConfiguration mappingConfiguration, Project project, ServiceFactory serviceFactory, String newNamespace) {
		List<AnnotationsData> datas = mappingConfiguration.getAnnotationsData();

		if (datas.isEmpty()) {
			return null;
		}

		AnnotationsData result = datas.getFirst().remap(targetNamespace, mappingConfiguration, project, serviceFactory, newNamespace);

		for (int i = 1; i < datas.size(); i++) {
			result = result.merge(datas.get(i).remap(targetNamespace, mappingConfiguration, project, serviceFactory, newNamespace));
		}

		return result;
	}

	private AnnotationsData remap(MappingsNamespace targetNamespace, MappingConfiguration mappingConfiguration, Project project, ServiceFactory serviceFactory, String newNamespace) {
		if (namespace.equals(targetNamespace.toString())) {
			return this;
		}

		TinyRemapperService remapperService = serviceFactory.get(TinyRemapperService.createSimple(
				project,
				project.provider(() -> namespace),
				project.provider(() -> newNamespace),
				TinyRemapperService.ClasspathLibraries.EXCLUDE
		));
		TinyRemapper remapper = remapperService.getTinyRemapperForRemapping();
		// 成员名走映射树（见 mapMemberName 的说明）：remapper 只负责类名与描述符
		MappingTree mappingTree = mappingConfiguration.getMappingsService(project, serviceFactory).getMappingTree();

		return remap(remapper, mappingTree, namespace, newNamespace, newNamespace);
	}
}
