/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

package net.fabricmc.loom.test.unit.processor

import spock.lang.Specification

import net.fabricmc.loom.configuration.accesswidener.ModAccessWidenerEntry
import net.fabricmc.loom.util.fmj.FabricModJson
import net.fabricmc.loom.util.fmj.FabricModJsonSource
import net.fabricmc.loom.util.fmj.ModEnvironment

class ModAccessWidenerEntryTest extends Specification {
	def "read local mod"() {
		given:
		def aw = "accessWidener\tv2\tnamed\n"
		def mod = Mock(FabricModJson.Mockable)
		mod.getClassTweakers() >> ["test.accesswidener": ModEnvironment.UNIVERSAL]
		// 规则文件内容在**建条目时**就取出来（条目要能进配置缓存，不能再带着 FabricModJson，
		// 理由见 ModAccessWidenerEntry 的注释），所以这里必须给出可读的来源。
		mod.getSource() >> ({ String path -> aw.getBytes() } as FabricModJsonSource)

		when:
		def entries = ModAccessWidenerEntry.readAll(mod, true)
		then:
		entries.size() == 1
		def entry = entries[0]

		entry.path() == "test.accesswidener"
		entry.environment() == ModEnvironment.UNIVERSAL
		entry.transitiveOnly()
		entry.modId() == null
		// 身份判据：这个哈希是 spec 指纹，产物路径与缓存值（getJarHash）由它派生，所以条目不再持有
		// FabricModJson 之后它必须逐位不变。改造前它来自 record 自动生成的实现
		// （对 [FabricModJson.hashCode(), path, environment, transitiveOnly] 做「从 0 开始」的 31 折），
		// 本次改造把它显式写出来复现。
		// 注意别用 Objects.hash(...) 去复现：「从 1 开始」的写法会得到 -1218057875，与本期望值不同。
		entry.hashCode() == -1218981396
	}
}
