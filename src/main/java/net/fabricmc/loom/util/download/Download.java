/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2024 FabricMC
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

package net.fabricmc.loom.util.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.util.AttributeHelper;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.cache.AtomicFiles;

public final class Download {
	private static final String E_TAG = "ETag";
	private static final Logger LOGGER = LoggerFactory.getLogger(Download.class);

	// 等待「另一个进程/线程正在写同一文件」的上限。超时后不再失败，而是接管锁继续下载：
	// 内容由「本次尝试专用的唯一临时文件 + 原子落位」保证完整，多一个写入者最多重复一次网络传输。
	// 实测正常热路径（文件已存在）根本不会走到这里，冷下载的典型耗时在秒级。
	private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofMinutes(2);

	// 陈旧临时文件的回收阈值（仅用于清理被 kill 掉的构建留下的 .part，不影响热路径）
	private static final Duration PART_RETENTION = Duration.ofHours(1);
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.executor(Executors.newVirtualThreadPerTaskExecutor())
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.proxy(ProxySelector.getDefault())
			.connectTimeout(Duration.ofMinutes(2))
			.build();

	public static DownloadBuilder create(String url) throws URISyntaxException {
		return DownloadBuilder.create(url);
	}

	private final URI url;
	private final @Nullable String expectedHash;
	private final boolean useEtag;
	private final boolean forceDownload;
	private final boolean offline;
	private final Duration maxAge;
	private final DownloadProgressListener progressListener;
	private final HttpClient.Version httpVersion;

	Download(URI url, @Nullable String expectedHash, boolean useEtag, boolean forceDownload, boolean offline, Duration maxAge, DownloadProgressListener progressListener, HttpClient.Version httpVersion) {
		this.url = url;
		this.expectedHash = expectedHash;
		this.useEtag = useEtag;
		this.forceDownload = forceDownload;
		this.offline = offline;
		this.maxAge = maxAge;
		this.progressListener = progressListener;
		this.httpVersion = httpVersion;
	}

	private HttpRequest.Builder requestBuilder() {
		return HttpRequest.newBuilder(url)
				.timeout(Duration.ofMinutes(10))
				.version(httpVersion)
				.GET();
	}

	private HttpRequest getRequest() {
		return requestBuilder()
				.build();
	}

	private HttpRequest getETagRequest(String etag) {
		return requestBuilder()
				.header("If-None-Match", etag)
				.build();
	}

	private <T> HttpResponse<T> send(HttpRequest httpRequest, HttpResponse.BodyHandler<T> bodyHandler) throws DownloadException {
		if (offline) {
			throw error("Unable to download %s in offline mode", this.url);
		}

		progressListener.onStart();

		try {
			return HTTP_CLIENT.send(httpRequest, bodyHandler);
		} catch (IOException | InterruptedException e) {
			throw error(e, "Failed to download (%s)", url);
		}
	}

	String downloadString() throws DownloadException {
		final HttpResponse<InputStream> response = send(getRequest(), HttpResponse.BodyHandlers.ofInputStream());
		final int statusCode = response.statusCode();
		final boolean successful = statusCode >= 200 && statusCode < 300;

		if (!successful) {
			progressListener.onEnd();
			throw statusError("HTTP request to (%s) returned unsuccessful status".formatted(url) + "(%d)", statusCode);
		}

		try (InputStream inputStream = decodeOutput(response)) {
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw error(e, "Failed to decode download output");
		} finally {
			progressListener.onEnd();
		}
	}

	DownloadResult downloadPath(Path output) throws DownloadException {
		boolean downloadRequired = requiresDownload(output);

		if (!downloadRequired) {
			// Does not require download, we are done here.
			progressListener.onEnd();
			return new DownloadResultImpl(false);
		}

		try {
			return new DownloadResultImpl(doDownload(output));
		} catch (Throwable throwable) {
			throw error(throwable, "Failed to download file from (%s) to (%s)", url, output);
		} finally {
			progressListener.onEnd();
		}
	}

