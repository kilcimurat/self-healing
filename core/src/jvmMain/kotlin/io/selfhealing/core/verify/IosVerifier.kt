package io.selfhealing.core.verify

import io.selfhealing.core.config.IosConfig
import io.selfhealing.core.config.PipelineConfig
import io.selfhealing.core.config.RepoConfig
import io.selfhealing.core.config.TestAccountConfig
import io.selfhealing.core.devices.DeviceManager
import io.selfhealing.core.engine.PlatformVerifier
import io.selfhealing.core.engine.ReleaseSource
import io.selfhealing.core.engine.ReproResult
import io.selfhealing.core.engine.VerificationResult
import io.selfhealing.core.harness.PromptBuilder
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.process.ProcessResult
import io.selfhealing.core.process.ProcessRunner
import io.selfhealing.core.process.Tools
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Reproduces the crash on the release built from source, then builds + installs the fixed app on an iOS
 * simulator, replays the harness-written Maestro flow and checks the app stays alive without a crash report.
 */
class IosVerifier(private val pipeline: PipelineConfig, private val devices: DeviceManager) : PlatformVerifier {
    override val prefix = "ios"

    private var maestroHintLogged = false

    /** A built, installed and launched app on [udid]. */
    private class Launched(val udid: String, val exe: String, val launchedAt: Long, val buildTime: String)

    override suspend fun reproduce(repo: RepoConfig, crash: CrashReport, release: ReleaseSource, artifactsDir: File, log: LineSink): ReproResult {
        val cfg = repo.ios ?: return ReproResult(false, false, emptyList(), "no iOS config")
        if (!repo.release.enabled) return ReproResult(false, false, emptyList(), "release reproduction disabled")
        val shots = mutableListOf<File>()
        fun notAttempted(reason: String) = ReproResult(false, false, shots.toList(), reason).also { log.build("iOS release reproduction skipped: $reason") }
        if (!isMac()) return notAttempted("iOS requires macOS with Xcode")
        artifactsDir.mkdirs()
        val tag = release.tag ?: "unknown"
        val dir = release.sourceDir(log) ?: return notAttempted("release $tag source not available")
        val udid = devices.ensureIos(cfg, log) ?: return notAttempted("iOS simulator not available")

        // Recorded from launch (after the build) until a few seconds after the crash.
        var recording: ScreenRecording? = null
        val (flow, maestro) = flowAndMaestro(artifactsDir, log)
        val launched = recorded({ recording }) {
            val launched = when (val r = buildInstallLaunch(cfg, udid, dir, log) { recording = startIosRecording(udid, recordingFile(artifactsDir, prefix, "release"), maestroDebugDir(artifactsDir, prefix, "release"), log) }) {
                is Step.Fail -> return notAttempted("release build failed: ${r.reason.lines().firstOrNull().orEmpty()}")
                is Step.Ok -> r.value
            }
            delay(1.seconds)
            shot(udid, File(artifactsDir, "$prefix-00-release-launch.png"), shots, log)
            if (flow != null && maestro) replay(udid, flow, dir, repo.testAccount, maestroDebugDir(artifactsDir, prefix, "release"), launched, cfg.bundleId, log)
            delay(3.seconds)
            launched
        }
        val report = findCrashReport(diagnosticReportDirs(), launched.launchedAt, cfg.bundleId, launched.exe)
        shot(udid, File(artifactsDir, "$prefix-00-release-after-repro.png"), shots, log)
        if (report != null) log.build("Crash report ${report.name} on release $tag:\n${firstLines(runCatching { report.readText() }.getOrDefault(""), 40)}")
        return ReproResult(true, report != null, shots.toList(), reproDetails(report != null, tag, flow != null && maestro))
    }

