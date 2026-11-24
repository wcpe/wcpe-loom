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
import java.util.zip.ZipOutputStream

import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 校验「悬空内部类检测」的判据：被指令引用但条目缺失时才报，其余一律放过。
 *
 * <p>这类缺失是 Forge 二进制补丁的通病（只带引用、不带类体），运行期才炸且栈指向触发方，
 * 所以要在构建期拦住。用例用合成 jar 覆盖三种情形：真缺失、类在、外部命名空间。
 */
class ForgeDanglingInnerClassTest extends Specification {
	@TempDir
	Path tempDir

	private static byte[] classReferencing(String selfName, String targetName) {
		def cw = new ClassWriter(0)
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, selfName, null, "java/lang/Object", null)

		// 一个静态初值里 new 一次目标类：这正是 Items.<clinit> 里 new Items$1 的形态。
		def mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
		mv.visitCode()
		mv.visitTypeInsn(Opcodes.NEW, targetName)
		mv.visitInsn(Opcodes.DUP)
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetName, "<init>", "()V", false)
		mv.visitInsn(Opcodes.POP)
		mv.visitInsn(Opcodes.RETURN)
		mv.visitMaxs(2, 0)
		mv.visitEnd()

		cw.visitEnd()
		return cw.toByteArray()
	}

	private static byte[] simpleClass(String name) {
		def cw = new ClassWriter(0)
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
		def mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		mv.visitCode()
		mv.visitVarInsn(Opcodes.ALOAD, 0)
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		mv.visitInsn(Opcodes.RETURN)
		mv.visitMaxs(1, 1)
		mv.visitEnd()
		cw.visitEnd()
		return cw.toByteArray()
	}

	private Path jarWith(Map<String, byte[]> entries) {
		Path jar = tempDir.resolve("probe-${UUID.randomUUID()}.jar")
		new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { ZipOutputStream out ->
			entries.each { name, bytes ->
				out.putNextEntry(new ZipEntry(name))
				out.write(bytes)
				out.closeEntry()
			}
		}
		return jar
	}

	def "引用了不存在的内部类时把该缺失类报出来"() {
		given: "宿主类里 new 了一个不存在的内部类"
		def jar = jarWith([
			"net/minecraft/world/item/Items.class": classReferencing("net/minecraft/world/item/Items", "net/minecraft/world/item/Items\$1"),
		])

		when: "检测缺失"
		def missing = MinecraftPatchedProvider.verifyNoDanglingInnerClassReferences(jar, { String ignored -> } as java.util.function.Consumer)

		then: "返回缺失集合，交由后续补齐步骤处理"
		missing == [
			"net/minecraft/world/item/Items\$1"
		] as Set
	}

	def "内部类存在时不报错"() {
		given: "被引用的内部类就在 jar 里"
		def jar = jarWith([
			"net/minecraft/world/item/Items.class": classReferencing("net/minecraft/world/item/Items", "net/minecraft/world/item/Items\$1"),
			"net/minecraft/world/item/Items\$1.class": simpleClass("net/minecraft/world/item/Items\$1"),
		])
		when:
		def missing = MinecraftPatchedProvider.verifyNoDanglingInnerClassReferences(jar, { String ignored -> } as java.util.function.Consumer)

		then: "不报缺失"
		missing.isEmpty()
	}

	def "引用外部命名空间的类不算缺失"() {
		given: "引用了不在本 jar 的 JDK 类与 guava 类"
		def jar = jarWith([
			"net/minecraft/world/item/Items.class": classReferencing("net/minecraft/world/item/Items", "java/util/ArrayList"),
			"net/minecraft/Probe.class": classReferencing("net/minecraft/Probe", "com/google/common/collect/ImmutableList\$Builder"),
		])

		when:
		def missing = MinecraftPatchedProvider.verifyNoDanglingInnerClassReferences(jar, { String ignored -> } as java.util.function.Consumer)

		then: "只看 net/minecraft/ 命名空间，不报外部依赖"
		missing.isEmpty()
	}
}
