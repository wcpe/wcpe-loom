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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import javax.tools.ToolProvider

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.task.service.MappingsService
import net.fabricmc.loom.task.service.SourceRemapperService
import net.fabricmc.loom.util.service.ScopedServiceFactory

class ForgeStrictSourcesTest extends Specification {
	@TempDir Path root
	private static final String HEADER = 'tiny\t2\t0\tmojang\tnamed\n'
	private static final String NAMES = 'c\tgame/Payload\tgame/Packet\n\tm\t()I\ttype\tkind\n\tf\tI\tVALUE\tMARKER\n'
	private static final String API = 'package game; public interface Payload { int VALUE = 17; int type(); }'
	private static final String IMPL = '''package forge;
import game.Payload;
public class Hook implements Payload {
    @Override public int type() { return Payload.VALUE; }
    public int call(Payload payload) { return payload.type(); }
}
'''

	def '自定义命名空间覆盖游戏接口、Forge 覆写和调用点，完整保留资源和注释源'() {
		given:
		def src = root.resolve('game-sources.jar')
		def out = root.resolve('named.jar')
		def blank = '// class NotADeclaration {}\n/* interface Ignored {} */\n'
		archive(src, ['game/Payload.java': API, 'forge/Hook.java': IMPL, 'forge/Disabled.java': blank,
			'forge/package-info.java': '/** 说明 */ package forge;', 'forge/Unused.java': 'package forge;',
			'Empty.java': '', 'module-info.java': 'module smoke { exports forge; }', 'LICENSE': '许可'])

		when:
		convert(src, out, HEADER + NAMES)

		then:
		new ZipFile(out.toFile()).withCloseable { z ->
			def text = { name -> z.getInputStream(z.getEntry(name)).text }
			assert !z.getEntry('game/Payload.java')
			assert text('game/Packet.java').contains('int kind()')
			assert text('forge/Hook.java').contains('implements Packet')
			assert text('forge/Hook.java').contains('int kind()')
			assert text('forge/Hook.java').contains('Packet.MARKER')
			assert text('forge/Hook.java').contains('payload.kind()')
			assert text('forge/Disabled.java') == blank
			assert text('forge/package-info.java').contains('package forge;')
			assert text('forge/Unused.java') == 'package forge;'
			assert text('Empty.java') == ''
			assert text('module-info.java').contains('module smoke')
			assert text('LICENSE') == '许可'
			true
		}
	}

	def '失败不覆盖旧目标：#reason'() {
		given:
		def src = root.resolve('input.jar')
		def out = root.resolve('named.jar')
		Files.writeString(out, '既有完整产物')
		archive(src, entries)

		when:
		convert(src, out, mapping)

		then:
		thrown(IOException)
		Files.readString(out) == '既有完整产物'

		where:
		reason     | entries                                                 | mapping
		'空归档'   | [:]                                                     | HEADER + NAMES
		'缺少列'   | ['game/Payload.java': API]                              | (HEADER + NAMES).replace('mojang', 'official')
		'无效声明' | ['game/Bad.java': 'package game; public class Bad {']   | HEADER + NAMES
		'错误路径' | ['wrong/Payload.java': API]                             | HEADER + NAMES
		'同名输出' | ['game/Payload.java': API, 'game/Packet.java': '// 占用'] | HEADER + NAMES
	}

	private void convert(Path src, Path out, String tiny) {
		def project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
		def map = Files.writeString(root.resolve('names.tiny'), tiny)
		def api = Files.writeString(root.resolve('Payload.java'), API)
		assert ToolProvider.systemJavaCompiler.run(null, null, null, '-d', root.toString(), api.toString()) == 0
		def classes = root.resolve('classes.jar')
		archive(classes, ['game/Payload.class': Files.readAllBytes(root.resolve('game/Payload.class'))])
		def opts = SourceRemapperService.TYPE.create(project) { o ->
			o.mappings.set(MappingsService.createOptions(project, map, 'mojang', 'named', false))
			o.javaCompileRelease.set(21)
		}
		new ScopedServiceFactory().withCloseable { factory -> factory.get(opts).remapSourcesJarStrict(src, out, classes) }
	}

	private static void archive(Path path, Map entries) {
		new ZipOutputStream(Files.newOutputStream(path)).withCloseable { z ->
			entries.each { name, value ->
				z.putNextEntry(new ZipEntry(name))
				z.write(value instanceof String ? value.bytes : value)
				z.closeEntry()
			}
		}
	}
}
