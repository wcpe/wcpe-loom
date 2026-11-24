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

package net.fabricmc.loom.test.unit.forge

import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import dev.architectury.loom.forge.tool.ForgeMavenizerArtifact
import dev.architectury.loom.forge.tool.ForgeMavenizerService
import dev.architectury.loom.forge.tool.ForgeMavenizerSourcesBundle
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.Checksum
import net.fabricmc.loom.util.cache.AtomicFiles

class ForgeMavenizerSourcesBundleTest extends Specification {
	@TempDir
	Path root
	private Path runtime
	private Path receipt
	private Path classes
	private Path sources
	private Path mappings
	private Path output
	private Object snapshot

	def setup() {
		runtime = root.resolve('runtime.jar')
		classes = Files.writeString(root.resolve('classes.jar'), 'Mojang classes')
		sources = Files.writeString(root.resolve('sources.jar'), 'Mojang sources')
		mappings = Files.writeString(root.resolve('map2srg.tsrg.gz'), 'TSRG')
		output = root.resolve('mavenizer-sources-v1.zip')
		receipt = ForgeMavenizerService.receipt(runtime)
		invoke(ForgeMavenizerService, 'publish', runtime, 'forge', 'mc', 'mcp', classes, mappings, { stage ->
			Files.writeString(stage, 'SRG classes')
		} as AtomicFiles.IOConsumer)
		snapshot = invoke(ForgeMavenizerSourcesBundle, 'readRuntime', runtime, receipt)
	}

	def '同批源码包可直接复用并还原两个原件'() {
		given:
		publish()
		def original = Checksum.of(output).sha256().hex()

		when:
		def first = ForgeMavenizerSourcesBundle.unpack(output, runtime, receipt, root.resolve('first'))
		def second = ForgeMavenizerSourcesBundle.unpack(output, runtime, receipt, root.resolve('second'))

		then:
		invoke(ForgeMavenizerSourcesBundle, 'isReusable', output, snapshot)
		Checksum.of(output).sha256().hex() == original
		Files.mismatch(first.mojangClassesJar(), classes) == -1
		Files.mismatch(second.sourcesJar(), sources) == -1
		first.sourcesJar() != second.sourcesJar()
	}

	def '暖导出的原始 classes 或 TSRG 不同就不覆盖已发布源码包与运行产物'() {
		given:
		publish()
		def original = Checksum.of(output).sha256().hex()
		def runtimeDigest = Checksum.of(runtime).sha256().hex()
		Files.writeString(changeMappings ? mappings : classes, '另一次导出')

		when:
		publish()

		then:
		thrown(IOException)
		Checksum.of(output).sha256().hex() == original
		Checksum.of(runtime).sha256().hex() == runtimeDigest

		where:
		changeMappings << [false, true]
	}

	def '源包被替换时检查内容摘要和固定 ZIP 布局'() {
		given:
		publish()
		def altered = root.resolve('altered.zip')
		new ZipFile(output.toFile()).withCloseable { original ->
			new ZipOutputStream(Files.newOutputStream(altered)).withCloseable { zip ->
				original.entries().each { entry ->
					zip.putNextEntry(new ZipEntry(entry.name))
					if (!extraEntry && entry.name == 'sources.jar') {
						zip.write('替换源码'.getBytes('UTF-8'))
					} else {
						original.getInputStream(entry).withCloseable { source -> source.transferTo(zip) }
					}
					zip.closeEntry()
				}
				if (extraEntry) {
					zip.putNextEntry(new ZipEntry('unexpected.jar'))
					zip.closeEntry()
				}
			}
		}

		when:
		ForgeMavenizerSourcesBundle.unpack(altered, runtime, receipt, root.resolve('work'))

		then:
		thrown(IOException)
		!invoke(ForgeMavenizerSourcesBundle, 'isReusable', altered, snapshot)
		!Files.exists(root.resolve('work'))

		where:
		extraEntry << [false, true]
	}

	def '收据字节或运行时 JAR 更换后无法读取已有源码包'() {
		given:
		publish()
		def original = Files.readAllBytes(receipt)
		if (changeReceipt) {
			Files.writeString(receipt, Files.readString(receipt).replace('|forge=forge', '|forge=other'))
		} else {
			Files.writeString(runtime, '另一份 SRG')
		}

		when:
		ForgeMavenizerSourcesBundle.unpack(output, runtime, receipt, root.resolve('work'))

		then:
		thrown(IOException)
		!Files.exists(root.resolve('work'))
		changeReceipt != (Files.readAllBytes(receipt) == original)

		where:
		changeReceipt << [false, true]
	}

	private void publish() {
		def artifact = new ForgeMavenizerArtifact(classes, root.resolve('unused.zip'), sources, mappings)
		invoke(ForgeMavenizerSourcesBundle, 'publish', output, runtime, receipt, snapshot, artifact)
	}

	private static Object invoke(Class<?> type, String name, Object... args) {
		def method = type.declaredMethods.find { it.name == name }
		method.setAccessible(true)

		try {
			return method.invoke(null, args)
		} catch (InvocationTargetException e) {
			throw e.cause
		}
	}
}
