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

package dev.architectury.loom.forge.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.cache.AtomicFiles;

/** 保存同一批 Mavenizer 的 Mojang classes 和源码，并绑定当前 SRG 产物. */
public record ForgeMavenizerSourcesBundle(Path mojangClassesJar, Path sourcesJar) {
	private static final String FORMAT = "mavenizer-sources-v1";
	private static final String STAMP = "identity.txt";
	private static final String CLASSES = "mojang-classes.jar";
	private static final String SOURCES = "sources.jar";
	private static final Set<String> ENTRIES = Set.of(STAMP, CLASSES, SOURCES);
	private static final int MAX_STAMP = 4096;
	private static final long MAX_JAR = 128L * 1024 * 1024;

	record RuntimeIdentity(String recipe, String receiptSha, String srgSha, String classesSha, String mappingsSha) {
	}

	static RuntimeIdentity readRuntime(Path runtimeSrgJar, Path runtimeReceipt) throws IOException {
		if (!Files.isRegularFile(runtimeReceipt, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(runtimeSrgJar, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("缺少已经发布的 Mavenizer 运行时 JAR 或收据：" + runtimeSrgJar + " / " + runtimeReceipt);
		}

		try (InputStream input = Files.newInputStream(runtimeReceipt)) {
			final byte[] bytes = readStamp(input);
			final List<String> lines = lines(bytes);

			if (lines.size() != 4 || !lines.getFirst().startsWith(ForgeMavenizerService.runtimeRecipe() + "|forge=")
					|| !lines.subList(1, 4).stream().allMatch(ForgeMavenizerSourcesBundle::isSha256)) {
				throw new IOException("无效的 Mavenizer 运行时收据：" + runtimeReceipt);
			}

			if (!sha256(runtimeSrgJar).equals(lines.get(3))) {
				throw new IOException("Mavenizer SRG JAR 摘要与收据不符：" + runtimeSrgJar);
			}

			return new RuntimeIdentity(lines.getFirst(), Checksum.of(bytes).sha256().hex(), lines.get(3), lines.get(1), lines.get(2));
		}
	}

	static boolean isReusable(Path bundle, RuntimeIdentity runtime) {
		if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}

		try (ZipFile zip = new ZipFile(bundle.toFile())) {
			verify(zip, runtime);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	static void publish(Path bundle, Path runtimeSrgJar, Path runtimeReceipt, RuntimeIdentity runtime, ForgeMavenizerArtifact artifact) throws IOException {
		if (!runtime.classesSha().equals(sha256(artifact.classesJar())) || !runtime.mappingsSha().equals(sha256(artifact.officialToSrgMappings()))) {
			throw new IOException("Mavenizer 本次导出的 Mojang classes 或 map2srg 与当前运行时收据不符；保留原运行时与已有源码包");
		}

		final List<String> identity = List.of(FORMAT, runtime.receiptSha(), runtime.srgSha(), runtime.classesSha(), sha256(artifact.sourcesJar()));
		AtomicFiles.publish(bundle, stage -> {
			try (var zip = new ZipOutputStream(Files.newOutputStream(stage))) {
				zip.setLevel(0);
				zip.putNextEntry(entry(STAMP));
				zip.write(String.join("\n", identity).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
				writeJar(zip, CLASSES, artifact.classesJar());
				writeJar(zip, SOURCES, artifact.sourcesJar());
			}

			try (ZipFile zip = new ZipFile(stage.toFile())) {
				verify(zip, runtime);
			}

			verifyCurrentRuntime(runtimeSrgJar, runtimeReceipt, runtime);
		});
	}

	/** 从固定条目名解包；只在整包和运行时身份通过校验后写入调用方的工作目录. */
	public static ForgeMavenizerSourcesBundle unpack(Path bundle, Path runtimeSrgJar, Path runtimeReceipt, Path workDirectory) throws IOException {
		final RuntimeIdentity runtime = readRuntime(runtimeSrgJar, runtimeReceipt);

		if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Mavenizer 源码包不存在或不是普通文件：" + bundle);
		}

		try (ZipFile zip = new ZipFile(bundle.toFile())) {
			final String sourcesSha = verify(zip, runtime);
			final Path directory = Files.createTempDirectory(Files.createDirectories(workDirectory), "mavenizer-sources-");
			final Path classes = directory.resolve(CLASSES);
			final Path sources = directory.resolve(SOURCES);

			try {
				extract(zip, CLASSES, classes, runtime.classesSha());
				extract(zip, SOURCES, sources, sourcesSha);
				verifyCurrentRuntime(runtimeSrgJar, runtimeReceipt, runtime);
				return new ForgeMavenizerSourcesBundle(classes, sources);
			} catch (IOException e) {
				try {
					Files.deleteIfExists(sources);
					Files.deleteIfExists(classes);
					Files.deleteIfExists(directory);
				} catch (IOException cleanup) {
					e.addSuppressed(cleanup);
				}

				throw e;
			}
		}
	}

	private static void verifyCurrentRuntime(Path runtimeSrgJar, Path runtimeReceipt, RuntimeIdentity runtime) throws IOException {
		if (!runtime.equals(readRuntime(runtimeSrgJar, runtimeReceipt))) {
			throw new IOException("准备 Mavenizer 源码期间运行时身份已变更：" + runtimeSrgJar);
		}
	}

	private static String verify(ZipFile zip, RuntimeIdentity runtime) throws IOException {
		final Set<String> found = new HashSet<>();
		final var entries = zip.entries();

		while (entries.hasMoreElements()) {
			final ZipEntry entry = entries.nextElement();

			if (!ENTRIES.contains(entry.getName()) || entry.isDirectory() || !found.add(entry.getName())) {
				throw new IOException("Mavenizer 源码包含非预期或重复条目：" + entry.getName());
			}
		}

		if (!found.equals(ENTRIES)) {
			throw new IOException("Mavenizer 源码包条目不完整：" + zip.getName());
		}

		try (InputStream input = zip.getInputStream(zip.getEntry(STAMP))) {
			final List<String> lines = lines(readStamp(input));
			final List<String> expected = List.of(FORMAT, runtime.receiptSha(), runtime.srgSha(), runtime.classesSha());

			if (lines.size() != 5 || !lines.subList(0, 4).equals(expected) || !isSha256(lines.get(4))) {
				throw new IOException("Mavenizer 源码包身份与当前 SRG JAR 或收据不符：" + zip.getName());
			}

			try (OutputStream discard = OutputStream.nullOutputStream()) {
				copyJar(zip, CLASSES, runtime.classesSha(), discard);
				copyJar(zip, SOURCES, lines.get(4), discard);
			}

			return lines.get(4);
		}
	}

	private static void copyJar(ZipFile zip, String name, String expected, OutputStream output) throws IOException {
		final ZipEntry entry = zip.getEntry(name);
		final MessageDigest digest;

		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}

		try (var input = new DigestInputStream(zip.getInputStream(entry), digest)) {
			final long size = entry.getSize();

			if (size <= 0 || size > MAX_JAR) {
				throw new IOException("Mavenizer 源码包 JAR 尺寸异常：" + name);
			}

			final byte[] buffer = new byte[8192];
			long remaining = size;

			while (remaining > 0) {
				final int read = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));

				if (read < 0) {
					throw new IOException("Mavenizer 源码包 JAR 被截断：" + name);
				}

				output.write(buffer, 0, read);
				remaining -= read;
			}

			if (input.read() != -1) {
				throw new IOException("Mavenizer 源码包 JAR 实际尺寸超出声明：" + name);
			}
		}

		if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
			throw new IOException("Mavenizer 源码包 JAR 摘要不符：" + name);
		}
	}

	private static void extract(ZipFile zip, String name, Path target, String expected) throws IOException {
		try (OutputStream output = Files.newOutputStream(target)) {
			copyJar(zip, name, expected, output);
		}
	}

	private static void writeJar(ZipOutputStream zip, String name, Path jar) throws IOException {
		zip.putNextEntry(entry(name));
		Files.copy(jar, zip);
		zip.closeEntry();
	}

	private static ZipEntry entry(String name) {
		final ZipEntry entry = new ZipEntry(name);
		entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
		return entry;
	}

	private static String sha256(Path file) throws IOException {
		try {
			return Checksum.of(file).sha256().hex();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static byte[] readStamp(InputStream input) throws IOException {
		final byte[] bytes = input.readNBytes(MAX_STAMP + 1);

		if (bytes.length > MAX_STAMP) {
			throw new IOException("Mavenizer 身份文件超出允许大小");
		}

		return bytes;
	}

	private static List<String> lines(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8).lines().toList();
	}

	private static boolean isSha256(String value) {
		return value.matches("[a-f0-9]{64}");
	}
}
