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

package net.fabricmc.loom.test.unit.cache

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.AttributeHelper
import net.fabricmc.loom.util.FileSystemUtil
import net.fabricmc.loom.util.cache.ArtifactReuse

/**
 * 共享产物「输入没变就跳过重算」的判据边界.
 *
 * <p>这条判据的失效方向是不对称的：误判「可复用」会拿陈旧产物一路传给下游，形成静默损坏；
 * 误判「不可复用」只是多算一次。所以这里逐条钉住两种方向的边界：
 * <ul>
 *   <li>输入真的没变时必须判 true——否则每个新项目首次构建都白算一遍，等于没做这个优化；</li>
 *   <li>输入被换了、产物被换了/截断/删了、产出规则换代了，必须判 false。</li>
 * </ul>
 *
 * <p>另外钉住「只读」这条性质：判据自身不得改变产物与输入的字节或 mtime——它跑在 {@code AtomicFiles}
 * 那条原子发布契约旁边，任何写入都会把「原子落位」的语义弄成两半。
 */
class ArtifactReuseTest extends Specification {
	private static final String RECIPE = "test-recipe/v1"

	@TempDir
	Path tempDir

	def "产物与输入都没变时判为可复用"() {
		given: "一次真实的产出：输入 jar、产物 jar，以及记在产物上的指纹"
		def client = jar("client.jar", ["net/minecraft/A.class": "client-a"])
		def server = jar("server.jar", ["net/minecraft/A.class": "server-a"])
		def merged = jar("minecraft-merged.jar", ["net/minecraft/A.class": "merged-a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client, server])

		expect: "nothing changed → reuse"
		ArtifactReuse.isReusable(merged, RECIPE, [merged], [client, server])
	}

	def "一批两件产物：任何一件缺失都判为不可复用"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def server = jar("server.jar", ["b.txt": "b"])
		def clientOnly = jar("minecraft-client-only.jar", ["a.txt": "a"])
		def common = jar("minecraft-common.jar", ["b.txt": "b"])
		ArtifactReuse.record(clientOnly, RECIPE, [clientOnly, common], [client, server])

		expect:
		ArtifactReuse.isReusable(clientOnly, RECIPE, [clientOnly, common], [client, server])

		when: "两半同属一次生产，缺任何一半都只能整次重跑"
		Files.delete(common)

		then:
		!ArtifactReuse.isReusable(clientOnly, RECIPE, [clientOnly, common], [client, server])
	}

	def "产物缺失时判为不可复用并照常执行"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])
		Files.delete(merged)

		expect:
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "产物被截断或不是 zip 时判为不可复用"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])
		Files.write(merged, "not a zip".getBytes(StandardCharsets.UTF_8))

		expect: "内容判定必须比「文件存在」严格：这种情形的产物必需重算"
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "输入内容被换了（大小不同）时判为不可复用"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])

		when: "另一个版本的 client jar 落位到同一路径"
		client = jar("client.jar", ["a.txt": "completely different client payload"])

		then:
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "输入内容被换了（大小相同、仅 mtime 不同）也能识别出来"() {
		given: "两个不同内容但**恰好等长**的 client jar：只比大小会漏掉这种换代"
		def client = jar("client.jar", ["a.txt": "aaaa"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])

		when:
		client = jar("client.jar", ["a.txt": "bbbb"])

		then:
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "输入只是 mtime 变了（内容相同）仍判为可复用"() {
		given: "下载走 304 时 {@code Download} 会刷新 mtime，内容却还是同一份"
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])

		when:
		Files.setLastModifiedTime(client, FileTime.from(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1), TimeUnit.MILLISECONDS))

		then: "mtime 变了就必须真的比一次摘要，内容一致就不能白白重算"
		ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "产出规则换代后判为不可复用（即使输入与产物都没动）"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])

		expect: "合并/拆分实现变了而输入没变，也绝不能拿旧产物充当新产物"
		!ArtifactReuse.isReusable(merged, "test-recipe/v2", [merged], [client])
	}

	def "输入集合变了（多了一个 jar）时判为不可复用"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])
		def extra = jar("extra.jar", ["b.txt": "b"])

		expect:
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client, extra])
	}

	def "输入顺序变了时判为不可复用（顺序参与指纹）"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def server = jar("server.jar", ["b.txt": "b"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client, server])

		expect:
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [server, client])
	}

	def "没有指纹标记的产物判为不可复用（旧版 loom 留下的产物）"() {
		given: "旧版 loom 只原子落位内容，没写任何指纹"
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])

		expect: "来源不明的产物不能当自己的：否则「标记是新的、内容是旧的」会静默传播"
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "指纹标记写坏时判为不可复用，而不是抛异常"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])
		AttributeHelper.writeAttribute(merged, "loom-artifact-inputs", "garbage without format header")

		expect:
		!ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
	}

	def "判定与记录都不改产物字节、不改输入与产物的 mtime"() {
		given:
		def client = jar("client.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])
		def mergedBytes = Files.readAllBytes(merged)
		def mergedTime = Files.getLastModifiedTime(merged)
		def clientTime = Files.getLastModifiedTime(client)

		when:
		ArtifactReuse.isReusable(merged, RECIPE, [merged], [client])
		ArtifactReuse.record(merged, RECIPE, [merged], [client])

		then: "原子发布的语义靠内容不变 + 一次 mtime 变化表达，判据不得把它们弄丢"
		Files.readAllBytes(merged) == mergedBytes
		Files.getLastModifiedTime(merged) == mergedTime
		Files.getLastModifiedTime(client) == clientTime
	}

	def "锚点必须是产物之一"() {
		given:
		def other = jar("other.jar", ["a.txt": "a"])
		def merged = jar("minecraft-merged.jar", ["a.txt": "a"])

		when:
		ArtifactReuse.isReusable(other, RECIPE, [merged], [merged])

		then:
		thrown(IllegalArgumentException)
	}

	private Path jar(String name, Map<String, String> entries) {
		def target = tempDir.resolve(name)
		Files.deleteIfExists(target)

		FileSystemUtil.getJarFileSystem(target, true).withCloseable { zip ->
			entries.each { path, value ->
				def zipPath = zip.getPath(path)

				if (zipPath.getParent() != null) {
					Files.createDirectories(zipPath.getParent())
				}

				Files.write(zipPath, value.getBytes(StandardCharsets.UTF_8))
			}
		}

		return target
	}
}
