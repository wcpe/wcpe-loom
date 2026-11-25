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
import dev.architectury.loom.forge.dependency.ForgeProvider
import dev.architectury.loom.forge.dependency.ForgeUserdevProvider
import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.configuration.DependencyInfo
import net.fabricmc.loom.util.Constants

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

	def '从缓存的 #shape 清单派生默认运行库'() {
		given:
		def project = ProjectBuilder.builder().withProjectDir(temp.toFile()).build()
		def forge = Stub(ForgeProvider) {
			getGlobalCache() >> temp.toFile()
		}
		project.extensions.add('loom', Stub(LoomGradleExtension) {
			getForgeProvider() >> forge
		})
		[
			Constants.Configurations.MCP_CONFIG,
			Constants.Configurations.SRG,
			Constants.Configurations.FORGE_UNIVERSAL
		].each {
			project.configurations.create(it)
		}
		def notation = "net.minecraftforge:forge:${version}".toString()
		def forgeDependency = project.dependencies.create(notation)
		def dependency = Stub(DependencyInfo) {
			getDependency() >> forgeDependency
			getResolvedVersion() >> version
			getDepString() >> notation
			resolveFile() >> { throw new AssertionError('已归一的 userdev 不应重新解析') }
		}
		def original = userdev3 ? [spec: 2, notchObf: true, libraries: [
				'org.jline:jline:3.5.1',
				'java3d:vecmath:1.5.2',
				'net.minecraftforge:legacydev:0.2.3.+:fatjar'
			]] : [inheritsFrom: '1.12.2', libraries: [
				[name: 'net.minecraft:launchwrapper:1.12']
			]]
		def rawConfig = temp.resolve('forge-config.json')
		Files.writeString(rawConfig, JsonOutput.toJson(original))
		def sources = new ByteArrayOutputStream()
		new ZipOutputStream(sources).withCloseable { addEntry(it, 'Example.java', 'class Example {}') }
		def cached = temp.resolve('forge-userdev.jar')
		new ZipOutputStream(Files.newOutputStream(cached)).withCloseable { zip ->
			addEntry(zip, 'merged_at.cfg', '')
			zip.putNextEntry(new ZipEntry('sources.zip'))
			zip.write(sources.toByteArray())
			zip.closeEntry()
		}
		def before = Files.readAllBytes(cached)
		def provider = new ForgeUserdevProvider(project)

		when:
		provider.provide(dependency)

		then:
		def libraries = provider.config.libraries()
		libraries.findAll { it.startsWith('org.jline:') || it.startsWith('java3d:') } == extras
		libraries.contains('net.minecraft:launchwrapper:1.12')
		!libraries.any { it.startsWith('net.minecraftforge:legacydev:') }
		Arrays.equals(Files.readAllBytes(cached), before)

		where:
		shape             | version            | userdev3 || extras
		'1.12.2 userdev3'  | '1.12.2-14.23.5.2860' | true     || [
			'org.jline:jline:3.5.1',
			'java3d:vecmath:1.5.2'
		]
		'其它版本 userdev3' | '1.11.2-13.20.1.2588' | true     || []
		'原生 FG2'         | '1.12.2-14.23.5.2847' | false    || []
	}

	private static void addEntry(ZipOutputStream zip, String name, String content) {
		zip.putNextEntry(new ZipEntry(name))
		zip.write(content.getBytes(StandardCharsets.UTF_8))
		zip.closeEntry()
	}
}
