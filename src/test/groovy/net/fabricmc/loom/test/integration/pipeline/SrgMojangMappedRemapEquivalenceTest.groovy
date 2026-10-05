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
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * {@code SrgMinecraftProvider} 与 {@code MojangMappedMinecraftProvider} 的**产物内容**等价性验证.
 *
 * <p>{@code RemapMinecraftTaskEquivalenceTest} 只覆盖了「Intermediary → Named」这一条（fabric + merged）。
 * 但被切到任务的映射型 provider 有四个，另外两个是 Forge / NeoForge 专用的命名空间：{@code srg}
 * （{@code SrgMinecraftProvider}）与 {@code mojang}（{@code MojangMappedMinecraftProvider}）。
 * 它们的任务输入与 Named 那条**不同**，所以「Named 等价」推不出「Srg/Mojang 等价」——本测试补这一块。
 *
 * <h2>对照怎么构造</h2>
 * 配置期路径已从 {@code AbstractMappedMinecraftProvider.provide()} 切走，产出不再自动可得，因此本测试
 * 在探针里**手工调用被切走的那一处**：反射调用
 * {@code AbstractMappedMinecraftProvider.remapJar(RemappedJars, ConfigContext)}——即改造前的生产逻辑
 * 本身，不是它的再实现。反射只是为了拿到 {@code protected} 的入口，参数与语义都取自被测 provider 自己的
 * {@code getRemappedJars()}。
 *
 * <p>参照的产出写在 {@code build/probe/configtime-<type>.jar}（用 {@code outputJar().forPath()} 把落位
 * 改到独立文件），因此**被测产物的路径上只有被测任务一个写入者**——不会出现「配置期先写一份、任务再覆盖」
 * 这类别名，否则「两者一致」可以由「读到了同一份文件」伪造出来。
 *
 * <h2>比对口径</h2>
 * 解压后比**每一个 entry 的字节摘要**（SHA-256），entry 名集合也必须一致——不是整文件哈希：
 * 中央目录顺序与时间戳不属于语义。任何一条 entry 的字节不同即判为差异。
 *
 * <h2>反向排除与活性检查</h2>
 * <ul>
 *   <li>反向排除：产物必须与**任务自己的输入 jar**（{@code remappedJars.inputJar()}，即 Forge 打过补丁的
 *       官方 jar）不同——排除「复制而非重映射」这种假通过。</li>
 *   <li>活性检查（两条，各注册一个 {@code RemapMinecraftTask}，把被测任务的每一个输入原样搬过去、
 *       只改一个维度）：
 *       <ul>
 *         <li>打开 {@code injectClientSidedVisitor}：条目名集合必须保持不变而字节必须变，
 *             即口径能识别「名字相同、字节不同」这类最隐蔽的分叉；</li>
 *         <li>把 {@code toNamespace} 换成另一个真实存在的命名空间：必须报出差异，
 *             即 {@code toNamespace} 确实参与产物内容。</li>
 *       </ul>
 *       没有这两条，「任务产出 == 配置期产出」可能只是因为比的是两个都空转的东西。</li>
 * </ul>
 *
 * <h2>与 Named 那条的差异点（本测试逐项断言）</h2>
 * <ul>
 *   <li>{@code getTargetNamespace()} 是 {@code srg} / {@code mojang} 而不是 {@code named}，
 *       {@code getRemappedJars()} 的源命名空间同为 {@code official}；</li>
 *   <li>{@code innerClassNames} **非空**：{@code isForgeLike()} 为真时它是输入 jar 的一列内部类名。
 *       接线侧登记的是**算法**而不是配置期算好的值（它要读输入 jar，而输入 jar 在生产链迁移后由执行期任务
 *       产出），故该输入在执行期才求值；本探针在配置期读它只是为了钉住「这个夹具确实有内部类」，
 *       那次读取不构成对被测实现求值时机的断言（fabric 的 Named 那条两者都是空集）；</li>
 *   <li>{@code objectHolder*} **非空**：{@code isForgeLikeAndOfficial()} 为真（本夹具的 mcp/neoform 配置
 *       都是 {@code official: true}），object holder 改写的类名与源命名空间都参与产物内容；</li>
 *   <li>{@code injectMixinExtension} = {@code isNeoForge()}：Forge 为 false、NeoForge 为 true。
 *       它与 {@code forgeLike} 不是同一件事（「Forge 但非 NeoForge」这一支两者取值不同）；</li>
 *   <li>{@code injectClientSidedVisitor} 恒为 false：本测试覆盖的 {@code MergedImpl} 没有覆写
 *       {@code configureRemapper}（基类钩子是空实现），故归约结果是 {@code RemapperHookKind.NO_OP}
 *       （与声明的默认值 {@code UNKNOWN} 无关，见 {@code resolveRemapperHookKind}）；
 *       唯一覆写了该钩子的 {@code SplitImpl}（已声明 {@code NO_OP}）**无法被构造**，
 *       见 {@code SrgMojangMappedSplitReachabilityTest}。因此本测试只把「本形态下归约结果就是 NO_OP」
 *       钉住，没有为那处覆写的声明提供证据；</li>
 *   <li>{@code copyOnly} / {@code fixRecords} / {@code validateTargetNamespace} 与配置期同判据；</li>
 *   <li>mappings 来源是 {@code MappingOption.forPlatform(extension)}（Forge → {@code WITH_SRG}、
 *       NeoForge → {@code WITH_MOJANG}），两侧同源。</li>
 * </ul>
 *
 * <h2>AT 为什么不在本测试的断言里</h2>
 * {@code AccessTransformerJarProcessor} **不是** {@code RemapMinecraftTask} 的输入：它由
 * {@code CompileConfiguration} 在 {@code isForgeLike()} 时注册进 **named jar 的 processor 链**
 * （{@code ProcessMinecraftJarTask} 那条），而本测试的被测任务是 {@code official → srg/mojang} 的重映射，
 * 其输入是 Forge 打过补丁的**官方** jar。本测试因此只断言「AT processor 确实注册在另一条链上」+
 * 「被测任务的输入不是 named jar」——把「AT 属于哪条链」这件事钉住，而不是假装它在本任务的输入里。
 *
 * <h2>覆盖边界（如实记录）</h2>
 * <ul>
 *   <li>覆盖 **Merged 形态**，Srg 与 MojangMapped 各一次。</li>
 *   <li>**Single jar 与 Split 两个形态没有覆盖**，两者卡住的位置不同：
 *       <ul>
 *         <li>split：**形态本身不可达**（不是本测试的环境问题）。{@code CompileConfiguration.setupMinecraft}
 *             对 forge 系直接拒绝 split 配置，{@code SrgMinecraftProvider.SplitImpl} /
 *             {@code MojangMappedMinecraftProvider.SplitImpl} 因此无法被构造，它们那处「判据恒为 false、
 *             已声明 {@code NO_OP}」的 {@code configureRemapper} 覆写没有等价性证据。取证见
 *             {@code SrgMojangMappedSplitReachabilityTest}。</li>
 *         <li>single jar：**变更本身被 forge 工具链接受**（{@code SingleJarForgeMinecraftProvider} 实现了
 *             {@code ForgeMinecraftProvider}，能过守卫），但**它的输入 jar 在本环境下产不出来**——
 *             Forge/NeoForge 的 client/server binpatch 与 loom 交给它的 jar 校验和不符，配置在
 *             {@code MinecraftPatchedProvider.providePatched} 处中止（{@code Could not produce patched
 *             Minecraft jars}）。手工复现（直接用 userdev 的 binpatcher，退出码 1）给出确切错误——client：
 *             {@code Patch expected com/mojang/blaze3d/pipeline/RenderTarget to have the checksum 86bf42cd
 *             but it was d6407a58}；server：
 *             {@code Patch expected com/mojang/math/Transformation to have the checksum 6a6eb5a9
 *             but it was b77a96f1}；作为对照，merged 的 joined patch 对 srg merged jar 校验和通过
 *             （退出码 0）。这是**输入 jar 生产**上的既有问题、位于被测任务的上游，与本任务切换无关，
 *             也不在本测试的授权范围内。</li>
 *       </ul>
 *       代价必须说清：本测试因此**没有**覆盖「{@code !isMerged() && includesClient()} 为真」的那种 jar，
 *       而那正是与两个 {@code SplitImpl} 的 {@code NO_OP} 声明最相关的形态。</li>
 *   <li>{@code signatureFixes} 与 {@code annotationsJson} 在本夹具下都是**空**：前者的来源是映射构件里的
 *       {@code extras/record_signatures.json}，后者的来源是 {@code extras/annotations.json}，本夹具用到的
 *       yarn / officialMojangMappings / yarn-mappings-patch 构件都不含这两个 extras（实测）。本测试因此把
 *       「空」这个事实断言下来，**没有**证明「非空时两侧一致」。要覆盖非空，需要给夹具加一层
 *       {@code loom.layered { signatureFix(file(...)) }}。</li>
 *   <li>{@code disableObfuscation}、legacy merged 与「链含无法执行期重建的 processor」三种形态已显式排除在
 *       本次任务切换之外（见 {@code verifyProjectable}），本测试不覆盖它们。</li>
 * </ul>
 */
