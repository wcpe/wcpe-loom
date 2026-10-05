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

import java.nio.charset.StandardCharsets
import java.nio.file.ClosedFileSystemException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import org.gradle.api.provider.Provider

import net.fabricmc.loom.configuration.mods.ArtifactMetadata
import net.fabricmc.loom.configuration.mods.ArtifactRef
import net.fabricmc.loom.task.service.TinyRemapperService
import net.fabricmc.loom.test.unit.service.ServiceTestBase
import net.fabricmc.loom.util.FileSystemUtil
import net.fabricmc.loom.util.ZipFsCloseRaceTestHook
import net.fabricmc.loom.util.cache.JarReusability
import net.fabricmc.tinyremapper.TinyRemapper

/**
 * JDK-8291712 的毒化只可能发生在 JDK 进程级 zipfs 登记簿里，因此本 spec 复刻的正是那条链路：
 *
 * <ol>
 *     <li>jar 在盘上时被打开（登记）：登记簿出现 {@code realPath -> fileSystem} 条目；</li>
 *     <li>该 jar 在打开期间被删掉（{@code --refresh-dependencies} 下依赖被「先删后写」就是这形状）；</li>
 *     <li>{@code close()} 需要先 {@code toRealPath()} 才能摘除条目，文件不在盘上就抛 IOException，
 *         条目于是永久残留成「已关闭」的死实例；</li>
 *     <li>文件回来后，任何走 URI 路线打开该路径的代码（tiny-remapper 的
 *         {@code FileSystemReference.openJar} 就先查登记簿）都只会拿到这个死实例。</li>
 * </ol>
 *
 * <p>复现里「重建文件」必须绕开 zipfs 的登记路线（这里用 {@link ZipOutputStream}）：
 * 走登记路线重建会用新实例覆盖掉那条死条目，反而看不到毒化.
 *
 * <p>本 spec 同时固化 remap 服务的<b>失败路径自愈</b>：受害 jar 读不到时改从其同目录私有快照读一次，
 * 使得「Gradle 自己重写依赖 jar」这种 loom 控制不了的场景也不再让构建失败.
 */
class ZipFsPoisoningTest extends ServiceTestBase {
	/** 受害 jar 里的探针类：断言「它真的被读进了 classpath」时用这个名字. */
	private static final String PROBE_CLASS = "net/fabricmc/loom/util/FileSystemUtil"
	/** 关闭 zipfs 后被置空、随后在目录遍历时被解引用的字段名：判据正是按它认形态（与 {@code TinyRemapperService} 里的常量一致）. */
	private static final String INODES_FIELD = "inodes"
	/** JDK 实际抛出的那条消息（JDK 15+ 的「有帮助的 NPE 消息」），用于构造「消息同形但栈不对」的反例. */
	private static final String NPE_FORM_MESSAGE = 'Cannot invoke "java.util.LinkedHashMap.get(Object)" because "this.inodes" is null'
	/** 让 classpath 读取按指定形态失败的测试属性，须与 {@code ZipFsCloseRaceTestHook} 里的常量一致. */
	private static final String READ_FAILURE_PROPERTY = "loom.test.zipfs.readFailure"
	/** {@value #READ_FAILURE_PROPERTY} 取值：只有第一次读取失败，其后照常（模拟「重读拿到健康新实例」）. */
	private static final String READ_FAILURE_ONCE = "fastThrowNpeOnce"
	/** {@value #READ_FAILURE_PROPERTY} 取值：每次读取都失败（模拟「重读仍然以同样方式失败」的真实缺陷）. */
	private static final String READ_FAILURE_ALWAYS = "fastThrowNpeAlways"
	/** 深目录 victim 的目录层数：层数越多，目录列举次数越多，关闭点落进「属性已取到、正要列目录」窗口的机会越大. */
	private static final int DEEP_DIR_LEVELS = 4_000
	/**
	 * 竞态轮数.
	 *
	 * <p>单轮命中「目录列举窗口」的概率实测约 5%~15%（其余是 CFSE，落点本身随机，改不了——那个窗口是
	 * JDK 里两次 zipfs 调用之间的几百纳秒，外部没有触发点）。{@value #RACE_ROUNDS} 轮下来
	 * 「一次都没落进窗口」的概率已可忽略，本用例断言的正是「窗口确实会被撞上」。
	 */
	private static final int RACE_ROUNDS = 150

