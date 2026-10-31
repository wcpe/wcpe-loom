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

package net.fabricmc.loom.test.unit.cache

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import org.gradle.api.services.BuildServiceParameters
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.gradle.LoomCacheService

class LoomCacheServiceTest extends Specification {
	@TempDir
	Path tempDir

	Path lockRoot
	// BuildService 是 abstract 类，无法直接 new，使用匿名子类绕过 Gradle 实例化；
	// runExclusive 不会用到 getParameters，故返回 None 占位即可
	LoomCacheService service = new LoomCacheService() {
		// 以 Groovy 属性形式声明：自动生成的 getParameters() 满足 BuildService 抽象方法，
		// 同时避开 codenarc GetterMethodCouldBeProperty
		final BuildServiceParameters.None parameters = null
	}

	def setup() {
		lockRoot = tempDir.resolve("locks")
	}

	def "runExclusive 执行 action 并正常返回"() {
		given:
		AtomicInteger callCount = new AtomicInteger(0)
		Callable<Void> action = { -> callCount.incrementAndGet(); null }

		when:
		service.runExclusive(lockRoot, "key", Duration.ofSeconds(30), action)

		then:
		callCount.get() == 1
	}

	def "同名异步缓存复用实例且不同名称隔离"() {
		when:
		def metadata1 = service.getAsyncCache("metadata")
		def metadata2 = service.getAsyncCache("metadata")
		def mappings = service.getAsyncCache("mappings")

		then:
		metadata1.is(metadata2)
		!metadata1.is(mappings)
	}

	def "同 key 并发 runExclusive 严格互斥且每个 action 都执行"() {
		given:
		int threads = 8
		AtomicInteger totalRuns = new AtomicInteger(0)
		AtomicInteger inside = new AtomicInteger(0)
		AtomicBoolean overlapDetected = new AtomicBoolean(false)
		CountDownLatch ready = new CountDownLatch(threads)
		CountDownLatch start = new CountDownLatch(1)
		def pool = Executors.newFixedThreadPool(threads)

		Callable<Void> action = {
			->
			// 进入临界区：若已有其它线程在内，说明互斥被破坏
			if (inside.incrementAndGet() != 1) {
				overlapDetected.set(true)
			}

			Thread.sleep(20)
			totalRuns.incrementAndGet()
			inside.decrementAndGet()
			return null
		}

		when:
		def futures = (1..threads).collect {
			pool.submit {
				ready.countDown()
				start.await()
				service.runExclusive(lockRoot, "key", Duration.ofSeconds(60), action)
			}
		}
		ready.await()
		start.countDown()
		futures.each { it.get(120, TimeUnit.SECONDS) }
		pool.shutdown()

		then:
		!overlapDetected.get()
		totalRuns.get() == threads
	}

	def "不同分片 key 的并发 runExclusive 可同时进入临界区（下载与合并不再互阻）"() {
		// 背景：同一 MC 版本的「下载/抽取」「合并」「拆分」曾经共用一把锁（key = minecraft:<mc>），
		// 于是冷启动时全部排成一条队。分片后三者各用各的 key，本用例直接验证这一点。
		//
		// 断言不靠墙钟阈值，而是靠两个 latch 互相确认：两边都必须先进入临界区、再等对方进入；
		// 只要有一边被挡在锁外，等对方的那个 latch 就永远不会归零。如果锁被合并回去，本用例会卡住
		// 到上限后判 sawOther == false 而失败，而不是靠「跑得快不快」猜。
		given:
		CountDownLatch downloadInside = new CountDownLatch(1)
		CountDownLatch mergeInside = new CountDownLatch(1)
		AtomicBoolean downloadSawMerge = new AtomicBoolean(false)
		AtomicBoolean mergeSawDownload = new AtomicBoolean(false)
		def pool = Executors.newFixedThreadPool(2)

		// 失败上限，不是断言依据：成功路径上两个 latch 毫秒级归零
		long boundSeconds = 30

		when:
		def downloadFuture = pool.submit {
			service.runExclusive(lockRoot, "minecraft-download:1.20.1", Duration.ofSeconds(60)) {
				downloadInside.countDown()
				downloadSawMerge.set(mergeInside.await(boundSeconds, TimeUnit.SECONDS))
				return null
			}
		}
		def mergeFuture = pool.submit {
			service.runExclusive(lockRoot, "minecraft-merge:1.20.1", Duration.ofSeconds(60)) {
				mergeInside.countDown()
				mergeSawDownload.set(downloadInside.await(boundSeconds, TimeUnit.SECONDS))
				return null
			}
		}
		downloadFuture.get(boundSeconds * 2, TimeUnit.SECONDS)
		mergeFuture.get(boundSeconds * 2, TimeUnit.SECONDS)

		then: "两个临界区确实重叠过（各自看到了对方在内）"
		downloadSawMerge.get()
		mergeSawDownload.get()

		cleanup:
		pool.shutdownNow()
	}

	def "不同分片 key 的锁文件互不相同（分片不会退化成共用一把锁）"() {
		// 与上一个用例互补：那里验「能并行」，这里验「锁文件确实分开了」。
		// 只看行为的话，两个 key 里只要有一个被哈希碰撞撞到同一文件就会误判；这里直接看目录里落了几把锁。
		given:
		def keys = [
			"minecraft-download:1.20.1",
			"minecraft-merge:1.20.1",
			"minecraft-split:1.20.1"
		]

		when:
		keys.forEach { key ->
			service.runExclusive(lockRoot, key, Duration.ofSeconds(30)) {
				-> null
			}
		}
		def lockFiles = lockRoot.toFile().listFiles().findAll { it.name.endsWith(".lock") }*.name.sort()

		then:
		lockFiles.size() == keys.size()
	}
}
