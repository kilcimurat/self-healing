package io.selfhealing.core.verify

import io.selfhealing.core.config.AndroidConfig
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
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Android side of a run: replays the crash on the released APK of the crash's version, then installs the
 * fixed build, replays the harness-written Maestro flow (or reproCommand) and checks the app survives.
 */
class AndroidVerifier(private val pipeline: PipelineConfig, private val devices: DeviceManager) : PlatformVerifier {
    override val prefix = "android"

    /** One device session: adb bound to the resolved serial, screenshots into the run's artifacts. */
    private inner class Session(val cfg: AndroidConfig, val account: TestAccountConfig?, val serial: String, val artifactsDir: File, val log: LineSink) {
        val adbCmd = listOf(adbPath(), "-s", serial)
        // Gradle's installDebug installs on every connected device unless ANDROID_SERIAL pins one;
        // another device with a differently signed install would fail the whole build.
        val env = sdkEnv() + ("ANDROID_SERIAL" to serial)
        val shots = mutableListOf<File>()
        val flow = File(artifactsDir, PromptBuilder.REPRO_FLOW).takeIf { it.isFile }

        suspend fun adb(vararg args: String, timeoutSec: Long = 60) = ProcessRunner.run(adbCmd + args, timeout = timeoutSec.seconds)

        suspend fun uninstall() {
            val r = adb("uninstall", cfg.applicationId)
            log.build("Android: uninstalled ${cfg.applicationId} (${r.output.trim().ifEmpty { "exit ${r.exitCode}" }})")
        }

        suspend fun launch(): String? {
            val r = if (cfg.launchActivity.isNotBlank()) adb("shell", "am", "start", "-W", "-n", "${cfg.applicationId}/${cfg.launchActivity}")
            else adb("shell", "monkey", "-p", cfg.applicationId, "-c", "android.intent.category.LAUNCHER", "1")
            log.build("Android launch: ${r.output.trim().lines().lastOrNull().orEmpty()}")
            return if (!r.ok || launchFailed(r.output)) "App launch failed: ${firstLines(r.output, 10)}" else null
        }

        suspend fun shot(name: String) {
            val f = File(artifactsDir, "$prefix-$name")
            if (captureToFile(adbCmd + listOf("exec-out", "screencap", "-p"), f)) shots += f else log.build("Screenshot $name failed")
        }

        /** Records the screen (taps visible) into `android-<phase>.mp4`. */
        suspend fun record(phase: String) =
            startAndroidRecording(adbCmd, recordingFile(artifactsDir, prefix, phase), maestroDebugDir(artifactsDir, prefix, phase), log)

        /** A clear error when the device can't resolve host names; the app's network calls (sign-in) would just hang. */
        suspend fun dnsProblem(): String? {
            val r = adb("shell", "ping", "-c", "1", "-W", "3", DNS_PROBE_HOST, timeoutSec = 20)
            if (!dnsBroken(r.output)) return null
            return "the device cannot resolve host names (DNS), so the app can't reach its servers. " +
                "An emulator keeps the host's DNS from boot: restart it, or set android.dnsServers (e.g. [8.8.8.8, 1.1.1.1])"
        }

        suspend fun crashSnippet(): String? = detectAndroidCrash(
            adb("logcat", "-d", "-b", "crash", "-v", "threadtime", timeoutSec = 120).output,
            adb("logcat", "-d", "-b", "main", "-v", "threadtime", timeoutSec = 120).output,
            cfg.applicationId,
        )

        /** Replays the Maestro flow (or reproCommand), retried once on a failure without a crash; null = nothing to replay. */
        suspend fun replay(dir: File, phase: String): ProcessResult? =
            replayWithRetry(maestroDebugDir(artifactsDir, prefix, phase), "Android", log, { crashSnippet() != null }) { debugDir ->
                when {
                    flow != null && maestroAvailable(log) -> ProcessRunner.run(
                        listOf(Tools.maestro(pipeline.maestroCommand), "--device", serial, "test") + maestroDebugArgs(debugDir) +
                            maestroAccountArgs(account) + flow.absolutePath,
                        dir = dir, env = env, timeout = 10.minutes, log = log, source = LogSource.BUILD, redact = accountSecrets(account),
                    )
                    cfg.reproCommand.isNotBlank() -> ProcessRunner.shell(cfg.reproCommand, dir = dir, env = env, timeout = 10.minutes, log = log, source = LogSource.BUILD)
                    else -> null
                }
            }
    }

    private suspend fun session(repo: RepoConfig, artifactsDir: File, log: LineSink): Session? {
        val cfg = repo.android ?: return null
        artifactsDir.mkdirs()
        val serial = devices.ensureAndroid(cfg, log) ?: return null
        return Session(cfg, repo.testAccount, serial, artifactsDir, log)
    }

