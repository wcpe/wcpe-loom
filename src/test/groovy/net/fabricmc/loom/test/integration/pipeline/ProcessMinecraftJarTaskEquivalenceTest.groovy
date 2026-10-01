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
 * {@code ProcessMinecraftJarTask} 的**产物内容**等价性验证.
 *
 * <p>与 {@code RemapMinecraftTaskEquivalenceTest} 互为两块：那一块证明重映射（映射型 provider）切到任务后
 * 产物没变，本块证明 **jar processor 链**（{@code ProcessedNamedMinecraftProvider}）切到任务后产物没变。
 * 两者都必须各自成立——链的输入来自重映射的产出，但链本身是一条完全不同的实现。
 *
 * <h2>对照怎么构造</h2>
 * 配置期那条路径已经在 {@code ProcessedNamedMinecraftProvider.provide()} 里被切走，产出不再自动可得，
 * 因此本测试手工构造一份对照：**在探针任务里直接调用配置期那两处调用点**。
 * <ul>
 *   <li>装配：{@code MinecraftJarProcessorManager.create(project)} —— 与
 *       {@code CompileConfiguration} 建 manager 的是同一次调用（含用 {@code RemappedSpecContext} 建 spec）；</li>
 *   <li>执行：{@code manager.processJar(path, new ProcessorContextImpl(configContext, minecraftJar))} ——
 *       与 {@code ProcessedNamedMinecraftProvider.processJars} 调的是同一个方法、同一个上下文实现；</li>
 *   <li>落位：与配置期的 {@code LocalMavenHelper.copyToMaven} 一样，先把输入整份复制到独立路径再就地改写。
 *       探针写的是 {@code build/probe/} 下的独立文件，不碰 maven 仓库里被测任务的产物。</li>
 *   <li>输入：取自被测任务自己的 {@code inputJar}（即父 provider 的产出），
 *       而不是按路径另取一份——两者不同源的话比的就是另一件事。</li>
 * </ul>
 *
 * <p>因此两侧的差别恰好是**被测的那一处**：链的装配时机与执行上下文实现
 * （任务侧经 {@code descriptor().createProcessor()} 重建 + {@code ExecutionProcessorContext}，
 * 配置期侧是原实例 + {@code ProcessorContextImpl}）。这一点必须说清：探针**不是**「把被测方换一个写法再比」，
 * 它用的是被改造前的那两处调用点。
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义，而 entry 内容才是。任何一个 entry 的字节不同即判为差异。
 *
 * <h2>链非空</h2>
 * 链为空等于没测。这里用本地 access widener 把链拉起来；断言里显式要求 entry 数 &gt; 0，
 * 并把实际链的名字、两侧的 spec 指纹、以及「链上需要 remapper 的条目数」都写进报告，
 * 避免「比了但比的是空链」。
 *
 * <h2>为什么还要一个依赖 mod（L4 的 remapper 装配覆盖）</h2>
 * 只有本地 AW 时链上唯一的一环在 {@code named} 命名空间读取规则（{@code LocalAccessWidenerEntry.read}
 * 根本不用 remapper，{@code LazyCloseable.close()} 在从未 {@code get()} 时是空操作），
 * 于是**连 remapper 实例都不会构造**——报告里的 {@code CHAIN_REMAPPER_ENTRIES} 就是这件事的数字化表述。
 * 而这一处恰好是配置期与执行期的已知差异所在：配置期 {@code ContextImplHelper.createRemapper} →
 * {@code TinyRemapperHelper}（{@code fixRecords=false}、{@code validateTargetNamespace=true}，另有
 * LVT 四项与 JSR 映射），执行期 {@code ExecutionProcessorContext.createRemapper} →
 * {@code TinyRemapperService}（没有那两个开关）。「够不到产物」是隐式推断，不是验证过的结论。
 *
 * <p>因此本测试挂了一个只含元数据与 access widener 的依赖 mod：它的 AW header 是
 * <b>intermediary</b>，且规则带 {@code transitive-} 前缀。前者让
 * {@code ModAccessWidenerEntry.read} 必须走 {@code remapper.get()}，后者让它能通过
 * {@code TransitiveOnlyFilter}（mod 依赖的 AW 是按 {@code transitiveOnly=true} 读的）。
 * 规则本身把 {@code net/minecraft/class_3797.field_16737}（{@code MinecraftVersion.stable}，
 * jar 里是 private）改成 accessible —— 选 private 成员是刻意的：**规则经 remapper 改写后必须
 * 真的改变字节**，否则「remapper 被用上了」在产物上依旧不可观察，覆盖会退化成「形式上取了 remapper」。
 *
 * <p>该 jar 自带 {@code Fabric-Loom-Remap: false}（loom 的既有 opt-out），因此不参与 L3 的 mod
 * 重映射：本测试只取它的 access widener，它自身重不重映射与 jar processor 链无关——基线里
 * {@code modImplementation} 上只有同样声明了 opt-out 的 fabric-loader，故那里根本没有
 * {@code RemapModsTask}。顺带记下一条实测到的缺陷：不声明 opt-out 时该任务会被注册并在任务校验
 * 阶段直接失败（{@code RemapModsTask} 属性 {@code mods.$0.inputJar} 标了 {@code @InputFile}
 * 却没有 {@code @PathSensitive}）。那属于 L3 那条链、不在本测试范围内，也没有为了绕过它改生产代码。
 *
 * <h2>这条链覆盖到哪、覆盖不到哪（如实记录）</h2>
 * 本场景（fabric + merged + 本地 AW + 一个文件型依赖 mod）实测链仍只有一环：
 * {@code fabric-loom:access-widener}。其余已在执行期可重建的 processor 在本场景下 spec 为 {@code null}：
 * {@code fabric-loom:mod-javadoc}（依赖 mod 未提供 javadoc）、{@code fabric-loom:interface-inject}
 * （没有 mod 声明注入接口）、{@code fabric-loom:jsr-annotations}
 * （{@code remapJsrAnnotationsToJetBrains} 默认为 true 时根本不注册）。
 *
 * <p>因此**本测试证明了**：这一环的「descriptor 重建 → 执行期上下文 → 就地改写」与配置期
 * 原实例 + {@code ProcessorContextImpl} 得到逐字节相同的产物；链的身份（descriptor 值、
 * spec 指纹）两侧一致；且在本场景下**两侧的 remapper 装配（L4）也走了同一条路**
 * ——{@code CHAIN_REMAPPER_ENTRIES &gt; 0} 证明 remapper 被构造过，而
 * {@code net/minecraft/MinecraftVersion.class} 出现在「产物 vs 输入」的差异里
 * 证明它被真的用于改写规则并落到了字节上。
 *
 * <p>**本测试仍没有覆盖**：一次构建里 remapper 同时被多个 processor 请求、
 * 或请求非 {@code 生产命名空间 → named} 的命名空间对（执行期那条路会直接拒绝，
 * 见 {@code ExecutionProcessorContext.createRemapper}）。
 */
class ProcessMinecraftJarTaskEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** 本地 access widener：让 processor 链至少有一环，并真的改写至少一个 class 条目. */
	private static final String ACCESS_WIDENER = "accessWidener\tv2\tnamed\nextendable\tclass\tnet/minecraft/item/ItemStack\n"
	/** 活性检查用的扰动 access widener：与上者的唯一差别是目标类. */
	private static final String PERTURBED_ACCESS_WIDENER = "accessWidener\tv2\tnamed\nextendable\tclass\tnet/minecraft/client/util/VideoMode\n"

	/**
	 * 依赖 mod 自带的 access widener：header 是 <b>intermediary</b> 且规则带 {@code transitive-} 前缀.
	 *
	 * <p>两个条件缺一不可：{@code transitive-} 前缀是 {@code ModAccessWidenerEntry} 以
	 * {@code transitiveOnly=true} 读取时 {@code TransitiveOnlyFilter} 的放行条件；
	 * 而 header 不是 {@code named} 才会走到 {@code ModAccessWidenerEntry.read} 里的
	 * {@code remapper.get()} ——那正是让链真正**构造并使用 remapper** 的那一处。
	 *
	 * <p>规则内容：{@code net/minecraft/class_3797.field_16737}（yarn 1.20.1 的
	 * {@code MinecraftVersion.stable}，jar 里是 private）改成 accessible。
	 * 用 private 字段是刻意的——它必须让目标 class 的字节真的改变，否则「规则有没有被
	 * 改写」在产物上无法观察，覆盖就退化成「形式上取了 remapper」。
	 */
	private static final String MOD_ACCESS_WIDENER = "accessWidener\tv2\tintermediary\n\ntransitive-accessible\tfield\tnet/minecraft/class_3797\tfield_16737\tZ\n"
	/** 依赖 mod 的 fabric.mod.json：除 id/version 外只声明那个 access widener. */
	private static final String MOD_FABRIC_MOD_JSON = '''{
  "schemaVersion": 1,
  "id": "eq-probe-dep",
  "version": "1.0.0",
  "name": "Equivalence Probe Dependency",
  "accessWidener": "eq-probe.accesswidener"
}
'''
	/**
	 * 依赖 mod 的 manifest：显式声明 {@code Fabric-Loom-Remap: false}（loom 的既有 opt-out）.
	 *
	 * <p>本测试只要**它的 access widener**，而这个 jar 自身走不走 L3 的 mod 重映射与本测试无关
	 * （fabric-loader 自己也是这样做的，基线里正因为 loader 声明了 opt-out，
	 * {@code modImplementation} 上根本不会注册 {@code RemapModsTask}）。
	 *
	 * <p>不声明 opt-out 时该任务会被注册并在任务校验阶段直接失败：
	 * {@code RemapModsTask} 属性 {@code mods.$0.inputJar} 标了 {@code @InputFile} 却没有
	 * {@code @PathSensitive}——那是 L3 那条链上的一处未修缺陷，不在本测试范围内，
	 * 这里也不为了绕过它去改生产代码。
	 */
	private static final String MOD_MANIFEST = "Manifest-Version: 1.0\nFabric-Loom-Remap: false\n\n"
	/**
	 * 上述规则**改写后**命中的 named 类条目.
	 *
	 * <p>AW 原文里的 owner 是 {@code net/minecraft/class_3797}，named jar 里没有这个类；
	 * 因此这个条目在产物上出现差异，等价于「规则被 remapper 改写到了 named 命名空间并被套用」。
	 */
	private static final String MOD_AW_TARGET_ENTRY = "net/minecraft/MinecraftVersion.class"
	/** 本场景下命名 jar 的处理任务名（见 ProcessedNamedMinecraftProvider.taskName）. */
	private static final String PROCESS_TASK = "processMinecraftNamedMerged"

	def "处理后的命名 jar 与按配置期路径执行的产出一致"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            loom.accessWidenerPath = file('src/main/resources/test.accesswidener')

            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
                // 依赖 mod 自带 intermediary 头的 access widener：链上因此出现一条「必须取 remapper
                // 才能改写到 named」的 AW 条目（见 MOD_ACCESS_WIDENER 的注释）。
                // 该 jar 自带 Fabric-Loom-Remap: false，故不参与 L3 的 mod 重映射（见 MOD_MANIFEST 的注释）。
                modImplementation files("dummy.jar")
            }

            def MappingsNamespace = net.fabricmc.loom.api.mappings.layered.MappingsNamespace
            def probeState = [:]

            // 配置期能取到的一切都在这里取好：任务动作里读 Task.project 在 Gradle 9 已是弃用行为
            // （本测试的 harness 以 --warning-mode fail 运行），而这里读的全是配置期就已经定下来的值。
            project.afterEvaluate {
                def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
                def processTask = tasks.getByName(TASK_NAME_PLACEHOLDER)

                probeState.project = project
                probeState.objects = project.objects
                probeState.loomExt = loomExt
                probeState.processTask = processTask
                probeState.inputJar = processTask.inputJar.get().asFile
                probeState.subjectJar = processTask.outputJar.get().asFile
                probeState.namedJar = new File(loomExt.getMinecraftJars(MappingsNamespace.NAMED)[0].toString())
                probeState.probeDir = new File(project.layout.buildDirectory.get().asFile, 'probe')
                probeState.reportFile = project.file('probe-report.properties')
                probeState.perturbFile = project.file('src/main/resources/perturb.accesswidener')

                // 与配置期 ProcessorContextImpl 拿到的 MinecraftJar 同源：父 provider 产出的那个 jar
                def namedProvider = loomExt.getNamedMinecraftProvider()
                probeState.parentJar = (namedProvider.hasProperty('parentMinecraftProvider')
                        ? namedProvider.parentMinecraftProvider : namedProvider).getMinecraftJars()[0]
            }

            tasks.register('jarProcessorEquivalenceProbe') {
                // 输入取自被测任务的 inputJar，故必须先让它跑完
                dependsOn TASK_NAME_PLACEHOLDER

                def state = probeState

                doLast {
                    def inputJar = state.inputJar
                    def subjectJar = state.subjectJar
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

                    // 活性检查用的扰动对照：链的其余每一环都相同，只把 access widener 换成另一个文件。
                    // 若两侧产出的差异检测不出来，说明上面的比对口径是死的。
                    def awDescriptor = configManager.processors[0].descriptor()
                    def perturbedAw = state.objects.newInstance(
                            net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor.class,
                            awDescriptor.name(), awDescriptor.includeTransitive(),
                            state.objects.fileProperty().fileValue(state.perturbFile))
                    def perturbedManager = net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager.create(
                            configManager.processors.collect { it.class.name.contains('AccessWidenerJarProcessor') ? perturbedAw : it },
                            net.fabricmc.loom.configuration.processors.speccontext.RemappedSpecContext.create(state.project))
                    def perturbedJar = new File(state.probeDir, 'perturbed.jar')
                    java.nio.file.Files.copy(inputJar.toPath(), perturbedJar.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    def perturbedServiceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()

                    try {
                        perturbedManager.processJar(perturbedJar.toPath(),
                                new net.fabricmc.loom.configuration.processors.ProcessorContextImpl(
                                        new net.fabricmc.loom.configuration.ConfigContextImpl(state.project, perturbedServiceFactory, state.loomExt),
                                        contextJar))
                    } finally {
                        perturbedServiceFactory.close()
                    }

                    def processTask = state.processTask
                    def taskDescriptors = processTask.processorDescriptors.get()
                    def configDescriptors = configManager.processors.collect { it.descriptor() }
                    def lines = []
                    lines << 'CHAIN_COUNT=' + taskDescriptors.size()
                    lines << 'CHAIN_TASK=' + taskDescriptors.join(' | ')
                    lines << 'CHAIN_CONFIG=' + configDescriptors.join(' | ')
                    lines << 'DESCRIPTORS_EQUAL=' + (taskDescriptors == configDescriptors)
                    lines << 'FINGERPRINTS_TASK=' + processTask.specFingerprints.get().join(',')
                    lines << 'FINGERPRINTS_CONFIG=' + configManager.specs.collect { it.hashCode() }.join(',')
                    lines << 'FINGERPRINTS_EQUAL=' + (processTask.specFingerprints.get() == configManager.specs.collect { it.hashCode() })
                    // 链上「来自依赖 mod 的 AW 条目」数：这类条目的 header 不在 named 命名空间，
                    // AccessWidenerJarProcessor 必须真的取到 remapper 才能把规则改写到 named。
                    // 为 0 说明这条链根本用不到 remapper，两侧 remapper 装配的差异也就覆盖不到。
                    def awSpec = configManager.specs.find {
                        it instanceof net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor.Spec
                    }
                    lines << 'CHAIN_REMAPPER_ENTRIES=' + (awSpec == null ? 0 : awSpec.accessWideners().count {
                        !(it instanceof net.fabricmc.loom.configuration.accesswidener.LocalAccessWidenerEntry)
                    })
                    // 逐条列出链上 AW 条目（类名 + sortKey），便于核对「需要 remapper 的那条」确实是依赖 mod 的
                    lines << 'CHAIN_AW_ENTRIES=' + (awSpec == null ? '' : awSpec.accessWideners().collect {
                        it.class.simpleName + '(' + it.getSortKey() + ')'
                    }.join(' | '))
                    lines << 'INPUT_JAR=' + inputJar.absolutePath
                    lines << 'SUBJECT_JAR=' + subjectJar.absolutePath
                    lines << 'NAMED_JAR=' + state.namedJar.absolutePath
                    lines << 'PARENT_JAR=' + state.parentJar.path.toString()
                    lines << 'REFERENCE_JAR=' + referenceJar.absolutePath
                    lines << 'PERTURBED_JAR=' + perturbedJar.absolutePath
                    state.reportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
                }
            }
            '''.replace('TASK_NAME_PLACEHOLDER', "'" + PROCESS_TASK + "'")

		def aw = new File(gradle.projectDir, "src/main/resources/test.accesswidener")
		aw.parentFile.mkdirs()
		aw.text = ACCESS_WIDENER
		new File(gradle.projectDir, "src/main/resources/perturb.accesswidener").text = PERTURBED_ACCESS_WIDENER

		def source = new File(gradle.projectDir, "src/main/java/com/example/Example.java")
		source.parentFile.mkdirs()
		source.text = "package com.example;\n\npublic class Example {\n\tpublic static final String NAME = \"example\";\n}\n"

		// 依赖 mod：一个只含元数据与 AW 的 jar。它必须通过 modImplementation 同时落在
		// 编译与运行配置上，才会进入 RemappedSpecContext.modDependenciesCompileRuntime()
		// ——AccessWidenerJarProcessor 的传递性 AW 条目正是从那里读的。
		def modDir = new File(gradle.projectDir, "dummyDependency")
		modDir.mkdirs()
		new File(modDir, "fabric.mod.json").text = MOD_FABRIC_MOD_JSON
		new File(modDir, "eq-probe.accesswidener").text = MOD_ACCESS_WIDENER
		new File(modDir, "META-INF").mkdirs()
		new File(modDir, "META-INF/MANIFEST.MF").text = MOD_MANIFEST
		ZipUtils.pack(modDir.toPath(), new File(gradle.projectDir, "dummy.jar").toPath())

		when: "一次构建里既跑正常 build，也跑探针（探针在配置期路径之外单独产出一份对照）"
		// 探针的动作要读 Gradle 的项目模型（那正是配置期调用点所需要的），因此这里显式关掉配置缓存：
		// 关掉的是**探针脚本**的可缓存性，与 loom 任务的协议无关。
		def build = gradle.run(tasks: [
			"build",
			"jarProcessorEquivalenceProbe"
		], configurationCache: false)
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def subject = entries(new File(report.SUBJECT_JAR))
		def reference = entries(new File(report.REFERENCE_JAR))
		def input = entries(new File(report.INPUT_JAR))
		def perturbed = entries(new File(report.PERTURBED_JAR))
		// 比对口径：entry 名 + 每个 entry 的字节摘要。差异只报「哪些条目不同」，不比整文件哈希
		def vsReference = diff(subject, reference)
		def vsInput = diff(subject, input)
		def vsPerturbed = diff(subject, perturbed)
		printEvidence(report, subject, reference, input, perturbed, vsReference, vsInput, vsPerturbed)

		then: "被测任务真的跑过，且它产出的就是消费侧看到的那个 named jar"
		build.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		build.task(":" + PROCESS_TASK).outcome in [SUCCESS, UP_TO_DATE]
		report.SUBJECT_JAR == report.NAMED_JAR
		// 对照的输入必须是被测任务自己的输入，否则比的是另一件事
		report.INPUT_JAR == report.PARENT_JAR
		new File(report.SUBJECT_JAR).exists()
		new File(report.REFERENCE_JAR).exists()

		and: "链非空，且两侧走的是同一条链（descriptor 与 spec 指纹都逐项一致）"
		report.CHAIN_COUNT.toInteger() > 0
		report.DESCRIPTORS_EQUAL == "true"
		report.FINGERPRINTS_EQUAL == "true"

		and: "链上真的存在「必须取 remapper 才能改写到 named」的条目（否则 L4 的 remapper 装配根本走不到）"
		report.CHAIN_REMAPPER_ENTRIES.toInteger() > 0
		// 说清这一条是谁：报告里应列出那条来自依赖 mod 的 AW 条目
		report.CHAIN_AW_ENTRIES.contains("ModAccessWidenerEntry(eq-probe-dep:")

		and: "两侧产出的 entry 名集合完全一致"
		!subject.isEmpty()
		!reference.isEmpty()
		subject.keySet() == reference.keySet()

		and: "每一个 entry 的字节逐一致（任一条目不同即失败）"
		assert vsReference.isEmpty(): "条目内容不一致: ${vsReference.take(20)}（共 ${vsReference.size()} 项）"

		and: "反向排除假通过：产物确实与输入不同（链真的改动了内容，不是把输入复制了一份）"
		assert !vsInput.isEmpty(): "产物与输入逐字节相同，说明链没有生效"

		and: "remapper 被**真的用上了**，不只是被构造：依赖 mod 的 intermediary 规则改写到 named 后确实改动了产物"
		// 这条规则在 AW 原文里写的是 net/minecraft/class_3797，named jar 中不存在这个类；
		// 只有「规则被 remapper 改写成了 net/minecraft/MinecraftVersion」才会让该条目出现在差异里。
		assert vsInput.contains(MOD_AW_TARGET_ENTRY):
		"${MOD_AW_TARGET_ENTRY} 未被改动，说明依赖 mod 的 AW 规则没有经过 remapper 改写就被套用（或根本没被读）"

		and: "活性检查：只把对照侧的 access widener 换成另一个目标，上面的同一口径必须报出差异"
		perturbed.keySet() == subject.keySet()
		assert !vsPerturbed.isEmpty(): "换掉 access widener 目标后两侧仍逐条相同，说明比对口径是死的"
	}

	/** {@return {@code actual} 与 {@code expected} 中内容不同的条目名} 条目名集合相同是调用方的前提. */
	private static List<String> diff(Map<String, String> actual, Map<String, String> expected) {
		return actual.findResults { name, hash -> hash == expected[name] ? null : name }
	}

	/** 把证据打进测试输出：条目数、差异数、差异条目名、链与指纹. */
	private static void printEvidence(Map<String, String> report, Map<String, String> subject, Map<String, String> reference,
			Map<String, String> input, Map<String, String> perturbed, List<String> vsReference, List<String> vsInput,
			List<String> vsPerturbed) {
		println "== jar processor 链等价性证据 =="
		println "条目数: subject=${subject.size()} reference=${reference.size()} input=${input.size()} perturbed=${perturbed.size()}"
		println "任务产出 vs 配置期路径: 差异 ${vsReference.size()} 项 ${vsReference.take(20)}"
		println "任务产出 vs 输入（反向排除）: 差异 ${vsInput.size()} 项 ${vsInput.take(20)}"
		println "任务产出 vs 扰动对照（活性检查）: 差异 ${vsPerturbed.size()} 项 ${vsPerturbed.take(20)}"
		println "processor 链: ${report.CHAIN_TASK}"
		println "需要 remapper 的链上条目数: ${report.CHAIN_REMAPPER_ENTRIES}（为 0 表示本条链不会取 remapper）"
		println "链上 AW 条目: ${report.CHAIN_AW_ENTRIES}"
		println "spec 指纹: task=${report.FINGERPRINTS_TASK} config=${report.FINGERPRINTS_CONFIG}"
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
