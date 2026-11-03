package io.selfhealing.core.sentry

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.engine.CrashSource
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SentryCrashSource(private val config: AppConfig, private val log: LineSink) : CrashSource {
    private val sentry = config.sentry
    private val base = sentry.baseUrl.trimEnd('/')
    private val org = sentry.organization
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HttpClient? = null
    private var server: EmbeddedServer<*, *>? = null

    private val secrets = ConcurrentHashMap.newKeySet<String>()
    private val projectIds = ConcurrentHashMap<String, String>()
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val polled = ConcurrentHashMap.newKeySet<String>()
    /** Issue id → lastSeen at Start, per project; only issues that are new or seen again after that count. */
    private val baseline = ConcurrentHashMap<String, Map<String, Instant?>>()

    private fun say(text: String) = log.line(LogSource.SENTRY, text)

    override suspend fun start(onCrash: suspend (CrashReport) -> Unit) {
        baseline.clear()
        client = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
            defaultRequest { header(HttpHeaders.Authorization, "Bearer ${sentry.authToken}") }
        }
        val webhookOn = sentry.webhook.enabled && config.server.publicUrl.isNotBlank()
        if (sentry.webhook.enabled && !webhookOn) say("Webhook disabled: server.publicUrl is empty")
        if (webhookOn) startWebhook(onCrash)
        if (sentry.polling.enabled) {
            say("Polling ${sentry.projects.size} project(s) every ${sentry.polling.intervalSeconds}s")
            scope.launch {
                while (true) {
                    try {
                        pollOnce(onCrash)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        say("Polling failed: ${e.message}")
                    }
                    delay(sentry.polling.intervalSeconds.coerceAtLeast(5) * 1000L)
                }
            }
        }
        if (!webhookOn && !sentry.polling.enabled) say("Warning: neither webhook nor polling is active; no crashes will be received")
    }

    override suspend fun stop() {
        runCatching { server?.stop(200, 1000) }
        server = null
        scope.cancel()
        runCatching { client?.close() }
        client = null
    }

    // ---- webhook ----

    private suspend fun startWebhook(onCrash: suspend (CrashReport) -> Unit) {
        val path = sentry.webhook.path.let { if (it.startsWith("/")) it else "/$it" }
        val hookUrl = config.server.publicUrl.trimEnd('/') + path
        for (p in sentry.projects) {
            try {
                ensureHook(p.slug, hookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                say("Could not set up service hook for ${p.slug}: ${e.message}")
            }
        }
        if (secrets.isEmpty()) say("Warning: no service-hook secrets known; incoming webhooks will be rejected unless they carry Sentry-Hook-Signature")
        val srv = embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            routing {
                get("/health") { call.respondText("ok") }
                post(path) {
                    val body = call.receiveText()
                    val hookSig = call.request.headers["X-ServiceHook-Signature"]
                    val integrationSig = call.request.headers["Sentry-Hook-Signature"]
                    when {
                        hookSig != null && SentrySignature.verifyAny(secrets, body, hookSig) -> {}
                        hookSig == null && integrationSig != null -> say("Webhook from internal integration accepted (Sentry-Hook-Signature not verified)")
                        else -> {
                            say("Rejected webhook with missing/invalid signature")
                            call.respondText("invalid signature", status = HttpStatusCode.Unauthorized)
                            return@post
                        }
                    }
                    call.respondText("ok")
                    scope.launch {
                        try {
                            handlePayload(body, onCrash)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            say("Webhook processing failed: ${e.message}")
                        }
                    }
                }
            }
        }
        srv.start(wait = false)
        server = srv
        say("Webhook listening on ${config.server.host}:${config.server.port}$path (public: $hookUrl)")
    }

    private suspend fun ensureHook(slug: String, hookUrl: String) {
        val endpoint = "$base/api/0/projects/$org/$slug/hooks/"
        getJson("$base/api/0/projects/$org/$slug/")?.obj()?.get("id").str()?.let { projectIds[it] = slug }
        val (status, text) = http("GET", endpoint)
        if (status !in 200..299) {
            say("Warning: cannot manage service hooks for $slug (HTTP $status; the org may lack the service-hooks feature). Create a hook manually or rely on polling.")
            return
        }
        val existing = json.parseToJsonElement(text).let { it as? JsonArray }.orEmpty().mapNotNull { it.obj() }
        val match = existing.firstOrNull { it["url"].str()?.trimEnd('/') == hookUrl.trimEnd('/') }
        if (match != null) {
            match["secret"].str()?.let { secrets.add(it) }
            say("Service hook already exists for $slug")
            return
        }
        val payload = buildJsonObject {
            put("url", hookUrl)
            putJsonArray("events") { sentry.webhook.events.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }.toString()
        val (pStatus, pText) = http("POST", endpoint, payload)
        if (pStatus !in 200..299) {
            say("Warning: could not create service hook for $slug (HTTP $pStatus). Relying on polling.")
            return
        }
        json.parseToJsonElement(pText).obj()?.get("secret").str()?.let { secrets.add(it) }
        say("Created service hook for $slug -> $hookUrl")
    }

    private suspend fun handlePayload(body: String, onCrash: suspend (CrashReport) -> Unit) {
        val root = json.parseToJsonElement(body).obj() ?: return
        val data = root.obj("data")
        val event = root.obj("event") ?: data?.obj("event")
        val issue = data?.obj("issue") ?: root.obj("issue")
        val eventId = event?.get("event_id").str() ?: event?.get("eventID").str() ?: event?.get("id").str()
        val issueId = event?.get("groupID").str() ?: event?.get("group_id").str() ?: event?.get("issue_id").str()
            ?: issue?.get("id").str() ?: root["id"].str()?.takeIf { event != null && eventId != null }
        val slug = resolveSlug(root, event, issue)
        if (slug == null) {
            say("Webhook ignored: cannot determine configured project")
            return
        }
        if (eventId == null && issueId == null) {
            say("Webhook ignored: no event or issue id in payload")
            return
        }
        process(slug, issueId, eventId, event, issue, onCrash)
    }

    private fun resolveSlug(root: JsonObject, event: JsonObject?, issue: JsonObject?): String? {
        val configured = sentry.projects.map { it.slug }
        val p = root["project"]
        val candidates = listOfNotNull(
            root["project_slug"].str(),
            p.obj()?.get("slug").str(),
            p.str(),
            event?.get("project").str(),
            issue?.get("project").obj()?.get("slug").str(),
            root.obj("data")?.get("project_slug").str(),
        )
        for (c in candidates) {
            if (c in configured) return c
            projectIds[c]?.let { return it }
        }
        return configured.singleOrNull()
    }

    // ---- polling ----

    private suspend fun pollOnce(onCrash: suspend (CrashReport) -> Unit) {
        for (p in sentry.projects) {
            // Sorted by last seen: a new event on an existing issue (same stack) must surface too.
            val url = "$base/api/0/projects/$org/${p.slug}/issues/?query=is:unresolved&sort=date&statsPeriod=24h&limit=25"
            val issues = (getJson(url) as? JsonArray ?: continue).mapNotNull { it.obj() }
            // Compared with what Sentry already had at Start, not with the crash time: Android sends a crash
            // report on the app's next launch, so an event can arrive after Start with an earlier timestamp.
            val known = baseline[p.slug]
            if (sentry.filters.onlyNewIssues && known == null) {
                baseline[p.slug] = issues.mapNotNull { i -> i["id"].str()?.let { it to lastSeen(i) } }.toMap()
                continue
            }
            for (issue in issues) {
                val id = issue["id"].str() ?: continue
                if (id in claimed || id in polled) continue
                if (known != null && !seenSinceStart(known, id, lastSeen(issue))) continue
                polled.add(id)
                try {
                    process(p.slug, id, null, null, issue, onCrash)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    polled.remove(id)
                    say("Failed to process issue $id: ${e.message}")
                }
            }
        }
    }

    private fun lastSeen(issue: JsonObject): Instant? = issue["lastSeen"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() }

    // ---- shared ----

    private suspend fun process(
        slug: String,
        issueIdHint: String?,
        eventId: String?,
        partialEvent: JsonObject?,
        issueHint: JsonObject?,
        onCrash: suspend (CrashReport) -> Unit,
    ) {
        if (issueIdHint != null && issueIdHint in claimed) return
        val event = (when {
            eventId != null -> getJson("$base/api/0/projects/$org/$slug/events/$eventId/")?.obj()
            issueIdHint != null -> getJson("$base/api/0/organizations/$org/issues/$issueIdHint/events/latest/")?.obj()
            else -> null
        } ?: partialEvent)
        if (event == null) {
            say("Could not load event for issue ${issueIdHint ?: "?"}")
            return
        }
        val issueId = issueIdHint ?: event["groupID"].str() ?: event["group_id"].str()
        if (issueId == null) {
            say("Event ${eventId ?: "?"} has no issue id; skipped")
            return
        }
        if (!claimed.add(issueId)) return
        try {
            val issue = issueHint?.takeIf { it["permalink"] != null }
                ?: getJson("$base/api/0/organizations/$org/issues/$issueId/")?.obj()
                ?: issueHint
            val reason = SentryEventMapper.filterReason(event, issue, sentry.filters)
            if (reason != null) {
                claimed.remove(issueId)
                say("Skipping issue $issueId: $reason")
                return
            }
            val override = sentry.projects.firstOrNull { it.slug == slug }?.platform
            val report = SentryEventMapper.map(event, slug, issue, override, org, base).let {
                if (it.id == issueId) it else it.copy(id = issueId)
            }
            say("New crash ${report.id} [${report.platform}] ${report.title}")
            onCrash(report)
        } catch (e: Throwable) {
            claimed.remove(issueId)
            throw e
        }
    }

    private suspend fun getJson(url: String): JsonElement? {
        val (status, text) = http("GET", url)
        if (status !in 200..299) {
            say("GET ${url.removePrefix(base)} failed: HTTP $status")
            return null
        }
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private suspend fun http(method: String, url: String, body: String? = null): Pair<Int, String> {
        val c = client ?: error("SentryCrashSource not started")
        return try {
            val resp = if (method == "POST") c.post(url) { contentType(ContentType.Application.Json); setBody(body.orEmpty()) } else c.get(url)
            resp.status.value to resp.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            say("$method ${url.removePrefix(base)} error: ${e.message}")
            0 to ""
        }
    }
}

/** True when [id] was not in the Start [baseline], or Sentry has seen it again since. */
internal fun seenSinceStart(baseline: Map<String, Instant?>, id: String, lastSeen: Instant?): Boolean {
    if (id !in baseline) return true
    val before = baseline[id] ?: return false
    return lastSeen != null && lastSeen.isAfter(before)
}
