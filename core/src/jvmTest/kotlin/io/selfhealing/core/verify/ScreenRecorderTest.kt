package io.selfhealing.core.verify

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenRecorderTest {
    private val start = LocalDateTime.of(2026, 10, 20, 19, 33, 58).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun parsesAndroidAndIosTapLines() {
        val log = """
            19:33:58.900 [ INFO] maestro.Maestro.tap-HgV9Ck0: Tapping on element:  UiElement(bounds=Bounds(x=133, y=529))
            19:33:59.125 [ INFO] maestro.Maestro.hierarchyBasedTap-ogj28Uc: Tapping at (540, 1379) using hierarchy based logic for wait
            19:34:01.500 [ INFO] maestro.Maestro.screenshotBasedTap-ogj28Uc: Try tapping at (200, 539) using hierarchy based logic for wait
            19:30:00.000 [ INFO] maestro.Maestro.hierarchyBasedTap-ogj28Uc: Tapping at (1, 1) before the recording
        """.trimIndent()
        assertEquals(listOf(Tap(1.125, 540, 1379), Tap(3.5, 200, 539)), parseMaestroTaps(log, start, ZoneOffset.UTC))
    }

    @Test
    fun tapAfterMidnightLandsOnTheNextDay() {
        val late = LocalDateTime.of(2026, 10, 20, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val log = "00:00:01.000 [ INFO] x: Tapping at (5, 6) using hierarchy based logic"
        assertEquals(listOf(Tap(2.0, 5, 6)), parseMaestroTaps(log, late, ZoneOffset.UTC))
    }
}
