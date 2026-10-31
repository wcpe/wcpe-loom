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

package net.fabricmc.loom.test.unit.mods

import java.nio.charset.StandardCharsets

import spock.lang.Specification

import net.fabricmc.loom.configuration.mods.AccessWidenerUtils
import net.fabricmc.loom.core.ModJarPostProcess
import net.fabricmc.loom.util.ModPlatform
import net.fabricmc.loom.util.Pair
import net.fabricmc.loom.util.ZipUtils
import net.fabricmc.loom.util.fmj.FabricModJson
import net.fabricmc.loom.util.fmj.FabricModJsonSource
import net.fabricmc.loom.util.fmj.ModEnvironment

/**
 * 一个 mod 声明多个 class tweaker（schema v2 的 {@code classTweakers} 是「路径 → 环境」映射）.
 *
 * <p>改造前这条路径只接受单条，第二条直接抛 {@code UnsupportedOperationException}（构建崩掉）。
 * 这里同时盯两件事：多的能被逐条读出来；单条的结果与改造前逐字段一致（回归底线）。
 */
class AccessWidenerUtilsTest extends Specification {
	private static final String UNIVERSAL_AW = "accessWidener\tv2\tnamed\naccessible\tmethod\tnet/minecraft/class_1\t()V\n"
	private static final String CLIENT_AW = "accessWidener\tv2\tnamed\naccessible\tfield\tnet/minecraft/client/class_2\tf\tI\n"
	private static final String SERVER_AW = "accessWidener\tv2\tnamed\naccessible\tmethod\tnet/minecraft/server/class_3\tm\t()V\n"

	def "单条声明：路径、环境、头、内容与改造前一致"() {
		given:
		def mod = mockMod(["test.accesswidener": ModEnvironment.UNIVERSAL], ["test.accesswidener": UNIVERSAL_AW])

		when:
		def declarations = AccessWidenerUtils.readClassTweakers(mod)

		then:
		declarations.size() == 1
		with(declarations[0]) {
			path() == "test.accesswidener"
			environment() == ModEnvironment.UNIVERSAL
			header().getNamespace() == "named"
			new String(content(), StandardCharsets.UTF_8) == UNIVERSAL_AW
		}
	}

	def "多条声明：逐条返回，环境不丢、顺序稳定"() {
		given:
		// 刻意乱序传入：schema v2 的声明表是 HashMap，按迭代顺序处理会让输出在 JVM 间抖动
		def mod = mockMod([
			"server.ct": ModEnvironment.SERVER,
			"client.ct": ModEnvironment.CLIENT,
			"universal.ct": ModEnvironment.UNIVERSAL
		], [
			"server.ct": SERVER_AW,
			"client.ct": CLIENT_AW,
			"universal.ct": UNIVERSAL_AW
		])

		when:
		def declarations = AccessWidenerUtils.readClassTweakers(mod)

		then: "三条都在，且按 jar 内路径排序"
		declarations*.path() == [
			"client.ct",
			"server.ct",
			"universal.ct"
		]

		and: "环境随条目带出（改造前被 keySet() 丢掉了）"
		declarations*.environment() == [
			ModEnvironment.CLIENT,
			ModEnvironment.SERVER,
			ModEnvironment.UNIVERSAL
		]

		and: "每条拿到的都是自己那份内容，没有串到别的文件"
		new String(declarations.find { it.path() == "client.ct" }.content(), StandardCharsets.UTF_8) == CLIENT_AW
		new String(declarations.find { it.path() == "server.ct" }.content(), StandardCharsets.UTF_8) == SERVER_AW
	}

	def "没有任何声明：空列表"() {
		given:
		def mod = Mock(FabricModJson.Mockable)
		mod.getClassTweakers() >> [:]

		expect:
		AccessWidenerUtils.readClassTweakers(mod).isEmpty()
	}

	def "真实 jar：schema v2 的两条声明不再抛异常"() {
		given: "一个真的 mod jar，classTweakers 里两条（客户端 + 通用）"
		def jar = createModJar("""
{
	"schemaVersion": 2,
	"id": "multi-tweaker-test",
	"version": "1.0.0",
	"classTweakers": [
		{"config": "client.ct", "environment": "client"},
		"universal.ct"
	]
}
""", ["client.ct": CLIENT_AW, "universal.ct": UNIVERSAL_AW])

		when:
		def declarations = AccessWidenerUtils.readAccessWideners(jar.toPath(), ModPlatform.FABRIC)

		then:
		declarations*.path() == ["client.ct", "universal.ct"]
		declarations*.environment() == [
			ModEnvironment.CLIENT,
			ModEnvironment.UNIVERSAL
		]
		new String(declarations[0].content(), StandardCharsets.UTF_8) == CLIENT_AW
	}

	def "非 mod jar：空列表"() {
		given:
		def dir = File.createTempDir()
		new File(dir, "hello.txt").text = "not a mod"
		def jar = File.createTempFile("loom-not-a-mod", ".jar")
		ZipUtils.pack(dir.toPath(), jar.toPath())

		expect:
		AccessWidenerUtils.readAccessWideners(jar.toPath(), ModPlatform.FABRIC).isEmpty()
	}

	def "多个声明逐个写回 jar，互不覆盖"() {
		given: "jar 里两份旧内容"
		def dir = File.createTempDir()
		new File(dir, "client.ct").text = "old client"
		new File(dir, "server.ct").text = "old server"
		def jar = File.createTempFile("loom-multi-tweaker-write", ".jar").toPath()
		ZipUtils.pack(dir.toPath(), jar)

		when:
		ModJarPostProcess.replaceAccessWideners(jar, [
			new Pair<>(CLIENT_AW.getBytes(StandardCharsets.UTF_8), "client.ct"),
			new Pair<>(SERVER_AW.getBytes(StandardCharsets.UTF_8), "server.ct")
		])

		then: "两份各写各的路径，后者没有盖掉前者"
		new String(ZipUtils.unpack(jar, "client.ct"), StandardCharsets.UTF_8) == CLIENT_AW
		new String(ZipUtils.unpack(jar, "server.ct"), StandardCharsets.UTF_8) == SERVER_AW

		when: "空列表（该 mod 不含声明）等同于不做任何事"
		ModJarPostProcess.replaceAccessWideners(jar, null)

		then:
		new String(ZipUtils.unpack(jar, "client.ct"), StandardCharsets.UTF_8) == CLIENT_AW
	}

	private FabricModJson mockMod(Map<String, ModEnvironment> classTweakers, Map<String, String> contents) {
		def mod = Mock(FabricModJson.Mockable)
		mod.getClassTweakers() >> classTweakers
		// 规则文件内容由读取方按路径去取，这里用一张按路径查表的来源代替真实 jar
		mod.getSource() >> ({ String path -> contents[path].getBytes(StandardCharsets.UTF_8) } as FabricModJsonSource)
		return mod
	}

	private File createModJar(String fabricModJson, Map<String, String> entries) {
		def dir = File.createTempDir()
		new File(dir, "fabric.mod.json").setText(fabricModJson, StandardCharsets.UTF_8.name())

		entries.each { name, content ->
			new File(dir, name).setText(content, StandardCharsets.UTF_8.name())
		}

		def jar = File.createTempFile("loom-multi-tweaker", ".jar")
		ZipUtils.pack(dir.toPath(), jar.toPath())
		return jar
	}
}
