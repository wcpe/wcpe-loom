package dev.architectury.loom.accesstransformer;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.forge.tool.ForgeToolExecutor;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.forge.tool.JavaExecutableFetcher;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * A service that executes the access transformer tool.
 * The tool information and the AT files are specified in the options.
 */
public final class AccessTransformerService extends Service<AccessTransformerService.Options> {
	public static final ServiceType<Options, AccessTransformerService> TYPE = new ServiceType<>(Options.class, AccessTransformerService.class);

	public interface Options extends Service.Options {
		@InputFiles
		@PathSensitive(PathSensitivity.NONE)
		ConfigurableFileCollection getAccessTransformers();

		@Input
		Property<String> getMainClass();

		@Classpath
		ConfigurableFileCollection getClasspath();

		@Nested
		Property<ForgeToolService.Options> getToolServiceOptions();
	}

	/**
	 * 执行期启动 AT 工具所需的纯值.
	 *
	 * <p>这些值都只能在配置期解析：工具 classpath 来自项目依赖解析，JVM 可执行文件来自 Java 工具链。
	 * 解析一次后以纯值保存，执行期就能在没有 {@code Project} 的情况下重建服务选项。
	 *
	 * @param mainClass AT 工具的主类
	 * @param classpath AT 工具及其依赖的 jar 绝对路径
	 * @param javaExecutable 执行 AT 工具的 JVM 可执行文件；未配置 Java 工具链时为 {@code null}，表示沿用 Gradle 自身的 JVM
	 * @param verboseStdout 是否把工具的标准输出透传到构建日志
	 * @param verboseStderr 是否把工具的标准错误透传到构建日志
	 */
	public record Tool(String mainClass, List<String> classpath, @Nullable String javaExecutable,
			boolean verboseStdout, boolean verboseStderr) implements Serializable {
		public Tool {
			classpath = List.copyOf(classpath);
		}
	}

	public static Provider<Options> createOptions(Project project, Object atFiles) {
		return TYPE.create(project, options -> {
			LoomVersions accessTransformer = chooseAccessTransformer(project);
			FileCollection classpath = new DependencyDownloader(project)
					.add(accessTransformer.mavenNotation())
					.add(LoomVersions.ASM.mavenNotation())
					.platform(LoomVersions.ACCESS_TRANSFORMERS_LOG4J_BOM.mavenNotation())
					.download();

			options.getMainClass().set(mainClass(accessTransformer));
			options.getAccessTransformers().from(atFiles);
			options.getClasspath().from(classpath);
			options.getToolServiceOptions().set(ForgeToolService.createOptions(project));
		});
	}

	/**
	 * 执行期创建服务选项.
	 *
	 * <p>与 {@link #createOptions(Project, Object)} 等价，区别只在于 AT 工具的信息与工具服务的设置
	 * 都已经在配置期解析成纯值，因此本方法不需要 {@code Project}。
	 *
	 * @param objects 执行期可用的对象工厂
	 * @param tool 配置期解析出的 AT 工具信息
	 * @param atFiles 待应用的 access transformer 文件
	 * @return 服务选项
	 */
	public static Options createOptions(ObjectFactory objects, Tool tool, Object atFiles) {
		return ServiceType.createOptions(objects, TYPE, options -> {
			options.getMainClass().set(tool.mainClass());
			options.getAccessTransformers().from(atFiles);
			options.getClasspath().from(tool.classpath());
			options.getToolServiceOptions().set(createToolServiceOptions(objects, tool));
		});
	}

	/**
	 * 配置期解析 AT 工具，得到执行期可用的信息.
	 *
	 * <p>工具 classpath 来自项目依赖解析、JVM 可执行文件来自 Java 工具链，两者都只能在配置期取得，
	 * 因此在这里一次性解析出来再传给执行期。
	 *
	 * @param project 当前项目
	 * @return AT 工具信息
	 */
	public static Tool resolveTool(Project project) {
		final LoomVersions accessTransformer = chooseAccessTransformer(project);
		final FileCollection classpath = new DependencyDownloader(project)
				.add(accessTransformer.mavenNotation())
				.add(LoomVersions.ASM.mavenNotation())
				.platform(LoomVersions.ACCESS_TRANSFORMERS_LOG4J_BOM.mavenNotation())
				.download();

		return new Tool(
				mainClass(accessTransformer),
				classpath.getFiles().stream().map(File::getAbsolutePath).toList(),
				JavaExecutableFetcher.getJavaToolchainExecutable(project).getOrNull(),
				ForgeToolExecutor.shouldShowVerboseStdout(project),
				ForgeToolExecutor.shouldShowVerboseStderr(project)
		);
	}

