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

import dev.architectury.loom.forge.ForgeMemberNameFixer
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree

class ForgeMemberNameFixerTest extends Specification {
	private static final String PARENT = 'net/minecraft/WorldData'
	private static final String CHILD = 'net/minecraftforge/test/ForgeData'
	private static final String CALLER = 'net/minecraft/World'
	private static final String MODEL_LOADER = 'net/minecraftforge/client/model/ModelLoader'

	@TempDir Path dir

	def '无 Forge 成员差异时仍检查继承字段：#scenario'() {
		given:
		def srg = writeJar('forge-srg.jar', [(CHILD): type(CHILD, 'b')])
		def named = hasReference ? writeJar('forge-named.jar', [(CHILD): type(CHILD, PARENT)]) : dir.resolve('missing.jar')
		def target = writeJar('target.jar', [(PARENT): type(PARENT, 'java/lang/Object', 'mapName'),
			(CHILD): type(CHILD, PARENT), (CALLER): caller(originalName)])
		def mappings = new MemoryMappingTree()
		def tiny = "tiny\t2\t0\tofficial\tnamed\nc\tb\t${PARENT}\n\tf\tI\ta\tmapName\n"
		MappingReader.read(new StringReader(tiny), mappings)

		when:
		def stats = ForgeMemberNameFixer.fix(target, srg, named, mappings, fromNamespace, 'named')

		then:
		stats.mappingCount() == 0
		stats.resolvedInheritedFields() == expectedResolutions
		fieldReference(target) == expectedName

		where:
		scenario       | hasReference | fromNamespace | originalName || expectedName | expectedResolutions
		'继承字段已改名'  | true         | 'official'    | 'a'          || 'mapName'    | 1
		'现有引用已正确'  | true         | 'official'    | 'mapName'    || 'mapName'    | 0
		'Forge 参考缺失' | false        | 'official'    | 'a'          || 'a'          | 0
		'映射源不匹配'    | true         | 'srg'         | 'a'          || 'a'          | 0
	}

	def 'Forge 方法按 #namespace 描述符补译且不误改同名重载或歧义签名'() {
		given:
		def mappings = new MemoryMappingTree()
		def tiny = 'tiny\t2\t0\tofficial\tsrg\tnamed\nc\tfm\tnet/minecraft/SrgRegistry\tnet/minecraft/Registry\n'
		MappingReader.read(new StringReader(tiny), mappings)
		def oldDescriptor = '()Lfm;'
		def namedDescriptor = '()Lnet/minecraft/Registry;'
		String targetDescriptor = "()L${registry};"
		String scalarDescriptor = "(L${registry};)V"
		String arrayDescriptor = "([L${registry};)V"
		def ambiguousDescriptor = '(Ljava/lang/String;)V'
		def missingDescriptor = '(Ljava/lang/Object;)V'
		def validDescriptor = '(Ljava/lang/Number;)V'
		def universal = writeJar('forge-universal.jar', [(MODEL_LOADER): modelLoader([
				['a', oldDescriptor],
				['a', '(Lfm;)V'],
				['a', '([Lfm;)V'],
				['unchanged', '(Lfm;)V'],
				['left', ambiguousDescriptor],
				['right', ambiguousDescriptor],
				[
					'oldMissing',
					missingDescriptor
				],
				['oldValid', validDescriptor]
			])])
		def named = writeJar('forge-named.jar', [(MODEL_LOADER): modelLoader([
				[
					'renamedRight',
					ambiguousDescriptor
				],
				[
					'a',
					'([Lnet/minecraft/Registry;)V'
				],
				[
					'setupModelRegistry',
					namedDescriptor
				],
				[
					'renamedLeft',
					ambiguousDescriptor
				],
				[
					'loadModel',
					'(Lnet/minecraft/Registry;)V'
				],
				[
					'unchanged',
					'(Lnet/minecraft/Registry;)V'
				],
				[
					'newMissing',
					missingDescriptor
				],
				['newValid', validDescriptor]
			])])
		def target = writeJar('target.jar', [(MODEL_LOADER): modelLoader([
				[
					'setupModelRegistry',
					targetDescriptor
				],
				['loadModel', scalarDescriptor],
				['unchanged', scalarDescriptor],
				['a', arrayDescriptor],
				[
					'renamedLeft',
					ambiguousDescriptor
				],
				[
					'renamedRight',
					ambiguousDescriptor
				],
				['oldValid', validDescriptor],
				['newValid', validDescriptor]
			]), (CALLER): modelCaller([
				['a', targetDescriptor],
				['a', scalarDescriptor],
				['a', arrayDescriptor],
				['left', ambiguousDescriptor],
				[
					'setupModelRegistry',
					targetDescriptor
				],
				[
					'oldMissing',
					missingDescriptor
				],
				['oldValid', validDescriptor]
			])])

		when:
		ForgeMemberNameFixer.fix(target, universal, named, mappings, 'official', namespace)

		then:
		def calls = readCaller(target).methods.first().instructions.toArray().findAll { it instanceof MethodInsnNode }
		calls*.name == [
			'setupModelRegistry',
			'loadModel',
			'a',
			'left',
			'setupModelRegistry',
			'oldMissing',
			'oldValid'
		]
		calls*.owner.every { it == MODEL_LOADER }
		calls*.desc == [
			targetDescriptor,
			scalarDescriptor,
			arrayDescriptor,
			ambiguousDescriptor,
			targetDescriptor,
			missingDescriptor,
			validDescriptor
		]

		where:
		namespace | registry
		'named'   | 'net/minecraft/Registry'
		'srg'     | 'net/minecraft/SrgRegistry'
	}

