package io.selfhealing.core.sentry

import io.selfhealing.core.config.CrashFilterConfig
import io.selfhealing.core.model.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryEventMapperTest {
    private fun parse(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val androidEvent = parse(
        """
        {
          "eventID": "abc123", "groupID": "42", "title": "java.lang.NullPointerException: boom",
          "culprit": "com.acme.shop.CartFragment.onResume", "platform": "java", "dateCreated": "2026-10-20T10:00:00Z",
          "release": {"version": "com.acme.shop@1.2.3+45"},
          "sdk": {"name": "sentry.java.android"},
          "tags": [{"key": "level", "value": "fatal"}, {"key": "environment", "value": "prod"}],
          "contexts": {"os": {"name": "Android", "version": "14"}, "device": {"model": "Pixel 8", "arch": "arm64"}},
          "entries": [
            {"type": "breadcrumbs", "data": {"values": [
              {"timestamp": "2026-10-20T09:59:58Z", "category": "ui.click", "message": "checkout button"}]}},
            {"type": "exception", "data": {"values": [{
              "type": "NullPointerException", "value": "boom",
              "mechanism": {"type": "UncaughtExceptionHandler", "handled": false},
              "stacktrace": {"frames": [
                {"module": "android.app.ActivityThread", "function": "main", "filename": "ActivityThread.java", "lineNo": 8000, "inApp": false},
                {"module": "com.acme.shop.CartFragment", "function": "onResume", "filename": "CartFragment.kt", "lineNo": 57, "inApp": true},
                {"module": "com.acme.shop.Cart", "function": "total", "filename": "Cart.kt", "lineNo": 12, "inApp": true}
              ]}}]}}
          ]
        }
        """.trimIndent(),
    )

    private val iosEvent = parse(
        """
        {
          "eventID": "ios1", "groupID": "77", "title": "EXC_BAD_ACCESS", "platform": "cocoa",
          "release": "com.acme.shop@2.0", "sdk": {"name": "sentry.cocoa"},
          "contexts": {"os": {"name": "iOS", "version": "17.2"}},
          "tags": [["level", "fatal"]],
          "entries": [{"type": "exception", "data": {"values": [{
            "type": "EXC_BAD_ACCESS", "value": "Attempted to dereference null pointer",
            "mechanism": {"type": "mach", "handled": false},
            "stacktrace": {"frames": [
              {"function": "start", "filename": "dyld", "inApp": false},
              {"function": "CartViewController.viewDidLoad()", "module": "Shop", "filename": "CartViewController.swift", "lineNo": 31, "inApp": true}
            ]}}]}}]
        }
        """.trimIndent(),
    )

    @Test
    fun mapsAndroidEvent() {
        val r = SentryEventMapper.map(androidEvent, "shop-android", null, null, "acme", "https://sentry.io/")
        assertEquals("42", r.id)
        assertEquals("abc123", r.eventId)
        assertEquals(Platform.ANDROID, r.platform)
        assertEquals("fatal", r.level)
        assertEquals("com.acme.shop@1.2.3+45", r.release)
        assertEquals("https://sentry.io/organizations/acme/issues/42/", r.permalink)
        assertContains(r.stacktrace, "NullPointerException: boom (UncaughtExceptionHandler, unhandled)")
        assertContains(r.context, "ui.click: checkout button")
        assertContains(r.context, "environment=prod")
        assertContains(r.context, "model=Pixel 8")
    }

    @Test
    fun framesAreInnermostFirst() {
        val st = SentryEventMapper.stacktrace(androidEvent)
        val lines = st.lines()
        assertEquals("  at com.acme.shop.Cart.total (Cart.kt:12) [in-app]", lines[1])
        assertEquals("  at com.acme.shop.CartFragment.onResume (CartFragment.kt:57) [in-app]", lines[2])
        assertEquals("  at android.app.ActivityThread.main (ActivityThread.java:8000)", lines[3])
    }

    @Test
    fun mapsIosEvent() {
        val r = SentryEventMapper.map(iosEvent, "shop-ios", null, null, "acme", "https://sentry.io")
        assertEquals(Platform.IOS, r.platform)
        assertEquals("com.acme.shop@2.0", r.release)
        assertEquals("fatal", r.level)
        assertTrue(r.stacktrace.lines()[1].contains("CartViewController.swift:31"))
    }

    @Test
    fun platformOverrideWins() {
        val r = SentryEventMapper.map(iosEvent, "p", null, Platform.ANDROID, "o", "https://sentry.io")
        assertEquals(Platform.ANDROID, r.platform)
    }

    @Test
    fun unhandledFilter() {
        val filters = CrashFilterConfig()
        assertNull(SentryEventMapper.filterReason(androidEvent, null, filters))
        val handled = parse(androidEvent.toString().replace("\"handled\":false", "\"handled\":true"))
        assertNotNull(SentryEventMapper.filterReason(handled, null, filters))
        assertNull(SentryEventMapper.filterReason(handled, null, filters.copy(onlyUnhandled = false)))
    }

    @Test
    fun levelFilterAndMissingMechanism() {
        val noMech = parse("""{"level":"error","entries":[]}""")
        assertNotNull(SentryEventMapper.filterReason(noMech, null, CrashFilterConfig()))
        val fatal = parse("""{"level":"fatal","entries":[]}""")
        assertNull(SentryEventMapper.filterReason(fatal, null, CrashFilterConfig()))
        val warn = parse("""{"level":"warning"}""")
        assertNotNull(SentryEventMapper.filterReason(warn, null, CrashFilterConfig(onlyUnhandled = false)))
        assertNull(SentryEventMapper.filterReason(warn, null, CrashFilterConfig(levels = emptyList(), onlyUnhandled = false)))
    }
}