	def "loom 读取依赖 jar 的只读路径不会被已毒化路径拖死"() {
		given: "一条已被 JDK-8291712 毒化的依赖 jar 路径"
		Path dir = Files.createTempDirectory("zipfs-poison")
		Path jar = poison(dir.resolve("dependency.jar"))

		and: "登记簿里确实是那个「已关闭」的死实例，而独立只读路线仍能读到它"
		assert !FileSystems.getFileSystem(toJarUri(jar)).isOpen()
		FileSystemUtil.getReadOnlyJarFileSystem(jar).withCloseable {
			assert Files.exists(it.getPath("fabric.mod.json"))
		}

		when: "走 loom 读取依赖 jar 的生产路径（remapJar 之前对 classpath 依赖做的事）"
		boolean reusable = JarReusability.isReusable(jar)
		def metadata = ArtifactMetadata.create(new ArtifactRef.FileArtifactRef(jar, "net.fabric", "loom-test", "1.0"), "1.4", ArtifactMetadata.MixinRemapType.MIXIN)

		then: "两条路径都照常工作：它们不经过登记簿，因而与死实例无关"
		reusable
		metadata.isFabricMod()

		cleanup:
		deleteQuietly(dir)
	}

	/**
	 * 本用例是缺陷单的正面复刻：持有者按 tiny-remapper 的共享路线打开 classpath jar，
	 * 文件在持有期间被重写（先删后写），此后该路径在本 JVM 内「一打开就是已关闭实例」。
	 *
	 * <p>改前：构造 remap 服务时抛
	 * {@code Failed to create service instance of TinyRemapperService: CompletionException: ClosedFileSystemException}。
	 * 改后：服务从私有快照把受害 jar 读完，受害 jar 里的类真的进了 classpath。
	 */
	def "共享实例被并发持有者关闭后，读取方从私有快照自愈而不是抛 ClosedFileSystemException"() {
		given: "一条被共享路线打开、期间又被「先删后写」的 classpath jar"
		Path dir = Files.createTempDirectory("zipfs-heal")
		Path jar = poison(dir.resolve("classpath.jar"))

		and: "它此刻确实打不开（登记簿里是死实例），而文件本身是完整的"
		assert !FileSystems.getFileSystem(toJarUri(jar)).isOpen()

		when: "构造 remap 服务（remapJar 读取 classpath 的入口）"
		def options = classpathOptions(jar)
		TinyRemapperService service = factory.get(options)

		then: "不再抛 ClosedFileSystemException"
		service != null

		and: "受害 jar 的类经由私有快照真的进了 classpath（改前这里根本走不到）"
		readClassNames(service).contains(PROBE_CLASS)

		and: "自愈不改动全局状态：登记簿里的死实例仍然是死的，只是被绕开了"
		!FileSystems.getFileSystem(toJarUri(jar)).isOpen()

		when: "服务结束"
		service.close()

		then: "私有快照已清理，不留垃圾"
		snapshotFiles(dir).isEmpty()

		cleanup:
		deleteQuietly(dir)
	}

	/**
	 * 与上一个用例同形，但用真线程把「并发持有者关闭共享实例」这件事演出来（用 latch 固定顺序，
	 * 不依赖调度）：持有者线程先拿住共享实例，主线程删掉文件后放行持有者关闭，
	 * 关闭于是失败并留下死条目，主线程再重建文件.
	 */
	def "并发持有者关闭共享实例（文件同时被重写）后，读取方仍能读完 classpath"() {
		given:
		Path dir = Files.createTempDirectory("zipfs-race")
		Path jar = writeJar(dir.resolve("classpath.jar"), probeEntries())

		and: "一个持有共享实例的线程"
		FileSystemUtil.Delegate holder = FileSystemUtil.getJarFileSystem(jar, false)
		def mayClose = new CountDownLatch(1)
		def closed = new CountDownLatch(1)
		Thread closer = new Thread({
			mayClose.await()
			try {
				holder.close()
			} catch (Exception expected) {
				// JDK-8291712：文件此刻不在盘上，close() 摘不掉登记簿条目，条目残留下来
			}
			closed.countDown()
		}, "zipfs-poison-closer")
		closer.start()

		when: "持有期间文件被重写（先删后写），持有者随后关闭"
		Files.delete(jar)
		mayClose.countDown()
		assert closed.await(30, TimeUnit.SECONDS)
		writeJar(jar, probeEntries())

		and: "此时构造 remap 服务"
		def options = classpathOptions(jar)
		TinyRemapperService service = factory.get(options)

		then: "classpath 仍然被完整读完"
		readClassNames(service).contains(PROBE_CLASS)

		cleanup:
		try {
			holder.close()
		} catch (Exception ignored) {
			// 实例已被并发关闭，重复关闭不需要处理
		}
		mayClose.countDown()
		closer.join(30_000)
		deleteQuietly(dir)
	}

