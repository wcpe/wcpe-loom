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

package net.fabricmc.loom.test.integration.forge.legacy

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * legacy Forge（FG2 时代，MC 1.12.2）的端到端构建验证。
 *
 * <p>覆盖 MinecraftLegacyPatchedProvider 的完整链路：FG2 manifest 生成、
 * pack200/binpatches 解包、access transform 与最终产物。
 *
 * <p>夹具固定用 1.12.2-14.23.5.2860，即只发布 {@code :userdev3} 的版本，
 * 因此同时覆盖 ForgeUserdevProvider 的 userdev3 → FG2 归一分支。
 */
class LegacyForgeExternalModDependencyTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "build (gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "forge/legacy/externalModDependency")

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS

		where:
		version << STANDARD_TEST_VERSIONS
	}

	/**
	 * userdev3 的归一发生在配置期（依赖解析本身就在 afterEvaluate），
	 * 因此配置缓存必须能完整复用，而不能在后续运行时重新解析或改写。
	 *
	 * <p>夹具的 {@code modLocalRuntime}（JEI）会在首次构建时产出 remapped mod 文件，
	 * 该新文件会让第二次运行的配置缓存失效；输出稳定后第三次必须真正复用同一条目。
	 */
	def "configuration cache is reused"() {
		setup:
		def gradle = gradleProject(project: "forge/legacy/externalModDependency")

		when:
		def result = gradle.run(task: "build")
		def warm = gradle.run(task: "build")
		def reused = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS
		// 后续运行可能是 UP_TO_DATE（输出未变）或 SUCCESS（重跑），只要不是 FAILED 即可
		warm.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		reused.task(":build").outcome in [SUCCESS, UP_TO_DATE]
		reused.output.contains("Configuration cache entry reused.")
	}
}
