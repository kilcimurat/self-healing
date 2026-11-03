package io.selfhealing.core.verify

import io.selfhealing.core.config.AndroidConfig
import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.config.TestAccountConfig
import io.selfhealing.core.harness.PromptBuilder
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestAccountTest {
    private val account = TestAccountConfig("qa@acme.com", "s3cret-pass", loginHint = "Continue with email")

    @Test
    fun maestroGetsAccountAsEnv() {
        assertEquals(listOf("-e", "TEST_USERNAME=qa@acme.com", "-e", "TEST_PASSWORD=s3cret-pass"), maestroAccountArgs(account))
        assertEquals(emptyList(), maestroAccountArgs(null))
        assertEquals(emptyList(), maestroAccountArgs(TestAccountConfig("", "")))
        assertEquals(listOf("s3cret-pass"), accountSecrets(account))
    }

    @Test
    fun promptReferencesPlaceholdersButNeverTheCredentials() {
        val repo = RepoConfig(id = "app", url = "https://github.com/acme/app.git", android = AndroidConfig(applicationId = "com.acme.app"), testAccount = account)
        val crash = CrashReport(id = "1", projectSlug = "p", title = "NPE", platform = Platform.ANDROID, stacktrace = "at X")
        val prompt = PromptBuilder.build(crash, repo, File("/tmp/r/result.json"))
        assertTrue("\${TEST_USERNAME}" in prompt && "\${TEST_PASSWORD}" in prompt)
        assertTrue("Continue with email" in prompt)
        assertFalse("qa@acme.com" in prompt)
        assertFalse("s3cret-pass" in prompt)
    }
}
