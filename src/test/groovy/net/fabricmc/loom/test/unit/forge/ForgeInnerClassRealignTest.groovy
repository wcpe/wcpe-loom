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

import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 校验匿名类错位纠正的判据：只有「改完签名对得上、改前对不上」才动手。
 *
 * <p>背景：Forge 的 binpatch 会把宿主类里 {@code new 匿名类} 的编号整体错开，却沿用旧编号的
 * 构造器调用签名，运行到类初始化即抛 {@code NoSuchMethodError}。纠正靠「序位提议 + 签名校验」，
 * 本用例把三种关键情形钉住：正常错位要修、本就自洽不许动、结构变化要跳过。
 */
class ForgeInnerClassRealignTest extends Specification {
	@TempDir
	Path tempDir

	/** 造一个匿名类，带指定构造器参数。 */
	private static byte[] anonClass(String name, String ctorDesc) {
		def cw = new ClassWriter(0)
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
		def mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null)
		mv.visitCode()
		mv.visitVarInsn(Opcodes.ALOAD, 0)
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		mv.visitInsn(Opcodes.RETURN)
		mv.visitMaxs(1, 1 + org.objectweb.asm.Type.getArgumentTypes(ctorDesc).length)
		mv.visitEnd()
		cw.visitEnd()
		return cw.toByteArray()
	}

	/** 造宿主类：{@code <clinit>} 里依次 new 给出的目标（每个都 by 指定签名调用构造器）。 */
	private static byte[] hostClass(String self, List<List<String>> news) {
		def cw = new ClassWriter(0)
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, self, null, "java/lang/Object", null)
		def mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
		mv.visitCode()
		news.each { pair ->
			def target = pair[0]
			def desc = pair[1]
			mv.visitTypeInsn(Opcodes.NEW, target)
			mv.visitInsn(Opcodes.DUP)
			// 为有参构造器补上占位实参（都是接口/对象类型，压 null 即可）。
			org.objectweb.asm.Type.getArgumentTypes(desc).each { type ->
				mv.visitInsn(type.sort == org.objectweb.asm.Type.INT ? Opcodes.ICONST_0 : Opcodes.ACONST_NULL)
			}
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", desc, false)
			mv.visitInsn(Opcodes.POP)
		}
		mv.visitInsn(Opcodes.RETURN)
		mv.visitMaxs(8, 0)
		mv.visitEnd()
		cw.visitEnd()
		return cw.toByteArray()
	}

	private Path jar(String fileName, Map<String, byte[]> entries) {
		Path p = tempDir.resolve(fileName)
		new ZipOutputStream(Files.newOutputStream(p)).withCloseable { ZipOutputStream zos ->
			entries.each { String k, byte[] v ->
				zos.putNextEntry(new ZipEntry(k))
				zos.write(v)
				zos.closeEntry()
			}
		}
		return p
	}

	/** 读出宿主类 {@code <clinit>} 里的 (new 目标, 构造器调用 owner) 对。 */
	private static List<List<String>> readPairs(Path jar, String entry) {
		def zf = new ZipFile(jar.toFile())
		try {
			def node = new ClassNode()
			new ClassReader(zf.getInputStream(zf.getEntry(entry)).bytes).accept(node, 0)
			def m = node.methods.find { it.name == "<clinit>" }
			def insns = []
			for (AbstractInsnNode i = m.instructions.first; i != null; i = i.next) insns << i
			def res = []
			insns.eachWithIndex { insn, idx ->
				if (!(insn instanceof TypeInsnNode) || insn.opcode != Opcodes.NEW) return
					for (int j = idx + 1; j < insns.size(); j++) {
						def nxt = insns[j]
						if (nxt instanceof MethodInsnNode && nxt.name == "<init>" && nxt.owner == insn.desc) {
							res << [insn.desc, nxt.owner]
							break
						}
						if (nxt instanceof TypeInsnNode && nxt.opcode == Opcodes.NEW) break
					}
			}
			return res
		} finally {
			zf.close()
		}
	}

	def "编号错位时按 pre-patch 的类纠正，且签名自洽"() {
		given: "pre-patch: new \$A(单参构造)"
		def pre = jar("pre.jar", [
			"T.class": hostClass("T", [
				[
					"T\$A",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$B.class": anonClass("T\$B", "()V"),
		])
		and: "产物把引用错指到 \$B，却仍按单参调用 —— 正是要修的形态"
		def patched = jar("patched.jar", [
			"T.class": hostClass("T", [
				[
					"T\$B",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$B.class": anonClass("T\$B", "()V"),
		])

		when:
		def fixed = MinecraftPatchedProvider.realignInnerClassNewSites(pre, patched) { }

		then: "改回 \$A，new 与构造调用 owner 一致"
		fixed == 1
		def pairs = readPairs(patched, "T.class")
		pairs == [["T\$A", "T\$A"]]
	}

	def "本就自洽的引用不许改动"() {
		given: "产物指向的类，其构造器签名与调用一致"
		def pre = jar("pre2.jar", [
			"T.class": hostClass("T", [
				[
					"T\$A",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$B.class": anonClass("T\$B", "(Lnet/minecraft/core/BlockPos;)V"),
		])
		def entries = [
			"T.class": hostClass("T", [
				[
					"T\$B",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$B.class": anonClass("T\$B", "(Lnet/minecraft/core/BlockPos;)V"),
		]
		def ordered = reverse ? entries.entrySet().toList().reverse().collectEntries { [(it.key): it.value] } : entries
		def patched = jar("patched2.jar", ordered)

		when:
		def fixed = MinecraftPatchedProvider.realignInnerClassNewSites(pre, patched) { }

		then: "不动——签名本来对得上，说明是合法改名而非错位"
		fixed == 0
		readPairs(patched, "T.class") == [["T\$B", "T\$B"]]
		where:
		reverse << [false, true]
	}

	def "签名对不上时保守跳过，不改坏产物"() {
		given: "产物里 new 的目标与 pre 完全不同（补丁改了结构）"
		def pre = jar("pre3.jar", [
			"T.class": hostClass("T", [
				[
					"T\$A",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$C.class": anonClass("T\$C", "(I)V"),
		])
		and: "调用签名 (I) 在 pre 的 \$A 上根本不存在 → 无法证明 \$A 是正确目标"
		def patched = jar("patched3.jar", [
			"T.class": hostClass("T", [["T\$C", "(I)V"]]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$C.class": anonClass("T\$C", "(I)V"),
		])

		when:
		def fixed = MinecraftPatchedProvider.realignInnerClassNewSites(pre, patched) { }

		then: "跳过，保持原样"
		fixed == 0
		readPairs(patched, "T.class") == [["T\$C", "T\$C"]]
	}

	def "不同类的同名方法互不干扰（clinit 不会被别的类覆盖）"() {
		given: "两个类都有 <clinit>，各自 new 自己的匿名类"
		def pre = jar("pre4.jar", [
			"A.class": hostClass("A", [["A\$1", "()V"]]),
			"B.class": hostClass("B", [["B\$2", "(I)V"]]),
			"A\$1.class": anonClass("A\$1", "()V"),
			"A\$2.class": anonClass("A\$2", "(I)V"),
			"B\$1.class": anonClass("B\$1", "(I)V"),
			"B\$2.class": anonClass("B\$2", "(I)V"),
		])
		and: "两侧编号被打乱：A 的 ()V 调用指向了 (I)V 的 \$2，B 的 (I)V 调用指向了 ()V 的 \$2"
		def patched = jar("patched4.jar", [
			"A.class": hostClass("A", [["A\$2", "()V"]]),
			"B.class": hostClass("B", [["B\$1", "(I)V"]]),
			"A\$1.class": anonClass("A\$1", "()V"),
			"A\$2.class": anonClass("A\$2", "(I)V"),
			"B\$1.class": anonClass("B\$1", "()V"),
			"B\$2.class": anonClass("B\$2", "(I)V"),
		])

		when:
		def fixed = MinecraftPatchedProvider.realignInnerClassNewSites(pre, patched) { }

		then: "两个类各按自己的 pre 序列纠正；键不带类名时会互相覆盖，导致其中一类漏修"
		fixed == 2
		readPairs(patched, "A.class") == [["A\$1", "A\$1"]]
		readPairs(patched, "B.class") == [["B\$2", "B\$2"]]
	}

	def "两侧命名空间不同时整体拒绝，一个类名都不许改"() {
		given: "pre 是混淆名（ezy\$c），产物是重映射后的 srg 名（T\$B）"
		def pre = jar("pre5.jar", [
			"ezy.class": hostClass("ezy", [
				[
					"ezy\$c",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"ezy\$c.class": anonClass("ezy\$c", "(Lnet/minecraft/core/BlockPos;)V"),
		])
		and: "产物侧名字完全不同，且引用在自身命名空间内本来就自洽"
		def patched = jar("patched5.jar", [
			"T.class": hostClass("T", [
				[
					"T\$B",
					"(Lnet/minecraft/core/BlockPos;)V"
				]
			]),
			"T\$A.class": anonClass("T\$A", "(Lnet/minecraft/core/BlockPos;)V"),
			"T\$B.class": anonClass("T\$B", "(Lnet/minecraft/core/BlockPos;)V"),
		])
		and:
		def before = Files.readAllBytes(patched)

		when:
		List<String> logs = []
		def fixed = MinecraftPatchedProvider.realignInnerClassNewSites(pre, patched) { logs << it }

		then: "不纠正，且明确说明原因"
		fixed == 0
		logs.any { it.contains("不在同一命名空间") }

		and: "产物字节完全未变 —— 误改会把能跑的地方也改崩"
		Files.readAllBytes(patched) == before
		readPairs(patched, "T.class") == [["T\$B", "T\$B"]]
	}
}