    override suspend fun reproduce(repo: RepoConfig, crash: CrashReport, release: ReleaseSource, artifactsDir: File, log: LineSink): ReproResult {
        if (!repo.release.enabled) return ReproResult(false, false, emptyList(), "Android: release reproduction disabled")
        val s = session(repo, artifactsDir, log) ?: return ReproResult(false, false, emptyList(), "Android: no device available")
        val tag = release.tag ?: return ReproResult(false, false, emptyList(), "Android: crash has no release version")
        s.dnsProblem()?.let { return ReproResult(false, false, emptyList(), "Android: $it").also { r -> log.build(r.details) } }

        // Prefer the published APK; fall back to building the release tag from source.
        val apk = runCatching { release.asset(Regex(repo.release.androidAsset), log) }
            .onFailure { log.build("Android: release APK download failed: ${it.message}") }.getOrNull()
        s.uninstall()
        if (apk != null) {
            val install = s.adb("install", "-r", apk.absolutePath, timeoutSec = 300)
            if (!install.ok || "Success" !in install.output) {
                return ReproResult(false, false, s.shots, "Android: installing ${apk.name} failed: ${firstLines(install.output, 5)}")
            }
        } else {
            val dir = release.sourceDir(log) ?: return ReproResult(false, false, emptyList(), "Android: release $tag not available")
            val build = ProcessRunner.shell(s.cfg.buildCommand, dir = dir, env = s.env, timeout = pipeline.buildTimeoutMinutes.minutes, log = log, source = LogSource.BUILD)
            if (!build.ok) return ReproResult(false, false, emptyList(), "Android: building release $tag failed: ${buildFailureSummary(build.output)}")
        }

        s.adb("logcat", "-c")
        // Recorded from launch until a few seconds after the crash, with the flow's taps shown on screen.
        val (replayed, snippet) = recorded(s.record("release")) {
            s.launch()?.let { return ReproResult(false, false, s.shots, "Android: release $tag: $it") }
            delay(2.seconds)
            s.shot("00-release-launch.png")
            val replayed = s.replay(apk?.parentFile ?: artifactsDir, "release") != null
            delay(3.seconds)
            replayed to s.crashSnippet()
        }
        s.shot("00-release-after-repro.png")
        return if (snippet != null) {
            log.build("Android: reproduced on release $tag:\n$snippet")
            ReproResult(true, true, s.shots, "Android: crash reproduced on release $tag")
        } else {
            ReproResult(true, false, s.shots, "Android: not reproduced on release $tag" + if (!replayed) " (no repro flow)" else "")
        }
    }

    override suspend fun verify(repo: RepoConfig, repoDir: File, crash: CrashReport, artifactsDir: File, log: LineSink): VerificationResult {
        if (repo.android == null) return VerificationResult(false, emptyList(), "Android: android config missing")
        val s = session(repo, artifactsDir, log) ?: return VerificationResult(false, emptyList(), "Android: no device/emulator available")
        fun fail(reason: String) = VerificationResult(false, s.shots.toList(), "Android: $reason").also { log.build("Android verification failed: $reason") }
        s.dnsProblem()?.let { return fail(it) }

        if (s.cfg.cleanInstall) s.uninstall() // a differently signed install (store/release) blocks the debug build
        val started = TimeSource.Monotonic.markNow()
        val build = ProcessRunner.shell(s.cfg.buildCommand, dir = repoDir, env = s.env, timeout = pipeline.buildTimeoutMinutes.minutes, log = log, source = LogSource.BUILD)
        if (!build.ok) return fail("build failed (exit ${build.exitCode}): ${buildFailureSummary(build.output)}")
        val buildTime = formatDuration(started.elapsedNow())
        log.build("Android: built in $buildTime")
        val notes = mutableListOf("built in $buildTime")

        s.adb("logcat", "-c")
        s.adb("shell", "am", "force-stop", s.cfg.applicationId)
        // Same recording as on the release build: launch + repro flow, ending a few seconds after the crash point.
        recorded(s.record("fixed")) {
            s.launch()?.let { return fail(it) }
            s.shot("01-launch.png")
            val replay = s.replay(repoDir, "fixed")
            if (replay != null) {
                delay(3.seconds)
                s.crashSnippet()?.let { return fail("fixed build still crashes on the repro flow:\n$it") }
                if (!replay.ok) return fail("repro flow failed on the fixed build (exit ${replay.exitCode}): ${replay.output.lines().filter { it.isNotBlank() }.takeLast(10).joinToString("\n")}")
                notes += "repro flow passes"
                s.shot("02-after-repro.png")
            } else {
                delay(pipeline.verifyWaitSeconds.seconds)
                s.shot("02-after-wait.png")
            }
        }
        s.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        delay(2.seconds)
        s.launch()?.let { return fail(it) }
        delay(3.seconds)
        s.shot("03-after-resume.png")

        val pid = s.adb("shell", "pidof", s.cfg.applicationId).output.trim()
        s.crashSnippet()?.let { snippet ->
            log.build("Android: crash detected:\n$snippet")
            return fail("app crashed:\n$snippet")
        }
        if (!pid.matches(Regex("\\d+(\\s+\\d+)*"))) return fail("app process is not running after launch (pidof empty)")
        notes += "alive after a background→foreground round trip; no crash in logcat"
        return VerificationResult(true, s.shots.toList(), "Android: " + notes.joinToString("; "))
    }

