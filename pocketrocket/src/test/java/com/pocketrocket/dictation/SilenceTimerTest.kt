package com.pocketrocket.dictation

import com.pocketrocket.dictation.SilenceTimer.State
import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceTimerTest {
    @Test fun listeningUntilTenSecondsOfQuiet() {
        val t = SilenceTimer()
        t.start(1_000)
        assertEquals(State.Listening, t.state(1_000))
        assertEquals(State.Listening, t.state(10_999))
        assertEquals(State.StillThere, t.state(11_000))
        assertEquals(State.StillThere, t.state(15_999))
        assertEquals(State.Over, t.state(16_000))
    }

    @Test fun speechResetsTheQuiet() {
        val t = SilenceTimer()
        t.start(0)
        t.heard(12_000)
        assertEquals(State.Listening, t.state(13_000))
        assertEquals(State.StillThere, t.state(22_000))
        assertEquals(State.Over, t.state(27_000))
    }

    @Test fun anOlderHearingDoesNotMoveItBack() {
        val t = SilenceTimer()
        t.start(0)
        t.heard(9_000)
        t.heard(2_000)
        assertEquals(State.Listening, t.state(18_000))
    }

    @Test fun clockCountsFromStart() {
        val t = SilenceTimer()
        t.start(5_000)
        assertEquals("0:00", t.clock(5_400))
        assertEquals("0:07", t.clock(12_000))
        assertEquals("1:05", t.clock(70_000))
        t.heard(60_000)
        assertEquals("1:05", t.clock(70_000))
    }
}
