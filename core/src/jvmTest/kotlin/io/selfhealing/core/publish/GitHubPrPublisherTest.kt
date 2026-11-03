package io.selfhealing.core.publish

import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.Platform
import io.selfhealing.core.engine.VerificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubPrPublisherTest {
    @Test
    fun parsesUrls() {
        val expected = GitHubRepoRef("github.com", "acme", "shop")
        assertEquals(expected, parseGitHubRepo("https://github.com/acme/shop.git"))
        assertEquals(expected, parseGitHubRepo("https://github.com/acme/shop"))
        assertEquals(expected, parseGitHubRepo("git@github.com:acme/shop.git"))
        assertEquals(GitHubRepoRef("ghe.corp.com", "a", "b"), parseGitHubRepo("https://ghe.corp.com/a/b.git"))
        assertEquals(expected, parseGitHubRepo("ssh://git@github.com/acme/shop.git"))
        assertFailsWith<IllegalArgumentException> { parseGitHubRepo("nonsense") }
    }

    @Test
    fun bodyHasSections() {
        val crash = CrashReport(id = "1", projectSlug = "p", title = "NPE", platform = Platform.IOS, release = "2.0",
            level = "fatal", permalink = "https://sentry.io/i/1", stacktrace = "x".repeat(7000))
        val body = buildPrBody(
            crash, FixSummary(true, "cause", "the fix", listOf("A.kt"), listOf("ATest.kt")),
            VerificationResult(true, emptyList(), "launched ok"), hasScreenshots = true,
        )
        listOf("## Sentry crash", "## Root cause", "## Fix", "## Files changed", "## Tests added", "## Verification", "<details>", "review before merging").forEach {
            assertTrue(it in body, it)
        }
        assertTrue("comment below" in body)
        assertTrue("![" !in body)
        assertTrue("x".repeat(6001) !in body)
        assertTrue("https://sentry.io/i/1" in body)
    }

    @Test
    fun screenshotCommentIsAStepByPlatformTable() {
        val shots = io.selfhealing.core.engine.PublishedScreenshots(
            "self-healing-assets",
            listOf("9/android-00-release-after-repro.png", "9/android-01-launch.png", "9/ios-01-launch.png"),
        )
        val c = buildScreenshotComment(shots, GitHubRepoRef("github.com", "acme", "shop"))
        assertTrue("| Step | Android | iOS |" in c)
        assertTrue("| Release build · after repro flow | <img src=\"https://github.com/acme/shop/blob/self-healing-assets/9/android-00-release-after-repro.png?raw=true\" width=\"240\"> | — |" in c)
        assertTrue("| Fixed build · launch | <img" in c && "ios-01-launch.png" in c)
        assertTrue("Release build · launch" !in c)
    }

    @Test
    fun videoCommentEmbedsGifAndLinksMp4() {
        val shots = io.selfhealing.core.engine.PublishedScreenshots("self-healing-assets", listOf("9/crash-video.gif", "9/crash-video.mp4"))
        val c = buildScreenshotComment(shots, GitHubRepoRef("github.com", "acme", "shop"))
        assertTrue("## Crash video" in c)
        assertTrue("<img src=\"https://github.com/acme/shop/blob/self-healing-assets/9/crash-video.gif?raw=true\"" in c)
        assertTrue("(https://github.com/acme/shop/blob/self-healing-assets/9/crash-video.mp4?raw=true)" in c)
        assertTrue("| Step |" !in c)
    }
}
