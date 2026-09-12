/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 WCPE
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

import java.util.jar.JarFile

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.Checksum

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class RemapTaskCacheTest extends Specification implements GradleProjectTestTrait {
	def "remapped classes and sources restore from cache and track changed content"() {
		given:
		def gradle = cacheProject()
		def tasks = ["remapJar", "remapSourcesJar"]
		def original = gradle.run(tasks: tasks, args: ["--build-cache"])
		def outputs = outputJars(gradle)
		def hashes = outputs.collect { Checksum.of(it).sha256().hex() }

		when:
		// 只删除测试项目的两份最终输出，保留输入和缓存，证明是 FROM_CACHE 而非 UP_TO_DATE。
		outputs.each { assert it.delete() }
		def restored = gradle.run(tasks: tasks, args: ["--build-cache"])

		then:
		tasks.every { original.task(":$it").outcome == SUCCESS }
		tasks.every { restored.task(":$it").outcome == FROM_CACHE }
		outputs.collect { Checksum.of(it).sha256().hex() } == hashes

		when:
		def reused = gradle.run(tasks: tasks, args: ["--build-cache"])

		then:
		reused.output.contains("Configuration cache entry reused.")
		tasks.every { reused.task(":$it").outcome == UP_TO_DATE }

		when:
		def source = new File(gradle.projectDir, "src/main/java/example/Example.java")
		source.text = source.text.replace("return 1;", "return 2;")
		def changed = gradle.run(tasks: tasks, args: ["--build-cache"])

		then:
		tasks.every { changed.task(":$it").outcome == SUCCESS }
		outputs.collect { Checksum.of(it).sha256().hex() } != hashes
		gradle.getOutputZipEntry("fabric-example-mod-1.0.0-sources.jar", "example/Example.java").contains("return 2;")
	}

	def "archives retaining file timestamps do not use the output cache"() {
		given:
		def gradle = cacheProject()
		gradle.buildGradle << '''
			tasks.withType(net.fabricmc.loom.task.AbstractRemapJarTask).configureEach {
				preserveFileTimestamps = true
			}
		'''.stripIndent()
		def tasks = ["remapJar", "remapSourcesJar"]
		gradle.run(tasks: tasks, args: ["--build-cache"])

		when:
		outputJars(gradle).each { assert it.delete() }
		def rebuilt = gradle.run(tasks: tasks, args: ["--build-cache"])

		then:
		tasks.every { rebuilt.task(":$it").outcome == SUCCESS }
	}

	def "manifest service values participate in remapping inputs"() {
		given:
		def gradle = cacheProject()
		gradle.buildGradle << '''
			gradle.sharedServices.registrations.named("LoomJarManifestService:" + project.name) {
				parameters.minecraftVersion.set(providers.gradleProperty("cacheTestManifestVersion").orElse("manifest-one"))
			}
			tasks.named("jar") {
				manifest.attributes('Fabric-Mixin-Version': 'custom-mixin', 'Fabric-Mixin-Group': 'custom-group')
			}
		'''.stripIndent()
		def tasks = ["remapJar", "remapSourcesJar"]
		def original = gradle.run(tasks: tasks, args: ["--build-cache"])

		expect:
		tasks.every { original.task(":$it").outcome == SUCCESS }
		outputJars(gradle).every { manifestValue(it, "Fabric-Minecraft-Version") == "manifest-one" }

		when:
		def changed = gradle.run(tasks: tasks, args: ["--build-cache", "-PcacheTestManifestVersion=manifest-two"])

		then:
		tasks.every { changed.task(":$it").outcome == SUCCESS }
		outputJars(gradle).every { manifestValue(it, "Fabric-Minecraft-Version") == "manifest-two" }
		manifestValue(gradle.getOutputFile("fabric-example-mod-1.0.0.jar"), "Fabric-Mixin-Version") == "custom-mixin"
		manifestValue(gradle.getOutputFile("fabric-example-mod-1.0.0.jar"), "Fabric-Mixin-Group") == "custom-group"

		when:
		def reproducible = gradle.run(tasks: tasks, args: ["--build-cache", "-PcacheTestManifestVersion=manifest-two", "-Dloom.test.reproducible=true"])

		then:
		tasks.every { reproducible.task(":$it").outcome == SUCCESS }
		outputJars(gradle).every { manifestValue(it, "Fabric-Minecraft-Version") == null }
	}

	def "invalid target namespace cannot reuse a successful remapping"() {
		given:
		def gradle = cacheProject()
		gradle.buildGradle << '''
			tasks.named("remapJar") {
				targetNamespace.set(providers.gradleProperty("cacheTestNamespace").orElse("intermediary"))
			}
		'''.stripIndent()
		gradle.run(task: "remapJar", args: ["--build-cache"])

		when:
		def failed = gradle.run(task: "remapJar", args: ["--build-cache", "-PcacheTestNamespace=missing"], expectFailure: true)

		then:
		failed.task(":remapJar").outcome == FAILED
		!gradle.getOutputFile("fabric-example-mod-1.0.0.jar").exists()
	}

	private GradleProject cacheProject() {
		def gradle = gradleProject(project: "minimalBase", version: DEFAULT_GRADLE)
		new File(gradle.projectDir, "settings.gradle") << '''
			buildCache {
				local { directory = file('.gradle/test-build-cache') }
			}
		'''.stripIndent()
		gradle.gradleProperties << "org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx768m\n"
		gradle.buildGradle << '''
			dependencies {
				minecraft 'com.mojang:minecraft:1.20.4'
				mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
				modImplementation 'net.fabricmc:fabric-loader:0.15.6'
			}
			java {
				withSourcesJar()
			}
		'''.stripIndent()
		def source = new File(gradle.projectDir, "src/main/java/example/Example.java")
		source.parentFile.mkdirs()
		source.text = '''
			package example;
			public class Example {
				public net.minecraft.item.Item item;
				public int value() { return 1; }
			}
		'''.stripIndent()
		return gradle
	}

	private static List<File> outputJars(GradleProject gradle) {
		return [gradle.getOutputFile("fabric-example-mod-1.0.0.jar"), gradle.getOutputFile("fabric-example-mod-1.0.0-sources.jar")]
	}

	private static String manifestValue(File file, String name) {
		try (def jar = new JarFile(file)) {
			return jar.manifest.mainAttributes.getValue(name)
		}
	}
}
