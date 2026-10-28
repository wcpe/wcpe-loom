package net.fabricmc.example.mixinlib;

/** :lib 的普通类，与 mixin 映射无关，只是让该模块有可编译的源码。 */
public class LibEntrypoint {
	public static String greet() {
		return "mixinlib";
	}
}
