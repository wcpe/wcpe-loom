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

package net.fabricmc.loom.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「同一个产物路径，同一构建内只有一个生产者任务」的登记表.
 *
 * <p>本表覆盖所有把产物写进**跨项目共享 maven 仓库**的任务——重映射任务（{@link RemapMinecraftTask}）、
 * 处理链任务（{@link ProcessMinecraftJarTask}）、补出 pom/backup 的伴随任务
 * （{@link WriteMinecraftJarSidecarsTask}）与 mod 重映射任务（{@link RemapModsTask}，产物落在构建根的
 * {@code remapped_mods} 仓库，构建内所有项目共用同一个目录）：{@code MavenScope.GLOBAL} 下是 Gradle 用户目录里的全局仓库
 * （同一台机器上的所有构建、所有工作树共用），{@code MavenScope.LOCAL} 下则是构建根目录下的仓库
 * （同一构建的多个子项目共用）。因此同一构建内两个配置相同的子项目会算出**同一条产物路径**——
 * 处理链任务与伴随任务落在 LOCAL 仓库，同样受影响（它们的路径只由 MC 版本、映射标识、jar processor 哈希
 * 与 jar 类型决定，与项目无关）。
 *
 * <p>既有实现靠「跨进程文件锁 + 原子落位」协调这件事，而不是靠「各任务各写各的产物」：同一条路径上本来就
 * 允许出现多个生产者，谁先完成谁生效。改成任务后这条前提不再成立，而且失败是静默的——实测 Gradle 9.5 对
 * 「不同项目的两个任务声明同一个 {@code @OutputFile}」既不报错也不排序，两个任务都会执行、后完成的覆盖
 * 先完成的（本仓库 7436 class 的等价性对照正是建立在一份确定的产物上）；只有在有人按该路径取产物时，Gradle 的
 * 隐式依赖校验才会以「uses this output of task ... without declaring an explicit or implicit dependency」让构建失败。
 * 也就是说：这个共享不是「可以容忍的重复」，而是必须显式建模的所有权关系。
 *
 * <h2>本表如何建模</h2>
 * 以产物路径为键做登记：第一次登记创建任务并成为该路径的**唯一生产者**，后续登记（同一构建内的其它项目）
 * 只取回生产者的**任务路径**，不再创建第二个任务。消费方按该任务路径建依赖（{@code dependsOn} /
 * {@code builtBy}）时，Gradle 会把它解析成真正的跨项目任务依赖，从而满足上面那条隐式依赖校验；
 * 只声明 {@code mustRunAfter} 不够，它只排序、不保证产物存在。
 *
 * <p>生产单位是**批次**时（{@link RemapModsTask}：一批 mod 必须整批重映射）用 {@link #claimAll}：
 * 逐条判定、只接手尚无人认领的产物，任务只写出并只声明属于自己的那些文件。这样做而不是「按批次整体比对」，
 * 是因为两个项目的批次往往只是部分重叠（多模块工程里各模块的 mod 集合不同），按批次比对要么漏判、
 * 要么把重叠的那部分又变成两个生产者；批次本身也不因此被截断——任务仍按完整批次读取输入，
 * 因此每条产物的字节与「谁先登记」无关。
 *
 * <h2>为什么这张表必须跨 classloader 共享</h2>
 * 同一构建里的各个项目未必由同一份 Loom 配置：约定插件/构建逻辑（included build、buildSrc）各自把 Loom
 * 放到自己的 classpath 上时，不同项目拿到的是**不同 classloader 里的 Loom**。实测一个真实多模块工程
 * （146 个模块、两条 MC 版本车道）在一次构建里出现了三份 Loom classloader，其中“车道根项目”与“车道内的模块”
 * 分属两份。按 classloader 分表就等于承认「同一条产物路径上有多个生产者」，于是两边各注册一个
 * {@code remapMinecraftNamedMerged} 写同一个文件，消费方的隐式依赖校验直接把构建打挂——
 * 这正是本表存在的理由，所以表必须落在**构建级**、且只放 classloader 无关的数据。
 *
 * <p>表放在 {@link Gradle} 的扩展属性里（{@code gradle.ext}）：{@link ExtraPropertiesExtension} 是 Gradle
 * 核心对象，同一构建的各个 classloader 拿到的是同一个实例；表内只允许出现
 * {@code Map<String, Map<String, String>>} 这类 JDK 类型——若表里放本 classloader 定义的对象，
 * 另一份 classloader 取回来时会在强制转换处抛 {@link ClassCastException}（同一个类名在两份 classloader 里
 * 不是同一个类型）。也正是这个理由让 {@link Producer} 只带任务**路径**而不是 {@link TaskProvider}。
 *
 * <h2>为什么要比对输入指纹，而不是只按路径共享</h2>
 * 复用同一个任务实例意味着第二个请求方拿到的是第一个请求方那次生产的产物。若两者输入不同（例如只有其中一个
 * 项目改过 {@code loom.knownIndyBsms}、中间映射开关或 classpath），共享就会把 A 的产物当成 B 的产物——
 * 这正是配置期旧路径的静默行为（第二个项目会被 {@code JarReusability} 判为「已就绪」而直接复用），
 * 换成任务后必须变成可见的失败。因此每个请求方都带一份自己的输入指纹，指纹不同即拒绝共享并指出差异项。
 *
 * <h2>跨构建的那一半不在这里</h2>
 * 本表的作用域是一次 Gradle 调用（同一构建的多个项目），覆盖不了「另一个工作树 / 另一个 daemon 正在写同一条
 * 路径」：那一半仍由 {@code @OutputFile} 的 up-to-date 判定与产物的原子落位承担（见
 * {@link net.fabricmc.loom.util.cache.AtomicFiles}）。本表只解决「同一构建内不该有两个生产者」，
 * 且不改变任何已有产物的路径。
 */
public final class RemapMinecraftTaskRegistry {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemapMinecraftTaskRegistry.class);

	/** 表在 {@code gradle.ext} 下的名字；带上 Loom 前缀避免与构建脚本自己的扩展属性撞名. */
	private static final String CLAIMS_EXTENSION_NAME = "loomSharedArtifactProducers";

	/** 表内记录生产者任务路径的保留键；保留键一律以 {@code @} 开头，与输入指纹的键（驼峰标识符）不会撞. */
	private static final String PRODUCER_TASK_KEY = "@task";

	/** 表内记录生产者所属项目路径的保留键，只在诊断输出里用. */
	private static final String PRODUCER_PROJECT_KEY = "@project";

	private RemapMinecraftTaskRegistry() {
	}

	/**
	 * 登记一条产物路径的生产请求，返回该产物的唯一生产者.
	 *
	 * <p>第一次登记时调用 {@code registrar} 创建任务；后续登记的路径相同且 {@code identity} 完全一致时
	 * 直接返回既有生产者的任务路径，{@code registrar} 不会被调用（因此不会产生第二个「同一产物的生产者」）。
	 *
	 * @param project 发起登记的项目
	 * @param artifact 产物路径（本任务的 jar 输出）；路径本身相同即视为同一个构件
	 * @param identity 本次登记的输入指纹，见类注释；键是输入名、值是它的可比较表示，值不得为 {@code null}，
	 *         且键不得以 {@code @} 开头（那是本表的保留键）
	 * @param registrar 创建任务的回调，仅在本次登记成为生产者时调用
	 * @return 该产物的唯一生产者；产物路径已归一化为绝对路径
	 * @throws IllegalStateException 同一构建内同一路径已被另一组输入登记为产物时
	 */
	public static Producer claim(Project project, Path artifact, Map<String, String> identity,
			Supplier<TaskProvider<? extends Task>> registrar) {
		final Path key = artifact.toAbsolutePath().normalize();
		final Gradle gradle = project.getGradle();
		final ExtraPropertiesExtension extensions = extraProperties(gradle);

		// 整段持锁：查表 → 建任务 → 写表之间不允许插入另一个 classloader 的登记，否则两边都会查不到条目、
		// 各自建一个任务。锁对象取 ExtraPropertiesExtension 本身——它是构建级核心对象，两份 classloader
		// 拿到的是同一个实例，而各自的静态锁管不到对方。
		synchronized (extensions) {
			final Map<String, Map<String, String>> claims = claims(extensions);
			final Map<String, String> existing = claims.get(key.toString());

			if (existing != null) {
				requireSameIdentity(key, existing, identity, project);
				LOGGER.info("产物 {} 已由 {} 登记为唯一生产者，{} 复用同一个任务（不重复生产）",
						key, existing.get(PRODUCER_PROJECT_KEY), project.getPath());
				return new Producer(key, existing.get(PRODUCER_TASK_KEY));
			}

			final TaskProvider<? extends Task> task = registrar.get();
			final Map<String, String> entry = new LinkedHashMap<>(identity);
			entry.put(PRODUCER_TASK_KEY, absoluteTaskPath(project, task.getName()));
			entry.put(PRODUCER_PROJECT_KEY, project.getPath());
			claims.put(key.toString(), entry);
			return new Producer(key, entry.get(PRODUCER_TASK_KEY));
		}
	}

	/**
	 * 登记一批产物路径的生产请求，返回**每一条**请求路径的生产者.
	 *
	 * <p>与 {@link #claim} 唯一的差别是「一次登记覆盖多条产物」。需要它是因为有些生产单位天然是批量的：
	 * mod 重映射必须整批进行（跨 mod 的类型上下文不能拆开逐个重映射，否则结果与既有实现不等价），
	 * 而这条批次里的每一条产物路径在别的项目里都可能已经被登记。
	 *
	 * <p>逐条判定、只接手尚无人认领的那些，并且**只对认领到的产物负责**：任务仍然按完整批次读取输入
	 * （这样每一条产物的字节与「谁先登记」无关），但只写出、也只声明属于自己的那些产物。
	 * 于是同一路径在同一构建内始终只有一个生产者，而批次本身不会因为共享而被截断。
	 *
	 * @param project 发起登记的项目
	 * @param artifacts 本批次会写出的全部产物路径
	 * @param identity 本批次的输入指纹（见类注释）；批内每条产物相同，且不得含对象身份
	 * @param registrar 创建任务的回调，仅在存在无人认领的产物时调用一次，参数是**本任务拥有的产物路径**
	 * @return 入参中每条路径（已归一化）的生产者；已被别人登记的返回既有的那个
	 * @throws IllegalStateException 同一构建内同一路径已被另一组输入登记为产物时
	 */
	public static Map<Path, Producer> claimAll(Project project, List<Path> artifacts, Map<String, String> identity,
			Function<List<Path>, TaskProvider<? extends Task>> registrar) {
		final List<Path> distinct = new ArrayList<>(new LinkedHashSet<>(
				artifacts.stream().map(artifact -> artifact.toAbsolutePath().normalize()).toList()));
		final Gradle gradle = project.getGradle();
		final ExtraPropertiesExtension extensions = extraProperties(gradle);

		synchronized (extensions) {
			final Map<String, Map<String, String>> claims = claims(extensions);
			final Map<Path, Producer> producers = new LinkedHashMap<>();
			final List<Path> owned = new ArrayList<>();

			for (Path artifact : distinct) {
				final Map<String, String> existing = claims.get(artifact.toString());

				if (existing == null) {
					owned.add(artifact);
					continue;
				}

				requireSameIdentity(artifact, existing, identity, project);
				producers.put(artifact, new Producer(artifact, existing.get(PRODUCER_TASK_KEY)));
			}

			if (!owned.isEmpty()) {
				final TaskProvider<? extends Task> task = registrar.apply(List.copyOf(owned));
				final String taskPath = absoluteTaskPath(project, task.getName());

				for (Path artifact : owned) {
					final Map<String, String> entry = new LinkedHashMap<>(identity);
					entry.put(PRODUCER_TASK_KEY, taskPath);
					entry.put(PRODUCER_PROJECT_KEY, project.getPath());
					claims.put(artifact.toString(), entry);
					producers.put(artifact, new Producer(artifact, taskPath));
				}
			}

			return producers;
		}
	}

	/**
	 * 校验同一路径上的两次登记输入一致，不一致即拒绝共享并指出差异项.
	 *
	 * <p>共享一份输入不同的产物，等于把别的配置的结果静默交给本项目，因此这里必须是失败而不是复用。
	 *
	 * @param key 产物路径（已归一化）
	 * @param existing 表内已有的登记条目
	 * @param identity 本次登记的输入指纹
	 * @param project 发起本次登记的项目
	 * @throws IllegalStateException 两组输入不一致时
	 */
	private static void requireSameIdentity(Path key, Map<String, String> existing, Map<String, String> identity, Project project) {
		final Map<String, String> existingIdentity = identityOf(existing);

		if (existingIdentity.equals(new TreeMap<>(identity))) {
			return;
		}

		throw new IllegalStateException(("产物 %s 在同一构建内被以两组不同的输入请求生产，无法共享同一个任务：\n"
				+ "%s"
				+ "该路径位于跨项目共享的 maven 仓库，同一条路径只能有一个生产者——不同输入写到同一路径，"
				+ "会让先完成的一方被另一方覆盖，产物与请求方的配置不符。可行处置：\n"
				+ "  1. 若两个项目本就该产出同一个 jar，请让它们的输入完全一致（见上面的差异项）；\n"
				+ "  2. 若确实需要两个不同的 jar，需要不同的产物路径，即不同的 name/version"
				+ "（映射、MC 版本、中间映射提供者或 jar processor 配置）；\n"
				+ "  3. 若差异来自按项目设置的扩展项（loom.knownIndyBsms、useIntermediateMappings 等），"
				+ "请在各项目间统一。")
				.formatted(key, describeDifference(existingIdentity, existing.get(PRODUCER_PROJECT_KEY), project, identity)));
	}

	/** {@return 构建级的登记表} 不存在时创建；只在 {@link ExtraPropertiesExtension} 的锁内调用. */
	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, String>> claims(ExtraPropertiesExtension extensions) {
		if (extensions.has(CLAIMS_EXTENSION_NAME)) {
			// 只可能装本方法写入的 Map<String, Map<String, String>>：值是 JDK 类型，
			// 因此另一份 classloader 取回来的对象本 classloader 也能直接当 Map 用（见类注释）
			return (Map<String, Map<String, String>>) extensions.get(CLAIMS_EXTENSION_NAME);
		}

		final Map<String, Map<String, String>> claims = new LinkedHashMap<>();
		extensions.set(CLAIMS_EXTENSION_NAME, claims);
		return claims;
	}

	/** {@return 构建级扩展属性容器} 跨 classloader 是同一个实例，故既能当共享存储也能当锁. */
	private static ExtraPropertiesExtension extraProperties(Gradle gradle) {
		return gradle.getExtensions().getExtraProperties();
	}

	/** {@return 剥离保留键后的输入指纹} 用于比较两次登记是否等价. */
	private static Map<String, String> identityOf(Map<String, String> entry) {
		final Map<String, String> identity = new TreeMap<>(entry);
		identity.remove(PRODUCER_TASK_KEY);
		identity.remove(PRODUCER_PROJECT_KEY);
		return identity;
	}

	/** {@return 项目内任务的绝对路径} 直接交给 {@code dependsOn}/{@code builtBy} 使用. */
	private static String absoluteTaskPath(Project project, String taskName) {
		return project.getPath().equals(":") ? ":" + taskName : project.getPath() + ":" + taskName;
	}

	/**
	 * {@return 两组输入指纹的差异，逐项列出「本次」与「已在生产者」的取值}.
	 *
	 * <p>按输入名排序输出，便于两次构建的日志互相对照；只在一边出现的键同样列出，避免「对方没设」被当成「一致」.
	 */
	private static String describeDifference(Map<String, String> existing, @Nullable String producerProject,
			Project project, Map<String, String> identity) {
		final Set<String> keys = new TreeSet<>(existing.keySet());
		keys.addAll(identity.keySet());
		final Map<String, String> sorted = new TreeMap<>(identity);
		final StringBuilder difference = new StringBuilder();

		for (String key : keys) {
			final String claimed = sorted.get(key);
			final String current = existing.get(key);

			if (claimed == null || !claimed.equals(current)) {
				difference.append("  - ").append(key)
						.append(": 本次（").append(project.getPath()).append("）=").append(describeValue(claimed))
						.append("，已在生产者（").append(producerProject).append("）=").append(describeValue(current))
						.append('\n');
			}
		}

		return difference.toString();
	}

	private static String describeValue(@Nullable String value) {
		return value == null ? "<未设置>" : value;
	}

	/**
	 * 一条共享产物的生产位置.
	 *
	 * <p>只带「产物路径」与「产出它的任务的绝对路径」两个 JDK 值：产出方可能由另一份 classloader 加载的
	 * Loom 注册（见类注释），此时把对方的 {@link TaskProvider} 交给本 classloader 会在使用它的地方抛
	 * {@link ClassCastException}；任务路径是字符串，交给 {@code Task#dependsOn}、
	 * {@code ConfigurableFileCollection#builtBy} 都能被 Gradle 解析成真正的任务依赖。
	 *
	 * @param artifact 产物文件（绝对规范化路径）
	 * @param taskPath 产出该产物的任务的绝对路径（如 {@code :a:remapMinecraftNamedMerged}）；
	 *         产物由配置期生产（未投影成任务）时为 {@code null}
	 */
	public record Producer(Path artifact, @Nullable String taskPath) {
	}
}
