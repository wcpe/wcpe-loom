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

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Fabric **split** 形态的等价性验证：接线的任务产出必须与配置期路径的产出逐条目一致.
 *
 * <h2>为什么这个形态必须单独取证</h2>
 * 它是目前**唯一**能触达 {@code RemapperHookKind.SPLIT_CLIENT_VISITOR_ONLY} 声明效果的形态。
 * 归约规则把 {@code configureRemapper} 覆写的效果收敛成一个布尔输入
 * （{@code RemapMinecraftTask.getInjectClientSidedVisitor()}），判据是
 * {@code !outputJar.isMerged() && outputJar.includesClient()}——即**只对 split 配置里那个 client-only jar**
 * 挂 {@code SidedClassVisitor.CLIENT}。
 *
 * <p>其它形态都到不了这一支：merged jar 的 {@code isMerged()} 为真，forge 系的 split 在
 * {@code CompileConfiguration.setupMinecraft} 处被直接拒绝（见 {@code SrgMojangMappedSplitReachabilityTest}），
 * 而 fabric 的 merged / single jar 形态压根没有覆写钩子（归约为 {@code NO_OP}）。因此本文件是第一个
 * 覆盖「{@code injectClientSidedVisitor} 实际为 true」的用例——若只覆盖了同批的 common jar，那等于
 * 没有补上关键形态。
 *
 * <h2>对照怎么构造</h2>
 * 与 {@code SrgMojangMappedRemapEquivalenceTest} 同一手法：配置期路径已从
 * {@code AbstractMappedMinecraftProvider.provide()} 切走，故探针**逐 jar** 反射调用被切走的那一处
 * ——{@code AbstractMappedMinecraftProvider.remapJar(RemappedJars, ConfigContext)}（改造前的生产逻辑
 * 本身，不是它的再实现）。反射只用于取 {@code protected} 入口；输入、源命名空间与 classpath 一律取自
 * 被测 provider 自己的 {@code getRemappedJars()}，只把落位换成探针的独立文件
 * （{@code build/probe/configtime-<type>.jar}）。
 *
 * <p>这次调用由**探针任务在执行期**发起（早先写在 {@code project.afterEvaluate} 里，即配置期）：那一处
 * 调用点要读输入 jar（vanilla split 的 common / client-only jar），而输入 jar 在生产链迁到任务之后由
 * 执行期任务产出，配置期它还不存在——冷缓存/CI 下配置期调用必以
 * {@code FileNotFoundException ... caches/fabric-loom/1.20.1/minecraft-common.jar} 失败，
 * 只有本机那种「产物早已落在共享 gradle home 里」的热缓存才掩盖得住。探针任务因此声明
 * {@code dependsOn(四个被测任务)}：配置期调用点的输入是被测任务输入的真子集（同一份
 * {@code RemappedJars} 的 {@code inputJar} / {@code remapClasspath}），跟在被测任务之后就一次性拿到它们
 * 全部的任务依赖，探针里不必复刻那套生产接线。**被调用的调用点与比对口径一个字没有改**：搬的只是发起时机。
 *
 * <p>因此**被测产物的路径上只有被测任务一个写入者**：「两者一致」不可能由「读到了同一份文件」伪造出来；
 * 探针改到执行期发起也不引入这类别名——对照仍写在上面那个独立文件上。
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义。任何一条 entry 的字节不同即判为差异。两个 jar 各自独立比一次。
 *
 * <h2>反向排除与活性检查</h2>
 * <ul>
 *   <li>反向排除：每份产物都必须与**它自己的输入 jar** 不同（排除「复制而非重映射」）；</li>
 *   <li>活性检查一（这一条是本文件的核心）：把 {@code injectClientSidedVisitor} **翻转**
 *       ——client-only 从 true 翻成 false、common 从 false 翻成 true——条目名集合必须保持不变而字节必须变。
 *       对 client-only 的这一半直接证明「那个 visitor 真的被挂上了」：它同时是
 *       {@code TASK_CLIENTONLY_INJECT_CLIENT_SIDED_VISITOR == 'true'} 那条断言的反证，若该输入是死的，
 *       「true」就无从谈起；对 common 的那一半证明该输入在 common 上同样活着（两侧都翻，才排除
 *       「只有一个方向恰好不生效」）；</li>
 *   <li>visitor 的效果在产物里可直接观察（活性检查一的加强版）：两份产物的每个 class 条目里是否出现
 *       {@code Lnet/fabricmc/api/Environment;} 描述符。实测 client-only 的所有 class 都带、common 的
 *       class 一条都不带，翻转后两者恰好互换。这一条把证据从「字节变了」推进到「变的就是那个注解」，
 *       并且**配置期对照与任务产物两侧同判据**——不是「任务恰好也产出了同样字节」的巧合；</li>
 *   <li>活性检查二：把 {@code toNamespace} 换成另一个真实存在的命名空间（{@code intermediary}），
 *       必须报出差异，即映射确实参与产物内容、比对口径是活的。</li>
 * </ul>
 *
 * <h2>与 merged 形态的差异点（本测试逐项断言，取值都是实测）</h2>
 * <ul>
 *   <li>{@code getRemappedJars()} 返回**两个** {@code RemappedJars}（类型 {@code common} 与
 *       {@code clientOnly}），因此 split 配置下注册的是**两个** {@code RemapMinecraftTask}
 *       （{@code remapMinecraftNamedCommon} 与 {@code remapMinecraftNamedClientOnly}），不是 merged 形态的一个；</li>
 *   <li>两者的 {@code isMerged()} 都是 {@code false}，但 {@code includesClient()} 只有 client-only 为真
 *       ——这正是 {@code configureSplitRemapper} 的判据，因此 {@code injectClientSidedVisitor} 也只有
 *       client-only 为 true，与「按 jar 而非按 provider」的判定一致；</li>
 *   <li>两个 jar 的输入 jar 不同，且 client-only 的 {@code remapClasspath} 是**非空**的
 *       （含同一个 input provider 的 common jar，配置期构造 {@code RemappedJars} 时作为第四个参数传入），
 *       common 的为空；</li>
 *   <li>归约链：provider 确实覆写了 {@code configureRemapper}、声明
 *       {@code SPLIT_CLIENT_VISITOR_ONLY}、归约结果仍是 {@code SPLIT_CLIENT_VISITOR_ONLY}；</li>
 *   <li>形态开关与 fabric 一致：{@code forgeLike} / {@code injectMixinExtension} / {@code copyOnly} 为 false、
 *       {@code fixRecords} 与 {@code validateTargetNamespace} 为 true。</li>
 * </ul>
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>内容比对覆盖 fabric split 的**两个 provider**：{@code NamedMinecraftProvider.SplitImpl}
 *       （{@code official → named}）与 {@code IntermediaryMinecraftProvider.SplitImpl}
 *       （{@code official → intermediary}），各两个 jar。四处扰动任务只注册在 named 一侧：口径的活性与
 *       visitor 的产物级效果证一次即可，intermediary 用的是同一个任务类、同一套接线、同一处覆写；
 *       它的 visitor 效果改由**产物级观察**（@Environment 计数）取证，而不是靠扰动；</li>
 *   <li>{@code signatureFixes} 与 {@code annotationsJson} 的来源是映射构件里的 extras，本夹具用到的
 *       {@code yarn 1.20.1+build.10} 不含它们（实测两侧都是空），因此本测试**没有**证明「非空时两侧一致」。
 *       要覆盖非空，需要给夹具加一层 {@code loom.layered { signatureFix(file(...)) }}；</li>
 *   <li>其余形态（merged、single jar、legacy merged、disableObfuscation 直通）不在本文件范围内，
 *       分别由 {@code RemapMinecraftTaskEquivalenceTest}、{@code ProcessMinecraftJarTaskEquivalenceTest}、
 *       {@code SrgMojangMappedRemapEquivalenceTest} 覆盖或显式排除。</li>
 * </ul>
 */
class FabricSplitRemapEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** named split 的 common jar 任务名（见 AbstractMappedMinecraftProvider.taskName）. */
	private static final String TASK_COMMON = "remapMinecraftNamedCommon"
	/** named split 的 client-only jar 任务名. */
	private static final String TASK_CLIENT_ONLY = "remapMinecraftNamedClientOnly"
	/** intermediary split 的 common jar 任务名. */
	private static final String TASK_INTERMEDIARY_COMMON = "remapMinecraftIntermediaryCommon"
	/** intermediary split 的 client-only jar 任务名. */
	private static final String TASK_INTERMEDIARY_CLIENT_ONLY = "remapMinecraftIntermediaryClientOnly"
	/** 活性检查：common jar 的 visitor 注入被翻转（false → true）. */
	private static final String PERTURBED_VISITOR_COMMON = "equivalencePerturbedClientVisitorCommon"
	/** 活性检查：client-only jar 的 visitor 注入被翻转（true → false）. */
	private static final String PERTURBED_VISITOR_CLIENT_ONLY = "equivalencePerturbedClientVisitorClientOnly"
	/** 活性检查：common jar 的目标命名空间被换掉. */
	private static final String PERTURBED_NAMESPACE_COMMON = "equivalencePerturbedToNamespaceCommon"
	/** 活性检查：client-only jar 的目标命名空间被换掉. */
	private static final String PERTURBED_NAMESPACE_CLIENT_ONLY = "equivalencePerturbedToNamespaceClientOnly"
	/** 目标命名空间扰动的取值：另一个真实存在的命名空间. */
	private static final String PERTURBED_NAMESPACE = "intermediary"
	/**
	 * 探针任务名：在执行期调用配置期调用点，产出对照并写报告.
	 *
	 * <p>它必须在**执行期**跑：配置期调用点要读的输入 jar（vanilla split 的 common / client-only jar）现在由
	 * 执行期任务产出，配置期读不到（冷缓存/CI 下必以 {@code FileNotFoundException} 失败）。
	 */
	private static final String PROBE_TASK = "equivalenceConfigTimeReferenceProbe"
	/** 报告里 common jar 的键前缀. */
	private static final String COMMON = "COMMON"
	/** 报告里 client-only jar 的键前缀（类型名 {@code clientOnly} 大写后无分隔）. */
	private static final String CLIENT_ONLY = "CLIENTONLY"
	/**
	 * {@code SidedClassVisitor.CLIENT} 会追加的类级注解描述符.
	 *
	 * <p>按描述符字节扫产物是为了把「visitor 被挂上了」这件事变成**产物里可直接观察的事实**：
	 * 只看「翻转输入后字节变了」能证明该输入有效，但证不了那个变化就是该注解。
	 */
	private static final byte[] ENVIRONMENT_DESCRIPTOR = "Lnet/fabricmc/api/Environment;".getBytes(java.nio.charset.StandardCharsets.UTF_8)

	def "Fabric split 的 common 与 client-only 两个 jar 都与配置期路径等价"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << '''
            loom {
                splitMinecraftJar()
            }

            dependencies {
                minecraft 'com.mojang:minecraft:1.20.1'
                mappings 'net.fabricmc:yarn:1.20.1+build.10:v2'
                modImplementation "''' + LoomTestVersions.FABRIC_LOADER.mavenNotation() + '''"
            }
            ''' + probeScript()

		when: "一次构建里既跑四个被测任务与四个扰动任务，也跑探针任务；对照来自探针任务在执行期调用的配置期调用点"
		// 探针任务在执行期调用配置期调用点（它要读那两个输入 jar，而它们由执行期的产出任务给出），
		// 故探针排在四个被测任务之后；比较口径不变：仍是「任务产出 vs 配置期调用点产出」的全量逐条字节对比。
		// 仍显式关掉配置缓存与构建缓存：探针任务读的是 Gradle 项目模型（关配置缓存），
		// 而本用例要断言被测任务**真的执行了**，不是从缓存恢复了别的产物（关构建缓存）
		def build = gradle.run(tasks: [
			TASK_COMMON,
			TASK_CLIENT_ONLY,
			TASK_INTERMEDIARY_COMMON,
			TASK_INTERMEDIARY_CLIENT_ONLY,
			PROBE_TASK,
			PERTURBED_VISITOR_COMMON,
			PERTURBED_VISITOR_CLIENT_ONLY,
			PERTURBED_NAMESPACE_COMMON,
			PERTURBED_NAMESPACE_CLIENT_ONLY
		],
		configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		Map<String, Object> common = loadEvidence(report, COMMON)
		Map<String, Object> clientOnly = loadEvidence(report, CLIENT_ONLY)
		Map<String, Object> intermediaryCommon = loadPairEvidence(report, "INTERMEDIARY_", COMMON)
		Map<String, Object> intermediaryClientOnly = loadPairEvidence(report, "INTERMEDIARY_", CLIENT_ONLY)
		printEvidence(report, common, clientOnly, intermediaryCommon, intermediaryClientOnly)

		then: "九个任务都执行了（四个被测、四个扰动，以及产出对照的探针任务）"
		// 探针任务不声明任何产物 ⇒ 永不 UP-TO-DATE；报告里的键全由它在执行期写出，
		// 因此「报告读得到」本身也是它真跑过的证据，这条断言把它明写成断言而不是隐含前提。
		build.task(":" + TASK_COMMON).outcome == SUCCESS
		build.task(":" + TASK_CLIENT_ONLY).outcome == SUCCESS
		build.task(":" + TASK_INTERMEDIARY_COMMON).outcome == SUCCESS
		build.task(":" + TASK_INTERMEDIARY_CLIENT_ONLY).outcome == SUCCESS
		build.task(":" + PROBE_TASK).outcome == SUCCESS
		build.task(":" + PERTURBED_VISITOR_COMMON).outcome == SUCCESS
		build.task(":" + PERTURBED_VISITOR_CLIENT_ONLY).outcome == SUCCESS
		build.task(":" + PERTURBED_NAMESPACE_COMMON).outcome == SUCCESS
		build.task(":" + PERTURBED_NAMESPACE_CLIENT_ONLY).outcome == SUCCESS

		and: "provider 形态：正是 fabric split 的 Named 实现，split 配置产出两个 jar（common + client-only）"
		report.PROVIDER_CLASS.endsWith("NamedMinecraftProvider\$SplitImpl")
		report.REMAPPED_JAR_COUNT == "2"
		report.REMAPPED_JAR_TYPES == "common|clientOnly"
		report.TARGET_NAMESPACE == "named"
		report.REMAP_TASK_NAMES.contains(TASK_COMMON)
		report.REMAP_TASK_NAMES.contains(TASK_CLIENT_ONLY)

		and: "两个 jar 各自对应一个任务，任务名取自被测任务自身（探针里的名字是独立写死的字面量）"
		common.taskName == TASK_COMMON
		clientOnly.taskName == TASK_CLIENT_ONLY

		and: "common jar：非 merged、不含客户端 → 任务侧 injectClientSidedVisitor 必须是 false"
		report.TASK_COMMON_OUTPUT_MERGED == "false"
		report.TASK_COMMON_OUTPUT_INCLUDES_CLIENT == "false"
		report.TASK_COMMON_INJECT_CLIENT_SIDED_VISITOR == "false"

		and: "client-only jar：非 merged 且含客户端 → 任务侧 injectClientSidedVisitor 必须是 true（configureSplitRemapper 的判据）"
		report.TASK_CLIENTONLY_OUTPUT_MERGED == "false"
		report.TASK_CLIENTONLY_OUTPUT_INCLUDES_CLIENT == "true"
		report.TASK_CLIENTONLY_INJECT_CLIENT_SIDED_VISITOR == "true"

		and: "归约链：覆写确实存在、声明 SPLIT_CLIENT_VISITOR_ONLY、归约结果仍是 SPLIT_CLIENT_VISITOR_ONLY"
		report.PROVIDER_OVERRIDES_HOOK == "true"
		report.PROVIDER_DECLARED_HOOK_KIND == "SPLIT_CLIENT_VISITOR_ONLY"
		report.PROVIDER_EFFECTIVE_HOOK_KIND == "SPLIT_CLIENT_VISITOR_ONLY"

		and: "两个 jar 的源命名空间同源，但输入 jar 不同、client-only 还多带 common jar 作为 classpath"
		report.SOURCE_NAMESPACE_COMMON == "official"
		report.SOURCE_NAMESPACE_CLIENTONLY == "official"
		report.INPUT_JAR_COMMON != report.INPUT_JAR_CLIENTONLY
		report.TASK_COMMON_REMAP_CLASSPATH_COUNT == "0"
		report.TASK_CLIENTONLY_REMAP_CLASSPATH_COUNT == "1"

		and: "两侧命名空间接线取自被测 provider 自身（任务的 from/to 与 provider 声明一致）"
		report.TASK_COMMON_FROM_NAMESPACE == report.SOURCE_NAMESPACE_COMMON
		report.TASK_COMMON_TO_NAMESPACE == report.TARGET_NAMESPACE
		report.TASK_CLIENTONLY_FROM_NAMESPACE == report.SOURCE_NAMESPACE_CLIENTONLY
		report.TASK_CLIENTONLY_TO_NAMESPACE == report.TARGET_NAMESPACE

		and: "形态开关与 fabric 一致（它们缺省都是 false，漏设不会报错、只会静默产出另一种 jar）"
		report.TASK_COMMON_FORGE_LIKE == "false"
		report.TASK_COMMON_INJECT_MIXIN_EXTENSION == "false"
		report.TASK_COMMON_COPY_ONLY == "false"
		report.TASK_COMMON_FIX_RECORDS == "true"
		report.TASK_COMMON_VALIDATE_TARGET_NAMESPACE == "true"
		report.TASK_CLIENTONLY_FORGE_LIKE == "false"
		report.TASK_CLIENTONLY_INJECT_MIXIN_EXTENSION == "false"
		report.TASK_CLIENTONLY_COPY_ONLY == "false"
		report.TASK_CLIENTONLY_FIX_RECORDS == "true"
		report.TASK_CLIENTONLY_VALIDATE_TARGET_NAMESPACE == "true"

		and: "产物落位：两个 jar 落位不同，各自声明了 pom 与 backup 两个伴随产物"
		report.SUBJECT_JAR_COMMON != report.SUBJECT_JAR_CLIENTONLY
		report.SUBJECT_JAR_COMMON == report.TASK_OUTPUT_JAR_COMMON
		report.SUBJECT_JAR_CLIENTONLY == report.TASK_OUTPUT_JAR_CLIENTONLY
		report.TASK_COMMON_OUTPUT_POM_PRESENT == "true"
		report.TASK_CLIENTONLY_OUTPUT_POM_PRESENT == "true"
		report.TASK_COMMON_OUTPUT_BACKUP_PRESENT == "true"
		report.TASK_CLIENTONLY_OUTPUT_BACKUP_PRESENT == "true"
		new File(report.TASK_COMMON_OUTPUT_BACKUP_JAR).exists()
		new File(report.TASK_CLIENTONLY_OUTPUT_BACKUP_JAR).exists()

		and: "对照写在独立文件上，且对照的输入与任务的输入同源（产物路径上只有任务一个写入者）"
		report.SUBJECT_JAR_COMMON != report.REFERENCE_JAR_COMMON
		report.SUBJECT_JAR_CLIENTONLY != report.REFERENCE_JAR_CLIENTONLY
		report.TASK_INPUT_JAR_COMMON == report.INPUT_JAR_COMMON
		report.TASK_INPUT_JAR_CLIENTONLY == report.INPUT_JAR_CLIENTONLY

		and: "intermediary split（同一处覆写、另一处声明）的接线也按同一判据逐 jar 分开"
		report.INTERMEDIARY_PROVIDER_CLASS.endsWith("IntermediaryMinecraftProvider\$SplitImpl")
		report.INTERMEDIARY_REMAPPED_JAR_COUNT == "2"
		report.INTERMEDIARY_TARGET_NAMESPACE == "intermediary"
		report.INTERMEDIARY_OVERRIDES_HOOK == "true"
		report.INTERMEDIARY_DECLARED_HOOK_KIND == "SPLIT_CLIENT_VISITOR_ONLY"
		report.INTERMEDIARY_EFFECTIVE_HOOK_KIND == "SPLIT_CLIENT_VISITOR_ONLY"
		report.INTERMEDIARY_TASK_COMMON_NAME == TASK_INTERMEDIARY_COMMON
		report.INTERMEDIARY_TASK_CLIENTONLY_NAME == TASK_INTERMEDIARY_CLIENT_ONLY
		report.INTERMEDIARY_TASK_COMMON_TO_NAMESPACE == report.INTERMEDIARY_TARGET_NAMESPACE
		report.INTERMEDIARY_TASK_CLIENTONLY_TO_NAMESPACE == report.INTERMEDIARY_TARGET_NAMESPACE
		report.INTERMEDIARY_TASK_COMMON_INJECT_CLIENT_SIDED_VISITOR == "false"
		report.INTERMEDIARY_TASK_CLIENTONLY_INJECT_CLIENT_SIDED_VISITOR == "true"
		report.INTERMEDIARY_TASK_CLIENTONLY_OUTPUT_INCLUDES_CLIENT == "true"
		// intermediary 与 named 的一处真实差异：前者不产 backup（IntermediaryMinecraftProvider.requiresBackupJars 为 false）
		report.INTERMEDIARY_TASK_COMMON_OUTPUT_BACKUP_PRESENT == "false"
		report.INTERMEDIARY_TASK_CLIENTONLY_OUTPUT_BACKUP_PRESENT == "false"
		intermediaryCommon.taskName == TASK_INTERMEDIARY_COMMON
		intermediaryClientOnly.taskName == TASK_INTERMEDIARY_CLIENT_ONLY
		report.INTERMEDIARY_TASK_COMMON_REMAP_CLASSPATH_COUNT == "0"
		report.INTERMEDIARY_TASK_CLIENTONLY_REMAP_CLASSPATH_COUNT == "1"

		and: "intermediary split：两个 jar 各自与配置期路径逐条目一致"
		intermediaryCommon.sameNames
		intermediaryCommon.missingVsReference.isEmpty()
		intermediaryCommon.referenceCount > 0
		assert intermediaryCommon.vsReference.isEmpty(): "intermediary common jar 的条目内容不一致: ${intermediaryCommon.vsReference.take(20)}（共 ${intermediaryCommon.vsReference.size()} 项）"
		intermediaryClientOnly.sameNames
		intermediaryClientOnly.missingVsReference.isEmpty()
		intermediaryClientOnly.referenceCount > 0
		assert intermediaryClientOnly.vsReference.isEmpty(): "intermediary client-only jar 的条目内容不一致: ${intermediaryClientOnly.vsReference.take(20)}（共 ${intermediaryClientOnly.vsReference.size()} 项）"

		and: "intermediary split：反向排除与 visitor 的产物级效果（@Environment 只在 client-only 上）"
		assert !intermediaryCommon.vsInput.isEmpty(): "intermediary common jar 与它的输入逐字节相同，说明重映射没有生效"
		assert !intermediaryClientOnly.vsInput.isEmpty(): "intermediary client-only jar 与它的输入逐字节相同，说明重映射没有生效"
		intermediaryCommon.subjectAnnotatedClasses == 0
		intermediaryCommon.referenceAnnotatedClasses == 0
		intermediaryClientOnly.subjectClassCount > 0
		intermediaryClientOnly.subjectAnnotatedClasses == intermediaryClientOnly.subjectClassCount
		intermediaryClientOnly.referenceAnnotatedClasses == intermediaryClientOnly.subjectClassCount

		and: "common jar：两侧产出的 entry 名集合完全一致"
		common.subjectCount > 0
		common.referenceCount > 0
		common.sameNames
		common.missingVsReference.isEmpty()

		and: "common jar：每一个 entry 的字节逐一致（任一条目不同即失败）"
		assert common.vsReference.isEmpty(): "common jar 的条目内容不一致: ${common.vsReference.take(20)}（共 ${common.vsReference.size()} 项）"

		and: "client-only jar：两侧产出的 entry 名集合完全一致"
		clientOnly.subjectCount > 0
		clientOnly.referenceCount > 0
		clientOnly.sameNames
		clientOnly.missingVsReference.isEmpty()

		and: "client-only jar：每一个 entry 的字节逐一致——这一份是 SPLIT_CLIENT_VISITOR_ONLY 唯一有定义的那一支"
		assert clientOnly.vsReference.isEmpty(): "client-only jar 的条目内容不一致: ${clientOnly.vsReference.take(20)}（共 ${clientOnly.vsReference.size()} 项）"

		and: "反向排除假通过：两份产物都与各自的输入不同（排除「复制而非重映射」）"
		assert !common.vsInput.isEmpty(): "common jar 与它的输入逐字节相同，说明重映射没有生效"
		assert !clientOnly.vsInput.isEmpty(): "client-only jar 与它的输入逐字节相同，说明重映射没有生效"

		and: "活性检查一（核心）：把 client-only 的 visitor 注入翻成 false，条目名集合不变而字节必须变"
		// 这一条直接证明「那个 visitor 真的被挂上了」：若生产接线把该输入挂在了别的 jar 上、
		// 或压根没接上，「翻转它」不会有任何效果，下面的断言就会失败。
		clientOnly.visitorSameNames
		assert !clientOnly.byteLevelVsPerturbedVisitor.isEmpty(): "翻转 client-only 的 injectClientSidedVisitor 后两侧仍逐条相同，说明该输入对产物无影响（visitor 没被挂上）"

		and: "活性检查一之二：把 common 的 visitor 注入翻成 true，同样条目名集合不变而字节必须变"
		// 两个方向都翻，才排除「只有一个方向恰好不生效」这种假证据。
		common.visitorSameNames
		assert !common.byteLevelVsPerturbedVisitor.isEmpty(): "翻转 common 的 injectClientSidedVisitor 后两侧仍逐条相同，说明该输入对产物无影响"

		and: "visitor 的效果在产物里可直接观察：@Environment 只出现在 client-only 的每一个 class 上"
		// 这一组把「visitor 真的被挂上了」从「字节变了」推进到「变的就是那个注解」：
		// 两个 jar 的 class 数分别为 5460 / 1976，加起来正是 merged 形态的 7436（见
		// RemapMinecraftTaskEquivalenceTest 的说明），即 split 的两个 jar 恰好把 merged 的 class 集合切开。
		common.subjectClassCount > 0
		clientOnly.subjectClassCount > 0
		common.subjectAnnotatedClasses == 0
		clientOnly.subjectAnnotatedClasses == clientOnly.subjectClassCount
		// 配置期对照同样如此——两侧同判据，而不是「任务恰好也产出了同样字节」的巧合
		common.referenceAnnotatedClasses == 0
		clientOnly.referenceAnnotatedClasses == clientOnly.subjectClassCount
		// 翻转后效果对称：common 翻成 true 后它的每个 class 都带上，client-only 翻成 false 后一个都不带
		common.perturbedVisitorAnnotatedClasses == common.subjectClassCount
		clientOnly.perturbedVisitorAnnotatedClasses == 0

		and: "活性检查二：把目标命名空间换成另一个真实命名空间（其余输入完全相同），同一口径必须报出差异"
		!common.namespaceSameNames
		assert !common.vsPerturbedNamespace.isEmpty(): "换掉 common 的目标命名空间后两侧仍逐条相同，说明比对口径是死的"
		!clientOnly.namespaceSameNames
		assert !clientOnly.vsPerturbedNamespace.isEmpty(): "换掉 client-only 的目标命名空间后两侧仍逐条相同，说明比对口径是死的"
	}

	/**
	 * 探针脚本：注册一个执行期的探针任务，在**执行期**按被测 provider 逐 jar 调用配置期调用点，
	 * 并把任务侧接线与两侧产物路径写进报告.
	 *
	 * <p>为什么必须挂在执行期：配置期调用点要读输入 jar（vanilla split 的 common / client-only jar），
	 * 而它们现在由执行期任务产出——配置期（{@code project.afterEvaluate} 里）它们还不存在，冷缓存下必以
	 * {@code FileNotFoundException} 失败。探针任务声明 {@code dependsOn(被测任务)} 来拿到它们全部输入的任务
	 * 依赖（见脚本里的说明）；任务注册（探针任务与四个扰动任务）留在配置期——任务注册本来就不能发生在执行期。
	 *
	 * <p>{@code remapJar} 的入口是 {@code protected}，这里用反射取到它以调用**同一段代码**；构造
	 * {@code RemappedJars} 时只把落位换成探针自己的文件，输入、源命名空间与 classpath 都取自被测 provider。
	 * 两个 jar 各调用一次——split 形态下**只有逐 jar 调用才能同时得到两份对照**。
	 */
	private static String probeScript() {
		return '''
// ==== Fabric split 等价性探针（由 FabricSplitRemapEquivalenceTest 追加） ====
def probeReportFile = project.file('probe-report.properties')

project.afterEvaluate {
	def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
	def provider = loomExt.getNamedMinecraftProvider()
	def remappedJars = provider.getRemappedJars()
	def probeDir = new File(project.layout.buildDirectory.get().asFile, 'probe')
	probeDir.mkdirs()
	// 执行期要用 Project 时只能走这个**配置期捕获的局部变量**：任务动作里直接写 `project` 会被解析成
	// Task.getProject()，Gradle 8 起那是弃用 API（"Invocation of Task.project at execution time has been
	// deprecated"），而内层构建以 --warning-mode fail 运行——弃用警告会把构建直接判失败。
	def probeProject = project
	def lines = []

	// 被测任务名刻意在这里写成字面量，而不是复刻生产代码里的拼接公式：复刻公式会让
	// 「生产代码换了命名」这件事在探针里同步发生，测试侧对任务名的断言随之失去意义。
	def taskNamesByType = ['common': '@TASK_COMMON@', 'clientOnly': '@TASK_CLIENT_ONLY@']
	def suffixByType = ['common': 'Common', 'clientOnly': 'ClientOnly']
	// 逐 jar 的被测任务对象：报告里的接线字段与探针任务的排序都要用它们。一律在**配置期**取好，
	// 执行期不再碰任务容器——任务注册与按名查找都只属于配置期。
	def realTasksByType = taskNamesByType.collectEntries { type, name -> [(type): project.tasks.getByName(name)] }

	def baseClass = Class.forName('net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider')

	// 「钩子事实 + 声明 → 有效形态」：事实靠反射看有没有覆写 configureRemapper，
	// 归约本身调 private 的 resolveRemapperHookKind（与生产代码同一个归约，不在探针里再写一份）
	def hookFacts = { def subjectProvider, String prefix ->
		def overridesHook = false
		def declaredHookKind = null

		for (Class<?> type = subjectProvider.getClass(); type != null && type != baseClass; type = type.superclass) {
			if (type.declaredMethods.any { it.name == 'configureRemapper' && it.parameterCount == 2 }) {
				overridesHook = true
			}
		}

		for (Class<?> type = subjectProvider.getClass(); type != null && declaredHookKind == null; type = type.superclass) {
			def method = type.declaredMethods.find { it.name == 'remapperHookKind' && it.parameterCount == 0 }

			if (method != null) {
				method.setAccessible(true)
				declaredHookKind = method.invoke(subjectProvider).toString()
			}
		}

		def resolveHookKind = baseClass.getDeclaredMethod('resolveRemapperHookKind')
		resolveHookKind.setAccessible(true)
		lines << prefix + '_OVERRIDES_HOOK=' + overridesHook
		lines << prefix + '_DECLARED_HOOK_KIND=' + declaredHookKind
		lines << prefix + '_EFFECTIVE_HOOK_KIND=' + resolveHookKind.invoke(subjectProvider).toString()
	}

	lines << 'PROVIDER_CLASS=' + provider.getClass().name
	lines << 'TARGET_NAMESPACE=' + provider.getTargetNamespace().toString()
	lines << 'REMAPPED_JAR_COUNT=' + remappedJars.size()
	lines << 'REMAPPED_JAR_TYPES=' + remappedJars.collect { it.type().toString() }.join('|')
	lines << 'REMAP_TASK_NAMES=' + project.tasks.names.findAll { it.startsWith('remapMinecraft') }.sort().join('|')
	hookFacts.call(provider, 'PROVIDER')

	// 「配置期那处调用点」本身：AbstractMappedMinecraftProvider.remapJar(RemappedJars, ConfigContext)。
	// 只反射取 protected 入口，参数一律取自被测 provider，不另写一份语义。
	def remapJarMethod = null

	for (Class<?> type = provider.getClass(); type != null && remapJarMethod == null; type = type.superclass) {
		remapJarMethod = type.declaredMethods.find { it.name == 'remapJar' && it.parameterCount == 2 }
	}

	assert remapJarMethod != null
	remapJarMethod.setAccessible(true)

	def remappedJarsClass = Class.forName('net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider$RemappedJars')
	def remappedJarsCtor = remappedJarsClass.getConstructor(
			java.nio.file.Path,
			net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar,
			net.fabricmc.loom.api.mappings.layered.MappingsNamespace,
			java.nio.file.Path[].class)

	// 活性检查：把被测任务的每一个输入原样搬过去，只扰动其中一个维度。
	def perturb = { String perturbationTask, File output, def realTask, Closure mutate ->
		project.tasks.register(perturbationTask, net.fabricmc.loom.pipeline.RemapMinecraftTask) { task ->
			task.getInputJar().set(realTask.getInputJar())
			// MC 侧输入（common / client-only jar）现在由 loom 的任务产出（download → extract → split），
			// 探针沿用被测任务的输入，因此也必须沿用它的任务依赖。漏登记时 Gradle 的配置校验会直接失败，
			// 且错误点名的是**本探针**而不是 loom 自己的任务——后者已由接线侧按产物路径登记了 dependsOn。
			// 这里用 loom 给消费方的正式通道（登记过产出的命名空间集合），而不是硬编码任务名。
			task.dependsOn(loomExt.getMinecraftJarsCollection(net.fabricmc.loom.api.mappings.layered.MappingsNamespace.OFFICIAL))
			task.getRemapClasspath().from(realTask.getRemapClasspath())
			task.getMappingsServiceOptions().set(realTask.getMappingsServiceOptions())
			task.getFromNamespace().set(realTask.getFromNamespace())
			task.getToNamespace().set(realTask.getToNamespace())
			task.getFixRecords().set(realTask.getFixRecords())
			task.getForgeLike().set(realTask.getForgeLike())
			task.getInjectMixinExtension().set(realTask.getInjectMixinExtension())
			task.getInjectClientSidedVisitor().set(realTask.getInjectClientSidedVisitor())
			task.getValidateTargetNamespace().set(realTask.getValidateTargetNamespace())
			task.getInnerClassNames().set(realTask.getInnerClassNames())
			task.getKnownIndyBsms().set(realTask.getKnownIndyBsms())
			task.getSignatureFixes().set(realTask.getSignatureFixes())

			if (realTask.getAnnotationsJson().isPresent()) {
				task.getAnnotationsJson().set(realTask.getAnnotationsJson())
			}

			if (realTask.getObjectHolderClassName().isPresent()) {
				task.getObjectHolderClassName().set(realTask.getObjectHolderClassName())
				task.getObjectHolderSourceNamespace().set(realTask.getObjectHolderSourceNamespace())
			}

			task.getObjectHolderTargetNamespace().set(realTask.getObjectHolderTargetNamespace())
			mutate.call(task)
			task.getOutputJar().set(output)
		}

		return output
	}

	// 扰动任务**必须在配置期注册**（任务注册不能发生在执行期），因此这一段留在 afterEvaluate 里；
	// 对照产出则搬进执行期的探针任务（见下）。扰动任务自身的输入接线一字未改。
	remappedJars.each { remappedJar ->
		def jarType = remappedJar.type().toString()
		def realTask = realTasksByType[jarType]
		perturb.call('equivalencePerturbedClientVisitor' + suffixByType[jarType],
				new File(probeDir, 'perturbed-client-visitor-' + jarType + '.jar'), realTask) { task ->
			task.getInjectClientSidedVisitor().set(!realTask.getInjectClientSidedVisitor().get())
		}
		perturb.call('equivalencePerturbedToNamespace' + suffixByType[jarType],
				new File(probeDir, 'perturbed-to-' + '@PERTURBED_NAMESPACE@' + '-' + jarType + '.jar'), realTask) { task ->
			task.getToNamespace().set('@PERTURBED_NAMESPACE@')
		}
	}

	// 第二个 provider：intermediary split（同一处 configureRemapper 覆写、同一条 configureSplitRemapper
	// 判据的另一处声明）。为了拿到对照，它同样逐 jar 反射调用配置期调用点被切走的那一处；**不**再为它注册
	// 扰动任务——口径的活性与 visitor 的产物级效果已在 named 一侧证明，两者用的是同一个任务类与同一套接线。
	def intermediaryProvider = loomExt.getIntermediaryMinecraftProvider()
	def intermediaryJars = intermediaryProvider == null ? null : intermediaryProvider.getRemappedJars()
	def intermediaryTasksByType = intermediaryProvider == null ? [:] : suffixByType.collectEntries { type, suffix ->
		[(type): project.tasks.getByName('remapMinecraftIntermediary' + suffix)]
	}

	if (intermediaryProvider != null) {
		lines << 'INTERMEDIARY_PROVIDER_CLASS=' + intermediaryProvider.getClass().name
		lines << 'INTERMEDIARY_REMAPPED_JAR_COUNT=' + intermediaryJars.size()
		lines << 'INTERMEDIARY_TARGET_NAMESPACE=' + intermediaryProvider.getTargetNamespace().toString()
		hookFacts.call(intermediaryProvider, 'INTERMEDIARY')
	}

	// ==== 探针本体：必须挂在**执行期** ====
	// 配置期调用点要读输入 jar（vanilla split 的 common / client-only jar），而它现在由执行期任务产出：
	// 冷缓存下配置期它还不存在。这段原先直接写在 afterEvaluate 里（即配置期），于是必以
	// FileNotFoundException .../caches/fabric-loom/1.20.1/minecraft-common.jar 失败——CI 上必现，
	// 本机只是恰好共享 gradle home 里早就有那两个 jar 才一直掩盖着这条路径。
	//
	// 搬进任务动作**不改变调用点本身**：仍是反射调 AbstractMappedMinecraftProvider.remapJar(
	// RemappedJars, ConfigContext)，参数仍取自被测 provider 自己的 getRemappedJars()，只把落位换到
	// 探针自己的文件。对照因此仍是「配置期调用点」的产出，不是它的再实现。
	def probeAction = {
		def serviceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()
		def configContext = new net.fabricmc.loom.configuration.ConfigContextImpl(probeProject, serviceFactory, loomExt)

		try {
			remappedJars.each { remappedJar ->
				def jarType = remappedJar.type().toString()
				def key = jarType.toUpperCase(Locale.ROOT)
				def referenceJar = new File(probeDir, 'configtime-' + jarType + '.jar')
				def perturbedVisitor = new File(probeDir, 'perturbed-client-visitor-' + jarType + '.jar')
				def perturbedNamespace = new File(probeDir, 'perturbed-to-' + '@PERTURBED_NAMESPACE@' + '-' + jarType + '.jar')
				def realTask = realTasksByType[jarType]

				// remapJar 末尾会写 pom（savePom 假设构件目录已存在——配置期路径里它是「先把 jar 写进该目录」
				// 顺带建出来的）。探针把落位改到独立文件后这个前提消失，这里显式补上：它只是让同一段配置期代码
				// 能跑完，不改变任何输入，也不改变被测产物的路径。
				realTask.getOutputPom().get().asFile.parentFile.mkdirs()

				// 只改落位：输入、源命名空间与 classpath 都沿用被测 provider 自己声明的那一份
				def ctorArgs = new Object[4]
				ctorArgs[0] = remappedJar.inputJar()
				ctorArgs[1] = remappedJar.outputJar().forPath(referenceJar.toPath())
				ctorArgs[2] = remappedJar.sourceNamespace()
				ctorArgs[3] = remappedJar.remapClasspath()
				def referenceEntry = remappedJarsCtor.newInstance(ctorArgs)

				try {
					remapJarMethod.invoke(provider, referenceEntry, configContext)
				} catch (java.lang.reflect.InvocationTargetException e) {
					throw new RuntimeException('配置期对照的重映射失败（' + jarType + '）', e.getTargetException())
				}

				lines << 'SOURCE_NAMESPACE_' + key + '=' + remappedJar.sourceNamespace().toString()
				lines << 'INPUT_JAR_' + key + '=' + remappedJar.inputJar().toAbsolutePath().normalize().toString()
				lines << 'SUBJECT_JAR_' + key + '=' + realTask.getOutputJar().get().asFile.absolutePath
				lines << 'REFERENCE_JAR_' + key + '=' + referenceJar.absolutePath
				lines << 'TASK_' + key + '_NAME=' + realTask.getName()
				lines << 'TASK_INPUT_JAR_' + key + '=' + realTask.getInputJar().get().asFile.absolutePath
				lines << 'TASK_OUTPUT_JAR_' + key + '=' + realTask.getOutputJar().get().asFile.absolutePath
				lines << 'TASK_' + key + '_OUTPUT_MERGED=' + remappedJar.outputJar().isMerged()
				lines << 'TASK_' + key + '_OUTPUT_INCLUDES_CLIENT=' + remappedJar.outputJar().includesClient()
				lines << 'TASK_' + key + '_FROM_NAMESPACE=' + realTask.getFromNamespace().get()
				lines << 'TASK_' + key + '_TO_NAMESPACE=' + realTask.getToNamespace().get()
				lines << 'TASK_' + key + '_FORGE_LIKE=' + realTask.getForgeLike().get()
				lines << 'TASK_' + key + '_INJECT_MIXIN_EXTENSION=' + realTask.getInjectMixinExtension().get()
				lines << 'TASK_' + key + '_INJECT_CLIENT_SIDED_VISITOR=' + realTask.getInjectClientSidedVisitor().get()
				lines << 'TASK_' + key + '_COPY_ONLY=' + realTask.getCopyOnly().get()
				lines << 'TASK_' + key + '_FIX_RECORDS=' + realTask.getFixRecords().get()
				lines << 'TASK_' + key + '_VALIDATE_TARGET_NAMESPACE=' + realTask.getValidateTargetNamespace().get()
				lines << 'TASK_' + key + '_INNER_CLASS_COUNT=' + realTask.getInnerClassNames().get().size()
				lines << 'TASK_' + key + '_SIGNATURE_FIX_COUNT=' + realTask.getSignatureFixes().get().size()
				lines << 'TASK_' + key + '_ANNOTATIONS_PRESENT=' + realTask.getAnnotationsJson().isPresent()
				lines << 'TASK_' + key + '_OBJECT_HOLDER_CLASS=' + (realTask.getObjectHolderClassName().isPresent() ? realTask.getObjectHolderClassName().get() : '')
				lines << 'TASK_' + key + '_OBJECT_HOLDER_SOURCE_NAMESPACE=' + (realTask.getObjectHolderSourceNamespace().isPresent() ? realTask.getObjectHolderSourceNamespace().get() : '')
				lines << 'TASK_' + key + '_OBJECT_HOLDER_TARGET_NAMESPACE=' + realTask.getObjectHolderTargetNamespace().get()
				lines << 'TASK_' + key + '_REMAP_CLASSPATH_COUNT=' + realTask.getRemapClasspath().getFiles().size()
				lines << 'TASK_' + key + '_OUTPUT_POM_PRESENT=' + realTask.getOutputPom().isPresent()
				lines << 'TASK_' + key + '_OUTPUT_POM=' + (realTask.getOutputPom().isPresent() ? realTask.getOutputPom().get().asFile.absolutePath : '')
				lines << 'TASK_' + key + '_OUTPUT_BACKUP_PRESENT=' + realTask.getOutputBackupJar().isPresent()
				lines << 'TASK_' + key + '_OUTPUT_BACKUP_JAR=' + (realTask.getOutputBackupJar().isPresent() ? realTask.getOutputBackupJar().get().asFile.absolutePath : '')
				lines << 'PERTURBED_VISITOR_JAR_' + key + '=' + perturbedVisitor.absolutePath
				lines << 'PERTURBED_NAMESPACE_JAR_' + key + '=' + perturbedNamespace.absolutePath
			}

			if (intermediaryProvider != null) {
				intermediaryJars.each { remappedJar ->
					def jarType = remappedJar.type().toString()
					def key = jarType.toUpperCase(Locale.ROOT)
					def referenceJar = new File(probeDir, 'configtime-intermediary-' + jarType + '.jar')
					def realTask = intermediaryTasksByType[jarType]

					// 同 named 一侧：补上配置期路径隐含依赖的构件目录（见上面的说明）
					realTask.getOutputPom().get().asFile.parentFile.mkdirs()

					def ctorArgs = new Object[4]
					ctorArgs[0] = remappedJar.inputJar()
					ctorArgs[1] = remappedJar.outputJar().forPath(referenceJar.toPath())
					ctorArgs[2] = remappedJar.sourceNamespace()
					ctorArgs[3] = remappedJar.remapClasspath()
					def referenceEntry = remappedJarsCtor.newInstance(ctorArgs)

					try {
						remapJarMethod.invoke(intermediaryProvider, referenceEntry, configContext)
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw new RuntimeException('配置期对照的重映射失败（intermediary ' + jarType + '）', e.getTargetException())
					}

					lines << 'INTERMEDIARY_SOURCE_NAMESPACE_' + key + '=' + remappedJar.sourceNamespace().toString()
					lines << 'INTERMEDIARY_INPUT_JAR_' + key + '=' + remappedJar.inputJar().toAbsolutePath().normalize().toString()
					lines << 'INTERMEDIARY_SUBJECT_JAR_' + key + '=' + realTask.getOutputJar().get().asFile.absolutePath
					lines << 'INTERMEDIARY_REFERENCE_JAR_' + key + '=' + referenceJar.absolutePath
					lines << 'INTERMEDIARY_TASK_' + key + '_NAME=' + realTask.getName()
					lines << 'INTERMEDIARY_TASK_' + key + '_INJECT_CLIENT_SIDED_VISITOR=' + realTask.getInjectClientSidedVisitor().get()
					lines << 'INTERMEDIARY_TASK_' + key + '_OUTPUT_MERGED=' + remappedJar.outputJar().isMerged()
					lines << 'INTERMEDIARY_TASK_' + key + '_OUTPUT_INCLUDES_CLIENT=' + remappedJar.outputJar().includesClient()
					lines << 'INTERMEDIARY_TASK_' + key + '_TO_NAMESPACE=' + realTask.getToNamespace().get()
					lines << 'INTERMEDIARY_TASK_' + key + '_REMAP_CLASSPATH_COUNT=' + realTask.getRemapClasspath().getFiles().size()
					lines << 'INTERMEDIARY_TASK_' + key + '_OUTPUT_BACKUP_PRESENT=' + realTask.getOutputBackupJar().isPresent()
				}
			}
		} finally {
			serviceFactory.close()
		}

		lines << 'PERTURBED_TO_NAMESPACE=' + '@PERTURBED_NAMESPACE@'
		probeReportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
	}

	// 探针任务：不声明任何产物 ⇒ 永不 UP-TO-DATE，每次构建都会把报告重写一遍
	def equivalenceProbe = project.tasks.register('@PROBE_TASK@') { task ->
		task.doLast(probeAction)
		// 排在被测任务之后执行：配置期调用点要读的输入是被测任务输入的真子集（同一份 RemappedJars 的
		// inputJar / remapClasspath），让探针跟在被测任务后面，就按生产自己的接线一次性拿到它们全部的任务依赖。
		// 这里刻意不在探针里复刻一套接线：生产接线一变，复刻的那份会静默失真。
		task.dependsOn(realTasksByType.values())
		task.dependsOn(intermediaryTasksByType.values())
		// 另外按生产消费方的通道（登记过任务产出的命名空间集合）补一条直接依赖，让「读的这两个 jar
		// 由谁产出」在 Gradle 的任务模型里是明写的，而不是只靠上面那条间接可达。
		task.dependsOn(loomExt.getMinecraftJarsCollection(net.fabricmc.loom.api.mappings.layered.MappingsNamespace.OFFICIAL))
		realTasksByType.values().each { subjectTask -> task.inputs.file(subjectTask.getInputJar()) }
		intermediaryTasksByType.values().each { subjectTask -> task.inputs.file(subjectTask.getInputJar()) }
	}
}
'''
				.replace('@TASK_COMMON@', TASK_COMMON)
				.replace('@TASK_CLIENT_ONLY@', TASK_CLIENT_ONLY)
				.replace('@PROBE_TASK@', PROBE_TASK)
				.replace('@PERTURBED_NAMESPACE@', PERTURBED_NAMESPACE)
	}

	/**
	 * 载入一个 jar 的「任务产出 / 配置期对照 / 输入」三份产物与内容级判据.
	 *
	 * <p>差异分两类，不能混成一个数：「同名但字节不同」（{@link #byteLevelDiff}）才是「同一条 entry 的内容
	 * 变了」，「对照里缺同名条目」（{@link #missingNames}）也可能是重命名造成的。只有前者能支撑
	 * 「每个 entry 的字节一致」这句结论，所以两者分别记录、分别打印。
	 *
	 * @param prefix 报告键前缀；named 一侧为空串，intermediary 一侧为 {@code INTERMEDIARY_}
	 */
	private static Map<String, Object> loadPairEvidence(Map<String, String> report, String prefix, String key) {
		final File subjectFile = new File(report[prefix + "SUBJECT_JAR_" + key])
		final File referenceFile = new File(report[prefix + "REFERENCE_JAR_" + key])
		final File inputFile = new File(report[prefix + "INPUT_JAR_" + key])
		final Map<String, String> subject = entries(subjectFile)
		final Map<String, String> reference = entries(referenceFile)
		final Map<String, String> input = entries(inputFile)

		return [
			key: key,
			taskName: report[prefix + "TASK_" + key + "_NAME"],
			subjectCount: subject.size(),
			referenceCount: reference.size(),
			inputCount: input.size(),
			subjectClassCount: classCount(subjectFile),
			subjectAnnotatedClasses: annotatedClassCount(subjectFile),
			referenceAnnotatedClasses: annotatedClassCount(referenceFile),
			sameNames: subject.keySet() == reference.keySet(),
			missingVsReference: missingNames(subject, reference),
			vsReference: diff(subject, reference),
			vsInput: diff(subject, input)
		]
	}

	/**
	 * 载入 named split 一个 jar 的全部证据：三份产物加两个扰动产出.
	 *
	 * <p>扰动只有 named 一侧有——口径的活性与 visitor 的产物级效果证一次即可，intermediary 用的是同一个
	 * 任务类与同一套接线，因此 {@link #loadPairEvidence} 的结果就够了。
	 */
	private static Map<String, Object> loadEvidence(Map<String, String> report, String key) {
		final Map<String, Object> result = loadPairEvidence(report, "", key)
		final File subjectFile = new File(report["SUBJECT_JAR_" + key])
		final File perturbedVisitorFile = new File(report["PERTURBED_VISITOR_JAR_" + key])
		final File perturbedNamespaceFile = new File(report["PERTURBED_NAMESPACE_JAR_" + key])
		final Map<String, String> subject = entries(subjectFile)
		final Map<String, String> perturbedVisitor = entries(perturbedVisitorFile)
		final Map<String, String> perturbedNamespace = entries(perturbedNamespaceFile)
		result.perturbedVisitorCount = perturbedVisitor.size()
		result.perturbedNamespaceCount = perturbedNamespace.size()
		result.perturbedVisitorAnnotatedClasses = annotatedClassCount(perturbedVisitorFile)
		result.visitorSameNames = subject.keySet() == perturbedVisitor.keySet()
		result.byteLevelVsPerturbedVisitor = byteLevelDiff(subject, perturbedVisitor)
		result.vsPerturbedVisitor = diff(subject, perturbedVisitor)
		result.namespaceSameNames = subject.keySet() == perturbedNamespace.keySet()
		result.vsPerturbedNamespace = diff(subject, perturbedNamespace)
		return result
	}

	/** {@return {@code actual} 与 {@code expected} 中内容不同的条目名} 要求条目名集合相同. */
	private static List<String> diff(Map<String, String> actual, Map<String, String> expected) {
		return actual.findResults { name, hash -> hash == expected[name] ? null : name }
	}

	/**
	 * {@return 两侧**同名但字节不同**的条目名}.
	 *
	 * <p>与 {@link #diff} 的区别：这里只统计「对照里存在同名条目」的那些，即真正的字节级分歧。
	 */
	private static List<String> byteLevelDiff(Map<String, String> subject, Map<String, String> other) {
		return subject.findResults { name, hash -> other.containsKey(name) && other[name] != hash ? name : null }
	}

	/** {@return 对照里缺少的同名条目名}. */
	private static List<String> missingNames(Map<String, String> subject, Map<String, String> other) {
		return subject.keySet().findAll { !other.containsKey(it) }.toList()
	}

	/** {@return jar 里的 class 条目数}. */
	private static int classCount(File jar) {
		return classNames(jar).size()
	}

	/**
	 * {@return jar 里带类级 {@code @Environment} 注解的 class 条目数}.
	 *
	 * <p>判据是 class 常量池里出现 {@code Lnet/fabricmc/api/Environment;} 这个描述符。**这是一个下界式的
	 * 字节级判据**，不是 ASM 级的精确判定：它会把「在别处引用了该类型的 class」也算进来。对被重映射过的
	 * MC 类这不构成误报（MC 自身的类不引用 {@code net.fabricmc.api.Environment}，该描述符只可能来自
	 * loom 自己注入的注解），故这里用它换取「不必在测试里再引一层 ASM」。
	 */
	private static int annotatedClassCount(File jar) {
		int count = 0

		new ZipFile(jar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (e.directory || !e.name.endsWith(".class")) {
					continue
				}

				byte[] bytes = null

				zip.getInputStream(e).withCloseable { input ->
					bytes = input.readAllBytes()
				}

				if (containsBytes(bytes, ENVIRONMENT_DESCRIPTOR)) {
					count++
				}
			}
		}

		return count
	}

	/** {@return jar 里的 class 条目名}. */
	private static List<String> classNames(File jar) {
		final List<String> result = []

		new ZipFile(jar).withCloseable { zip ->
			for (def e : zip.entries()) {
				if (!e.directory && e.name.endsWith(".class")) {
					result.add(e.name)
				}
			}
		}

		return result
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

	/** 把证据打进测试输出：每个 jar 的条目数、差异数、差异条目名与形态开关. */
	private static void printEvidence(Map<String, String> report, Map<String, Object> common, Map<String, Object> clientOnly,
			Map<String, Object> intermediaryCommon, Map<String, Object> intermediaryClientOnly) {
		println "== Fabric split 等价性证据 =="
		println "provider=${report.PROVIDER_CLASS} 目标命名空间=${report.TARGET_NAMESPACE}（源=${report.SOURCE_NAMESPACE_COMMON}）"
		println "provider 声明的 jar：${report.REMAPPED_JAR_TYPES}（共 ${report.REMAPPED_JAR_COUNT} 个）；remapMinecraft* 任务：${report.REMAP_TASK_NAMES}"
		println "钩子链: 覆写=${report.PROVIDER_OVERRIDES_HOOK} 声明=${report.PROVIDER_DECLARED_HOOK_KIND} 归约后=${report.PROVIDER_EFFECTIVE_HOOK_KIND}"
		printJarEvidence(common, [
			subject: report.SUBJECT_JAR_COMMON,
			reference: report.REFERENCE_JAR_COMMON,
			input: report.INPUT_JAR_COMMON,
			perturbedVisitor: report.PERTURBED_VISITOR_JAR_COMMON,
			perturbedNamespace: report.PERTURBED_NAMESPACE_JAR_COMMON,
			merged: report.TASK_COMMON_OUTPUT_MERGED,
			includesClient: report.TASK_COMMON_OUTPUT_INCLUDES_CLIENT,
			visitor: report.TASK_COMMON_INJECT_CLIENT_SIDED_VISITOR,
			classpath: report.TASK_COMMON_REMAP_CLASSPATH_COUNT
		])
		printJarEvidence(clientOnly, [
			subject: report.SUBJECT_JAR_CLIENTONLY,
			reference: report.REFERENCE_JAR_CLIENTONLY,
			input: report.INPUT_JAR_CLIENTONLY,
			perturbedVisitor: report.PERTURBED_VISITOR_JAR_CLIENTONLY,
			perturbedNamespace: report.PERTURBED_NAMESPACE_JAR_CLIENTONLY,
			merged: report.TASK_CLIENTONLY_OUTPUT_MERGED,
			includesClient: report.TASK_CLIENTONLY_OUTPUT_INCLUDES_CLIENT,
			visitor: report.TASK_CLIENTONLY_INJECT_CLIENT_SIDED_VISITOR,
			classpath: report.TASK_CLIENTONLY_REMAP_CLASSPATH_COUNT
		])
		println "== intermediary split（同一处覆写、另一处声明；只做对照，不做扰动）=="
		println "provider=${report.INTERMEDIARY_PROVIDER_CLASS} 目标命名空间=${report.INTERMEDIARY_TARGET_NAMESPACE}" +
				"（钩子覆写=${report.INTERMEDIARY_OVERRIDES_HOOK} 声明=${report.INTERMEDIARY_DECLARED_HOOK_KIND} 归约后=${report.INTERMEDIARY_EFFECTIVE_HOOK_KIND}）"
		printPairEvidence(intermediaryCommon, [
			subject: report.INTERMEDIARY_SUBJECT_JAR_COMMON,
			merged: report.INTERMEDIARY_TASK_COMMON_OUTPUT_MERGED,
			includesClient: report.INTERMEDIARY_TASK_COMMON_OUTPUT_INCLUDES_CLIENT,
			visitor: report.INTERMEDIARY_TASK_COMMON_INJECT_CLIENT_SIDED_VISITOR,
			classpath: report.INTERMEDIARY_TASK_COMMON_REMAP_CLASSPATH_COUNT
		])
		printPairEvidence(intermediaryClientOnly, [
			subject: report.INTERMEDIARY_SUBJECT_JAR_CLIENTONLY,
			merged: report.INTERMEDIARY_TASK_CLIENTONLY_OUTPUT_MERGED,
			includesClient: report.INTERMEDIARY_TASK_CLIENTONLY_OUTPUT_INCLUDES_CLIENT,
			visitor: report.INTERMEDIARY_TASK_CLIENTONLY_INJECT_CLIENT_SIDED_VISITOR,
			classpath: report.INTERMEDIARY_TASK_CLIENTONLY_REMAP_CLASSPATH_COUNT
		])
	}

	/** 打印单个 jar 的条目数与四组差异数. */
	private static void printJarEvidence(Map<String, Object> evidence, Map<String, String> paths) {
		println "-- ${evidence.key} jar: 任务=${evidence.taskName} 落位=${paths.subject}"
		println "   形态: merged=${paths.merged} 含客户端=${paths.includesClient} clientVisitor=${paths.visitor} classpath=${paths.classpath} 项"
		println "   条目数: subject=${evidence.subjectCount} reference=${evidence.referenceCount} input=${evidence.inputCount} " +
				"perturbedVisitor=${evidence.perturbedVisitorCount} perturbedNamespace=${evidence.perturbedNamespaceCount}" +
				"；其中 class ${evidence.subjectClassCount} 条"
		println "   带 @Environment 的 class: subject=${evidence.subjectAnnotatedClasses} reference=${evidence.referenceAnnotatedClasses} " +
				"perturbedVisitor=${evidence.perturbedVisitorAnnotatedClasses}"
		println "   任务产出 vs 配置期路径: ${evidence.sameNames ? 'entry 名集合一致' : 'entry 名集合不一致'}；" +
				"同名但字节不同 ${evidence.vsReference.size()} 条，对照缺同名条目 ${evidence.missingVsReference.size()} 条 ${evidence.vsReference.take(10)}"
		println "   任务产出 vs 输入（反向排除）: 同名但字节不同 ${evidence.vsInput.size()} 条"
		println "   任务产出 vs 扰动 client visitor（活性检查）: entry 名集合一致=${evidence.visitorSameNames}，同名但字节不同 ${evidence.byteLevelVsPerturbedVisitor.size()} 条 ${evidence.byteLevelVsPerturbedVisitor.take(10)}"
		println "   任务产出 vs 扰动 toNamespace（活性检查）: entry 名集合一致=${evidence.namespaceSameNames}，差异 ${evidence.vsPerturbedNamespace.size()} 条"
	}

	/** 打印只做对照（无扰动）的那个 provider 里单个 jar 的条目数与差异数. */
	private static void printPairEvidence(Map<String, Object> evidence, Map<String, String> paths) {
		println "-- ${evidence.key} jar: 任务=${evidence.taskName} 落位=${paths.subject}"
		println "   形态: merged=${paths.merged} 含客户端=${paths.includesClient} clientVisitor=${paths.visitor} classpath=${paths.classpath} 项"
		println "   条目数: subject=${evidence.subjectCount} reference=${evidence.referenceCount} input=${evidence.inputCount}" +
				"；其中 class ${evidence.subjectClassCount} 条，带 @Environment 的 class: subject=${evidence.subjectAnnotatedClasses} reference=${evidence.referenceAnnotatedClasses}"
		println "   任务产出 vs 配置期路径: ${evidence.sameNames ? 'entry 名集合一致' : 'entry 名集合不一致'}；" +
				"同名但字节不同 ${evidence.vsReference.size()} 条，对照缺同名条目 ${evidence.missingVsReference.size()} 条 ${evidence.vsReference.take(10)}"
		println "   任务产出 vs 输入（反向排除）: 同名但字节不同 ${evidence.vsInput.size()} 条"
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
