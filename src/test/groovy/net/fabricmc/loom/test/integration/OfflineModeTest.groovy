/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.PRE_RELEASE_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class OfflineModeTest extends Specification implements GradleProjectTestTrait {
	// Test offline mode when running on a previously locked project
	@Unroll
	def "Offline refresh deps"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: PRE_RELEASE_GRADLE)
		gradle.buildGradle << """
            dependencies {
                minecraft 'com.mojang:minecraft:1.20.4'
                mappings 'net.fabricmc:yarn:1.20.4+build.3:v2'
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                modImplementation 'net.fabricmc.fabric-api:fabric-api:0.95.4+1.20.4'
            }

			import net.fabricmc.loom.util.Checksum
			def projectHash = Checksum.of(getProject()).sha1().hex()
            println("%%" + projectHash + "%%")

            """.stripIndent()
		when:
		// Normal online run to populate the caches
		def result1 = gradle.run(task: "build")

		def projectHash = result1.output.split("%%")[1]

		// 写入一个伪造的残留锁文件。
		//
		// 锁文件机制已整个废弃（理由见 ConfigurationCacheTest 末尾的说明）：Loom 不再读写它。
		// 但升级前的旧版本、或降级回去的构建会在缓存目录里留下这类文件，所以「它的存在
		// 不得影响新版本」是一个真实且必须守住的风险。
		def lockFile = new File(gradle.gradleHomeDir, "caches/fabric-loom/.${projectHash}.lock")
		lockFile.text = "12345"

		// Run with --offline to ensure that nothing is downloaded.
		def result2 = gradle.run(tasks: ["clean", "build"], args: ["--offline"])
		then:
		result1.task(":build").outcome == SUCCESS
		// 残留锁文件不得导致离线构建失败，也不得触发缓存重建（离线环境下重建必然失败）
		result2.task(":build").outcome == SUCCESS

		and: "离线构建全程不得等待锁——配置期不再存在跨项目串行点"
		!result2.output.contains("Have been waiting on")
	}
}
