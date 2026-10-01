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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import org.gradle.testfixtures.ProjectBuilder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import spock.lang.Specification

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar
import net.fabricmc.loom.pipeline.ExecutionProcessorContext
import net.fabricmc.loom.pipeline.ExecutionProcessorContext.JarConfigurationKind
import net.fabricmc.loom.task.service.MappingsService
import net.fabricmc.loom.task.service.TinyRemapperService
import net.fabricmc.loom.util.service.ScopedServiceFactory
import net.fabricmc.loom.util.service.ServiceType
import net.fabricmc.tinyremapper.TinyRemapper

/**
 * 守住「一次构建里多个 processor 同时取用 remapper」这个场景.
 *
 * <p>{@code ProcessMinecraftJarTask} 对整条 processor 链只建**一个** {@link ExecutionProcessorContext}
 * （见其 {@code process()}），而链上两个 processor 会各自请求一次 remapper：
 * {@code AccessWidenerJarProcessor}（先跑）与 {@code InterfaceInjectionProcessor}（后跑）。
 * 执行期的 remapper 由 {@link TinyRemapperService} 统一装配，{@code ExecutionProcessorContext}
 * 交给每个消费者的 {@code LazyCloseable} 的 {@code close()} 是**空操作**——这正是并列使用最容易写错的地方：
 * 配置期同名的 {@code ContextImplHelper.createRemapper} 用的是 {@code TinyRemapper::finish}，
 * 若执行期照抄，先跑完的那一环就会把**共享的** remapper 拆掉，后跑的那一环要么拿不到、
 * 要么拿到已废的对象，产物**静默出错**（或直接崩在难以定位的地方）。
 *
 * <h2>「仍然可用」怎么才算数</h2>
 * 只断言「第二次 {@code get()} 不抛异常」是不够的：{@code TinyRemapper.finish()} 只清空**已读入的类**
 * 并关掉内部线程池，**不动映射表**，所以「关掉之后 {@code map()} 还能映射」在 finish 前后都成立，
 * 拿它当活性证据等于没测。本用例因此真的往 remapper 的 classpath 里塞一个探针类
 * （见 {@link #PROBE_CLASS}），用「remapper 还记不记得这个类」判定它是否还活着；
 * 并且最后用一个**正控**（服务被关掉时探针必须消失）证明这条探针确实看得见 finish。
 *
 * <p>映射只有一个类（{@code net/minecraft/class_1234 -> net/minecraft/Probe}），刻意与 Minecraft 无关：
 * 这里验的是共享与生命周期语义，不是映射结果。用真实映射而非空映射，是为了让「可用」可以被观测到
 * ——空映射下任何输入都会原样返回，映射成功与「remapper 根本没生效」无法区分。
 */
class ExecutionProcessorContextSharedRemapperTest extends Specification {
	/** 最小 tiny(v1) 映射：official 下的 {@code net/minecraft/class_1234} 在 named 下叫 {@code net/minecraft/Probe}. */
	private static final String MAPPINGS = "v1\tofficial\tnamed\nCLASS\tnet/minecraft/class_1234\tnet/minecraft/Probe\n"
	private static final String OFFICIAL_CLASS = "net/minecraft/class_1234"
	private static final String NAMED_CLASS = "net/minecraft/Probe"
	/** remapper classpath 里的探针类：它是否还在，是「这份 remapper 是否已被拆解」的判据. */
	private static final String PROBE_CLASS = "probe/Live"

	private ScopedServiceFactory serviceFactory
	private ExecutionProcessorContext context
	private TinyRemapperService.Options remapperOptions

	def setup() {
		def project = ProjectBuilder.builder().withName("shared-remapper-probe").build()
		def objects = project.objects
		Path probeDir = Files.createTempDirectory("loom-shared-remapper")
		Path mappingsFile = probeDir.resolve("probe.tiny")
		Files.writeString(mappingsFile, MAPPINGS)

		// 上下文构造要求一个 TinyMappingsService.Options；本用例不读映射树，给个空实例即可
		def contextMappingsOptions = objects.newInstance(TinyMappingsService.Options)

		def mappingsOptions = ServiceType.createOptions(objects, MappingsService.TYPE) { options ->
			options.mappingsFile.set(mappingsFile.toFile())
			options.from.set("official")
			options.to.set("named")
			options.remapLocals.set(true)
		}

		remapperOptions = ServiceType.createOptions(objects, TinyRemapperService.TYPE) { options ->
			options.from.set("official")
			options.to.set("named")
			options.mappings.add(mappingsOptions)
			options.uselegacyMixinAP.set(false)
			options.knownIndyBsms.set([])
			options.remapperExtensions.set([])
			// classpath 会在 TinyRemapperService 构造时被读入，探针类因此成为「remapper 还活着」的痕迹
			options.classpath.from([
				writeProbeJar(probeDir).toFile()
			])
		}

		serviceFactory = new ScopedServiceFactory()
		context = new ExecutionProcessorContext(
				serviceFactory,
				JarConfigurationKind.MERGED,
				MinecraftJar.Type.MERGED,
				false,
				MappingsNamespace.OFFICIAL,
				MappingsNamespace.INTERMEDIARY,
				contextMappingsOptions,
				remapperOptions)
	}

