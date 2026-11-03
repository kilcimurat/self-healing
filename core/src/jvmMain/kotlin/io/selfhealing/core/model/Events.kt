package io.selfhealing.core.model

enum class LogSource { SYSTEM, SENTRY, HARNESS, BUILD, GIT }

/** Receives output lines; implementations must be thread-safe. */
fun interface LineSink {
    fun line(source: LogSource, text: String)
}

sealed interface EngineEvent {
    val timestamp: Long

    data class EngineStatus(val running: Boolean, val message: String, override val timestamp: Long = now()) : EngineEvent
    data class CrashDetected(val report: CrashReport, override val timestamp: Long = now()) : EngineEvent
    data class StageChanged(val crashId: String, val stage: CrashStage, val message: String, override val timestamp: Long = now()) : EngineEvent
    data class Log(val crashId: String?, val source: LogSource, val text: String, override val timestamp: Long = now()) : EngineEvent
    data class Screenshot(val crashId: String, val path: String, override val timestamp: Long = now()) : EngineEvent
    data class PrOpened(val crashId: String, val url: String, override val timestamp: Long = now()) : EngineEvent
}

private fun now() = System.currentTimeMillis()
