package io.selfhealing.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Turns Claude Code `--output-format stream-json` lines into readable terminal text. */
object StreamJsonDisplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns the text to show, or null to drop the line. Non-JSON lines pass through. */
    fun render(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return line
        val obj = try {
            json.parseToJsonElement(trimmed) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return line
        return when (obj.str("type")) {
            "system" -> if (obj.str("subtype") == "init") obj.str("model")?.let { "model: $it" } else null
            "assistant" -> blocks(obj).mapNotNull { b ->
                when (b.str("type")) {
                    "text" -> b.str("text")?.trim()?.takeIf { it.isNotEmpty() }
                    "tool_use" -> "▸ ${b.str("name").orEmpty()} ${shortInput(b["input"])}".trimEnd()
                    else -> null
                }
            }.joinToString("\n").ifEmpty { null }
            "user" -> blocks(obj).mapNotNull { b ->
                if (b.str("type") == "tool_result" && (b["is_error"] as? JsonPrimitive)?.contentOrNull == "true") {
                    "✗ " + truncate(oneLine(resultText(b["content"])), 160)
                } else null
            }.joinToString("\n").ifEmpty { null }
            "result" -> {
                val turns = obj.str("num_turns") ?: "?"
                val cost = obj.str("total_cost_usd") ?: "?"
                "✓ done ($turns turns, \$$cost)"
            }
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun blocks(obj: JsonObject): List<JsonObject> =
        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun shortInput(input: JsonElement?): String {
        val o = input as? JsonObject ?: return ""
        val v = listOf("file_path", "command", "pattern", "path").firstNotNullOfOrNull { o.str(it) } ?: ""
        return truncate(oneLine(v), 160)
    }

    private fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString(" ") { (it as? JsonObject)?.str("text").orEmpty() }
        else -> ""
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
    private fun truncate(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

    /** Final assistant text from a stream-json transcript (the `result` field), if any. */
    fun finalResultText(output: String): String? =
        output.lineSequence().toList().asReversed().firstNotNullOfOrNull { l ->
            val o = try { json.parseToJsonElement(l.trim()).jsonObject } catch (_: Exception) { null }
            o?.takeIf { it.str("type") == "result" }?.str("result")
        }
}
