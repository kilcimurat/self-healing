package io.selfhealing.core.engine

import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.devices.DeviceManager
import io.selfhealing.core.harness.CliHarness
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.CrashStage
import io.selfhealing.core.model.CrashState
import io.selfhealing.core.model.EngineEvent
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.model.Platform
import io.selfhealing.core.publish.GitHubPrPublisher
import io.selfhealing.core.sentry.SentryCrashSource
import io.selfhealing.core.verify.AndroidVerifier
import io.selfhealing.core.verify.IosVerifier
import io.selfhealing.core.workspace.GitWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared by the desktop app and the headless server. */
class SelfHealingEngine(
    private val config: AppConfig,
    private val source: CrashSource,
    private val pipeline: Pipeline,
    /** Emulator / simulator the pipeline verifies on; the desktop app renders their screens. */
    val devices: DeviceManager,
) {
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val _crashes = MutableStateFlow<Map<String, CrashState>>(emptyMap())
    val crashes: StateFlow<Map<String, CrashState>> = _crashes.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var scope: CoroutineScope? = null
    private var queue = Channel<CrashReport>(Channel.UNLIMITED)

    val systemLog = LineSink { source, text -> _events.tryEmit(EngineEvent.Log(null, source, text)) }

    suspend fun start() {
        if (_running.value) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        queue = Channel(Channel.UNLIMITED)
        repeat(config.pipeline.maxConcurrent.coerceAtLeast(1)) { newScope.launch { worker() } }
        newScope.launch { devices.startAll(systemLog) }
        try {
            source.start(::enqueue)
        } catch (e: Exception) {
            newScope.coroutineContext[Job]?.cancelAndJoin()
            scope = null
            status(false, "Failed to start: ${e.message}")
            throw e
        }
        _running.value = true
        status(true, "Listening for Sentry crashes")
    }

    suspend fun stop() {
        if (!_running.value) return
        runCatching { source.stop() }.onFailure { systemLog.line(LogSource.SENTRY, "Stop failed: ${it.message}") }
        queue.close()
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        _running.value = false
        status(false, "Stopped")
    }

    /** Re-runs a finished crash (e.g. after fixing config). */
    fun retry(crashId: String) {
        val state = _crashes.value[crashId] ?: return
        if (!state.stage.terminal || !_running.value) return
        _crashes.update { it + (crashId to CrashState(state.report)) }
        queue.trySend(state.report)
    }

    private suspend fun enqueue(report: CrashReport) {
        val existing = _crashes.value[report.id]
        if (existing != null) return // already seen this issue
        _crashes.update { it + (report.id to CrashState(report)) }
        _events.emit(EngineEvent.CrashDetected(report))
        queue.send(report)
    }

    private suspend fun worker() {
        for (report in queue) pipeline.process(report, listener)
    }

    private val listener = object : Pipeline.Listener {
        override fun stage(crashId: String, stage: CrashStage, message: String) {
            mutate(crashId) { it.copy(stage = stage, message = message) }
            _events.tryEmit(EngineEvent.StageChanged(crashId, stage, message))
        }

        override fun log(crashId: String, source: LogSource, text: String) {
            _events.tryEmit(EngineEvent.Log(crashId, source, text))
        }

        override fun summary(crashId: String, summary: FixSummary) = mutate(crashId) { it.copy(summary = summary) }

        override fun artifactsRemoved(crashId: String) {
            mutate(crashId) { it.copy(screenshots = emptyList()) }
        }

        override fun screenshot(crashId: String, path: String) {
            mutate(crashId) { it.copy(screenshots = it.screenshots + path) }
            _events.tryEmit(EngineEvent.Screenshot(crashId, path))
        }

        override fun prOpened(crashId: String, url: String) {
            mutate(crashId) { it.copy(prUrl = url) }
            _events.tryEmit(EngineEvent.PrOpened(crashId, url))
        }
    }

    private fun mutate(crashId: String, block: (CrashState) -> CrashState) =
        _crashes.update { map -> map[crashId]?.let { map + (crashId to block(it)) } ?: map }

    private fun status(running: Boolean, message: String) {
        _events.tryEmit(EngineEvent.EngineStatus(running, message))
        systemLog.line(LogSource.SYSTEM, message)
    }

    companion object {
        /** Wires the default implementations from config. */
        fun create(config: AppConfig): SelfHealingEngine {
            lateinit var engine: SelfHealingEngine
            val sentryLog = LineSink { source, text -> engine.systemLog.line(source, text) }
            val devices = DeviceManager(config)
            val pipeline = Pipeline(
                config = config,
                workspace = GitWorkspace(config),
                harness = CliHarness(config.harness),
                verifiers = mapOf(
                    Platform.ANDROID to AndroidVerifier(config.pipeline, devices),
                    Platform.IOS to IosVerifier(config.pipeline, devices),
                ),
                publisher = GitHubPrPublisher(config.github),
            )
            engine = SelfHealingEngine(config, SentryCrashSource(config, sentryLog), pipeline, devices)
            return engine
        }
    }
}
