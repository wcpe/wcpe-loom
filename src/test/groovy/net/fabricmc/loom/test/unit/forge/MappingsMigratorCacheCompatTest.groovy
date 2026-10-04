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

import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * 迁移器缓存的**跨版本格式兼容**：新版写出的缓存必须仍能被旧版 loom 整份读下去.
 *
 * <h2>被测缺陷</h2>
 *
 * <p>迁移器缓存（{@code migrated-fields.json} / {@code method-inheritance-migrator.json}）位于**跨版本共享**
 * 的 forge 缓存目录。旧版 loom（1.17.11 及以前）把整份文件当扁平数据解析：字段表是
 * {@code Map<String,String>}，方法表是 {@code List<Pair<String,String>>}。把键与内容包成
 * {@code {"key":…,"entries":…}} 信封之后，旧版会在**配置期硬失败**：
 * {@code JsonSyntaxException: Expected a string but was BEGIN_OBJECT}（报错列号正落在 {@code entries} 的起点）。
 * 也就是说「把 loom 版本回退一档」这种正常运维动作会把仓库直接打瘫——新旧版对缓存格式的容忍度是
 * **不对称**的：新版能读旧格式（键缺失即重算），旧版读不了新格式。
 *
 * <h2>本用例钉住什么</h2>
 *
 * <ul>
 *   <li><b>①新版写出的内容文件形状</b>：顶层必须是旧版认识的扁平形状，且逐字段可被旧版解析
 *       （字段表：对象 + 全部 value 为 JSON 字符串；方法表：数组 + 每项为两个字符串的数组）。
 *       这两条判据与「Gson 能否反序列化成 {@code Map<String,String>} / {@code List<Pair<String,String>>}」
 *       等价——Gson 对这两种目标类型只接受上述形状，其它一律抛 {@code JsonSyntaxException}。</li>
 *   <li><b>②负对照</b>：把同一份内容包成上一版那个信封，旧版解析**必须变红**。没有这一条，
 *       ①里的解析判据可能只是「什么都没检查」的空转断言。</li>
 *   <li><b>③双向兼容</b>：旧版写下扁平内容（没有旁车键）→ 新版不采信（按未命中重算），而旧版自己读它照旧；
 *       新版写下内容 + 旁车键 → 新版键一致时命中、键不符时未命中。</li>
 * </ul>
 *
 * <h2>为什么用反射</h2>
 *
 * <p>{@code MappingsMigratorCache} 是包私有实现类，而本用例的断言对象是「缓存文件的形状」而不是类可见性，
 * 故与 {@code ZipFsPoisoningTest} 同样用反射取用，避免为了测试把内部实现提升为公开 API。
 */
class MappingsMigratorCacheCompatTest extends Specification {
	/** 被测的包私有实现（{@code dev.architectury.loom.forge.MappingsMigratorCache}）. */
	private static final Class<?> CACHE = Class.forName('dev.architectury.loom.forge.MappingsMigratorCache')

	private static final Method PUBLISH = CACHE.getDeclaredMethod('publish', Path, String, JsonElement)
	private static final Method READ = CACHE.getDeclaredMethod('read', Path, String, Logger)
	private static final Method KEY_FILE = CACHE.getDeclaredMethod('keyFile', Path)

	private static final Logger LOGGER = LoggerFactory.getLogger(MappingsMigratorCacheCompatTest)

	private static final String KEY = 'loom-forge-migrator-cache-v1|srg=true|mojang=false|mappings-srg.tiny=abc'

	static {
		[PUBLISH, READ, KEY_FILE].each { it.setAccessible(true) }
	}

	@TempDir
	Path tempDir

	def "①字段表：新版写出的内容文件保持旧版能整份解析的扁平形状"() {
		given: "一份非空的字段迁移表（形状与冷分支产出的完全一致：owner#field → 描述符）"
		def cache = tempDir.resolve("migrated-fields.json")
		def entries = new JsonObject()
		entries.addProperty("net/minecraft/class_304#field_1658", "Lnet/minecraftforge/client/settings/KeyMappingLookup;")
		entries.addProperty("net/minecraft/class_52#field_943", "Ljava/util/List;")

		when: "用新版实现发布"
		publish(cache, KEY, entries)

		then: "旧版 1.17.11 的解析逻辑（Gson → Map<String,String>）必须不报错，且读到的内容与写入一致"
		def old = oldFieldParse(cache)
		old.size() == 2
		old["net/minecraft/class_304#field_1658"] == "Lnet/minecraftforge/client/settings/KeyMappingLookup;"
		old["net/minecraft/class_52#field_943"] == "Ljava/util/List;"

		and: "内容键另放旁车文件，内容文件里没有 key/entries 信封的任何痕迹"
		Files.exists(keyFile(cache))
		!cache.text.contains('"entries"')
	}

	def "①方法表：新版写出的内容文件保持旧版能整份解析的扁平形状"() {
		given: "一份非空的方法表（形状与冷分支产出的完全一致：[[intermediary 名, 描述符], …]）"
		def cache = tempDir.resolve("method-inheritance-migrator.json")
		def entries = new JsonArray()
		entries.add(new JsonArray().tap { add("method_1234"); add("()V") })
		entries.add(new JsonArray().tap { add("method_5678"); add("(I)V") })

		when: "用新版实现发布"
		publish(cache, KEY, entries)

		then: "旧版 1.17.11 的解析逻辑（Gson → List<Pair<String,String>>）必须不报错"
		def old = oldMethodParse(cache)
		old.size() == 2
		old[0] == ["method_1234", "()V"]
		old[1] == ["method_5678", "(I)V"]

		and: "内容键同样只在旁车文件里"
		Files.exists(keyFile(cache))
		!cache.text.contains('"entries"')
	}

