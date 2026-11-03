package io.selfhealing.core.process

import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class ProcessResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

class ProcessFailedException(val command: String, val result: ProcessResult) :
    RuntimeException("`$command` exited with ${result.exitCode}: ${result.output.takeLast(800)}")

object ProcessRunner {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Runs [command], streaming every stdout/stderr line to [log] as [source].
     * Cancelling the coroutine destroys the process tree.
     */
    suspend fun run(
        command: List<String>,
        dir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 30.minutes,
        log: LineSink? = null,
        source: LogSource = LogSource.SYSTEM,
        stdin: String? = null,
        /** Hide these strings (tokens) from logs and returned output. */
        redact: List<String> = emptyList(),
        /** Optional transform of each raw line before logging (e.g. stream-json → text); null drops the line. */
        display: (String) -> String? = { it },
    ): ProcessResult = withContext(Dispatchers.IO) {
        val secrets = redact.filter { it.length >= 4 }
        fun clean(s: String) = secrets.fold(s) { acc, secret -> acc.replace(secret, "***") }

        val process = ProcessBuilder(command)
            .apply { dir?.let { directory(it) } }
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()
        val output = StringBuilder()
        try {
            if (stdin != null) process.outputStream.bufferedWriter().use { it.write(stdin) }
            else process.outputStream.close()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val line = clean(raw)
                        synchronized(output) { output.appendLine(line) }
                        display(line)?.let { shown -> log?.line(source, shown) }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            if (!finished) {
                destroyTree(process)
                log?.line(source, "Timed out after $timeout")
            }
            reader.join(5_000)
            ProcessResult(if (finished) process.exitValue() else -1, synchronized(output) { output.toString() })
        } finally {
            if (process.isAlive) destroyTree(process)
        }
    }

    /** Runs a shell command line (`bash -lc` on macOS/Linux, `cmd /c` on Windows). */
    suspend fun shell(
        commandLine: String,
        dir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 30.minutes,
        log: LineSink? = null,
        source: LogSource = LogSource.BUILD,
        redact: List<String> = emptyList(),
    ): ProcessResult {
        log?.line(source, "$ $commandLine")
        val command = if (isWindows) listOf("cmd", "/c", commandLine) else listOf("bash", "-lc", commandLine)
        return run(command, dir, env, timeout, log, source, redact = redact)
    }

    /** Like [run] but throws [ProcessFailedException] on a non-zero exit code. */
    suspend fun checked(
        command: List<String>,
        dir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 30.minutes,
        log: LineSink? = null,
        source: LogSource = LogSource.SYSTEM,
        redact: List<String> = emptyList(),
    ): ProcessResult {
        val result = run(command, dir, env, timeout, log, source, redact = redact)
        if (!result.ok) throw ProcessFailedException(command.joinToString(" ").let { c -> redact.fold(c) { a, s -> a.replace(s, "***") } }, result)
        return result
    }

    private fun destroyTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