class SrgMojangMappedRemapEquivalenceTest extends Specification implements GradleProjectTestTrait {
	/** Forge 1.20.1 的 srg merged jar 的任务名（见 AbstractMappedMinecraftProvider.taskName）. */
	private static final String SRG_TASK = "remapMinecraftSrgMerged"
	/** NeoForge 1.20.6 的 mojang merged jar 的任务名. */
	private static final String MOJANG_TASK = "remapMinecraftMojangMerged"
	/** 活性检查用的扰动任务名：打开 client visitor 注入. */
	private static final String PERTURBED_VISITOR_TASK = "equivalencePerturbedClientVisitor"
	/** 活性检查用的扰动任务名：替换目标命名空间. */
	private static final String PERTURBED_NAMESPACE_TASK = "equivalencePerturbedToNamespace"
	/** 目标命名空间扰动的取值：另一个真实存在的命名空间. */
	private static final String PERTURBED_NAMESPACE = "intermediary"

	@Unroll
	def "任务产出与配置期路径产出等价： #provider (MC #mcVersion)"() {
		if (neoforgeVersion != "" && Integer.parseInt(System.getProperty("java.version").split("\\.")[0]) < 21) {
			println("本用例需要 Java 21，当前是 ${System.getProperty("java.version")}")
			return
		}

		setup:
		def gradle = gradleProject(project: fixture)
		// 夹具里与平台相关的占位符：替换不存在的占位符是无操作，故两个夹具共用同一段替换
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace("@MCVERSION@", mcVersion)
				.replace("@FORGEVERSION@", forgeVersion)
				.replace("@NEOFORGEVERSION@", neoforgeVersion)
				.replace("@MAPPINGS@", "loom.officialMojangMappings()")
				.replace("@REPOSITORIES@", "")
				.replace("@PACKAGE@", "net.minecraftforge:forge")
				.replace("@JAVA_VERSION@", "17")
				.replace("MAPPINGS", "loom.officialMojangMappings()")
				.replace("PATCHES", "")
		gradle.buildGradle << probeScript(providerGetter, taskName)

		when: "一次构建里既跑被测任务，也跑两个扰动任务；对照来自探针在配置期调用的配置期调用点"
		// 探针在配置期调用配置期调用点（它需要 Gradle 项目模型），故显式关掉配置缓存；
		// 同时关掉构建缓存：本用例要断言被测任务**真的执行了**，而不是从缓存恢复了别的产物
		def build = gradle.run(tasks: [
			taskName,
			PERTURBED_VISITOR_TASK,
			PERTURBED_NAMESPACE_TASK
		],
		configurationCache: false, args: [
			"--console=plain",
			"--no-build-cache"
		])
		def report = parseReport(new File(gradle.projectDir, "probe-report.properties"))
		def subject = entries(new File(report.SUBJECT_JAR))
		def reference = entries(new File(report.REFERENCE_JAR))
		def input = entries(new File(report.INPUT_JAR))
		def perturbedVisitor = entries(new File(report.PERTURBED_VISITOR_JAR))
		def perturbedNamespace = entries(new File(report.PERTURBED_NAMESPACE_JAR))
		def vsReference = diff(subject, reference)
		def vsInput = diff(subject, input)
		def vsPerturbedVisitor = diff(subject, perturbedVisitor)
		def vsPerturbedNamespace = diff(subject, perturbedNamespace)
		printEvidence(provider, report, subject, reference, input, perturbedVisitor, perturbedNamespace)

		then: "被测任务执行了，且它产出的就是消费侧按坐标看到的那份产物"
		build.task(":" + taskName).outcome == SUCCESS
		build.task(":" + PERTURBED_VISITOR_TASK).outcome == SUCCESS
		build.task(":" + PERTURBED_NAMESPACE_TASK).outcome == SUCCESS
		new File(report.SUBJECT_JAR).exists()
		new File(report.REFERENCE_JAR).exists()
		!report.TASK_OUTPUT_POM.isEmpty()
		!report.TASK_OUTPUT_BACKUP.isEmpty()

		and: "对照的输入与产物的输入同源，且对照写在独立文件上（产物路径上只有任务一个写入者）"
		report.TASK_INPUT_JAR == report.INPUT_JAR
		report.SUBJECT_JAR != report.REFERENCE_JAR

		and: "provider 形态：正是被切到任务的那两个命名空间，且本 provider 只有一个 merged jar"
		report.PROVIDER_CLASS.endsWith(providerClassSuffix)
		report.REMAPPED_JAR_COUNT == "1"
		report.TARGET_NAMESPACE == expectedNamespace
		report.TARGET_NAMESPACE != "named"
		report.SOURCE_NAMESPACE == "official"
		report.TASK_FROM_NAMESPACE == report.SOURCE_NAMESPACE
		report.TASK_TO_NAMESPACE == report.TARGET_NAMESPACE
		report.SUBJECT_JAR == report.TASK_OUTPUT_JAR
		report.TASK_OUTPUT_JAR_MERGED == "true"
		report.TASK_OUTPUT_JAR_INCLUDES_CLIENT == "true"

		and: "AT 注册在 named 那条链上，被测任务吃的是打过补丁的官方 jar——AT 不是本任务的输入"
		report.NAMED_CHAIN_PROCESSORS.contains("AccessTransformerJarProcessor")
		report.TASK_INPUT_JAR != report.NAMED_JAR

		and: "Forge 系专有输入：内部类名集合与 object holder 改写都非空（Named 那条两者都是空的）"
		report.TASK_INNER_CLASS_COUNT.toInteger() > 0
		report.TASK_OBJECT_HOLDER_CLASS == expectedObjectHolderClass
		// 源命名空间取 extension.getProductionNamespace()：Forge 1.20.1 是 srg、NeoForge 是 mojang，
		// 恰好就是本 provider 的目标命名空间；目标是字面量 named，与本 provider 的目标无关
		report.TASK_OBJECT_HOLDER_SOURCE_NAMESPACE == report.TARGET_NAMESPACE
		report.TASK_OBJECT_HOLDER_TARGET_NAMESPACE == "named"

		and: "两个形态开关与平台判据一致（它们缺省都是 false，漏设不会报错、只会静默产出另一种 jar）"
		report.TASK_FORGE_LIKE == "true"
		report.TASK_INJECT_MIXIN_EXTENSION == expectedMixinExtension
		report.TASK_COPY_ONLY == "false"
		report.TASK_FIX_RECORDS == "true"
		report.TASK_VALIDATE_TARGET_NAMESPACE == "true"

		and: "visitor 注入：两个形态都没有覆写 configureRemapper，故恒不挂 visitor"
		// 归约规则（resolveRemapperHookKind）：没有覆写 → 基类空实现 → NO_OP，与声明的默认值 UNKNOWN 无关。
		// 这两个 provider 的两个可达形态都落在这一支，因此任务侧 injectClientSidedVisitor 恒为 false。
		report.TASK_INJECT_CLIENT_SIDED_VISITOR == "false"
		report.PROVIDER_OVERRIDES_HOOK == "false"
		report.PROVIDER_DECLARED_HOOK_KIND == "UNKNOWN"

		and: "本夹具下这两个输入都为空——这里只把「空」钉住，非空两侧一致没有证据（见类 javadoc）"
		report.TASK_SIGNATURE_FIX_COUNT == "0"
		report.TASK_ANNOTATIONS_PRESENT == "false"

		and: "两侧产出的 entry 名集合完全一致"
		!subject.isEmpty()
		!reference.isEmpty()
		subject.keySet() == reference.keySet()

		and: "每一个 entry 的字节逐一致（任一条目不同即失败）"
		assert vsReference.isEmpty(): "条目内容不一致: ${vsReference.take(20)}（共 ${vsReference.size()} 项）"

		and: "反向排除假通过：产物确实与它的输入不同（排除「复制而非重映射」）"
		assert !vsInput.isEmpty(): "产物与输入逐字节相同，说明重映射没有生效"

		and: "活性检查一：只把 client visitor 注入打开（其余输入完全相同），同一口径必须报出差异"
		// 这一条的差异必须是**纯字节**的：条目名集合保持一致，且必须存在「同名但字节不同」的条目，
		// 否则「报出差异」可能只是名字变了。它同时是下面 TASK_INJECT_CLIENT_SIDED_VISITOR == false
		// 那条断言的反证——若该输入是死的，「false」就无从谈起。
		perturbedVisitor.keySet() == subject.keySet()
		def byteLevelVsVisitor = byteLevelDiff(subject, perturbedVisitor)
		assert !byteLevelVsVisitor.isEmpty(): "打开 client visitor 注入后两侧仍逐条相同（或只差条目标名），说明口径识别不了「名字相同、字节不同」"

		and: "活性检查二：只把目标命名空间换成另一个真实存在的命名空间（其余输入完全相同），同一口径必须报出差异"
		// 这一条会重命名类，故条目名集合本来就应当不同；这里只断言差异非空
		perturbedNamespace.keySet() != subject.keySet()
		assert !vsPerturbedNamespace.isEmpty(): "换掉目标命名空间后两侧仍逐条相同，说明比对口径是死的"

		where:
		// 两个 provider 的 merged 形态。其余形态（single jar、split）在本次环境下不可达，
		// 理由与取证见类 javadoc 的「覆盖边界」。
		provider | fixture         | mcVersion | forgeVersion | neoforgeVersion | providerGetter                     | taskName    | providerClassSuffix                         | expectedNamespace | expectedObjectHolderClass                                | expectedMixinExtension
		"srg"    | "forge/simple"  | "1.20.1"  | "47.2.1"     | ""              | "getSrgMinecraftProvider"          | SRG_TASK    | "SrgMinecraftProvider\$MergedImpl"          | "srg"             | "net.minecraftforge.registries.ObjectHolderRegistry"     | "false"
		"mojang" | "neoforge/1206" | "1.20.6"  | ""           | "20.6.5-beta"   | "getMojangMappedMinecraftProvider" | MOJANG_TASK | "MojangMappedMinecraftProvider\$MergedImpl" | "mojang"          | "net.neoforged.neoforge.registries.ObjectHolderRegistry" | "true"
	}

