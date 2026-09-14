package net.fabricmc.loom.test.integration.buildSrc.loomClasspath

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class ForeignCacheService implements BuildService<BuildServiceParameters.None> {
}
