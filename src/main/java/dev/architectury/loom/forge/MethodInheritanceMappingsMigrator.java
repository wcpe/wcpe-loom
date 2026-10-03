/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024-2025 FabricMC
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

package dev.architectury.loom.forge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.architectury.loom.util.collection.Multimap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * With some forge patches, methods can inherit methods from a class that is not in the mappings.
 * This migrator will try to detect all the methods that are inherited from a class that is not in the mappings,
 * see if there are different names for the same method in the mappings, and remove them.
 */
public final class MethodInheritanceMappingsMigrator implements MappingsMigrator {
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodInheritanceMappingsMigrator.class);

	private Set<Pair<String, String>> methodsToRemove;

	@Override
	public long setup(Inputs inputs) throws IOException {
		Path cacheFile = inputs.cache().resolve("method-inheritance-migrator.json");
		List<Path> jars = List.of(inputs.patchedIntermediateJar(), inputs.forgeJar(), inputs.userdevJar());
		// 本代输入的内容键：命名空间开关 + 原始 mappings + 冷分支会读的三个 jar。
		// 只用路径存在性判断缓存是否可用会让「同一份声明输入对应两种产物」——见 MappingsMigratorCache。
		final String key = MappingsMigratorCache.identity(inputs.hasSrg(), inputs.hasMojang(),
				Stream.concat(Stream.of(inputs.rawMappings()), jars.stream()).toList());
		final Set<Pair<String, String>> cached = inputs.refreshDeps() ? null : readCache(cacheFile, key);

		if (cached != null) {
			methodsToRemove = cached;
		} else {
			methodsToRemove = prepareCache(inputs.info(), inputs.rawMappings(), jars, inputs.hasSrg(), inputs.hasMojang());
			final JsonArray entries = new JsonArray();

			// 每条用 [名字, 描述符] 两元数组：缓存是「输入决定的中间数据」，形状越简单越不容易在改字段名时静默失配
			for (Pair<String, String> pair : methodsToRemove.stream()
					.sorted(Comparator.comparing(p -> p.left() + "|" + p.right())).toList()) {
				final JsonArray entry = new JsonArray();
				entry.add(pair.left());
				entry.add(pair.right());
				entries.add(entry);
			}

			MappingsMigratorCache.publish(cacheFile, key, entries);
		}

		return methodsToRemove.hashCode();
	}

	/**
	 * 读缓存里「要删除的方法」集合；内容键与本代输入不符（或缺键、旧格式、半截内容）时返回 {@code null}.
	 */
	private @Nullable Set<Pair<String, String>> readCache(Path cacheFile, String expectedKey) throws IOException {
		final JsonElement cached = MappingsMigratorCache.read(cacheFile, expectedKey, LOGGER);

		if (cached != null && !cached.isJsonArray()) {
			// entries 形状不符（例如被写成了对象）同样不可信：要么是别的东西写进来的，要么是半截内容
			LOGGER.info("迁移器缓存的内容不是方法表（{}），按未命中处理", cacheFile);
			return null;
		}

		if (cached == null) {
			return null;
		}

		final Set<Pair<String, String>> methods = new HashSet<>();

		for (JsonElement entry : cached.getAsJsonArray()) {
			if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 2) {
				LOGGER.info("迁移器缓存的条目形状不符（{}），按未命中处理", cacheFile);
				return null;
			}

			methods.add(new Pair<>(entry.getAsJsonArray().get(0).getAsString(), entry.getAsJsonArray().get(1).getAsString()));
		}

		return methods;
	}

	@Override
	public void migrate(List<MappingsEntry> entries, Consumer<String> info) throws IOException {
		for (MappingsEntry entry : entries) {
			MemoryMappingTree mappings = new MemoryMappingTree();

			try (BufferedReader reader = Files.newBufferedReader(entry.path())) {
				MappingReader.read(reader, mappings);
			}

			for (MappingTree.ClassMapping classMapping : mappings.getClasses()) {
				// TODO: Change if/when MIO supports removals again
				for (MappingTree.MethodMapping method : List.copyOf(classMapping.getMethods())) {
					var key = new Pair<>(method.getName(MappingsNamespace.INTERMEDIARY.toString()), method.getDesc(MappingsNamespace.INTERMEDIARY.toString()));

					if (methodsToRemove.contains(key)) {
						classMapping.removeMethod(method.getSrcName(), method.getSrcDesc());
					}
				}
			}

			try (Writer writer = Files.newBufferedWriter(entry.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				mappings.accept(new Tiny2FileWriter(writer, false));
			}
		}
	}

	private Set<Pair<String, String>> prepareCache(Consumer<String> logger, Path rawMappings, List<Path> jars, boolean hasSrg, boolean hasMojang) throws IOException {
		MemoryMappingTree mappings = new MemoryMappingTree();
		String patchedNs = hasSrg ? MappingsNamespace.SRG.toString() : MappingsNamespace.MOJANG.toString();

		try (BufferedReader reader = Files.newBufferedReader(rawMappings)) {
			MappingReader.read(reader, new MappingSourceNsSwitch(mappings, patchedNs));
		}

		Pair<Multimap<String, String>, Set<MethodKey>> collected = collectClassesAndMethods(jars);
		Multimap<String, String> classInheritanceMap = collected.left();
		Set<MethodKey> methods = collected.right();

		Multimap<MethodKey, MethodKey> overriddenIntermediaries = Multimap.setMultimap();

		for (MethodKey method : methods) {
			// First check if the method is in the mappings, and as a different intermediary name
			MappingTree.ClassMapping aClass = mappings.getClass(method.className());
			if (aClass == null) continue;
			MappingTree.MethodMapping aMethod = aClass.getMethod(method.name(), method.descriptor());
			if (aMethod == null) continue;
			String intermediaryClassName = aClass.getName(MappingsNamespace.INTERMEDIARY.toString());
			String intermediaryName = aMethod.getName(MappingsNamespace.INTERMEDIARY.toString());
			if (intermediaryName == null || Objects.equals(intermediaryName, method.name())) continue;

			for (String superClass : classInheritanceMap.get(method.className())) {
				MethodKey superMethodKey = new MethodKey(superClass, method.name(), method.descriptor());

				if (methods.contains(superMethodKey)) {
					MappingTree.ClassMapping sClass = mappings.getClass(superClass);

					if (sClass == null) {
						logger.accept("Method %s.%s%s is inherited from a class %s that is not in the mappings, removing it if there are more than one".formatted(method.className(), method.name(), method.descriptor(), superClass));
					} else if (sClass.getMethod(method.name(), method.descriptor()) == null) {
						logger.accept("Method %s.%s%s is inheriting a method in %s that is not in the mappings, removing it if there are more than one".formatted(method.className(), method.name(), method.descriptor(), superClass));
					} else {
						continue;
					}

					// We will collect these methods here, and remove them later
					// if there are more than intermediary name for the same method
					String intermediaryDesc = aMethod.getDesc(MappingsNamespace.INTERMEDIARY.toString());
					overriddenIntermediaries.put(superMethodKey, new MethodKey(intermediaryClassName, intermediaryName, intermediaryDesc));
				}
			}
		}

		Set<Pair<String, String>> methodsToRemove = new HashSet<>();

		for (Map.Entry<MethodKey, ? extends Collection<MethodKey>> entry : overriddenIntermediaries.entrySet()) {
			if (entry.getValue().size() >= 2) {
				// We should remove these names from the mappings
				// as the particular method is inherited by multiple different intermediary names
				for (MethodKey methodKey : entry.getValue()) {
					methodsToRemove.add(new Pair<>(methodKey.name(), methodKey.descriptor()));
					logger.accept("Removing method %s%s from the mappings".formatted(methodKey.name(), methodKey.descriptor()));
				}
			}
		}

		return methodsToRemove;
	}

	private static Pair<Multimap<String, String>, Set<MethodKey>> collectClassesAndMethods(Iterable<Path> jars) throws IOException {
		Multimap<String, String> classInheritanceMap = Multimap.setMultimap();
		Set<MethodKey> methods = new HashSet<>();
		Visitor visitor = new Visitor(Constants.ASM_VERSION, classInheritanceMap, methods);

		for (Path jar : jars) {
			try (FileSystemUtil.Delegate system = FileSystemUtil.getReadOnlyJarFileSystem(jar)) {
				for (Path fsPath : (Iterable<? extends Path>) Files.walk(system.get().getPath("/"))::iterator) {
					if (Files.isRegularFile(fsPath) && fsPath.toString().endsWith(".class")) {
						new ClassReader(Files.readAllBytes(fsPath)).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					}
				}
			}
		}

		// Populate class inheritance
		Multimap<String, String> classes = Multimap.setMultimap();

		for (Map.Entry<String, ? extends Collection<String>> entry : classInheritanceMap.entrySet()) {
			Set<String> allSuperClasses = new HashSet<>();

			for (String superClass : entry.getValue()) {
				collectSuperClasses(superClass, new HashSet<>(), allSuperClasses, classInheritanceMap);
			}

			classes.putAll(entry.getKey(), allSuperClasses);
		}

		return new Pair<>(classes, methods);
	}

	private static void collectSuperClasses(String className, Set<String> travelled, Set<String> allSuperClasses, Multimap<String, String> classInheritanceMap) {
		if (className != null && !className.isEmpty()) {
			allSuperClasses.add(className);

			for (String superClass : classInheritanceMap.get(className)) {
				if (travelled.add(superClass)) {
					collectSuperClasses(superClass, travelled, allSuperClasses, classInheritanceMap);
				}
			}
		}
	}

	private static class Visitor extends ClassVisitor {
		private final Multimap<String, String> classInheritanceMap;
		private final Set<MethodKey> methods;
		private String lastClass = null;

		Visitor(int api, Multimap<String, String> classInheritanceMap, Set<MethodKey> methods) {
			super(api);
			this.classInheritanceMap = classInheritanceMap;
			this.methods = methods;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			this.lastClass = name;
			this.classInheritanceMap.put(name, superName);
			this.classInheritanceMap.putAll(name, Arrays.asList(interfaces));
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			this.methods.add(new MethodKey(this.lastClass, name, descriptor));
			return super.visitMethod(access, name, descriptor, signature, exceptions);
		}
	}

	private record MethodKey(String className, String name, String descriptor) {
	}
}
