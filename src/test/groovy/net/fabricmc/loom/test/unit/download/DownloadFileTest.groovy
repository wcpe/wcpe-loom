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

package net.fabricmc.loom.test.unit.download

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import io.javalin.http.HttpStatus
import spock.lang.IgnoreIf

import net.fabricmc.loom.util.Checksum
import net.fabricmc.loom.util.download.Download
import net.fabricmc.loom.util.download.DownloadException
import net.fabricmc.loom.util.download.DownloadExecutor
import net.fabricmc.loom.util.download.DownloadProgressListener
import net.fabricmc.loom.util.download.DownloadResult

class DownloadFileTest extends DownloadTest {
	@IgnoreIf({ os.windows }) // Requires admin on windows.
	def "Directory: Symlink"() {
		setup:
		server.get("/symlinkFile") {
			it.result("Hello World")
		}
		def output = new File(File.createTempDir(), "file.txt").toPath()
		def linkedtmp = new File(File.createTempDir(), "linkedtmp").toPath()
		Files.createSymbolicLink(linkedtmp, output.getParent())
		def symlink = Paths.get(linkedtmp.toString(), "file.txt")

		when:
		def result = Download.create("$PATH/symlinkFile").downloadPath(symlink)

		then:
		Files.readString(symlink) == "Hello World"
	}

	def "File: Simple"() {
		setup:
		server.get("/simpleFile") {
			it.result("Hello World")
		}

		def output = new File(File.createTempDir(), "subdir/file.txt").toPath()

		when:
		def result = Download.create("$PATH/simpleFile").downloadPath(output)

		then:
		Files.readString(output) == "Hello World"
	}

