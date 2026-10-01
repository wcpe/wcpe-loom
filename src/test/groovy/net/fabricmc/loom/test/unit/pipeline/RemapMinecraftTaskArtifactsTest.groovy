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

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar
import net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider
import net.fabricmc.loom.pipeline.RemapMinecraftTask

/**
 * 任务侧声明的 pom / backup 位置必须与既有实现一致.
 *
 * <p>一个 maven 构件目录里有三个属于 mapped provider 的产物：jar 本身、同名的 {@code .pom}（坐标式依赖解析
 * 必需）与 {@code <jar>.backup}（genSources 的 {@code @Classpath} 输入）。任务把它们各自声明为
 * {@code @OutputFile}，于是「声明的位置」与「既有实现写出/读取的位置」必须逐一对齐：
 * <ul>
 *   <li>pom：{@link RemapMinecraftTask#pomPathFor(Path)} 是与 {@link LocalMavenHelper#savePom()} 一致的落位规则，
 *       本用例真的写一次 pom 来核对（内容仍由 {@code savePom} 生成，任务不另写模板）；</li>
 *   <li>backup：{@link AbstractMappedMinecraftProvider#getBackupJarPath(MinecraftJar)} 是 genSources 的查找规则，
 *       任务侧的校验用的是同一条规则。</li>
 * </ul>
 * 这两条规则都是「约定」而非类型约束：一旦它们与实现分离，产物会写到没人读的位置（坐标解析缺 pom、
 * genSources 找不到输入），且不会立刻报错。
 */
class RemapMinecraftTaskArtifactsTest extends Specification {
	@TempDir
	Path tempDir

	private Path artifactJar

	def setup() {
		def mavenHelper = new LocalMavenHelper("net.minecraft", "minecraft-merged", "1.20.1-yarn", null, tempDir.resolve("minecraftMaven"))
		artifactJar = mavenHelper.getOutputFile(null)
	}

	def "pom 落位规则与 LocalMavenHelper.savePom 实际写出的位置一致"() {
		given:
		def mavenHelper = new LocalMavenHelper("net.minecraft", "minecraft-merged", "1.20.1-yarn", null, tempDir.resolve("minecraftMaven"))

		when: "按任务侧的规则推导 pom 位置并真的写一次"
		def pom = RemapMinecraftTask.pomPathFor(artifactJar)
		Files.createDirectories(pom.getParent())
		mavenHelper.savePom()

		then: "pom 与 jar 同目录同名，只有扩展名不同"
		pom.getParent() == artifactJar.getParent()
		pom.getFileName().toString() == "minecraft-merged-1.20.1-yarn.pom"

		and: "savePom 写出的正是这个文件（内容由它生成，任务不另写一份）"
		Files.exists(pom)
		def content = Files.readString(pom)
		content.contains("<groupId>net.minecraft</groupId>")
		content.contains("<artifactId>minecraft-merged</artifactId>")
		content.contains("<version>1.20.1-yarn</version>")
	}

	def "backup 位置规则与 genSources 使用的 getBackupJarPath 一致"() {
		when:
		def backup = AbstractMappedMinecraftProvider.getBackupJarPath(new MinecraftJar.Merged(artifactJar))

		then: "任务侧校验的规则（jar 同目录、名为 <jar>.backup）与它得出同一路径"
		backup.getParent() == artifactJar.getParent()
		backup.getFileName().toString() == artifactJar.getFileName().toString() + ".backup"
	}

	def "产物名没有扩展名时推导 pom 直接失败，而不是给出一个看似合理的路径"() {
		when:
		RemapMinecraftTask.pomPathFor(tempDir.resolve("minecraft-merged-1.20.1-yarn"))

		then:
		thrown(IllegalArgumentException)
	}
}
