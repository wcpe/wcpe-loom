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

import spock.lang.Specification
import spock.lang.TempDir

import org.gradle.api.services.BuildServiceParameters

import net.fabricmc.loom.util.gradle.LoomCacheService

class LoomCacheServiceTest extends Specification {
	@TempDir
	Path tempDir

	Path lockRoot
	// BuildService 是 abstract 类，无法直接 new，使用匿名子类绕过 Gradle 实例化；
	// runExclusive 不会用到 getParameters，故返回 None 占位即可
	LoomCacheService service = new LoomCacheService() {
		@Override
		BuildServiceParameters.None getParameters() {
			return null
		}
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

	def "同 key 并发 runExclusive 严格互斥且每个 action 都执行"() {
		given:
		int threads = 8
		AtomicInteger totalRuns = new AtomicInteger(0)
		AtomicInteger inside = new AtomicInteger(0)
		AtomicBoolean overlapDetected = new AtomicBoolean(false)
		CountDownLatch ready = new CountDownLatch(threads)
		CountDownLatch start = new CountDownLatch(1)
		def pool = Executors.newFixedThreadPool(threads)

		Callable<Void> action = { ->
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
			pool.submit({
				ready.countDown()
				start.await()
				service.runExclusive(lockRoot, "key", Duration.ofSeconds(60), action)
			})
		}
		ready.await()
		start.countDown()
		futures.each { it.get(120, TimeUnit.SECONDS) }
		pool.shutdown()

		then:
		!overlapDetected.get()
		totalRuns.get() == threads
	}
}