	private static ForgeToolService.Options createToolServiceOptions(ObjectFactory objects, Tool tool) {
		return ServiceType.createOptions(objects, ForgeToolService.TYPE, options -> {
			final ForgeToolExecutor.Settings settings = objects.newInstance(ForgeToolExecutor.Settings.class);

			if (tool.javaExecutable() != null) {
				settings.getExecutable().set(tool.javaExecutable());
			}

			settings.getShowVerboseStdout().set(tool.verboseStdout());
			settings.getShowVerboseStderr().set(tool.verboseStderr());

			// 与 ForgeToolExecutor.getDefaultSettings 同理，先把属性都取一遍，
			// 否则 JSON 序列化这些选项时字段为 null
			settings.getProgramArgs();
			settings.getJvmArgs();
			settings.getMainClass();
			settings.getExecClasspath();

			options.getBaseSettings().set(settings);
		});
	}

	private static String mainClass(LoomVersions accessTransformer) {
		return accessTransformer.equals(LoomVersions.ACCESS_TRANSFORMERS_NEO)
				? "net.neoforged.accesstransformer.cli.TransformerProcessor"
				: "net.minecraftforge.accesstransformer.TransformerProcessor";
	}

	public static Provider<Options> createOptionsForLoaderAts(Project project, TempFiles tempFiles) {
		final Provider<List<String>> atFiles = project.provider(() -> {
			LoomGradleExtension extension = LoomGradleExtension.get(project);
			Path userdevJar = extension.getForgeUserdevProvider().getUserdevJar().toPath();
			return extractAccessTransformers(userdevJar, extension.getForgeUserdevProvider().getConfig().ats(), tempFiles);
		});
		return createOptions(project, atFiles);
	}

	private static List<String> extractAccessTransformers(Path jar, UserdevConfig.AccessTransformerLocation location, TempFiles tempFiles) throws IOException {
		final List<String> extracted = new ArrayList<>();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getReadOnlyJarFileSystem(jar)) {
			for (Path atFile : getAccessTransformerPaths(fs, location)) {
				byte[] atBytes;

				try {
					atBytes = Files.readAllBytes(atFile);
				} catch (NoSuchFileException e) {
					continue;
				}

				Path tmpFile = tempFiles.file("at-conf", ".cfg");
				Files.write(tmpFile, atBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				extracted.add(tmpFile.toAbsolutePath().toString());
			}
		}

		return extracted;
	}

	private static List<Path> getAccessTransformerPaths(FileSystemUtil.Delegate fs, UserdevConfig.AccessTransformerLocation location) throws IOException {
		return location.visitIo(directory -> {
			Path dirPath = fs.getPath(directory);

			try (Stream<Path> paths = Files.list(dirPath)) {
				return paths.toList();
			}
		}, paths -> paths.stream().map(fs::getPath).toList());
	}

	public AccessTransformerService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	private static LoomVersions chooseAccessTransformer(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		boolean serverBundleMetadataPresent = extension.getMinecraftProvider().getServerBundleMetadata() != null;

		if (!serverBundleMetadataPresent) {
			return LoomVersions.ACCESS_TRANSFORMERS;
		} else if (extension.isNeoForge()) {
			MinecraftVersionMeta.JavaVersion javaVersion = extension.getMinecraftProvider().getVersionInfo().javaVersion();

			if (javaVersion != null && javaVersion.majorVersion() >= 21) {
				return LoomVersions.ACCESS_TRANSFORMERS_NEO;
			}
		}

		return LoomVersions.ACCESS_TRANSFORMERS_NEW;
	}

	public void execute(Path input, Path output) throws IOException {
		final List<String> args = new ArrayList<>();
		args.add("--inJar");
		args.add(input.toAbsolutePath().toString());
		args.add("--outJar");
		args.add(output.toAbsolutePath().toString());

		for (File atFile : getOptions().getAccessTransformers().getFiles()) {
			args.add("--atFile");
			args.add(atFile.getAbsolutePath());
		}

		final ForgeToolService toolService = getServiceFactory().get(getOptions().getToolServiceOptions());
		toolService.exec(spec -> {
			spec.getMainClass().set(getOptions().getMainClass());
			spec.setArgs(args);
			spec.setClasspath(getOptions().getClasspath());
		});
	}
}
