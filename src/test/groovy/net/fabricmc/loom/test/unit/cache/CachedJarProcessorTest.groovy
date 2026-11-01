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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Duration
import java.util.concurrent.TimeUnit

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.decompilers.ClassLineNumbers
import net.fabricmc.loom.decompilers.cache.CachedData
import net.fabricmc.loom.decompilers.cache.CachedFileStore
import net.fabricmc.loom.decompilers.cache.CachedFileStoreImpl
import net.fabricmc.loom.decompilers.cache.CachedJarProcessor
import net.fabricmc.loom.test.util.ZipTestUtils
import net.fabricmc.loom.util.ZipUtils

class CachedJarProcessorTest extends Specification {
	static Map<String, byte[]> jarEntries = [
		"net/fabricmc/Example.class": newClass("net/fabricmc/Example"),
		"net/fabricmc/other/Test.class": newClass("net/fabricmc/other/Test", [] as String[], [
			"net/fabricmc/other/Test\$Inner",
			"net/fabricmc/other/Test\$1"
		] as String[]),
		"net/fabricmc/other/Test\$Inner.class": newInnerClass("net/fabricmc/other/Test\$Inner", "net/fabricmc/other/Test", "Inner"),
		"net/fabricmc/other/Test\$1.class": newInnerClass("net/fabricmc/other/Test\$1", "net/fabricmc/other/Test"),
		"net/fabricmc/other/Test\$Dangling.class": newInnerClass("net/fabricmc/other/Test\$Dangling", "net/fabricmc/other/Test"),
	]

	static String ExampleHash = "abc123/db5c3a2d04e0c6ea03aef0d217517aa0233f9b8198753d3c96574fe5825a13c4"
	static String TestHash = "abc123/62db36eda67a8bdb19626126176aa0fccc64a509ac808db166e9795caac5a784"
	static String TestDanglingHash = "abc123/6659d90f0ac7ee783c8b44e51c08d4388477ef3fa998f1d6d4fc95f4adf628ca"

	// The temporary jars prepareJob creates, by createTempFile prefix.
	static final List<String> CACHE_JAR_PREFIXES = [
		"loom-cache-incomplete",
		"loom-cache-existingClasses",
		"loom-cache-existingSources",
		"loom-cache-output",
	]

	static CachedData ExampleCachedData = new CachedData("net/fabricmc/Example", "Example sources", lineNumber("net/fabricmc/Example"))
	static CachedData TestCachedData = new CachedData("net/fabricmc/other/Test", "Test sources", lineNumber("net/fabricmc/other/Test"))
	static CachedData TestDanglingCachedData = new CachedData("net/fabricmc/other/Test\$Dangling", "Test\$Dangling sources", lineNumber("net/fabricmc/other/Test\$Dangling"))

	@TempDir
	Path testPath