	/**
	 * 读取期间共享实例被别的持有者关掉（跨 loom 类加载器副本时引用计数是分开的，
	 * 那一侧以为自己是创建者，关闭不看本侧的引用），读取方必须自愈而不是失败。
	 *
	 * <p>读取窗口靠「jar 里条目足够多」拉宽：逐个类的读取发生在 tiny-remapper 的线程池上，
	 * 主线程还在走后续条目时关闭者即可插入.
	 */
	def "读取进行中共享实例被关闭时，remap 服务多轮都不失败"() {
		given:
		Path dir = Files.createTempDirectory("zipfs-inflight")
		Map<String, byte[]> entries = loomClassEntries(400)
		assert entries.size() > 100

		expect: "每一轮都在读取进行中关闭共享实例，服务都必须成功构造"
		(1..5).every { round ->
			Path jar = writeJar(dir.resolve("inflight-${round}.jar"), entries)
			FileSystemUtil.Delegate holder = FileSystemUtil.getJarFileSystem(jar, false)
			def stop = new CountDownLatch(1)
			Thread closer = new Thread({
				stop.await()
				// 等读取方先拿到共享实例，再把登记簿里的那个实例关掉
				Thread.sleep(25)
				FileSystems.getFileSystem(toJarUri(jar)).close()
			}, "zipfs-inflight-closer-${round}")
			closer.start()
			TinyRemapperService service = null

			try {
				stop.countDown()
				service = factory.get(classpathOptions(jar))
				return service != null
			} finally {
				service?.close()
				stop.countDown()
				closer.join(30_000)
				try {
					holder.close()
				} catch (Exception ignored) {
					// 同上：重复关闭不需要处理
				}
				Files.deleteIfExists(jar)
			}
		}

		cleanup:
		deleteQuietly(dir)
	}

	/**
	 * 判据收窄的确定性用例：把「已关闭的 zipfs」在目录遍历时抛出的那个 NPE 真造出来，断言它被判据认可；
	 * 同时断言其它形态一律不认——「只认这一种 NPE」的收窄意义全在这里。
	 *
	 * <p>造法不依赖竞态：{@code ZipFileSystem.isDirectory} 没有 ensureOpen 检查，所以只要把一个 zipfs 关掉，
	 * 再对它调 {@code Files.newDirectoryStream}（{@code FileTreeWalker} 每进一个目录都会这么调），
	 * 得到的就与真实竞态里一模一样：
	 * {@code NullPointerException: Cannot invoke "java.util.LinkedHashMap.get(Object)" because "this.inodes" is null}。
	 * 同一个调用在实例未关闭时是成功的（本用例先验证这一点），因此这个 NPE 与「关闭」的因果关系是被证明的，
	 * 而不是被假设的。
	 */
	def "关闭的 zipfs 在目录遍历抛出的 inodes NPE 被判据认可，其它 NPE 一律不认"() {
		given: "一个真的被关闭的 zipfs"
		Path dir = Files.createTempDirectory("zipfs-npe-form")
		Path jar = writeJar(dir.resolve("victim.jar"), ["sub/a.class": probeClassBytes()])
		FileSystemUtil.Delegate holder = FileSystemUtil.getJarFileSystem(jar, false)
		def root = holder.fileSystem.getPath("/")

		and: "实例没关闭时，同一个调用是成功的（先排除「这个 NPE 本来就会抛」）"
		Files.newDirectoryStream(root).withCloseable { it.iterator().hasNext() }

		when: "关闭之后，目录遍历立刻变成那个 NPE"
		holder.close()
		NullPointerException realNpe = directoryStreamFailure(root)
		println("[判据] 关闭后的真实形态=" + realNpe)
		println("[判据] 栈顶=" + realNpe.stackTrace[0])

		then: "真实形态被判据认可（改前只认 ClosedFileSystemException，这里会失败）"
		acceptsClosedFileSystemFailure(realNpe)

		and: "包装过的形态也认可：tiny-remapper 的 read(...).join() 会把底层异常套在 CompletionException 里"
		acceptsClosedFileSystemFailure(new java.util.concurrent.CompletionException(new RuntimeException(realNpe)))

		and: "CFSE 照旧认可（回归护栏：改前的行为不能被改掉）"
		acceptsClosedFileSystemFailure(new java.nio.file.ClosedFileSystemException())

		and: "任意 NPE 不被形态判据认可——判据收窄的全部意义；但它不再因此被直接抛出，而是改走行为判别入口"
		!acceptsClosedFileSystemFailure(new NullPointerException("boom"))
		!acceptsClosedFileSystemFailure(new NullPointerException())
		entersBehavioralHeal(new NullPointerException("boom"))

		and: "消息里恰好有 inodes、但栈里没有 zipfs 帧的 NPE 也不认（不靠一句消息就认定关闭竞态）"
		!acceptsClosedFileSystemFailure(npeMentioningInodesWithoutZipFsFrame())

		and: "其它异常类型一概不认"
		!acceptsClosedFileSystemFailure(new IllegalStateException("boom"))
		!acceptsClosedFileSystemFailure(new IOException("boom"))
		!acceptsClosedFileSystemFailure(null)

		cleanup:
		try {
			holder.close()
		} catch (Exception ignored) {
			// 已关闭，重复关闭不需要处理
		}
		deleteQuietly(dir)
	}

