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
import java.util.concurrent.atomic.AtomicReference

import org.gradle.api.GradleException
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.cache.CacheEntryLock

class CacheEntryLockTest extends Specification {
	@TempDir
	Path lockRoot

	def "单线程 withLock 能正常返回 action 结果"() {
		when:
		def result = CacheEntryLock.withLock(lockRoot, "key", Duration.ofSeconds(5)) { -> "ok" }

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
			pool.submit {
				ready.countDown()
				start.await()
				CacheEntryLock.withLock(lockRoot, "shared-key", Duration.ofSeconds(30)) {
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
				}
			}
		}
		ready.await()
		start.countDown()
		futures.each { it.get(60, TimeUnit.SECONDS) }
		pool.shutdown()

		then:
		counter.get() == threads
		conflicts.get() == 0
	}

	def "同一线程嵌套获取同一 key 立即返回（外层超时 1 秒，不可重入就会超时失败）"() {
		given: "三层都用 1 秒超时——这本身就是断言：不可重入时最内层会一直轮询到超时抛 GradleException"
		Duration shortTimeout = Duration.ofSeconds(1)
		AtomicInteger deepestCalls = new AtomicInteger(0)

		when:
		def result = CacheEntryLock.withLock(lockRoot, "nested-key", shortTimeout) {
			CacheEntryLock.withLock(lockRoot, "nested-key", shortTimeout) {
				CacheEntryLock.withLock(lockRoot, "nested-key", shortTimeout) {
					deepestCalls.incrementAndGet()
					return "深度 3 的返回值"
				}
			}
		}

		then: "返回值从最内层透传出来，且确实只执行了一次"
		result == "深度 3 的返回值"
		deepestCalls.get() == 1

		when: "嵌套退出后计数应归零、文件锁应已释放：另一个线程能以极短超时立刻拿到这把锁"
		AtomicReference<Throwable> otherThreadFailure = new AtomicReference<>()
		def pool = Executors.newSingleThreadExecutor()
		def other = pool.submit {
			try {
				CacheEntryLock.withLock(lockRoot, "nested-key", Duration.ofSeconds(1)) { -> null }
			} catch (Throwable t) {
				otherThreadFailure.set(t)
			}
		}
		other.get(30, TimeUnit.SECONDS)
		pool.shutdown()

		then:
		otherThreadFailure.get() == null
	}

	def "嵌套内层抛异常不泄露重入计数：同线程下次获取仍会真去抢文件锁"() {
		given:
		Duration shortTimeout = Duration.ofSeconds(1)
		RuntimeException boom = new RuntimeException("boom")
		Throwable thrown = null

		when: "同线程嵌套一次，内层抛异常"
		try {
			CacheEntryLock.withLock(lockRoot, "boom-key", shortTimeout) {
				CacheEntryLock.withLock(lockRoot, "boom-key", shortTimeout) {
					throw boom
				}
			}
		} catch (Exception e) {
			thrown = e
		}

		then: "异常原样上抛（两层 finally 都跑过了）"
		thrown.is(boom)

		when: "另一线程正持着同一把锁时，本线程再次获取"
		CountDownLatch heldByOther = new CountDownLatch(1)
		CountDownLatch releaseOther = new CountDownLatch(1)
		AtomicReference<Throwable> holderFailure = new AtomicReference<>()
		def pool = Executors.newSingleThreadExecutor()
		def holder = pool.submit {
			try {
				CacheEntryLock.withLock(lockRoot, "boom-key", Duration.ofSeconds(10)) {
					heldByOther.countDown()
					releaseOther.await()
					return null
				}
			} catch (Throwable t) {
				holderFailure.set(t)
			}
		}
		boolean acquired = heldByOther.await(10, TimeUnit.SECONDS)

		Throwable secondThrow = null

		if (acquired) {
			try {
				CacheEntryLock.withLock(lockRoot, "boom-key", shortTimeout) { -> null }
			} catch (Exception e) {
				secondThrow = e
			}
		}

		then: "若重入计数泄露成本线程「已持有」，这里会直接返回而不取锁；没泄露则等到超时"
		acquired
		secondThrow instanceof GradleException

		cleanup:
		releaseOther.countDown()
		holder?.get(30, TimeUnit.SECONDS)
		pool.shutdownNow()
		assert holderFailure.get() == null
	}

	def "不同线程拿同一 key 仍互斥：后到的线程等到超时并抛 GradleException"() {
		given:
		CountDownLatch heldByFirst = new CountDownLatch(1)
		CountDownLatch releaseFirst = new CountDownLatch(1)
		AtomicReference<Throwable> firstFailure = new AtomicReference<>()
		AtomicInteger insideCriticalSection = new AtomicInteger(0)
		AtomicReference<Throwable> waiterFailure = new AtomicReference<>()
		def pool = Executors.newFixedThreadPool(2)

		when: "线程 A 拿住锁不放"
		def first = pool.submit {
			try {
				CacheEntryLock.withLock(lockRoot, "mutex-key", Duration.ofSeconds(30)) {
					insideCriticalSection.incrementAndGet()
					heldByFirst.countDown()
					releaseFirst.await()
					insideCriticalSection.decrementAndGet()
					return null
				}
			} catch (Throwable t) {
				firstFailure.set(t)
			}
		}
		boolean firstHolding = heldByFirst.await(10, TimeUnit.SECONDS)

		and: "线程 B 用 1 秒超时去拿同一 key，只记结果不阻塞"
		if (firstHolding) {
			def waiter = pool.submit {
				try {
					CacheEntryLock.withLock(lockRoot, "mutex-key", Duration.ofSeconds(1)) {
						insideCriticalSection.incrementAndGet()
						return null
					}
				} catch (Throwable t) {
					waiterFailure.set(t)
				}
			}
			waiter.get(30, TimeUnit.SECONDS)
		}

		then: "可重入只限同线程：线程 B 不得进入临界区，而是等到超时"
		firstHolding
		firstFailure.get() == null
		waiterFailure.get() instanceof GradleException
		insideCriticalSection.get() == 1

		cleanup:
		releaseFirst.countDown()
		first?.get(30, TimeUnit.SECONDS)
		pool.shutdownNow()
	}
}
