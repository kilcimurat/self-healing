package io.selfhealing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.config.ConfigLoader
import io.selfhealing.core.engine.SelfHealingEngine
import io.selfhealing.core.model.CrashState
import io.selfhealing.core.model.EngineEvent
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.model.Platform
import io.selfhealing.core.devices.DeviceInfo
import io.selfhealing.core.devices.DeviceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.isActive
import org.jetbrains.skia.Image as SkiaImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LogLine(val id: Long, val time: Long, val source: LogSource, val text: String)

/** A decoded device screen with its intrinsic pixel size. */
class DeviceFrame(val bitmap: ImageBitmap, val width: Int, val height: Int)

enum class EngineStatus { STOPPED, STARTING, LISTENING, ERROR }

const val SYSTEM_TAB = "__system__"
private const val MAX_LINES = 5000

class AppViewModel(initialConfigPath: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var configPath by mutableStateOf(initialConfigPath)
    var status by mutableStateOf(EngineStatus.STOPPED)
        internal set
    var error by mutableStateOf<String?>(null)
    var config by mutableStateOf<AppConfig?>(null)
        internal set
    var crashes by mutableStateOf<Map<String, CrashState>>(emptyMap())
        private set
    var selectedCrashId by mutableStateOf<String?>(null)
        private set
    var activeTab by mutableStateOf(SYSTEM_TAB)
        private set
    var now by mutableStateOf(System.currentTimeMillis())
        private set

    /** crashId -> first seen (millis). */
    val detectedAt = mutableStateMapOf<String, Long>()
    private val tabs = mutableStateMapOf<String, SnapshotStateList<LogLine>>()

    /** Live device list from the engine (empty until it starts). */
    var liveDevices by mutableStateOf<List<DeviceInfo>>(emptyList())
        internal set
    val frames = mutableStateMapOf<Platform, DeviceFrame>()

    private val pollJobs = HashMap<Platform, Pair<String?, Job>>()

    /** Always [Android, iOS]: engine devices, else placeholders from config, else OFF. */
    val deviceTiles: List<DeviceInfo>
        get() {
            val repos = config?.repos.orEmpty()
            return listOf(Platform.ANDROID, Platform.IOS).map { p ->
                liveDevices.firstOrNull { it.platform == p } ?: when (p) {
                    Platform.ANDROID -> repos.firstNotNullOfOrNull { it.android }
                        ?.let { DeviceInfo(p, it.device.ifBlank { null }, it.avd.ifBlank { it.device.ifBlank { "Android" } }, DeviceState.OFF) }
                    else -> repos.firstNotNullOfOrNull { it.ios }
                        ?.let { DeviceInfo(p, null, it.simulator, DeviceState.OFF) }
                } ?: DeviceInfo(p, null, if (p == Platform.ANDROID) "Android" else "iOS", DeviceState.OFF)
            }
        }

    private val axeInstalled = io.selfhealing.core.process.Tools.axe() != null

    /** Show the "view only" hint: AXe (iOS input) missing and Simulator.app not opened for manual use. */
    val iosInteractiveHint: Boolean
        get() = !axeInstalled && config?.repos.orEmpty().none { it.ios?.openSimulatorApp == true }

    private var engine: SelfHealingEngine? = null
    private var collectJobs = emptyList<Job>()
    private var nextId = 0L
    private val pending = ArrayList<Pair<String, LogLine>>()

    init {
        tabs[SYSTEM_TAB] = mutableStateListOf()
        scope.launch {
            while (true) {
                delay(100)
                flush()
            }
        }
        scope.launch {
            while (true) {
                delay(15_000)
                now = System.currentTimeMillis()
            }
        }
    }

    fun linesFor(tab: String): List<LogLine> = tabs[tab] ?: emptyList()

    /** Crash ids newest first. */
    val sortedCrashes: List<CrashState>
        get() = crashes.values.sortedByDescending { detectedAt[it.report.id] ?: 0L }

    fun selectCrash(id: String?) {
        selectedCrashId = id
        if (id != null) activeTab = id
    }

    fun selectTab(tab: String) {
        activeTab = tab
        if (tab != SYSTEM_TAB) selectedCrashId = tab
    }

    fun start() {
        if (status == EngineStatus.STARTING || status == EngineStatus.LISTENING) return
        error = null
        status = EngineStatus.STARTING
        val path = configPath
        scope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) { ConfigLoader.load(File(path).absoluteFile) }
                config = cfg
                val eng = SelfHealingEngine.create(cfg)
                engine = eng
                attach(eng)
                withContext(Dispatchers.IO) { eng.start() }
                status = EngineStatus.LISTENING
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Başlatılamadı: ${e.message ?: e::class.simpleName}"
                status = EngineStatus.ERROR
                detach()
                engine = null
            }
        }
    }

    fun stop() {
        val eng = engine ?: run { status = EngineStatus.STOPPED; return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { eng.stop() }
                status = EngineStatus.STOPPED
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Durdurulamadı: ${e.message ?: e::class.simpleName}"
                status = EngineStatus.ERROR
            } finally {
                flush()
                detach()
                engine = null
            }
        }
    }

    /** Called on window close; blocks briefly so the engine shuts down cleanly. */
    suspend fun shutdown() {
        val eng = engine
        if (eng != null) {
            try {
                withContext(Dispatchers.IO) { eng.stop() }
            } catch (_: Throwable) {
            }
        }
        scope.cancel()
    }

    fun retry(id: String) {
        engine?.retry(id)
    }

    private fun attach(eng: SelfHealingEngine) {
        detach()
        collectJobs = listOf(
            scope.launch { eng.events.collect { onEvent(it) } },
            scope.launch { eng.crashes.collect { onCrashes(it) } },
            scope.launch { eng.devices.devices.collect { onDevices(it) } },
            scope.launch {
                eng.running.collect { running ->
                    if (!running && status == EngineStatus.LISTENING) status = EngineStatus.STOPPED
                }
            },
        )
    }

    private fun detach() {
        collectJobs.forEach { it.cancel() }
        collectJobs = emptyList()
        pollJobs.values.forEach { it.second.cancel() }
        pollJobs.clear()
        frames.clear()
        liveDevices = emptyList()
    }

    internal fun onDevices(list: List<DeviceInfo>) {
        liveDevices = list
        for (p in listOf(Platform.ANDROID, Platform.IOS)) {
            val info = list.firstOrNull { it.platform == p }
            val running = pollJobs[p]
            if (info == null || info.state != DeviceState.READY || info.id == null) {
                running?.second?.cancel()
                pollJobs.remove(p)
                frames.remove(p)
            } else if (running == null || running.first != info.id || !running.second.isActive) {
                running?.second?.cancel()
                pollJobs[p] = info.id to scope.launch { pollScreen(p, info.id!!) }
            }
        }
    }

    private suspend fun pollScreen(platform: Platform, id: String) {
        val period = if (platform == Platform.ANDROID) 330L else 500L
        var last: ByteArray? = null
        while (scope.isActive) {
            val eng = engine ?: return
            val info = eng.devices.devices.value.firstOrNull { it.platform == platform }
            if (info == null || info.state != DeviceState.READY || info.id != id) return
            val began = System.currentTimeMillis()
            // Sequential loop => requests for one device never overlap.
            val bytes = try {
                withContext(Dispatchers.IO) { eng.devices.screenshot(info) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (bytes != null && (last == null || !bytes.contentEquals(last))) {
                last = bytes
                try {
                    val frame = withContext(Dispatchers.IO) {
                        val img = SkiaImage.makeFromEncoded(bytes)
                        DeviceFrame(img.toComposeImageBitmap(), img.width, img.height)
                    }
                    frames[platform] = frame
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            delay((period - (System.currentTimeMillis() - began)).coerceAtLeast(30L))
        }
    }

    private fun androidSerial(): String? =
        liveDevices.firstOrNull { it.platform == Platform.ANDROID && it.state == DeviceState.READY }?.id

    private fun readyDevice(platform: Platform) =
        liveDevices.firstOrNull { it.platform == platform && it.state == DeviceState.READY }

    /** Tap at screenshot pixel coordinates on [platform]'s device (Android via adb, iOS via AXe). */
    fun tap(platform: Platform, x: Int, y: Int) {
        val eng = engine ?: return
        val device = readyDevice(platform) ?: return
        val width = frames[platform]?.width ?: return
        scope.launch(Dispatchers.IO) { runCatching { eng.devices.tap(device, x, y, width) } }
    }

    fun swipe(platform: Platform, x1: Int, y1: Int, x2: Int, y2: Int, millis: Int) {
        val eng = engine ?: return
        val device = readyDevice(platform) ?: return
        val width = frames[platform]?.width ?: return
        scope.launch(Dispatchers.IO) { runCatching { eng.devices.swipe(device, x1, y1, x2, y2, millis, width) } }
    }

    fun home(platform: Platform) {
        val eng = engine ?: return
        val device = readyDevice(platform) ?: return
        scope.launch(Dispatchers.IO) { runCatching { eng.devices.home(device) } }
    }

    fun androidKey(keycode: String) {
        val eng = engine ?: return
        val serial = androidSerial() ?: return
        scope.launch(Dispatchers.IO) { runCatching { eng.devices.androidKey(serial, keycode) } }
    }

    /** Test hook: show a frame without an engine. */
    internal fun setFrameForTest(platform: Platform, frame: DeviceFrame) { frames[platform] = frame }

    internal fun onCrashes(map: Map<String, CrashState>) {
        val t = System.currentTimeMillis()
        for (id in map.keys) if (id !in detectedAt) detectedAt[id] = t
        crashes = map
    }

    internal fun onEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.Log -> add(e.crashId ?: SYSTEM_TAB, e.timestamp, e.source, e.text)
            is EngineEvent.EngineStatus -> add(SYSTEM_TAB, e.timestamp, LogSource.SYSTEM, e.message)
            is EngineEvent.CrashDetected -> {
                detectedAt.getOrPut(e.report.id) { e.timestamp }
                add(e.report.id, e.timestamp, LogSource.SENTRY, "Crash alındı: ${e.report.title}")
                add(SYSTEM_TAB, e.timestamp, LogSource.SENTRY, "Yeni crash: ${e.report.title} (${e.report.projectSlug})")
            }
            is EngineEvent.StageChanged -> add(
                e.crashId, e.timestamp, LogSource.SYSTEM,
                "▸ Aşama: ${e.stage.name}" + if (e.message.isNotBlank()) " — ${e.message}" else "",
            )
            is EngineEvent.Screenshot -> add(e.crashId, e.timestamp, LogSource.SYSTEM, "Ekran görüntüsü: ${e.path}")
            is EngineEvent.PrOpened -> add(e.crashId, e.timestamp, LogSource.GIT, "PR açıldı: ${e.url}")
        }
    }

    private fun add(tab: String, time: Long, source: LogSource, text: String) {
        for (l in text.split('\n')) pending += tab to LogLine(nextId++, time, source, l.trimEnd('\r'))
    }

    internal fun flush() {
        if (pending.isEmpty()) return
        val grouped = pending.groupBy({ it.first }, { it.second })
        pending.clear()
        for ((tab, lines) in grouped) {
            val list = tabs.getOrPut(tab) { mutableStateListOf() }
            list.addAll(lines)
            if (list.size > MAX_LINES) list.removeRange(0, list.size - MAX_LINES)
        }
    }
}
