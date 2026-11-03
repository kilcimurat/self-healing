package io.selfhealing.server

import io.selfhealing.core.config.ConfigLoader
import io.selfhealing.core.engine.SelfHealingEngine
import io.selfhealing.core.model.EngineEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun stamp(ts: Long) = timeFormat.format(Instant.ofEpochMilli(ts))

private fun emit(ts: Long, tag: String, crashId: String?, text: String) {
    val id = if (crashId != null) " [$crashId]" else ""
    kotlin.io.println("${stamp(ts)} [$tag]$id $text")
    System.out.flush()
}

private fun parseConfigPath(args: Array<String>): String {
    val i = args.indexOf("--config")
    if (i >= 0 && i + 1 < args.size) return args[i + 1]
    args.firstOrNull { it.startsWith("--config=") }?.let { return it.removePrefix("--config=") }
    return System.getenv("SELF_HEALING_CONFIG")?.takeIf { it.isNotBlank() } ?: "./config.yml"
}

private fun format(event: EngineEvent) {
    when (event) {
        is EngineEvent.Log -> emit(event.timestamp, event.source.name, event.crashId, event.text)
        is EngineEvent.EngineStatus ->
            emit(event.timestamp, "SYSTEM", null, "engine ${if (event.running) "running" else "stopped"}: ${event.message}")
        is EngineEvent.CrashDetected -> emit(
            event.timestamp, "CRASH", event.report.id,
            "detected: ${event.report.title} (${event.report.projectSlug}, ${event.report.platform})",
        )
        is EngineEvent.StageChanged ->
            emit(event.timestamp, "STAGE", event.crashId, "${event.stage} ${event.message}".trim())
        is EngineEvent.Screenshot -> emit(event.timestamp, "SCREENSHOT", event.crashId, event.path)
        is EngineEvent.PrOpened -> emit(event.timestamp, "PR", event.crashId, "opened: ${event.url}")
    }
}

fun main(args: Array<String>) {
    val file = File(parseConfigPath(args)).absoluteFile
    val config = try {
        ConfigLoader.load(file)
    } catch (e: Throwable) {
        System.err.println("Config error (${file.path}): ${e.message ?: e::class.simpleName}")
        exitProcess(1)
    }

    val engine = SelfHealingEngine.create(config)

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            withTimeoutOrNull(30_000) { engine.stop() }
        }
    })

    runBlocking {
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { format(it) }
        }
        try {
            engine.start()
        } catch (e: Throwable) {
            System.err.println("Failed to start engine: ${e.message ?: e::class.simpleName}")
            exitProcess(1)
        }
        awaitCancellation()
    }
}
