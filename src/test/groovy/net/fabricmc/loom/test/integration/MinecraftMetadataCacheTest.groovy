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

package net.fabricmc.loom.test.integration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.javalin.Javalin
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 守护 Minecraft 元数据的配置缓存输入只包含目标版本的语义结果.
 *
 * <p>只应用仓库插件以取得真实插件 classpath，不运行 Minecraft 产物生产链；所有 HTTP 内容均由本地服务器提供.
 */
class MinecraftMetadataCacheTest extends Specification {
	private static final String VERSION = "1.20.1"
	private static final String CONFIGURED = "METADATA_CONFIGURED"
	private static final String MAIN = "example.Main"

	@TempDir
	Path projectDir

	private Javalin server
	private String baseUrl
	private volatile String manifestJson
	private volatile String manifestEtag
	private volatile String nextMetadataJson
	private String metadataJson
	private final AtomicInteger manifestRequests = new AtomicInteger()
	private final AtomicInteger metadataRequests = new AtomicInteger()
	private final AtomicInteger notModifiedResponses = new AtomicInteger()

	def setup() {
		metadataJson = metadata(MAIN)
		nextMetadataJson = metadataJson
		server = Javalin.create()
		server.get("/manifest") { context ->
			manifestRequests.incrementAndGet()
			context.header("ETag", manifestEtag)

			if (context.header("If-None-Match") == manifestEtag) {
				notModifiedResponses.incrementAndGet()
				context.status(304)
				return
			}

			context.result(manifestJson)
		}
		server.get("/metadata") { context ->
			metadataRequests.incrementAndGet()
			context.result(metadataJson)
		}
		server.get("/metadata-next") { context ->
			metadataRequests.incrementAndGet()
			context.result(nextMetadataJson)
		}
		server.start(0)
		baseUrl = "http://127.0.0.1:${server.port()}"
		setManifest(manifest([
			targetEntry("/metadata", metadataJson)
		]))
		writeProject()
	}

	def cleanup() {
		server?.stop()
	}

	def "全新派生缓存写入后第二次构建立即复用配置缓存"() {
		when:
		def first = runBuild()

		then:
		first.output.contains("Configuration cache entry stored")
		first.output.contains(CONFIGURED)
		first.output.contains("METADATA_MAIN=${MAIN}")
		first.output.count("Architectury Loom:") == 1
		specEntries().size() == 1

		when:
		def second = runBuild()

		then:
		reused(second)
		second.output.contains("METADATA_MAIN=${MAIN}")
		manifestRequests.get() == 1
		metadataRequests.get() == 1
	}

	def "删除或损坏派生缓存只触发重算而不使配置缓存失效"() {
		given:
		runBuild()
		def entry = specEntries().first()

		when: "命中配置缓存检查时重新填充缺失的派生文件"
		Files.delete(entry)
		def afterDeletion = runBuild()

		then:
		reused(afterDeletion)
		Files.isRegularFile(entry)

		when: "损坏的派生文件也不能成为配置输入"
		Files.writeString(entry, "{broken")
		def afterCorruption = runBuild()

		then:
		reused(afterCorruption)
		new JsonSlurper().parseText(Files.readString(entry)).id == VERSION
		manifestRequests.get() == 1
		metadataRequests.get() == 1
	}

	def "清单过期后304更新mtime仍复用配置缓存"() {
		given:
		runBuild()
		def oldTime = expireManifest()

		when:
		def refreshed = runBuild()

		then:
		reused(refreshed)
		manifestRequests.get() == 2
		notModifiedResponses.get() == 1
		Files.getLastModifiedTime(manifestFile()).toMillis() > oldTime.toMillis()
		metadataRequests.get() == 1

		when: "304更新时间后不应立即再次请求"
		def next = runBuild()

		then:
		reused(next)
		manifestRequests.get() == 2
	}

	def "全版本清单新增无关版本改变顺序和排版均不使配置缓存失效"() {
		given:
		runBuild()
		def target = targetEntry("/metadata", metadataJson)
		def unrelated = [id: "99.0", url: "${baseUrl}/never-requested", sha1: "unused"]

		when: "新增版本并把目标条目移到第二位，服务端返回新的200响应"
		setManifest(manifest([unrelated, target]))
		expireManifest()
		def afterAddition = runBuild()

		then:
		reused(afterAddition)
		manifestRequests.get() == 2
		notModifiedResponses.get() == 0

		when: "重新排序并改成带缩进的JSON，latest也不参与目标版本的语义"
		setManifest(JsonOutput.prettyPrint(manifest([target, unrelated], "99.0")))
		expireManifest()
		def afterReordering = runBuild()

		then:
		reused(afterReordering)
		manifestRequests.get() == 3
		notModifiedResponses.get() == 0
		metadataRequests.get() == 1
		afterReordering.output.contains("METADATA_MAIN=${MAIN}")
	}