	/**
	 * 探针脚本：在配置期调用配置期调用点，并把任务输入与对照路径写进报告.
	 *
	 * <p>{@code remapJar} 的入口是 {@code protected}，这里用反射取到它以调用**同一段代码**；构造
	 * {@code RemappedJars} 时只把落位换成探针自己的文件，输入、源命名空间与 classpath 都取自被测 provider。
	 */
	private static String probeScript(String providerGetter, String taskName) {
		return '''
// ==== 等价性探针（由 SrgMojangMappedRemapEquivalenceTest 追加） ====
def probeReportFile = project.file('probe-report.properties')

project.afterEvaluate {
	def loomExt = net.fabricmc.loom.LoomGradleExtension.get(project)
	def provider = loomExt.@PROVIDER_GETTER@()
	def remappedJars = provider.getRemappedJars()
	def probeDir = new File(project.layout.buildDirectory.get().asFile, 'probe')
	probeDir.mkdirs()

	// 「配置期那处调用点」本身：AbstractMappedMinecraftProvider.remapJar(RemappedJars, ConfigContext)。
	// 只反射取 protected 入口，参数一律取自被测 provider，不另写一份语义。
	def remapJarMethod = null

	for (Class<?> type = provider.getClass(); type != null && remapJarMethod == null; type = type.superclass) {
		remapJarMethod = type.declaredMethods.find { it.name == 'remapJar' && it.parameterCount == 2 }
	}

	assert remapJarMethod != null
	remapJarMethod.setAccessible(true)

	// 只改落位：输入、源命名空间与 classpath 都沿用被测 provider 自己声明的那一份
	def remappedJarsClass = Class.forName('net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider$RemappedJars')
	def remappedJarsCtor = remappedJarsClass.getConstructor(
			java.nio.file.Path,
			net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar,
			net.fabricmc.loom.api.mappings.layered.MappingsNamespace,
			java.nio.file.Path[].class)

	def lines = []
	lines << 'PROVIDER_CLASS=' + provider.getClass().name
	lines << 'TARGET_NAMESPACE=' + provider.getTargetNamespace().toString()
	lines << 'REMAPPED_JAR_COUNT=' + remappedJars.size()
	lines << 'NAMED_CHAIN_PROCESSORS=' + loomExt.getMinecraftJarProcessors().get().collect { it.getClass().name }.join('|')
	lines << 'NAMED_JAR=' + loomExt.getMinecraftJars(net.fabricmc.loom.api.mappings.layered.MappingsNamespace.NAMED)[0].toAbsolutePath().normalize().toString()

	// 本 provider 上 configureRemapper 覆写的形态：反射只能确认「覆写了没有」，覆写体干了什么由 remapperHookKind() 声明。
	// 这里把「事实」与「声明」两个原始值都报出来，供测试核对归约规则（resolveRemapperHookKind）。
	def baseClass = Class.forName('net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider')
	def overridesHook = false
	def declaredHookKind = null

	for (Class<?> type = provider.getClass(); type != null && type != baseClass; type = type.superclass) {
		if (type.declaredMethods.any { it.name == 'configureRemapper' && it.parameterCount == 2 }) {
			overridesHook = true
		}
	}

	for (Class<?> type = provider.getClass(); type != null && declaredHookKind == null; type = type.superclass) {
		def method = type.declaredMethods.find { it.name == 'remapperHookKind' && it.parameterCount == 0 }

		if (method != null) {
			method.setAccessible(true)
			declaredHookKind = method.invoke(provider).toString()
		}
	}

	lines << 'PROVIDER_OVERRIDES_HOOK=' + overridesHook
	lines << 'PROVIDER_DECLARED_HOOK_KIND=' + declaredHookKind

	def first = remappedJars[0]
	def referenceJar = new File(probeDir, 'configtime-' + first.type().toString() + '.jar')

	// remapJar 末尾会写 pom，而 savePom 假设 maven 构件目录已存在——配置期路径里它是
	// 「先把 jar 写进该目录」顺带建出来的。探针把落位改到独立文件后这个前提消失，必须显式补上。
	// 取的是**被测任务真实产出**的 pom 父目录，而不是按 RemappedJars 反推：后者在配置期
	// 拿不到（outputJar().forPath() 与 outputJar().getPath() 都会抛 NPE）。
	// 少了这一步，暖缓存下会因共享 gradle home 里恰有该目录而侥幸通过，**冷缓存/CI 下必以
	// NoSuchFileException ... .pom*.tmp 失败**——而 CI 不缓存 loom 产物目录。
	def subjectPom = project.tasks.getByName('@TASK_NAME@').getOutputPom()

	if (subjectPom.isPresent()) {
		subjectPom.get().asFile.parentFile.mkdirs()
	}

	// 已知隐患（未修，故意保留原状）：remapJar 末尾的 savePom 假设 maven 构件目录已存在
	// （配置期路径里它是「先把 jar 写进该目录」顺带建出来的）；探针把落位改到独立文件后
	// 这个前提消失。当前用例仅因共享 gradle home 里恰有该目录而通过，**冷缓存/CI 下可能以
	// NoSuchFileException ... .pom*.tmp 失败**。修法见 FabricSplitRemapEquivalenceTest：
	// 取被测任务真实产物的 pom 父目录 mkdirs。此处没有照搬是因为按 RemappedJars 反推路径
	// 会拿到 null（试过 outputJar().forPath() 与 outputJar().getPath()，均在配置期抛 NPE）。

	def ctorArgs = new Object[4]
	ctorArgs[0] = first.inputJar()
	ctorArgs[1] = first.outputJar().forPath(referenceJar.toPath())
	ctorArgs[2] = first.sourceNamespace()
	ctorArgs[3] = first.remapClasspath()
	def referenceEntry = remappedJarsCtor.newInstance(ctorArgs)

	def serviceFactory = new net.fabricmc.loom.util.service.ScopedServiceFactory()

	try {
		def callArgs = new Object[2]
		callArgs[0] = referenceEntry
		callArgs[1] = new net.fabricmc.loom.configuration.ConfigContextImpl(project, serviceFactory, loomExt)
		remapJarMethod.invoke(provider, callArgs)
	} catch (java.lang.reflect.InvocationTargetException e) {
		throw new RuntimeException('配置期对照的重映射失败', e.getTargetException())
	} finally {
		serviceFactory.close()
	}

	def realTask = project.tasks.getByName('@TASK_NAME@')

	lines << 'SOURCE_NAMESPACE=' + first.sourceNamespace().toString()
	lines << 'INPUT_JAR=' + first.inputJar().toAbsolutePath().normalize().toString()
	lines << 'SUBJECT_JAR=' + realTask.getOutputJar().get().asFile.absolutePath
	lines << 'REFERENCE_JAR=' + referenceJar.absolutePath
	lines << 'TASK_NAME=' + realTask.getName()
	lines << 'TASK_INPUT_JAR=' + realTask.getInputJar().get().asFile.absolutePath
	lines << 'TASK_OUTPUT_JAR=' + realTask.getOutputJar().get().asFile.absolutePath
	lines << 'TASK_OUTPUT_JAR_MERGED=' + first.outputJar().isMerged()
	lines << 'TASK_OUTPUT_JAR_INCLUDES_CLIENT=' + first.outputJar().includesClient()
	lines << 'TASK_OUTPUT_POM=' + (realTask.getOutputPom().isPresent() ? realTask.getOutputPom().get().asFile.absolutePath : '')
	lines << 'TASK_OUTPUT_BACKUP=' + (realTask.getOutputBackupJar().isPresent() ? realTask.getOutputBackupJar().get().asFile.absolutePath : '')
	lines << 'TASK_FROM_NAMESPACE=' + realTask.getFromNamespace().get()
	lines << 'TASK_TO_NAMESPACE=' + realTask.getToNamespace().get()
	lines << 'TASK_FORGE_LIKE=' + realTask.getForgeLike().get()
	lines << 'TASK_INJECT_MIXIN_EXTENSION=' + realTask.getInjectMixinExtension().get()
	lines << 'TASK_INJECT_CLIENT_SIDED_VISITOR=' + realTask.getInjectClientSidedVisitor().get()
	lines << 'TASK_COPY_ONLY=' + realTask.getCopyOnly().get()
	lines << 'TASK_FIX_RECORDS=' + realTask.getFixRecords().get()
	lines << 'TASK_VALIDATE_TARGET_NAMESPACE=' + realTask.getValidateTargetNamespace().get()
	// 内部类名集合不再是一个「配置期接线的输入」：它由任务在执行期从 (inputJar, forgeLike) 现算
	// （见 RemapMinecraftTask.resolveInnerClassNames）。接线侧若给它挂惰性 provider，配置缓存写入
	// 任务状态时会求值它，冷缓存下输入 jar 尚不存在，整次配置缓存写入直接失败；而若让它吞掉缺失
	// 返回空集，序列化下来的就是与 jar 内容脱钩的陈旧值。故只能在执行期现算。
	// 探针于是在配置期按同一条函数关系自行现算被断言的那个量——断言守的仍是同一件事：
	// Forge 系的输入 jar 确实含内部类，Named 那条（forgeLike=false）不含。
	def innerClassCount = 0

	if (realTask.getForgeLike().get()) {
		def taskInputJar = realTask.getInputJar().get().asFile

		if (taskInputJar.isFile()) {
			innerClassCount = dev.architectury.loom.forge.InnerClassRemapper
					.readClassNames(taskInputJar.toPath())
					.size()
		}
	}

	lines << 'TASK_INNER_CLASS_COUNT=' + innerClassCount
	lines << 'TASK_SIGNATURE_FIX_COUNT=' + realTask.getSignatureFixes().get().size()
	lines << 'TASK_ANNOTATIONS_PRESENT=' + realTask.getAnnotationsJson().isPresent()
	lines << 'TASK_OBJECT_HOLDER_CLASS=' + (realTask.getObjectHolderClassName().isPresent() ? realTask.getObjectHolderClassName().get() : '')
	lines << 'TASK_OBJECT_HOLDER_SOURCE_NAMESPACE=' + (realTask.getObjectHolderSourceNamespace().isPresent() ? realTask.getObjectHolderSourceNamespace().get() : '')
	lines << 'TASK_OBJECT_HOLDER_TARGET_NAMESPACE=' + (realTask.getObjectHolderTargetNamespace().isPresent() ? realTask.getObjectHolderTargetNamespace().get() : '')
	lines << 'TASK_REMAP_CLASSPATH_COUNT=' + realTask.getRemapClasspath().getFiles().size()

	// 活性检查：把被测任务的每一个输入原样搬过来，只扰动其中一个维度。两条各回答一个问题：
	// 口径能不能识别「条目名相同、字节不同」，以及 toNamespace 是否真的参与产物内容。
	def perturb = { String perturbationTask, String outputName, Closure mutate ->
		def output = new File(probeDir, outputName)

		project.tasks.register(perturbationTask, net.fabricmc.loom.pipeline.RemapMinecraftTask) { task ->
			task.getInputJar().set(realTask.getInputJar())
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

	def perturbedVisitor = perturb.call('@PERTURBED_VISITOR_TASK@', 'perturbed-client-visitor.jar') { task ->
		task.getInjectClientSidedVisitor().set(true)
	}
	def perturbedNamespace = perturb.call('@PERTURBED_NAMESPACE_TASK@', 'perturbed-to-' + '@PERTURBED_NAMESPACE@' + '.jar') { task ->
		task.getToNamespace().set('@PERTURBED_NAMESPACE@')
	}

	lines << 'PERTURBED_VISITOR_JAR=' + perturbedVisitor.absolutePath
	lines << 'PERTURBED_NAMESPACE_JAR=' + perturbedNamespace.absolutePath
	lines << 'PERTURBED_TO_NAMESPACE=' + '@PERTURBED_NAMESPACE@'
	probeReportFile.text = lines.join(System.lineSeparator()) + System.lineSeparator()
}
'''
				.replace('@PROVIDER_GETTER@', providerGetter)
				.replace('@TASK_NAME@', taskName)
				.replace('@PERTURBED_VISITOR_TASK@', PERTURBED_VISITOR_TASK)
				.replace('@PERTURBED_NAMESPACE_TASK@', PERTURBED_NAMESPACE_TASK)
				.replace('@PERTURBED_NAMESPACE@', PERTURBED_NAMESPACE)
	}