	def "File: Not found"() {
		setup:
		server.get("/fileNotfound") {
			it.status(HttpStatus.NOT_FOUND)
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()

		when:
		def result = Download.create("$PATH/fileNotfound").downloadPath(output)

		then:
		def e = thrown DownloadException
		e.statusCode == 404
	}

	def "File: Server error"() {
		setup:
		server.get("/fileServerError") {
			it.status(HttpStatus.INTERNAL_SERVER_ERROR)
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()

		when:
		def result = Download.create("$PATH/fileServerError").downloadPath(output)

		then:
		def e = thrown DownloadException
		e.statusCode == 500
	}

	def "Cache: Sha1"() {
		setup:
		int requestCount = 0

		server.get("/sha1.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()
		def results = [] as List<DownloadResult>

		when:
		for (i in 0..<2) {
			def result = Download.create("$PATH/sha1.txt")
					.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
					.downloadPath(output)
			results << result
		}

		then:
		requestCount == 1
		results[0].didDownload()
		!results[1].didDownload()
	}

	def "Force: 本地 hash 匹配时跳过强制重下"() {
		setup:
		int requestCount = 0

		server.get("/forceSha1Skip.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()

		when:
		// 第一次正常下载；第二次 forceDownload（模拟 --refresh-dependencies），本地 hash 匹配应直接复用、不发请求
		def first = Download.create("$PATH/forceSha1Skip.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.downloadPath(output)
		def second = Download.create("$PATH/forceSha1Skip.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.forceDownload()
				.downloadPath(output)

		then:
		requestCount == 1
		first.didDownload()
		!second.didDownload()
	}

	def "Force: 本地 hash 不匹配时仍强制重下"() {
		setup:
		int requestCount = 0

		server.get("/forceSha1Redownload.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()
		// 预置一个内容不符（hash 必不匹配）的本地文件，模拟损坏缓存
		Files.writeString(output, "corrupted content")

		when:
		def result = Download.create("$PATH/forceSha1Redownload.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.forceDownload()
				.downloadPath(output)

		then:
		requestCount == 1
		result.didDownload()
		Files.readString(output) == "Hello World"
	}

	def "Invalid Sha1"() {
		setup:
		server.get("/sha1.invalid") {
			it.result("Hello World")
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()

		when:
		Download.create("$PATH/sha1.invalid")
				.sha1("d139cccf047a749691416ce385d3f168c1e28309")
				.downloadPath(output)

		then:
		// Ensure the file we downloaded with the wrong hash was deleted
		Files.notExists(output)
		thrown DownloadException
	}

	def "Offline"() {
		setup:
		int requestCount = 0

		server.get("/offline.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def output = new File(File.createTempDir(), "offline.txt").toPath()

		when:
		Download.create("$PATH/offline.txt")
				.downloadPath(output)

		Download.create("$PATH/offline.txt")
				.offline()
				.downloadPath(output)

		then:
		requestCount == 1
	}

	def "Max Age"() {
		setup:
		int requestCount = 0

		server.get("/maxage.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def output = new File(File.createTempDir(), "maxage.txt").toPath()

		when:
		Download.create("$PATH/maxage.txt")
				.maxAge(Duration.ofDays(1))
				.downloadPath(output)

		Download.create("$PATH/maxage.txt")
				.maxAge(Duration.ofDays(1))
				.downloadPath(output)

		def twoDaysAgo = Instant.now() - Duration.ofDays(2)
		Files.setLastModifiedTime(output, FileTime.from(twoDaysAgo))

		Download.create("$PATH/maxage.txt")
				.maxAge(Duration.ofDays(1))
				.downloadPath(output)

		then:
		requestCount == 2
	}

	def "ETag"() {
		setup:
		int requestCount = 0

		server.get("/etag") {
			def clientEtag = it.req.getHeader("If-None-Match")

			def result = "Hello world"
			def etag = result.hashCode().toString()
			it.header("ETag", etag)

			if (clientEtag == etag) {
				// Etag matches, no need to send the data.
				it.status(HttpStatus.NOT_MODIFIED)
				return
			}

			it.result(result)
			requestCount ++
		}

		def output = new File(File.createTempDir(), "etag.txt").toPath()

		when:
		for (i in 0..<3) {
			Download.create("$PATH/etag")
					.etag(true)
					.downloadPath(output)
		}

		then:
		requestCount == 1
	}

	def "ETag with max age"() {
		setup:
		// The count of requests, can return early if the ETag matches.
		int requestCount = 0
		// The count of full downloads
		int downloadCount = 0

		server.get("/etag") {
			def clientEtag = it.req.getHeader("If-None-Match")

			def result = "Hello world"
			def etag = result.hashCode().toString()
			it.header("ETag", etag)

			requestCount ++

			if (clientEtag == etag) {
				// Etag matches, no need to send the data.
				it.status(HttpStatus.NOT_MODIFIED)
				return
			}

			it.result(result)
			downloadCount ++
		}

		def output = new File(File.createTempDir(), "etag.txt").toPath()

		when:
		for (i in 0..<3) {
			if (i == 2) {
				// On the third request, set the file to be 2 days old, forcing the etag to be checked.
				Files.setLastModifiedTime(output, FileTime.from(Instant.now() - Duration.ofDays(2)))
			}

			Download.create("$PATH/etag")
					.etag(true)
					.maxAge(Duration.ofDays(1))
					.downloadPath(output)
		}

		then:
		requestCount == 2
		downloadCount == 1
	}

	def "Progress: File"() {
		setup:
		server.get("/progressFile") {
			it.result("Hello World")
		}

		def output = new File(File.createTempDir(), "file.txt").toPath()
		def started, ended = false

		when:
		Download.create("$PATH/progressFile")
				.progress(new DownloadProgressListener() {
					@Override
					void onStart() {
						started = true
					}

					@Override
					void onProgress(long bytesTransferred, long contentLength) {
					}

					@Override
					void onEnd() {
						ended = true
					}
				})
				.downloadPath(output)

		then:
		started
		ended
	}

	def "Progress: String"() {
		setup:
		server.get("/progressFile") {
			it.result("Hello World")
		}

		def started, ended = false

		when:
		Download.create("$PATH/progressFile")
				.progress(new DownloadProgressListener() {
					@Override
					void onStart() {
						started = true
					}

					@Override
					void onProgress(long bytesTransferred, long contentLength) {
					}

					@Override
					void onEnd() {
						ended = true
					}
				})
				.downloadString()

		then:
		started
		ended
	}

	def "File: Async"() {
		setup:
		server.get("/async1") {
			it.result("Hello World")
		}

		def dir = File.createTempDir().toPath()

		when:
		new DownloadExecutor(2).withCloseable {
			Download.create("$PATH/async1").downloadPathAsync(dir.resolve("1.txt"), it)
			Download.create("$PATH/async1").downloadPathAsync(dir.resolve("2.txt"), it)
			Download.create("$PATH/async1").downloadPathAsync(dir.resolve("3.txt"), it)
			Download.create("$PATH/async1").downloadPathAsync(dir.resolve("4.txt"), it)
		}

		then:
		Files.readString(dir.resolve("4.txt")) == "Hello World"
	}

	def "File: Async result"() {
		setup:
		server.get("/async1") {
			it.result("Hello World")
		}

		def dir = File.createTempDir().toPath()

		when:
		boolean didDownload = false
		new DownloadExecutor(2).withCloseable {
			Download.create("$PATH/async1")
					.downloadPathAsync(dir.resolve("1.txt"), it)
					.thenAccept {
						didDownload = it.didDownload()
					}
		}

		then:
		didDownload
	}

	def "File: Async Error"() {
		setup:
		server.get("/async2") {
			it.result("Hello World")
		}

		def dir = File.createTempDir().toPath()

		when:
		new DownloadExecutor(2).withCloseable {
			Download.create("$PATH/async2").downloadPathAsync(dir.resolve("1.txt"), it)
			Download.create("$PATH/async2").downloadPathAsync(dir.resolve("2.txt"), it)
			Download.create("$PATH/async2").downloadPathAsync(dir.resolve("3.txt"), it)
			Download.create("$PATH/async2").downloadPathAsync(dir.resolve("4.txt"), it)

			Download.create("$PATH/asyncError").downloadPathAsync(dir.resolve("5.txt"), it)
			Download.create("$PATH/asyncError2").downloadPathAsync(dir.resolve("6.txt"), it)
		}

		then:
		thrown DownloadException
	}

	def "File: Large"() {
		setup:
		byte[] data = new byte[1024 * 1024 * 10] // 10MB
		new Random().nextBytes(data)

		server.get("/largeFile") {
			it.result(data)
		}

		def output = new File(File.createTempDir(), "file").toPath()

		when:
		def result = Download.create("$PATH/largeFile").downloadPath(output)

		then:
		Files.readAllBytes(output) == data
	}

	def "Lock: 残留锁被接管，既不抛异常也不强制重下"() {
		setup:
		int requestCount = 0

		server.get("/staleLock.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def dir = File.createTempDir().toPath()
		def output = dir.resolve("file.txt")
		def lockFile = dir.resolve("file.txt.lock")
		Files.writeString(output, "Hello World")
		// 造一个「持有者进程早已不在」的残留锁：用一个肯定不存在的 PID（模拟被杀掉的构建）
		writeStaleLock(lockFile)

		when: "本地文件完好，但上次构建留下的锁文件还在"
		def result = Download.create("$PATH/staleLock.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.downloadPath(output)

		then: "不抛异常、也不重下：锁只说明“曾经有人写过”，与本地文件是否可用无关"
		!result.didDownload()
		requestCount == 0
		Files.readString(output) == "Hello World"

		when: "同一个残留锁 + 目标文件缺失：必需接管而不是抛 DownloadException"
		Files.delete(output)
		def second = Download.create("$PATH/staleLock.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.downloadPath(output)

		then:
		second.didDownload()
		requestCount == 1
		Files.readString(output) == "Hello World"
		// 自己那一轮收尾时释放掉，不把残留留给下一个构建
		Files.notExists(lockFile)
	}

	def "Lock: 两个线程并发下载同一文件都能成功，且只下载一次"() {
		setup:
		int requestCount = 0
		byte[] data = new byte[1024 * 512]
		new Random().nextBytes(data)

		server.get("/concurrentLock.bin") {
			it.result(data)
			requestCount ++
		}

		def dir = File.createTempDir().toPath()
		def output = dir.resolve("concurrent.bin")
		def pool = Executors.newFixedThreadPool(2)
		def ready = new CountDownLatch(2)
		def start = new CountDownLatch(1)
		def sha1 = Checksum.of(data).sha1().hex()

		when:
		// maxRetries(1)：证明「并发下也能成功」不靠外部重试兜底
		def futures = (1..2).collect {
			pool.submit {
				ready.countDown()
				start.await()
				return Download.create("$PATH/concurrentLock.bin")
						.sha1(sha1)
						.maxRetries(1)
						.downloadPath(output)
			}
		}
		ready.await()
		start.countDown()
		def results = futures.collect { it.get(60, TimeUnit.SECONDS) }
		pool.shutdown()

		then:
		// 两个调用都成功，且文件内容完整
		results.size() == 2
		Arrays.equals(Files.readAllBytes(output), data)
		Files.notExists(dir.resolve("concurrent.bin.lock"))
		// 后到者等锁期间前一个已把文件落位，锁内重检直接复用，不需要第二趟网络
		requestCount == 1
	}

	def "Lock: 热路径不取锁"() {
		setup:
		int requestCount = 0

		server.get("/hotPath.txt") {
			it.result("Hello World")
			requestCount ++
		}

		def dir = File.createTempDir().toPath()
		def output = dir.resolve("hot.txt")
		Download.create("$PATH/hotPath.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.downloadPath(output)

		when:
		def result = Download.create("$PATH/hotPath.txt")
				.sha1("0a4d55a8d778e5022fab701977c5d840bbc486d0")
				.downloadPath(output)

		then:
		!result.didDownload()
		requestCount == 1
		// 热路径连锁文件都没碰过
		Files.notExists(dir.resolve("hot.txt.lock"))
		// 也不再留下 .part
		dir.toFile().listFiles().every { !it.name.endsWith(".part") }
	}

	// 造一个「持有者进程早已不在」的残留锁：PID 用一个肯定不存在的值（模拟被 kill 掉的构建）
	private static void writeStaleLock(Path lockFile) {
		Files.writeString(lockFile, "pid=2147483647 start=0 host=${InetAddress.getLocalHost().getHostName()} token=stale since=0")
		Files.setLastModifiedTime(lockFile, FileTime.from(Instant.now() - Duration.ofHours(1)))
	}

	def "File: Insecure protocol"() {
		setup:
		def output = new File(File.createTempDir(), "file").toPath()
		when:
		def result = Download.create("http://fabricmc.net").downloadPath(output)
		then:
		thrown IllegalArgumentException
	}

	// Known
	def "Download Mojang Mappings"() {
		setup:
		def file = File.createTempDir().toPath().resolve("client.txt")
		when:
		Download.create("https://piston-data.mojang.com/v1/objects/8e8c9be5dc27802caba47053d4fdea328f7f89bd/client.txt")
				.sha1("8e8c9be5dc27802caba47053d4fdea328f7f89bd")
				.downloadPath(file)

		then:
		Checksum.of(file).sha1().hex() == "8e8c9be5dc27802caba47053d4fdea328f7f89bd"
	}
}
