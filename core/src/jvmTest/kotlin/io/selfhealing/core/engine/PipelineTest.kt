package io.selfhealing.core.engine

import io.selfhealing.core.config.AndroidConfig
import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.config.GitHubConfig
import io.selfhealing.core.config.IosConfig
import io.selfhealing.core.config.PipelineConfig
import io.selfhealing.core.config.ReleaseConfig
import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.config.SentryConfig
import io.selfhealing.core.config.SentryProjectConfig
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.CrashStage
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.model.Platform
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineTest {
    private val tmp = Files.createTempDirectory("pipeline-test").toFile()
    private val repo = RepoConfig(
        id = "app", url = "https://github.com/acme/app.git",
        android = AndroidConfig(applicationId = "com.acme.app"),
        ios = IosConfig(buildCommand = "true", appPath = "App.app", bundleId = "com.acme.app"),
    )
    private fun config(r: RepoConfig = repo) = AppConfig(
        sentry = SentryConfig(authToken = "t", organization = "acme", projects = listOf(SentryProjectConfig("app-android", repo = "app"))),
        github = GitHubConfig(token = "t"),
        repos = listOf(r),
        pipeline = PipelineConfig(workDir = File(tmp, "work").path),
    )
    private val crash = CrashReport(id = "42", projectSlug = "app-android", title = "NPE", platform = Platform.ANDROID, release = "com.acme.app@1.1.0+110", stacktrace = "at Foo")

    private class Recorder : Pipeline.Listener {
        val stages = mutableListOf<CrashStage>()
        var pr: String? = null
        override fun stage(crashId: String, stage: CrashStage, message: String) { stages += stage }
        override fun log(crashId: String, source: LogSource, text: String) = Unit
        override fun summary(crashId: String, summary: FixSummary) = Unit
        override fun screenshot(crashId: String, path: String) = Unit
        override fun prOpened(crashId: String, url: String) { pr = url }
        var removed = false
        override fun artifactsRemoved(crashId: String) { removed = true }
    }

    private inner class FakeWorkspace(val changes: Boolean = true) : RepoWorkspace {
        var pushed = false
        val dir = File(tmp, "repo").apply { mkdirs() }
        override suspend fun prepare(repo: RepoConfig, crash: CrashReport, log: LineSink) = PreparedRepo(dir, "fix", "main", "abc")
        override suspend fun hasChanges(prepared: PreparedRepo) = changes
        override suspend fun commitAndPush(prepared: PreparedRepo, repo: RepoConfig, message: String, log: LineSink) { pushed = true }
        override suspend fun checkoutRef(repo: RepoConfig, ref: String, log: LineSink): File? = null
        override suspend fun releaseRef(repo: RepoConfig, dir: File, log: LineSink) {}
        var cleanedUp = false
        override suspend fun cleanup(repo: RepoConfig, prepared: PreparedRepo, log: LineSink) { cleanedUp = true }
        var discardedExcept: Set<String>? = null
        var assets: List<File> = emptyList()
        override suspend fun changedFiles(prepared: PreparedRepo) = setOf("Fix.kt")
        override suspend fun discardChangesExcept(prepared: PreparedRepo, keep: Set<String>, log: LineSink) { discardedExcept = keep }
        override suspend fun pushAssets(repo: RepoConfig, branch: String, dir: String, files: List<File>, log: LineSink): List<String> {
            assets = files
            return files.map { "$dir/${it.name}" }
        }
    }

    private fun harness(summary: FixSummary?) = object : Harness {
        override suspend fun run(request: HarnessRequest, log: LineSink) = HarnessResult(0, summary)
    }

    private inner class FakeVerifier(override val prefix: String, val success: Boolean = true, val reproduces: Boolean = true) : PlatformVerifier {
        val calls = mutableListOf<String>()
        var releaseTag: String? = null
        override suspend fun reproduce(repo: RepoConfig, crash: CrashReport, release: ReleaseSource, artifactsDir: File, log: LineSink): ReproResult {
            calls += "reproduce"
            releaseTag = release.tag
            return ReproResult(true, reproduces, emptyList(), "$prefix: reproduced=$reproduces")
        }
        override suspend fun verify(repo: RepoConfig, repoDir: File, crash: CrashReport, artifactsDir: File, log: LineSink): VerificationResult {
            calls += "verify"
            val shot = File(artifactsDir, "$prefix-01-launch.png").apply { writeBytes(byteArrayOf(1)) }
            return VerificationResult(success, listOf(shot), "$prefix: ${if (success) "alive" else "crashed"}")
        }
    }

    private val publisher = object : PrPublisher {
        var screenshots: List<String> = emptyList()
        var details = ""
        override suspend fun publish(
            repo: RepoConfig, prepared: PreparedRepo, crash: CrashReport, summary: FixSummary,
            verification: VerificationResult, screenshots: PublishedScreenshots?, log: LineSink,
        ): String {
            this.screenshots = screenshots?.paths.orEmpty()
            details = verification.details
            return "https://github.com/acme/app/pull/1"
        }
    }

    private fun run(
        workspace: FakeWorkspace, summary: FixSummary?, android: FakeVerifier, ios: FakeVerifier, r: RepoConfig = repo,
    ): Recorder {
        val recorder = Recorder()
        val pipeline = Pipeline(config(r), workspace, harness(summary), mapOf(Platform.ANDROID to android, Platform.IOS to ios), publisher)
        runBlocking { pipeline.process(crash, recorder) }
        return recorder
    }

    @Test
    fun verifiesEveryPlatformAndOpensPr() {
        val workspace = FakeWorkspace()
        val android = FakeVerifier("android")
        val ios = FakeVerifier("ios")
        val r = run(workspace, FixSummary(fixed = true, rootCause = "race"), android, ios)
        assertEquals(CrashStage.DONE, r.stages.last())
        assertEquals(listOf("verify"), android.calls)
        assertEquals(listOf("verify"), ios.calls)
        assertTrue(workspace.pushed)
        assertEquals(listOf("42/android-01-launch.png", "42/ios-01-launch.png"), publisher.screenshots)
        assertEquals(setOf("Fix.kt"), workspace.discardedExcept)
        assertFalse(File(workspace.dir, ".self-healing").exists())
        assertTrue("android: alive" in publisher.details && "ios: alive" in publisher.details)
        // Nothing but the log stays behind once the PR is open.
        assertTrue(workspace.cleanedUp)
        assertEquals(listOf("run.log"), File(tmp, "work/artifacts/42").list()!!.sorted())
        assertTrue(r.removed)
    }

    @Test
    fun reproducesOnReleaseBeforeVerifying() {
        val android = FakeVerifier("android")
        val ios = FakeVerifier("ios", reproduces = false)
        val r = run(FakeWorkspace(), FixSummary(true, "race"), android, ios, repo.copy(release = ReleaseConfig(enabled = true)))
        assertTrue(CrashStage.REPRODUCING in r.stages)
        assertEquals(listOf("reproduce", "verify"), android.calls)
        assertEquals("v1.1.0", android.releaseTag)
        assertEquals(CrashStage.DONE, r.stages.last())
        assertTrue("ios: reproduced=false" in publisher.details)
    }

    @Test
    fun requireReproductionStopsWhenNoPlatformReproduces() {
        val r = run(
            FakeWorkspace(), FixSummary(true, "race"), FakeVerifier("android", reproduces = false), FakeVerifier("ios", reproduces = false),
            repo.copy(release = ReleaseConfig(enabled = true, requireReproduction = true)),
        )
        assertEquals(CrashStage.FAILED, r.stages.last())
    }

    @Test
    fun oneFailingPlatformFailsTheRun() {
        val workspace = FakeWorkspace()
        val r = run(workspace, FixSummary(true, "race"), FakeVerifier("android"), FakeVerifier("ios", success = false))
        assertEquals(CrashStage.FAILED, r.stages.last())
        assertFalse(workspace.pushed)
        assertTrue(workspace.cleanedUp, "a failed run is cleaned up too")
        // ...but keeps its screenshots to diagnose the failure.
        assertTrue(File(tmp, "work/artifacts/42/android-01-launch.png").exists())
    }

    @Test
    fun missingResultOrNoChangesFails() {
        assertEquals(CrashStage.FAILED, run(FakeWorkspace(), null, FakeVerifier("a"), FakeVerifier("i")).stages.last())
        assertEquals(CrashStage.FAILED, run(FakeWorkspace(changes = false), FixSummary(true, "x"), FakeVerifier("a"), FakeVerifier("i")).stages.last())
        assertEquals(CrashStage.FAILED, run(FakeWorkspace(), FixSummary(false, "unknown"), FakeVerifier("a"), FakeVerifier("i")).stages.last())
    }

    @Test
    fun unmappedRepoIsSkipped() {
        val recorder = Recorder()
        val pipeline = Pipeline(config().copy(repos = emptyList()), FakeWorkspace(), harness(null), emptyMap(), publisher)
        runBlocking { pipeline.process(crash, recorder) }
        assertEquals(listOf(CrashStage.SKIPPED), recorder.stages)
    }
}
