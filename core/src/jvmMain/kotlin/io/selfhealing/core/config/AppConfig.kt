package io.selfhealing.core.config

import io.selfhealing.core.model.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

@Serializable
data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val sentry: SentryConfig,
    val github: GitHubConfig,
    val repos: List<RepoConfig>,
    val harness: HarnessConfig = HarnessConfig(),
    val pipeline: PipelineConfig = PipelineConfig(),
    /** Directory of the loaded config file; relative paths in the config resolve against it. */
    @Transient val baseDir: String = "",
) {
    fun resolvePath(path: String): File =
        File(path).let { if (it.isAbsolute) it else File(baseDir.ifEmpty { "." }, path) }.absoluteFile.normalize()

    fun repoFor(projectSlug: String, platform: Platform): RepoConfig? {
        val mapped = sentry.projects.firstOrNull { it.slug == projectSlug }?.repo
        return mapped?.let { id -> repos.firstOrNull { it.id == id } }
            ?: repos.firstOrNull { platform in it.platforms }
    }
}

@Serializable
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8787,
    /** Public base URL Sentry can reach (e.g. an ngrok / cloudflared tunnel). Empty = webhook disabled. */
    val publicUrl: String = "",
)

@Serializable
data class SentryConfig(
    val baseUrl: String = "https://sentry.io",
    val authToken: String,
    val organization: String,
    val projects: List<SentryProjectConfig>,
    val webhook: WebhookConfig = WebhookConfig(),
    val polling: PollingConfig = PollingConfig(),
    val filters: CrashFilterConfig = CrashFilterConfig(),
)

@Serializable
data class SentryProjectConfig(
    val slug: String,
    /** Id of an entry in [AppConfig.repos]. Falls back to the first repo matching the crash platform. */
    val repo: String? = null,
    val platform: Platform? = null,
)

@Serializable
data class WebhookConfig(
    val enabled: Boolean = true,
    /** Path the embedded HTTP server listens on. */
    val path: String = "/sentry/webhook",
    /** Sentry service-hook events to subscribe to. */
    val events: List<String> = listOf("event.created"),
)

@Serializable
data class PollingConfig(
    val enabled: Boolean = true,
    val intervalSeconds: Int = 60,
)

@Serializable
data class CrashFilterConfig(
    val levels: List<String> = listOf("fatal", "error"),
    /** Only handle events whose exception mechanism is unhandled (real crashes). */
    val onlyUnhandled: Boolean = true,
    /** Only issues that reach Sentry after Start: new ones, or older ones with a new event (reports sent late on the next app launch count too). */
    val onlyNewIssues: Boolean = true,
)

@Serializable
data class GitHubConfig(
    val apiUrl: String = "https://api.github.com",
    val token: String,
    val draftPr: Boolean = true,
    val reviewers: List<String> = emptyList(),
    val labels: List<String> = listOf("self-healing"),
    /** Orphan branch that hosts the PR's crash video (or screenshots), so they stay out of the fix's diff. */
    val assetsBranch: String = "self-healing-assets",
    val commitAuthorName: String = "Self-Healing Bot",
    val commitAuthorEmail: String = "self-healing-bot@users.noreply.github.com",
)

/**
 * A repository and every platform it ships. A KMP repo lists both `android` and `ios`;
 * a crash from either platform is then reproduced and verified on both.
 */
@Serializable
data class RepoConfig(
    val id: String,
    /** Legacy single-platform hint; the platforms are the `android` / `ios` blocks that are present. */
    val platform: Platform? = null,
    /** HTTPS clone URL, e.g. https://github.com/acme/shop-android.git */
    val url: String,
    val baseBranch: String = "main",
    val android: AndroidConfig? = null,
    val ios: IosConfig? = null,
    /** Reproduce the crash on the released build of its version before verifying the fix. */
    val release: ReleaseConfig = ReleaseConfig(),
    /** Account the repro flows sign in with; null when the crash path needs no login. */
    val testAccount: TestAccountConfig? = null,
) {
    val platforms: List<Platform>
        get() = listOfNotNull(android?.let { Platform.ANDROID }, ios?.let { Platform.IOS })
}