	def cleanup() {
		serviceFactory.close()
	}

	def "链上先后两个消费者都能取到 remapper，前一个归还后后一个仍可用"() {
		when: "第一个消费者（等价于 AccessWidenerJarProcessor）取用，这是链上的真实顺序"
		def first = context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.NAMED)
		def firstRemapper = first.get()

		then: "它拿到的是真的能用的 remapper，不是空壳"
		mapType(firstRemapper) == NAMED_CLASS
		// 前提检查：classpath 确实被读进来了，否则下面的活性探针会永远为假
		knowsProbeClass(firstRemapper)

		when: "第一个消费者用完并归还（链上那一环的 try-with-resources）"
		first.close()

		then: "归还**没有**把共享的 remapper 拆掉——这是本用例的核心断言"
		knowsProbeClass(firstRemapper)

		when: "第二个消费者（等价于 InterfaceInjectionProcessor）随后向同一个上下文取用"
		def second = context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.NAMED)
		def secondRemapper = second.get()

		then: "第二个也拿到了可用的 remapper，且与第一个是同一份底层对象"
		secondRemapper.is(firstRemapper)
		mapType(secondRemapper) == NAMED_CLASS
		knowsProbeClass(secondRemapper)

		when: "第二个也归还"
		second.close()

		then: "全部归还之后 remapper 仍然完整——拆解归 TinyRemapperService，不归消费者"
		knowsProbeClass(firstRemapper)

		and: "两个句柄是各自独立的对象，不是一个实例被复用"
		!second.is(first)
	}

	def "并列持有句柄时先关闭的一方不影响另一方随后解析"() {
		given: "两个消费者都先拿到句柄（等价于链上两环都已持有同一个 context），但都尚未真正解析"
		def first = context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.NAMED)
		def second = context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.NAMED)

		expect: "句柄本身是各自独立的对象"
		!first.is(second)

		when: "第一个先解析、用完并关闭"
		def firstRemapper = first.get()
		first.close()

		then: "第一个的关闭没有连坐另一个：它随后仍能解析出活着的、可用的 remapper"
		knowsProbeClass(second.get())
		mapType(second.get()) == NAMED_CLASS

		and: "也没有把它自己的值作废到一个不可用的状态"
		knowsProbeClass(firstRemapper)
	}

	def "remapper 的拆解发生在服务工厂关闭时，而不是消费者归还时"() {
		given: "两个消费者各自取用并归还"
		def first = context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.NAMED)
		def firstRemapper = first.get()
		first.close()
		def second = context.createRemapper(MappingsNamespace.OFFICIAL, MappingsNamespace.NAMED)
		second.get()
		second.close()

		expect: "消费者全部归还之后，remapper 依然活着——生命周期归 TinyRemapperService"
		knowsProbeClass(firstRemapper)

		when: "任务动作结束，作用域服务工厂关闭（ProcessMinecraftJarTask 的 try-with-resources）"
		def service = serviceFactory.get(remapperOptions)
		serviceFactory.close()

		then: "这时才由服务拆掉 remapper；ExecutionContext 自己不再 finish，故不会与它重复拆解"
		service.getTinyRemapperForInputs() == null

		and: "正控：探针确实看得见「拆解」——否则上面几处「仍然可用」全是假的"
		!knowsProbeClass(firstRemapper)
	}

	/** 真的用 remapper 映射一个类名——只有映射生效才算「可用」，仅非 null 不算. */
	private static String mapType(TinyRemapper remapper) {
		return remapper.getEnvironment().getRemapper().map(OFFICIAL_CLASS)
	}

	/**
	 * remapper 是否还记得它 classpath 里的探针类.
	 *
	 * <p>之所以要看这个而不是「映射还对不对」：{@code TinyRemapper.finish()} 不改映射表，
	 * 只清空已读入的类并关掉线程池，所以映射结果无法区分「还活着」与「已被 finish」。
	 */
	private static boolean knowsProbeClass(TinyRemapper remapper) {
		return remapper.getEnvironment().getClass(PROBE_CLASS) != null
	}

	/** 造一个只含 {@link #PROBE_CLASS} 一个类的 jar，作为 remapper 的 classpath. */
	private static Path writeProbeJar(Path dir) {
		Path jar = dir.resolve("probe.jar")
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PROBE_CLASS, null, "java/lang/Object", null)
		writer.visitEnd()

		new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { zip ->
			zip.putNextEntry(new ZipEntry(PROBE_CLASS + ".class"))
			zip.write(writer.toByteArray())
			zip.closeEntry()
		}

		return jar
	}
}
