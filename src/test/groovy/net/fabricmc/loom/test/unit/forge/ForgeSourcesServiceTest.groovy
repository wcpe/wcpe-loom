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

import java.nio.file.Files
import java.nio.file.Path

import dev.architectury.loom.forge.ForgeSourcesService
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.mockito.Mockito
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.task.service.MappingsService
import net.fabricmc.loom.task.service.SourceRemapperService
import net.fabricmc.loom.util.Checksum
import net.fabricmc.loom.util.service.ServiceFactory

/**
 * Forge 源码注入的两条实现约束.
 *
 * <h4>输入 jar 不存在时不许「就地造 jar」</h4>
 * 类过滤的依据是 named MC jar 的**内容**（源码是否保留取决于同名 class 是否存在于该 jar）。
 * MC jar 的生产已搬到任务，因此在配置期它可能还不存在——这正是冷缓存下的常态。
 *
 * <p>旧实现用 {@code getJarFileSystem(minecraftJar, true)}（create=true）打开这个输入：父目录存在时
 * zipfs 会**就地造出一个空 jar**，于是
 * <ol>
 *   <li>磁盘上凭空多出一份「存在但为空」的产物（若该路径正是 maven 构件路径，它会骗过后续的存在性判定）；</li>
 *   <li>类过滤因为「所有 class 都不存在」而丢掉全部 Forge 源码，写出一个内容错误的源码包，且不报错。</li>
 * </ol>
 * 现在输入改为只读打开：文件缺失时直接失败，且因为资源按序初始化，输出 jar 也不会被创建。
 * 本用例把这两条都固定下来（把输入改回 create=true 会让它失败）。
 *
 * <h4>注入缓存键必须覆盖「重映射用的映射」</h4>
 * 注入进缓存条目的是**重映射之后**的源码文本，而重映射结果由
 * {@code getSourcesCacheKey()} 所覆盖的那几项输入决定。映射文件若不入键，则
 * 「换映射、类字节不变」时会全命中并按旧映射静默产出旧源码；本用例固定它按**内容**入键
 * （路径与文件名不进键）。
 */
class ForgeSourcesServiceTest extends Specification {
	@TempDir
	Path temp

	def "输入 jar 不存在时直接失败，且不产出任何 jar"() {
		given: "一个父目录存在、但文件本身不存在的输入 jar（冷缓存下 MC 产物的状态）"
		Path missingMinecraftJar = temp.resolve("minecraft-merged-named.jar")
		Path sourcesJar = temp.resolve("minecraft-merged-named-sources.jar")

		and: "Forge 源码包为空：本用例只关心「输入缺失」这一环"
		ConfigurableFileCollection forgeSourceJars = Mock()
		forgeSourceJars.iterator() >> Collections.emptyIterator()
		ForgeSourcesService.Options options = Stub() {
			getForgeSourceJars() >> forgeSourceJars
		}
		def service = new ForgeSourcesService(options, Mock(ServiceFactory))

		when:
		service.addForgeSources(missingMinecraftJar, sourcesJar, false)

		then: "失败是显式的"
		thrown(IOException)

		and: "既没有就地造出一个空输入 jar，也没有写出源码包"
		Files.notExists(missingMinecraftJar)
		Files.notExists(sourcesJar)
	}

	def "named MC jar 缺失时配置期入口整体跳过"() {
		given: "named MC jar 的路径已确定，但产物还不存在（任务尚未生产）"
		Path missingMinecraftJar = temp.resolve("minecraft-merged-named.jar")
		Path sourcesJar = temp.resolve("minecraft-merged-named-sources.jar")
		def project = Mock(Project)
		project.getLogger() >> Mock(Logger)
		def extension = Mock(LoomGradleExtension)
		extension.getMinecraftJars(MappingsNamespace.NAMED) >> [missingMinecraftJar]
		def serviceFactory = Mock(ServiceFactory)

		when:
		// Groovy 不支持 try-with-resources（那是 Java 语法），故显式 close
		def mocked = Mockito.mockStatic(LoomGradleExtension)
		try {
			mocked.when { LoomGradleExtension.get(project) }.thenReturn(extension)
			ForgeSourcesService.addForgeSourcesDuringProjectConfiguration(project, serviceFactory)
		} finally {
			mocked.close()
		}

		then: "整批跳过：不取服务（也就不会解压归档／装配源码包），也不写任何文件"
		Files.notExists(sourcesJar)
		Files.notExists(missingMinecraftJar)
		0 * serviceFactory._
	}

