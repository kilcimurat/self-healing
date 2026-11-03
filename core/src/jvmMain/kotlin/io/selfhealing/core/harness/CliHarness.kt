package io.selfhealing.core.harness

import io.selfhealing.core.config.HarnessConfig
import io.selfhealing.core.config.HarnessType
import io.selfhealing.core.engine.Harness
import io.selfhealing.core.engine.HarnessRequest
import io.selfhealing.core.engine.HarnessResult
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.process.ProcessRunner
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

class CliHarness(private val config: HarnessConfig) : Harness {

    override suspend fun run(request: HarnessRequest, log: LineSink): HarnessResult {
        val resultFile = request.resultFile.absoluteFile
        val resultDir = resultFile.parentFile.apply { mkdirs() }
        resultFile.delete()
        val prompt = PromptBuilder.build(request.crash, request.repo, resultFile)
        val promptFile = resultDir.resolve("prompt.md").apply { writeText(prompt) }
        log.line(LogSource.HARNESS, "Prompt written to ${promptFile.path} (${prompt.length} chars), harness=${config.type} model=${config.model}")

        val repoDir = request.repoDir.absoluteFile
        val timeout = config.timeoutMinutes.minutes
        val result = when (config.type) {
            HarnessType.CLAUDE_CODE -> ProcessRunner.run(
                listOf(
                    "claude", "-p", "--model", config.model,
                    "--output-format", "stream-json", "--verbose",
                    "--permission-mode", "acceptEdits",
                    "--allowedTools", "Read,Edit,Write,Glob,Grep,Bash",
                    "--add-dir", resultDir.path,
                ) + config.extraArgs,
                dir = repoDir, env = config.env, timeout = timeout, log = log, source = LogSource.HARNESS,
                stdin = prompt, display = StreamJsonDisplay::render,
            )
            HarnessType.CODEX -> ProcessRunner.run(
                listOf(
                    "codex", "exec", "--model", config.model, "--full-auto",
                    "--skip-git-repo-check", "-C", repoDir.path, "-",
                ) + config.extraArgs,
                dir = repoDir, env = config.env, timeout = timeout, log = log, source = LogSource.HARNESS, stdin = prompt,
            )
            HarnessType.CUSTOM -> {
                require(config.command.isNotBlank()) { "harness.command must be set for the custom harness" }
                val cmd = config.command
                    .replace("{model}", config.model)
                    .replace("{promptFile}", promptFile.path)
                    .replace("{repoDir}", repoDir.path)
                    .replace("{resultFile}", resultFile.path)
                ProcessRunner.shell(cmd, dir = repoDir, env = config.env, timeout = timeout, log = log, source = LogSource.HARNESS)
            }
        }

        val summary = readSummary(resultFile)
            ?: parseFromOutput(result.output)
        return HarnessResult(result.exitCode, summary)
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun readSummary(file: java.io.File): FixSummary? =
            if (file.isFile) runCatching { json.decodeFromString<FixSummary>(file.readText()) }.getOrNull() else null

        /** Finds the last JSON object containing "rootCause" in [output] (or in the stream-json final result text). */
        fun parseFromOutput(output: String): FixSummary? {
            val texts = listOfNotNull(StreamJsonDisplay.finalResultText(output), output)
            for (text in texts) extractLastSummary(text)?.let { return it }
            return null
        }

        private fun extractLastSummary(text: String): FixSummary? {
            var idx = text.lastIndexOf("\"rootCause\"")
            var attempts = 0
            while (idx >= 0 && attempts++ < 20) {
                var start = text.lastIndexOf('{', idx)
                var tries = 0
                while (start >= 0 && tries++ < 50) {
                    val end = matchingBrace(text, start)
                    if (end > idx) {
                        runCatching { json.decodeFromString<FixSummary>(text.substring(start, end + 1)) }.getOrNull()?.let { return it }
                    }
                    start = if (start == 0) -1 else text.lastIndexOf('{', start - 1)
                }
                idx = if (idx == 0) -1 else text.lastIndexOf("\"rootCause\"", idx - 1)
            }
            return null
        }

        private fun matchingBrace(s: String, start: Int): Int {
            var depth = 0
            var inStr = false
            var i = start
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '\\') i++ else if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
                i++
            }
            return -1
        }
    }
}
