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

package net.fabricmc.loom

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * 覆盖「settings 侧已声明过仓库」这一 marker 的判据。
 *
 * <p>marker 打在 Gradle 对象上，项目侧据此跳过整套仓库的重复声明。若判据用
 * {@code hasPlugin(LoomRepositoryPlugin.class)}，走的就是类型身份比较：cross classloader 时恒判 false，
 * marker 静默失效，项目侧会把整套仓库再声明一遍并二次重排（不报错，只是行为静默变差）。
 * 这里用「第二份 Loom」的类加载器真实复现该场景，验证字符串插件 id 判据仍然成立。
 */
class LoomRepositoryPluginTest extends Specification {
	private static final String PLUGIN_CLASS = "net.fabricmc.loom.LoomRepositoryPlugin"

	def "按 Class 应用 marker 后，项目侧能按插件 id 判出已声明"() {
		given:
		def project = ProjectBuilder.builder().withName("root").build()

		expect:
		!LoomRepositoryPlugin.isRepositoriesDeclared(project.getGradle())

		when: "settings 侧应用 marker，写法与 apply(Settings) 中一致（按 Class 应用）"
		project.getGradle().getPluginManager().apply(LoomRepositoryPlugin.class)

		then:
		LoomRepositoryPlugin.isRepositoriesDeclared(project.getGradle())
	}

	def "另一份 classloader 的 Loom 留下的 marker 仍能按插件 id 判出"() {
		given: "第二份 Loom：同名类不是同一个 Class，模拟 settings 与项目 classpath 各加载一份"
		def project = ProjectBuilder.builder().withName("root").build()
		def foreignLoader = new ForeignLoomClassLoader(getClass().getClassLoader(), PLUGIN_CLASS)
		def foreignPluginClass = foreignLoader.loadClass(PLUGIN_CLASS)

		when: "settings 侧那份 Loom 把 marker 打在构建上"
		project.getGradle().getPluginManager().apply(foreignPluginClass)

		then: "旧判据（类型身份比较）已经判不出来 —— 这正是 marker 静默失效的原因"
		foreignPluginClass != LoomRepositoryPlugin.class
		!project.getGradle().getPlugins().hasPlugin(LoomRepositoryPlugin.class)

		and: "按插件 id 的判据与 classloader 无关，仍然成立"
		LoomRepositoryPlugin.isRepositoriesDeclared(project.getGradle())
	}

	/**
	 * 「第二份 Loom」的类加载器：只自行定义指定类，形成与父加载器不同的类身份；
	 * 其余类（Gradle API、Spock、Loom 的其它类与资源）一律委托父加载器，
	 * 保证两边共享同一批 Gradle 类型与同一份插件描述符。
	 */
	private static class ForeignLoomClassLoader extends ClassLoader {
		private final Set<String> ownedClasses

		ForeignLoomClassLoader(ClassLoader parent, String... ownedClasses) {
			super(parent)
			this.ownedClasses = Set.of(ownedClasses)
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name)

				if (loaded == null && ownedClasses.contains(name)) {
					loaded = findClass(name)
				}

				return loaded == null ? super.loadClass(name, resolve) : loaded
			}
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			def resource = getParent().getResourceAsStream(name.replace('.', '/') + '.class')

			if (resource == null) {
				throw new ClassNotFoundException(name)
			}

			resource.withCloseable { input ->
				byte[] bytes = input.bytes
				return defineClass(name, bytes, 0, bytes.length)
			}
		}
	}
}
