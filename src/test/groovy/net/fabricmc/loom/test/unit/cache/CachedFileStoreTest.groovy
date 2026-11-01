/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.decompilers.cache.CachedFileStore
import net.fabricmc.loom.decompilers.cache.CachedFileStoreImpl
import net.fabricmc.loom.util.FileSystemUtil

class CachedFileStoreTest extends Specification {
	@TempDir
	Path testPath

	FileSystemUtil.Delegate zipDelegate
	Path root

	void setup() {
		zipDelegate = FileSystemUtil.getJarFileSystem(testPath.resolve("cache.zip"), true)
		root = zipDelegate.get().getPath("/")
	}

	void cleanup() {
		zipDelegate.close()
	}

	def "putEntry"() {
		given:
		def cacheRules = new CachedFileStoreImpl.CacheRules(100, Duration.ofDays(7))
		def store = new CachedFileStoreImpl(root, BYTE_ARRAY_SERIALIZER, cacheRules)
		when:
		store.putEntry("abc", "Hello world".bytes)
		then:
		Files.exists(root.resolve("abc"))
	}

	def "getEntry"() {
		given:
		def cacheRules = new CachedFileStoreImpl.CacheRules(100, Duration.ofDays(7))
		def store = new CachedFileStoreImpl(root, BYTE_ARRAY_SERIALIZER, cacheRules)
		when:
		store.putEntry("abc", "Hello world".bytes)
		def entry = store.getEntry("abc")
		def unknownEntry = store.getEntry("123")
		then:
		entry == "Hello world".bytes
		unknownEntry == null
	}

	def "pruneManyFiles"() {
		given:
		def cacheRules = new CachedFileStoreImpl.CacheRules(250, Duration.ofDays(7))
		def store = new CachedFileStoreImpl(root, BYTE_ARRAY_SERIALIZER, cacheRules)
		when:

		for (i in 0..<500) {
			def key = "test_" + i
			store.putEntry(key, "Hello world".bytes)
			// Higher files are older and should be removed.
			Files.setLastModifiedTime(root.resolve(key), FileTime.from(Instant.now().minusSeconds(i)))
		}

		store.prune()

		then:
		Files.exists(root.resolve("test_0"))
		Files.exists(root.resolve("test_100"))
		Files.notExists(root.resolve("test_300"))
	}

	def "pruneOldFiles"() {
		given:
		def cacheRules = new CachedFileStoreImpl.CacheRules(1000, Duration.ofSeconds(250))
		def store = new CachedFileStoreImpl(root, BYTE_ARRAY_SERIALIZER, cacheRules)
		when:

		for (i in 0..<500) {
			def key = "test_" + i
			store.putEntry(key, "Hello world".bytes)
			// Higher files are older and should be removed.
			Files.setLastModifiedTime(root.resolve(key), FileTime.from(Instant.now().minusSeconds(i)))
		}

		store.prune()

		then:
		Files.exists(root.resolve("test_0"))
		Files.exists(root.resolve("test_100"))
		Files.notExists(root.resolve("test_300"))
	}

	/**
	 * The eviction order has to survive the switch from insert-as-you-go to sort-once: it is the oldest
	 * files (by last modified time) that get dropped, and the newest that stay.
	 *
	 * <p>The mtimes are fixed rather than relative to {@code now} so the case is fully deterministic,
	 * and they are deliberately *not* in walk order: entries arrive in whatever order the file system
	 * hands them out, so an implementation that relied on insertion order instead of on the mtime
	 * would fail here.
	 *
	 * <p>Note the count: when the cache is over the limit the existing rule deletes exactly
	 * {@code maxFiles} of the oldest entries (not {@code size - maxFiles}), so 9 files with a limit of
	 * 5 leaves the 4 newest ones. That is upstream behaviour and is pinned here on purpose — this
	 * test is about keeping the eviction *rule* unchanged while the sorting changes.
	 */
	def "pruneEvictsOldestFilesFirst"() {
		given:
		final int maxFiles = 5
		final Instant base = Instant.parse("2024-01-01T00:00:00Z")
		def cacheRules = new CachedFileStoreImpl.CacheRules(maxFiles, Duration.ofDays(36500))
		def store = new CachedFileStoreImpl(root, BYTE_ARRAY_SERIALIZER, cacheRules)

		// Key -> how many seconds older it is than `base`; a larger number means an older entry.
		def ages = [
			"f_now": 0,
			"f_10s": 10,
			"f_20s": 20,
			"f_30s": 30,
			"f_40s": 40,
			"f_50s": 50,
			"f_60s": 60,
			"f_70s": 70,
			"f_80s": 80,
		]

		when:
		ages.each { key, age ->
			store.putEntry(key, "Hello world".bytes)
			Files.setLastModifiedTime(root.resolve(key), FileTime.from(base.minusSeconds(age)))
		}

		store.prune()

		then: "only the newest entries survive, and the oldest ones are gone"
		Files.newDirectoryStream(root).withCloseable { it.toList().size() } == 4

		Files.exists(root.resolve("f_now"))
		Files.exists(root.resolve("f_10s"))
		Files.exists(root.resolve("f_20s"))
		Files.exists(root.resolve("f_30s"))

		Files.notExists(root.resolve("f_40s"))
		Files.notExists(root.resolve("f_50s"))
		Files.notExists(root.resolve("f_60s"))
		Files.notExists(root.resolve("f_70s"))
		Files.notExists(root.resolve("f_80s"))
	}

	/**
	 * When the cache is within its size limit nothing may be deleted.
	 */
	def "pruneKeepsEverythingUnderTheLimit"() {
		given:
		final Instant base = Instant.parse("2024-01-01T00:00:00Z")
		def cacheRules = new CachedFileStoreImpl.CacheRules(10, Duration.ofDays(36500))
		def store = new CachedFileStoreImpl(root, BYTE_ARRAY_SERIALIZER, cacheRules)

		when:
		for (i in 0..<4) {
			store.putEntry("f_" + i, "Hello world".bytes)
			Files.setLastModifiedTime(root.resolve("f_" + i), FileTime.from(base.minusSeconds((i + 1) * 10)))
		}

		store.prune()

		then:
		Files.newDirectoryStream(root).withCloseable { it.toList().size() } == 4
	}

	private static CachedFileStore.EntrySerializer<byte[]> BYTE_ARRAY_SERIALIZER = new CachedFileStore.EntrySerializer<byte[]>() {
		@Override
		byte[] read(Path path) throws IOException {
			return Files.readAllBytes(path)
		}

		@Override
		void write(byte[] entry, Path path) throws IOException {
			Files.write(path, entry)
		}
	}
}
