package io.selfhealing.core.engine

import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.LineSink
import java.io.File

/** Produces crash reports (Sentry webhook + polling). */
interface CrashSource {
    /** Starts listening; must return once listening is set up. [onCrash] may be called from any thread. */
    suspend fun start(onCrash: suspend (CrashReport) -> Unit)
    suspend fun stop()
}

data class PreparedRepo(
    val dir: File,
    val branch: String,
    val baseBranch: String,
    val baseCommit: String,
)

/** Clones/updates the target repository into an isolated working copy and handles git operations. */
interface RepoWorkspace {
    suspend fun prepare(repo: RepoConfig, crash: CrashReport, log: LineSink): PreparedRepo
    suspend fun hasChanges(prepared: PreparedRepo): Boolean
    /** Commits everything (including [extraFiles] already inside the repo) and pushes the branch. */
    suspend fun commitAndPush(prepared: PreparedRepo, repo: RepoConfig, message: String, log: LineSink)
    /** A detached, read-only working copy of [ref] (e.g. a release tag); null when the ref does not exist. */
    suspend fun checkoutRef(repo: RepoConfig, ref: String, log: LineSink): File?
    /** Gives back a [checkoutRef] copy; it is deleted once no running crash uses it. */
    suspend fun releaseRef(repo: RepoConfig, dir: File, log: LineSink)
    /** Deletes the run's working copy (with its build output) and local branch; the PR branch stays on the remote. */
    suspend fun cleanup(repo: RepoConfig, prepared: PreparedRepo, log: LineSink)
    /** Repo-relative paths that differ from the base commit (modified, added or untracked). */
    suspend fun changedFiles(prepared: PreparedRepo): Set<String>
    /** Reverts every change outside [keep], e.g. lockfiles a build rewrote, so only the fix is committed. */
    suspend fun discardChangesExcept(prepared: PreparedRepo, keep: Set<String>, log: LineSink)
    /**
     * Pushes [files] to `<dir>/` on [branch] (an orphan branch created on first use), keeping them out of the PR's diff.
     * Returns their paths on that branch.
     */
    suspend fun pushAssets(repo: RepoConfig, branch: String, dir: String, files: List<File>, log: LineSink): List<String>
}

/** Media (crash video or screenshots) hosted on the assets branch, shown in a PR comment. */
data class PublishedScreenshots(val branch: String, val paths: List<String>)

data class HarnessRequest(
    val repoDir: File,
    val crash: CrashReport,
    val repo: RepoConfig,
    /** Where the harness must write its [FixSummary] JSON. */
    val resultFile: File,
)

data class HarnessResult(val exitCode: Int, val summary: FixSummary?)

/** Runs the configured coding agent CLI (Claude Code, Codex, custom) against the repo. */
interface Harness {
    suspend fun run(request: HarnessRequest, log: LineSink): HarnessResult
}

data class VerificationResult(
    val success: Boolean,
    val screenshots: List<File>,
    val details: String,
)

data class ReproResult(
    /** False when there was nothing to run (no release build / no repro flow). */
    val attempted: Boolean,
    val reproduced: Boolean,
    val screenshots: List<File>,
    val details: String,
)

/** The released build of the crash's version, resolved lazily and shared by all platforms of one run. */
interface ReleaseSource {
    /** e.g. `1.1.0`, from Sentry's `app@1.1.0+110`; null when the crash has no release. */
    val version: String?
    /** e.g. `v1.1.0`. */
    val tag: String?
    /** First GitHub release asset of [tag] matching [pattern], downloaded into the run's artifacts. */
    suspend fun asset(pattern: Regex, log: LineSink): File?
    /** Source checkout of [tag], to build the release for targets without a usable asset (iOS simulator). */
    suspend fun sourceDir(log: LineSink): File?
}

/** Drives one platform's device: replays the crash on the released build and verifies the fixed build. */
interface PlatformVerifier {
    /** Screenshot names are prefixed with this (e.g. `android-01-launch.png`) so platforms don't collide. */
    val prefix: String

    suspend fun reproduce(repo: RepoConfig, crash: CrashReport, release: ReleaseSource, artifactsDir: File, log: LineSink): ReproResult

    suspend fun verify(repo: RepoConfig, repoDir: File, crash: CrashReport, artifactsDir: File, log: LineSink): VerificationResult
}

/** Opens the pull request and returns its URL. */
interface PrPublisher {
    suspend fun publish(
        repo: RepoConfig,
        prepared: PreparedRepo,
        crash: CrashReport,
        summary: FixSummary,
        verification: VerificationResult,
        /** Crash video (or screenshots) on the assets branch, posted as a PR comment; null when nothing could be uploaded. */
        screenshots: PublishedScreenshots?,
        log: LineSink,
    ): String
}
