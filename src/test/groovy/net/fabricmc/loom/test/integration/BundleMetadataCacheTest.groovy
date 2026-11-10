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
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 服务端 bundle 的派生缓存只应暴露可用状态与解析结果，不暴露 JSON 排版等维护状态.
 *
 * <p>使用本地微型 jar，不应用 Minecraft 产物管线；冷暖切换改变任务投影时允许一次合法重配.
 */
class BundleMetadataCacheTest extends Specification {
	private static final String CONFIGURED = "BUNDLE_CONFIGURED"
	private static final String MAIN = "example.BundleMain"
	private static final String LIBRARY = "example:library:1.0"
	private static final String INNER_HASH = "0123456789012345678901234567890123456789"

	@TempDir
	Path projectDir

	def setup() {
		writeBundledJar(MAIN, LIBRARY)
		writeProject()
	}

	def "暖缓存只改变JSON排版和对象键顺序仍复用配置缓存"() {
		given:
		warmCache()
		def entry = cacheEntry()
		def cached = new JsonSlurper().parseText(Files.readString(entry))
		def reformatted = JsonOutput.prettyPrint(JsonOutput.toJson([
			mainClass: cached.mainClass,
			versions: cached.versions,
			libraries: cached.libraries
		]))

		when:
		Files.writeString(entry, reformatted)
		def result = runBuild()

		then:
		reused(result)
		result.output.contains("BUNDLE_SUMMARY=${MAIN}|${LIBRARY}|1.20.1")
	}

	def "损坏或结构无效的bundle缓存应重新读取jar而不是误作legacy：#description"() {
		given:
		def entry = cacheEntry()
		Files.createDirectories(entry.parent)
		Files.writeString(entry, content)

		when:
		def result = runBuild()

		then:
		result.output.contains("BUNDLE_AVAILABLE=false")
		result.output.contains("BUNDLE_SUMMARY=${MAIN}|${LIBRARY}|1.20.1")
		def repaired = new JsonSlurper().parseText(Files.readString(entry))
		repaired.mainClass == MAIN
		repaired.libraries*.name == [LIBRARY]
		repaired.versions*.name == ["1.20.1"]

		where:
		description | content
		"语法损坏" | "{broken"
		"JSON null" | "null"
		"仅有空白" | " \n\t "
		"字段缺失" | "{}"
	}

	def "精确空字符串是有效legacy结论且暖缓存不再读取jar"() {
		given:
		writeLegacyJar()

		when:
		def first = runBuild()
		def second = runBuild()

		then:
		first.output.contains("BUNDLE_SUMMARY=legacy")
		second.output.contains("BUNDLE_AVAILABLE=true")
		Files.readString(cacheEntry()).isEmpty()

		when:
		def stable = runBuild()

		then:
		reused(stable)
		stable.output.contains("BUNDLE_SUMMARY=legacy")

		when: "已有明确的legacy结论不依赖jar是否仍在磁盘上"
		Files.delete(projectDir.resolve("server.jar"))
		def afterDeletion = runBuild()

		then:
		reused(afterDeletion)
		afterDeletion.output.contains("BUNDLE_SUMMARY=legacy")
	}

	def "真实bundle元数据和制品身份变化必须重配并消费新值"() {
		given:
		warmCache()

		when:
		writeBundledJar("example.ChangedMain", "example:library:2.0")
		def changed = runBuild()

		then:
		!changed.output.contains("Configuration cache entry reused")
		changed.output.contains(CONFIGURED)
		changed.output.contains("BUNDLE_SUMMARY=example.ChangedMain|example:library:2.0|1.20.1")

		when: "冷条目填充后允许任务投影由回退路径切换至缓存路径，再检查稳定复用"
		runBuild()
		def stable = runBuild()

		then:
		reused(stable)
		stable.output.contains("BUNDLE_SUMMARY=example.ChangedMain|example:library:2.0|1.20.1")
	}

	private void warmCache() {
		// 旧入口的首轮填充会使是否可投影改变；复用断言从明确的暖态开始。
		runBuild()
		runBuild()
		assert reused(runBuild())
	}

	private void writeProject() {
		Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'bundle-cache-test'\n")
		Files.writeString(projectDir.resolve("build.gradle"), """
			plugins {
				id 'top.wcpe.loom-repositories'
			}

			import net.fabricmc.loom.configuration.providers.BundleMetadataValueSource

			def artifactSha1 = providers.fileContents(layout.projectDirectory.file('server.sha1')).asText.get().trim()
			def userCache = file('artifacts').toPath()
			def available = BundleMetadataValueSource.isCached(providers, userCache, artifactSha1)
			def projection = tasks.register(available ? 'cachedProjection' : 'eagerProjection')
			def bundle = BundleMetadataValueSource.fromJarCached(providers, userCache, file('server.jar').toPath(), artifactSha1)
			def snapshot = bundle == null ? 'legacy' : bundle.mainClass() + '|' +
				bundle.libraries().collect { it.name() }.join(',') + '|' + bundle.versions().collect { it.name() }.join(',')
			println '${CONFIGURED}'
			println 'BUNDLE_AVAILABLE=' + available

			abstract class BundleProbe extends DefaultTask {
				@Input
				abstract Property<String> getSnapshot()

				@TaskAction
				void probe() {
					println 'BUNDLE_SUMMARY=' + snapshot.get()
				}
			}

			tasks.register('bundleProbe', BundleProbe) {
				dependsOn(projection)
				getSnapshot().set(snapshot)
			}
			""".stripIndent())
	}

	private void writeBundledJar(String mainClass, String library) {
		writeJar([
			"META-INF/libraries.list": "${INNER_HASH}\t${library}\tlibrary.jar\n".toString(),
			"META-INF/versions.list": "${INNER_HASH}\t1.20.1\tserver.jar\n".toString(),
			"META-INF/main-class": mainClass + "\n"
		])
	}

	private void writeLegacyJar() {
		writeJar(["legacy.txt": "旧版服务端，没有bundle清单\n"])
	}

	private void writeJar(Map<String, String> entries) {
		def jar = projectDir.resolve("server.jar")
		new JarOutputStream(Files.newOutputStream(jar)).withCloseable { stream ->
			entries.each { name, content ->
				def entry = new JarEntry(name)
				entry.time = 0L
				stream.putNextEntry(entry)
				stream.write(content.getBytes(StandardCharsets.UTF_8))
				stream.closeEntry()
			}
		}
		def sha1 = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar)).encodeHex().toString()
		Files.writeString(projectDir.resolve("server.sha1"), sha1 + "\n")
	}

	private Path cacheEntry() {
		def sha1 = Files.readString(projectDir.resolve("server.sha1")).trim()
		return projectDir.resolve("artifacts/spec/bundle-metadata/${sha1}/v1-server-bundle.json")
	}

	private BuildResult runBuild() {
		return GradleRunner.create()
				.withProjectDir(projectDir.toFile())
				.withPluginClasspath()
				.withGradleVersion("9.5.0")
				.withArguments("bundleProbe", "--configuration-cache", "--configuration-cache-problems=fail",
				"--console=plain", "--stacktrace", "--gradle-user-home", projectDir.resolve("gradle-home").toString())
				.build()
	}

	private static boolean reused(BuildResult result) {
		assert result.output.contains("Configuration cache entry reused")
		assert !result.output.contains(CONFIGURED)
		return true
	}
}
