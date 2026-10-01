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

package net.fabricmc.loom.configuration.providers.minecraft.mapped;

import java.util.List;

import org.gradle.api.Project;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.LegacyMergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarEnvType;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.SplitMinecraftProvider;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract sealed class MojangMappedMinecraftProvider<M extends MinecraftProvider> extends AbstractMappedMinecraftProvider<M> permits MojangMappedMinecraftProvider.MergedImpl, MojangMappedMinecraftProvider.LegacyMergedImpl, MojangMappedMinecraftProvider.SingleJarImpl, MojangMappedMinecraftProvider.SplitImpl {
	public MojangMappedMinecraftProvider(Project project, M minecraftProvider) {
		super(project, minecraftProvider);
	}

	@Override
	public MavenScope getMavenScope() {
		return MavenScope.GLOBAL;
	}

	@Override
	public final MappingsNamespace getTargetNamespace() {
		return MappingsNamespace.MOJANG;
	}

	public static final class MergedImpl extends MojangMappedMinecraftProvider<MergedMinecraftProvider> implements Merged {
		public MergedImpl(Project project, MergedMinecraftProvider minecraftProvider) {
			super(project, minecraftProvider);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
				new RemappedJars(minecraftProvider.getMergedJar(), getMergedJar(), MappingsNamespace.OFFICIAL)
			);
		}
	}

	public static final class LegacyMergedImpl extends MojangMappedMinecraftProvider<LegacyMergedMinecraftProvider> implements Merged {
		public LegacyMergedImpl(Project project, LegacyMergedMinecraftProvider minecraftProvider) {
			super(project, minecraftProvider);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			// The delegate providers will handle the remapping
			throw new UnsupportedOperationException("LegacyMergedImpl does not support getRemappedJars");
		}
	}

	public static final class SplitImpl extends MojangMappedMinecraftProvider<SplitMinecraftProvider> implements Split {
		public SplitImpl(Project project, SplitMinecraftProvider minecraftProvider) {
			super(project, minecraftProvider);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
				new RemappedJars(minecraftProvider.getMinecraftCommonJar(), getCommonJar(), MappingsNamespace.OFFICIAL),
				new RemappedJars(minecraftProvider.getMinecraftClientOnlyJar(), getClientOnlyJar(), MappingsNamespace.OFFICIAL, minecraftProvider.getMinecraftCommonJar())
			);
		}

		@Override
		protected void configureRemapper(RemappedJars remappedJars, TinyRemapper.Builder tinyRemapperBuilder) {
			if (remappedJars.outputJar().equals(getClientOnlyJar())) {
				tinyRemapperBuilder.extraPostApplyVisitor(SidedClassVisitor.CLIENT);
			}
		}

		// 声明为「空操作」：本覆写的判据恒为 false，实际什么也不挂，因此任务侧 injectClientSidedVisitor = false
		// 就是精确复现配置期行为，可以放行投影（此前它被归入「未知覆写」而整 provider 被拒）。
		//
		// 判据恒假的证据链：
		// 1. MinecraftJar 未覆写 equals/hashCode，即按身份相等；
		// 2. Split.getClientOnlyJar() 是每次调用都新建实例的默认实现（new MinecraftJar.ClientOnly(...)），
		//    且本类没有覆写它；
		// 3. 故 remappedJars.outputJar() 与这里重新取到的 getClientOnlyJar() 不可能是同一个对象——
		//    实测「同路径同类型」时为 pathEqual=true 但 jarEquals=false。
		// 于是 if 块永不进入，覆写体等价于空实现（疑似 MinecraftJar 从按值相等改为身份相等后遗留的失效条件）。
		//
		// 也不能反过来声明为 SPLIT_CLIENT_VISITOR_ONLY：那会让任务给 client-only jar 挂上配置期没有的
		// @Environment(CLIENT)，产物因此分叉——本声明要复现的是现状，不是纠正现状。
		//
		// 失效条件：本声明依赖上面第 1、2 条同时成立。若将来 MinecraftJar 引入按值相等（或
		// getClientOnlyJar() 改为复用同一个实例），该 if 就会真的挂上 visitor，本声明随之失效、必须重新评估；
		// 届时「这个覆写到底该不该挂 visitor」是产品决策，本声明不替它做决定。
		@Override
		protected RemapperHookKind remapperHookKind() {
			return RemapperHookKind.NO_OP;
		}
	}

	public static final class SingleJarImpl extends MojangMappedMinecraftProvider<SingleJarMinecraftProvider> implements SingleJar {
		private final SingleJarEnvType env;

		private SingleJarImpl(Project project, SingleJarMinecraftProvider minecraftProvider, SingleJarEnvType env) {
			super(project, minecraftProvider);
			this.env = env;
		}

		public static SingleJarImpl server(Project project, SingleJarMinecraftProvider minecraftProvider) {
			return new SingleJarImpl(project, minecraftProvider, SingleJarEnvType.SERVER);
		}

		public static SingleJarImpl client(Project project, SingleJarMinecraftProvider minecraftProvider) {
			return new SingleJarImpl(project, minecraftProvider, SingleJarEnvType.CLIENT);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
				new RemappedJars(minecraftProvider.getMinecraftEnvOnlyJar(), getEnvOnlyJar(), MappingsNamespace.OFFICIAL)
			);
		}

		@Override
		public SingleJarEnvType env() {
			return env;
		}
	}
}
