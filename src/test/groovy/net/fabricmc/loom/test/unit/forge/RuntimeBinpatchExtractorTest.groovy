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

import dev.architectury.loom.forge.minecraft.RuntimeBinpatchExtractor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 校验「从 Forge installer 的运行时补丁补齐缺失内部类」的关键步骤。
 *
 * <p>背景：userdev 的 joined.lzma 不含内部类条目，而 installer 的 client/server 补丁含
 * 完整的类体。本工具把后者里指定类的字节码搬进产物。用例聚焦两处最容易出错的逻辑：
 * 条目名到类名的转换（{@code $} 不是包分隔符）、以及 binpatch 头部之后取内嵌 class。
 */
class RuntimeBinpatchExtractorTest extends Specification {
	@TempDir
	Path tempDir

	/** 造一份最小 class 字节码，供内嵌到 binpatch 里。 */
	private static byte[] sampleClass(String name) {
		def cw = new ClassWriter(0)
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
		cw.visitEnd()
		return cw.toByteArray()
	}

	/**
	 * 按 binpatch v1 格式拼一份「含完整 class」的补丁：
	 * u1 版本, u2 源名长 + 源名, u2 目标名长 + 目标名, u1 标志, u4 校验和, 负载。
	 */
	private static byte[] binpatchWith(String name, byte[] classBytes) {
		def out = new ByteArrayOutputStream()
		def data = new DataOutputStream(out)
		data.writeByte(1)
		data.writeUTF(name)
		data.writeUTF(name)
		data.writeByte(0)
		data.writeInt(0)
		// 负载前置一段无关字节，验证实现是按魔数扫描而不是硬编码偏移。
		data.write([0xD1, 0xFF, 0xD1, 0xFF, 4, 7] as byte[])
		data.write(classBytes)
		data.flush()
		return out.toByteArray()
	}

	def "binpatch 条目名转类名时 \$ 不被当作包分隔符"() {
		expect:
		RuntimeBinpatchExtractor.binpatchNameToClassNameForTest(entry) == expected

		where:
		entry                                                | expected
		"net.minecraft.world.item.Items\$1.binpatch"         | "net/minecraft/world/item/Items\$1"
		"net.minecraft.world.item.Items.binpatch"            | "net/minecraft/world/item/Items"
		"com.mojang.blaze3d.opengl.GlStateManager\$1.binpatch" | "com/mojang/blaze3d/opengl/GlStateManager\$1"
	}

	def "从 binpatch 负载里取出内嵌的 class 字节码"() {
		given:
		def original = sampleClass("net/minecraft/world/item/Items\$1")
		def patch = binpatchWith("net.minecraft.world.item.Items\$1", original)

		when:
		def extracted = RuntimeBinpatchExtractor.readClassBodyForTest(patch)

		then: "提取到的就是那份 class"
		extracted != null
		extracted == original
		new ClassReader(extracted).getClassName() == "net/minecraft/world/item/Items\$1"
	}

	def "负载里没有 class 魔数时返回 null 而不是抛错"() {
		given: "只有头部没有任何类体"
		def out = new ByteArrayOutputStream()
		def data = new DataOutputStream(out)
		data.writeByte(1)
		data.writeUTF("a.B")
		data.writeUTF("a.B")
		data.writeByte(0)
		data.writeInt(0)
		data.flush()

		expect:
		RuntimeBinpatchExtractor.readClassBodyForTest(out.toByteArray()) == null
	}

	def "版本号不是 1 时保守返回 null"() {
		given:
		def cls = sampleClass("a.B")
		def bad = binpatchWith("a.B", cls)
		bad[0] = (byte) 2

		expect:
		RuntimeBinpatchExtractor.readClassBodyForTest(bad) == null
	}

	def "exists=true 的差分补丁一律返回 null"() {
		given: "该标志表示原版已有此类，数据是以原类为源的 GDIFF 差分，不是类文件"
		def original = sampleClass("net/minecraft/world/inventory/BrewingStandMenu\$PotionSlot")
		def patch = binpatchWith("net/minecraft/world/inventory/BrewingStandMenu\$PotionSlot", original)
		// 把 exists 字节从 0 改成 1：位置在 版本(1) + UTF(2+len)*2 之后
		def nameLen = "net/minecraft/world/inventory/BrewingStandMenu\$PotionSlot".length()
		patch[1 + 2 + nameLen + 2 + nameLen] = (byte) 1

		expect: "即使负载里确实嵌着合法 class，也不许取出来"
		RuntimeBinpatchExtractor.readClassBodyForTest(patch) == null
	}

