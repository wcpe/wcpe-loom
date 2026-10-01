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

import java.security.MessageDigest
import java.util.zip.ZipFile

import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.ZipUtils

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * 「一次构建里 processor 链上有**两个** remapper 消费者」的产物级验证.
 *
 * <p>{@code ProcessMinecraftJarTaskEquivalenceTest} 证明的是链上只有一个 remapper 消费者时的等价性
 * （它自己也把这条边界写进了类注释）。真实链上会有第二个：{@code AccessWidenerJarProcessor} 与
 * {@code InterfaceInjectionProcessor} 都会向**同一个** {@code ExecutionProcessorContext} 请求
 * remapper，而任务对整条链只建一个上下文——执行期的 remapper 由 {@code TinyRemapperService} 共用，
 * 消费者拿到的 {@code LazyCloseable.close()} 是空操作（与配置期的 {@code TinyRemapper::finish} 相反）。
 * 先跑完的一环若把共享的 remapper 拆掉，后跑的那一环的产物就会**静默出错**。
 *
 * <h2>怎么让链上出现第二个消费者</h2>
 * {@code InterfaceInjectionProcessor.buildSpec} 在没有任何 mod 声明被注入接口时返回 {@code null}、
 * 不进链。因此这里给测试工程加一个本地 mod（{@code src/main/resources/fabric.mod.json}）声明
 * {@code loom:injected_interfaces}，目标写 <b>intermediary</b> 名（mod 里的写法就是生产命名空间下的名字）：
 * {@code net/minecraft/class_310} → named 下的 {@code net/minecraft/client/MinecraftClient}。
 * 该 processor 在 {@code !disableObfuscation()} 时必然取一次 remapper（`getInjectedInterfaces`），
 * 于是链上出现第二个消费者。
 *
 * <p>第一个消费者（AW）仍然来自依赖 mod 自带的 **intermediary 头 + transitive- 前缀** AW 规则
 * ——那正是让 {@code ModAccessWidenerEntry} 必须 {@code remapper.get()} 的条件（同
 * {@code ProcessMinecraftJarTaskEquivalenceTest}）。两个消费者刻意打在**不同**的目标类上，
 * 这样「谁真的生效了」在产物上可以分开观察。
 *
 * <h2>两个消费者都「真的用了 remapper」怎么取证</h2>
 * 不看「有没有调用」，看产物：
 * <ul>
 *   <li>AW 侧：规则原文是 {@code net/minecraft/class_3797}（named 的 jar 里不存在这个类），
 *       它对应的 named 条目出现在「产物 vs 输入」的差异里，等价于「规则被改写到了 named 并套用」；</li>
 *   <li>接口注入侧：注入的接口名 {@code net/example/...} 不在映射里，因此它出现的位置就是注入落点；
 *       而它**带的泛型参数**写的是 {@code class_3797}，只有在 remapper 真的把它改写成
 *       {@code MinecraftVersion} 之后，产物里才会出现 {@link #PROBE_SIGNATURE} 那一串。
 *       故「产物里有且只有一个 class 条目带这串签名，且那个条目正是 named 目标」同时证明了三件事：
 *       第二个消费者跑了、目标类名被映射到了 named、它拿到的 remapper 真的映射出了东西。</li>
 * </ul>
 *
 * <h2>本测试没有覆盖的</h2>
 * <ul>
 *   <li>「两个消费者拿到的是同一份底层 remapper」「一方 close 后另一方仍可用」在**进程内**的断言
 *       由 {@code ExecutionProcessorContextSharedRemapperTest} 承担：这里只证明真实链上先后取用时
 *       行为正确（后取用的一环完成不了就产不出上面那串签名）。</li>
 *   <li>链上两个消费者是**先后**取用（Gradle 任务的 processor 链本就是顺序执行），不是线程并发。
 *       「先关闭的一环不影响后取用的一环」正是这里覆盖的形态；真正并发的场景不存在于生产代码。</li>
 *   <li>链的 remapper 装配差异（配置期 {@code TinyRemapperHelper} 的 {@code fixRecords} /
 *       {@code validateTargetNamespace} / JSR 映射开关，执行期 {@code TinyRemapperService} 没有）
 *       与消费者个数无关，仍由 {@code ProcessMinecraftJarTaskEquivalenceTest} 的边界说明承担。</li>
 * </ul>
 */
class ProcessMinecraftJarTaskMultiRemapperTest extends Specification implements GradleProjectTestTrait {
	/** 本地 access widener：让链上第一环有本地条目（named 头，不需要 remapper）. */
	private static final String ACCESS_WIDENER = "accessWidener\tv2\tnamed\nextendable\tclass\tnet/minecraft/item/ItemStack\n"
	/**
	 * 依赖 mod 自带的 access widener：intermediary 头 + {@code transitive-} 前缀.
	 *
	 * <p>两者缺一不可：前缀是 {@code TransitiveOnlyFilter} 的放行条件，而 header 不是 named
	 * 才会让 {@code ModAccessWidenerEntry.read} 走到 {@code remapper.get()}——第一个消费者靠它成立。
	 */
	private static final String MOD_ACCESS_WIDENER = "accessWidener\tv2\tintermediary\n\ntransitive-accessible\tfield\tnet/minecraft/class_3797\tfield_16737\tZ\n"
	/** 依赖 mod 的 fabric.mod.json：只声明那个 access widener. */
	private static final String MOD_FABRIC_MOD_JSON = '''{
  "schemaVersion": 1,
  "id": "multi-remapper-dep",
  "version": "1.0.0",
  "name": "Multi Remapper Probe Dependency",
  "accessWidener": "multi-remapper.accesswidener"
}
'''
	/**
	 * 依赖 mod 的 manifest：声明 {@code Fabric-Loom-Remap: false}.
	 *
	 * <p>本测试只要它的 access widener；不声明 opt-out 时 {@code RemapModsTask} 会被注册并在任务校验阶段
	 * 失败（该任务属性缺 {@code @PathSensitive}，见 {@code ProcessMinecraftJarTaskEquivalenceTest} 的记录）。
	 */
	private static final String MOD_MANIFEST = "Manifest-Version: 1.0\nFabric-Loom-Remap: false\n\n"
	/** 本地 mod 声明的注入接口：不在映射表里，故它在产物里的位置就是注入落点. */
	private static final String PROBE_INTERFACE = "net/example/MultiRemapperProbeInterface"
	/**
	 * 注入接口的泛型参数，写 intermediary 名.
	 *
	 * <p>刻意带泛型，因为**只有泛型这条路径**才真的把 {@code TinyRemapper} 用上：
	 * {@code InterfaceInjectionProcessor.remap} 里类名走映射树（{@code mappings.mapClassName}），
	 * 泛型走 {@code TrRemapper.mapSignature}。不带泛型时第二个消费者虽然照样请求了 remapper，
	 * 它的映射输出在产物上却无法观察——那样这个夹具就退化成「形式上有两个消费者」。
	 */
	private static final String PROBE_GENERICS = "<Lnet/minecraft/class_3797;>"
	/**
	 * 上述泛型经 remapper 映射后应出现在产物里的完整签名（作为目标类的一个接口项）.
	 *
	 * <p>{@code class_3797} 在 named 下是 {@code MinecraftVersion}；这个字面量只可能来自
	 * 「注入 + 泛型签名改写」这一条路径，因此它出现即是第二个消费者的 remapper **真的映射出了东西**。
	 */
	private static final String PROBE_SIGNATURE = "L" + PROBE_INTERFACE + "<Lnet/minecraft/MinecraftVersion;>;"
	/** 注入目标：intermediary 名（named 下是 {@code net/minecraft/client/MinecraftClient}）. */
	private static final String PROBE_TARGET_INTERMEDIARY = "net/minecraft/class_310"
	/** 注入目标改写后的 named 条目名；断言里直接写出来，避免测试自己再算一遍映射. */
	private static final String PROBE_TARGET_ENTRY = "net/minecraft/client/MinecraftClient.class"
	/**
	 * 本地 mod 的 fabric.mod.json：声明把 {@link #PROBE_INTERFACE} 注入到 {@link #PROBE_TARGET_INTERMEDIARY}.
	 *
	 * <p>目标写 intermediary：mod 里的名字就是生产命名空间（fabric 下是 intermediary）下的名字，
	 * 与既有 {@code interfaceInjection} 夹具的写法一致。它就是第二个消费者的成立条件。
	 */
	private static final String LOCAL_FABRIC_MOD_JSON = '''{
  "schemaVersion": 1,
  "id": "multi-remapper-probe",
  "version": "1.0.0",
  "name": "Multi Remapper Probe",
  "custom": {
    "loom:injected_interfaces": {
      "''' + PROBE_TARGET_INTERMEDIARY + '''": [
        "''' + PROBE_INTERFACE + PROBE_GENERICS + '''"
      ]
    }
  }
}
'''
	/**
	 * 上述 AW 规则**改写后**命中的 named 类条目.
	 *
	 * <p>AW 原文里的 owner 是 {@code net/minecraft/class_3797}，named jar 里没有这个类；
	 * 因此它出现在「产物 vs 输入」的差异里，等价于「规则被 remapper 改写到了 named 并落到字节上」。
	 */
	private static final String AW_TARGET_ENTRY = "net/minecraft/MinecraftVersion.class"
	/** 链上会真的调 {@code createRemapper} 的 processor 名（本仓库当前的全部调用方）. */
	private static final List<String> REMAPPER_CONSUMERS = [
		"fabric-loom:access-widener",
		"fabric-loom:interface-inject"
	]
	/** 本场景下命名 jar 的处理任务名（见 ProcessedNamedMinecraftProvider.taskName）. */
	private static final String PROCESS_TASK = "processMinecraftNamedMerged"
	/** 探针任务名（构造配置期对照 + 写报告）. */
	private static final String PROBE_TASK = "multiRemapperConsumerProbe"

	def "链上有两个 remapper 消费者时产物仍与配置期路径逐条目一致"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << probeScript()

		def resources = new File(gradle.projectDir, "src/main/resources")
		resources.mkdirs()
		new File(resources, "test.accesswidener").text = ACCESS_WIDENER
		// 本地 mod：第二个 remapper 消费者（接口注入）的成立条件
		new File(resources, "fabric.mod.json").text = LOCAL_FABRIC_MOD_JSON

		// 依赖 mod：一个只含元数据与 AW 的 jar，必须通过 modImplementation 同时落在编译与运行配置上，
		// 才会进入 RemappedSpecContext.modDependenciesCompileRuntime()
		def modDir = new File(gradle.projectDir, "dummyDependency")
		modDir.mkdirs()
		new File(modDir, "fabric.mod.json").text = MOD_FABRIC_MOD_JSON
		new File(modDir, "multi-remapper.accesswidener").text = MOD_ACCESS_WIDENER
		new File(modDir, "META-INF").mkdirs()
		new File(modDir, "META-INF/MANIFEST.MF").text = MOD_MANIFEST
		ZipUtils.pack(modDir.toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when: "跑被测任务与探针（探针另外按配置期路径产出一份对照）"
		// 探针的动作要读 Gradle 的项目模型（那正是配置期调用点所需要的），因此显式关掉配置缓存；
		// 关掉的是**探针脚本**的可缓存性，与 loom 任务的协议无关。
		def build = gradle.run(tasks: [PROCESS_TASK, PROBE_TASK], configurationCache: false)
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def subject = entries(new File(report.SUBJECT_JAR))
		def reference = entries(new File(report.REFERENCE_JAR))
		def input = entries(new File(report.INPUT_JAR))
		// 用**带泛型的完整签名**而不是裸接口名作判据：前者才会把第二个消费者拿到的 remapper 的
		// 映射输出也钉住（裸接口名只说明「注入跑到了」）
		def subjectInjected = entriesContaining(new File(report.SUBJECT_JAR), PROBE_SIGNATURE)
		def referenceInjected = entriesContaining(new File(report.REFERENCE_JAR), PROBE_SIGNATURE)
		def vsReference = diff(subject, reference)
		def vsInput = diff(subject, input)
		printEvidence(report, subject, reference, input, subjectInjected, referenceInjected, vsReference, vsInput)

		then: "被测任务真的跑过，且它产出的就是消费侧看到的那个 named jar"
		build.task(":" + PROCESS_TASK).outcome in [SUCCESS, UP_TO_DATE]
		report.SUBJECT_JAR == report.NAMED_JAR
		// 对照的输入必须是被测任务自己的输入（另外取一份比的就是另一件事）
		report.INPUT_JAR == report.PARENT_JAR
		new File(report.SUBJECT_JAR).exists()
		new File(report.REFERENCE_JAR).exists()

		and: "链上同时有两个会取 remapper 的 consumer，且两侧是同一条链"
		report.CHAIN_REMAPPER_CONSUMERS.toInteger() >= 2
		report.CHAIN_NAMES.tokenize(" | ").containsAll(REMAPPER_CONSUMERS)
		report.DESCRIPTORS_EQUAL == "true"

		and: "第一个消费者（access-widener）真的用上了 remapper：规则原文的 intermediary 名在产物上改成了 named 名"
		// class_3797 在 named jar 里不存在；只有规则被 remapper 改写才会让该条目出现在差异里
		assert vsInput.contains(AW_TARGET_ENTRY):
		"${AW_TARGET_ENTRY} 未被改动，说明依赖 mod 的 AW 规则没有经过 remapper 改写就被套用（或根本没被读）"

		and: "第二个消费者（interface-inject）真的跑到了，且它拿到的 remapper 真的映射出了东西"
		// 这一串签名里同时含「注入的接口名」「改写后的 named 目标」，以及**经 remapper 改写后的泛型参数**；
		// 三者齐备才说明：注入落点是对的、目标类名映射到了 named、remapper 的 mapSignature 有输出
		subjectInjected == [PROBE_TARGET_ENTRY]
		// 配置期对照的落点必须完全相同——两侧不是「都出现了一次」就算等价，而是同一个条目
		referenceInjected == subjectInjected

		and: "两侧产出的 entry 名集合完全一致，且每一个 entry 的字节逐一致"
		!subject.isEmpty()
		!reference.isEmpty()
		subject.keySet() == reference.keySet()
		assert vsReference.isEmpty(): "条目内容不一致: ${vsReference.take(20)}（共 ${vsReference.size()} 项）"

		and: "反向排除假通过：产物确实与输入不同（链真的改动了内容）"
		assert !vsInput.isEmpty(): "产物与输入逐字节相同，说明链没有生效"
	}

	/**
	 * 探针脚本：按配置期路径产出一份对照，并把链与产物路径写进报告.
	 *
	 * <p>调用的两处都是**配置期那条路径的调用点本身**（{@code MinecraftJarProcessorManager.create}
	 * 与 {@code manager.processJar(path, ProcessorContextImpl)}），不是本测试另写的一份语义；
	 * 对照写在自己的文件上，不碰 maven 仓库里被测任务的产物。
	 */
	private static String probeScript() {
		return '''
            loom.accessWidenerPath = file('src/main/resources/test.accesswidener')

            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
                modImplementation files("dummy.jar")
            }

            def ProbeMappingsNamespace = net.fabricmc.loom.api.mappings.layered.MappingsNamespace
            def probeState = [:]

            // 配置期能取到的一切都在这里取好：任务动作里读 Task.project 在 Gradle 9 已是弃用行为
            // （本测试的 harness 以 --warning-mode fail 运行），而这里读的全是配置期就已经定下来的值。
            project.afterEvaluate {
                def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
                def processTask = tasks.getByName('@PROCESS_TASK@')

                probeState.project = project
                probeState.loomExt = loomExt
                probeState.processTask = processTask
                probeState.inputJar = processTask.inputJar.get().asFile
                probeState.subjectJar = processTask.outputJar.get().asFile
                probeState.namedJar = new File(loomExt.getMinecraftJars(ProbeMappingsNamespace.NAMED)[0].toString())
                probeState.probeDir = new File(project.layout.buildDirectory.get().asFile, 'probe')
                probeState.reportFile = project.file('probe-report.properties')

                // 与配置期 ProcessorContextImpl 拿到的 MinecraftJar 同源：父 provider 产出的那个 jar
                def namedProvider = loomExt.getNamedMinecraftProvider()
                probeState.parentJar = (namedProvider.hasProperty('parentMinecraftProvider')
                        ? namedProvider.parentMinecraftProvider : namedProvider).getMinecraftJars()[0]
            }

            // 链上「会真的调 createRemapper 的 processor」：这里是**独立写死的字面量**，不是从生产代码里读的集合
            // ——若将来链上多了第三个消费者而这里没同步，本测试的 ≥2 断言会退化成「只数了两个」，因此这条清单
            // 必须与 ExecutionProcessorContext.createRemapper 的调用方同步维护。
            def probeRemapperConsumers = ['fabric-loom:access-widener', 'fabric-loom:interface-inject']

            tasks.register('@PROBE_TASK@') {
                // 输入取自被测任务的 inputJar，故必须先让它跑完
                dependsOn '@PROCESS_TASK@'

                def state = probeState

                doLast {
                    def inputJar = state.inputJar
                    def contextJar = state.parentJar.forPath(inputJar.toPath())
                    state.probeDir.mkdirs()

                    // 配置期那两处调用点：装配与执行。两者都不是本测试新写的语义。
                    def configManager = net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager.create(state.project)

                    def referenceJar = new File(state.probeDir, 'configtime.jar')
                    java.nio.file.Files.copy(inputJar.toPath(), referenceJar.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    def serviceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
                    def configContext = new net.fabricmc.loom.configuration.ConfigContextImpl(state.project, serviceFactory, state.loomExt)

                    try {
                        configManager.processJar(referenceJar.toPath(),
                                new net.fabricmc.loom.configuration.processors.ProcessorContextImpl(configContext, contextJar))
                    } finally {
                        serviceFactory.close()
                    }

                    def processTask = state.processTask
                    def descriptors = processTask.processorDescriptors.get()
                    def configDescriptors = configManager.processors.collect { it.descriptor() }
                    def names = descriptors.collect { it.name() }
                    def lines = []
                    lines << 'CHAIN_COUNT=' + descriptors.size()
                    lines << 'CHAIN_NAMES=' + names.join(' | ')
                    lines << 'CHAIN_TASK=' + descriptors.join(' | ')
                    lines << 'CHAIN_CONFIG=' + configDescriptors.join(' | ')
                    lines << 'DESCRIPTORS_EQUAL=' + (descriptors == configDescriptors)
                    // 链上「会取 remapper 的 consumer」个数：≥2 才说明本场景真的构造出了并列使用
                    lines << 'CHAIN_REMAPPER_CONSUMERS=' + names.count { probeRemapperConsumers.contains(it) }
                    lines << 'INPUT_JAR=' + inputJar.absolutePath
                    lines << 'SUBJECT_JAR=' + state.subjectJar.absolutePath
                    lines << 'NAMED_JAR=' + state.namedJar.absolutePath
                    lines << 'PARENT_JAR=' + state.parentJar.path.toString()
                    lines << 'REFERENCE_JAR=' + referenceJar.absolutePath
                    state.reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
                }
            }
            '''.replace('@PROCESS_TASK@', PROCESS_TASK).replace('@PROBE_TASK@', PROBE_TASK)
	}

	/** {@return {@code actual} 与 {@code expected} 中内容不同的条目名} 条目名集合相同是调用方的前提. */
	private static List<String> diff(Map<String, String> actual, Map<String, String> expected) {
		return actual.findResults { name, hash -> hash == expected[name] ? null : name }
	}

	/**
	 * {@return 字节里含 {@code needle} 的 class 条目名}.
	 *
	 * <p>判据是字节级包含，不是 ASM 级的精确判定：注入的接口名不在映射表里，因此在产物里只可能来自
	 * 那次注入，它出现在某个 class 常量池里就足以定位落点。这里用它换取「不必在测试里再引一层 ASM」。
	 */
	private static List<String> entriesContaining(File jar, String needle) {
		final byte[] needleBytes = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8)
		final List<String> result = []

		new ZipFile(jar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (e.directory || !e.name.endsWith(".class")) {
					continue
				}

				byte[] bytes = null

				zip.getInputStream(e).withCloseable { input ->
					bytes = input.readAllBytes()
				}

				if (containsBytes(bytes, needleBytes)) {
					result.add(e.name)
				}
			}
		}

		return result.sort()
	}

	/** {@return {@code haystack} 里是否出现了完整的 {@code needle} 字节序列}. */
	private static boolean containsBytes(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			boolean matched = true

			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					matched = false
					break
				}
			}

			if (matched) {
				return true
			}
		}

		return false
	}

	/** 把证据打进测试输出：链、两个消费者的落点、四组差异数. */
	private static void printEvidence(Map<String, String> report, Map<String, String> subject, Map<String, String> reference,
			Map<String, String> input, List<String> subjectInjected, List<String> referenceInjected, List<String> vsReference,
			List<String> vsInput) {
		println "== 双 remapper 消费者链的证据 =="
		println "processor 链（${report.CHAIN_COUNT} 环）: ${report.CHAIN_TASK}"
		println "其中会取 remapper 的 consumer: ${report.CHAIN_REMAPPER_CONSUMERS} 个（${report.CHAIN_NAMES}）"
		println "descriptor 与配置期一致: ${report.DESCRIPTORS_EQUAL}"
		println "注入签名 ${PROBE_SIGNATURE} 的落点: 产物=${subjectInjected} 配置期对照=${referenceInjected}"
		println "条目数: subject=${subject.size()} reference=${reference.size()} input=${input.size()}"
		println "任务产出 vs 配置期路径: 差异 ${vsReference.size()} 项 ${vsReference.take(20)}"
		println "任务产出 vs 输入（反向排除）: 差异 ${vsInput.size()} 项 ${vsInput.take(20)}"
	}

	/** 解析探针写出的 {@code KEY=VALUE} 报告. */
	private static Map<String, String> parseReport(File report) {
		assert report.exists(): "未找到探针报告 ${report.absolutePath}"

		final Map<String, String> result = [:]

		report.readLines().each { line ->
			def index = line.indexOf('=')

			if (index > 0) {
				result[line.substring(0, index)] = line.substring(index + 1)
			}
		}

		return result
	}

	/** 取出 jar 中每个 entry 的名字与内容摘要（目录条目除外）. */
	private static Map<String, String> entries(File jar) {
		final Map<String, String> result = [:]

		new ZipFile(jar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (e.directory) {
					continue
				}

				def digest = MessageDigest.getInstance("SHA-256")

				zip.getInputStream(e).withCloseable { input ->
					digest.update(input.readAllBytes())
				}

				result[e.name] = digest.digest().encodeHex().toString()
			}
		}

		return result
	}
}
