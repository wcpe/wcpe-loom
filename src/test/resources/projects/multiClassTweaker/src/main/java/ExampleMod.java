import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
	@Override
	public void onInitialize() {
		// 这个成员由依赖 mod 的 universal.accesswidener 开放。
		// 两个声明只要漏掉任何一个，本工程都构建不过（旧的单声明路径会直接抛异常）。
		RegistryKey<Biome> key = BiomeKeys.register("dummy");
		System.out.println(key);
	}
}
