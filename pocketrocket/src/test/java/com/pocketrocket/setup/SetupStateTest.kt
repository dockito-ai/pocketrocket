package com.pocketrocket.setup

import com.pocketrocket.ui.setup.elapsedText
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStateTest {

    @Test
    fun stepStartsTheTimerAndEveryLineMovesTheQuietClock() {
        var s = SetupState(phase = SetupPhase.Running).on(SetupLine.parse("[step 2/8] Installing base packages"), now = 1_000)
        assertEquals(1_000L, s.stepStartedAt)
        s = s.on(SetupLine.parse("[progress 12] Downloading git · 4.3 of 129 MB"), now = 5_000)
        assertEquals(12, s.progress)
        assertEquals("Downloading git · 4.3 of 129 MB", s.detail)
        assertEquals(1_000L, s.stepStartedAt)
        assertEquals(5_000L, s.lastLineAt)
        s = s.on(SetupLine.parse("prepared git (12 of 110)"), now = 9_000)
        assertEquals(9_000L, s.lastLineAt)
        assertEquals("prepared git (12 of 110)", s.log.last())
        s = s.on(SetupLine.parse("[step 3/8] Installing your extra tools"), now = 20_000)
        assertEquals(20_000L, s.stepStartedAt)
        assertEquals(null, s.detail)
    }

    @Test
    fun elapsedReadsLikeAClock() {
        assertEquals("0:00", elapsedText(0))
        assertEquals("0:09", elapsedText(9_999))
        assertEquals("2:14", elapsedText(134_000))
        assertEquals("1:02:03", elapsedText(3_723_000))
    }
}
