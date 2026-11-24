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

package net.fabricmc.loom.test.unit

import java.nio.file.Files
import java.nio.file.Path

import com.google.gson.JsonObject
import dev.architectury.loom.mcpconfig.McpConfigData
import dev.architectury.loom.mcpconfig.McpConfigProvider
import dev.architectury.loom.mcpconfig.McpConfigStep
import dev.architectury.loom.mcpconfig.McpExecutor
import dev.architectury.loom.mcpconfig.McpExecutorBuilder
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta
import net.fabricmc.loom.extension.LoomFiles
import net.fabricmc.loom.util.Checksum
import net.fabricmc.loom.util.service.ScopedServiceFactory
import net.fabricmc.loom.util.service.Service

class McpOfflineInputsTest extends Specification {
	private static final String VERSION = 'test-version'
	// 不可用于网络传输；若错误进入下载分支，会在 URL 解析时失败。
	private static final String NO_NETWORK_REPO = 'not-a-network-protocol://mcp/'

	@TempDir Path tempDir
	private Project project
	private Path userCache
	private Path versionCache

	def setup() {
		project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
		userCache = tempDir.resolve('loom')
		versionCache = userCache.resolve(VERSION)
		def files = Stub(LoomFiles) {
			getUserCache() >> userCache.toFile()
		}
		project.extensions.add('loom', Stub(LoomGradleExtension) {
			getFiles() >> files
		})
	}

	def '离线 #side 映射只选匹配官方摘要的 layered 文件，并跨步骤目录复用持久副本'() {
		given:
		def type = side == 'client' ? 'downloadClientMappings' : 'downloadServerMappings'
		def mapping = "${side} 官方映射".toString()
		def sha1 = Checksum.of(mapping).sha1().hex()
		def persistent = versionCache.resolve("mcp/official-mappings/${sha1}/${side}.txt")
		def wrong = versionCache.resolve("layered/working_dir/a/mojang/${side}.txt")
		def correct = versionCache.resolve("layered/working_dir/z/mojang/${side}.txt")
		put(wrong, '摘要不符')
		put(correct, mapping)
		put(persistent, '损坏的持久副本')
		def step = new McpConfigStep(type, 'mojangMappings', [:])
		def options = stepOptions(step, [("${side}_mappings".toString()): download(sha1)])

		when:
		def first = execute(step, options, 'first')
		Files.delete(correct)
		def second = execute(step, options, 'second')

		then:
		first == tempDir.resolve('first/mojangMappings/output')
		second == tempDir.resolve('second/mojangMappings/output')
		Files.readString(first) == mapping
		Files.readString(second) == mapping
		Files.readString(persistent) == mapping
		Files.readString(wrong) == '摘要不符'

		where:
		side << ['client', 'server']
	}

	def '离线 #caseName 不使用未经校验的步骤输出，也不尝试下载'() {
		given:
		def sha1 = Checksum.of('官方映射').sha1().hex()
		def persistent = versionCache.resolve("mcp/official-mappings/${sha1}/client.txt")
		def output = tempDir.resolve('work/mojangMappings/output')
		if (corrupt) {
			put(output, '不可信的旧输出')
			put(persistent, '持久副本摘要不符')
			put(versionCache.resolve('layered/working_dir/a/mojang/client.txt'), '原文摘要不符')
		}
		def step = new McpConfigStep('downloadClientMappings', 'mojangMappings', [:])
		def options = stepOptions(step, [client_mappings: download(sha1)])

		when:
		execute(step, options, 'work')

		then:
		def failure = thrown(IOException)
		failure.message.contains('offline')
		failure.message.contains('client')
		failure.message.contains(sha1)
		corrupt ? Files.readString(output) == '不可信的旧输出' : Files.notExists(output)
		corrupt ? Files.readString(persistent) == '持久副本摘要不符' : Files.notExists(persistent)

		where:
		caseName | corrupt
		'资料损坏' | true
		'资料缺失' | false
	}

	private static MinecraftVersionMeta.Download download(String sha1) {
		new MinecraftVersionMeta.Download(null, sha1, 0L, NO_NETWORK_REPO + 'mapping.txt')
	}

	private Service.Options stepOptions(McpConfigStep step, Map downloads) {
		def versionMeta = new MinecraftVersionMeta(null, null, '', 0, downloads, VERSION, [], null, '', 0, '', '', '', null)
		def versionDir = versionCache.toFile()
		def minecraft = Stub(MinecraftProvider) {
			minecraftVersion() >> VERSION
			getVersionInfo() >> versionMeta
			workingDir() >> versionDir
		}
		def data = new McpConfigData(VERSION, new JsonObject(), null, false, [joined: [step]], [:])
		def mcp = Stub(McpConfigProvider) {
			getData() >> data
		}
		def builder = new McpExecutorBuilder(project, minecraft, tempDir.resolve('work'), mcp, 'joined')
		def setupStep = McpExecutorBuilder.getDeclaredMethod('getStepLogic', String, String)
		setupStep.accessible = true
		setupStep.invoke(builder, step.name(), step.type()).get()
	}

	private Path execute(McpConfigStep step, Service.Options logic, String work) {
		def options = McpExecutor.TYPE.create(project) {
			it.stepLogicOptions.set([(step.name()): logic])
			it.stepsToExecute.set([step])
			it.initialConfig.set([:])
			it.cache.set(tempDir.resolve(work).toFile())
			it.offline.set(true)
			it.manualRefreshDeps.set(false)
		}
		new ScopedServiceFactory().withCloseable { it.get(options).execute() }
	}

	private static void put(Path path, String content) {
		Files.createDirectories(path.parent)
		Files.writeString(path, content)
	}
}
