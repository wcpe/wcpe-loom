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

package net.fabricmc.loom.test.unit

import java.nio.file.Files
import java.nio.file.Path

import com.google.gson.JsonSyntaxException
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.internal.LoomGradleSharedData
import net.fabricmc.loom.internal.LoomProjectData

class LoomProjectDataTest extends Specification {
	@TempDir
	Path tempDir

	def "项目数据桥可通过 Java 序列化传递并还原模组元数据"() {
		given:
		def modData = new LoomProjectData.ModData(
				'{"schemaVersion":1,"id":"example","version":"1.0.0","mixins":["example.mixins.json"]}',
				['example.mixins.json': 'mixin-data'.bytes]
				)
		def data = new LoomProjectData(':example', [modData], 'mappings', 'named', true, ['build/mixin.refmap.json'])
		def sharedData = new LoomGradleSharedData('test')
		sharedData.putProject(data)

		when:
		def bytes = new ByteArrayOutputStream()
		new ObjectOutputStream(bytes).withCloseable { it.writeObject(sharedData) }
		def restored = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).withCloseable { it.readObject() }

		then:
		restored instanceof LoomGradleSharedData
		def restoredData = restored.getProject(':example')
		restoredData.projectPath() == ':example'
		restoredData.mappingId() == 'mappings'
		restoredData.splitEnvironmentSourceSets()
		restoredData.createMods().first().id == 'example'
		restoredData.createMods().first().getMixinConfigurations() == ['example.mixins.json']
	}

	def "同一项目的不同实例按项目路径判等"() {
		given:
		// 内存共享表与导出文件两条路径会给出内容等价但实例不同的对象，两者必须被当成同一个项目，
		// 否则依赖项目求交会得到空集，distinct() 也会失效。
		def modData = new LoomProjectData.ModData('{"schemaVersion":1,"id":"example","version":"1.0.0"}', [:])
		def fromSharedTable = new LoomProjectData(':example', [modData], 'mappings', 'named', false, [])
		def fromExportedFile = new LoomProjectData(':example', [], 'mappings', 'named', false, [])

		expect:
		fromSharedTable == fromExportedFile
		fromSharedTable.hashCode() == fromExportedFile.hashCode()
		[
			fromSharedTable,
			fromExportedFile
		].toSet().size() == 1
		[
			fromSharedTable,
			fromExportedFile
		].stream().distinct().count() == 1

		and:
		fromSharedTable != new LoomProjectData(':other', [modData], 'mappings', 'named', false, [])
	}

	def "读取项目数据时区分「没有文件」与「文件损坏」"() {
		given:
		def missing = tempDir.resolve('missing.json')
		def broken = tempDir.resolve('broken.json')
		Files.writeString(broken, 'this is not json')

		expect: '文件不存在属于常规情形，不产生异常'
		LoomProjectData.read(missing).isEmpty()

		when: '文件存在但内容损坏时必须显式失败，不能静默当成「没有数据」'
		LoomProjectData.read(broken)

		then:
		thrown(JsonSyntaxException)
	}

	def "没有 schemaVersion 的模组元数据不会被伪造版本"() {
		given:
		def modData = new LoomProjectData.ModData('{"id":"example","version":"1.0.0"}', [:])

		when:
		def mod = modData.createMod()

		then: '缺失 schemaVersion 是 V0 的正常形态；补一个 1 会把它变成语义不同的 V1'
		mod.version == 0
		mod.id == 'example'
		mod.modVersion == '1.0.0'
	}
}
