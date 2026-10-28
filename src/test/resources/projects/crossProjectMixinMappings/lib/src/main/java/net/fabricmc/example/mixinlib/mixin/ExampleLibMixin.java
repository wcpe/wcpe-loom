package net.fabricmc.example.mixinlib.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一个真实的客户端 mixin。写法照既有能正常产出 mixin-map 的夹具（mixinApAutoRefmap）：
 * <ul>
 *   <li>目标用 {@code @Mixin(Foo.class)}（公开类不能用 targets）；</li>
 *   <li>注入点用带描述符的成员引用 {@code init()V}，AP 才查得到映射；</li>
 *   <li>必须归到 mixins.json 的 {@code client} 数组（目标是客户端类）。</li>
 * </ul>
 * 本夹具只需要 AP 产出 mixin-map-*.tiny，不验证注入的实际行为。
 */
@Mixin(ChatScreen.class)
public class ExampleLibMixin {
	@Inject(at = @At("HEAD"), method = "init()V")
	private void init(CallbackInfo info) {
		// 不做任何事
	}
}