	private Path writeJar(String name, Map<String, byte[]> classes) {
		def path = dir.resolve(name)
		new ZipOutputStream(Files.newOutputStream(path)).withCloseable { out ->
			classes.each { String internalName, byte[] data ->
				out.putNextEntry(new ZipEntry(internalName + '.class'))
				out.write(data)
				out.closeEntry()
			}
		}
		return path
	}

	private static byte[] type(String name, String parent, String field = null) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, null)
		if (field != null) {
			writer.visitField(Opcodes.ACC_PUBLIC, field, 'I', null, null).visitEnd()
		}
		writer.visitEnd()
		return writer.toByteArray()
	}

	private static byte[] caller(String field) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CALLER, null, 'java/lang/Object', null)
		def method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 'readField', "(L${CHILD};)I", null, null)
		method.visitCode()
		method.visitVarInsn(Opcodes.ALOAD, 0)
		method.visitFieldInsn(Opcodes.GETFIELD, CHILD, field, 'I')
		method.visitInsn(Opcodes.IRETURN)
		method.visitMaxs(1, 1)
		method.visitEnd()
		writer.visitEnd()
		return writer.toByteArray()
	}

	private static byte[] modelLoader(List<List<String>> methods) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, MODEL_LOADER, null, 'java/lang/Object', null)
		methods.each { method ->
			writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method[0], method[1], null, null).visitEnd()
		}
		writer.visitEnd()
		return writer.toByteArray()
	}

	private static byte[] modelCaller(List<List<String>> calls) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CALLER, null, 'java/lang/Object', null)
		def method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 'reload', "(L${MODEL_LOADER};)V", null, null)
		method.visitCode()
		calls.each { call ->
			String descriptor = call[1]
			method.visitVarInsn(Opcodes.ALOAD, 0)
			if (!descriptor.startsWith('()')) {
				method.visitInsn(Opcodes.ACONST_NULL)
			}
			method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL_LOADER, call[0], descriptor, false)
			if (!descriptor.endsWith('V')) {
				method.visitInsn(Opcodes.POP)
			}
		}
		method.visitInsn(Opcodes.RETURN)
		method.visitMaxs(2, 1)
		method.visitEnd()
		writer.visitEnd()
		return writer.toByteArray()
	}

	private static String fieldReference(Path jar) {
		def field = readCaller(jar).methods.first().instructions.toArray().find { it instanceof FieldInsnNode } as FieldInsnNode
		return field.name
	}

	private static ClassNode readCaller(Path jar) {
		def node = new ClassNode()
		new ZipFile(jar.toFile()).withCloseable { zip ->
			zip.getInputStream(zip.getEntry(CALLER + '.class')).withCloseable { stream ->
				new ClassReader(stream).accept(node, 0)
			}
		}
		return node
	}
}
