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

package net.fabricmc.loom.test.unit.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import org.gradle.api.logging.Logging
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.pipeline.ForgePrePatchJarReuse

/**
 * 「pre-patch jar 已就绪且身份相符就跳过整条 MCP 链」的判据边界.
 *
 * <p>这是全仓库最贵的一条链（十多个外部进程、分钟到十分钟级）的门卫，因此两个方向都要钉死：
 * <ul>
 *   <li>该跳过的必须跳过——否则收益为零，用户还是会等十几分钟；</li>
 *   <li>不该跳过的必须不跳过——跳错会让用户拿到一份与当前 Forge 版本 / MCPConfig 无关的 MC jar，
 *       这是不可接受的错误（错误产物会一路传到编译、运行期）。</li>
 * </ul>
 *
 * <p>另一条硬性要求是「判据纯读」：{@code tryReuse} 不得改写产物、不得新建任何文件（旁车只由生产成功后的
 * {@code record} 写），否则这条本来只是为了省时间的判据本身就成了缓存污染源。
 */
class ForgePrePatchJarReuseTest extends Specification {
	private static final String IDENTITY = "patchVersion=10|platform=forge|mcVersion=1.20.1|forgeVersion=1.20.1-47.2.1|type=merged|namespace=srg"

	@TempDir
	Path tempDir

	private Path output
	private Path input

	def setup() {
		output = writeJar(tempDir.resolve("forge/minecraft-merged-srg.jar"), ["net/minecraft/Client.class": "字节"])
		input = writeFile(tempDir.resolve("mcp/config.json"), '{"version":"1.20.1"}')
	}

	def "产物就绪且身份相符时直接复用，且判据本身不改写任何文件"() {
		given: "一次成功生产（记录身份）"
		record()
		def outputSize = Files.size(output)
		def outputModified = Files.getLastModifiedTime(output)
		def sidecarContent = Files.readString(ForgePrePatchJarReuse.sidecar(output), StandardCharsets.UTF_8)

		when:
		def reused = ForgePrePatchJarReuse.tryReuse(output, key(IDENTITY, [input]), Logging.getLogger("test"))

		then: "命中：任务可以直接返回，不启动 MCP 链"
		reused

		and: "产物本身一字未动（判据只读）"
		Files.size(output) == outputSize
		Files.getLastModifiedTime(output) == outputModified

		and: "旁车也没有被判据重写"
		Files.readString(ForgePrePatchJarReuse.sidecar(output), StandardCharsets.UTF_8) == sidecarContent
	}

	def "产物缺失时照常执行"() {
		given:
		Files.delete(output)

		when:
		def reused = ForgePrePatchJarReuse.tryReuse(output, key(IDENTITY, [input]), Logging.getLogger("test"))

		then:
		!reused
	}

	def "产物存在但从未记录身份（旧版本的共享缓存）时照常执行"() {
		expect:
		!ForgePrePatchJarReuse.tryReuse(output, key(IDENTITY, [input]), Logging.getLogger("test"))
	}

	def "身份不符（换了 Forge 版本）时照常执行"() {
		given: "旧 Forge 版本下生产过一次（旧产物仍在盘上）"
		record()

		when: "换成新 Forge 版本后的键"
		def newIdentity = IDENTITY.replace("forgeVersion=1.20.1-47.2.1", "forgeVersion=1.20.1-47.4.2")
		def reused = ForgePrePatchJarReuse.tryReuse(output, key(newIdentity, [input]), Logging.getLogger("test"))

		then: "不得复用：那份 jar 是旧 Forge 对应的一批输入产出的"
		!reused
	}

	def "输入内容变了（MCPConfig 换了一份）时照常执行"() {
		given:
		record()

		when: "config.json 的内容变了，但路径没变"
		writeFile(input, '{"version":"1.20.1","spec":5}')

		then:
		!ForgePrePatchJarReuse.tryReuse(output, key(IDENTITY, [input]), Logging.getLogger("test"))
	}

	def "产物在记录之后被改写时照常执行"() {
		given:
		record()

		when: "另一个 loom 进程把产物换成了另一份内容"
		writeJar(output, ["net/minecraft/Client.class": "另一份字节，长度不同"])

		then:
		!ForgePrePatchJarReuse.tryReuse(output, key(IDENTITY, [input]), Logging.getLogger("test"))
	}

	def "产物是残骸（空 zip）时照常执行"() {
		given:
		record()

		when:
		writeJar(output, [:])

		then:
		!ForgePrePatchJarReuse.tryReuse(output, key(IDENTITY, [input]), Logging.getLogger("test"))
	}

	def "输入不可读时既不复用也不记录身份"() {
		given:
		def missingInput = tempDir.resolve("mcp/does-not-exist.json")
		def incompleteKey = key(IDENTITY, [missingInput])

		when: "带着不完整的键去判定与记录"
		def reused = ForgePrePatchJarReuse.tryReuse(output, incompleteKey, Logging.getLogger("test"))
		ForgePrePatchJarReuse.record(output, incompleteKey, Logging.getLogger("test"))

		then: "不完整器永远不命中，也不会被写进旁车（否则下次会拿它当真）"
		!reused
		!Files.exists(ForgePrePatchJarReuse.sidecar(output))
	}

	def "输入顺序不影响键"() {
		given:
		def second = writeFile(tempDir.resolve("mcp/joined.tsrg"), "tsrg2 内容")

		expect:
		key(IDENTITY, [input, second]) == key(IDENTITY, [second, input])
	}

	def "旁车与产物同名不同后缀，不落在产物路径上"() {
		expect:
		ForgePrePatchJarReuse.sidecar(output) != output
		ForgePrePatchJarReuse.sidecar(output).toString().endsWith("minecraft-merged-srg.jar.key")
		ForgePrePatchJarReuse.sidecar(output).parent != output.parent
	}

	private void record() {
		ForgePrePatchJarReuse.record(output, key(IDENTITY, [input]), Logging.getLogger("test"))
	}

	private static String key(String identity, List<Path> inputs) {
		return ForgePrePatchJarReuse.fingerprint(identity, inputs)
	}

	private static Path writeFile(Path path, String content) {
		Files.createDirectories(path.parent)
		Files.writeString(path, content, StandardCharsets.UTF_8)
		return path
	}

	private static Path writeJar(Path path, Map<String, String> entries) {
		Files.createDirectories(path.parent)
		def jar = new ZipOutputStream(Files.newOutputStream(path))

		try {
			entries.each { String name, String content ->
				jar.putNextEntry(new ZipEntry(name))
				jar.write(content.getBytes(StandardCharsets.UTF_8))
				jar.closeEntry()
			}
		} finally {
			jar.close()
		}

		return path
	}
}
