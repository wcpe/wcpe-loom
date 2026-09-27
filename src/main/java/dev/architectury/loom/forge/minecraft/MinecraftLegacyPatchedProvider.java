/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package dev.architectury.loom.forge.minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import dev.architectury.loom.forge.CoreModManagerTransformer;
import dev.architectury.loom.forge.dependency.ForgeProvider;
import dev.architectury.loom.forge.dependency.PatchProvider;
import dev.architectury.loom.util.Stopwatch;
import dev.architectury.loom.util.ThreadingUtils;
import org.gradle.api.Project;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftLegacyPatchedProvider extends MinecraftPatchedProvider {
	// Step 1: Binary Patch
	private Path minecraftClientPatchedJar;
	private Path minecraftServerPatchedJar;
	// Step 2: Merge (global)
	private Path minecraftMergedPatchedJar;
	// Step 4: Access Transform (global or project)
	private Path minecraftPatchedAtJar;

	private Path forgeJar;

	public MinecraftLegacyPatchedProvider(Project project, MinecraftProvider minecraftProvider, Type type) {
		super(project, minecraftProvider, type);
	}

	private LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(project);
	}

	@Override
	public void provide() throws Exception {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = ForgeProvider.getForgeCache(project);
		String patchId = "forge-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		minecraftClientPatchedJar = forgeWorkingDir.resolve("client-patched.jar");
		minecraftServerPatchedJar = forgeWorkingDir.resolve("server-patched.jar");
		minecraftMergedPatchedJar = forgeWorkingDir.resolve("merged-patched.jar");
		minecraftPatchedAtJar = forgeWorkingDir.resolve(type.id + "-at-patched.jar");
		forgeJar = forgeWorkingDir.resolve("forge.jar");

		// 无锁快路径：产物齐备且补丁版本最新时，本次仅做内存配置，不触碰共享缓存，不取锁
		if (!needsWork()) {
			dirty = false;
			return;
		}

		withPatchedLock(() -> {
			// 锁内二次确认：等锁期间其它进程可能已完成生产。本方法不删除任何共享产物，
			// 需要重建时由 checkCache 的返回值表达，缺失或损坏的产物由 remapJar 的可复用性判定按件补齐
			dirty = checkCache();
			return null;
		});
	}

	/**
	 * {@return 是否需要重建 legacy patched jar}.
	 *
	 * <p>产物缺失或 Loom 补丁版本过期即需要工作。manifest 读取遇到损坏文件（多进程并发下的
	 * 半截产物）时按需要工作处理，进入锁内走完整判定。
	 */
	private boolean needsWork() {
		if (getExtension().refreshDeps() || Stream.of(getGlobalCaches()).anyMatch(jar -> !isReusableJar(jar))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(minecraftPatchedAtJar) || !isPatchedJarUpToDate(forgeJar);
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * 锁内二次确认：判定现有产物能否复用，并返回本次是否需要整链重建.
	 *
	 * <p>与父类一致，本方法不再删除共享产物：删除会让锁外只做存在性判定的读方拿到缺失文件，
	 * 也会让其它工作树互相触发重建。legacy 链没有单一版本标记产物，任一件缺失即无法确认
	 * 其余产物与当前算法同代，因此按整链重建处理（各件仍以「临时文件 + 原子落位」重新生成）。
	 *
	 * @return 是否需要整链重建
	 */
	@Override
	protected boolean checkCache() throws IOException {
		if (getExtension().refreshDeps()) {
			// 显式刷新（--refresh-dependencies / -Dloom.refresh）：强制整链重建
			return true;
		}

		if (Stream.of(getGlobalCaches()).anyMatch(jar -> !isReusableJar(jar))) {
			return true;
		}

		try {
			return !isPatchedJarUpToDate(minecraftPatchedAtJar) || !isPatchedJarUpToDate(forgeJar);
		} catch (IOException e) {
			// manifest 读不出来：共享产物被外部破坏，同样按整链重建处理
			return true;
		}
	}

	protected Path[] getGlobalCaches() {
		return new Path[] {
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftMergedPatchedJar,
				minecraftPatchedAtJar,
				forgeJar,
		};
	}

	@Override
	protected void produceIntermediateJarIfMissing() throws Exception {
		// legacy 链的产物（forge.jar / client-patched.jar / server-patched.jar 等）在 remapJar 阶段生成，
		// provide() 只做路径初始化与「是否需要重建」的判定，因此这里两步都要走一遍（均为幂等快路径）
		provide();

		try (var serviceFactory = new ScopedServiceFactory()) {
			remapJar(serviceFactory);
		}
	}

	@Override
	public void remapJar(ServiceFactory serviceFactory) throws Exception {
		// 无锁快路径：provide 已判定产物齐备（needsWork=false → dirty=false）时，本方法的全链
		// 可复用性判定（存在 + 不是空 zip／截断文件）都会短路，无需取锁；仅当 provide 判定需要工作才进锁。
		// 注意必须带上 !dirty：显式刷新与「补丁版本过期」不再通过删除产物表达，
		// 若这里只判存在性，就会把 provide 设好的强制重建信号清掉。
		if (!dirty
				&& Stream.of(forgeJar, minecraftClientPatchedJar, minecraftServerPatchedJar).allMatch(MinecraftLegacyPatchedProvider::isReusableJar)
				&& (type != Type.MERGED || isReusableJar(minecraftMergedPatchedJar))
				&& isReusableJar(minecraftPatchedAtJar)) {
			return;
		}

		withPatchedLock(() -> {
			// 锁内二次确认：等锁期间其它进程可能已完成生产；所有产物均以「临时文件 + 原子落位」生成
			if (dirty || !isReusableJar(forgeJar)) {
				dirty = true;
				publishAtomically(forgeJar, this::patchForge);
			}

			if (dirty || !isReusableJar(minecraftClientPatchedJar) || !isReusableJar(minecraftServerPatchedJar)) {
				dirty = true;
				patchJars();
			}

			if (type == Type.MERGED && (dirty || !isReusableJar(minecraftMergedPatchedJar))) {
				dirty = true;
				publishAtomically(minecraftMergedPatchedJar, this::mergeJars);
			}

			if (dirty || !isReusableJar(minecraftPatchedAtJar)) {
				dirty = true;
				Path minecraftPatchedJar = switch (type) {
				case CLIENT_ONLY -> minecraftClientPatchedJar;
				case SERVER_ONLY -> minecraftServerPatchedJar;
				case MERGED -> minecraftMergedPatchedJar;
				};
				// AT、Forge 文件复制与补丁版本标记全部在临时文件上完成，最后原子落位
				publishAtomically(minecraftPatchedAtJar, output -> {
					accessTransform(minecraftPatchedJar, output);
					walkFileSystems(forgeJar, output, (path) -> true, this::copyReplacing);
					applyLoomPatchVersion(output);
				});
			}

			return null;
		});
	}

	private void patchForge(Path output) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching forge");

		// output 是本次独占的临时文件：复制与全部加工都在它上面完成，最后原子落位
		Files.copy(getExtension().getForgeUniversalProvider().getForge().toPath(), output);

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(output, false)) {
			// For the development environment, we need to remove the binpatches, otherwise forge will try to re-apply them
			Files.delete(fs.get().getPath("binpatches.pack.lzma"));

			// We also need to remove Forge's jar signature data
			// Based off https://github.com/FabricMC/tiny-remapper/blob/7f341dcbf1bae754ba992f0b0f127566f347f37f/src/main/java/net/fabricmc/tinyremapper/MetaInfFixer.java
			Files.walkFileTree(fs.get().getPath("META-INF"), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
					String fileName = path.getFileName().toString();

					if (fileName.endsWith(".SF") || fileName.endsWith(".DSA") || fileName.endsWith(".RSA") || fileName.endsWith(".EC") || fileName.startsWith("SIG-")) {
						Files.delete(path);
					} else if (path.getNameCount() == 2 && fileName.equals("MANIFEST.MF")) {
						Manifest manifest = new Manifest();

						try (InputStream is = Files.newInputStream(path)) {
							manifest.read(is);
						}

						for (Iterator<Attributes> it = manifest.getEntries().values().iterator(); it.hasNext(); ) {
							Attributes attrs = it.next();

							for (Iterator<Object> it2 = attrs.keySet().iterator(); it2.hasNext(); ) {
								Attributes.Name attrName = (Attributes.Name) it2.next();
								String name = attrName.toString();

								if (name.endsWith("-Digest") || name.contains("-Digest-") || name.equals("Magic")) {
									it2.remove();
								}
							}

							if (attrs.isEmpty()) it.remove();
						}

						try (OutputStream os = Files.newOutputStream(path)) {
							manifest.write(os);
						}
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}

		// Older versions of Forge rely on utility classes from log4j-core 2.0-beta9 but we'll upgrade the runtime to a
		// release version (so we can use the LoggerNamePatternSelector from fabric-log4j-util) where some of those
		// classes have been moved from a `helpers` to a `utils` package.
		// To allow Forge to work regardless, we'll re-package those helper classes into the forge jar.
		Path log4jBeta9 = project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES)
				.getFiles()
				.stream()
				.map(File::toPath)
				.filter(it -> it.getFileName().toString().equals("log4j-core-2.0-beta9.jar"))
				.findAny()
				.orElse(null);
		if (log4jBeta9 != null) {
			Predicate<Path> isHelper = path -> path.startsWith("/org/apache/logging/log4j/core/helpers");
			walkFileSystems(log4jBeta9, output, isHelper, this::copyReplacing);
		}

		// While Forge will discover mods on the classpath, it won't do the same for ATs, coremods or tweakers.
		// ForgeGradle "solves" this problem using a huge, gross hack (GradleForgeHacks and related classes), and only
		// for ATs and coremods, not tweakers.
		// No clue why FG went the hack route when it's the same project and they could have just added first-party
		// support for loading both from the classpath right into Forge (it's even really simply to do).
		// We'll have none of those hacks and instead patch first-party support into Forge.
		ZipUtils.transform(output, Stream.of(new Pair<>(CoreModManagerTransformer.FILE, original -> {
			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new CoreModManagerTransformer(writer), 0);
			return writer.toByteArray();
		})));

		// 补丁版本标记是就绪标记：必须在原子落位之前写入临时文件，读方才能「见到即完整」
		applyLoomPatchVersion(output);

		logger.lifecycle(":patched forge in " + stopwatch.stop());
	}

	private void patchJars() throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		// 两件产物各自「临时文件 + 原子落位」：任一件失败都不会在共享缓存里留下半截 jar，
		// 也不会破坏另一件已发布的有效产物
		publishAtomically(minecraftServerPatchedJar, output -> patchJars(minecraftProvider.getMinecraftServerJar().toPath(), output, patchProvider.extractServerPatches()));
		publishAtomically(minecraftClientPatchedJar, output -> patchJars(minecraftProvider.getMinecraftClientJar().toPath(), output, patchProvider.extractClientPatches()));

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	@Override
	protected void patchJars(Path clean, Path output, Path patches) throws Exception {
		super.patchJars(clean, output, patches);

		// Patching only preserves affected classes, everything else we need to copy manually
		copyMissingClasses(clean, output);
		walkFileSystems(clean, output, file -> !file.toString().endsWith(".class"), this::copyReplacing);

		// Workaround Forge patches apparently violating the JVM spec (see ParameterAnnotationsFixer for details)
		modifyClasses(output, ParameterAnnotationsFixer::new);
	}

	private void mergeJars(Path output) throws Exception {
		logger.info(":merging jars");
		Stopwatch stopwatch = Stopwatch.createStarted();

		// output 是本次独占的临时文件，合并与后续类改写都在它上面完成，最后原子落位
		try (JarMerger jarMerger = new JarMerger(minecraftClientPatchedJar.toFile(), minecraftServerPatchedJar.toFile(), output.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		// The JarMerger adds Sided annotations but so do the Forge patches. The latter doesn't require extra
		// dependencies beyond Forge, so we'll keep those and convert any non-redundant Fabric ones.
		modifyClasses(output, SideAnnotationMerger::new);

		logger.info(":merged jars in " + stopwatch);
	}

	private void modifyClasses(Path jarFile, Function<ClassVisitor, ClassVisitor> func) throws Exception {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] original = Files.readAllBytes(file);

					ClassReader reader = new ClassReader(original);
					ClassWriter writer = new ClassWriter(reader, 0);
					reader.accept(func.apply(writer), 0);

					byte[] modified = writer.toByteArray();

					if (!Arrays.equals(original, modified)) {
						Files.write(file, modified, StandardOpenOption.TRUNCATE_EXISTING);
					}
				});
			}

			completer.complete();
		}
	}

	@Override
	public Path getMinecraftIntermediateJar() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Path getMinecraftPatchedIntermediateJar() {
		return forgeJar; // TODO current used only for ATs
	}

	@Override
	public Path getMinecraftPatchedJar() {
		return minecraftPatchedAtJar;
	}

	/**
	 * It seems that Forge patches produce class files which are in violation of the JVM spec. Specifically, the value
	 * of Runtime[In]VisibleParameterAnnotation.num_parameters is by SE8 spec required to match the number of formal
	 * parameters of the method (and may be ignored in favor of directly looking at the method arguments, which is
	 * indeed what the OpenJDK 8 compiler does). Using a smaller value (possible if e.g. the last parameter has no
	 * annotations) will cause the compiler to read past the end of the table, throwing an exception and therefore being
	 * unable to read the class file.
	 * <br>
	 * This class visitor fixes that by ignoring the original num_parameters value, letting the MethodVisitor compute a
	 * new value based on its signature. This will at first produce an invalid count when there are synthetic parameters
	 * but later, during mergeJars, those will be properly offset (enableSyntheticParamsOffset).
	 * <br>
	 * SE8 JVM spec: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.18
	 * Example method affected: RenderGlobal.ContainerLocalRenderInformation(RenderChunk, EnumFacing, int)
	 */
	private static class ParameterAnnotationsFixer extends ClassVisitor {
		private ParameterAnnotationsFixer(ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

			// This issue has so far only been observed with constructors, so we can skip everything else
			if (name.equals("<init>")) {
				methodVisitor = new MethodFixer(methodVisitor);
			}

			return methodVisitor;
		}

		private static class MethodFixer extends MethodVisitor {
			private MethodFixer(MethodVisitor methodVisitor) {
				super(Opcodes.ASM9, methodVisitor);
			}

			@Override
			public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
				// Not calling visitAnnotableParameterCount will cause it to compute its value from the method signature
			}
		}
	}

	private static class SideAnnotationMerger extends ClassVisitor {
		private static final String FABRIC_ANNOTATION_DESCRIPTOR = "Lnet/fabricmc/api/Environment;";
		private static final String FORGE_ANNOTATION_DESCRIPTOR = "Lnet/minecraftforge/fml/relauncher/SideOnly;";
		private static final String FORGE_SIDE_DESCRIPTOR = "Lnet/minecraftforge/fml/relauncher/Side;";

		private static boolean isSideAnnotation(String descriptor) {
			return FABRIC_ANNOTATION_DESCRIPTOR.equals(descriptor) || FORGE_ANNOTATION_DESCRIPTOR.equals(descriptor);
		}

		private boolean visitedAnnotation;

		private SideAnnotationMerger(ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (isSideAnnotation(descriptor)) {
				if (visitedAnnotation) {
					return null;
				}

				visitedAnnotation = true;
				return new FabricToForgeConverter(super.visitAnnotation(FORGE_ANNOTATION_DESCRIPTOR, true));
			}

			return super.visitAnnotation(descriptor, visible);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			return new FieldSideAnnotationMerger(super.visitField(access, name, descriptor, signature, value));
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			return new MethodSideAnnotationMerger(super.visitMethod(access, name, descriptor, signature, exceptions));
		}

		private static class FieldSideAnnotationMerger extends FieldVisitor {
			private boolean visitedAnnotation;

			private FieldSideAnnotationMerger(FieldVisitor fieldVisitor) {
				super(Opcodes.ASM9, fieldVisitor);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (isSideAnnotation(descriptor)) {
					if (visitedAnnotation) {
						return null;
					}

					visitedAnnotation = true;
					return new FabricToForgeConverter(super.visitAnnotation(FORGE_ANNOTATION_DESCRIPTOR, true));
				}

				return super.visitAnnotation(descriptor, visible);
			}
		}

		private static class MethodSideAnnotationMerger extends MethodVisitor {
			private boolean visitedAnnotation;

			private MethodSideAnnotationMerger(MethodVisitor methodVisitor) {
				super(Opcodes.ASM9, methodVisitor);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (isSideAnnotation(descriptor)) {
					if (visitedAnnotation) {
						return null;
					}

					visitedAnnotation = true;
					return new FabricToForgeConverter(super.visitAnnotation(FORGE_ANNOTATION_DESCRIPTOR, true));
				}

				return super.visitAnnotation(descriptor, visible);
			}
		}

		private static class FabricToForgeConverter extends AnnotationVisitor {
			private FabricToForgeConverter(AnnotationVisitor annotationVisitor) {
				super(Opcodes.ASM9, annotationVisitor);
			}

			@Override
			public void visitEnum(String name, String descriptor, String value) {
				super.visitEnum(name, FORGE_SIDE_DESCRIPTOR, value);
			}
		}
	}
}