	def "注入缓存键覆盖源码重映射映射的内容，且只认内容不认路径"() {
		given: "同一份 srg→named 映射分别落在两个不同路径下"
		Path firstDir = Files.createDirectories(temp.resolve("first"))
		Path secondDir = Files.createDirectories(temp.resolve("second/nested"))
		Path first = firstDir.resolve("mappings-srg.tiny")
		Path second = secondDir.resolve("mappings-srg.tiny")
		String content = "tiny\t2\t0\tofficial\tsrg\tintermediary\tnamed\n\tc\tnet/minecraft/client/renderer/RenderType\n\t\tm\t()Ljava/util/List;\tG\tm_110506_\tmethod_22720\tchunkBufferLayers\n"
		Files.writeString(first, content)
		Files.writeString(second, content)

		when: "分别用这两份文件算注入缓存键"
		String keyFirst = serviceWithRemapMappings(first).getSourcesCacheKey()
		String keySecond = serviceWithRemapMappings(second).getSourcesCacheKey()

		then: "内容相同则键相同：路径不进键"
		keyFirst == keySecond

		and: "该文件的内容哈希确实在键里，而不是被算成了空"
		keyFirst.contains(Checksum.of(first).sha256().hex())

		when: "只改映射里的一个名字，文件路径与其余一切都不动"
		Files.writeString(first, content.replace("chunkBufferLayers", "chunkBufferLayersRenamed"))
		String keyChanged = serviceWithRemapMappings(first).getSourcesCacheKey()

		then: "键必须变：否则「换了映射、类字节却没变」时注入会按旧映射静默产出旧源码"
		keyChanged != keyFirst
		!keyChanged.contains(Checksum.of(second).sha256().hex())

		when: "映射文件不在场（拿不到该文件）"
		String keyMissing = serviceWithRemapMappings(temp.resolve("absent.tiny")).getSourcesCacheKey()

		then: "不抛异常，且与文件在场时不同：缺失不会被当成「与旧内容等价」"
		keyMissing != keyFirst
		keyMissing != keyChanged

		when: "不重映射的形态（unobfuscated）根本没有源码重映射服务"
		String keyNoRemap = serviceWithRemapMappings(null).getSourcesCacheKey()

		then: "照样能算出键，且与「有映射」不同：少了这一类注入输入也必须改键"
		keyNoRemap != keyFirst
		// 刻意不断言它与「拿不到文件」那次不同：两者都退化成空分量，而「拿不到文件」在真跑重映射时
		// 会以读映射失败显式报错（MappingsService），不会静默产出，共用空分量无风险。
	}

	/**
	 * 造一个只摆好「源码重映射用的映射文件」这一项注入输入的服务：源码包与预解压目录都为空.
	 *
	 * <p>其余（工具链 stderr 开关等）与缓存键无关，不设置。
	 *
	 * <p>刻意不是 static：Spock 的 mock 不能在静态作用域里创建。
	 *
	 * @param mappingsFile 源码重映射用的映射文件；文件不存在表示「拿不到该文件」，
	 *         传 {@code null} 表示「这一形态不做重映射」（unobfuscated）
	 */
	private ForgeSourcesService serviceWithRemapMappings(Path mappingsFile) {
		ConfigurableFileCollection forgeSourceJars = Mock()
		forgeSourceJars.iterator() >> Collections.emptyIterator()
		ConfigurableFileCollection forgeSourceDirectories = Mock()
		forgeSourceDirectories.iterator() >> Collections.emptyIterator()

		ForgeSourcesService.Options options = Mock()
		options.getForgeSourceJars() >> forgeSourceJars
		options.getForgeSourceDirectories() >> forgeSourceDirectories

		if (mappingsFile == null) {
			Property absentRemapper = Mock()
			absentRemapper.isPresent() >> false
			options.getSourceRemapperService() >> absentRemapper
			return new ForgeSourcesService(options, Mock(ServiceFactory))
		}

		RegularFile mappingsRegularFile = Mock()
		mappingsRegularFile.getAsFile() >> mappingsFile.toFile()
		RegularFileProperty mappingsFileProperty = Mock()
		mappingsFileProperty.get() >> mappingsRegularFile

		MappingsService.Options mappingsOptions = Mock()
		mappingsOptions.getMappingsFile() >> mappingsFileProperty
		Property mappingsOptionsProperty = Mock()
		mappingsOptionsProperty.get() >> mappingsOptions

		SourceRemapperService.Options remapperOptions = Mock()
		remapperOptions.getMappings() >> mappingsOptionsProperty
		Property remapperProperty = Mock()
		remapperProperty.isPresent() >> true
		remapperProperty.get() >> remapperOptions
		options.getSourceRemapperService() >> remapperProperty

		return new ForgeSourcesService(options, Mock(ServiceFactory))
	}
}
