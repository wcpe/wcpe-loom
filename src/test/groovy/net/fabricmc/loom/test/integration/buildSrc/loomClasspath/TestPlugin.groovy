package net.fabricmc.loom.test.integration.buildSrc.loomClasspath

import org.gradle.api.Plugin
import org.gradle.api.Project

class TestPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		// Register a same-name service from the convention plugin classloader.
		// Loom must not cast this foreign registration to its own LoomCacheService.
		project.gradle.sharedServices.registerIfAbsent("loomSharedCache", ForeignCacheService) { }
	}
}
