package com.pocketrocket.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeUsageTest {
    // 29 Oct 2026, 15:00 UTC.
    private val now = 1793286000_000L

    @Test fun parsesDateAndTimeInUtc() {
        // 29 Oct 2026, 20:19 UTC.
        assertEquals(1793305140L, ClaudeUsage.parseUtcReset("Oct 29, 8:19pm (UTC)", now))
    }

    @Test fun parsesTimeOnlyAsNextOccurrence() {
        // 9am already passed today, so it is tomorrow: 30 Oct 2026, 09:00 UTC.
        assertEquals(1793350800L, ClaudeUsage.parseUtcReset("9am (UTC)", now))
    }

    @Test fun costLinesCarryAnEpoch() {
        val usage = ClaudeUsage.fromCostText(listOf("Current session: 2% used · resets Oct 29, 8:19pm (UTC)"), now)!!
        assertEquals(1793305140L, usage.windows.single().resetsAt)
        assertNull(usage.windows.single().resetsText)
    }

    @Test fun limitErrorShowsResetInPhoneTime() {
        val ist = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        // 15:00 UTC is 20:30 IST; 20:20 UTC is 01:50 IST the next day.
        assertEquals(
            "You've hit your 5-hour limit · resets Fri 1:50am",
            ClaudeUsage.localizeUtcResets("You've hit your 5-hour limit · resets 8:20pm (UTC)", now, ist),
        )
        // 19:30 UTC is 01:00 IST on the 30th, the same day as the reset.
        assertEquals(
            "You've hit your 5-hour limit · resets 1:50am",
            ClaudeUsage.localizeUtcResets("You've hit your 5-hour limit · resets 8:20pm (UTC)", 1793302200_000L, ist),
        )
        assertEquals("Something else broke", ClaudeUsage.localizeUtcResets("Something else broke", now, ist))
    }

    @Test fun unknownWordingIsKept() {
        assertNull(ClaudeUsage.parseUtcReset("tomorrow", now))
    }
}
