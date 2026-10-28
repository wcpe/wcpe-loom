package dev.architectury.loom.forge.tool;

import java.util.Collection;
import java.util.List;

import dev.architectury.loom.util.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;
import org.jspecify.annotations.Nullable;

/**
 * Contains helpers for executing Forge's command line tools
 * with suppressed output streams to prevent annoying log spam.
 *
 * <p>To execute Forge tools during project config, use {@link ForgeToolValueSource};
 * to execute them during config or tasks, use {@link ForgeToolService}.
 */
public final class ForgeToolExecutor {
	private ForgeToolExecutor() {
	}

	public static boolean shouldShowVerboseStdout(Project project) {
		// if running with INFO or DEBUG logging
		return project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0;
	}

	public static boolean shouldShowVerboseStderr(Project project) {
		// if stdout is shown or stacktraces are visible so that errors printed to stderr show up
		return shouldShowVerboseStdout(project) || project.getGradle().getStartParameter().getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS;
	}

	public static Settings getDefaultSettings(Project project) {
		final Settings settings = project.getObjects().newInstance(Settings.class);
		settings.getExecutable().set(JavaExecutableFetcher.getJavaToolchainExecutable(project));
		settings.getShowVerboseStdout().set(shouldShowVerboseStdout(project));
		settings.getShowVerboseStderr().set(shouldShowVerboseStderr(project));

		// call this to ensure the fields aren't null when used in services
		// (otherwise, the JSON serialization crashes)
		settings.getProgramArgs();
		settings.getJvmArgs();
		settings.getMainClass();
		settings.getExecClasspath();

		return settings;
	}

	static ExecResult exec(ExecOperations execOperations, Settings settings) {
		return execOperations.javaexec(spec -> applyToSpec(settings, spec));
	}

	private static void applyToSpec(Settings settings, JavaExecSpec spec) {
		final @Nullable String executable = settings.getExecutable().getOrNull();
		if (executable != null) spec.setExecutable(executable);
		final @Nullable String mainClass = settings.getMainClass().getOrNull();
		if (mainClass != null) spec.getMainClass().set(mainClass);
		spec.setArgs(settings.getProgramArgs().get());
		spec.setJvmArgs(settings.getJvmArgs().get());
		spec.setClasspath(settings.getExecClasspath());

		if (settings.getShowVerboseStdout().get()) {
			spec.setStandardOutput(System.out);
		} else {
			spec.setStandardOutput(NullOutputStream.INSTANCE);
		}

		if (settings.getShowVerboseStderr().get()) {
			spec.setErrorOutput(System.err);
		} else {
			spec.setErrorOutput(NullOutputStream.INSTANCE);
		}
	}

	static void copySettings(Settings source, Settings target) {
		target.getExecutable().set(source.getExecutable());
		target.getMainClass().set(source.getMainClass());
		target.getProgramArgs().set(source.getProgramArgs());
		target.getJvmArgs().set(source.getJvmArgs());
		target.getExecClasspath().setFrom(source.getExecClasspath());
		target.getShowVerboseStdout().set(source.getShowVerboseStdout());
		target.getShowVerboseStderr().set(source.getShowVerboseStderr());
	}

	public interface Settings {
		@Input
		@Optional
		Property<String> getExecutable();

		@Input
		@Optional
		ListProperty<String> getProgramArgs();

		@Input
		@Optional
		ListProperty<String> getJvmArgs();

		@Input
		@Optional
		Property<String> getMainClass();

		@Classpath
		ConfigurableFileCollection getExecClasspath();

		/**
		 * 是否把工具的标准输出透传到构建日志.
		 *
		 * <p>标记为 {@code @Internal} 而**不是** {@code @Input}：本值只决定「输出流接哪里」，
		 * 不参与任何产物字节。而它的取值随构建日志级别变化（{@link #shouldShowVerboseStdout}），
		 * 若当 {@code @Input}，则「换一个日志级别」就等于换了一整套缓存键。
		 *
		 * <p>实测后果（2026-10-27，AllinGame）：节点 {@code generateForgePrePatchJar} 标了
		 * {@code @CacheableTask} 却永远撞不到同一键，每轮重跑整条 10 步 MCP 链，
		 * 直接造成 forge 全量 26 分钟的头部开销；下游 {@code generateForgePatchedIntermediateJar}
		 * /{@code manipulateForgeMappings}/{@code accessTransformForgeJar}/{@code generateForgePatchedJar}
		 * 全部跟着重跑。复合构建下更糟：每个构建根各自一份，重复行数成倍上涨。
		 */
		@Internal
		Property<Boolean> getShowVerboseStdout();

		/** 同 {@link #getShowVerboseStdout()}：只影响日志透传，不影响产物. */
		@Internal
		Property<Boolean> getShowVerboseStderr();

		default void classpath(Object... paths) {
			getExecClasspath().from(paths);
		}

		default void setClasspath(Object... paths) {
			getExecClasspath().setFrom(paths);
		}

		default void args(String... args) {
			getProgramArgs().addAll(args);
		}

		default void args(Collection<String> args) {
			getProgramArgs().addAll(args);
		}

		default void setArgs(List<String> args) {
			getProgramArgs().set(args);
		}

		default void jvmArgs(String... args) {
			getJvmArgs().addAll(args);
		}

		default void jvmArgs(Collection<String> args) {
			getJvmArgs().addAll(args);
		}
	}
}
