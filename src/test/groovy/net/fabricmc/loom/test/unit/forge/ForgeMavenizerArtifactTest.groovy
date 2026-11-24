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

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import dev.architectury.loom.forge.tool.ForgeMavenizerArtifact
import groovy.json.JsonOutput
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import spock.lang.Specification
import spock.lang.TempDir

class ForgeMavenizerArtifactTest extends Specification {
	private static final String MC = '1.21.11'
	private static final String FORGE = '1.21.11-61.2.5'
	private static final String MCP = '1.21.11-20251223.124241'
	private static final String GAME = 'net/minecraft/client/Minecraft'
	private static final String API = 'net/minecraftforge/common/MinecraftForge'
	@TempDir Path repo
	Path dir, module, classes, mappings
	Map descriptor

	def setup() {
		dir = Files.createDirectories(repo.resolve("net/minecraftforge/forge/$FORGE"))
		classes = dir.resolve("forge-${FORGE}.jar")
		zip(classes, [(GAME + '.class'): type(GAME), (API + '.class'): type(API),
			'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\r\nAutomatic-Module-Name: net.minecraftforge.forge\r\n\r\n',
			'META-INF/services/demo.Service': 'demo.Provider\n', 'META-INF/mods.toml': 'modId="forge"'])
		def metadata = dir.resolve("forge-${FORGE}-metadata.zip")
		zip(metadata, ['launcher/runs.json': '{}', 'minecraft/version.json': '{}'])
		def sources = dir.resolve("forge-${FORGE}-sources.jar")
		zip(sources, [(GAME + '.java'): '// 游戏源码', (API + '.java'): '// Forge 源码'])
		def mapped = ['net.minecraftforge.mappings.channel': 'official', 'net.minecraftforge.mappings.version': MC,
			'org.gradle.usage': 'java-runtime', 'org.gradle.libraryelements': 'jar']
		def runtime = mapped + ['org.gradle.category': 'library']
		descriptor = [component: [group: 'net.minecraftforge', module: 'forge', version: FORGE], variants: [
				[name: 'classes', attributes: runtime, files: [fileEntry(classes)], dependencies: [
						[group: 'net.minecraft', module: 'mappings_official', version: [requires: MCP]]
					]],
				[name: 'sources', attributes: mapped + ['org.gradle.category': 'documentation', 'org.gradle.docstype': 'sources'], files: [fileEntry(sources)]],
				[name: 'metadata', attributes: ['org.gradle.usage': 'metadata'], files: [fileEntry(metadata)]]
			]]
		module = dir.resolve("forge-${FORGE}.module")
		mappings = Files.createDirectories(repo.resolve("net/minecraft/mappings_official/$MCP")).resolve("mappings_official-${MCP}-map2srg.tsrg.gz")
		new GZIPOutputStream(Files.newOutputStream(mappings)).withCloseable { it.write("tsrg2 left right\n$GAME $GAME\n\tvalue f_1_\n".bytes) }
	}

	def '共享运行文件重映射字段而保留服务、Forge 类和清单'() {
		given:
		def runtime = descriptor.variants.remove(0)
		['linux', 'macos', 'windows'].each { os ->
			descriptor.variants << runtime + [name: "classes-$os", attributes: runtime.attributes + ['net.minecraftforge.native.operatingSystem': os]]
		}
		def output = repo.resolve('converted.tmp')

		when:
		def resolved = resolve()
		resolved.writeSrgJar(output)

		then:
		resolved.classesJar() == classes
		resolved.officialToSrgMappings() == mappings
		new JarFile(output.toFile()).withCloseable { jar ->
			def node = new ClassNode()
			new ClassReader(jar.getInputStream(jar.getEntry(GAME + '.class'))).accept(node, 0)
			assert node.fields*.name == ['f_1_']
			assert jar.getEntry(API + '.class')
			assert jar.getEntry('META-INF/mods.toml')
			assert jar.getInputStream(jar.getEntry('META-INF/services/demo.Service')).text.trim() == 'demo.Provider'
			assert jar.manifest.mainAttributes.getValue('Automatic-Module-Name') == 'net.minecraftforge.forge'
			true
		}
	}

	def '拒绝与当前生产不一致的模块：#reason'() {
		when:
		mutate(descriptor)
		resolve()

		then:
		thrown(IOException)

		where:
		reason   | mutate
		'坐标'   | { it.component.module = 'other' }
		'命名'   | { it.variants[0].attributes['net.minecraftforge.mappings.channel'] = 'srg' }
		'版本'   | { it.variants[0].attributes['net.minecraftforge.mappings.version'] = '1.21.10' }
		'跨目录' | { it.variants[0].files[0].url = '../outside.jar' }
		'校验和' | { it.variants[0].files[0].sha256 = '0' * 64 }
		'旧映射' | { it.variants[0].dependencies[0].version.requires = 'other' }
	}

	def '不把同目录的 map2obf 当作缺失的 map2srg'() {
		given:
		Files.move(mappings, mappings.resolveSibling(mappings.fileName.toString().replace('map2srg', 'map2obf')))

		when:
		resolve()

		then:
		thrown(IOException)
	}

	def '拒绝只有类签名没有 Forge 运行资源的 stub 包'() {
		given:
		zip(classes, [(GAME + '.class'): type(GAME), (API + '.class'): type(API), 'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\r\n\r\n'])
		descriptor.variants[0].files = [fileEntry(classes)]

		when:
		resolve()

		then:
		thrown(IOException)
	}

	def '同名 TSRG2 头也不能把混淆方向误认为 SRG'() {
		given:
		new GZIPOutputStream(Files.newOutputStream(mappings)).withCloseable { it.write("tsrg2 left right\n$GAME abc\n".bytes) }

		when:
		resolve()

		then:
		thrown(IOException)
	}

	def '不同 OS 文件不能被当成共享包而取首个'() {
		given:
		def variant = descriptor.variants.first()
		descriptor.variants << variant + [files: [
				fileEntry(classes) + [url: 'windows.jar']
			]]

		when:
		resolve()

		then:
		thrown(IOException)
	}

	private ForgeMavenizerArtifact resolve() {
		Files.writeString(module, JsonOutput.toJson(descriptor))
		return ForgeMavenizerArtifact.resolve(repo, FORGE, MC, MCP)
	}

	private static Map fileEntry(Path path) {
		def name = path.fileName.toString()
		return [name: name, url: name, size: Files.size(path), sha256: MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path)).encodeHex().toString()]
	}

	private static byte[] type(String name) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, 'java/lang/Object', null)
		writer.visitField(Opcodes.ACC_PUBLIC, 'value', 'I', null, null).visitEnd()
		writer.visitEnd()
		return writer.toByteArray()
	}

	private static void zip(Path path, Map entries) {
		new ZipOutputStream(Files.newOutputStream(path)).withCloseable { out ->
			entries.each { name, value ->
				out.putNextEntry(new ZipEntry(name))
				out.write(value instanceof String ? value.bytes : value)
				out.closeEntry()
			}
		}
	}
}