	/**
	 * 本轮补的缺口（判据层）：形态被 JIT 抹掉之后，特征判据<b>必然</b>认不出来.
	 *
	 * <p>HotSpot 在同一个隐式空指针被反复抛出后会改用预分配的「fast throw」异常：消息为 {@code null}、
	 * 栈长为 0，于是 {@link #INODES_FIELD} 消息与 zipfs 栈帧两条特征同时消失。真实实例已实测
	 * （同一个隐式空指针循环到第 5193 次即切换；形态为 {@code message=null}、{@code stackLen=0}、
	 * {@code class=java.lang.NullPointerException}、同一个实例反复抛出），但切换时机由 JIT 决定，
	 * 单测里无法稳定复现，因此这里直接构造同形实例作为<b>等价模拟</b>：可观测属性逐条一致.
	 *
	 * <p>改前：这类异常既不被特征判据认可、也没有别的入口，于是「认不出 ⇒ 不走自愈」（构建仍失败）。
	 * 改后：形态不可辨不再等于「与 zipfs 无关」，一律交给行为判别
	 * （{@code recoverClasspathRead} 逐条重读；治好 ⇒ 确是共享实例被并发关闭，治不好 ⇒ 原样抛出原始失败）。
	 */
	def "形态被 JIT 抹掉的 NPE（消息 null、栈空）由行为判别收下，而不是靠形态猜"() {
		given: "一个与 HotSpot fast throw 同形的 NPE：消息 null、栈长 0、同一个实例"
		NullPointerException fastThrow = ZipFsCloseRaceTestHook.fastThrowNullPointer()

		expect: "它带着 fast throw 的每条可观测属性（等价模拟的前提）"
		fastThrow.class == NullPointerException
		fastThrow.message == null
		fastThrow.stackTrace.length == 0
		fastThrow.cause == null

		and: "特征判据认不出它——缺口就在这里"
		!acceptsClosedFileSystemFailure(fastThrow)

		and: "行为判别入口必须收下它，否则仍是「认不出就不自愈」"
		entersBehavioralHeal(fastThrow)

		and: "包装过的形态同样收下：tiny-remapper 的 read(...).join() 会把底层异常套在 CompletionException 里"
		entersBehavioralHeal(new java.util.concurrent.CompletionException(fastThrow))
	}

	/**
	 * 缺口复现（行为层，改前红 / 改后绿）：第一次读取以「形态被抹掉的 NPE」失败，重读能治好.
	 *
	 * <p>这是真实形态的另一半：共享实例被并发持有者关闭后，登记簿里的条目随关闭一起消失，
	 * 重读会打开一个<b>健康的新实例</b>因而成功。因此「重读能治好」本身就是「刚才那次失败是关闭竞态」的判据.
	 *
	 * <p>注入方式见 {@code ZipFsCloseRaceTestHook#throwIfReadFailureArmed()}：让<b>第一次</b> classpath 读取
	 * 抛出与 fast throw 同形的 NPE，其余读取照常。改前：形态判据认不出它，直接抛出（下面的断言变红）；
	 * 改后：行为判别重读一次即治好，服务正常构造、受害 jar 的类真的进了 classpath.
	 */
	def "形态被抹掉的 NPE 触发读取失败时，重读治好它而不是直接抛出"() {
		given: "一条健康 classpath jar"
		Path dir = Files.createTempDirectory("zipfs-fastthrow-heal")
		Path jar = writeJar(dir.resolve("classpath.jar"), probeEntries())

		and: "只让第一次读取以 fast-throw 形态的 NPE 失败，其后读取照常"
		System.setProperty(READ_FAILURE_PROPERTY, READ_FAILURE_ONCE)

		when: "构造 remap 服务（remapJar 读取 classpath 的入口）"
		TinyRemapperService service = factory.get(classpathOptions(jar))

		then: "改前这里会抛出那个 NPE；改后重读治好，服务正常构造"
		service != null

		and: "classpath 也真的读完了（不是「吞掉失败」换来的假成功）"
		readClassNames(service).contains(PROBE_CLASS)

		cleanup:
		// 注入是进程级系统属性，必须清掉，否则会污染同一 daemon 里的其它用例
		System.clearProperty(READ_FAILURE_PROPERTY)
		service?.close()
		deleteQuietly(dir)
	}