	/**
	 * {@return 本次调用是否真的写了文件（并发去重时可能由另一个写入者代劳）}.
	 */
	private boolean doDownload(Path output) throws DownloadException {
		try {
			Files.createDirectories(output.getParent());
		} catch (IOException e) {
			throw error(e, "Failed to create parent directories");
		}

		// <name>.lock 只负责「同一个文件不并发写」，持有者信息写在锁文件里；
		// 残留锁（持有者已不在）由 DownloadLock 判活后直接接管，既不报错也不触发重下。
		// 下载全程持锁：后到者不为同一个文件重复走一趟网络。
		final DownloadLock lock;

		try {
			lock = DownloadLock.acquire(getLockFile(output), LOCK_WAIT_TIMEOUT);
		} catch (IOException e) {
			throw error(e, "Failed to acquire lock on %s", getLockFile(output));
		}

		try (lock) {
			// 锁内重检（只在真的等过锁时才做）：持锁者在此期间可能已经把完整文件落位（同一 URL、
			// 同一期望 hash）。没等过就不可能有人写过，重检只会白多一次 IO，热路径不受影响。
			if (lock.waitedForLock() && !requiresDownload(output)) {
				LOGGER.debug("等待下载锁期间 {} 已由其它写入者完成，直接复用", output);
				return false;
			}

			// etag 在锁内读：锁外读到的是另一个写入者落位之前的旧文件，拿它当 If-None-Match
			// 只会得到一个毫无意义的 304
			final Optional<String> eTag = !forceDownload && useEtag && exists(output) ? readEtag(output) : Optional.empty();
			final HttpRequest httpRequest = eTag
					.map(this::getETagRequest)
					.orElseGet(this::getRequest);

			final HttpResponse<InputStream> response = send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
			final int statusCode = response.statusCode();
			boolean success = statusCode == HttpURLConnection.HTTP_NOT_MODIFIED || (statusCode >= 200 && statusCode < 300);

			if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
				try {
					// Update the last modified time so we don't retry the request until the max age has passed again.
					Files.setLastModifiedTime(output, FileTime.from(Instant.now()));
				} catch (IOException e) {
					throw error(e, "Failed to update last modified time");
				}

				// Success, etag matched.
				return false;
			}

			if (!success) {
				throw statusError("HTTP request returned unsuccessful status (%d)", statusCode);
			}

			downloadToPath(output, response);

			if (useEtag) {
				final HttpHeaders headers = response.headers();
				final String responseETag = headers.firstValue(E_TAG.toLowerCase(Locale.ROOT)).orElse(null);

				if (responseETag != null) {
					writeEtag(output, responseETag);
				}
			}

			if (expectedHash != null) {
				// Ensure we downloaded the expected hash.
				if (!isHashValid(output)) {
					String downloadedHash;

					try {
						downloadedHash = Checksum.of(output).sha1().hex();
						Files.deleteIfExists(output);
					} catch (IOException e) {
						downloadedHash = "unknown hash";
					}

					throw error("Failed to download (%s) with expected hash: %s got %s", url, expectedHash, downloadedHash);
				}
			}

			return true;
		}
	}

	private void downloadToPath(Path output, HttpResponse<InputStream> response) throws DownloadException {
		// 刻意不在下载前删除 output：那会留下「文件不存在」的窗口，而 output 是可以被并发读取的共享缓存产物
		// （依赖 jar 就是被 remap classpath 读的）。读方在窗口里打开它，关闭时就会因 toRealPath() 失败而把该
		// 路径的 zipfs 登记簿条目永久留成死实例（JDK-8291712）。old output 由下载完成后的原子 move 覆盖。
		//
		// 临时文件按「每次尝试唯一」命名：两个写入者重叠时（锁被超时接管、上一轮的临时文件未清）各写各的，
		// 写完再原子落位，谁也覆盖不了谁。
		deleteStalePartFiles(output);
		final Path partFile = getUniquePartFile(output);

		try {
			writeToPath(output, partFile, response);
		} finally {
			// 成功时 partFile 已被原子 move 走，这里是空操作；失败时不留半成品给下一次构建
			try {
				Files.deleteIfExists(partFile);
			} catch (IOException ignored) {
				// ignored
			}
		}
	}

	private void writeToPath(Path output, Path partFile, HttpResponse<InputStream> response) throws DownloadException {
		final long length = Long.parseLong(response.headers().firstValue("Content-Length").orElse("-1"));
		AtomicLong totalBytes = new AtomicLong(0);

		try (OutputStream outputStream = Files.newOutputStream(partFile, StandardOpenOption.CREATE_NEW)) {
			copyWithCallback(decodeOutput(response), outputStream, value -> {
				if (length < 0) {
					return;
				}

				progressListener.onProgress(totalBytes.addAndGet(value), length);
			});
		} catch (IOException e) {
			throw error(e, "Failed to decode and write download output");
		}

		if (Files.notExists(partFile)) {
			throw error("No file was downloaded");
		}

		if (length > 0) {
			try {
				final long actualLength = Files.size(partFile);

				if (actualLength != length) {
					throw error("Unexpected file length of %d bytes, expected %d bytes".formatted(actualLength, length));
				}
			} catch (IOException e) {
				throw error(e);
			}
		}

		try {
			// Once the file has been fully read, move it to the destination file.
			// This ensures that the output file only exists in fully populated state.
			// 用原子 move（而不是裸 Files.move）：目标可能已存在且正被读方持有，
			// AtomicFiles 会优先 ATOMIC_MOVE，并在 Windows 的共享冲突上做有上限的退避重试。
			AtomicFiles.move(partFile, output);
		} catch (IOException e) {
			throw error(e, "Failed to complete download");
		}
	}

	private void copyWithCallback(InputStream is, OutputStream os, IntConsumer consumer) throws IOException {
		byte[] buffer = new byte[1024];
		int length;

		while ((length = is.read(buffer)) > 0) {
			os.write(buffer, 0, length);
			consumer.accept(length);
		}
	}

	private InputStream decodeOutput(HttpResponse<InputStream> response) throws IOException {
		final String encoding = response.headers().firstValue("Content-Encoding").orElse("");

		return switch (encoding) {
		case "gzip" -> new GZIPInputStream(response.body());
		case "" -> response.body();
		default -> throw error("Unsupported encoding: %s", encoding);
		};
	}

	private boolean requiresDownload(Path output) throws DownloadException {
		if (forceDownload || !exists(output)) {
			// 内容寻址下载的快路径：带期望 hash 的文件（如 vanilla jar）内容永不改变，
			// 本地 hash 匹配 = 重下结果必然一字节不差。即使 --refresh-dependencies 强制刷新，
			// 也直接复用本地完好文件，避免重复下载几十 MB 的不变产物；
			// 损坏文件 hash 必不匹配，仍会照常重下，语义安全。
			if (forceDownload && expectedHash != null && exists(output) && isHashValid(output)) {
				LOGGER.info("跳过强制重新下载：本地文件 hash 与期望一致（{}）", output);
				return false;
			}

			// File does not exist, or we are forced to download again.
			return true;
		}

		// 这里刻意**不看** <name>.lock：锁文件存在只说明「有人正在写」或「写的人崩了」，与本地已有文件
		// 是否可用无关。前者由 DownloadLock 串行化，后者由 DownloadLock 的判活逻辑接管；把锁当成
		// 「必须重下」的信号，等于让进程被 kill 后的残留锁白白触发一次完整下载。
		if (offline) {
			// We know the file exists, nothing more we can do.
			return false;
		}

		if (expectedHash != null) {
			if (isHashValid(output)) {
				// Valid hash, no need to re-download
				return false;
			}

			LOGGER.info("Found existing file ({}) to download with unexpected hash.", output);
		}

		//noinspection RedundantIfStatement
		if (!maxAge.equals(Duration.ZERO) && !isOutdated(output)) {
			return false;
		}

		// Default to re-downloading, may check the etag
		return true;
	}

	private boolean isHashValid(Path path) {
		int i = expectedHash.indexOf(':');
		String algorithm = expectedHash.substring(0, i);
		String hash = expectedHash.substring(i + 1);

		try {
			Checksum.Result computedHash = switch (algorithm) {
			case "sha1" -> Checksum.of(path).sha1();
			default -> throw error("Unsupported hash algorithm (%s)", algorithm);
			};

			return computedHash.matchesStr(hash);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private boolean isOutdated(Path path) throws DownloadException {
		try {
			final FileTime lastModified = Files.getLastModifiedTime(path);
			return lastModified.toInstant()
					.isBefore(Instant.now().minus(maxAge));
		} catch (IOException e) {
			throw error(e, "Failed to check if (%s) is outdated", path);
		}
	}

	private Optional<String> readEtag(Path output) {
		try {
			return AttributeHelper.readAttribute(output, E_TAG);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	private void writeEtag(Path output, String eTag) throws DownloadException {
		try {
			AttributeHelper.writeAttribute(output, E_TAG, eTag);
		} catch (IOException e) {
			throw error(e, "Failed to write etag to (%s)", output);
		}
	}

	// A faster exists check
	private static boolean exists(Path path) {
		return path.getFileSystem() == FileSystems.getDefault() ? path.toFile().exists() : Files.exists(path);
	}

	private Path getLockFile(Path output) {
		return output.resolveSibling(output.getFileName() + ".lock");
	}

	// 临时文件名带唯一后缀：并发写同一目标时各自写各自的临时文件，最后原子 move 落位
	private Path getUniquePartFile(Path output) {
		return output.resolveSibling(output.getFileName() + "." + UUID.randomUUID() + ".part");
	}

	/**
	 * 清掉同一目标遗留的陈旧临时文件.
	 *
	 * <p>临时文件名带唯一后缀后，被 kill 掉的构建不会再被下一次下载「顺手删掉」；这里在下一次真正要下载
	 * 同一个文件时回收它们。<b>只在冷路径（已经决定要下载）调用</b>，热路径一次目录 IO 都不会多。
	 *
	 * <p>只删一小时以前的：锁可能被超时接管，此时另一个写入者正在写它的临时文件，删掉它会让那一次
	 * 下载白做。一小时遠超过任何单文件的下载耗时，这个阈值把误删的可能降到可以忽略。
	 */
	private void deleteStalePartFiles(Path output) {
		final Path parent = output.getParent();

		if (parent == null || !Files.isDirectory(parent)) {
			return;
		}

		final String prefix = output.getFileName() + ".";
		final Instant cutoff = Instant.now().minus(PART_RETENTION);

		try (var entries = Files.newDirectoryStream(parent, entry -> {
			final String name = entry.getFileName().toString();
			return name.startsWith(prefix) && name.endsWith(".part");
		})) {
			for (Path entry : entries) {
				try {
					if (Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
						Files.deleteIfExists(entry);
					}
				} catch (IOException e) {
					// 回收临时文件是尽力而为：删不掉不影响本次下载
					LOGGER.debug("清理旧临时文件失败：{}", entry, e);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("枚举临时文件失败：{}", parent, e);
		}
	}

	private DownloadException statusError(String message, int statusCode) {
		return new DownloadException(String.format(Locale.ENGLISH, message, statusCode), statusCode);
	}

	private DownloadException error(String message, Object... args) {
		return new DownloadException(String.format(Locale.ENGLISH, message, args));
	}

	private DownloadException error(Throwable throwable) {
		return new DownloadException(throwable);
	}

	private DownloadException error(Throwable throwable, String message, Object... args) {
		return new DownloadException(message.formatted(args), throwable);
	}

	private record DownloadResultImpl(boolean didDownload) implements DownloadResult { }
}
