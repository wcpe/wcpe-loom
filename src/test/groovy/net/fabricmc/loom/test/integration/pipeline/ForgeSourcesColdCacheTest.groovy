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

import java.util.zip.ZipFile

import dev.architectury.loom.forge.ForgeSourcesService
import org.apache.commons.io.FileUtils
import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestConstants
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * 配置期 Forge 源码注入在**冷缓存**下（named MC jar 不存在）的行为.
 *
 * <p>{@code ForgeSourcesService.addForgeSourcesDuringProjectConfiguration} 在配置期按「named MC jar
 * 里有没有同名 class」过滤 Forge 源码。改造后 named MC jar 由 {@code RemapMinecraftTask} 在**执行期**落位，
 * 因此冷缓存下这条路径拿不到自己的输入。本用例固定改造后的契约：
 * <ol>
 *   <li>冷缓存 → 打 {@link ForgeSourcesService#SKIPPED_LOG_MARKER} 并整批返回，
 *       **既不写源码包、也不去动（更不会凭空造出）那个缺失的输入 jar**；</li>
 *   <li>跑产出任务让 named MC jar 就位；</li>
 *   <li>再跑一次 → 同一条路径照常注入 Forge 源码，且过滤确实对着刚产出的那份 jar 做
 *       （每个 {@code .java} 条目都在该 jar 里有同名 {@code .class}）。</li>
 * </ol>
 *
 * <h4>为什么不能直接用默认的测试 gradle home</h4>
 * {@code ForgeTest} 等用例跑过的 home 里，named MC jar 是**热的**，第 1 条根本不会被触发。
 * 而「把共享缓存里的那个 jar 删掉」会破坏同一台机器上并发构建的其它用例。
 * 本用例因此用独立的临时 {@code --gradle-user-home}：它的 {@code caches/fabric-loom} 由默认 home
 * **按需播种**——只复制 Forge 工具链与依赖缓存，**刻意不复制 named MC jar**。
 * 这样对被测产物恰好是冷缓存，而对重建代价高的 Forge 工具链是热的；
 * 种子若不存在（例如从未跑过 Forge 车道），复制自然为空，退化成一次全冷的 Forge 构建——慢，但结论不变。
 *
 * <h4>播种为什么要复制而不是硬链接</h4>
 * 被测构建会往这个 home 里**写**产物（named jar、源码包、maven 元数据）。硬链接会让这些写入穿透到
 * 共享 home，静默污染并发运行的其它用例。代价是每次跑用例多几分钟的复制时间。
 *
 * <h4>冷缓存是真的冷，不是「删出来的」</h4>
 * 临时 home 里被删掉的只有「被测构建自己会重新产出的那几个文件」（named jar 及其
 * {@code .backup} / {@code -sources.jar}），删在有归属的副本上，共享 home 一个字节都不动。
 */
class ForgeSourcesColdCacheTest extends Specification implements GradleProjectTestTrait {
	private static final String MC_VERSION = "1.20.1"
	private static final String FORGE_VERSION = "47.2.1"
	/** 被测产物：single-jar Forge 场景下 {@code getMinecraftJars(NAMED)} 指向的那份构件. */
	private static final String NAMED_ARTIFACT = "forge-${MC_VERSION}-${FORGE_VERSION}-minecraft-merged"
	private static final String JAR_MARKER = "NAMED_MC_JAR="

	/**
	 * 播种好的隔离 gradle home.
	 *
	 * <p>{@code @Shared}：播种要复制近 GB 的缓存，同一个测试类的多条用例共用一份即可。
	 * 用例本身会把这唯一一份用成「先冷后热」，因此这里只应有一条用例。
	 */
	@Lazy
	@Shared
	private static File coldHome = resolveColdHome()

	def "冷缓存下配置期 Forge 源码注入整批跳过，named jar 就位后同一次调用能正确注入"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", gradleHomeDir: coldHome)
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace("@MCVERSION@", MC_VERSION)
				.replace("@FORGEVERSION@", FORGE_VERSION)
				.replace("@MAPPINGS@", "loom.officialMojangMappings()")
				.replace("@REPOSITORIES@", "")
				.replace("@PACKAGE@", "net.minecraftforge:forge")
				.replace("@JAVA_VERSION@", "17")
		gradle.buildGradle << ('''
			def namedJarPathFile = project.file('named-jar.txt')

			project.afterEvaluate {
				def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
				def jars = loomExt.getMinecraftJars(net.fabricmc.loom.api.mappings.layered.MappingsNamespace.NAMED)
				namedJarPathFile.text = jars.collect { it.toString() }.join('\\n')
			}

			tasks.register('printNamedJar') {
				doLast {
					namedJarPathFile.readLines().each { println 'NAMED_MC_JAR=' + it }
				}
			}

			// MC jar 的产出任务：按类型取而不是按名字硬编，名字会随 provider 形态变化
			tasks.register('produceNamedMinecraftJar') {
				dependsOn(project.provider {
					project.tasks.withType(net.fabricmc.loom.pipeline.RemapMinecraftTask).names +
							project.tasks.withType(net.fabricmc.loom.pipeline.ProcessMinecraftJarTask).names
				})
			}
			''')

		when: "冷缓存下只跑探针任务（named MC jar 尚未生产）"
		def cold = gradle.run(task: "printNamedJar", configurationCache: false, args: ["--console=plain"])
		def namedJar = extractPath(cold.output, JAR_MARKER)

		then: "探针拿到了 named jar 路径，而它此刻确实不存在"
		cold.task(":printNamedJar").outcome == SUCCESS
		!new File(namedJar).exists()

		and: "整批跳过并说明原因，且不写源码包、也不留下任何被凭空造出的输入 jar"
		cold.output.contains(ForgeSourcesService.SKIPPED_LOG_MARKER)
		!new File(sourcesJarOf(namedJar)).exists()
		!new File(namedJar).exists()
		!new File(namedJar + ".backup").exists()

		when: "让产出任务把 named MC jar 生产出来"
		def produce = gradle.run(task: "produceNamedMinecraftJar", configurationCache: false, args: ["--console=plain"])

		then: "产出任务跑过，产物已就位；本次配置仍然跳过（配置期先于任务执行）"
		produce.task(":produceNamedMinecraftJar").outcome == SUCCESS
		produce.output.contains(ForgeSourcesService.SKIPPED_LOG_MARKER)
		new File(namedJar).exists()

		when: "named jar 就位后再跑一次探针任务"
		def warm = gradle.run(task: "printNamedJar", configurationCache: false, args: ["--console=plain"])
		def sourcesJar = new File(sourcesJarOf(namedJar))

		then: "这次不再跳过，源码包被写出来（跳过不等于把功能关掉）"
		!warm.output.contains(ForgeSourcesService.SKIPPED_LOG_MARKER)
		!warm.output.contains("Could not find Minecraft jar for Forge sources")
		sourcesJar.exists()

		and: "注入的是真实的 Forge 源码，不是空壳"
		def forgeSources = javaEntries(sourcesJar)
		forgeSources.size() > 600
		forgeSources.every { it.startsWith("net/minecraftforge/") }

		and: "过滤对着刚产出的那份 named jar 做：每个源码条目在输入 jar 里都有同名 class"
		def inputClasses = classEntries(new File(namedJar))
		def missing = forgeSources.findAll { !inputClasses.contains(it.replace(".java", ".class")) }
		missing.isEmpty()
	}

	/** 由 named jar 路径推出配置期注入的目标源码包路径（见 GenerateSourcesTask.getJarFileWithSuffix）. */
	private static String sourcesJarOf(String namedJar) {
		assert namedJar.toLowerCase(Locale.ROOT).endsWith(".jar"): "意外路径 ${namedJar}"
		return namedJar.substring(0, namedJar.length() - 4) + "-sources.jar"
	}

	/** 从构建输出里取形如 PREFIX<path> 的一行. */
	private static String extractPath(String output, String prefix) {
		def line = output.readLines().find { it.startsWith(prefix) }
		assert line != null: "未在构建输出里找到 ${prefix}"
		return line.substring(prefix.length()).trim()
	}

	/** jar 里的 {@code .java} 条目名（不含目录条目）. */
	private static List<String> javaEntries(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries().findAll { !it.directory && it.name.endsWith(".java") }.collect { it.name }
		}
	}

	/** jar 里的 {@code .class} 条目名集合. */
	private static Set<String> classEntries(File jar) {
		new ZipFile(jar).withCloseable { zip ->
			return zip.entries().findAll { !it.directory && it.name.endsWith(".class") }.collect { it.name } as Set
		}
	}

	/**
	 * 取得本次要用的隔离 gradle home.
	 *
	 * <p>默认自动播种一份；设置环境变量 {@code LOOM_TEST_FORGE_COLD_HOME}（或同名系统属性）可指定一份
	 * **调用方自己准备好**的 home（用于反复跑同一个场景：重新造冷只需删掉 named jar，不必再复制一次缓存）。
	 * 指定路径时本用例**不删任何文件**——冷度由调用方负责，没准备好会在第一条断言处直接失败。
	 */
	private static File resolveColdHome() {
		def provided = System.getenv("LOOM_TEST_FORGE_COLD_HOME")

		if (provided == null) {
			provided = System.getProperty("loom.test.forgeColdHome")
		}

		if (provided != null) {
			return new File(provided)
		}

		return coldForgeHome()
	}

	/**
	 * 播种一份「Forge 工具链热、named MC jar 冷」的隔离 gradle home.
	 *
	 * <p>种子里被刻意剔除的只有被测那一份产物：named MC jar 及其 {@code .backup}、
	 * {@code -sources.jar}（{@code .pom} 留着，重映射任务自己会重写）。
	 * 同一构件目录下的 srg / intermediary 变体是**另外的**产物，保留。
	 */
	private static File coldForgeHome() {
		def home = File.createTempDir("loom-forge-cold-home", "")
		def seedCaches = new File(LoomTestConstants.TEST_DIR, "integration/gradle_home/caches")
		def targetCaches = new File(home, "caches")

		// Gradle 自身的依赖缓存与本版本缓存：缺了就变成「重新下载 + 重新生成 API jar」，
		// 与被测的冷缓存无关，却会把用例拖到几十分钟
		[
			"9.5.0",
			"jars-9",
			"modules-2",
			"journal-1",
			"CACHEDIR.TAG"
		].each { name ->
			copyIfExists(new File(seedCaches, name), new File(targetCaches, name))
		}

		// Loom 的产物仓库：MC 版本目录 + 全局 Forge 目录
		copyIfExists(new File(seedCaches, "fabric-loom/${MC_VERSION}"), new File(targetCaches, "fabric-loom/${MC_VERSION}"))
		copyIfExists(new File(seedCaches, "fabric-loom/forge"), new File(targetCaches, "fabric-loom/forge"))

		// maven 仓库里本场景用到的构件：映射构件，以及 Forge 合并 jar 的三个命名空间变体。
		// 只有 named 那一份（{@link #NAMED_ARTIFACT}）会被造冷——srg / intermediary 是它的上游输入，
		// 保持热态，本用例才只测「named jar 缺失」这一件事。
		def mavenRoot = new File(seedCaches, "fabric-loom/minecraftMaven")
		copyIfExists(new File(mavenRoot, "loom"), new File(targetCaches, "fabric-loom/minecraftMaven/loom"))

		[
			NAMED_ARTIFACT,
			"forge-${MC_VERSION}-${FORGE_VERSION}-minecraft-merged-srg",
			"forge-${MC_VERSION}-${FORGE_VERSION}-minecraft-merged-intermediary"
		].each { name ->
			copyIfExists(new File(mavenRoot, "net/minecraft/${name}"), new File(targetCaches, "fabric-loom/minecraftMaven/net/minecraft/${name}"))
		}

		// 冷缓存：把被测产物从副本里删掉（共享 home 里的原件不动）
		makeNamedJarCold(targetCaches)

		return home
	}

	/** 删掉副本里 named jar 及其伴生文件（{@code .jar} / {@code .jar.backup} / {@code -sources.jar}）. */
	private static void makeNamedJarCold(File targetCaches) {
		def artifactDir = new File(targetCaches, "fabric-loom/minecraftMaven/net/minecraft/${NAMED_ARTIFACT}")

		if (!artifactDir.isDirectory()) {
			return
		}

		artifactDir.listFiles().findAll { it.isDirectory() }.each { mappingsDir ->
			// 只留 .pom：它是坐标元数据，重映射任务落位产物时会自己重写
			mappingsDir.listFiles().findAll { it.isFile() && !it.name.endsWith(".pom") }.each { it.delete() }
		}
	}

	/**
	 * 拷贝冷 gradle home，跳过 {@code *.lock} 并对其余失败短暂退避重试。
	 *
	 * <p>源目录是共享的 TestKit gradle home，其中 {@code caches/<版本>/fileContent/fileContent.lock}
	 * 等锁文件被**正在运行的 Gradle 守护进程持续持有**——实测在 Windows 上拷贝这些文件必然以
	 * FileSystemException 失败，且**不是暂时性的**（重试再多次也一样，因为守护进程活着就一直占着）。
	 * 锁文件是运行时产物，对「冷 home 种子」没有意义，直接跳过。
	 *
	 * <p>保留重试是为了覆盖其它真正的短暂占用（例如刚写完尚未释放）。此外本失败只在目录级批量跑
	 * 时出现——单跑时没有别的用例的守护进程持有那些锁。
	 *
	 * <p>本方法与 {@link net.fabricmc.loom.test.util.ForgeColdLoomCache} 中的同名声重复，
	 * 两处需保持行为一致。
	 */
	private static void copyIfExists(File source, File target) {
		if (!source.exists()) {
			return
		}

		IOException last = null

		for (int attempt = 0; attempt < 5; attempt++) {
			try {
				if (source.isDirectory()) {
					target.mkdirs()
					FileUtils.copyDirectory(source, target, { File file -> !file.name.endsWith(".lock") } as FileFilter)
				} else {
					target.parentFile.mkdirs()
					FileUtils.copyFile(source, target)
				}

				return
			} catch (IOException e) {
				last = e
				Thread.sleep(200L * (attempt + 1))
			}
		}

		throw new RuntimeException("Could not copy " + source + " to " + target + " after retries", last)
	}
}
