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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.architectury.loom.forge.dependency.ForgeUserdevProvider
import spock.lang.Specification
import spock.lang.TempDir

class ForgeUserdevProviderTest extends Specification {
	@TempDir
	Path temp

	def "merges every userdev3 access transformer"() {
		given:
		Path userdev = temp.resolve('userdev.jar')
		new ZipOutputStream(Files.newOutputStream(userdev)).withCloseable { zip ->
			addEntry(zip, 'ats/first.cfg', 'public-f net.minecraft.First one\n')
			addEntry(zip, 'ats/second.cfg', 'protected net.minecraft.Second two')
		}
		JsonArray ats = new JsonArray()
		ats.add('ats/first.cfg')
		ats.add('ats/second.cfg')
		JsonObject config = new JsonObject()
		config.add('ats', ats)

		when:
		def method = ForgeUserdevProvider.getDeclaredMethod('mergeUserdev3Ats', Path, JsonObject)
		method.accessible = true
		byte[] result = method.invoke(null, userdev, config)

		then:
		new String(result, StandardCharsets.UTF_8) == 'public-f net.minecraft.First one\nprotected net.minecraft.Second two\n'
	}

	private static void addEntry(ZipOutputStream zip, String name, String content) {
		zip.putNextEntry(new ZipEntry(name))
		zip.write(content.getBytes(StandardCharsets.UTF_8))
		zip.closeEntry()
	}
}
