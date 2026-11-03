package io.selfhealing.core.devices

import io.selfhealing.core.config.AndroidConfig
import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.config.IosConfig
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.model.Platform
import io.selfhealing.core.process.ProcessRunner
import io.selfhealing.core.process.Tools
import io.selfhealing.core.verify.adbPath
import io.selfhealing.core.verify.androidSdkRoot
import io.selfhealing.core.verify.parseAdbDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class DeviceState { OFF, STARTING, READY, ERROR }

data class DeviceInfo(
    val platform: Platform,
    /** adb serial / simulator UDID once known. */
    val id: String?,
    /** AVD / simulator name as configured. */
    val name: String,
    val state: DeviceState,
    val message: String = "",
)

/**
 * Starts and tracks the Android emulator and iOS simulator the pipeline verifies on, and gives the
 * desktop app their screens. Devices are started headless; the app shows them in its own panel.
 */
class DeviceManager(private val config: AppConfig) {
    private val _devices = MutableStateFlow(initialDevices())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val androidLock = Mutex()
    private val iosLock = Mutex()
    private val logDir get() = File(config.resolvePath(config.pipeline.workDir), "logs").apply { mkdirs() }

    private fun initialDevices(): List<DeviceInfo> = buildList {
        config.repos.firstNotNullOfOrNull { it.android }?.let { add(DeviceInfo(Platform.ANDROID, it.device.ifBlank { null }, it.avd.ifBlank { it.device.ifBlank { "Android" } }, DeviceState.OFF)) }
        config.repos.firstNotNullOfOrNull { it.ios }?.let { add(DeviceInfo(Platform.IOS, null, it.simulator, DeviceState.OFF)) }
    }

    /** Boots every configured device in parallel; failures are reported on the device, not thrown. */
    suspend fun startAll(log: LineSink) = coroutineScope {
        val android = config.repos.firstNotNullOfOrNull { it.android }?.let { async { ensureAndroid(it, log) } }
        val ios = config.repos.firstNotNullOfOrNull { it.ios }?.let { async { ensureIos(it, log) } }
        android?.await(); ios?.await()
    }

    // ---- Android ----

    /** Returns the adb serial of a booted device for [cfg], starting its AVD headless when needed. */
    suspend fun ensureAndroid(cfg: AndroidConfig, log: LineSink): String? = androidLock.withLock {
        val name = cfg.avd.ifBlank { cfg.device.ifBlank { "Android" } }
        try {
            findAndroid(cfg)?.let { return it.also { serial -> ready(Platform.ANDROID, serial, "") } }
            if (cfg.avd.isBlank()) {
                return null.also { set(Platform.ANDROID, null, DeviceState.ERROR, "No device ${cfg.device} connected and no `avd` configured") }
            }
            val emulator = androidSdkRoot()?.let { File(it, "emulator/emulator") }?.takeIf { it.isFile }
                ?: return null.also { set(Platform.ANDROID, null, DeviceState.ERROR, "Android emulator binary not found (ANDROID_HOME)") }
            set(Platform.ANDROID, null, DeviceState.STARTING, "Starting AVD $name")
            log.line(LogSource.SYSTEM, "Starting Android emulator ${cfg.avd} (headless)")
            withContext(Dispatchers.IO) {
                val dns = cfg.dnsServers.filter { it.isNotBlank() }
                ProcessBuilder(
                    listOf(emulator.path, "-avd", cfg.avd, "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot-save") +
                        (if (dns.isNotEmpty()) listOf("-dns-server", dns.joinToString(",")) else emptyList()),
                )
                    .redirectErrorStream(true)
                    .redirectOutput(File(logDir, "emulator-${cfg.avd}.log"))
                    .start()
            }
            val started = TimeSource.Monotonic.markNow()
            while (started.elapsedNow() < 4.minutes) {
                delay(3.seconds)
                val serial = findAndroid(cfg) ?: continue
                log.line(LogSource.SYSTEM, "Android emulator ${cfg.avd} ready as $serial")
                return serial.also { ready(Platform.ANDROID, it, "") }
            }
            null.also { set(Platform.ANDROID, null, DeviceState.ERROR, "AVD ${cfg.avd} did not boot within 4 minutes") }
        } catch (e: Exception) {
            set(Platform.ANDROID, null, DeviceState.ERROR, e.message ?: "Android start failed")
            null
        }
    }

    /** A booted device matching [cfg]: the configured serial, else a running emulator of the AVD, else the only device. */
    private suspend fun findAndroid(cfg: AndroidConfig): String? {
        val online = parseAdbDevices(adb("devices").output).filter { it.state == "device" }.map { it.serial }
        val candidate = when {
            cfg.device.isNotBlank() -> cfg.device.takeIf { it in online }
            cfg.avd.isNotBlank() -> online.filter { it.startsWith("emulator-") }
                .firstOrNull { adb("-s", it, "emu", "avd", "name").output.lineSequence().firstOrNull()?.trim() == cfg.avd }
            else -> online.singleOrNull()
        } ?: return null
        val booted = adb("-s", candidate, "shell", "getprop", "sys.boot_completed").output.trim() == "1"
        return candidate.takeIf { booted }
    }

