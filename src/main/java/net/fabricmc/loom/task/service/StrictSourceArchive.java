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

package net.fabricmc.loom.task.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.SourceContext;
import org.cadixdev.mercury.shadow.org.eclipse.jdt.core.ToolFactory;
import org.cadixdev.mercury.shadow.org.eclipse.jdt.core.compiler.IProblem;
import org.cadixdev.mercury.shadow.org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.cadixdev.mercury.shadow.org.eclipse.jdt.core.compiler.InvalidInputException;

import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.mappingio.tree.MappingTreeView;

/** 官方完整源码归档转换；只发布覆盖校验后的结果. */
final class StrictSourceArchive {
	private final Path input;
	private final Path output;
	private final Map<String, String> javaFiles = new HashMap<>();
	private final Set<String> entries = new TreeSet<>();
	private final Set<String> seen = new HashSet<>();
	private final List<String> problems = new ArrayList<>();

	private StrictSourceArchive(Path work) throws IOException {
		input = Files.createDirectory(work.resolve("mojang"));
		output = Files.createDirectory(work.resolve("named"));
	}

	static void remap(Path source, Path destination, Mercury mercury, MappingTreeView mappings) throws IOException {
		Path target = destination.toAbsolutePath();
		Files.createDirectories(target.getParent());
		Path work = Files.createTempDirectory(target.getParent(), ".forge-src-");

		try {
			var archive = new StrictSourceArchive(work);
			archive.extract(source, mappings, mercury.getSourceCompatibility());
			archive.rewrite(mercury);
			Path jar = work.resolve("complete.jar");
			archive.pack(jar);
			Files.move(jar, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.walkFileTree(work, new DeletingFileVisitor());
		}
	}

	private void extract(Path source, MappingTreeView mappings, String release) throws IOException {
		int from = mappings.getNamespaceId("mojang");
		int to = mappings.getNamespaceId("named");

		try (var zip = new ZipFile(source.toFile())) {
			var files = zip.entries();
			var inputs = new HashSet<String>();

			while (files.hasMoreElements()) {
				ZipEntry file = files.nextElement();

				if (file.isDirectory()) {
					continue;
				}

				String name = file.getName();
				String mapped = name;
				Path resource = safePath(output, name);

				if (!inputs.add(name)) {
					throw new IOException("Duplicate source entry: " + name);
				}

				try (var stream = zip.getInputStream(file)) {
					if (name.endsWith(".java")) {
						byte[] bytes = stream.readAllBytes();

						if (hasJavaTokens(bytes, release)) {
							String owner = name.substring(0, name.length() - ".java".length());
							mapped = mappings.mapClassName(owner, from, to) + ".java";
							safePath(output, mapped);
							javaFiles.put(name, mapped);
							Path src = safePath(input, name);
							Files.createDirectories(src.getParent());
							Files.write(src, bytes);
						} else {
							// 只有词法上确认为空/纯注释的 Java 才原样复制；损坏的源不能走此分支。
							Files.createDirectories(resource.getParent());
							Files.write(resource, bytes);
						}
					} else {
						Files.createDirectories(resource.getParent());
						Files.copy(stream, resource);
					}
				}

				if (!entries.add(mapped)) {
					throw new IOException("Multiple source entries map to " + mapped);
				}
			}
		}

		if (javaFiles.isEmpty()) {
			throw new IOException("No Java declarations in " + source);
		}
	}

	private static boolean hasJavaTokens(byte[] bytes, String release) throws IOException {
		var scanner = ToolFactory.createScanner(false, false, false, release);
		scanner.setSource(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().toCharArray());

		try {
			return scanner.getNextToken() != ITerminalSymbols.TokenNameEOF;
		} catch (InvalidInputException e) {
			throw new IOException("Invalid Java source token", e);
		}
	}

	private static Path safePath(Path root, String name) throws IOException {
		Path path = root.resolve(name).normalize();

		if (name.isEmpty() || !path.startsWith(root) || !root.relativize(path).toString().replace('\\', '/').equals(name) || name.contains("\\")) {
			throw new IOException("Invalid source archive path: " + name);
		}

		return path;
	}

	private void checkSource(SourceContext context) {
		String source = input.relativize(context.getSourceFile()).toString().replace('\\', '/');
		var unit = context.getCompilationUnit();

		for (var problem : unit.getProblems()) {
			if (problem.isError() && (problem.getID() & IProblem.Syntax) != 0) {
				problems.add(source + ":" + problem.getSourceLineNumber() + ": " + problem.getMessage());
				return;
			}
		}

		if (unit.types().isEmpty() && unit.getModule() == null && unit.getPackage() == null) {
			problems.add("No Java declaration in " + source);
		}
	}

	private void rewrite(Mercury mercury) throws IOException {
		mercury.getProcessors().addFirst(this::checkSource);
		// Mercury 会吞掉单文件处理器的异常；完成回调与实际输出文件必须同时存在。
		mercury.getProcessors().add(context -> {
			String source = input.relativize(context.getSourceFile()).toString().replace('\\', '/');
			String actual = context.getQualifiedPrimaryType().replace('.', '/') + ".java";

			if (!actual.equals(javaFiles.get(source))) {
				problems.add(source + " -> " + actual + ", expected " + javaFiles.get(source));
			}

			seen.add(source);
		});

		try {
			mercury.rewrite(input, output);
		} catch (Exception e) {
			throw new IOException("Failed to rewrite Forge sources", e);
		}

		if (!problems.isEmpty()) {
			throw new IOException("Invalid Forge source rewrite (" + problems.size() + "): " + problems.getFirst());
		}

		for (var file : javaFiles.entrySet()) {
			if (!seen.contains(file.getKey()) || !Files.isRegularFile(output.resolve(file.getValue())) || Files.size(output.resolve(file.getValue())) == 0) {
				throw new IOException("Missing rewritten Forge source " + file.getKey() + " -> " + file.getValue());
			}
		}
	}

	private void pack(Path jar) throws IOException {
		try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			for (String name : entries) {
				var entry = new ZipEntry(name);
				entry.setTime(0);
				zip.putNextEntry(entry);
				Files.copy(output.resolve(name), zip);
				zip.closeEntry();
			}
		}
	}
}
