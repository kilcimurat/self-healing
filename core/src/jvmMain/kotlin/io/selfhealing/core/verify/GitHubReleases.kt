package io.selfhealing.core.verify

import io.selfhealing.core.config.GitHubConfig
import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.publish.parseGitHubRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** `com.acme.app@1.0.5+105` → version `1.0.5`, build `105`. */
internal data class ReleaseVersion(val version: String, val build: String?)

internal fun parseSentryRelease(release: String?): ReleaseVersion? {
    val raw = release?.substringAfter('@')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ReleaseVersion(raw.substringBefore('+'), raw.substringAfter('+', "").ifEmpty { null })
}

internal fun releaseTag(template: String, v: ReleaseVersion): String =
    template.replace("{version}", v.version).replace("{build}", v.build.orEmpty())

/** Downloads assets of GitHub releases. */
class GitHubReleases(private val github: GitHubConfig) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    /** Returns the first asset of release [tag] whose name matches [pattern], or null (reason logged). */
    suspend fun downloadAsset(repoUrl: String, tag: String, pattern: Regex, into: File, log: LineSink): File? = withContext(Dispatchers.IO) {
        val ref = parseGitHubRepo(repoUrl)
        val api = "${github.apiUrl.trimEnd('/')}/repos/${ref.owner}/${ref.repo}"
        val release = get("$api/releases/tags/$tag", "application/vnd.github+json")
        if (release.statusCode() != 200) {
            log.line(LogSource.BUILD, "Release: no GitHub release for tag $tag (HTTP ${release.statusCode()})")
            return@withContext null
        }
        val asset = json.parseToJsonElement(String(release.body())).jsonObject["assets"]?.jsonArray
            ?.map { it.jsonObject }?.firstOrNull { pattern.matches(it.str("name")) }
            ?: return@withContext null.also { log.line(LogSource.BUILD, "Release: $tag has no asset matching ${pattern.pattern}") }
        val name = asset.str("name")
        File(into, "release-$name").takeIf { it.isFile && it.length() > 0 }?.let { return@withContext it }
        log.line(LogSource.BUILD, "Release: downloading $name from $tag")
        // The asset endpoint redirects to storage; the redirect must not carry the token.
        var resp = get("$api/releases/assets/${asset.str("id")}", "application/octet-stream")
        if (resp.statusCode() in 300..399) {
            val location = resp.headers().firstValue("location").orElseThrow()
            resp = http.send(HttpRequest.newBuilder(URI(location)).timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofByteArray())
        }
        check(resp.statusCode() == 200) { "Downloading $name failed: HTTP ${resp.statusCode()}" }
        File(into.apply { mkdirs() }, "release-$name").apply { writeBytes(resp.body()) }
    }

    private fun get(url: String, accept: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer ${github.token}")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun JsonObject.str(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()
}