/**
 * Test account for the automated repro flows. The harness never sees these values: it writes
 * `${TEST_USERNAME}` / `${TEST_PASSWORD}` into the Maestro flow and Maestro gets them at run time.
 */
@Serializable
data class TestAccountConfig(
    val username: String,
    val password: String,
    /** Optional hint for the harness on how to reach the sign-in form, e.g. "Continue with email → Email / Password → Sign in". */
    val loginHint: String = "",
) {
    val isSet get() = username.isNotBlank() && password.isNotBlank()
}

@Serializable
data class ReleaseConfig(
    val enabled: Boolean = false,
    /** Git tag / GitHub release of the crash's version. Placeholders: {version} (1.0.5) and {build} (105) from Sentry's `app@1.0.5+105`. */
    val tag: String = "v{version}",
    /** Regex for the GitHub release APK asset; when no asset matches, the release tag is built from source. */
    val androidAsset: String = ".*\\.apk",
    /** Fail the run when the crash cannot be reproduced on any platform's release build (otherwise it is only reported). */
    val requireReproduction: Boolean = false,
)

@Serializable
data class AndroidConfig(
    /** Run from the repo root; must build and install the debug app on [device]. */
    val buildCommand: String = "./gradlew installDebug",
    val applicationId: String,
    /** Optional fully-qualified activity; launcher intent is used when empty. */
    val launchActivity: String = "",
    /** adb serial; empty = resolved from [avd], else the only connected device. */
    val device: String = "",
    /** AVD to start (headless, shown inside the app) when no device with that name is running, e.g. `Pixel_10`. */
    val avd: String = "",
    /** Optional command that drives the app along the crash path (e.g. a Maestro flow). */
    val reproCommand: String = "",
    /** Uninstall the app before installing, so a differently signed install (store/release build) can't block it. */
    val cleanInstall: Boolean = true,
    /**
     * DNS servers for an emulator started from [avd], e.g. `[8.8.8.8, 1.1.1.1]`. Empty = the host's DNS at boot,
     * which stops resolving when the host switches network or VPN while the emulator keeps running.
     */
    val dnsServers: List<String> = emptyList(),
)

@Serializable
data class IosConfig(
    val buildCommand: String,
    /** Path of the built .app, relative to the repo root. */
    val appPath: String,
    val bundleId: String,
    /** Simulator name or UDID; booted headless and shown inside the app. */
    val simulator: String = "iPhone 16",
    /** Also open Simulator.app (needed to interact with the iOS screen by hand; the in-app view is read-only). */
    val openSimulatorApp: Boolean = false,
    val reproCommand: String = "",
)

@Serializable
data class HarnessConfig(
    val type: HarnessType = HarnessType.CLAUDE_CODE,
    val model: String = "claude-opus-5-5",
    /** Custom command template for [HarnessType.CUSTOM]. Placeholders: {model} {promptFile} {repoDir}. */
    val command: String = "",
    val extraArgs: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val timeoutMinutes: Int = 30,
)

@Serializable
enum class HarnessType {
    @SerialName("claude-code") CLAUDE_CODE,
    @SerialName("codex") CODEX,
    @SerialName("custom") CUSTOM,
}

@Serializable
data class PipelineConfig(
    /** Runs, artifacts and logs. Relative to the config file's directory. */
    val workDir: String = "./workspace",
    /** Where target repositories are cloned (one folder per repo name). Relative to the config file's directory. */
    val cloneDir: String = "./projects",
    val maxConcurrent: Int = 1,
    val branchPrefix: String = "self-healing/",
    /** Seconds the app must stay alive after launch to count as verified. */
    val verifyWaitSeconds: Int = 15,
    val buildTimeoutMinutes: Int = 30,
    /** Maestro CLI for the harness-written repro flow (PATH, then ~/.maestro/bin); the step is skipped when missing. */
    val maestroCommand: String = "maestro",
)
