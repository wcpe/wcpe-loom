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

package net.fabricmc.loom.test.integration.pipeline

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.Checksum

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 端到端证明 remap 服务的「受害侧自愈」真的会被走到、并且真的救回了构建.
 *
 * <p>被证明的分支是 {@code TinyRemapperService.recoverClasspathRead}：它只在
 * 「读取 classpath 期间共享 zipfs 实例被并发持有者关掉」（JDK-8291712 的真实故障形态）时才会执行。
 * 上一轮已经用单测确定性复刻了该形态，但端到端构建里这个竞态极难自然命中（连续多次全量构建 0 命中），
 * 于是这条自愈在生产路径上从来没被走到过——单测证明不了「端到端确实会自愈，而不是换个地方炸」。
 *
 * <p>本用例用 {@code ZipFsCloseRaceTestHook}（系统属性 {@code loom.test.zipfs.closeRaceEntry}）把该形态
 * 变成确定性事件：钩子等读取方（tiny-remapper）自己把受害者 jar 打开、登记簿里出现活实例之后，
 * 再由一个并发的持有者线程把那个实例关掉。断言分四层，逐层排除「其实没触发」：
 * <ul>
 *   <li><b>形态确实发生</b>：钩子日志「{@value #CLOSED_LOG}」出现，说明读取方手里的共享实例真的被关掉了；</li>
 *   <li><b>自愈确实被走到</b>：自愈分支入口的日志「{@value #HEAL_LOG}」出现——这是本轮新加的痕迹，
 *       因为真实形态下逐条重读通常一次就成功，旧代码在这条路径上是静默恢复，没有任何可检索的证据；</li>
 *   <li><b>构建仍然成功</b>：{@code remapJar} 成功。把自愈临时禁用后同一条用例必须变红
 *       （见提交说明里的红/绿两次输出）；</li>
 *   <li><b>不设钩子时零影响</b>：对照组既不出现钩子日志也不出现自愈日志，且两条产物**逐字节一致**——
 *       自愈绕开坏实例这件事没有改变任何产物内容。</li>
 * </ul>
 *
 * <p>受害者 jar 是**扁平**布局（根目录下全是 class，没有子目录），理由见
 * {@link #writeVictimJar}：关闭点落在 {@code ZipFileSystem.isDirectory}（该方法没有 ensureOpen 检查）
 * 那个微秒级窗口里时，失败形态会变成 NPE 而不是 ClosedFileSystemException；本用例要稳定断言的是
 * 「自愈分支入口那条日志」，因此用扁平布局把关闭点推离那个窗口。
 * NPE 落点本身由下面 {@code 深目录} 那条用例覆盖（改后它同样走自愈）。
 */
class ZipFsCloseRaceHealTest extends Specification implements GradleProjectTestTrait {
	/** 受害者 jar 的文件名片段：钩子按它挑受害者，本用例也用它做断言. */
	private static final String VICTIM_MARKER = "zipfs-close-race-victim"
	/** 受害者 jar 所在的目录（夹具工程把 libs/ 下的受害者 jar 当成依赖）. */
	private static final String VICTIM_DIR = "libs"
	/** 受害者 jar 相对夹具工程根目录的路径. */
	private static final String VICTIM_PATH = "${VICTIM_DIR}/${VICTIM_MARKER}.jar"
	/**
	 * 受害者 jar 的条目数.
	 *
	 * <p>这个数字不是随便取的：钩子在检测到读取方打开受害者之后要等几毫秒才关闭（原因见
	 * {@code ZipFsCloseRaceTestHook} 的类注释），所以遍历窗口必须远大于那个延迟，关闭点才稳定落在
	 * 「读取进行中」而不是「读完了」。实测 4 万条目的遍历窗口在 0.4~0.8 秒量级，延迟只有 5 毫秒。
	 */
	private static final int VICTIM_ENTRIES = 40_000
	/** 钩子属性名，须与 {@code ZipFsCloseRaceTestHook} 里的常量一致. */
	private static final String HOOK_PROPERTY = "loom.test.zipfs.closeRaceEntry"
	/** 钩子「检测到受害者被打开之后再等多久才关闭」的属性名，须与 {@code ZipFsCloseRaceTestHook} 里的常量一致. */
	private static final String DELAY_PROPERTY = "loom.test.zipfs.closeRaceDelayMs"
	/** 钩子「已关掉读取方手里的共享实例」的日志片段. */
	private static final String CLOSED_LOG = "测试钩子生效：共享 zipfs 实例"
	/** 自愈分支入口的日志片段（{@code TinyRemapperService.recoverClasspathRead}）. */
	private static final String HEAL_LOG = "开始自愈重读"
	/** 「命中关闭竞态」的日志片段：自愈入口那条与自愈重读中再次命中的那条都含它，形态就记在这两行里. */
	private static final String HEAL_FORM_MARKER = "共享 zipfs 实例已被关闭"
	/**
	 * 「关闭的 zipfs 在目录遍历时抛出的 NPE」在自愈日志里的特征片段.
	 *
	 * <p>取自 JDK 实际消息（{@code Cannot invoke "java.util.LinkedHashMap.get(Object)" because "this.inodes" is null}）：
	 * {@code this.inodes} 为 null 只可能由 {@code ZipFileSystem.close()} 造成，因此日志里出现它就是命中了该形态。
	 */
	private static final String NPE_FORM_MARKER = 'this.inodes'
	/**
	 * 深目录 victim 的目录层数.
	 *
	 * <p>目录越多，遍历时「取属性 → 列目录」的次数越多，关闭点落进那个窗口的机会越大。
	 * 深度不能太小：遍历窗口必须明显长于关闭者的检测延迟（轮询 + 线程启动约 1 ms），否则实例在关闭者
	 * 看见之前就已经读完，竞态根本不发生（实测 1000 层时 20 条 victim 全部错过）。
	 * 也不能太大：zipfs 索引里每个节点都存自己的绝对路径，链式嵌套的内存是 O(深度²)，
	 * 而 victim 会被 tiny-remapper 并行读取（实测 4 条 × 4000 层就把测试 daemon 的堆打爆）。
	 */
	private static final int DEEP_VICTIM_LEVELS = 4_000

	def "读取期间共享 zipfs 实例被并发持有者关闭时，remapJar 靠自愈照常成功"() {
		when: "带钩子跑一次 remapJar：受害者 jar 在读取期间被并发持有者关掉"
		Run hooked = remapJar(victimJarName: VICTIM_MARKER)
		println("[带钩子] build/libs 产物 sha256=" + sha256(hooked.artifact))
		println("[带钩子] outcome=" + hooked.result.task(":remapJar").outcome)

		and: "再跑一次同样的工程，但不设钩子，作为对照"
		Run baseline = remapJar(victimJarName: VICTIM_MARKER, hook: false)
		println("[对照] build/libs 产物 sha256=" + sha256(baseline.artifact))

		then: "① 形态确实发生了：钩子关掉的正是读取方手里那个共享实例"
		hooked.result.output.contains(CLOSED_LOG)

		and: "② 自愈分支确实被走到（而不是竞态没触发、或者换了个地方失败）"
		hooked.result.output.contains(HEAL_LOG)

		and: "③ 构建仍然成功"
		hooked.result.task(":remapJar").outcome == SUCCESS

		and: "④ 不设钩子时两条日志都不出现，构建照常成功——钩子对真实构建零影响"
		baseline.result.task(":remapJar").outcome == SUCCESS
		!baseline.result.output.contains(CLOSED_LOG)
		!baseline.result.output.contains(HEAL_LOG)

		and: "⑤ 产物逐字节一致：自愈绕开坏实例没有改变任何产物内容"
		baseline.artifact.isFile()
		sha256(hooked.artifact) == sha256(baseline.artifact)
	}

	/**
	 * 同一竞态的另一个落点：目录遍历时 {@code ZipFileSystem.isDirectory} 没有 ensureOpen 检查.
	 *
	 * <p>{@code FileTreeWalker} 每进一个目录先取属性（{@code readAttributes} 有 ensureOpen，关闭后报 CFSE），
	 * 紧接着列目录，后者走到 {@code ZipFileSystem.isDirectory}——它只做 {@code beginRead() + getInode()}，
	 * 没有 ensureOpen；实例已关闭时 {@code close()} 已把 {@code inodes} 置空，于是抛
	 * {@code NullPointerException: Cannot invoke "java.util.LinkedHashMap.get(Object)" because "this.inodes" is null}。
	 * 改前判据只认 {@code ClosedFileSystemException}，这类失败会直接炸构建、不走自愈；本用例验证改后同样自愈。
	 *
	 * <p><b>命中与否为什么是随机的</b>：那个窗口是 JDK 里「取完属性」到「列目录」之间的几百纳秒，
	 * 外部没有任何可观测的触发点——关闭线程只能按时间撞，撞上就是 NPE 落点，撞不上就是 CFSE 落点。
	 * 实测单条受害者单次构建命中约一成（提高命中率的办法都试过：加大目录层数、去掉关闭延迟、
	 * 多放几条受害者——后者受测试 daemon 堆限制，4 条 × 4000 层即 OOM）。
	 * 因此本用例写成「命中即断言、未命中即记录」：竞态发生、自愈被走到、构建成功这三条每次都断言
	 * （命中 NPE 落点时，「构建成功」就是「该形态同样被自愈接住」的断言），本次命中的落点用 println 如实打印。
	 * 「该形态确实会发生」「判据确实认可它」由 {@code ZipFsPoisoningTest} 的两个用例兜住
	 * （一个是把该 NPE 真造出来的确定性用例，一个是 150 轮竞态的统计用例），不依赖本用例的运气。
	 */
	def "关闭竞态落到目录遍历（isDirectory 的 inodes NPE）时，remapJar 同样靠自愈成功"() {
		when: "深目录 victim + 关闭延迟 0：让关闭点尽量落在「属性已取到、正要列目录」的窗口里"
		Run hooked = remapJar(victimJarName: VICTIM_MARKER, deepVictim: true, closeDelayMs: 0)
		boolean hitNpeForm = hitsNpeForm(hooked.result)
		println("[深目录] 命中 inodes NPE 形态=${hitNpeForm}，落点日志=" + formLogLines(hooked.result))

		then: "① 竞态确实发生、自愈确实被走到（否则本用例什么也没证明）"
		hooked.result.output.contains(CLOSED_LOG)
		hooked.result.output.contains(HEAL_LOG)

		and: "② 构建照常成功——命中 NPE 落点时，这一条就是「该形态同样被自愈接住」的断言（改前会直接炸构建）"
		hooked.result.task(":remapJar").outcome == SUCCESS
	}

	/** 跑一次 remapJar 的结果：构建输出与产物. */
	private static final class Run {
		BuildResult result
		File artifact
	}

	/**
	 * 在夹具工程里跑一次 {@code remapJar}.
	 *
	 * @param options {@code victimJarName} 受害者 jar 的名字片段；{@code hook} 为 {@code false} 时不设钩子属性；
	 *                {@code deepVictim} 为 {@code true} 时写深目录布局（默认扁平）；
	 *                {@code closeDelayMs} 非空时覆盖钩子的关闭延迟
	 * @return 产物与构建结果
	 */
	private Run remapJar(Map options) {
		final String victimName = (options.victimJarName as String) + ".jar"
		def gradle = gradleProject(project: "zipfsCloseRace", version: DEFAULT_GRADLE)

		if (options.deepVictim) {
			writeDeepVictimJar(new File(gradle.projectDir, VICTIM_PATH))
		} else {
			writeVictimJar(new File(gradle.projectDir, VICTIM_PATH))
		}

		def args = [
			"--console=plain",
			"--no-build-cache"
		]

		if (options.hook != false) {
			args << "-D${HOOK_PROPERTY}=${victimName}"
		}

		if (options.closeDelayMs != null) {
			args << "-D${DELAY_PROPERTY}=${options.closeDelayMs}"
		}

		def result = gradle.run(task: "remapJar", configurationCache: false, args: args)
		return new Run(result: result, artifact: gradle.getOutputFile("zipfs-close-race-1.0.0.jar"))
	}

	/** 取出构建输出里所有「命中关闭竞态」的日志行（自愈入口那条与自愈重读中再次命中的那条，形态都记在上面）. */
	private static List<String> formLogLines(BuildResult result) {
		return result.output.readLines().findAll { it.contains(HEAL_FORM_MARKER) }
	}

	/** 本次构建里有没有出现「目录遍历撞上已置空的 inodes」这个落点（形态只可能由关闭造成，见 {@link #NPE_FORM_MARKER}）. */
	private static boolean hitsNpeForm(BuildResult result) {
		return formLogLines(result).any { it.contains(NPE_FORM_MARKER) }
	}

	private static String sha256(File file) {
		return Checksum.of(file).sha256().hex()
	}

	/**
	 * 写出受害者 jar：**扁平**布局（根目录下全是 class 条目，没有任何子目录）.
	 *
	 * <p>为什么扁平：{@code FileTreeWalker} 每进一个目录都会先取属性（有 ensureOpen 检查，关闭后报 CFSE），
	 * 紧接着调 {@code Files.newDirectoryStream}，而后者最终走到 {@code ZipFileSystem.isDirectory}——
	 * 这个方法**没有** ensureOpen 检查，实例一关就 NPE。目录越多，关闭点撞进那个窗口的机会越多
	 * （实测目录树布局约 1~2 成是 NPE，扁平布局约 0）。本用例要稳定断言的是「自愈分支入口那条日志」，
	 * 用扁平布局把关闭点推离那个窗口、稳定落在 CFSE 上；NPE 落点由深目录那条用例覆盖
	 * （改后它也走自愈，见 {@link #writeDeepVictimJar}）。
	 *
	 * <p>class 内容侧是各自独立的最小合法 class（常量池只有自己的名字），条目名与类名一一对应、
	 * 互不重名，tiny-remapper 会照常把它们登记进 classpath。
	 */
	private static void writeVictimJar(File jar) {
		jar.parentFile.mkdirs()

		new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(jar))).withStream { ZipOutputStream zip ->
			VICTIM_ENTRIES.times { int index ->
				String name = String.format("racevictim%06d", index)
				zip.putNextEntry(new ZipEntry(name + ".class"))
				zip.write(tinyClass(name))
				zip.closeEntry()
			}
		}
	}

	/**
	 * 写出受害者 jar：**深目录**布局（{@value #DEEP_VICTIM_LEVELS} 层深的目录链，末端一个 class）.
	 *
	 * <p>为什么用深目录：关闭竞态落在「取完属性、正要列目录」那个窗口里时，失败是
	 * {@code ZipFileSystem.isDirectory} 抛的 inodes NPE 而不是 CFSE；目录越多，这种机会越多。
	 * 链式嵌套每层只占一个目录条目（比铺同级目录省条目），但遍历时每层都会走一次「取属性 → 列目录」，
	 * 且每层的目录列举都只有一两个子项，遍历时间几乎全花在「属性 → 列目录」这个循环上。
	 * 末端那个 class 保证 tiny-remapper 的遍历真的会读到一个类（与生产形态一致）。
	 */
	private static void writeDeepVictimJar(File jar) {
		jar.parentFile.mkdirs()
		StringBuilder path = new StringBuilder()

		new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(jar))).withStream { ZipOutputStream zip ->
			DEEP_VICTIM_LEVELS.times { int level ->
				path.append("d").append(level).append('/')
				zip.putNextEntry(new ZipEntry(path.toString()))
				zip.closeEntry()
			}

			String leaf = "racevictimleaf"
			zip.putNextEntry(new ZipEntry(path.toString() + leaf + ".class"))
			zip.write(tinyClass(leaf))
			zip.closeEntry()
		}
	}

	/** 最小合法 class 文件：常量池只有自己的类名，无字段、无方法、无接口. */
	private static byte[] tinyClass(String internalName) {
		byte[] name = internalName.getBytes(StandardCharsets.UTF_8)
		def out = new ByteArrayOutputStream()
		u4(out, 0xCAFEBABE)
		u2(out, 0)
		u2(out, 52)
		u2(out, 3)
		out.write(7)
		u2(out, 2)
		out.write(1)
		u2(out, name.length)
		out.write(name)
		u2(out, 0x0021)
		u2(out, 1)
		u2(out, 0)
		u2(out, 0)
		u2(out, 0)
		u2(out, 0)
		u2(out, 0)
		return out.toByteArray()
	}

	private static void u2(ByteArrayOutputStream out, int value) {
		out.write((int) ((value >>> 8) & 0xFF))
		out.write((int) (value & 0xFF))
	}

	/** 写大端 4 字节；参数是 {@code long}：Groovy 里 {@code 0xCAFEBABE} 超出 int 范围，会解析成 Long. */
	private static void u4(ByteArrayOutputStream out, long value) {
		out.write((int) ((value >>> 24) & 0xFF))
		out.write((int) ((value >>> 16) & 0xFF))
		out.write((int) ((value >>> 8) & 0xFF))
		out.write((int) (value & 0xFF))
	}
}
