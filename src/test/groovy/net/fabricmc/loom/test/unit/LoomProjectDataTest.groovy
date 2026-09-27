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

import spock.lang.Specification

import net.fabricmc.loom.internal.LoomGradleSharedData
import net.fabricmc.loom.internal.LoomProjectData

class LoomProjectDataTest extends Specification {
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
}
