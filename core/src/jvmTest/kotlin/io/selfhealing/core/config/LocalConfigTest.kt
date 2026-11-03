package io.selfhealing.core.config

import java.io.File
import kotlin.test.Test

/** Parses the developer's own config.yml when present, so typos surface in `./gradlew test`. */
class LocalConfigTest {
    @Test
    fun localConfigParses() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "config.yml") }.firstOrNull { it.isFile } ?: return
        val config = ConfigLoader.parse(file.readText(), System.getenv() + mapOf("SENTRY_AUTH_TOKEN" to "x", "GITHUB_TOKEN" to "x"))
        config.sentry.projects.forEach { p -> checkNotNull(config.repos.firstOrNull { it.id == p.repo }) { "Unknown repo ${p.repo}" } }
    }
}
