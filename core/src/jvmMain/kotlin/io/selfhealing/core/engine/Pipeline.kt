package io.selfhealing.core.engine

import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.CrashStage
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.model.Platform
import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.harness.MaestroFlows
import io.selfhealing.core.harness.PromptBuilder
import io.selfhealing.core.media.CrashVideo
import io.selfhealing.core.media.VideoCell
import io.selfhealing.core.media.VideoRow
import io.selfhealing.core.verify.GitHubReleases
import io.selfhealing.core.verify.recordingFile
import io.selfhealing.core.verify.parseSentryRelease
import io.selfhealing.core.verify.releaseTag
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * One crash end-to-end: prepare repo → harness fix → build & verify on device → commit → PR.
 * Every gate is deterministic; the harness only proposes the change.
 */
class Pipeline(
    private val config: AppConfig,
    private val workspace: RepoWorkspace,
    private val harness: Harness,
    private val verifiers: Map<Platform, PlatformVerifier>,
    private val publisher: PrPublisher,
    private val releases: GitHubReleases = GitHubReleases(config.github),
) {
    interface Listener {
        fun stage(crashId: String, stage: CrashStage, message: String)
        fun log(crashId: String, source: LogSource, text: String)
        fun summary(crashId: String, summary: FixSummary)
        fun screenshot(crashId: String, path: String)
        /** The run's screenshots and recordings were deleted (they live on in the PR). */
        fun artifactsRemoved(crashId: String) {}
        fun prOpened(crashId: String, url: String)
    }

    suspend fun process(crash: CrashReport, listener: Listener) {
        val artifactsDir = File(config.resolvePath(config.pipeline.workDir), "artifacts/${crash.id}").apply { mkdirs() }
        // A retry reuses the folder; media from the previous attempt must not end up in this run's PR.
        artifactsDir.listFiles { f -> f.extension in setOf("png", "mp4", "gif") }?.forEach { it.delete() }
        // Everything shown in the terminal also lands in run.log, so a failed run can be diagnosed later.
        val runLog = File(artifactsDir, "run.log")
        val log = LineSink { source, text ->
            listener.log(crash.id, source, text)
            synchronized(runLog) { runLog.appendText("${java.time.LocalTime.now().withNano(0)} [${source.name}] $text\n") }
        }
        fun stage(stage: CrashStage, message: String) {
            listener.stage(crash.id, stage, message)
            log.line(LogSource.SYSTEM, "── ${stage.name}: $message")
        }

        val repo = config.repoFor(crash.projectSlug, crash.platform)
            ?: return stage(CrashStage.SKIPPED, "No repo configured for project ${crash.projectSlug} (${crash.platform})")
        // A KMP repo ships several platforms: a crash from one is reproduced and verified on all of them.
        val targets = repo.platforms.mapNotNull { p -> verifiers[p]?.let { p to it } }
        if (targets.isEmpty()) return stage(CrashStage.SKIPPED, "No verifier for ${repo.id} (${repo.platforms})")
        val platformNames = targets.joinToString(" + ") { it.first.label }

        // Read by the cleanup in `finally`, whatever step the run stops at.
        var preparedRepo: PreparedRepo? = null
        var published = false
        var releaseSource: RunReleaseSource? = null
        try {
            stage(CrashStage.PREPARING, "Preparing ${repo.id} (${repo.baseBranch})")
            val prepared = workspace.prepare(repo, crash, log).also { preparedRepo = it }

            stage(CrashStage.ANALYZING, "Running ${config.harness.type.name.lowercase().replace('_', '-')} with ${config.harness.model}")
            val result = harness.run(HarnessRequest(prepared.dir, crash, repo, File(artifactsDir, "result.json")), log)
            val summary = result.summary
                ?: return stage(CrashStage.FAILED, "Harness exited with ${result.exitCode} and wrote no result file")
            listener.summary(crash.id, summary)
            if (!summary.fixed) return stage(CrashStage.FAILED, "Harness could not fix it: ${summary.rootCause}")
            if (!workspace.hasChanges(prepared)) return stage(CrashStage.FAILED, "Harness reported a fix but changed no files")
            File(artifactsDir, PromptBuilder.REPRO_FLOW).takeIf { it.isFile }?.let { it.writeText(MaestroFlows.normalize(it.readText())) }
            // Builds can rewrite tracked files (e.g. Package.resolved); only what the harness changed is committed.
            val fixFiles = workspace.changedFiles(prepared)

            val report = mutableListOf<String>()
            var releaseRow: VideoRow? = null
            if (repo.release.enabled) {
                val release = RunReleaseSource(repo, crash, artifactsDir).also { releaseSource = it }
                stage(CrashStage.REPRODUCING, "Installing release ${release.tag ?: "(unknown version)"} on $platformNames")
                // Android installs the published APK while iOS builds the tag in its own checkout, so they can run together.
                val repros = coroutineScope {
                    targets.map { (_, v) -> async { v.reproduce(repo, crash, release, artifactsDir, log) } }.awaitAll()
                }
                repros.flatMap { it.screenshots }.forEach { listener.screenshot(crash.id, it.absolutePath) }
                repros.forEach { log.line(LogSource.SYSTEM, it.details) }
                report += repros.map { it.details }
                releaseRow = VideoRow("Release ${release.tag ?: ""}".trim(), targets.zip(repros) { (_, v), r ->
                    VideoCell(recordingFile(artifactsDir, v.prefix, "release").takeIf { it.isFile }, if (r.reproduced) "crash reproduced" else "no crash", ok = !r.reproduced)
                })
                if (repo.release.requireReproduction && repros.none { it.reproduced }) {
                    return stage(CrashStage.FAILED, "Crash not reproduced on release ${release.tag}: ${repros.joinToString("; ") { it.details }}")
                }
            }

            stage(CrashStage.BUILDING, "Building and verifying the fix on $platformNames")
            // Sequential: every platform builds from the same working copy (one Gradle project).
            val results = targets.map { (_, v) ->
                v.verify(repo, prepared.dir, crash, artifactsDir, log).also { r ->
                    r.screenshots.forEach { listener.screenshot(crash.id, it.absolutePath) }
                    log.line(LogSource.SYSTEM, r.details)
                }
            }
            report += results.map { it.details }
            val verification = VerificationResult(
                success = results.all { it.success },
                screenshots = artifactsDir.listFiles { f -> f.extension == "png" }.orEmpty().sortedBy { it.name },
                details = report.joinToString("\n"),
            )
            if (!verification.success) return stage(CrashStage.FAILED, "Verification failed: ${results.filterNot { it.success }.joinToString("; ") { it.details }}")
            stage(CrashStage.VERIFYING, results.joinToString("; ") { it.details })

            stage(CrashStage.PUBLISHING, "Committing and opening PR")
            workspace.discardChangesExcept(prepared, fixFiles, log)
            workspace.commitAndPush(prepared, repo, commitMessage(crash, summary), log)
            // Release (top) and fixed build (bottom) recordings, Android left and iOS right, as one video.
            val fixedRow = VideoRow("Fixed build", targets.zip(results) { (_, v), r ->
                VideoCell(recordingFile(artifactsDir, v.prefix, "fixed").takeIf { it.isFile }, if (r.success) "no crash" else "failed", ok = r.success)
            })
            val video = CrashVideo.compose(
                targets.map { it.first.label }, listOfNotNull(releaseRow, fixedRow), artifactsDir, "crash-video", log,
            )
            // Media go to a separate branch and into a PR comment, never into the fix's diff. Screenshots only without a video.
            val media = video?.let { listOfNotNull(it.preview, it.video) } ?: verification.screenshots
            val assetsBranch = config.github.assetsBranch
            val screenshots = runCatching { workspace.pushAssets(repo, assetsBranch, crash.id, media, log) }
                .onFailure { log.line(LogSource.GIT, "Uploading PR media failed: ${it.message}") }
                .getOrNull()?.let { PublishedScreenshots(assetsBranch, it) }
            val url = publisher.publish(repo, prepared, crash, summary, verification, screenshots, log)
            listener.prOpened(crash.id, url)
            published = true
            stage(CrashStage.DONE, "PR opened: $url")
        } catch (e: kotlinx.coroutines.CancellationException) {
            stage(CrashStage.FAILED, "Cancelled")
            throw e
        } catch (e: Exception) {
            log.line(LogSource.SYSTEM, e.stackTraceToString())
            stage(CrashStage.FAILED, e.message ?: e::class.simpleName.orEmpty())
        } finally {
            withContext(NonCancellable) { cleanup(repo, preparedRepo, releaseSource, artifactsDir, crash.id, listener, published, log) }
        }
    }

    /**
     * Deletes everything the run left behind: its working copy with the build output, the release checkout and the
     * downloaded/recorded media. After a PR only small text files stay; a failed run also keeps its screenshots and
     * Maestro debug output (the screen and UI tree at the failing step) to diagnose it. A retry starts from scratch.
     */
    private suspend fun cleanup(
        repo: RepoConfig, prepared: PreparedRepo?, release: RunReleaseSource?, artifactsDir: File,
        crashId: String, listener: Listener, published: Boolean, log: LineSink,
    ) {
        runCatching { prepared?.let { workspace.cleanup(repo, it, log) } }
            .onFailure { log.line(LogSource.GIT, "Removing the run's working copy failed: ${it.message}") }
        runCatching { release?.close(log) }
            .onFailure { log.line(LogSource.GIT, "Removing the release checkout failed: ${it.message}") }
        val keep: (File) -> Boolean =
            if (published) { f -> f.name in KEPT_ARTIFACTS } else { f -> f.extension !in BULKY_ARTIFACTS }
        artifactsDir.listFiles { f -> !keep(f) }?.forEach { it.deleteRecursively() }
        if (published) listener.artifactsRemoved(crashId)
    }

    /** The crash's release: GitHub assets of its tag, or a source checkout of the tag. */
    private inner class RunReleaseSource(val repo: RepoConfig, crash: CrashReport, val artifactsDir: File) : ReleaseSource {
        private val parsed = parseSentryRelease(crash.release)
        override val version = parsed?.version
        override val tag = parsed?.let { releaseTag(repo.release.tag, it) }
        private val sourceLock = Mutex()
        private var source: File? = null

        override suspend fun asset(pattern: Regex, log: LineSink): File? =
            tag?.let { releases.downloadAsset(repo.url, it, pattern, artifactsDir, log) }

        override suspend fun sourceDir(log: LineSink): File? = sourceLock.withLock {
            source ?: tag?.let { workspace.checkoutRef(repo, it, log) }.also { source = it }
        }

        suspend fun close(log: LineSink) = sourceLock.withLock {
            source?.let { workspace.releaseRef(repo, it, log) }
            source = null
        }
    }

    private fun commitMessage(crash: CrashReport, summary: FixSummary) = buildString {
        appendLine("Fix crash: ${crash.title}".take(72))
        appendLine()
        appendLine(summary.rootCause)
        crash.permalink?.let { appendLine(); appendLine("Sentry: $it") }
    }
}

/** Left in `artifacts/<crash-id>/` after a run: the log and the harness output, no media or builds. */
private val KEPT_ARTIFACTS = setOf("run.log", "result.json", PromptBuilder.REPRO_FLOW)

/** Deleted after a failed run too: recordings, the combined video and the downloaded release APK. */
private val BULKY_ARTIFACTS = setOf("mp4", "gif", "apk")

private val Platform.label get() = when (this) {
    Platform.ANDROID -> "Android"
    Platform.IOS -> "iOS"
    Platform.UNKNOWN -> "unknown"
}
