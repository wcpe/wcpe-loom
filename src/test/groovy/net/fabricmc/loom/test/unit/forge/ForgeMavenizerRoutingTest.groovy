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

package net.fabricmc.loom.test.unit.forge

import dev.architectury.loom.forge.dependency.ForgeProvider
import dev.architectury.loom.forge.dependency.ForgeProvider.ForgeVersion
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider.Type
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionContainer
import spock.lang.Specification

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider

class ForgeMavenizerRoutingTest extends Specification {
	def '仅已验证的混淆 Forge 合包启用 Mavenizer：#profile'() {
		given:
		ForgeProvider forge = Stub(ForgeProvider) {
			getVersion() >> new ForgeVersion(forgeVersion)
		}
		LoomGradleExtension loom = Stub(LoomGradleExtension) {
			isForge() >> forgePlatform
			isLegacyForge() >> legacy
			disableObfuscation() >> noRemap
			isUnobfuscatedForge() >> unobfuscated
			getForgeProvider() >> forge
		}
		ExtensionContainer extensions = Stub(ExtensionContainer) {
			getByName('loom') >> loom
		}
		Project project = Stub(Project) {
			getExtensions() >> extensions
		}
		MinecraftProvider minecraft = Stub(MinecraftProvider) {
			minecraftVersion() >> gameVersion
		}

		expect:
		new MinecraftPatchedProvider(project, minecraft, type).usesMavenizer() == enabled

		where:
		profile         | gameVersion | forgeVersion          | type             | forgePlatform | legacy | noRemap | unobfuscated || enabled
		'目标混淆合包'        | '1.21.11'   | '1.21.11-61.2.5'      | Type.MERGED      | true          | false  | false   | false        || true
		'仅客户端'          | '1.21.11'   | '1.21.11-61.2.5'      | Type.CLIENT_ONLY | true          | false  | false   | false        || false
		'仅服务端'          | '1.21.11'   | '1.21.11-61.2.5'      | Type.SERVER_ONLY | true          | false  | false   | false        || false
		'其他 Forge 构建'   | '1.21.11'   | '1.21.11-61.2.4'      | Type.MERGED      | true          | false  | false   | false        || false
		'1.20.1 原有路径'    | '1.20.1'    | '1.20.1-47.4.0'       | Type.MERGED      | true          | false  | false   | false        || false
		'1.16.5 MCPConfig' | '1.16.5'    | '1.16.5-36.2.42'      | Type.MERGED      | true          | false  | false   | false        || false
		'1.12.2 FG2'       | '1.12.2'    | '1.12.2-14.23.5.2860' | Type.MERGED      | true          | true   | false   | false        || false
		'非 Forge 平台'     | '1.21.11'   | '1.21.11-61.2.5'      | Type.MERGED      | false         | false  | false   | false        || false
		'禁止重映射'         | '1.21.11'   | '1.21.11-61.2.5'      | Type.MERGED      | true          | false  | true    | false        || false
		'非混淆 Forge'      | '1.21.11'   | '1.21.11-61.2.5'      | Type.MERGED      | true          | false  | false   | true         || false
	}
}
