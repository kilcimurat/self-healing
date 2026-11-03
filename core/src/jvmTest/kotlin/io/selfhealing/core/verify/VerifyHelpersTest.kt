package io.selfhealing.core.verify

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyHelpersTest {
    @Test
    fun parsesAdbDevices() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554	device
            R58M123ABC	unauthorized
            192.168.1.5:5555	offline

        """.trimIndent()
        assertEquals(
            listOf(AdbDevice("emulator-5554", "device"), AdbDevice("R58M123ABC", "unauthorized"), AdbDevice("192.168.1.5:5555", "offline")),
            parseAdbDevices(out),
        )
        assertTrue(parseAdbDevices("List of devices attached\n\n").isEmpty())
    }

    private val crashBuffer = """
        10-20 12:00:01.100  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
        10-20 12:00:01.100  1234  1234 E AndroidRuntime: Process: com.acme.shop, PID: 1234
        10-20 12:00:01.100  1234  1234 E AndroidRuntime: java.lang.NullPointerException: boom
        10-20 12:00:01.100  1234  1234 E AndroidRuntime: 	at com.acme.shop.Cart.total(Cart.kt:42)
    """.trimIndent()

    @Test
    fun detectsCrashOfOurPackage() {
        val snippet = assertNotNull(detectAndroidCrash(crashBuffer, "", "com.acme.shop"))
        assertTrue(snippet.startsWith("10-20 12:00:01.100  1234  1234 E AndroidRuntime: FATAL EXCEPTION"))
        assertTrue(snippet.contains("NullPointerException"))
    }

    @Test
    fun ignoresOtherPackagesAndPrefixes() {
        assertNull(detectAndroidCrash(crashBuffer, "", "com.acme.other"))
        assertNull(detectAndroidCrash(crashBuffer, "", "com.acme.sho"))
        assertNull(detectAndroidCrash("", "I ActivityManager: Start proc com.acme.shop", "com.acme.shop"))
    }

    @Test
    fun detectsAnrAndMainBufferCrash() {
        assertNotNull(detectAndroidCrash("", "E ActivityManager: ANR in com.acme.shop (com.acme.shop/.MainActivity)", "com.acme.shop"))
        assertNotNull(detectAndroidCrash("", "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.acme.shop, PID: 9", "com.acme.shop"))
    }

    @Test
    fun snippetCappedAt40Lines() {
        val long = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: a.b, PID: 1\n" + (1..100).joinToString("\n") { "at line$it" }
        assertEquals(40, assertNotNull(detectAndroidCrash(long, "", "a.b")).lines().size)
    }

    @Test
    fun launchFailureDetection() {
        assertTrue(launchFailed("** No activities found; aborting"))
        assertTrue(launchFailed("Error type 3\nError: Activity class {x/y} does not exist."))
        assertFalse(launchFailed("Status: ok\nLaunchState: COLD\nTotalTime: 812"))
    }

    private val simJson = """
        {"devices": {
          "com.apple.CoreSimulator.SimRuntime.iOS-17-5": [
            {"udid":"AAAAAAAA-1111-2222-3333-444444444444","name":"iPhone 16","state":"Shutdown","isAvailable":true}
          ],
          "com.apple.CoreSimulator.SimRuntime.iOS-18-0": [
            {"udid":"BBBBBBBB-1111-2222-3333-444444444444","name":"iPhone 16","state":"Shutdown","isAvailable":true},
            {"udid":"CCCCCCCC-1111-2222-3333-444444444444","name":"iPhone 16 Pro","state":"Booted","isAvailable":true}
          ],
          "com.apple.CoreSimulator.SimRuntime.watchOS-11-0": []
        }}
    """.trimIndent()

    @Test
    fun picksSimulator() {
        assertEquals("BBBBBBBB-1111-2222-3333-444444444444", pickSimulator(simJson, "iPhone 16")?.udid)
        assertEquals("Booted", pickSimulator(simJson, "iPhone 16 Pro")?.state)
        assertNull(pickSimulator(simJson, "iPad"))
        assertNull(pickSimulator("not json", "iPhone 16"))
    }

    @Test
    fun prefersBootedDuplicate() {
        val json = simJson.replace("""{"udid":"AAAAAAAA-1111-2222-3333-444444444444","name":"iPhone 16","state":"Shutdown"""", """{"udid":"AAAAAAAA-1111-2222-3333-444444444444","name":"iPhone 16","state":"Booted"""")
        assertEquals("AAAAAAAA-1111-2222-3333-444444444444", pickSimulator(json, "iPhone 16")?.udid)
    }

    @Test
    fun udidDetection() {
        assertTrue(looksLikeUdid("AAAAAAAA-1111-2222-3333-444444444444"))
        assertFalse(looksLikeUdid("iPhone 16"))
    }

    @Test
    fun findsRecentCrashReports() {
        val dir = createTempDirectory("diag").toFile()
        try {
            val since = System.currentTimeMillis()
            val old = File(dir, "Shop-old.ips").apply { writeText("{}"); setLastModified(since - 60_000) }
            val other = File(dir, "Other-2026.ips").apply { writeText("{\"bundleID\":\"com.other\"}"); setLastModified(since + 1000) }
            assertNull(findCrashReport(listOf(dir), since, "com.acme.shop", "Shop"))
            val byBody = File(dir, "x-2026.ips").apply { writeText("{\"bundleID\":\"com.acme.shop\"}"); setLastModified(since + 2000) }
            assertEquals(byBody, findCrashReport(listOf(dir, File(dir, "missing")), since, "com.acme.shop", "Shop"))
            byBody.delete()
            File(dir, "Shop-2026-10-20.crash").apply { writeText("x"); setLastModified(since + 3000) }
            assertEquals("Shop-2026-10-20.crash", findCrashReport(listOf(dir), since, "com.acme.shop", "Shop")?.name)
            assertTrue(old.exists() && other.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun formatsDurations() {
        assertEquals("2m14s", formatDuration(kotlin.time.Duration.parse("134s")))
        assertEquals("9s", formatDuration(kotlin.time.Duration.parse("9s")))
    }

    @Test
    fun buildFailureSummaryPrefersGradleCause() {
        val out = """
            > Task :app:android:installDebug FAILED
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:android:installDebug'.
            > INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.mkilci.recoup signatures do not match newer version

            * Try:
            > Run with --stacktrace option to get the stack trace.
            BUILD FAILED in 40s
        """.trimIndent()
        val summary = buildFailureSummary(out)
        kotlin.test.assertTrue(summary.startsWith("Execution failed for task"))
        kotlin.test.assertTrue("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in summary)
        kotlin.test.assertFalse("--stacktrace" in summary)
    }

    @Test
    fun dnsFailureIsToldApartFromBlockedPing() {
        assertTrue(dnsBroken("ping: unknown host google.com"))
        assertFalse(dnsBroken("2 packets transmitted, 0 received, 100% packet loss"))
        assertFalse(dnsBroken("64 bytes from 142.250.184.206: icmp_seq=1 ttl=115 time=21 ms"))
    }

    @Test
    fun flakyFlowIsRetriedOnceButACrashIsNot() = kotlinx.coroutines.runBlocking {
        val dir = java.io.File("unused")
        val log = io.selfhealing.core.model.LineSink { _, _ -> }
        val dirs = mutableListOf<java.io.File>()
        val flaky = replayWithRetry(dir, "iOS", log, { false }) { d -> dirs += d; io.selfhealing.core.process.ProcessResult(if (dirs.size == 1) 1 else 0, "") }
        assertEquals(0, flaky?.exitCode)
        assertEquals(listOf(dir, java.io.File(dir, "retry")), dirs)

        var runs = 0
        val crashed = replayWithRetry(dir, "iOS", log, { true }) { runs++; io.selfhealing.core.process.ProcessResult(1, "") }
        assertEquals(1, crashed?.exitCode)
        assertEquals(1, runs)
    }
}
