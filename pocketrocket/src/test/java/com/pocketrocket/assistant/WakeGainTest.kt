package com.pocketrocket.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGainTest {
    private fun frame(level: Float) = FloatArray(1600) { if (it % 2 == 0) level else -level }

    @Test fun quietSpeechIsRaisedAndLoudIsLeftAbout() {
        val g = WakeGain()
        val quiet = frame(0.002f)
        g.apply(quiet)
        assertEquals(-54f, g.inputDb, 0.5f)
        assertEquals(WakeGain.MAX_GAIN, g.gain, 0.01f)
        assertEquals(0.06f, quiet[0], 0.001f)
        val loud = WakeGain()
        val f = frame(0.1f)
        loud.apply(f)
        assertEquals(1f, loud.gain, 0.01f)
    }

    @Test fun aLoudSoundHoldsTheGainDownThenItComesBack() {
        val g = WakeGain()
        g.apply(frame(0.2f))
        assertEquals(0.5f, g.gain, 0.01f)
        repeat(10) { g.apply(frame(0.001f)) }
        assertTrue(g.gain < WakeGain.MAX_GAIN)
        repeat(100) { g.apply(frame(0.001f)) }
        assertEquals(WakeGain.MAX_GAIN, g.gain, 0.01f)
    }

    @Test fun neverClipsPastFullScale() {
        val g = WakeGain()
        val f = frame(0.9f)
        g.apply(f)
        assertTrue(f.all { it in -1f..1f })
        assertEquals(WakeGain.SILENT_DB, WakeGain().apply { apply(FloatArray(1600)) }.inputDb, 0f)
    }

    @Test fun aScreenThatResumedBeforeTheAskDoesntCount() {
        assertFalse(ScreenLaunch.cameUp(null, 100))
        assertFalse(ScreenLaunch.cameUp(99, 100))
        assertTrue(ScreenLaunch.cameUp(100, 100))
        assertTrue(ScreenLaunch.cameUp(250, 100))
    }
}
