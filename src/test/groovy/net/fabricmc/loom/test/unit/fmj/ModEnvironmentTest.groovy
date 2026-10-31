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

package net.fabricmc.loom.test.unit.fmj

import spock.lang.Specification

import net.fabricmc.loom.util.fmj.ModEnvironment

/**
 * 声明环境与「当前上下文包含哪一侧」的匹配规则.
 *
 * <p>一个 mod 可以声明多个 class tweaker，每条各带环境；能声明多个之后，
 * 「客户端声明不能在只有服务端的上下文里生效」就从约束变成了必须被验证的行为。
 */
class ModEnvironmentTest extends Specification {
	def "环境匹配"(ModEnvironment environment, boolean client, boolean server) {
		expect:
		environment.appliesTo(client, server) == expected

		where:
		environment              | client | server || expected
		// 合并 jar（两侧都在）——全部生效
		ModEnvironment.UNIVERSAL | true   | true   || true
		ModEnvironment.CLIENT    | true   | true   || true
		ModEnvironment.SERVER    | true   | true   || true
		// 只有客户端——服务端声明不得串过来
		ModEnvironment.UNIVERSAL | true   | false  || true
		ModEnvironment.CLIENT    | true   | false  || true
		ModEnvironment.SERVER    | true   | false  || false
		// 只有服务端——客户端声明不得串过来
		ModEnvironment.UNIVERSAL | false  | true   || true
		ModEnvironment.CLIENT    | false  | true   || false
		ModEnvironment.SERVER    | false  | true   || true
	}
}