    private var maestroChecked: Boolean? = null

    private suspend fun maestroAvailable(log: LineSink): Boolean = maestroChecked ?: run {
        val ok = runCatching { ProcessRunner.run(listOf(Tools.maestro(pipeline.maestroCommand), "--version"), timeout = 60.seconds).ok }.getOrDefault(false)
        if (!ok) log.build("Maestro not found (`${Tools.maestro(pipeline.maestroCommand)}`); repro flow skipped. Install: https://maestro.mobile.dev")
        ok.also { maestroChecked = it }
    }
}

private const val DNS_PROBE_HOST = "google.com"

/** `ping` output of a name that could not be resolved (ICMP being blocked is not a DNS failure). */
internal fun dnsBroken(pingOutput: String): Boolean =
    pingOutput.contains("unknown host", ignoreCase = true) || pingOutput.contains("Name or service not known", ignoreCase = true)

internal data class AdbDevice(val serial: String, val state: String)

/** Parses `adb devices` output ("serial<TAB>state" lines after the header, daemon noise ignored). */
internal fun parseAdbDevices(output: String): List<AdbDevice> =
    output.lines().map { it.trim() }
        .dropWhile { !it.startsWith("List of devices") }.drop(1)
        .filter { it.isNotEmpty() && !it.startsWith("*") }
        .mapNotNull { line -> line.split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { AdbDevice(it[0], it[1]) } }

/** SDK root from ANDROID_HOME / ANDROID_SDK_ROOT, else the Android Studio default for this OS. */
internal fun androidSdkRoot(env: Map<String, String> = System.getenv(), osName: String = System.getProperty("os.name")): File? {
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { env[it]?.takeIf(String::isNotBlank) }
        .map(::File).firstOrNull { it.isDirectory }?.let { return it }
    val home = System.getProperty("user.home")
    val os = osName.lowercase()
    val default = when {
        os.contains("win") -> env["LOCALAPPDATA"]?.let { File(it, "Android/Sdk") }
        os.contains("mac") -> File(home, "Library/Android/sdk")
        else -> File(home, "Android/Sdk")
    }
    return default?.takeIf { it.isDirectory }
}

internal fun adbPath(env: Map<String, String> = System.getenv(), windows: Boolean = System.getProperty("os.name").lowercase().contains("win")): String {
    val exe = if (windows) "adb.exe" else "adb"
    val f = androidSdkRoot(env)?.let { File(it, "platform-tools/$exe") }
    return if (f != null && f.isFile) f.path else "adb"
}

/** `am start` / `monkey` print these on failure while still exiting 0 on some adb versions. */
internal fun launchFailed(output: String): Boolean =
    listOf("Error type", "Error:", "No activities found", "Exception occurred").any { output.contains(it) }

/**
 * Returns a ≤40 line crash snippet when the logcat crash buffer or main buffer shows a fatal crash / ANR of [appId], else null.
 */
internal fun detectAndroidCrash(crashBuffer: String, mainBuffer: String, appId: String): String? {
    val pkg = Regex("(?<![\\w.])" + Regex.escape(appId) + "(?![\\w.])")
    for (buffer in listOf(crashBuffer, mainBuffer)) {
        val lines = buffer.lines()
        for ((i, line) in lines.withIndex()) {
            val hit = (line.contains("Process: ") && pkg.containsMatchIn(line.substringAfter("Process: "))) ||
                (line.contains("FATAL EXCEPTION") && pkg.containsMatchIn(line)) ||
                line.contains("ANR in $appId") ||
                line.contains(">>> $appId <<<")
            if (!hit) continue
            val start = (maxOf(0, i - 3)..i).lastOrNull { lines[it].contains("FATAL EXCEPTION") || lines[it].contains("ANR in") || lines[it].contains("*** ***") } ?: i
            return lines.drop(start).take(40).joinToString("\n").trimEnd()
        }
    }
    return null
}

/** Gradle needs the SDK location; a fresh worktree has no local.properties. */
internal fun sdkEnv(): Map<String, String> =
    androidSdkRoot()?.let { mapOf("ANDROID_HOME" to it.path, "ANDROID_SDK_ROOT" to it.path) } ?: emptyMap()

/** Gradle's "What went wrong" block when present, else the last non-blank lines. */
internal fun buildFailureSummary(output: String, maxLines: Int = 15): String {
    val lines = output.lines()
    val start = lines.indexOfFirst { it.trim() == "* What went wrong:" }
    if (start >= 0) {
        val block = lines.drop(start + 1).takeWhile { !it.startsWith("* Try:") }.filter { it.isNotBlank() }
        if (block.isNotEmpty()) return block.take(maxLines).joinToString("\n")
    }
    return lines.filter { it.isNotBlank() }.takeLast(maxLines).joinToString("\n")
}
