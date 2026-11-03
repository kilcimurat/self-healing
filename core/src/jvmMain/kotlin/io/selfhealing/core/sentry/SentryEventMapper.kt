package io.selfhealing.core.sentry

import io.selfhealing.core.config.CrashFilterConfig
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SentryEventMapper {
    private const val MAX_FRAMES = 60
    private const val MAX_BREADCRUMBS = 30

    /** Returns why the event must be ignored, or null when it passes [filters]. */
    fun filterReason(event: JsonObject, issue: JsonObject?, filters: CrashFilterConfig): String? {
        val level = levelOf(event, issue)
        if (filters.levels.isNotEmpty() && (level == null || filters.levels.none { it.equals(level, ignoreCase = true) })) {
            return "level '${level ?: "none"}' not in ${filters.levels}"
        }
        if (filters.onlyUnhandled) {
            val handled = exceptions(event).mapNotNull { it.obj("mechanism")?.get("handled")?.bool() }
            val unhandled = when {
                handled.isNotEmpty() -> handled.any { !it }
                else -> level.equals("fatal", ignoreCase = true)
            }
            if (!unhandled) return "event is handled (not a crash)"
        }
        return null
    }

    fun map(
        event: JsonObject,
        projectSlug: String,
        issue: JsonObject?,
        platformOverride: Platform?,
        organization: String,
        baseUrl: String,
    ): CrashReport {
        val id = issue?.get("id").str() ?: event["groupID"].str() ?: event["group_id"].str() ?: event["issue_id"].str()
            ?: event["eventID"].str() ?: event["event_id"].str() ?: event["id"].str() ?: "unknown"
        val permalink = issue?.get("permalink").str()
            ?: "${baseUrl.trimEnd('/')}/organizations/$organization/issues/$id/"
        return CrashReport(
            id = id,
            eventId = event["eventID"].str() ?: event["event_id"].str(),
            projectSlug = projectSlug,
            title = event["title"].str() ?: issue?.get("title").str() ?: event["message"].str() ?: "Unknown crash",
            culprit = event["culprit"].str() ?: issue?.get("culprit").str(),
            level = levelOf(event, issue),
            platform = platformOverride ?: detectPlatform(event),
            release = release(event),
            permalink = permalink,
            timestamp = event["dateCreated"].str() ?: event["timestamp"].str(),
            stacktrace = stacktrace(event),
            context = context(event),
        )
    }

    fun detectPlatform(event: JsonObject): Platform {
        val osName = event.obj("contexts")?.obj("os")?.get("name").str()?.lowercase().orEmpty()
        val sdkName = event.obj("sdk")?.get("name").str()?.lowercase().orEmpty()
        val platform = event["platform"].str()?.lowercase().orEmpty()
        return when {
            osName == "ios" || osName == "ipados" || platform == "cocoa" || "cocoa" in sdkName || "swift" in sdkName -> Platform.IOS
            osName == "android" || "android" in sdkName || platform == "java" -> Platform.ANDROID
            else -> Platform.UNKNOWN
        }
    }

    fun stacktrace(event: JsonObject): String {
        val sb = StringBuilder()
        val exceptions = exceptions(event)
        for (ex in exceptions) {
            val type = ex["type"].str() ?: "Exception"
            val value = ex["value"].str()
            sb.append(type)
            if (!value.isNullOrBlank()) sb.append(": ").append(value)
            val mech = ex.obj("mechanism")
            if (mech != null) {
                val parts = listOfNotNull(mech["type"].str(), mech["handled"].bool()?.let { if (it) "handled" else "unhandled" })
                if (parts.isNotEmpty()) sb.append(" (").append(parts.joinToString(", ")).append(")")
            }
            sb.append('\n')
            appendFrames(sb, ex.obj("stacktrace") ?: ex.obj("rawStacktrace"))
        }
        if (exceptions.isEmpty()) {
            val threads = threads(event)
            val thread = threads.firstOrNull { it["crashed"].bool() == true } ?: threads.firstOrNull { it["current"].bool() == true }
            if (thread != null) {
                sb.append("Crashed thread: ").append(thread["name"].str() ?: thread["id"].str() ?: "?").append('\n')
                appendFrames(sb, thread.obj("stacktrace") ?: thread.obj("rawStacktrace"))
            }
        }
        return sb.toString().trimEnd().ifEmpty { "(no stack trace available)" }
    }

    fun context(event: JsonObject): String {
        val sb = StringBuilder()
        val crumbs = entry(event, "breadcrumbs")?.obj("data")?.arr("values").orEmpty().mapNotNull { it.obj() }
        if (crumbs.isNotEmpty()) {
            sb.append("Breadcrumbs (oldest first):\n")
            for (c in crumbs.takeLast(MAX_BREADCRUMBS)) {
                val msg = c["message"].str() ?: c.obj("data")?.let { d -> d["url"].str() ?: d["to"].str() }
                val line = listOfNotNull(c["timestamp"].str(), c["category"].str()?.let { "$it:" } ?: c["type"].str()?.let { "$it:" }, msg)
                sb.append("  ").append(line.joinToString(" ")).append('\n')
            }
        }
        val tags = tags(event)
        if (tags.isNotEmpty()) {
            sb.append("Tags:\n")
            tags.forEach { (k, v) -> sb.append("  ").append(k).append('=').append(v).append('\n') }
        }
        val contexts = event.obj("contexts")
        for (name in listOf("device", "os", "app")) {
            val c = contexts?.obj(name) ?: continue
            val items = c.entries.filter { it.key != "type" }.mapNotNull { (k, v) -> v.str()?.let { "$k=$it" } }.take(12)
            if (items.isNotEmpty()) sb.append(name.replaceFirstChar { it.uppercase() }).append(": ").append(items.joinToString(", ")).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun appendFrames(sb: StringBuilder, stacktrace: JsonObject?) {
        val frames = stacktrace?.arr("frames").orEmpty().mapNotNull { it.obj() }.reversed()
        for (f in frames.take(MAX_FRAMES)) {
            val name = listOfNotNull(f["module"].str(), f["function"].str()).joinToString(".").ifEmpty { "<unknown>" }
            val file = f["filename"].str() ?: f["absPath"].str() ?: f["package"].str()
            val line = f["lineNo"].str()
            val loc = when {
                file != null && line != null -> "$file:$line"
                file != null -> file
                else -> null
            }
            sb.append("  at ").append(name)
            if (loc != null) sb.append(" (").append(loc).append(')')
            if (f["inApp"].bool() == true) sb.append(" [in-app]")
            sb.append('\n')
        }
        if (frames.size > MAX_FRAMES) sb.append("  ... ${frames.size - MAX_FRAMES} more frames\n")
    }

    private fun entry(event: JsonObject, type: String): JsonObject? =
        event.arr("entries").orEmpty().mapNotNull { it.obj() }.firstOrNull { it["type"].str() == type }

    private fun exceptions(event: JsonObject): List<JsonObject> {
        val fromEntries = entry(event, "exception")?.obj("data")?.arr("values")
        val values = fromEntries ?: event.obj("exception")?.arr("values")
        return values.orEmpty().mapNotNull { it.obj() }
    }

    private fun threads(event: JsonObject): List<JsonObject> {
        val values = entry(event, "threads")?.obj("data")?.arr("values") ?: event.obj("threads")?.arr("values")
        return values.orEmpty().mapNotNull { it.obj() }
    }

    private fun tags(event: JsonObject): List<Pair<String, String>> =
        event.arr("tags").orEmpty().mapNotNull { t ->
            when (t) {
                is JsonObject -> t["key"].str()?.let { k -> k to (t["value"].str() ?: "") }
                is JsonArray -> t.getOrNull(0).str()?.let { k -> k to (t.getOrNull(1).str() ?: "") }
                else -> null
            }
        }

    private fun levelOf(event: JsonObject, issue: JsonObject?): String? =
        event["level"].str() ?: tags(event).firstOrNull { it.first == "level" }?.second ?: issue?.get("level").str()

    private fun release(event: JsonObject): String? {
        val r = event["release"]
        return (if (r is JsonObject) r["version"].str() ?: r["shortVersion"].str() else r.str())
            ?: tags(event).firstOrNull { it.first == "release" }?.second
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull()