    override suspend fun verify(repo: RepoConfig, repoDir: File, crash: CrashReport, artifactsDir: File, log: LineSink): VerificationResult {
        val cfg = repo.ios ?: return VerificationResult(false, emptyList(), "ios config missing")
        val shots = mutableListOf<File>()
        fun fail(reason: String) = VerificationResult(false, shots.toList(), "iOS: $reason").also { log.build("iOS verification failed: $reason") }
        if (!isMac()) return fail("verification requires macOS with Xcode")
        artifactsDir.mkdirs()
        val udid = devices.ensureIos(cfg, log) ?: return fail("iOS simulator not available")

        var recording: ScreenRecording? = null
        val (flow, maestro) = flowAndMaestro(artifactsDir, log)
        val notes = mutableListOf<String>()
        // Same recording as on the release build: launch + repro flow, ending a few seconds after the crash point.
        val launched = recorded({ recording }) {
            val launched = when (val r = buildInstallLaunch(cfg, udid, repoDir, log) { recording = startIosRecording(udid, recordingFile(artifactsDir, prefix, "fixed"), maestroDebugDir(artifactsDir, prefix, "fixed"), log) }) {
                is Step.Fail -> return fail(r.reason)
                is Step.Ok -> r.value
            }
            notes += "built in ${launched.buildTime}"
            delay(1.seconds)
            shot(udid, File(artifactsDir, "$prefix-01-launch.png"), shots, log)
            if (flow != null && maestro) {
                val r = replay(udid, flow, repoDir, repo.testAccount, maestroDebugDir(artifactsDir, prefix, "fixed"), launched, cfg.bundleId, log)
                delay(3.seconds)
                val report = findCrashReport(diagnosticReportDirs(), launched.launchedAt, cfg.bundleId, launched.exe)
                if (report != null) return fail(crashMessage(report, log))
                if (!r.ok) return fail("repro flow failed on the fixed build (exit ${r.exitCode}): ${r.output.lines().filter { it.isNotBlank() }.takeLast(10).joinToString("\n")}")
                notes += "repro flow passes"
                shot(udid, File(artifactsDir, "$prefix-02-after-repro.png"), shots, log)
            } else {
                if (flow == null) log.build("No repro flow from the harness; checking launch + resume only")
                delay(pipeline.verifyWaitSeconds.seconds)
                shot(udid, File(artifactsDir, "$prefix-02-after-wait.png"), shots, log)
            }
            launched
        }

        simctl(udid, "launch", "com.apple.Preferences").also { log.build("launch Preferences: ${it.output.trim().lines().lastOrNull().orEmpty()}") }
        delay(2.seconds)
        launchApp(udid, cfg.bundleId, log)?.let { return fail(it) }
        delay(3.seconds)
        shot(udid, File(artifactsDir, "$prefix-03-after-resume.png"), shots, log)

        val report = findCrashReport(diagnosticReportDirs(), launched.launchedAt, cfg.bundleId, launched.exe)
        if (report != null) return fail(crashMessage(report, log))
        val running = launchctlHasApp(simctl(udid, "spawn", "launchctl", "list").output, cfg.bundleId)
        if (!running) return fail("app is not running after resume (no UIKitApplication:${cfg.bundleId} in launchctl)")
        notes += "alive after resume"
        notes += "no crash report"
        return VerificationResult(true, shots.toList(), "iOS: " + notes.joinToString("; "))
    }

    private sealed interface Step {
        class Ok(val value: Launched) : Step
        class Fail(val reason: String) : Step
    }

    /** Uninstalls any previous install, builds in [dir], installs and launches the app. */
    private suspend fun buildInstallLaunch(cfg: IosConfig, udid: String, dir: File, log: LineSink, beforeLaunch: suspend () -> Unit = {}): Step {
        simctl(udid, "uninstall", cfg.bundleId)
        val started = TimeSource.Monotonic.markNow()
        val build = ProcessRunner.shell(
            cfg.buildCommand, dir = dir, env = sdkEnv(), timeout = pipeline.buildTimeoutMinutes.minutes, log = log, source = LogSource.BUILD,
        )
        if (!build.ok) return Step.Fail("build failed (exit ${build.exitCode}): ${buildFailureSummary(build.output)}")
        val buildTime = formatDuration(started.elapsedNow())
        log.build("Built in $buildTime")
        val app = File(cfg.appPath).let { if (it.isAbsolute) it else File(dir, cfg.appPath) }
        if (!app.exists()) return Step.Fail("built app not found at ${app.path}")
        val install = simctl(udid, "install", app.path)
        if (!install.ok) return Step.Fail("install failed: ${firstLines(install.output, 10)}")
        val exe = executableName(app)
        simctl(udid, "terminate", cfg.bundleId)
        beforeLaunch()
        val launchedAt = System.currentTimeMillis()
        launchApp(udid, cfg.bundleId, log)?.let { return Step.Fail(it) }
        return Step.Ok(Launched(udid, exe, launchedAt, buildTime))
    }

    private suspend fun launchApp(udid: String, bundle: String, log: LineSink): String? {
        val r = simctl(udid, "launch", bundle)
        log.build("launch $bundle: ${r.output.trim().lines().lastOrNull().orEmpty()}")
        return if (r.ok) null else "launch of $bundle failed: ${firstLines(r.output, 10)}"
    }

    private suspend fun simctl(udid: String, vararg args: String, timeoutSec: Long = 120): ProcessResult =
        ProcessRunner.run(listOf("xcrun", "simctl", args.first(), udid) + args.drop(1), timeout = timeoutSec.seconds)

    private suspend fun shot(udid: String, file: File, shots: MutableList<File>, log: LineSink) {
        val r = ProcessRunner.run(listOf("xcrun", "simctl", "io", udid, "screenshot", file.path), timeout = 30.seconds)
        if (r.ok && file.length() > 0) shots += file else log.build("Screenshot ${file.name} failed: ${firstLines(r.output, 3)}")
    }