	/**
	 * 行为判别的硬约束（负对照）：重读<b>仍然以同样方式失败</b>时，必须原样抛出原始失败.
	 *
	 * <p>「重读仍失败」说明它不是共享实例被并发关闭（那种情况下重读会成功），而是真实缺陷；
	 * 此时任何「无条件重试」或「无条件吞掉」都会把真实缺陷掩盖成一次成功的构建。本用例把这一点钉死：
	 * 抛出的链条里那个 NPE <b>就是</b>被注入的同一个实例（对象标识相同），消息为 null、栈长为 0——
	 * 即与改造前逐字一致，既没被替换成别的异常，也没被追上新的栈.
	 *
	 * <p>把「重读失败 ⇒ 放弃自愈」这条约束去掉（改成无条件当作治好），本用例立刻变红
	 * （本轮已实测：整份 spec 只有这一条变红，其余 10 条仍绿）。
	 */
	def "重读仍然以同样方式失败时，原样抛出原始 NPE（不重试、不吞掉）"() {
		given: "一条健康 classpath jar"
		Path dir = Files.createTempDirectory("zipfs-fastthrow-rethrow")
		Path jar = writeJar(dir.resolve("classpath.jar"), probeEntries())

		and: "每次读取都以同一个 fast-throw 形态 NPE 失败——复刻「重读仍然以同样方式失败」"
		NullPointerException injected = ZipFsCloseRaceTestHook.fastThrowNullPointer()
		System.setProperty(READ_FAILURE_PROPERTY, READ_FAILURE_ALWAYS)

		when: "构造 remap 服务"
		factory.get(classpathOptions(jar))

		then: "抛出的原始失败与注入的那个实例是<b>同一个对象</b>：类型、消息、栈逐字一致"
		RuntimeException failure = thrown(RuntimeException)
		failure.cause.is(injected)
		injected.message == null
		injected.stackTrace.length == 0

		and: "外层包装也如实转述原始失败（没有替换成自愈或诊断异常）"
		failure.message.endsWith(NullPointerException.name)

		cleanup:
		System.clearProperty(READ_FAILURE_PROPERTY)
		deleteQuietly(dir)
	}

	/**
	 * 该形态在真实竞态下确实会发生（不是只有顺序关闭才造得出来），而且判据认可它.
	 *
	 * <p>每轮复刻的都是生产链路：按 URI 路线打开 victim jar（与 tiny-remapper 的
	 * {@code FileSystemReference.openJar} 同形，因而进进程级登记簿），并发持有者把登记簿里那个活实例关掉，
	 * 读取方用 {@code Files.walkFileTree} 遍历（与 {@code FileTreeWalker} 同一条路线）。
	 * 关闭点落在哪个调用点上是随机的：有 ensureOpen 的调用点报 CFSE，目录列举
	 * （{@code ZipFileSystem.isDirectory}）报 inodes NPE。实测深目录 victim 上约 8 成 CFSE、1~2 成 inodes NPE，
	 * 因此这里跑多轮并要求至少命中一次 inodes NPE；每轮的形态计数都会打印出来，
	 * 避免「其实一次都没命中」的假绿。
	 *
	 * <p>第三种子形态是长命 JVM 里的「fast throw」：同一条隐式空指针被反复抛出后，HotSpot 改用预分配的
	 * NPE（消息为 null、栈长为 0）。它与带消息的 inodes NPE 是<b>同一个落点</b>（都是 {@code getInode} 里
	 * 解引用已置空的 {@code inodes}），只是异常对象被廉价化了：特征判据必然认不出它（{@link #INODES_FIELD}
	 * 与 zipfs 栈帧都取不到），但它必须被<b>行为判别入口</b>收下，否则还是「认不出 ⇒ 不走自愈」。
	 * 本用例按「落点是否被撞上」统计（{@code NPE-inodes} 与 {@code NPE-bare} 都算撞上）并要求至少撞上一次，
	 * 同时要求被抹掉形态的那些轮次确实落在「形态判据认不出、行为判别入口收下」这一格上。
	 */
	def "深目录 jar 上关闭竞态真的会落到 isDirectory 分支，且该形态被判据认可"() {
		given: "一个深目录 victim jar（与端到端用例同形）"
		Path dir = Files.createTempDirectory("zipfs-npe-race")
		Path jar = writeDeepDirJar(dir.resolve("deep-victim.jar"))

		when: "跑 {@value #RACE_ROUNDS} 轮关闭竞态，每轮记录失败形态与两个判据的裁决"
		def rounds = (1..RACE_ROUNDS).collect { raceRound(jar) }
		def forms = rounds.countBy { it.form }
		int windowHits = (forms["NPE-inodes"] ?: 0) + (forms["NPE-bare"] ?: 0)
		def erasedRounds = rounds.findAll { it.form == "NPE-bare" && it.detail.contains("空栈") }
		println("[竞态形态统计] 共 ${RACE_ROUNDS} 轮：" + forms)
		println("[竞态形态统计] 落进目录列举窗口（inodes NPE，含 fast-throw 子形态）=" + windowHits +
				"，自愈入口收下的轮次=" + rounds.count { it.accepted })
		println("[竞态形态统计] 形态被抹掉（消息 null、栈空）的轮次=" + erasedRounds.size() +
				"，其中形态判据认得出=" + erasedRounds.count { it.diagnosed })

		then: "关闭竞态确实会落到「属性已取到、正要列目录」那个窗口里（否则本用例什么也没证明）"
		windowHits > 0

		and: "CFSE 与带消息的 inodes NPE 两种真实形态都必须被行为判别入口收下（收窄不得把它们丢掉）"
		rounds.findAll { it.form in ["CFSE", "NPE-inodes"] }.every { it.accepted }

		and: "命中 inodes NPE 的轮次确实带着「关闭」的证据：消息指名 inodes、栈里有 zipfs 帧"
		def inodesRounds = rounds.findAll { it.form == "NPE-inodes" }
		inodesRounds.every { it.detail.contains(INODES_FIELD) && it.detail.contains("ZipFileSystem") }

		and: "被 JIT 抹掉形态的轮次（真实 fast throw：消息 null、栈空）：形态判据必然认不出，自愈入口必须收下"
		erasedRounds.every { !it.diagnosed && it.accepted }

		cleanup:
		deleteQuietly(dir)
	}

