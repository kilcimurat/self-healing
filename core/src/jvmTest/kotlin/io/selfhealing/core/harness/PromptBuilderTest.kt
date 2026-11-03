package io.selfhealing.core.harness

import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptBuilderTest {
    private val crash = CrashReport(
        id = "42", projectSlug = "shop", title = "NPE in Cart", platform = Platform.ANDROID,
        culprit = "CartViewModel.load", release = "1.2.3", permalink = "https://sentry.io/i/42",
        stacktrace = "java.lang.NullPointerException\n at com.acme.Cart.load(Cart.kt:10)", context = "tag: a=b",
    )
    private val repo = RepoConfig(id = "shop-android", platform = Platform.ANDROID, url = "https://github.com/acme/shop.git")

    @Test
    fun containsSchemaAndResultPath() {
        val f = File("/tmp/x/result.json")
        val p = PromptBuilder.build(crash, repo, f)
        assertTrue(f.absolutePath in p)
        listOf("\"fixed\"", "\"rootCause\"", "\"filesChanged\"", "\"testsAdded\"", "\"reproSteps\"", "\"confidence\"").forEach { assertTrue(it in p, it) }
        assertTrue("https://sentry.io/i/42" in p)
        assertTrue("Cart.kt:10" in p)
    }

    @Test
    fun asksForReproFlowWhenAppIdKnown() {
        val f = File("/tmp/x/result.json")
        val android = repo.copy(android = io.selfhealing.core.config.AndroidConfig(applicationId = "com.acme.shop"))
        val p = PromptBuilder.build(crash, android, f)
        assertTrue(File("/tmp/x/repro.yaml").absolutePath in p)
        assertTrue("appId: com.acme.shop" in p)
        assertTrue("6. Finally write a JSON file" in p)
        assertTrue("repro.yaml" !in PromptBuilder.build(crash, repo, f))
    }

    @Test
    fun displayMapsStreamJson() {
        assertEquals("model: opus", StreamJsonDisplay.render("""{"type":"system","subtype":"init","model":"opus"}"""))
        assertEquals("hello", StreamJsonDisplay.render("""{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}"""))
        assertEquals(
            "▸ Read /a/b.kt",
            StreamJsonDisplay.render("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/a/b.kt"}}]}}"""),
        )
        assertEquals(
            "✗ boom",
            StreamJsonDisplay.render("""{"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"boom"}]}}"""),
        )
        assertNull(StreamJsonDisplay.render("""{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}"""))
        assertEquals("✓ done (3 turns, \$0.5)", StreamJsonDisplay.render("""{"type":"result","num_turns":3,"total_cost_usd":0.5}"""))
        assertEquals("plain text", StreamJsonDisplay.render("plain text"))
        assertNull(StreamJsonDisplay.render("""{"type":"rate_limit_event"}"""))
    }

    @Test
    fun extractsSummaryFromOutput() {
        val out = "blah {\"x\":1}\nfinal: {\"fixed\": true, \"rootCause\": \"npe {x}\", \"fix\": \"f\"} done"
        val s = assertNotNull(CliHarness.parseFromOutput(out))
        assertTrue(s.fixed)
        assertEquals("npe {x}", s.rootCause)
        assertNull(CliHarness.parseFromOutput("nothing here"))
    }
}
