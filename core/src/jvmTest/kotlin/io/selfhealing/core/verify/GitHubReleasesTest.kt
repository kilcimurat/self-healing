package io.selfhealing.core.verify

import com.sun.net.httpserver.HttpServer
import io.selfhealing.core.config.GitHubConfig
import io.selfhealing.core.model.LineSink
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubReleasesTest {
    @Test
    fun parsesSentryReleaseAndTag() {
        val v = parseSentryRelease("com.mkilci.recoup@1.0.5+105")!!
        assertEquals(ReleaseVersion("1.0.5", "105"), v)
        assertEquals("v1.0.5", releaseTag("v{version}", v))
        assertEquals("release-1.0.5-105", releaseTag("release-{version}-{build}", v))
        assertEquals(ReleaseVersion("2.1", null), parseSentryRelease("2.1"))
        assertNull(parseSentryRelease(null))
    }

    @Test
    fun downloadsAssetWithoutLeakingTokenOnRedirect() {
        val authSeen = Collections.synchronizedMap(mutableMapOf<String, String?>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            authSeen[ex.requestURI.path] = ex.requestHeaders.getFirst("Authorization")
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        val port = server.address.port
        server.createContext("/repos/kilcimurat/Recoup/releases/tags/v1.0.5") {
            reply(it, 200, """{"assets":[{"id":7,"name":"notes.txt"},{"id":8,"name":"RecoupRelease_1.0.5.apk"}]}""".toByteArray())
        }
        server.createContext("/repos/kilcimurat/Recoup/releases/assets/8") {
            reply(it, 302, ByteArray(0), mapOf("Location" to "http://127.0.0.1:$port/storage/apk"))
        }
        server.createContext("/storage/apk") { reply(it, 200, byteArrayOf(1, 2, 3)) }
        server.start()
        try {
            val dir = Files.createTempDirectory("apk").toFile()
            val log = LineSink { _, _ -> }
            val releases = GitHubReleases(GitHubConfig(apiUrl = "http://127.0.0.1:$port", token = "ghp_secret"))
            val apk = runBlocking {
                releases.downloadAsset("https://github.com/kilcimurat/Recoup.git", "v1.0.5", Regex(".*\\.apk"), dir, log)
            }!!
            assertEquals("release-RecoupRelease_1.0.5.apk", apk.name)
            assertEquals(listOf<Byte>(1, 2, 3), apk.readBytes().toList())
            assertEquals("Bearer ghp_secret", authSeen["/repos/kilcimurat/Recoup/releases/assets/8"])
            assertNull(authSeen["/storage/apk"])
            assertNull(runBlocking { releases.downloadAsset("https://github.com/kilcimurat/Recoup.git", "v9.9.9", Regex(".*\\.apk"), dir, log) })
        } finally {
            server.stop(0)
        }
    }
}