	def "毒化只影响被毒化的那条路径，健康 classpath 照常可用"() {
		given: "一条健康 jar 和另一条被毒化的 jar"
		Path dir = Files.createTempDirectory("zipfs-poison")
		Path healthy = writeJar(dir.resolve("healthy.jar"), probeEntries())
		Path poisoned = poison(dir.resolve("poisoned.jar"))

		when: "只用健康 jar 当 classpath"
		def options = classpathOptions(healthy)
		TinyRemapperService service = factory.get(options)

		then:
		readClassNames(service).contains(PROBE_CLASS)

		cleanup:
		service?.close()
		deleteQuietly(dir)
	}

	/**
	 * 自愈不是万能的：受害 jar 的内容若也读不了（这里用非 zip 内容模拟「正被重写、只写了一半」），
	 * 服务仍要给出带路径与成因的诊断，而不是一个裸 ClosedFileSystemException.
	 */
	def "自愈不可用时仍给出带路径与成因的诊断，而不是裸 ClosedFileSystemException"() {
		given: "一条已被毒化、且内容本身也不再是合法 zip 的 classpath jar"
		Path dir = Files.createTempDirectory("zipfs-poison")
		Path jar = poison(dir.resolve("classpath.jar"))
		Files.write(jar, "not a zip at all".getBytes(StandardCharsets.UTF_8))

		when: "构造 remap 服务"
		def options = classpathOptions(jar)
		TinyRemapperService service = factory.get(options)

		then: "诊断明确指出是哪条路径被毒化、以及自愈已尝试过"
		def e = thrown(RuntimeException)
		e.message.contains("JDK-8291712")
		e.message.contains(jar.toString())
		e.message.contains("自愈")

		cleanup:
		service?.close()
		deleteQuietly(dir)
	}

	/**
	 * 一轮关闭竞态：按 URI 路线打开 victim jar → 并发持有者关闭那个共享实例 → 用 {@code Files.walkFileTree} 遍历.
	 *
	 * @param jar victim jar（每轮重新打开；上一轮的成功关闭会把登记簿条目摘掉，所以下一轮拿到的仍是新实例）
	 * @return {@code [form: 形态名, accepted: 判据是否认可, detail: 异常文本 + 栈顶]}
	 */
	private static Map<String, Object> raceRound(Path jar) {
		URI uri = toJarUri(jar)
		Thread closer = new Thread({
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)

			while (System.nanoTime() < deadline) {
				def shared = FileSystemUtil.findOpenRegistryEntry(jar)

				if (shared == null) {
					// 读取方还没打开这个条目（登记簿里还没有它的活实例）
					Thread.sleep(0, 50_000)
					continue
				}

				shared.close()
				return
			}
		}, "zipfs-npe-form-closer")
		closer.daemon = true
		closer.start()

