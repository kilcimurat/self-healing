package io.selfhealing.core.devices

import io.selfhealing.core.config.IosConfig
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.process.ProcessRunner
import io.selfhealing.core.process.Tools
import io.selfhealing.core.verify.SimDevice
import io.selfhealing.core.verify.firstLines
import io.selfhealing.core.verify.resolveSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** simctl helpers shared by [DeviceManager] and the iOS verifier. */
internal object IosSimulators {
    /** Resolves [IosConfig.simulator] (name or UDID), boots it headless if needed (and opens Simulator.app when configured); returns its UDID. */
    suspend fun ensureBooted(cfg: IosConfig, log: LineSink): String? {
        val sim: SimDevice = resolveSimulator(cfg) {
            ProcessRunner.run(listOf("xcrun", "simctl", "list", "devices", "available", "-j"), timeout = 60.seconds).output
        } ?: run {
            log.line(LogSource.SYSTEM, "Simulator '${cfg.simulator}' not found (xcrun simctl list devices available)")
            return null
        }
        val udid = sim.udid
        log.line(LogSource.SYSTEM, "iOS simulator ${cfg.simulator} = $udid")
        val boot = ProcessRunner.run(listOf("xcrun", "simctl", "boot", udid), timeout = 120.seconds)
        if (!boot.ok && !boot.output.contains("current state: Booted")) {
            log.line(LogSource.SYSTEM, "Could not boot simulator: ${firstLines(boot.output, 5)}")
            return null
        }
        val status = ProcessRunner.run(listOf("xcrun", "simctl", "bootstatus", udid, "-b"), timeout = 5.minutes)
        if (!status.ok) {
            log.line(LogSource.SYSTEM, "Simulator did not finish booting: ${firstLines(status.output, 5)}")
            return null
        }
        if (cfg.openSimulatorApp) {
            runCatching { ProcessRunner.run(listOf("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", udid), timeout = 30.seconds) }
        }
        log.line(LogSource.SYSTEM, "iOS simulator $udid ready")
        return udid
    }

    private val scales = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** Screenshot pixels per AXe point for [udid] (3.0 on current iPhones), read once from the UI tree. */
    private suspend fun scale(udid: String, axe: String, pixelWidth: Int): Double = scales[udid] ?: run {
        val out = ProcessRunner.run(listOf(axe, "describe-ui", "--udid", udid), timeout = 30.seconds).output
        val points = Regex("\"width\"\\s*:\\s*([0-9.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        val s = if (points != null && points > 0) pixelWidth / points else 3.0
        s.also { scales[udid] = it }
    }

    /** Taps at screenshot pixel ([x], [y]); false when AXe is not installed. */
    suspend fun tap(udid: String, x: Int, y: Int, pixelWidth: Int): Boolean {
        val axe = Tools.axe() ?: return false
        val s = scale(udid, axe, pixelWidth)
        return ProcessRunner.run(listOf(axe, "tap", "-x", "%.1f".format(java.util.Locale.ROOT, x / s), "-y", "%.1f".format(java.util.Locale.ROOT, y / s), "--udid", udid), timeout = 15.seconds).ok
    }

    suspend fun swipe(udid: String, x1: Int, y1: Int, x2: Int, y2: Int, millis: Int, pixelWidth: Int): Boolean {
        val axe = Tools.axe() ?: return false
        val s = scale(udid, axe, pixelWidth)
        fun p(v: Int) = "%.1f".format(java.util.Locale.ROOT, v / s)
        return ProcessRunner.run(
            listOf(axe, "swipe", "--start-x", p(x1), "--start-y", p(y1), "--end-x", p(x2), "--end-y", p(y2),
                "--duration", "%.2f".format(java.util.Locale.ROOT, millis / 1000.0), "--udid", udid),
            timeout = 15.seconds,
        ).ok
    }

    suspend fun home(udid: String): Boolean {
        val axe = Tools.axe() ?: return false
        return ProcessRunner.run(listOf(axe, "button", "home", "--udid", udid), timeout = 15.seconds).ok
    }

    /** PNG of the simulator's screen, or null. */
    suspend fun screenshot(udid: String): ByteArray? = withContext(Dispatchers.IO) {
        val tmp = runCatching { File.createTempFile("ios-shot-", ".png") }.getOrNull() ?: return@withContext null
        try {
            val r = ProcessRunner.run(listOf("xcrun", "simctl", "io", udid, "screenshot", "--type=png", tmp.path), timeout = 10.seconds)
            if (r.ok && tmp.length() > 0) tmp.readBytes() else null
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }
}