    /** The harness-written flow (if any) and whether Maestro can replay it. */
    private suspend fun flowAndMaestro(artifactsDir: File, log: LineSink): Pair<File?, Boolean> {
        val flow = File(artifactsDir, PromptBuilder.REPRO_FLOW).takeIf { it.isFile } ?: return null to false
        val ok = runCatching { ProcessRunner.run(listOf(Tools.maestro(pipeline.maestroCommand), "--version"), timeout = 60.seconds).ok }.getOrDefault(false)
        if (!ok && !maestroHintLogged) {
            maestroHintLogged = true
            log.build("Maestro not found (`${Tools.maestro(pipeline.maestroCommand)}`); repro flow skipped. Install: https://maestro.mobile.dev")
        }
        return flow to ok
    }

    /** Replays the flow, retried once when it fails without a crash report for [launched]'s app. */
    private suspend fun replay(
        udid: String, flow: File, dir: File, account: TestAccountConfig?, debugDir: File, launched: Launched, bundleId: String, log: LineSink,
    ): ProcessResult = replayWithRetry(
        debugDir, "iOS", log, { findCrashReport(diagnosticReportDirs(), launched.launchedAt, bundleId, launched.exe) != null },
    ) { d ->
        ProcessRunner.run(
            listOf(Tools.maestro(pipeline.maestroCommand), "--device", udid, "test") + maestroDebugArgs(d) + maestroAccountArgs(account) + flow.absolutePath,
            dir = dir, timeout = 10.minutes, log = log, source = LogSource.BUILD, redact = accountSecrets(account),
        )
    }!!

    private fun crashMessage(report: File, log: LineSink): String {
        val head = firstLines(runCatching { report.readText() }.getOrDefault(""), 40)
        log.build("Crash report ${report.name}:\n$head")
        return "app crashed (${report.name}):\n$head"
    }

    private suspend fun executableName(app: File): String {
        val r = ProcessRunner.run(listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleExecutable", File(app, "Info.plist").path), timeout = 15.seconds)
        return r.output.trim().takeIf { r.ok && it.isNotEmpty() && !it.contains('\n') } ?: app.nameWithoutExtension
    }

    private fun isMac() = System.getProperty("os.name").lowercase().contains("mac")

    private fun diagnosticReportDirs(): List<File> {
        val base = File(System.getProperty("user.home"), "Library/Logs/DiagnosticReports")
        return listOf(base, File(base, "Retired"))
    }

    /** The app's Gradle-built shared framework needs the Android SDK location. */
    private fun sdkEnv(): Map<String, String> =
        androidSdkRoot()?.let { mapOf("ANDROID_HOME" to it.path, "ANDROID_SDK_ROOT" to it.path) } ?: emptyMap()
}

internal fun reproDetails(reproduced: Boolean, tag: String, hasFlow: Boolean): String =
    if (reproduced) "iOS: crash reproduced on release $tag"
    else "iOS: not reproduced on release $tag" + if (!hasFlow) " (no repro flow)" else ""

/** True when `simctl spawn launchctl list` output shows the app's process. */
internal fun launchctlHasApp(output: String, bundleId: String): Boolean = output.contains("UIKitApplication:$bundleId")

internal data class SimDevice(val udid: String, val name: String, val state: String)

private val UDID_REGEX = Regex("^[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}$")

internal fun looksLikeUdid(s: String) = UDID_REGEX.matches(s.trim())

/** Resolves a configured simulator name/UDID; [listJson] is only invoked for names. */
internal suspend fun resolveSimulator(cfg: IosConfig, listJson: suspend () -> String): SimDevice? =
    if (looksLikeUdid(cfg.simulator)) SimDevice(cfg.simulator.trim(), cfg.simulator.trim(), "Unknown")
    else pickSimulator(listJson(), cfg.simulator)

/** Picks the device called [name] from `simctl list devices -j` output, preferring Booted, then newer runtimes. */
internal fun pickSimulator(json: String, name: String): SimDevice? {
    val devices = runCatching { Json.parseToJsonElement(json).jsonObject["devices"] as? JsonObject }.getOrNull() ?: return null
    val candidates = devices.entries.sortedByDescending { it.key }.flatMap { (_, list) ->
        (list as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val udid = o["udid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val available = o["isAvailable"]?.jsonPrimitive?.contentOrNull != "false"
            if (n == name && available) SimDevice(udid, n, o["state"]?.jsonPrimitive?.contentOrNull ?: "Unknown") else null
        }
    }
    return candidates.firstOrNull { it.state == "Booted" } ?: candidates.firstOrNull()
}

/** A .ips/.crash file modified at/after [since] whose name or first 2KB mentions the bundle id or executable. */
internal fun findCrashReport(dirs: List<File>, since: Long, bundleId: String, executable: String): File? =
    dirs.asSequence()
        .flatMap { d -> d.listFiles()?.asSequence() ?: emptySequence() }
        .filter { it.isFile && it.extension in setOf("ips", "crash") && it.lastModified() >= since }
        .sortedBy { it.lastModified() }
        .firstOrNull { f ->
            val needles = listOf(bundleId, executable).filter { it.isNotBlank() }
            needles.any { f.name.contains(it) } ||
                runCatching { f.inputStream().use { ins -> String(ins.readNBytes(2048), Charsets.UTF_8) } }.getOrDefault("").let { head -> needles.any { head.contains(it) } }
        }