		try {
			def fileSystem = FileSystems.newFileSystem(uri, [:])
			Files.walkFileTree(fileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
						@Override
						FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
							if (file.toString().endsWith(".class")) {
								Files.readAllBytes(file)
							}

							return FileVisitResult.CONTINUE
						}
					})
			return [form: "OK", accepted: false, detail: "本轮遍历没有失败"]
		} catch (Throwable failure) {
			return classifyRaceFailure(failure)
		} finally {
			closer.join(30_000)
		}
	}

	/** 把一轮竞态的失败归类：CFSE / inodes NPE / 无消息（多为 fast-throw）的 NPE / 其它，并顺带问两个判据. */
	private static Map<String, Object> classifyRaceFailure(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof ClosedFileSystemException) {
				return result("CFSE", t)
			}

			if (t instanceof NullPointerException) {
				return result(t.message?.contains(INODES_FIELD) ? "NPE-inodes" : "NPE-bare", t)
			}
		}

		return result("OTHER:" + failure.class.simpleName, failure)
	}

	/**
	 * 一轮竞态的分类结果.
	 *
	 * <p>{@code accepted} 问的是<b>自愈入口</b>（形态判据或行为判别入口，任一成立即可）；
	 * {@code diagnosed} 问的是<b>形态判据</b>（{@code isClosedFileSystemFailure}）：它成立才会在自愈失败时
	 * 给出带路径与成因的诊断。两者不是同一条判据，被 JIT 抹掉形态的轮次正是「入口收下、形态判据认不出」那一格.
	 */
	private static Map<String, Object> result(String form, Throwable t) {
		String top = t.stackTrace.length > 0 ? t.stackTrace[0].toString() : "<空栈>"
		return [form: form,
			accepted: entersHeal(t),
			diagnosed: acceptsClosedFileSystemFailure(t),
			detail: "${t} | ${top}"]
	}

	/**
	 * 问一次 remap 服务的<b>自愈入口</b>：形态判据成立（CFSE / 带特征的 inodes NPE）或行为判别入口成立
	 * （失败链里有 NPE）都会去试自愈.
	 *
	 * <p>这里把两条判据按生产代码里的顺序合成（{@code isClosedFileSystemFailure} 优先，其后是
	 * {@code containsNullPointer}），因为用例要断言的是「这次失败会不会走自愈」这个整体结论.
	 */
	private static boolean entersHeal(Throwable failure) {
		return acceptsClosedFileSystemFailure(failure) || entersBehavioralHeal(failure)
	}

	/**
	 * 问一次 remap 服务的<b>形态判据</b>（{@code TinyRemapperService.isClosedFileSystemFailure}）.
	 *
	 * <p>判据是私有的：本用例要断言的正是这条判据本身（收窄到哪一步），因此照本 spec 既有做法用反射直接问它，
	 * 而不是为了测试把它放开成公开 API.
	 */
	private static boolean acceptsClosedFileSystemFailure(Throwable failure) {
		def method = TinyRemapperService.getDeclaredMethod("isClosedFileSystemFailure", Throwable)
		method.setAccessible(true)
		return (boolean) method.invoke(null, [failure] as Object[])
	}

	/**
	 * 问一次 remap 服务的<b>行为判别入口</b>（{@code TinyRemapperService.containsNullPointer}）.
	 *
	 * <p>与 {@link #acceptsClosedFileSystemFailure} 同一条理由用反射：这里要钉住的正是这条入口判据，
	 * 它决定了「形态不可辨认的 NPE」到底会不会去试自愈（本轮缺口的判据侧）.
	 */
	private static boolean entersBehavioralHeal(Throwable failure) {
		def method = TinyRemapperService.getDeclaredMethod("containsNullPointer", Throwable)
		method.setAccessible(true)
		return (boolean) method.invoke(null, [failure] as Object[])
	}

	/** 把 zipfs 关闭后 {@code Files.newDirectoryStream} 抛出的那个 NPE 取出来（形态见 {@code ZipFsPoisoningTest} 的用例说明）. */
	private static NullPointerException directoryStreamFailure(Path dir) {
		try {
			Files.newDirectoryStream(dir).withCloseable { it.iterator().hasNext() }
			throw new AssertionError("预期关闭后的 zipfs 列目录会抛 NPE，实际没有抛")
		} catch (NullPointerException e) {
			return e
		}
	}

	/** 消息与 JDK 实际输出同形、但栈里没有 zipfs 帧的 NPE：判据必须不认它. */
	private static NullPointerException npeMentioningInodesWithoutZipFsFrame() {
		try {
			throw new NullPointerException(NPE_FORM_MESSAGE)
		} catch (NullPointerException e) {
			return e
		}
	}

	/**
	 * 写一个「深目录」victim jar：{@value #DEEP_DIR_LEVELS} 层深的目录链，末端一个 class.
	 *
	 * <p>为什么用深目录：{@code FileTreeWalker} 每进一个目录都先取属性（有 ensureOpen），紧接着列目录
	 * （{@code ZipFileSystem.isDirectory}，没有 ensureOpen）；目录越多，「关闭恰好落在两次调用之间」
	 * 的机会越多，命中 inodes NPE 落点的比例就越高（扁平 jar 实测 0/60）。
	 */
	private static Path writeDeepDirJar(Path jar) {
		ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))

		try {
			StringBuilder path = new StringBuilder()

			DEEP_DIR_LEVELS.times { int level ->
				path.append("d").append(level).append('/')
				zos.putNextEntry(new ZipEntry(path.toString()))
				zos.closeEntry()
			}

			String leaf = "racevictimleaf"
			zos.putNextEntry(new ZipEntry(path.toString() + leaf + ".class"))
			zos.write(probeClassBytes())
			zos.closeEntry()
		} finally {
			zos.close()
		}

		return jar
	}

	/** remap 服务的 classpath 选项：把 jar 当成 remapJar 的一条 classpath 条目. */
	private Provider<TinyRemapperService.Options> classpathOptions(Path jar) {
		return TinyRemapperService.TYPE.create(project) {
			it.from.set("named")
			it.to.set("intermediary")
			it.classpath.from(jar.toFile())
			it.uselegacyMixinAP.set(false)
			it.knownIndyBsms.set([])
		}
	}

	/** 读出 tiny-remapper 实际登记过的类名，用来断言 classpath 真被读了. */
	private static Set<String> readClassNames(TinyRemapperService service) {
		TinyRemapper remapper = service.getTinyRemapperForInputs()
		def field = TinyRemapper.getDeclaredField("readClasses")
		field.setAccessible(true)
		return new HashSet<String>(((Map<String, ?>) field.get(remapper)).keySet())
	}

	/** 列出目录里残留的自愈快照（AtomicFiles.tempSibling 的命名：{@code <stem>.<uuid>.tmp.jar}）. */
	private static List<Path> snapshotFiles(Path dir) {
		Files.list(dir).withCloseable { stream ->
			stream.filter { it.getFileName().toString().contains(".tmp.") }.toList()
		}
	}

	/**
	 * 在给定路径上造出一条已被 JDK-8291712 毒化的 jar.
	 *
	 * <p>步骤：写一个 jar → 走登记路线打开它 → 在打开期间删除文件 → close（此时条目已残留在登记簿里）
	 * → 用 {@link ZipOutputStream} 重建同路径文件（不走 zipfs，否则登记路线会把死条目覆盖掉）.
	 *
	 * @return 该路径，其登记簿条目已是一个「已关闭」的死实例
	 */
	private static Path poison(Path jar) {
		writeJar(jar, probeEntries())
		FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar, false)
		Files.delete(jar)

		try {
			fs.close()
			throw new AssertionError("预期 close() 因文件缺失而抛 IOException（JDK-8291712 的成因），实际没有抛")
		} catch (IOException expected) {
			// 期望：ZipFileSystem.close() 的去除步骤先 toRealPath() 失败
		}

		writeJar(jar, probeEntries())
		return jar
	}

	/** 受害 jar 的内容：一个 fabric.mod.json（给 ArtifactMetadata 用）加一个真实 class（给 readClassNames 用）. */
	private static Map<String, byte[]> probeEntries() {
		return [
			"fabric.mod.json": "{}".getBytes(StandardCharsets.UTF_8),
			(PROBE_CLASS + ".class"): probeClassBytes()
		]
	}

	/** 取一份真实 class 字节：tiny-remapper 的 analyze 只吃合法 class 文件. */
	private static byte[] probeClassBytes() {
		return FileSystemUtil.classLoader.getResourceAsStream("${PROBE_CLASS}.class").withCloseable { it.readAllBytes() }
	}

	/** 从 loom 自己的构建输出里取一批互不重名的 class，用来把读取窗口拉宽. */
	private static Map<String, byte[]> loomClassEntries(int max) {
		def location = new File(FileSystemUtil.protectionDomain.codeSource.location.toURI())
		Map<String, byte[]> entries = [:]

		if (location.isDirectory()) {
			Path root = location.toPath()
			Files.walk(root).withCloseable { stream ->
				stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
				.sorted()
				.limit(max)
				.forEach { entries.put(root.relativize(it).toString().replace('\\', '/'), Files.readAllBytes(it)) }
			}
		} else {
			new ZipFile(location).withCloseable { zip ->
				zip.entries().findAll { !it.isDirectory() && it.name.endsWith(".class") }
				.sort { it.name }
				.take(max)
				.forEach { entries.put(it.name, zip.getInputStream(it).withCloseable { input -> input.readAllBytes() }) }
			}
		}

		return entries
	}

	/** 写一个 zip（不用 zipfs：受害路径的登记簿条目必须保持「死」）. */
	private static Path writeJar(Path jar, Map<String, byte[]> entries) {
		ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))

		try {
			entries.each { name, bytes ->
				zos.putNextEntry(new ZipEntry(name))
				zos.write(bytes)
				zos.closeEntry()
			}
		} finally {
			zos.close()
		}

		return jar
	}

	private static void deleteQuietly(Path root) {
		if (root == null || !Files.exists(root)) {
			return
		}

		Files.walk(root).withCloseable { stream ->
			stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
		}
	}

	/** 与 {@code FileSystemUtil.toJarUri}/{@code FileSystemReference.toJarUri} 同形：{@code jar:file:/abs/path}. */
	private static URI toJarUri(Path path) {
		def uri = path.toUri()
		return new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment())
	}
}
