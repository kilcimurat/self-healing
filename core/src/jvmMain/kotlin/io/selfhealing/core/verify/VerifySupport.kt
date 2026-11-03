package io.selfhealing.core.verify

import io.selfhealing.core.config.TestAccountConfig
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.process.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun formatDuration(d: Duration): String {
    val total = d.inWholeSeconds
    return if (total >= 60) "${total / 60}m${(total % 60).toString().padStart(2, '0')}s" else "${total}s"
}

internal fun LineSink.build(text: String) = line(LogSource.BUILD, text)

/** `maestro test` arguments that hand the test account to the flow as `${TEST_USERNAME}` / `${TEST_PASSWORD}`. */
internal fun maestroAccountArgs(account: TestAccountConfig?): List<String> =
    if (account?.isSet == true) listOf("-e", "TEST_USERNAME=${account.username}", "-e", "TEST_PASSWORD=${account.password}") else emptyList()

/** Writes the run's maestro.log (with tap coordinates) into a fresh [dir] instead of ~/.maestro/tests. */
internal fun maestroDebugArgs(dir: File): List<String> {
    dir.deleteRecursively()
    return listOf("--debug-output", dir.absolutePath, "--flatten-debug-output")
}

/**
 * Runs a repro flow, and once more when it fails without a crash: UI steps can flake on a busy machine (e.g. a
 * keyboard that is not hidden in time covers the next button). A crash is a result and is never retried.
 * [attempt] runs the flow with the given Maestro debug folder; null means there was nothing to run.
 */
internal suspend fun replayWithRetry(
    debugDir: File, label: String, log: LineSink, crashed: suspend () -> Boolean, attempt: suspend (File) -> ProcessResult?,
): ProcessResult? {
    val first = attempt(debugDir) ?: return null
    if (first.ok) return first
    delay(2.seconds) // crash reports are written shortly after the process dies
    if (crashed()) return first
    log.build("$label: repro flow failed without a crash; retrying once")
    return attempt(File(debugDir, "retry"))
}

/** Values to mask in logs. */
internal fun accountSecrets(account: TestAccountConfig?): List<String> = listOfNotNull(account?.password?.takeIf { it.isNotBlank() })

internal fun firstLines(text: String, max: Int = 40): String =
    text.lines().take(max).joinToString("\n").trimEnd()

/** Runs [command] with stdout redirected (binary-safe) into [file]; true when it exited 0 and wrote data. */
internal suspend fun captureToFile(command: List<String>, file: File, timeout: Duration = 30.seconds): Boolean =
    withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .redirectOutput(file)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")))
            .start()
        try {
            val done = runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            done && process.exitValue() == 0 && file.length() > 0
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