	def "相同metadata更换来源仍复用而真实metadata变化使配置缓存失效"() {
		given:
		runBuild()

		when: "仅更换目标条目的URL，metadata内容保持一致"
		setManifest(manifest([
			targetEntry("/metadata-next", nextMetadataJson)
		]))
		expireManifest()
		def afterUrlChange = runBuild()

		then:
		reused(afterUrlChange)
		afterUrlChange.output.contains("METADATA_MAIN=${MAIN}")
		manifestRequests.get() == 2

		when: "新清单的sha1对应真实变化的metadata"
		nextMetadataJson = metadata("example.Changed")
		setManifest(manifest([
			targetEntry("/metadata-next", nextMetadataJson)
		]))
		expireManifest()
		def afterSemanticChange = runBuild()

		then:
		!afterSemanticChange.output.contains("Configuration cache entry reused")
		afterSemanticChange.output.contains("configuration cache cannot be reused")
		afterSemanticChange.output.contains(CONFIGURED)
		afterSemanticChange.output.contains("METADATA_MAIN=example.Changed")
		manifestRequests.get() == 3
		metadataRequests.get() == 2

		when:
		def stable = runBuild()

		then:
		reused(stable)
		stable.output.contains("METADATA_MAIN=example.Changed")
	}

	private void writeProject() {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'metadata-cache-test'\n")
		Files.writeString(projectDir.resolve("build.gradle"), """
			plugins {
				id 'top.wcpe.loom-repositories'
			}

			import groovy.json.JsonSlurper
			import net.fabricmc.loom.configuration.providers.minecraft.ManifestLocations
			import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider
			import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataValueSource
			import net.fabricmc.loom.util.gradle.LoomVersionLogService

			def previousLogFlag = System.getProperty('loom.printed.logged')
			LoomVersionLogService.logVersion(project)
			LoomVersionLogService.logVersion(project)
			assert System.getProperty('loom.printed.logged') == previousLogFlag

			def manifests = new ManifestLocations()
			manifests.add('test', '${baseUrl}/manifest', 0)
			def options = new MinecraftMetadataProvider.Options(
				'${VERSION}', manifests, null, file('artifacts').toPath(), file('artifacts/minecraft').toPath())
			def metadata = MinecraftMetadataValueSource.create(providers, options, false, false).get()
			println '${CONFIGURED}'

			abstract class MetadataProbe extends DefaultTask {
				@Input
				abstract Property<String> getMetadata()

				@TaskAction
				void probe() {
					println 'METADATA_MAIN=' + new JsonSlurper().parseText(metadata.get()).mainClass
				}
			}

			tasks.register('metadataProbe', MetadataProbe) {
				getMetadata().set(metadata)
			}
			""".stripIndent())
	}

	private BuildResult runBuild() {
		return GradleRunner.create()
				.withProjectDir(projectDir.toFile())
				.withPluginClasspath()
				.withGradleVersion("9.5.0")
				.withArguments("metadataProbe", "--configuration-cache", "--configuration-cache-problems=fail",
				"--console=plain", "--stacktrace", "--gradle-user-home", projectDir.resolve("gradle-home").toString())
				.build()
	}

	private Path manifestFile() {
		return projectDir.resolve("artifacts/test_versions_manifest.json")
	}

	private List<Path> specEntries() {
		def root = projectDir.resolve("artifacts/spec/versions-manifest")

		return Files.walk(root).withCloseable { paths ->
			paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
		}
	}

	private FileTime expireManifest() {
		def oldTime = FileTime.from(Instant.now() - Duration.ofDays(2))
		Files.setLastModifiedTime(manifestFile(), oldTime)
		return oldTime
	}

	private void setManifest(String json) {
		manifestJson = json
		manifestEtag = '"' + sha1(json) + '"'
	}

	private Map<String, String> targetEntry(String path, String json) {
		return [id: VERSION, url: "${baseUrl}${path}".toString(), sha1: sha1(json)]
	}

	private static String manifest(List<Map> entries, String latest = VERSION) {
		return JsonOutput.toJson([latest: [release: latest], versions: entries])
	}

	private static String metadata(String mainClass) {
		return JsonOutput.toJson([id: VERSION, mainClass: mainClass, downloads: [:], libraries: []])
	}

	private static String sha1(String value) {
		return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
	}

	private static boolean reused(BuildResult result) {
		assert result.output.contains("Configuration cache entry reused")
		assert !result.output.contains(CONFIGURED)
		return true
	}
}
