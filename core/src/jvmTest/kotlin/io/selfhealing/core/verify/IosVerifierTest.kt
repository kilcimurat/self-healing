package io.selfhealing.core.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosVerifierTest {
    @Test
    fun reproDetailsWording() {
        assertEquals("iOS: crash reproduced on release v1.1.0", reproDetails(true, "v1.1.0", true))
        assertEquals("iOS: not reproduced on release v1.1.0 (no repro flow)", reproDetails(false, "v1.1.0", false))
        assertEquals("iOS: not reproduced on release v1.1.0", reproDetails(false, "v1.1.0", true))
    }

    @Test
    fun detectsRunningApp() {
        val out = "PID\tStatus\tLabel\n123\t0\tUIKitApplication:com.acme.shop[abcd][rb-legacy]\n"
        assertTrue(launchctlHasApp(out, "com.acme.shop"))
        assertFalse(launchctlHasApp(out, "com.acme.other"))
    }
}
