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

package net.fabricmc.loom.test.unit.architectury

import java.nio.file.Files
import java.nio.file.Path

import dev.architectury.loom.forge.AccessTransformerRuleTranslator
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree

/**
 * AT 规则名称空间翻译的契约测试.
 *
 * <p>被钉住的核心事实：legacy Forge 的 {@code forge_at.cfg} 用「MCP 类名 + SRG 成员名」，
 * 而本仓库 AT 作用的 jar 是 official（notch）名。不翻译的话 AT 工具按字面量一条都匹配不上，
 * 且不会报错——症状推迟到运行期的 {@code IllegalAccessError}，因此这里必须钉死翻译结果。
 */
class AccessTransformerRuleTranslatorTest extends Specification {
	@TempDir
	Path tempDir

	/**
	 * 映射夹具：复刻 mappings-srg.tiny 的相关结构.
	 *
	 * 只保留一个类 {@code fd}（srg 名 net/minecraft/util/ObjectIntIdentityMap）及其两个字段，
	 * 数值与 Forge 1.12.2 的真实映射一致，见 mappings-srg.tiny 第 2695 行起。
	 */
	private MemoryMappingTree mappings() {
		Path tiny = tempDir.resolve("mappings.tiny")
		Files.writeString(tiny, """tiny\t2\t0\tofficial\tsrg\tintermediary\tnamed
c\tfd\tnet/minecraft/util/ObjectIntIdentityMap\tnet/minecraft/class_2112\tnet/minecraft/util/ObjectIntIdentityMap
\tf\tLjava/util/IdentityHashMap;\ta\tfield_148749_a\tfield_8818\tidentityMap
\tf\tLjava/util/List;\tb\tfield_148748_b\tfield_8819\tobjectList
\tm\t()I\ta\tfunc_186804_a\tmethod_7679\tsize
""")

		final MemoryMappingTree tree = new MemoryMappingTree()
		Files.newBufferedReader(tiny).withCloseable { reader ->
			MappingReader.read(reader, MappingFormat.TINY_2_FILE, tree)
		}
		return tree
	}

	private List<String> translate(List<String> rules) {
		return AccessTransformerRuleTranslator.translate(
				new AccessTransformerRuleTranslator.Input(rules, mappings(), "srg", "official", null))
	}

	def "字段规则翻译成 notch 名"() {
		when:
		def result = translate([
			"protected net.minecraft.util.ObjectIntIdentityMap field_148749_a # internal map"
		])

		then: "类名变 fd、字段名变 a，注释被去掉"
		result == ["protected fd a"]
	}

	def "注释与空行原样保留"() {
		when:
		def result = translate([
			"# a comment line",
			"",
			"public net.minecraft.util.ObjectIntIdentityMap field_148748_b"
		])

		then: "非规则行不动，规则行才翻译"
		result == [
			"# a comment line",
			"",
			"public fd b"
		]
	}

	def "整类规则翻译成 notch 名"() {
		when:
		def result = translate([
			"public net.minecraft.util.ObjectIntIdentityMap"
		])

		then:
		result == ["public fd"]
	}

	def "方法描述符原样保留"() {
		when: "真实 forge_at.cfg 的写法：成员名与描述符之间没有空白"
		def result = translate([
			"public net.minecraft.util.ObjectIntIdentityMap func_186804_a()I"
		])

		then: "方法名翻译，描述符不动"
		result == ["public fd a()I"]
	}

	def "无法翻译的规则必须让构建失败而不是静默丢弃"() {
		when: "类名在映射里根本不存在"
		translate([
			"protected net.minecraft.util.DoesNotExist field_1"
		])

		then: "抛异常，并指出具体规则"
		def e = thrown(AccessTransformerRuleTranslator.AccessTransformerTranslationException)
		e.failures.size() == 1
		e.failures[0].contains("net.minecraft.util.DoesNotExist")
	}

	def "成员缺失也要失败"() {
		when: "类存在但字段名不在该类下"
		translate([
			"protected net.minecraft.util.ObjectIntIdentityMap field_999999_x"
		])

		then:
		def e = thrown(AccessTransformerRuleTranslator.AccessTransformerTranslationException)
		e.failures[0].contains("field_999999_x")
	}

	def "成员声明在父类时改写规则到父类"() {
		given: "一个子类 Sub 继承 Base，方法只声明在 Base 上"
		def tree = new MemoryMappingTree()
		Files.writeString(tempDir.resolve("hier.tiny"), """tiny\t2\t0\tofficial\tsrg\tintermediary\tnamed
c\tbse\tnet/minecraft/Base\tnet/minecraft/class_1\tnet/minecraft/Base
\tm\t()V\ta\tfunc_100_a\tmethod_1\tdoThing
c\tsub\tnet/minecraft/Sub\tnet/minecraft/class_2\tnet/minecraft/Sub
""")
		Files.newBufferedReader(tempDir.resolve("hier.tiny")).withCloseable { reader ->
			MappingReader.read(reader, MappingFormat.TINY_2_FILE, tree)
		}

		and: "继承关系：Sub(notch=sub) 的父类是 Base(notch=bse)"
		def hierarchy = new AccessTransformerRuleTranslator.ClassHierarchy() {
					@Override
					List<String> parentsOf(String className) {
						return className == "sub" ? ["bse"] : []
					}
				}

		when: "AT 规则写在子类上（无空格描述符，与真实 AT 一致）"
		def result = AccessTransformerRuleTranslator.translate(
				new AccessTransformerRuleTranslator.Input(
				[
					"public net.minecraft.Sub func_100_a()V"
				], tree, "srg", "official", hierarchy))

		then: "规则被改写到声明它的父类，成员名翻译成 notch 名"
		result == ["public bse a()V"]
	}

	def "继承链上找不到成员仍然失败"() {
		given:
		def tree = new MemoryMappingTree()
		Files.writeString(tempDir.resolve("hier2.tiny"), """tiny\t2\t0\tofficial\tsrg\tintermediary\tnamed
c\tbse\tnet/minecraft/Base\tnet/minecraft/class_1\tnet/minecraft/Base
c\tsub\tnet/minecraft/Sub\tnet/minecraft/class_2\tnet/minecraft/Sub
""")
		Files.newBufferedReader(tempDir.resolve("hier2.tiny")).withCloseable { reader ->
			MappingReader.read(reader, MappingFormat.TINY_2_FILE, tree)
		}

		def hierarchy = new AccessTransformerRuleTranslator.ClassHierarchy() {
					@Override
					List<String> parentsOf(String className) {
						return className == "sub" ? ["bse"] : []
					}
				}

		when:
		AccessTransformerRuleTranslator.translate(
				new AccessTransformerRuleTranslator.Input(
				[
					"public net.minecraft.Sub func_999_nope()V"
				], tree, "srg", "official", hierarchy))

		then:
		def e = thrown(AccessTransformerRuleTranslator.AccessTransformerTranslationException)
		e.failures[0].contains("继承链上也未找到")
	}
}
