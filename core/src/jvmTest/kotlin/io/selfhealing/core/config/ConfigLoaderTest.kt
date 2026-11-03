package io.selfhealing.core.config

import io.selfhealing.core.model.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigLoaderTest {
    private val env = mapOf("SENTRY_AUTH_TOKEN" to "sntrys_x", "GITHUB_TOKEN" to "ghp_x")

    @Test
    fun parsesExampleConfig() {
        val text = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "config.example.yml") }.first { it.isFile }.readText()
        val config = ConfigLoader.parse(text, env)
        assertEquals("sntrys_x", config.sentry.authToken)
        assertEquals("", config.server.publicUrl)
        assertEquals(HarnessType.CLAUDE_CODE, config.harness.type)
        assertEquals("shop", config.repoFor("shop-ios", Platform.IOS)?.id)
        assertEquals("shop", config.repoFor("unmapped", Platform.ANDROID)?.id)
        assertEquals(listOf(Platform.ANDROID, Platform.IOS), config.repos.single().platforms)
    }

    @Test
    fun missingEnvFails() {
        assertFailsWith<IllegalStateException> { ConfigLoader.substituteEnv("token: \${NOPE}", emptyMap()) }
    }

    @Test
    fun relativePathsResolveAgainstConfigDir() {
        val dir = java.nio.file.Files.createTempDirectory("cfg").toFile()
        val file = File(dir, "config.yml").apply {
            writeText(File(generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "config.example.yml") }.first { it.isFile }.path).readText()
                .replace("\${SENTRY_AUTH_TOKEN}", "s").replace("\${GITHUB_TOKEN}", "g"))
        }
        val config = ConfigLoader.load(file)
        assertEquals(File(dir, "projects").canonicalPath, config.resolvePath(config.pipeline.cloneDir).canonicalPath)
        assertEquals("/abs/x", config.resolvePath("/abs/x").path)
    }
}
