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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.cache.CacheEntryLock

class CacheEntryLockTest extends Specification {
	@TempDir
	Path lockRoot

	def "单线程 withLock 能正常返回 action 结果"() {
		when:
		def result = CacheEntryLock.withLock(lockRoot, "key", Duration.ofSeconds(5), { -> "ok" })

		then:
		result == "ok"
	}

	def "同一 key 并发互斥：计数最终等于线程数且无临界区重叠"() {
		given:
		int threads = 8
		AtomicInteger counter = new AtomicInteger(0)
		AtomicBoolean inside = new AtomicBoolean(false)
		AtomicInteger conflicts = new AtomicInteger(0)
		CountDownLatch ready = new CountDownLatch(threads)
		CountDownLatch start = new CountDownLatch(1)
		def pool = Executors.newFixedThreadPool(threads)

		when:
		def futures = (1..threads).collect {
			pool.submit({
				ready.countDown()
				start.await()
				CacheEntryLock.withLock(lockRoot, "shared-key", Duration.ofSeconds(30), { ->
					// 进入临界区：若发现已有线程在内则记录冲突
					if (!inside.compareAndSet(false, true)) {
						conflicts.incrementAndGet()
					}

					// 读-改-写，并 sleep 制造重叠窗口
					int v = counter.get()
					Thread.sleep(5)
					counter.set(v + 1)
					inside.set(false)
					return null
				})
			})
		}
		ready.await()
		start.countDown()
		futures.each { it.get(60, TimeUnit.SECONDS) }
		pool.shutdown()

		then:
		counter.get() == threads
		conflicts.get() == 0
	}
}
