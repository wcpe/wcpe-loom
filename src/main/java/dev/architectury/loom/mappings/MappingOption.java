package dev.architectury.loom.mappings;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;

public enum MappingOption {
	DEFAULT,
	WITH_SRG,
	WITH_MOJANG,
	/**
	 * 补全了「实现类缺失的继承方法条目」的 srg tiny（仅 legacy Forge 有）.
	 *
	 * <p>与 {@link #WITH_SRG} 分开是必须的：{@code WITH_SRG} 在配置期还要被读成映射树
	 * （{@code srgNamedTree} / {@code LorenzMappingService}），而本选项的产物由执行期任务产出、
	 * 配置期必然不存在。只把它用在「重映射 Minecraft jar」这条执行期路径上。
	 *
	 * <p>非 legacy Forge 形态下本选项未注册，取用会抛 {@code UnsupportedOperationException}。
	 */
	WITH_SRG_INHERITED;

	public static MappingOption forPlatform(LoomGradleExtensionAPI extension) {
		return switch (extension.getPlatform().get()) {
		case FORGE -> WITH_SRG;
		case NEOFORGE -> WITH_MOJANG;
		default -> DEFAULT;
		};
	}
}
