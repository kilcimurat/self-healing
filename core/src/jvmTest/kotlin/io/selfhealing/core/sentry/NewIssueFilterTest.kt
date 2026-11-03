package io.selfhealing.core.sentry

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewIssueFilterTest {
    private val baseline = mapOf("old" to Instant.parse("2026-10-22T10:00:00Z"), "noTime" to null)

    @Test
    fun crashBeforeStartThatArrivesAfterwardsCounts() {
        // Android uploads the report on the next launch: its event time can predate Start, but the issue was not in Sentry at Start.
        assertTrue(seenSinceStart(baseline, "155627057", Instant.parse("2026-10-23T06:39:20Z")))
    }

    @Test
    fun oldIssueOnlyCountsWhenSeenAgain() {
        assertFalse(seenSinceStart(baseline, "old", Instant.parse("2026-10-22T10:00:00Z")))
        assertTrue(seenSinceStart(baseline, "old", Instant.parse("2026-10-23T06:40:00Z")))
        assertFalse(seenSinceStart(baseline, "noTime", Instant.parse("2026-10-23T06:40:00Z")))
    }
}
