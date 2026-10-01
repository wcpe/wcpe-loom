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

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * {@code SrgMinecraftProvider.SplitImpl} 与 {@code MojangMappedMinecraftProvider.SplitImpl} **不可达**的取证.
 *
 * <h2>为什么需要这个文件</h2>
 * 这两个 {@code SplitImpl} 各自覆写了 {@code configureRemapper}，且把覆写按
 * {@code RemapperHookKind.NO_OP} 声明（理由是判据
 * {@code remappedJars.outputJar().equals(getClientOnlyJar())} 恒为 false——{@code MinecraftJar} 未覆写
 * {@code equals}，而 {@code getClientOnlyJar()} 每次调用都新建实例）。
 *
 * <p>{@code SrgMojangMappedRemapEquivalenceTest} 覆盖了 merged 与 single jar 两个形态，但**没有**覆盖
 * split：不是漏测，而是 forge 系根本不允许 split jar。本文件把这件事变成一条断言，理由有两条：
 * <ul>
 *   <li>它是「为什么那处声明没有等价性证据」的答案——不写下来，下一个读代码的人只会看到一处
 *       「已证明是空操作」的声明而找不到证据；</li>
 *   <li>它是一条**活**的守卫：若哪天 forge 系放开了 split，本用例会立刻失败，那时
 *       {@code SplitImpl} 的钩子声明就变成可观察的了，必须补上真正的覆盖。</li>
 * </ul>
 *
 * <h2>断言为什么是「同一夹具 + 只差 split」</h2>
 * 同一份夹具先在不加 split 时配置一次（必须成功），再加 split 配置一次（必须失败）。
 * 两次只差一行 {@code splitMinecraftJar()}，因此失败的原因只能是 split 本身，
 * 而不是夹具或环境。失败信息里同时断言异常类型与那句守卫文案，避免把别的配置错误当成这条守卫。
 *
 * <h2>这条守卫在哪</h2>
 * {@code CompileConfiguration.setupMinecraft}：
 * {@code if (extension.isForgeLike() && !(minecraftProvider instanceof ForgeMinecraftProvider))} 时直接抛
 * {@code UnsupportedOperationException("Using %s with split jars is not supported!")}。
 * split 的 minecraftProvider 是 {@code SplitMinecraftProvider}（非 forge 实现），故必然命中；
 * merged 与 single jar 的 provider 都实现了 {@code ForgeMinecraftProvider}，都能过这条守卫
 * （single jar 另有一处与本测试无关的问题——它的输入 jar 产不出来，见
 * {@code SrgMojangMappedRemapEquivalenceTest} 的「覆盖边界」）。
 */
class SrgMojangMappedSplitReachabilityTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "Forge 系 split 配置在配置期被拒绝，因此 #platformName 的 SplitImpl 不可达"() {
		if (neoforgeVersion != "" && Integer.parseInt(System.getProperty("java.version").split("\\.")[0]) < 21) {
			println("本用例需要 Java 21，当前是 ${System.getProperty("java.version")}")
			return
		}

		setup:
		def plain = gradleProject(project: fixture)
		prepare(plain, mcVersion, forgeVersion, neoforgeVersion)
		def split = gradleProject(project: fixture)
		prepare(split, mcVersion, forgeVersion, neoforgeVersion)
		// 唯一的一处差别
		split.buildGradle << "\nloom {\n\tsplitMinecraftJar()\n}\n"

		when:
		def ok = plain.run(task: "help", configurationCache: false, args: ["--console=plain"])
		def rejected = split.run(task: "help", expectFailure: true, configurationCache: false, args: ["--console=plain"])

		then: "同一份夹具在不加 split 时能正常配置——失败的原因只能是 split，不是夹具或环境"
		ok.task(":help").outcome == SUCCESS

		and: "加了 split 之后配置期就被拒绝，且拒绝者正是那条守卫"
		rejected.output.contains("Using " + platformName + " with split jars is not supported!")
		rejected.output.contains("java.lang.UnsupportedOperationException")

		where:
		// 两个 provider 的 SplitImpl 都不可达，故两个平台都取一次证：守卫文案随平台变化
		fixture         | platformName | mcVersion | forgeVersion | neoforgeVersion
		"forge/simple"  | "Forge"      | "1.20.1"  | "47.2.1"     | ""
		"neoforge/1206" | "NeoForge"   | "1.20.6"  | ""           | "20.6.5-beta"
	}

	/** 夹具里与平台相关的占位符：替换不存在的占位符是无操作，故两个夹具共用同一段替换. */
	private static void prepare(GradleProjectTestTrait.GradleProject gradle, String mcVersion, String forgeVersion, String neoforgeVersion) {
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
	}
}
