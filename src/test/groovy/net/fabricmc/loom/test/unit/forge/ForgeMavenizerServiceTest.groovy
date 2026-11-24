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

import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

import dev.architectury.loom.forge.tool.ForgeMavenizerService
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.cache.AtomicFiles

class ForgeMavenizerServiceTest extends Specification {
	private static final String FORGE = "1.21.11-61.2.5"
	private static final String MC = "1.21.11"
	private static final String MCP = "1.21.11-20251223.124241"

	@TempDir
	Path root

	def "离线与刷新分别传给 CLI，三个版本的 JDK 支持含空格的安装路径"() {
		given:
		def cache = root.resolve("download cache")
		def jdks = root.resolve("jdk cache")
		def repo = root.resolve("staging repository")
		def home8 = root.resolve("jdk 8").toString()
		def home21 = root.resolve("jdk 21").toString()
		def home25 = root.resolve("jdk 25").toString()

		expect:
		invoke("arguments", FORGE, cache, jdks, repo, false, false) == [
			"--maven",
			"--artifact",
			"net.minecraftforge:forge",
			"--version",
			FORGE,
			"--mappings",
			"official",
			"--cache",
			cache.toString(),
			"--jdk-cache",
			jdks.toString(),
			"--output",
			repo.toString()
		]
		invoke("arguments", FORGE, cache, jdks, repo, true, false).last() == "--offline"
		invoke("arguments", FORGE, cache, jdks, repo, false, true).last() == "--ignore-cache"
		invoke("arguments", FORGE, cache, jdks, repo, true, true).takeRight(2) == ["--offline", "--ignore-cache"]
		invoke("javaInstallations", home8, home21, home25) ==
				"-Dorg.gradle.java.installations.paths=${home8},${home21},${home25}"
	}

	def "离线拒绝未经校验的工具且不修改缓存"() {
		given:
		def tool = Files.writeString(root.resolve("minecraft-mavenizer-0.4.24.jar"), "未固定的工具")

		when:
		invoke("resolveTool", root, true)

		then:
		def failure = thrown(IOException)
		failure.message.contains("SHA-256")
		Files.readString(tool) == "未固定的工具"
	}

	def "产物摘要拒绝不同坐标或被替换的同名产物"() {
		given:
		def classes = Files.writeString(root.resolve("official.jar"), "官方字节")
		def mappings = Files.writeString(root.resolve("map2srg.tsrg.gz"), "映射字节")
		def result = root.resolve("srg.jar")

		when:
		invoke("publish", result, FORGE, MC, MCP, classes, mappings, { stage ->
			Files.writeString(stage, "转换字节")
		} as AtomicFiles.IOConsumer)

		then:
		ForgeMavenizerService.isReusable(result, FORGE, MC, MCP)
		!ForgeMavenizerService.isReusable(result, FORGE, MC, "different-mcp")

		when:
		Files.writeString(result, "替换字节")

		then:
		!ForgeMavenizerService.isReusable(result, FORGE, MC, MCP)
	}

	def "转换失败后不会发布部分产物或摘要"() {
		given:
		def classes = Files.writeString(root.resolve("official.jar"), "官方字节")
		def mappings = Files.writeString(root.resolve("map2srg.tsrg.gz"), "映射字节")
		def result = root.resolve("srg.jar")

		when:
		invoke("publish", result, FORGE, MC, MCP, classes, mappings, { stage ->
			Files.writeString(stage, "部分字节")
			throw new IOException("转换失败")
		} as AtomicFiles.IOConsumer)

		then:
		def failure = thrown(IOException)
		failure.message == "转换失败"
		!Files.exists(result)
		!Files.exists(ForgeMavenizerService.receipt(result))
		root.toFile().list().toList().sort() == [
			"map2srg.tsrg.gz",
			"official.jar"
		]
	}

	def "源码补齐固定使用上游 cache-only 参数，正常编译和刷新参数不变"() {
		expect:
		invoke("sourceArguments", FORGE, root, root, root) ==
				invoke("arguments", FORGE, root, root, root, true, false) + ["--cache-only"]
		!invoke("arguments", FORGE, root, root, root, true, true).contains("--cache-only")
		!invoke("arguments", FORGE, root, root, root, false, false).contains("--cache-only")
	}

	def "源码导出离线先只读缓存，在线保持原调用"() {
		given:
		def attempts = []

		when:
		invoke("exportSources", offline, { cacheOnly -> attempts.add(cacheOnly) } as AtomicFiles.IOConsumer)

		then:
		attempts == expected

		where:
		offline | expected
		true    | [true]
		false   | [false]
	}

	def "离线只在外部进程失败后重试一次普通导出"() {
		given:
		def attempts = []
		def cliFailure = invoke("commandFailure", new IllegalStateException("listLibraries cache miss"))

		when:
		invoke("exportSources", true, { cacheOnly ->
			attempts.add(cacheOnly)
			if (cacheOnly) {
				throw cliFailure
			}
		} as AtomicFiles.IOConsumer)

		then:
		attempts == [true, false]
	}

	def "模块或配对校验失败以及在线进程失败不能重试"() {
		given:
		def attempts = []
		def cliFailure = invoke("commandFailure", new IllegalStateException("process failed"))
		def failure = onlineFailure ? cliFailure : new IOException("module or receipt validation failed")

		when:
		invoke("exportSources", !onlineFailure, { cacheOnly ->
			attempts.add(cacheOnly)
			throw failure
		} as AtomicFiles.IOConsumer)

		then:
		def actual = thrown(IOException)
		actual.is(failure)
		attempts == [!onlineFailure]

		where:
		onlineFailure << [false, true]
	}

	def "两次 CLI 失败保留完整原因且提示本机离线缓存"() {
		given:
		def attempts = []
		def first = invoke("commandFailure", new IllegalStateException("cache-only failed"))
		def second = invoke("commandFailure", new IllegalStateException("offline failed"))

		when:
		invoke("exportSources", true, { cacheOnly ->
			attempts.add(cacheOnly)
			throw cacheOnly ? first : second
		} as AtomicFiles.IOConsumer)

		then:
		def failure = thrown(IOException)
		failure.message.contains("离线缓存")
		failure.cause.is(second)
		failure.suppressed.toList() == [first]
		attempts == [true, false]
	}

	private static Object invoke(String name, Object... args) {
		def method = ForgeMavenizerService.declaredMethods.find { it.name == name }
		method.setAccessible(true)

		try {
			return method.invoke(null, args)
		} catch (InvocationTargetException e) {
			throw e.cause
		}
	}
}
