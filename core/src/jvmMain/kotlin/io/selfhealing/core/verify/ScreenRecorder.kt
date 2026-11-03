package io.selfhealing.core.verify

import io.selfhealing.core.model.LineSink
import io.selfhealing.core.process.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** A screen recording in progress; [stop] finishes it and returns the video, or null when nothing was recorded. */
interface ScreenRecording {
    suspend fun stop(): File?
}

/** Recording file of one platform's run, e.g. `android-release.mp4`; the pipeline combines them into one video. */
fun recordingFile(artifactsDir: File, prefix: String, phase: String) = File(artifactsDir, "$prefix-$phase.mp4")

/** Maestro's `--debug-output` folder for the flow replayed during that recording; its maestro.log has the taps. */
fun maestroDebugDir(artifactsDir: File, prefix: String, phase: String) = File(artifactsDir, "maestro-$prefix-$phase")

/** Tap sidecar of a recording: a `px`/`pt` unit line, then `<seconds> <x> <y>` per tap. */
fun tapsFile(video: File) = File(video.parentFile, video.nameWithoutExtension + ".taps")

data class Tap(val seconds: Double, val x: Int, val y: Int)

private val TAP_LINE = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3}) .*?(?:Try tapping|Tapping) at \((\d+), (\d+)\)""")

/**
 * Taps from a maestro.log, as seconds since [startedAt] (epoch ms). Log lines only carry the local time of day,
 * so they are placed on the recording's day (and the next one for a run across midnight).
 */
internal fun parseMaestroTaps(log: String, startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): List<Tap> {
    val start = Instant.ofEpochMilli(startedAt).atZone(zone)
    return log.lineSequence().mapNotNull { TAP_LINE.find(it) }.map { m ->
        var at = start.toLocalDate().atTime(LocalTime.parse(m.groupValues[1])).atZone(zone)
        if (at.isBefore(start.minusHours(1))) at = at.plusDays(1)
        Tap((at.toInstant().toEpochMilli() - startedAt) / 1000.0, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.filter { it.seconds >= 0 }.toList()
}

internal fun readTaps(file: File): Pair<String, List<Tap>>? = runCatching {
    val lines = file.readLines().filter { it.isNotBlank() }
    lines.first().trim() to lines.drop(1).map { l -> l.trim().split(' ').let { Tap(it[0].toDouble(), it[1].toInt(), it[2].toInt()) } }
}.getOrNull()

/** Writes the taps Maestro logged under [debugDir] next to [video]; [unit] is `px` (Android) or `pt` (iOS). */
private fun writeTaps(video: File, debugDir: File, startedAt: Long, unit: String) {
    // Every attempt's log (a retried flow writes its own), so all taps shown in the video are marked.
    val logs = debugDir.walk().filter { it.name == "maestro.log" }.toList().ifEmpty { return }
    val taps = logs.flatMap { parseMaestroTaps(it.readText(), startedAt) }.sortedBy { it.seconds }
    tapsFile(video).writeText(unit + "\n" + taps.joinToString("") { "%.2f %d %d\n".format(java.util.Locale.ROOT, it.seconds, it.x, it.y) })
}

/** Stops [recording] even when the surrounding coroutine is cancelled, so no recorder process is left behind. */
internal suspend inline fun <T> recorded(recording: ScreenRecording?, block: () -> T): T = recorded({ recording }, block)

/** As above, for a recording started inside [block] (read once [block] has finished). */
internal suspend inline fun <T> recorded(crossinline recording: () -> ScreenRecording?, block: () -> T): T =
    try {
        block()
    } finally {
        withContext(NonCancellable) { recording()?.stop() }
    }

private suspend fun waitFor(process: Process, seconds: Long): Boolean =
    runInterruptible(Dispatchers.IO) { process.waitFor(seconds, TimeUnit.SECONDS) }

private fun startProcess(command: List<String>): Process =
    ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

/**
 * Records the emulator with `screenrecord`, stopped with SIGINT so the MP4 is finalized, then pulled into [out].
 * "Show taps" is turned on as well, but headless emulators don't draw it, so the taps Maestro logged into
 * [debugDir] are written next to the video and drawn in when the recordings are combined.
 */
internal suspend fun startAndroidRecording(adb: List<String>, out: File, debugDir: File, log: LineSink): ScreenRecording? {
    out.delete()
    tapsFile(out).delete()
    val remote = "/sdcard/self-healing-${out.nameWithoutExtension}.mp4"
    val showTouches = ProcessRunner.run(adb + listOf("shell", "settings", "get", "system", "show_touches"), timeout = 15.seconds).output.trim()
    ProcessRunner.run(adb + listOf("shell", "settings", "put", "system", "show_touches", "1"), timeout = 15.seconds)
    val startedAt = System.currentTimeMillis()
    val process = runCatching { startProcess(adb + listOf("shell", "screenrecord", "--bit-rate", "6000000", "--time-limit", "180", remote)) }
        .onFailure { log.build("Android: screen recording could not start: ${it.message}") }.getOrNull() ?: return null
    delay(1.seconds) // screenrecord drops the first frames otherwise
    log.build("Android: recording ${out.name}")
    return object : ScreenRecording {
        override suspend fun stop(): File? {
            ProcessRunner.run(adb + listOf("shell", "pkill", "-INT", "screenrecord"), timeout = 15.seconds)
            if (!waitFor(process, 15)) process.destroyForcibly()
            delay(1.seconds) // the file is finalized after the process exits
            val pulled = ProcessRunner.run(adb + listOf("pull", remote, out.path), timeout = 120.seconds)
            ProcessRunner.run(adb + listOf("shell", "rm", "-f", remote), timeout = 15.seconds)
            // An unset setting reads as "null", which `settings put` rejects.
            if (showTouches != "1") ProcessRunner.run(adb + listOf("shell", "settings", "put", "system", "show_touches", "0"), timeout = 15.seconds)
            if (pulled.ok && out.length() > 0) return out.also { writeTaps(it, debugDir, startedAt, "px") }
            log.build("Android: recording ${out.name} failed: ${firstLines(pulled.output, 3)}")
            return null
        }
    }
}

/** Records the simulator with `simctl io recordVideo`; SIGINT finalizes the file. Taps are handled as on Android. */
internal suspend fun startIosRecording(udid: String, out: File, debugDir: File, log: LineSink): ScreenRecording? {
    out.delete()
    tapsFile(out).delete()
    val startedAt = System.currentTimeMillis()
    val process = runCatching { startProcess(listOf("xcrun", "simctl", "io", udid, "recordVideo", "--codec=h264", "--force", out.path)) }
        .onFailure { log.build("iOS: screen recording could not start: ${it.message}") }.getOrNull() ?: return null
    delay(1.seconds)
    log.build("iOS: recording ${out.name}")
    return object : ScreenRecording {
        override suspend fun stop(): File? {
            if (process.isAlive) ProcessRunner.run(listOf("kill", "-INT", process.pid().toString()), timeout = 10.seconds)
            if (!waitFor(process, 20)) process.destroyForcibly()
            if (out.length() > 0) return out.also { writeTaps(it, debugDir, startedAt, "pt") }
            log.build("iOS: recording ${out.name} failed")
            return null
        }
    }
}
