package dev.architectury.loom.forge.tool;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;

import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * 服务化的「一次外部 Forge 工具调用」.
 *
 * <p>它是 {@link ForgeToolService} 的「带工具声明」版本：{@code ForgeToolService} 只负责执行器本身
 * （JVM 可执行文件、日志透传等与项目状态相关的基设置），本服务额外把**工具本体**——承载它的 jar 集合、
 * 主类与参数——放到 {@link Options} 上声明出去。
 *
 * <h2>为什么要有这一层</h2>
 * 改造前这类调用走 {@link ForgeToolValueSource#exec}：它由**项目**的 {@code ProviderFactory} 创建
 * ValueSource 并立刻求值，因此只能在配置期同步执行；工具 classpath 也在那一刻被
 * {@code DependencyDownloader} 解析掉。把工具声明搬进服务选项后：
 * <ul>
 *   <li>classpath 是惰性的（配置期只接线，取值推迟到真正执行时），于是它既能在配置期路径里被解析，
 *       也能作为任务的 {@code @Classpath} 输入在执行期取值，并获得正确的 up-to-date 判定；</li>
 *   <li>发起调用只需要一个 {@link ServiceFactory}，不再需要 {@code Project}，因此同一次调用既能由
 *       配置期路径发起，也能由执行期任务发起；</li>
 *   <li>服务只持有选项，选项可以被配置缓存序列化，因此可以作为任务的 {@code @Nested} 输入携带。</li>
 * </ul>
 *
 * <h2>参数模板</h2>
 * 工具的参数以「模板」形式声明（{@link Options#getArgsTemplate()}）：模板里可以留 {@code {name}}
 * 形式的占位符，由调用方在 {@link #exec} 时给出实际取值。这样一来，配置期才能确定的取值（例如工具任务名、
 * 输入 jar 路径）可以就地定死在模板里，而只有调用时才知道的路径（例如本次调用的临时输出文件）留作占位符，
 * 两者都是任务声明的输入，执行期不必回读项目模型。
 *
 * <h2>调用只有一处构造</h2>
 * {@link #settingsFor} 造出本次调用实际交给 Gradle 的工具设置，{@link #exec} 就是把它交给
 * {@link ForgeToolService} 去执行。两者不会分叉，因此「实际执行的命令行与 classpath」可以被直接断言。
 */
public final class ForgeExternalToolService extends Service<ForgeExternalToolService.Options> {
	public static final ServiceType<Options, ForgeExternalToolService> TYPE = new ServiceType<>(Options.class, ForgeExternalToolService.class);

	public interface Options extends Service.Options {
		/**
		 * 工具本体及其依赖的 jar.
		 *
		 * <p>按类路径语义参与 up-to-date 判定：工具 jar 换了内容就该重新执行。
		 */
		@Classpath
		ConfigurableFileCollection getClasspath();

		/**
		 * 工具主类.
		 *
		 * <p>多数工具把它写在 jar 清单里（见 {@link #manifestMainClass}），少数直接是一个常量。
		 */
		@Input
		Property<String> getMainClass();

		/**
		 * 工具的参数模板，其中的 {@code {name}} 占位符由 {@link #exec} 替换.
		 */
		@Input
		ListProperty<String> getArgsTemplate();

		/**
		 * 执行器的基设置（JVM 可执行文件、日志透传）.
		 */
		@Nested
		Property<ForgeToolService.Options> getToolServiceOptions();
	}

	/**
	 * 创建工具选项.
	 *
	 * <p>只接线、不求值：{@code toolClasspath} 的取值被推迟到 {@link #exec}（或任务的输入快照）时，
	 * 因此本方法可以在配置期安全调用，也可以把返回的 provider 挂成任务的 {@code @Nested} 输入。
	 *
	 * @param project 当前项目，用于惰性 provider 与执行器基设置
	 * @param toolClasspath 工具及其依赖的 jar
	 * @param mainClass 工具主类
	 * @return 工具选项
	 */
	public static Provider<Options> createOptions(Project project, FileCollection toolClasspath, Provider<String> mainClass) {
		return TYPE.create(project, options -> {
			options.getClasspath().from(toolClasspath);
			options.getMainClass().set(mainClass);
			options.getToolServiceOptions().set(ForgeToolService.createOptions(project));
		});
	}

	/**
	 * 创建工具选项，主类固定为一个常量（工具清单里没有 {@code Main-Class} 时用）.
	 *
	 * @param project 当前项目
	 * @param toolClasspath 工具及其依赖的 jar
	 * @param mainClass 工具主类
	 * @return 工具选项
	 */
	public static Provider<Options> createOptions(Project project, FileCollection toolClasspath, String mainClass) {
		return createOptions(project, toolClasspath, project.provider(() -> mainClass));
	}

	/**
	 * 创建工具选项，主类取工具 jar 清单里的 {@code Main-Class}.
	 *
	 * @param project 当前项目
	 * @param toolClasspath 工具及其依赖的 jar
	 * @return 工具选项
	 */
	public static Provider<Options> createOptionsFromManifest(Project project, FileCollection toolClasspath) {
		return createOptions(project, toolClasspath, project.provider(() -> manifestMainClass(toolClasspath)));
	}

	/**
	 * {@return 给定 jar 集合里第一个清单含 {@code Main-Class} 的那个值}.
	 *
	 * <p>与改造前 {@code MinecraftPatchedProvider.getMainClass} 逐字一致：只认 {@code .jar} 结尾的条目，
	 * 读取失败的异常累积下来，全部读完仍没有主类时抛出。
	 *
	 * @param files 工具及其依赖的 jar（已解析）
	 */
	public static String manifestMainClass(final Iterable<File> files) {
		String mainClass = null;
		IOException ex = null;

		for (File file : files) {
			if (file.getName().endsWith(".jar")) {
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(file.toPath())) {
					final Path mfPath = fs.getPath("META-INF/MANIFEST.MF");

					if (Files.exists(mfPath)) {
						try (InputStream in = Files.newInputStream(mfPath)) {
							mainClass = new Manifest(in).getMainAttributes().getValue("Main-Class");
						}
					}
				} catch (final IOException e) {
					if (ex == null) {
						ex = e;
					} else {
						ex.addSuppressed(e);
					}
				}

				if (mainClass != null) {
					break;
				}
			}
		}

		if (mainClass == null) {
			if (ex != null) {
				throw new UncheckedIOException(ex);
			} else {
				throw new RuntimeException("Failed to find main class");
			}
		}

		return mainClass;
	}

	public ForgeExternalToolService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	/**
	 * 展开参数模板，构造本次调用交给 Gradle 的工具设置.
	 *
	 * <p>{@link #exec} 走的就是这里，因此返回值的每一个字段都是「实际执行时」的取值：可以直接断言
	 * 命令行（{@link ForgeToolExecutor.Settings#getMainClass()} 与
	 * {@link ForgeToolExecutor.Settings#getProgramArgs()}）、classpath（{@code getExecClasspath()}）
	 * 与执行器（{@code getExecutable()}）。
	 *
	 * <p>工作目录与环境变量不在这里：{@link ForgeToolExecutor} 从不设置它们，故两者始终沿用 Gradle
	 * 自身的默认值（与改造前一致）。
	 *
	 * @param placeholders 占位符（含花括号）到实际参数的映射；模板里没有匹配到的条目原样保留
	 * @return 工具设置
	 */
	public ForgeToolExecutor.Settings settingsFor(Map<String, String> placeholders) {
		final ForgeToolService.Options toolOptions = getOptions().getToolServiceOptions().get();
		final ForgeToolExecutor.Settings settings = toolOptions.getObjects().newInstance(ForgeToolExecutor.Settings.class);
		ForgeToolExecutor.copySettings(toolOptions.getBaseSettings().get(), settings);

		settings.setClasspath(getOptions().getClasspath());
		settings.getMainClass().set(getOptions().getMainClass());

		for (String template : getOptions().getArgsTemplate().get()) {
			settings.args(placeholders.getOrDefault(template, template));
		}

		return settings;
	}

	/**
	 * 执行工具.
	 *
	 * <p>命令行与 classpath 由 {@link #settingsFor} 给出，本方法只把它交给 {@link ForgeToolService}：
	 * 那个服务负责注入执行器（JVM 与日志透传），并通过 {@code ValueSource} 在**执行那一刻**发起进程，
	 * 因此调用点搬到任务里也不会在配置期触发求值。
	 *
	 * @param placeholders 占位符（含花括号）到实际参数的映射
	 */
	public void exec(Map<String, String> placeholders) {
		final ForgeToolExecutor.Settings settings = settingsFor(placeholders);
		final ForgeToolService toolService = getServiceFactory().get(getOptions().getToolServiceOptions());
		toolService.exec(spec -> ForgeToolExecutor.copySettings(settings, spec));
	}

	/**
	 * {@return 参数模板里出现的全部占位符}.
	 *
	 * <p>供诊断与测试断言使用：调用方据此知道该给 {@link #exec} 准备哪些取值。
	 */
	public List<String> placeholders() {
		return getOptions().getArgsTemplate().get().stream()
				.filter(arg -> arg.length() > 2 && arg.startsWith("{") && arg.endsWith("}"))
				.distinct()
				.toList();
	}
}
