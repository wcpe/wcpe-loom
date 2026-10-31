/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.util.fmj;

import java.util.Objects;

public final class ModEnvironment {
	public static final ModEnvironment UNIVERSAL = new ModEnvironment(true, true, "universal");
	public static final ModEnvironment CLIENT = new ModEnvironment(true, false, "client");
	public static final ModEnvironment SERVER = new ModEnvironment(false, true, "server");

	private final boolean client;
	private final boolean server;
	private final String name;

	private ModEnvironment(boolean client, boolean server, String name) {
		this.client = client;
		this.server = server;
		this.name = name;
	}

	public boolean isClient() {
		return client;
	}

	public boolean isServer() {
		return server;
	}

	/**
	 * {@return 该环境在「包含客户端 / 包含服务端」这样的组合下是否生效}.
	 *
	 * <p>规则只有一条：上下文包含的那一侧出现在环境里，声明就生效。合并 jar（两侧都在）因此对
	 * universal / client / server 全部生效；而 server-only 的上下文里 client 声明不生效——
	 * 这正是 schema v2 允许每条 class tweaker 单独声明环境的意义。
	 *
	 * <p>集中在这里是为了让环境语义只有一份实现：{@code AccessWidenerJarProcessor} 的筛选与
	 * 任何日后需要按侧筛选声明的地方都应当问同一个方法，而不是各写一遍。
	 */
	public boolean appliesTo(boolean includeClient, boolean includeServer) {
		return (includeClient && client) || (includeServer && server);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ModEnvironment that = (ModEnvironment) o;
		return name.equals(that.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name);
	}
}