	def "魔数之后的结构不合法时也返回 null"() {
		given: "负载里有 CAFEBABE，但后面是垃圾 —— 早期实现会把碎片当类写进产物"
		def out = new ByteArrayOutputStream()
		def data = new DataOutputStream(out)
		data.writeByte(1)
		data.writeUTF("a.Bad")
		data.writeUTF("a.Bad")
		data.writeByte(0)
		data.writeInt(0)
		data.write([
			0xCA,
			0xFE,
			0xBA,
			0xBE,
			0x00,
			0x00,
			0x00,
			0x41,
			0x6e,
			0xf9,
			0x07
		] as byte[])
		data.flush()

		expect:
		RuntimeBinpatchExtractor.readClassBodyForTest(out.toByteArray()) == null
	}

	def "只补缺失的类，已存在的一律不动"() {
		given: "产物里已有 Items.class，但没有 Items\$1.class 与 Extra.class"
		def existing = sampleClass("net/minecraft/world/item/Items")
		def jar = tempDir.resolve("patched.jar")
		new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { ZipOutputStream zos ->
			zos.putNextEntry(new ZipEntry("net/minecraft/world/item/Items.class"))
			zos.write(existing)
			zos.closeEntry()
		}

		and: "构造一份 installer：里面的运行时补丁包含 Items、Items\$1、Extra"
		def installer = buildInstaller([
			"net.minecraft.world.item.Items": sampleClass("net/minecraft/world/item/Items"),
			"net.minecraft.world.item.Items\$1": sampleClass("net/minecraft/world/item/Items\$1"),
			"net.minecraft.world.item.Extra": sampleClass("net/minecraft/world/item/Extra"),
		])

		when: "只请求补 Items 与 Items\$1"
		def filled = RuntimeBinpatchExtractor.fillMissingInnerClasses(
				installer, jar, [
					"net/minecraft/world/item/Items",
					"net/minecraft/world/item/Items\$1"
				] as Set,
				tempDir, { String ignored -> } as java.util.function.Consumer)

		then: "只新增缺失的那个，已有条目内容保持不变"
		filled == 1
		List<String> names
		byte[] itemsBytes
		new ZipFile(jar.toFile()).withCloseable { ZipFile zf ->
			names = zf.entries().collect { it.name }
			itemsBytes = zf.getInputStream(zf.getEntry("net/minecraft/world/item/Items.class")).bytes
		}
		names.contains("net/minecraft/world/item/Items\$1.class")
		!names.contains("net/minecraft/world/item/Extra.class")
		names.size() == 2
		itemsBytes == existing
	}

	def "installer 里没有待补的类时不写入任何东西"() {
		given:
		def cls = sampleClass("a.B")
		def jar = tempDir.resolve("empty-patched.jar")
		new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { ZipOutputStream zos ->
			zos.putNextEntry(new ZipEntry("a/B.class"))
			zos.write(cls)
			zos.closeEntry()
		}
		def installer = buildInstaller(["a.B": cls])

		when:
		def filled = RuntimeBinpatchExtractor.fillMissingInnerClasses(
				installer, jar, ["net/minecraft/NotThere"] as Set, tempDir, { String ignored -> } as java.util.function.Consumer)

		then:
		filled == 0
	}

	/** 造一份 installer：data/client.lzma 里放一个装 binpatch 的 zip（lzma 压缩）. */
	private Path buildInstaller(Map<String, byte[]> classes) {
		def patchZip = tempDir.resolve("patches.zip")
		new ZipOutputStream(Files.newOutputStream(patchZip)).withCloseable { ZipOutputStream zos ->
			classes.each { String cls, byte[] body ->
				zos.putNextEntry(new ZipEntry(cls.replace('$', '\$') + ".binpatch"))
				zos.write(binpatchWith(cls, body))
				zos.closeEntry()
			}
		}

		def lzmaBytes = compressLzma(Files.readAllBytes(patchZip))
		def installer = tempDir.resolve("installer.jar")
		new ZipOutputStream(Files.newOutputStream(installer)).withCloseable { ZipOutputStream zos ->
			zos.putNextEntry(new ZipEntry("data/client.lzma"))
			zos.write(lzmaBytes)
			zos.closeEntry()
		}
		return installer
	}

	private static byte[] compressLzma(byte[] raw) {
		// 测试里直接用未压缩 zip：实现侧对「不是 lzma 的输入」会回退成按 zip 读，
		// 这样用例聚焦在类提取与写回逻辑上，不依赖 lzma 编码器的稳定性。
		return raw
	}
}
