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
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 校验「被改名私有方法的桥接补齐」的判据.
 *
 * <p>Forge 36.2.42 把 {@code TransformationMatrix.func_227987_b_} 改名成 private
 * {@code inverseVanilla}，调用点却没跟着改，运行期抛 {@code NoSuchMethodError}。
 * 用例用合成 jar 覆盖三种情形：需要补桥接、旧名仍在（不得重复补）、
 * 改名后的目标方法也不在（不是该缺陷形态，不动产物）。
 */
class ForgeRenamedMethodBridgeTest extends Specification {
	/** srg 阶段（打补丁后的中间产物）里的类名。 */
	private static final String SRG_TARGET = "net/minecraft/util/math/vector/TransformationMatrix"
	/** named 阶段（最终产物）里的类名。 */
	private static final String NAMED_TARGET = "com/mojang/math/Transformation"

	@TempDir
	Path tempDir

	/** 目标类：按给定「方法名 -> 描述符」生成实例方法。 */
	private static byte[] targetClass(String owner, Map<String, String> methods, int renamedAccess = Opcodes.ACC_PRIVATE) {
		def cw = new ClassWriter(0)
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)

		methods.each { String name, String desc ->
			int access = name.endsWith("Vanilla") ? renamedAccess : Opcodes.ACC_PUBLIC
			def mv = cw.visitMethod(access, name, desc, null, null)
			mv.visitCode()
			mv.visitInsn(Opcodes.ACONST_NULL)
			mv.visitInsn(Opcodes.ARETURN)
			mv.visitMaxs(1, (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0)
			mv.visitEnd()
		}

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

	private static ClassNode readTarget(Path jar, String owner) {
		new ZipFile(jar.toFile()).withCloseable { ZipFile zip ->
			def node = new ClassNode()
			new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).bytes).accept(node, 0)
			return node
		}
	}

	private static List<MethodInsnNode> callsIn(ClassNode node, String methodName) {
		def calls = []
		node.methods.findAll { it.name == methodName }.each { m ->
			m.instructions.each { insn ->
				if (insn instanceof MethodInsnNode) {
					calls.add(insn)
				}
			}
		}
		return calls
	}

	def "旧名不存在时补出桥接并转调改名后的私有方法"() {
		given: "目标类只有 inverseVanilla，没有 func_227987_b_"
		def self = "L${owner};"
		def jar = jarWith([
			(owner + ".class"): targetClass(owner, ["inverseVanilla": "()" + self]),
		])

		when:
		def added = MinecraftPatchedProvider.bridgeRenamedVanillaMethods(
				jar, { String ignored -> } as java.util.function.Consumer)

		then: "补出 1 个公开方法，内部走 invokespecial 转调"
		added == 1
		def node = readTarget(jar, owner)
		def bridge = node.methods.find { it.name == "func_227987_b_" && it.desc == "()" + self }
		bridge != null
		(bridge.access & Opcodes.ACC_PUBLIC) != 0
		def calls = callsIn(node, "func_227987_b_")
		calls.size() == 1
		calls[0].name == "inverseVanilla"
		calls[0].owner == owner
		calls[0].opcode == Opcodes.INVOKESPECIAL

		where: "srg 阶段与 named 阶段的类名都要认"
		owner << [SRG_TARGET, NAMED_TARGET]
	}

	def "旧名仍在时不重复补"() {
		given: "目标类同时有两个名字（上游已修好或本就没改名）"
		def self = "L${owner};"
		def jar = jarWith([
			(owner + ".class"): targetClass(owner, [
				"func_227987_b_": "()" + self,
				"inverseVanilla": "()" + self,
			]),
		])

		when:
		def added = MinecraftPatchedProvider.bridgeRenamedVanillaMethods(
				jar, { String ignored -> } as java.util.function.Consumer)

		then: "零改动，避免出现重复方法定义"
		added == 0
		readTarget(jar, owner).methods.count { it.name == "func_227987_b_" } == 1

		where:
		owner << [SRG_TARGET, NAMED_TARGET]
	}

	def "不桥接公开或静态的同名目标"() {
		given:
		def desc = "()L${NAMED_TARGET};"
		def jar = jarWith([
			(NAMED_TARGET + ".class"): targetClass(NAMED_TARGET, ["inverseVanilla": desc], access),
		])

		expect:
		MinecraftPatchedProvider.bridgeRenamedVanillaMethods(jar) { } == 0
		readTarget(jar, NAMED_TARGET).methods.find { it.name == "func_227987_b_" } == null

		where:
		access << [
			Opcodes.ACC_PUBLIC,
			Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
		]
	}

	def "改名后的方法也不在时视为不是该缺陷形态"() {
		given: "目标类两个名字都没有"
		def self = "L${owner};"
		def jar = jarWith([
			(owner + ".class"): targetClass(owner, ["somethingElse": "()" + self]),
		])

		when:
		def added = MinecraftPatchedProvider.bridgeRenamedVanillaMethods(
				jar, { String ignored -> } as java.util.function.Consumer)

		then: "不动产物"
		added == 0
		readTarget(jar, owner).methods.find { it.name == "func_227987_b_" } == null

		where:
		owner << [SRG_TARGET, NAMED_TARGET]
	}
}
