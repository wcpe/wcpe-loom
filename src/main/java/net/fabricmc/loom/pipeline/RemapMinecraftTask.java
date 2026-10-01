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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.core.MinecraftJarRemap;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.cache.AtomicFiles;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * L3 流水线：把 Minecraft jar 重映射到目标命名空间.
 *
 * <p>这是「把 mapped jar 的生产移出配置期」的落点。它取代的是
 * {@code AbstractMappedMinecraftProvider.provide()} 里那段配置期同步执行的
 * 「判断是否需要重建 → 取跨进程锁 → 重映射 → 落位」。
 *
 * <table>
 *   <caption>旧路径 vs 本任务</caption>
 *   <tr><th></th><th>旧路径</th><th>本任务</th></tr>
 *   <tr><td>是否需要重建</td><td>{@code shouldRefreshOutputs}（配置期开 zipfs 逐产物判定）</td>
 *       <td>Gradle up-to-date 检查</td></tr>
 *   <tr><td>并发保护</td><td>跨进程文件锁 + 锁内二次确认</td><td>任务图 + 构建缓存</td></tr>
 *   <tr><td>产物损坏</td><td>{@code JarReusability} 手工判残骸</td><td>构建缓存重新恢复</td></tr>
 *   <tr><td>执行时机</td><td>配置期 {@code afterEvaluate}</td><td>执行期</td></tr>
 * </table>
 *
 * <h2>为什么 remapper 由 service 装配而不是本类自建</h2>
 * remapper 的构造需要映射服务、已知 BSB、以及第三方 remapper 扩展
 * （{@code RemapperExtensionHolder}）。这些在既有实现里统一由
 * {@link net.fabricmc.loom.task.service.TinyRemapperService} 装配，并通过 {@code @Nested} 的 Options 序列化进任务输入。
 * 本类沿用该路径，以避免出现「两套构造逻辑产生不同 remapper」的分叉。
 *
 * <p>实际的重映射 I/O 交给 L4 的 {@link MinecraftJarRemap}——它不认识 Gradle，
 * 且已保留「原子发布 + 发布终态」的契约。
 *
 * <h2>配置期的钩子与分支如何变成输入</h2>
 * 配置期 {@code AbstractMappedMinecraftProvider} 有三处语义不在「输入 jar + 映射」之内。
 * 本任务用三个 {@code @Input} 显式承载它们，而不是在任务里重新判断——任务只拿到一对路径，
 * 平台配置（是否 NeoForge、是否是 split 形态的 client-only jar）它无从得知：
 * <ul>
 *   <li>{@code configureRemapper} 钩子的效果 → {@link #getInjectClientSidedVisitor()}。
 *       四处覆写（各 provider 的 {@code SplitImpl}）都只给 client-only jar 追加
 *       {@code @Environment(CLIENT)}，故可收敛成一个布尔值。</li>
 *   <li>{@code disableObfuscation} 下 {@code remapJar} 走原子复制而非重映射 → {@link #getCopyOnly()}。</li>
 *   <li>{@code remapJar} 把 {@code isNeoForge()} 当作 {@code injectMixinExtension} 交给 L4 →
 *       {@link #getInjectMixinExtension()}。它与 {@code forgeLike} 不同源，理由见该输入的说明。</li>
 * </ul>
 * 三者的缺省值都取 {@code false}，即「重映射 + 无额外 remapper 配置」——与本次改造前任务的唯一行为
 * 逐位一致；需要非缺省取值的接线路径必须显式设置。
 *
 * <h2>产物落位：三个具体文件，绝不声明目录</h2>
 * 一个 {@code RemappedJars} 对应 maven 仓库里的同一个构件目录，该目录里属于本任务的文件有三个：
 * <ol>
 *   <li>{@link #getOutputJar()}——重映射产物本身；</li>
 *   <li>{@link #getOutputPom()}——同名的 {@code .pom}，由 {@link LocalMavenHelper#savePom} 写出
 *       （本任务不另行生成内容，理由见该输出的说明），缺失会让坐标式依赖解析失败；</li>
 *   <li>{@link #getOutputBackupJar()}——jar 的逐字节副本，是 genSources 的 {@code @Classpath} 输入，
 *       缺失会让 {@code GenerateSourcesTask} 直接抛错；provider 不需要备份时（中间映射产物不会被反编译）
 *       不设置该输出，此时本任务不产出它。</li>
 * </ol>
 * 三者都声明为 {@code @OutputFile}，本任务不声明任何 {@code @OutputDirectory}：构件目录位于共享 maven
 * 仓库，仓库根与版本目录里还有别的 provider、别的 MC 版本的产物，把它们纳入本任务的快照与清理范围会互相破坏
 * （既有实现正是为此在「按前缀扫描清理同目录」上踩过坑）。
 *
 * <p>该共享仓库决定了「同一条产物路径上不该有两个生产者」：{@code MavenScope.GLOBAL} 下路径跨项目、跨工作树
 * 共享。这件事不在本任务里协调——由 {@link RemapMinecraftTaskRegistry} 在同一构建内保证唯一生产者，
 * 跨构建则由 {@code @OutputFile} 的 up-to-date 判定与原子落位承担。
 */
@CacheableTask
public abstract class RemapMinecraftTask extends DefaultTask {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemapMinecraftTask.class);

	public RemapMinecraftTask() {
		// 缺省值 = 本次改造前任务的唯一行为：重映射、不挂任何额外 remapper 配置。
		// 这只是「与改造前逐位一致」，并不等于「与配置期一致」：NeoForge（缺 mixin 扩展）
		// 与 split 形态的 client-only jar（缺 @Environment(CLIENT)）都必须由接线侧显式设为 true，
		// 理由见各输入的说明。
		getCopyOnly().convention(false);
		getInjectClientSidedVisitor().convention(false);
		getInjectMixinExtension().convention(false);
	}

	/**
	 * 是否只做原子复制、不重映射（直通模式）.
	 *
	 * <p>开启后本任务与配置期 {@code AbstractMappedMinecraftProvider.remapJar} 在
	 * {@code disableObfuscation} 分支下的行为一致：不改写字节码，只把输入 jar 原子复制到产物位置；
	 * 不开启则走重映射。两种产物不同，任务自身无从分辨（它只有一对输入/输出路径），
	 * 因此该判断只能由掌握平台配置的接线侧给出。
	 *
	 * <h4>为什么用 AtomicFiles 而不是裸 Files.copy</h4>
	 * 产物位于跨进程、跨 daemon 共享的 maven 仓库，落位必须原子：否则读方会读到半写的 jar，
	 * 而内容判定只能发现截断、发现不了「完整但没写完」。这条契约是仓库既有的
	 * （见 {@link AtomicFiles} 的类注释），复制模式同样受它约束。
	 *
	 * <h4>与其它输入的关系</h4>
	 * 复制模式下不读取映射、也不装配 remapper 扩展，但任务协议不变：映射、命名空间等输入照常由接线侧
	 * 提供（它们是任务身份的一部分，不该随模式变化而消失）。
	 */
	@Input
	public abstract Property<Boolean> getCopyOnly();

	/** 待重映射的 jar. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	/** 重映射 classpath（通常是上一阶段的产物 + 编译期库）. */
	@Classpath
	public abstract ConfigurableFileCollection getRemapClasspath();

	/**
	 * 映射服务配置；执行期据此取出映射树.
	 *
	 * <p>刻意用 {@link TinyMappingsService.Options} 而不是别处的映射来源：配置期的
	 * {@code AbstractMappedMinecraftProvider.remapJar} 正是通过
	 * {@code MappingConfiguration.getMappingsService(project, serviceFactory, MappingOption.forPlatform(extension))}
	 * 拿到这一份映射树。换用其它来源（例如 {@code MappingsService.createOptionsWithProjectMappings}）
	 * 会解析到不同的映射文件，产出与配置期不等价——这正是等价性测试发现的问题。
	 */
	@Nested
	public abstract Property<TinyMappingsService.Options> getMappingsServiceOptions();

	/** 源命名空间. */
	@Input
	public abstract Property<String> getFromNamespace();

	/** 目标命名空间. */
	@Input
	public abstract Property<String> getToNamespace();

	/** 是否修复 record 组件（Java 16+ 目标需要）. */
	@Input
	public abstract Property<Boolean> getFixRecords();

	/** 是否 Forge/NeoForge 系，影响内部类重映射. */
	@Input
	public abstract Property<Boolean> getForgeLike();

	/**
	 * 是否向 remapper 注入 mixin 扩展（NeoForge 系需要）.
	 *
	 * <p>对应配置期 {@code remapJar} 传给 L4 的 {@code extension.isNeoForge()}：为真时 L4 会往
	 * remapper 上挂 {@code new MixinExtension(inputTag -> true)}。该扩展只作用于「带 mixin 注解的类」：
	 * 硬目标（{@code @Shadow}/{@code @Overwrite}/{@code @Accessor}/{@code @Invoker}/{@code @Implements}）
	 * 用于解析目标成员，软目标（{@code @Mixin}/{@code @Inject}/{@code @At} 等注解里的字符串）用于改写
	 * 注解内的类名与方法描述符；它不处理 mixin 配置 JSON，也不生成 refmap。
	 *
	 * <h4>与 forgeLike 不是同一件事</h4>
	 * {@link #getForgeLike()} 是 {@code isForgeLike()}（影响冲突忽略策略与内部类映射），本输入是
	 * {@code isNeoForge()}。NeoForge 是 forgeLike 的真子集：「Forge 但非 NeoForge」这一支两者取值不同，
	 * 故本输入无法由 {@link #getForgeLike()} 推导，必须独立承载。
	 *
	 * <h4>它的实际影响随 NeoForge 版本而变</h4>
	 * 实测：NeoForge 21.0.77-beta 的 jar 内含 {@code net/neoforged/neoforge/mixins/*} 访问器 mixin
	 * （类常量池里带 mixin 注解描述符），NeoForge 自身的类又会被并进 MC jar，故这一支上该扩展有实际作用；
	 * 而 NeoForge 20.2.93 及更早的 jar 里不存在任何 mixin 注解，此时它是空操作。因为作用随版本而变、
	 * 不能假定为零，任务必须把它当输入承载，不能写死 {@code false}。
	 *
	 * <h4>缺省 false 的边界</h4>
	 * 缺省 {@code false} 与 Fabric、Forge 路径的配置期取值一致；NeoForge 系接线时必须显式设为
	 * {@code true}，否则 NeoForge 自身那些 mixin 类在重映射后会与配置期产物不同。
	 */
	@Input
	public abstract Property<Boolean> getInjectMixinExtension();

	/**
	 * 是否给产物追加 {@code @Environment(CLIENT)} 注解（split jar 的 client-only 那一个）.
	 *
	 * <p>对应配置期的 {@code configureRemapper} 钩子。该钩子是实例方法、效果不可序列化，
	 * 但四处覆写（intermediary／named／srg／mojang 的 {@code SplitImpl}）都只做一件事——给 client-only
	 * 的 jar 追加 {@code SidedClassVisitor.CLIENT}——因此它的效果可收敛为一个布尔值。
	 *
	 * <h4>装配路径与配置期同源</h4>
	 * 为真时任务挂的是 {@link SidedClassVisitor#CLIENT}，即 {@code configureSplitRemapper} 与各覆写
	 * 使用的同一个常量，走同一条 {@code extraPostApplyVisitor} 装配，不存在第二套 visitor 构造逻辑。
	 *
	 * <h4>缺省 false 的边界</h4>
	 * 缺省 {@code false}：对 merged／single jar 就是配置期的取值（基类钩子是空实现）。split 形态下
	 * 只有 client-only 那个 jar 的任务需要设为 {@code true}；漏设会让客户端产物缺少该注解——产物仍能
	 * 构建成功，只是语义与配置期不同，故接线时必须按 provider 形态显式设置。
	 */
	@Input
	public abstract Property<Boolean> getInjectClientSidedVisitor();

	/** 是否校验目标命名空间存在. */
	@Input
	public abstract Property<Boolean> getValidateTargetNamespace();

	/** Forge 系内部类重映射所需的类名集合. */
	@Input
	public abstract SetProperty<String> getInnerClassNames();

	/** 已知的 indy BSM 集合. */
	@Input
	public abstract SetProperty<String> getKnownIndyBsms();

	/** 签名修复表；为空表示无需修复. */
	@Input
	public abstract MapProperty<String, String> getSignatureFixes();

	/** 注解重映射数据的 JSON；为空表示无. */
	@Input
	@Optional
	public abstract Property<String> getAnnotationsJson();

	/** object holder 改写的类名；为空表示不做该步骤. */
	@Input
	@Optional
	public abstract Property<String> getObjectHolderClassName();

	/** object holder 改写所用的源命名空间；为空表示不做该步骤. */
	@Input
	@Optional
	public abstract Property<String> getObjectHolderSourceNamespace();

	/** object holder 改写所用的目标命名空间. */
	@Input
	public abstract Property<String> getObjectHolderTargetNamespace();

	/** 产物. */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	/**
	 * 产物的 pom：maven 仓库里与 jar 同名同目录、只有扩展名不同的那个文件.
	 *
	 * <p>内容与落位都由 {@link LocalMavenHelper#savePom} 决定——本任务按坐标调用它，不另行拼模板、
	 * 也不另写一份格式，因此与配置期写出的 pom 逐字节一致。缺失它会让按坐标
	 * （{@code net.minecraft:<name>:<version>}）解析该构件的依赖直接失败：pom 是坐标式解析的必要条件，
	 * 不是可选的附产物。
	 *
	 * <h4>为什么坐标是输入而不是从输出路径反推</h4>
	 * pom 的内容只由 {@code group/name/version} 决定，是这三个值的函数，因此三者必须是 {@code @Input}，
	 * 否则改坐标不会让本任务失效。{@link #getPomMavenRoot()} 只决定落位、不决定内容，故为 {@code @Internal}。
	 *
	 * <h4>可选性的边界</h4>
	 * 未设置表示本次不产出 pom。经 {@code registerRemapTask} 接线时必定设置（配置期两条分支都会写 pom）；
	 * 直接注册本任务的场景（例如与配置期做等价性对照的探针）不设置它，因此这里保持可选而非必填。
	 * 一旦设置了它，{@link #getPomGroup()} 等坐标就必须同时给出，否则执行期直接失败——见 {@link #remap()}。
	 */
	@Optional
	@OutputFile
	public abstract RegularFileProperty getOutputPom();

	/**
	 * pom 的 group；只参与 pom 内容的生成与落位.
	 *
	 * <p>{@code @Optional} 的理由见 {@link #getOutputPom()} 的「可选性的边界」：只有声明了 pom 输出时
	 * 才需要坐标，未声明时留着默认空值。执行期对「声明了输出却缺坐标」的校验在 {@link #remap()}。
	 */
	@Optional
	@Input
	public abstract Property<String> getPomGroup();

	/** pom 的 artifact 名，与构件 jar 的主名相同；可选性同 {@link #getPomGroup()}. */
	@Optional
	@Input
	public abstract Property<String> getPomName();

	/** pom 的版本，即「MC 版本 + 映射标识」；可选性同 {@link #getPomGroup()}. */
	@Optional
	@Input
	public abstract Property<String> getPomVersion();

	/**
	 * pom 所在的 maven 仓库根.
	 *
	 * <p>只用于让 {@link LocalMavenHelper} 复算落位（它把 group、name、version 展开成目录，本任务不复算
	 * 这套布局规则），不参与 pom 内容的判定，故为 {@code @Internal}：同一坐标落在哪个仓库根，pom 的内容都一样。
	 * 它与声明出来的 {@link #getOutputPom()} 是否指向同一处，执行期会校验。
	 */
	@Internal
	public abstract Property<File> getPomMavenRoot();

	/**
	 * jar 的逐字节副本，作为 genSources 的输入.
	 *
	 * <p>genSources 按 {@code AbstractMappedMinecraftProvider.getBackupJarPath} 找这个文件
	 * （jar 路径加上 {@code .backup}），找不到就直接抛错；配置期也是先出 jar、再复制出 backup，
	 * 且 backup 被当作「产物齐了」的标志（见 {@code createBackupJars} 的注释）。
	 *
	 * <h4>为什么用 AtomicFiles 而不是裸 Files.copy</h4>
	 * 副本与正本同处跨进程共享的 maven 仓库，落位必须原子，否则读方会读到半写的 backup 而误判产物就绪。
	 * 这条契约与 {@link #getCopyOnly()} 的复制模式同源，见 {@link AtomicFiles} 的类注释。
	 *
	 * <h4>可选性的边界</h4>
	 * 未设置表示本 provider 不需要 backup（中间映射产物不会被反编译，见
	 * {@code AbstractMappedMinecraftProvider.requiresBackupJars}）。设置之后本任务按 jar 的逐字节副本写出，
	 * 并在执行期校验路径确实是 {@code <jar>.backup}，避免写出一份没人读的副本而让 genSources 找不到输入。
	 */
	@Optional
	@OutputFile
	public abstract RegularFileProperty getOutputBackupJar();

	@TaskAction
	public void remap() throws IOException {
		final Path inputJar = getInputJar().get().getAsFile().toPath();
		final Path outputJar = getOutputJar().get().getAsFile().toPath();

		if (getCopyOnly().get()) {
			// 直通模式：配置期 disableObfuscation 分支的等价路径。不改写字节码，也不是裸复制——
			// 落位必须原子，否则读方会读到半写的 jar（见 AtomicFiles 的类注释）
			LOGGER.info("Copying {} to {} without remapping", inputJar.getFileName(), outputJar.getFileName());
			AtomicFiles.copy(inputJar, outputJar);
		} else {
			remapJar(inputJar, outputJar);
		}

		// 落位顺序与配置期一致：jar → pom → backup。直通模式同样要产出后两者——配置期在该分支下也是
		// 「复制 jar + savePom」，backup 则由 provide() 在两个分支上统一复制。
		writePom(outputJar);
		writeBackupJar(outputJar);
	}

	private void remapJar(Path inputJar, Path outputJar) throws IOException {
		LOGGER.info("Remapping {} to namespace {}", inputJar.getFileName(), getToNamespace().get());

		// 配置期由 configureRemapper 钩子挂载的 remapper 扩展：任务侧按输入装配。
		// 无扩展时传 null，与本次改造前逐位一致（早先的等价性验证即在该取值下完成）。
		final Consumer<TinyRemapper.Builder> extraRemapperConfig;

		if (getInjectClientSidedVisitor().get()) {
			// 与 configureSplitRemapper、各 SplitImpl 覆写挂的是同一个 visitor 常量
			extraRemapperConfig = builder -> builder.extraPostApplyVisitor(SidedClassVisitor.CLIENT);
		} else {
			extraRemapperConfig = null;
		}

		try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
			final TinyMappingsService.Options mappingsOptions = getMappingsServiceOptions().get();
			final TinyMappingsService mappingsService = serviceFactory.get(mappingsOptions);
			final MemoryMappingTree mappings = mappingsService.getMappingTree();

			MinecraftJarRemap.run(new MinecraftJarRemap.Options(
					null,
					inputJar,
					outputJar,
					getRemapClasspath().getFiles().stream()
							.map(File::toPath)
							.toList(),
					mappings,
					getFromNamespace().get(),
					getToNamespace().get(),
					getForgeLike().get(),
					getInjectMixinExtension().get(),
					getFixRecords().get(),
					getValidateTargetNamespace().get(),
					Set.copyOf(getInnerClassNames().get()),
					Set.copyOf(getKnownIndyBsms().get()),
					readAnnotations(),
					Map.copyOf(getSignatureFixes().getOrElse(Map.of())),
					getObjectHolderClassName().getOrNull(),
					getObjectHolderSourceNamespace().getOrNull(),
					getObjectHolderTargetNamespace().get(),
					extraRemapperConfig
			));
		}
	}

	/**
	 * 写出 pom，并把「声明出来的输出」与「{@link LocalMavenHelper#savePom} 实际写入的位置」对齐校验.
	 *
	 * <p>内容不在这里生成：模板与替换都由 {@code savePom} 负责（它写的是 {@code mod_compile_template.pom}
	 * 模板加上 group/name/version 三处替换，且以「唯一临时文件 + 原子 move」落位）。本方法只做三件事：
	 * 校验声明位置就是它会写入的位置、保证构件目录存在、以及确认写出的确实是本任务声明的那个文件。
	 *
	 * <p>若不校验，坐标式解析会在该位置长期读到「缺 pom」或「别处来的 pom」——前者让依赖解析失败，
	 * 后者更糟：依赖能解析但拿到的是另一份内容。
	 */
	private void writePom(Path outputJar) throws IOException {
		if (!getOutputPom().isPresent()) {
			// 未声明 = 本次不产出 pom。接线侧（registerRemapTask）必定声明它，见 getOutputPom() 的说明
			return;
		}

		if (!getPomGroup().isPresent() || !getPomName().isPresent() || !getPomVersion().isPresent() || !getPomMavenRoot().isPresent()) {
			throw new IllegalStateException("声明了 pom 输出 %s，但缺少生成它所需的坐标（pomGroup=%s, pomName=%s, pomVersion=%s, pomMavenRoot=%s）。"
					.formatted(getOutputPom().get().getAsFile(),
							getPomGroup().getOrNull(), getPomName().getOrNull(), getPomVersion().getOrNull(), getPomMavenRoot().getOrNull()));
		}

		final Path pom = getOutputPom().get().getAsFile().toPath().toAbsolutePath().normalize();
		final LocalMavenHelper mavenHelper = mavenHelper();

		// 用 LocalMavenHelper 自己的公开接口复算落位：group 的点号展开、name/version 的拼接仍只由它负责，
		// 本任务只按「pom 是 jar 的同名兄弟」把扩展名换掉（见 pomPathFor）。两侧都必须与声明出来的位置一致：
		//   - 与 savePom 的落位一致，才能保证写出的就是声明的那个文件；
		//   - 与 jar 的落位一致（同一构件目录），才能保证 pom 与 jar 成对。
		final Path expectedPom = pomPathFor(
				mavenHelper.root().resolve(mavenHelper.getRelativeArtifactPath(null)).toAbsolutePath().normalize());

		if (!expectedPom.equals(pom) || !pomPathFor(outputJar.toAbsolutePath().normalize()).equals(pom)) {
			throw new IllegalStateException("声明的 pom 输出 %s 与 LocalMavenHelper 会写入的 pom %s 不是同一个文件"
					.formatted(pom, expectedPom)
					+ "（或与声明的 jar %s 不同目录/不同名）。".formatted(outputJar)
					+ "pom 的位置由 LocalMavenHelper 按 group/name/version 展开，本任务的 pom 输出必须与之一致，"
					+ "否则坐标式依赖解析会缺 pom，或拿到不在构件目录里的那份。");
		}

		// savePom 不建目录：它把内容写进目标同目录的临时文件，目录不存在会直接失败
		Files.createDirectories(pom.getParent());
		mavenHelper.savePom();

		if (Files.notExists(pom)) {
			throw new IllegalStateException("savePom 之后本任务声明的 pom 输出 %s 仍不存在：说明声明的坐标/仓库根与实际落位不一致。"
					.formatted(pom));
		}
	}

	/**
	 * 写出 jar 的逐字节副本（backup）.
	 *
	 * <p>顺序上是最后一步：配置期把 backup 当作「产物齐了」的就绪标志（见 {@code createBackupJars} 的注释），
	 * 消费者（genSources）也按 {@code <jar>.backup} 找它，所以它必须在本任务的其它产物都落位之后再出现。
	 */
	private void writeBackupJar(Path outputJar) throws IOException {
		if (!getOutputBackupJar().isPresent()) {
			// 未声明 = 本 provider 不需要 backup（例如中间映射产物不会被反编译）
			return;
		}

		final Path backupJar = getOutputBackupJar().get().getAsFile().toPath().toAbsolutePath().normalize();
		final String expectedName = outputJar.getFileName() + ".backup";
		final Path artifactJar = outputJar.toAbsolutePath().normalize();

		// 消费者按固定规则找这个文件，声明的位置必须与之一致：否则会写出一份没人读的副本，
		// 而 genSources 会在真正的路径上找不到输入（抛 IllegalStateException）
		if (!expectedName.equals(backupJar.getFileName().toString()) || !artifactJar.getParent().equals(backupJar.getParent())) {
			throw new IllegalStateException("声明的 backup 输出 %s 必须与 jar %s 同目录且名为 %s。"
					.formatted(backupJar, artifactJar, expectedName));
		}

		// 副本与正本同处共享 maven 仓库，必须原子落位，避免读方读到半写的 backup 而误判产物就绪
		AtomicFiles.copy(artifactJar, backupJar);
	}

	/**
	 * {@return 与产物 jar 同名同目录、只有扩展名不同的 pom 路径}.
	 *
	 * <p>{@link LocalMavenHelper} 用同一对 {@code name/version} 计算构件的 jar 与 pom
	 * （jar 是 {@code %s-%s.jar}、pom 是 {@code %s-%s.pom}），两者文件名主干完全相同，只差扩展名。
	 * 因此「pom 是 jar 的同名兄弟」这一条就足以确定 pom 位置，不需要在任务侧复算 maven 目录布局
	 * （group 的点号转斜杠等规则仍只由 LocalMavenHelper 负责）。
	 *
	 * <p>只认构件可能的扩展名（{@link LocalMavenHelper#copyToMaven} 接受的 {@code .jar} / {@code .zip}）：
	 * 若按「最后一个点」盲切，像 {@code minecraft-merged-1.20.1-yarn} 这种没有扩展名、但名字里带版本号点号的
	 * 路径会被切出一个看似合理却错误的 pom 名——那会让产物写到没人读的位置，且不报错。
	 *
	 * @param artifactJar 构件 jar 的路径
	 */
	public static Path pomPathFor(Path artifactJar) {
		final String fileName = artifactJar.getFileName().toString();
		final String stem;

		if (fileName.endsWith(".jar")) {
			stem = fileName.substring(0, fileName.length() - ".jar".length());
		} else if (fileName.endsWith(".zip")) {
			stem = fileName.substring(0, fileName.length() - ".zip".length());
		} else {
			throw new IllegalArgumentException("产物 %s 不是 .jar / .zip 构件，无法推导它的 pom".formatted(artifactJar));
		}

		return artifactJar.resolveSibling(stem + ".pom");
	}

	/** {@return 按当前输入构造的 maven 构件助手，用于复用 {@code savePom} 的内容与落位规则}. */
	private LocalMavenHelper mavenHelper() {
		return new LocalMavenHelper(getPomGroup().get(), getPomName().get(), getPomVersion().get(), null,
				getPomMavenRoot().get().toPath());
	}

	private AnnotationsData readAnnotations() {
		if (getAnnotationsJson().isPresent()) {
			return AnnotationsData.GSON.fromJson(getAnnotationsJson().get(), AnnotationsData.class);
		}

		return null;
	}
}