	def "②负对照：上一版的 key/entries 信封必须让旧版解析变红（证明①的判据不是空转）"() {
		given: "把同一份字段表包成上一版那个信封，并写进同一个路径"
		def cache = tempDir.resolve("migrated-fields.json")
		def entries = new JsonObject()
		entries.addProperty("net/minecraft/class_52#field_943", "Ljava/util/List;")
		def envelope = new JsonObject()
		envelope.addProperty("key", KEY)
		envelope.add("entries", entries)
		Files.writeString(cache, envelope.toString(), StandardCharsets.UTF_8)

		when: "旧版解析它"
		oldFieldParse(cache)

		then: "必须抛异常——正是 1.17.11 实测到的 JsonSyntaxException（Expected a string but was BEGIN_OBJECT）"
		def e = thrown(JsonSyntaxException)
		e.message.contains("BEGIN_OBJECT")
	}

	def "③新版读新版：键一致命中、键不符未命中"() {
		given: "新版写下一份缓存"
		def cache = tempDir.resolve("migrated-fields.json")
		def entries = new JsonObject()
		entries.addProperty("net/minecraft/class_52#field_943", "Ljava/util/List;")
		publish(cache, KEY, entries)

		expect: "键逐字相等时命中，取回的正是那份内容"
		read(cache, KEY).getAsJsonObject().get("net/minecraft/class_52#field_943").getAsString() == "Ljava/util/List;"

		and: "键不符（另一代输入）时按未命中处理"
		read(cache, KEY + '|different') == null
	}

	def "③旧版写下 → 新版不采信：没有旁车键的扁平内容一律按未命中重算"() {
		given: "模拟旧版 loom 的写方：只有扁平内容，没有旁车键"
		def cache = tempDir.resolve("migrated-fields.json")
		Files.writeString(cache, '{"net/minecraft/class_52#field_943":"Ljava/util/List;"}', StandardCharsets.UTF_8)

		expect: "旧版自己读它照旧（不报错）"
		oldFieldParse(cache).size() == 1

		and: "新版把它当未命中——来源不明的扁平内容不得被采信"
		read(cache, KEY) == null
	}

	def "③内容被扰动 → 新版不采信：旁车键里的内容摘要挡住跨代组合"() {
		given: "新版写下一份缓存，然后把内容换成别代的结果（旁车键保持不动）"
		def cache = tempDir.resolve("migrated-fields.json")
		def entries = new JsonObject()
		entries.addProperty("net/minecraft/class_52#field_943", "Ljava/util/List;")
		publish(cache, KEY, entries)
		Files.writeString(cache, '{}', StandardCharsets.UTF_8)

		expect: "键虽然相等，但内容与旁车键记录的不同代，仍按未命中处理"
		read(cache, KEY) == null
	}

	// --- 旧版 loom 解析逻辑的等价实现 ---

	/**
	 * 旧版字段表读法：{@code new Gson().fromJson(reader, new TypeToken<Map<String,String>>(){})}.
	 *
	 * <p>Gson 对 {@code Map<String,String>} 只接受「顶层对象 + 每个 value 都是 JSON 字符串」，
	 * 其余形状（信封里的嵌套对象、数组、数字……）一律抛 {@code JsonSyntaxException}，
	 * 故这里逐条断言这两件事，判据与旧版完全等价。
	 */
	private static Map<String, String> oldFieldParse(Path cache) {
		final JsonElement root = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8))

		if (root == null || !root.isJsonObject()) {
			throw new JsonSyntaxException("Expected BEGIN_OBJECT but was " + describe(root))
		}

		final Map<String, String> result = [:]

		root.getAsJsonObject().asMap().each { key, value ->
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
				throw new JsonSyntaxException("Expected a string but was " + describe(value))
			}

			result.put(key, value.getAsString())
		}

		return result
	}

	/** 旧版方法表读法：{@code new Gson().fromJson(reader, new TypeToken<List<Pair<String,String>>>(){})}. */
	private static List<List<String>> oldMethodParse(Path cache) {
		final JsonElement root = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8))

		if (root == null || !root.isJsonArray()) {
			throw new JsonSyntaxException("Expected BEGIN_ARRAY but was " + describe(root))
		}

		final List<List<String>> result = []

		root.getAsJsonArray().each { entry ->
			if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 2) {
				throw new JsonSyntaxException("Expected a two-element array but was " + describe(entry))
			}

			result.add(entry.getAsJsonArray().collect { it.getAsString() })
		}

		return result
	}

	private static String describe(JsonElement element) {
		if (element == null) {
			return "null"
		}

		if (element instanceof JsonObject) {
			return "BEGIN_OBJECT"
		}

		if (element instanceof JsonArray) {
			return "BEGIN_ARRAY"
		}

		return ((JsonPrimitive) element).isString() ? "STRING" : "PRIMITIVE"
	}

	// --- 反射入口 ---

	private static void publish(Path cacheFile, String key, JsonElement entries) {
		PUBLISH.invoke(null, cacheFile, key, entries)
	}

	private static JsonElement read(Path cacheFile, String key) {
		return READ.invoke(null, cacheFile, key, LOGGER) as JsonElement
	}

	private static Path keyFile(Path cacheFile) {
		return KEY_FILE.invoke(null, cacheFile) as Path
	}
}