    /** True when touches can be sent to the iOS simulator (AXe installed). */
    val iosInteractive: Boolean get() = Tools.axe() != null

    /** Tap at screenshot pixel ([x], [y]) on a READY device; [frameWidth] is the screenshot's pixel width. */
    suspend fun tap(device: DeviceInfo, x: Int, y: Int, frameWidth: Int) {
        val id = device.id?.takeIf { device.state == DeviceState.READY } ?: return
        when (device.platform) {
            Platform.ANDROID -> androidTap(id, x, y)
            Platform.IOS -> IosSimulators.tap(id, x, y, frameWidth)
            Platform.UNKNOWN -> Unit
        }
    }

    suspend fun swipe(device: DeviceInfo, x1: Int, y1: Int, x2: Int, y2: Int, millis: Int, frameWidth: Int) {
        val id = device.id?.takeIf { device.state == DeviceState.READY } ?: return
        when (device.platform) {
            Platform.ANDROID -> androidSwipe(id, x1, y1, x2, y2, millis)
            Platform.IOS -> IosSimulators.swipe(id, x1, y1, x2, y2, millis, frameWidth)
            Platform.UNKNOWN -> Unit
        }
    }

    suspend fun home(device: DeviceInfo) {
        val id = device.id?.takeIf { device.state == DeviceState.READY } ?: return
        when (device.platform) {
            Platform.ANDROID -> androidKey(id, "KEYCODE_HOME")
            Platform.IOS -> IosSimulators.home(id)
            Platform.UNKNOWN -> Unit
        }
    }

    suspend fun androidTap(serial: String, x: Int, y: Int) { adb("-s", serial, "shell", "input", "tap", "$x", "$y") }

    suspend fun androidSwipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, millis: Int = 250) {
        adb("-s", serial, "shell", "input", "swipe", "$x1", "$y1", "$x2", "$y2", "$millis")
    }

    /** e.g. `KEYCODE_BACK`, `KEYCODE_HOME`. */
    suspend fun androidKey(serial: String, keycode: String) { adb("-s", serial, "shell", "input", "keyevent", keycode) }

    suspend fun androidText(serial: String, text: String) {
        adb("-s", serial, "shell", "input", "text", text.replace(" ", "%s"))
    }

    // ---- iOS ----

    /** Returns the UDID of a booted simulator for [cfg], booting it headless when needed. */
    suspend fun ensureIos(cfg: IosConfig, log: LineSink): String? = iosLock.withLock {
        if (!System.getProperty("os.name").lowercase().contains("mac")) {
            return null.also { set(Platform.IOS, null, DeviceState.ERROR, "iOS simulator needs macOS") }
        }
        set(Platform.IOS, null, DeviceState.STARTING, "Booting ${cfg.simulator}")
        try {
            IosSimulators.ensureBooted(cfg, log)
                ?.also { ready(Platform.IOS, it, "") }
                ?: null.also { set(Platform.IOS, null, DeviceState.ERROR, "Simulator ${cfg.simulator} not found") }
        } catch (e: Exception) {
            set(Platform.IOS, null, DeviceState.ERROR, e.message ?: "iOS boot failed")
            null
        }
    }

    // ---- screens ----

    /** Current PNG frame of a READY device, or null. */
    suspend fun screenshot(device: DeviceInfo): ByteArray? {
        val id = device.id?.takeIf { device.state == DeviceState.READY } ?: return null
        return when (device.platform) {
            Platform.ANDROID -> captureBytes(listOf(adbPath(), "-s", id, "exec-out", "screencap", "-p"))
            Platform.IOS -> IosSimulators.screenshot(id)
            Platform.UNKNOWN -> null
        }
    }

    // ---- state ----

    private fun ready(platform: Platform, id: String, message: String) = set(platform, id, DeviceState.READY, message)

    private fun set(platform: Platform, id: String?, state: DeviceState, message: String) = _devices.update { list ->
        list.map { if (it.platform == platform) it.copy(id = id ?: it.id, state = state, message = message) else it }
    }

    private suspend fun adb(vararg args: String) = ProcessRunner.run(listOf(adbPath()) + args, timeout = 30.seconds)
}

/** Runs [command] and returns its raw stdout, or null on failure/timeout. */
internal suspend fun captureBytes(command: List<String>, timeout: Duration = 10.seconds): ByteArray? = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        val bytes = process.inputStream.readBytes()
        val done = runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        bytes.takeIf { done && process.exitValue() == 0 && it.isNotEmpty() }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