	/** {@return {@code actual} 与 {@code expected} 中内容不同的条目名} 条目名集合相同是调用方的前提. */
	private static List<String> diff(Map<String, String> actual, Map<String, String> expected) {
		return actual.findResults { name, hash -> hash == expected[name] ? null : name }
	}

	/**
	 * {@return 两侧**同名但字节不同**的条目名}.
	 *
	 * <p>与 {@link #diff} 的区别：这里只统计「对照里存在同名条目」的那些，即真正的字节级分歧；
	 * {@code diff} 还把「对照里根本没有这个条目」一并算作不同。比对口径要证明「每一个 entry 的字节一致」，
	 * 因此这一族条目才是关键的那一族。
	 */
	private static List<String> byteLevelDiff(Map<String, String> subject, Map<String, String> other) {
		return subject.findResults { name, hash -> other.containsKey(name) && other[name] != hash ? name : null }
	}

	/** {@return 对照里缺少的同名条目名}. */
	private static List<String> missingNames(Map<String, String> subject, Map<String, String> other) {
		return subject.keySet().findAll { !other.containsKey(it) }.toList()
	}

	/** 把证据打进测试输出：条目数、差异数、差异条目名与任务输入. */
	private static void printEvidence(String provider, Map<String, String> report, Map<String, String> subject,
			Map<String, String> reference, Map<String, String> input, Map<String, String> perturbedVisitor,
			Map<String, String> perturbedNamespace) {
		println "== ${provider} provider 等价性证据 =="
		println "provider=${report.PROVIDER_CLASS} 目标命名空间=${report.TARGET_NAMESPACE}（源=${report.SOURCE_NAMESPACE}）"
		println "产物形态: merged=${report.TASK_OUTPUT_JAR_MERGED} 含客户端=${report.TASK_OUTPUT_JAR_INCLUDES_CLIENT}"
		println "钩子事实: 覆写=${report.PROVIDER_OVERRIDES_HOOK} 声明=${report.PROVIDER_DECLARED_HOOK_KIND} 任务侧注入=${report.TASK_INJECT_CLIENT_SIDED_VISITOR}"
		println "条目数: subject=${subject.size()} reference=${reference.size()} input=${input.size()} " +
				"perturbedVisitor=${perturbedVisitor.size()} perturbedNamespace=${perturbedNamespace.size()}"
		println "任务产出 vs 配置期路径: ${describe(subject, reference)}"
		println "任务产出 vs 输入（反向排除）: ${describe(subject, input)}"
		println "任务产出 vs 扰动 client visitor（活性检查）: ${describe(subject, perturbedVisitor)}"
		println "任务产出 vs 扰动 toNamespace=${report.PERTURBED_TO_NAMESPACE}（活性检查）: ${describe(subject, perturbedNamespace)}"
		println "内部类名集合 ${report.TASK_INNER_CLASS_COUNT} 项；签名修复 ${report.TASK_SIGNATURE_FIX_COUNT} 项；注解数据存在=${report.TASK_ANNOTATIONS_PRESENT}"
		println "object holder: ${report.TASK_OBJECT_HOLDER_CLASS} (${report.TASK_OBJECT_HOLDER_SOURCE_NAMESPACE} -> ${report.TASK_OBJECT_HOLDER_TARGET_NAMESPACE})"
		println "形态开关: forgeLike=${report.TASK_FORGE_LIKE} mixinExtension=${report.TASK_INJECT_MIXIN_EXTENSION} clientVisitor=${report.TASK_INJECT_CLIENT_SIDED_VISITOR} copyOnly=${report.TASK_COPY_ONLY}"
		println "named 链上的 processor: ${report.NAMED_CHAIN_PROCESSORS}"
	}

	/**
	 * {@return 一次比对的口径描述}.
	 *
	 * <p>刻意把两类分开报：**同名但字节不同**才是「同一条 entry 的内容变了」，而「对照里缺同名条目」
	 * 也可能是重命名造成的。只有前者能支撑「每个 entry 的字节一致」这句结论，所以两者不能混成一个数。
	 */
	private static String describe(Map<String, String> subject, Map<String, String> other) {
		final List<String> byteLevel = byteLevelDiff(subject, other)
		final List<String> missing = missingNames(subject, other)
		final List<String> sample = (byteLevel + missing).take(10)
		return "被测产物 ${subject.size()} 条中 ${byteLevel.size() + missing.size()} 条与对照不同" +
				"（同名但字节不同 ${byteLevel.size()} 条，对照中缺同名条目 ${missing.size()} 条）${sample}"
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