	def "prepare full work job"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar)
		def workJob = workRequest.job() as CachedJarProcessor.FullWorkJob

		then:
		workRequest.lineNumbers() == null
		workJob.outputNameMap().size() == 3

		// Expect 3 calls looking for the existing entry in the cache
		3 * cache.getEntry(_) >> null

		0 * _ // Strict mock
	}

	def "prepare partial work job"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar)
		def workJob = workRequest.job() as CachedJarProcessor.PartialWorkJob
		def lineMap = workRequest.lineNumbers().lineMap()

		then:
		lineMap.size() == 1
		lineMap.get("net/fabricmc/Example") == ExampleCachedData.lineNumbers()

		workJob.outputNameMap().size() == 2
		ZipUtils.unpackNullable(workJob.existingSources(), "net/fabricmc/Example.java") == "Example sources".bytes
		ZipUtils.unpackNullable(workJob.existingClasses(), "net/fabricmc/Example.class") == newClass("net/fabricmc/Example")

		// Provide one cached entry
		// And then 2 calls not finding the entry in the cache
		1 * cache.getEntry(ExampleHash) >> ExampleCachedData
		2 * cache.getEntry(_) >> null

		0 * _ // Strict mock
	}

	def "prepare completed work job"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar)
		def workJob = workRequest.job() as CachedJarProcessor.CompletedWorkJob
		def lineMap = workRequest.lineNumbers().lineMap()

		then:
		lineMap.size() == 3
		lineMap.get("net/fabricmc/Example") == ExampleCachedData.lineNumbers()
		lineMap.get("net/fabricmc/other/Test") == TestCachedData.lineNumbers()
		lineMap.get("net/fabricmc/other/Test\$Dangling") == TestDanglingCachedData.lineNumbers()

		workJob.completed() != null
		ZipUtils.unpackNullable(workJob.completed(), "net/fabricmc/Example.java") == "Example sources".bytes
		ZipUtils.unpackNullable(workJob.completed(), "net/fabricmc/other/Test.java") == "Test sources".bytes
		ZipUtils.unpackNullable(workJob.completed(), "net/fabricmc/other/Test\$Dangling.java") == "Test\$Dangling sources".bytes

		// Provide one cached entry
		// And then 3 calls not finding the entry in the cache
		1 * cache.getEntry(ExampleHash) >> ExampleCachedData
		1 * cache.getEntry(TestHash) >> TestCachedData
		1 * cache.getEntry(TestDanglingHash) >> TestDanglingCachedData

		0 * _ // Strict mock
	}

	def "complete full work job"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar)
		def workJob = workRequest.job() as CachedJarProcessor.FullWorkJob

		// Do the work, such as decompiling.
		ZipUtils.add(workJob.output(), "net/fabricmc/Example.java", "Example sources")
		ZipUtils.add(workJob.output(), "net/fabricmc/other/Test.java", "Test sources")
		ZipUtils.add(workJob.output(), "net/fabricmc/other/Test\$Dangling.java", "Test\$Dangling sources")

		def outputJar = Files.createTempFile("loom-test-output", ".jar")
		Files.delete(outputJar)

		ClassLineNumbers lineNumbers = lineNumbers([
			"net/fabricmc/Example",
			"net/fabricmc/other/Test",
			"net/fabricmc/other/Test\$Dangling"
		])
		processor.completeJob(outputJar, workJob, lineNumbers)

		then:
		workJob.outputNameMap().size() == 3

		ZipUtils.unpackNullable(outputJar, "net/fabricmc/Example.java") == "Example sources".bytes
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/other/Test.java") == "Test sources".bytes
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/other/Test\$Dangling.java") == "Test\$Dangling sources".bytes

		// Expect 3 calls looking for the existingSources entry in the cache
		1 * cache.getEntry(ExampleHash) >> null
		1 * cache.getEntry(TestHash) >> null
		1 * cache.getEntry(TestDanglingHash) >> null

		// Expect the new work to be put into the cache
		1 * cache.putEntry(ExampleHash, ExampleCachedData)
		1 * cache.putEntry(TestHash, TestCachedData)
		1 * cache.putEntry(TestDanglingHash, TestDanglingCachedData)

		0 * _ // Strict mock
	}

	def "complete partial work job"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar)
		def workJob = workRequest.job() as CachedJarProcessor.PartialWorkJob

		// Do the work
		ZipUtils.add(workJob.output(), "net/fabricmc/other/Test.java", "Test sources")

		def outputJar = Files.createTempFile("loom-test-output", ".jar")
		Files.delete(outputJar)

		ClassLineNumbers lineNumbers = lineNumbers([
			"net/fabricmc/Example",
			"net/fabricmc/other/Test",
			"net/fabricmc/other/Test\$Dangling"
		])
		processor.completeJob(outputJar, workJob, lineNumbers)

		then:
		workJob.outputNameMap().size() == 1

		ZipUtils.unpackNullable(outputJar, "net/fabricmc/Example.java") == "Example sources".bytes
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/other/Test.java") == "Test sources".bytes
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/other/Test\$Dangling.java") == "Test\$Dangling sources".bytes

		// The cache already contains sources for example, but not for test
		1 * cache.getEntry(ExampleHash) >> ExampleCachedData
		1 * cache.getEntry(TestHash) >> null
		1 * cache.getEntry(TestDanglingHash) >> TestDanglingCachedData

		// Expect the new work to be put into the cache
		1 * cache.putEntry(TestHash, TestCachedData)

		0 * _ // Strict mock
	}

	/**
	 * When every class is a cache hit nothing is going to be decompiled, so the jar holding the cached
	 * classes — which the partial branch hands to the decompiler as its classpath — is never read.
	 * Copying the whole set of classes into it is pure waste (the jar was discarded again right away),
	 * so it must not be created at all.
	 */
	def "prepare job does not create the existing classes jar when every class is a cache hit"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def createdJars = createJarsOf(processor, jar)

		then: "no classes jar is created, only the ones the completed branch still uses"
		createdJars.sort() == [
			"loom-cache-existingSources",
			"loom-cache-incomplete",
			"loom-cache-output"
		]

		and: "the cache is still consulted for every class"
		1 * cache.getEntry(ExampleHash) >> ExampleCachedData
		1 * cache.getEntry(TestHash) >> TestCachedData
		1 * cache.getEntry(TestDanglingHash) >> TestDanglingCachedData

		0 * _ // Strict mock
	}

	/**
	 * A full miss needs no classes jar either: the whole input jar is decompiled, so nothing from the
	 * cache has to be put on the decompiler classpath.
	 */
	def "prepare job does not create the existing classes jar on a full miss"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def createdJars = createJarsOf(processor, jar)

		then:
		createdJars.sort() == [
			"loom-cache-existingSources",
			"loom-cache-incomplete",
			"loom-cache-output"
		]

		3 * cache.getEntry(_) >> null

		0 * _ // Strict mock
	}

	/**
	 * The partial branch still has to hand the decompiler the classes that came from the cache, so the
	 * jar is created there and contains them.
	 */
	def "prepare job creates the existing classes jar when some classes are left to decompile"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def createdJars = createJarsOf(processor, jar)
		def workJob = processor.prepareJob(jar).job() as CachedJarProcessor.PartialWorkJob

		then:
		createdJars.sort() == [
			"loom-cache-existingClasses",
			"loom-cache-existingSources",
			"loom-cache-incomplete",
			"loom-cache-output"
		]
		ZipUtils.unpackNullable(workJob.existingClasses(), "net/fabricmc/Example.class") == newClass("net/fabricmc/Example")

		2 * cache.getEntry(ExampleHash) >> ExampleCachedData
		4 * cache.getEntry(_) >> null

		0 * _ // Strict mock
	}

	def "complete completed work job"() {
		given:
		def jar = ZipTestUtils.createZipFromBytes(jarEntries)
		def cache = Mock(CachedFileStore)
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar)
		def workJob = workRequest.job() as CachedJarProcessor.CompletedWorkJob

		def outputJar = Files.createTempFile("loom-test-output", ".jar")
		Files.delete(outputJar)

		ClassLineNumbers lineNumbers = lineNumbers([
			"net/fabricmc/Example",
			"net/fabricmc/other/Test",
			"net/fabricmc/other/Test\$Dangling"
		])
		processor.completeJob(outputJar, workJob, lineNumbers)

		then:
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/Example.java") == "Example sources".bytes
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/other/Test.java") == "Test sources".bytes
		ZipUtils.unpackNullable(outputJar, "net/fabricmc/other/Test\$Dangling.java") == "Test\$Dangling sources".bytes

		// The cache already contains sources for example, but not for test
		1 * cache.getEntry(ExampleHash) >> ExampleCachedData
		1 * cache.getEntry(TestHash) >> TestCachedData
		1 * cache.getEntry(TestDanglingHash) >> TestDanglingCachedData

		0 * _ // Strict mock
	}

	def "hierarchy change invalidates cache"() {
		given:
		def jar1 = ZipTestUtils.createZipFromBytes(
				[
					"net/fabricmc/Example.class": newClass("net/fabricmc/Example"),
					"net/fabricmc/other/Test.class": newClass("net/fabricmc/other/Test", [] as String[], [
						"net/fabricmc/other/Test\$Inner",
						"net/fabricmc/other/Test\$1"
					] as String[]),
					"net/fabricmc/other/Test\$Inner.class": newInnerClass("net/fabricmc/other/Test\$Inner", "net/fabricmc/other/Test", "Inner", ["net/fabricmc/Example"] as String[]),
					"net/fabricmc/other/Test\$1.class": newInnerClass("net/fabricmc/other/Test\$1", "net/fabricmc/other/Test"),
				]
				)
		// The second jar changes Example, so we would expect Test to be invalidated, thus causing a full decompile in this case
		def jar2 = ZipTestUtils.createZipFromBytes(
				[
					"net/fabricmc/Example.class": newClass("net/fabricmc/Example", ["java/lang/Runnable"] as String[]),
					"net/fabricmc/other/Test.class": newClass("net/fabricmc/other/Test", [] as String[], [
						"net/fabricmc/other/Test\$Inner",
						"net/fabricmc/other/Test\$1"
					] as String[]),
					"net/fabricmc/other/Test\$Inner.class": newInnerClass("net/fabricmc/other/Test\$Inner", "net/fabricmc/other/Test", "Inner", ["net/fabricmc/Example"] as String[]),
					"net/fabricmc/other/Test\$1.class": newInnerClass("net/fabricmc/other/Test\$1", "net/fabricmc/other/Test"),
				]
				)

		def cache = new CachedFileStoreImpl<>(testPath.resolve("cache"), CachedData.SERIALIZER, new CachedFileStoreImpl.CacheRules(50_000, Duration.ofDays(90)))
		def processor = new CachedJarProcessor(cache, "abc123")

		when:
		def workRequest = processor.prepareJob(jar1)
		def workJob = workRequest.job() as CachedJarProcessor.FullWorkJob
		def outputSourcesJar = workJob.output()

		// Do the work, such as decompiling.
		ZipUtils.add(outputSourcesJar, "net/fabricmc/Example.java", "Example sources")
		ZipUtils.add(outputSourcesJar, "net/fabricmc/other/Test.java", "Test sources")

		// Complete the job
		def outputJar = Files.createTempFile("loom-test-output", ".jar")
		Files.delete(outputJar)

		ClassLineNumbers lineNumbers = lineNumbers([
			"net/fabricmc/Example",
			"net/fabricmc/other/Test"
		])
		processor.completeJob(outputJar, workJob, lineNumbers)

		workRequest = processor.prepareJob(jar2)
		def newWorkJob = workRequest.job()

		then:
		newWorkJob instanceof CachedJarProcessor.FullWorkJob
	}

	/**
	 * Run {@code prepareJob} while watching the temporary directory, and return which of the cache jars
	 * it created. The expected names are reported by prefix, as every one of them is a
	 * {@code createTempFile} temp file with a random suffix.
	 *
	 * <p>Watched rather than asserted by path because all of these jars are deleted again before
	 * {@code prepareJob} returns, so listing the directory before/after cannot see them; watching is
	 * done from the outside so the code under test needs no test hook. The watch is non-recursive, which
	 * is all {@code createTempFile} in the temp dir itself needs.
	 */
	private Set<String> createJarsOf(CachedJarProcessor processor, Path jar) {
		def tempDir = Paths.get(System.getProperty("java.io.tmpdir"))
		def created = new LinkedHashSet<String>()
		def watchService = FileSystems.getDefault().newWatchService()

		try {
			tempDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)
			processor.prepareJob(jar)

			// Drain everything the watch service queued up while the job was being prepared.
			WatchKey key

			while ((key = watchService.poll(500, TimeUnit.MILLISECONDS)) != null) {
				for (WatchEvent<?> event : key.pollEvents()) {
					def name = event.context()?.toString()

					if (name == null) {
						// Overflow event, nothing to identify.
						continue
					}

					def prefix = CACHE_JAR_PREFIXES.find { name.startsWith(it) }

					if (prefix != null) {
						created.add(prefix)
					}
				}

				key.reset()
			}
		} finally {
			watchService.close()
		}

		return created
	}

	private static ClassLineNumbers lineNumbers(List<String> names) {
		return new ClassLineNumbers(names.collectEntries { [it, lineNumber(it)] })
	}

	private static ClassLineNumbers.Entry lineNumber(String name) {
		return new ClassLineNumbers.Entry(name, 0, 0, [:])
	}

	private static byte[] newClass(String name, String[] interfaces = null, String[] innerNames = null, String superName = "java/lang/Object") {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, interfaces)
		if (innerNames != null) {
			for (String innerName : innerNames) {
				writer.visitInnerClass(innerName, null, null, 0)
			}
		}
		return writer.toByteArray()
	}

	private static byte[] newInnerClass(String name, String outerClass, String innerName = null, String[] interfaces = null, String superName = "java/lang/Object") {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, interfaces)
		writer.visitInnerClass(name, outerClass, innerName, 0)
		if (innerName == null) {
			writer.visitOuterClass(outerClass, null, null)
		}
		return writer.toByteArray()
	}
}
