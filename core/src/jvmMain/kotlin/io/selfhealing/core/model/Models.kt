package io.selfhealing.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
    @SerialName("android") ANDROID,
    @SerialName("ios") IOS,
    @SerialName("unknown") UNKNOWN,
}

/** A crash as reported by Sentry, normalized for the pipeline. */
@Serializable
data class CrashReport(
    /** Sentry issue id; also the pipeline key. */
    val id: String,
    val eventId: String? = null,
    val projectSlug: String,
    val title: String,
    val culprit: String? = null,
    val level: String? = null,
    val platform: Platform,
    val release: String? = null,
    val permalink: String? = null,
    val timestamp: String? = null,
    /** Human-readable exception + stack trace, innermost frame first. */
    val stacktrace: String,
    /** Breadcrumbs, tags, device context as readable text for the harness prompt. */
    val context: String = "",
)

enum class CrashStage(val terminal: Boolean = false) {
    QUEUED,
    PREPARING,
    ANALYZING,
    /** Installing the released build of the crash's version on every platform and replaying the repro flow. */
    REPRODUCING,
    BUILDING,
    VERIFYING,
    PUBLISHING,
    DONE(terminal = true),
    FAILED(terminal = true),
    SKIPPED(terminal = true),
}

/** What the harness reports back through its result file. */
@Serializable
data class FixSummary(
    val fixed: Boolean,
    val rootCause: String,
    val fix: String = "",
    val filesChanged: List<String> = emptyList(),
    val testsAdded: List<String> = emptyList(),
    val reproSteps: List<String> = emptyList(),
    val confidence: String = "",
)

data class CrashState(
    val report: CrashReport,
    val stage: CrashStage = CrashStage.QUEUED,
    val message: String = "",
    val summary: FixSummary? = null,
    val screenshots: List<String> = emptyList(),
    val prUrl: String? = null,
)
