package net.fabricmc.loom.test.integration.buildSrc.loomClasspath

import org.gradle.api.Plugin
import org.gradle.api.Project

class TestPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		// Register the project-scoped name used by the broken implementation from this
		// convention-plugin classloader. Loom must not cast this foreign service to its
		// own strongly typed LoomCacheService.
		project.gradle.sharedServices.registerIfAbsent("loomSharedCache:${project.path}", ForeignCacheService) { }
	}
}
