/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

package net.fabricmc.loom.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.fabricmc.tinyremapper.FileSystemReference;

public final class FileSystemUtil {
	public static final class Delegate implements AutoCloseable, Supplier<FileSystem> {
		private final FileSystem fileSystem;
		// 非 null 表示经 tiny-remapper 引用计数打开（写入路径），close 需归还引用。
		// 为 null 表示独立文件系统（只读路径），close 直接关闭。
		private final @Nullable FileSystemReference reference;
		private final @Nullable URI uri;

		Delegate(FileSystem fileSystem, @Nullable FileSystemReference reference, @Nullable URI uri) {
			this.fileSystem = fileSystem;
			this.reference = reference;
			this.uri = uri;
		}

		public @Nullable FileSystemReference reference() {
			return reference;
		}

		public @Nullable URI uri() {
			return uri;
		}

		public Path getPath(String path, String... more) {
			return get().getPath(path, more);
		}

		public Path getRoot() {
			return get().getPath("/");
		}

		public byte[] readAllBytes(String path) throws IOException {
			Path fsPath = getPath(path);

			if (Files.exists(fsPath)) {
				return Files.readAllBytes(fsPath);
			} else {
				throw new NoSuchFileException(fsPath.toString());
			}
		}

		public <T> T fromInputStream(IOFunction<InputStream, T> function, String path, String... more) throws IOException {
			try (InputStream inputStream = Files.newInputStream(getPath(path, more))) {
				return function.apply(inputStream);
			}
		}

		public String readString(String path) throws IOException {
			return new String(readAllBytes(path), StandardCharsets.UTF_8);
		}

		@Override
		public void close() throws IOException {
			if (reference == null) {
				// 独立文件系统未进入 JDK 进程级 filesystems 登记簿，不适用 JDK-8291712，直接关闭。
				fileSystem.close();
				return;
			}

			try {
				reference.close();
			} catch (IOException e) {
				// An IOException can only ever be thrown by the underlying FileSystem.close() call in tiny remapper
				// This means that this reference was the last open
				try {
					// We would then almost always expect this to throw a FileSystemNotFoundException
					FileSystem fileSystem = FileSystems.getFileSystem(uri);

					if (fileSystem.isOpen()) {
						// Or the unlikely chance that another thread opened a new reference
						throw e;
					}

					// However if we end up here, the closed FileSystem was not removed from ZipFileSystemProvider.filesystems
					// This leaves us in a broken state, preventing this JVM from ever being able to open a zip at this path.
					// See: https://bugs.openjdk.org/browse/JDK-8291712
					throw new UnrecoverableZipException(e.getMessage(), e);
				} catch (FileSystemNotFoundException ignored) {
					// This the "happy" case, where the zip FS failed to close but was
				}

				// Throw the normal exception, we can recover from this
				throw e;
			}
		}

		@Override
		public FileSystem get() {
			return fileSystem;
		}

		// TODO cleanup
		public FileSystem fs() {
			return get();
		}
	}

	private FileSystemUtil() {
	}

	/**
	 * 打开只读 jar 文件系统，走 JDK 的 {@code newFileSystem(Path, Map)} 重载.
	 *
	 * <p>与 {@link #getJarFileSystem(Path, boolean)} 的差别：该重载返回<b>独立</b>的文件系统实例，
	 * 不进入 JDK 进程级的 {@code ZipFileSystemProvider.filesystems} 登记簿，因此既不产生
	 * {@code FileSystemAlreadyExistsException}，也不与 tiny-remapper 的全局锁
	 * {@code FileSystemReference.openFsMap} 发生交互。多个线程可同时打开同一 jar.
	 *
	 * <p>调用方须保证只读取、不写入：独立实例各自持有文件锁，对同一 jar 并发写入会失败
	 * （{@code AccessDeniedException}）。需要写入时请用 {@link #getJarFileSystem(Path, boolean)}，
	 * 它经由 tiny-remapper 引用计数共享同一文件系统.
	 *
	 * <p>文件不存在时抛 {@code NoSuchFileException}，与原有实现一致.
	 */
	public static Delegate getReadOnlyJarFileSystem(Path path) throws IOException {
		return new Delegate(FileSystems.newFileSystem(path, Map.of()), null, null);
	}

	public static Delegate getJarFileSystem(File file, boolean create) throws IOException {
		FileSystemReference reference = FileSystemReference.openJar(file.toPath(), create);
		return new Delegate(reference.getFs(), reference, toJarUri(file.toPath()));
	}

	public static Delegate getJarFileSystem(Path path, boolean create) throws IOException {
		FileSystemReference reference = FileSystemReference.openJar(path, create);
		return new Delegate(reference.getFs(), reference, toJarUri(path));
	}

	public static Delegate getJarFileSystem(Path path) throws IOException {
		FileSystemReference reference = FileSystemReference.openJar(path);
		return new Delegate(reference.getFs(), reference, toJarUri(path));
	}

	public static Delegate getJarFileSystem(URI uri, boolean create) throws IOException {
		FileSystemReference reference = FileSystemReference.open(uri, create);
		return new Delegate(reference.getFs(), reference, uri);
	}

	private static URI toJarUri(Path path) {
		URI uri = path.toUri();

		try {
			return new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment());
		} catch (URISyntaxException e) {
			throw new RuntimeException("can't convert path "+path+" to uri", e);
		}
	}

	public static class UnrecoverableZipException extends RuntimeException {
		public UnrecoverableZipException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
