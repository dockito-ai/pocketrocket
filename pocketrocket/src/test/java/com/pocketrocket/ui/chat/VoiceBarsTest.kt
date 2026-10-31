package com.pocketrocket.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceBarsTest {
    private fun run(bars: VoiceBars, seconds: Float, level: Float) {
        repeat((seconds * 60).toInt()) { bars.step(1f / 60, level) }
    }

    @Test fun risesQuicklyAndFallsSlowly() {
        val bars = VoiceBars(23)
        run(bars, 0.1f, 1f)
        val up = bars.envelope
        assertTrue("rises within a tenth of a second: $up", up > 0.9f)
        run(bars, 0.1f, 0f)
        assertTrue("still falling after a tenth: ${bars.envelope}", bars.envelope > 0.4f)
        run(bars, 1f, 0f)
        assertTrue("back down after a second: ${bars.envelope}", bars.envelope < 0.01f)
    }

    @Test fun heightsStayInRangeAndTallerInTheMiddle() {
        val bars = VoiceBars(23)
        var middle = 0f
        var edge = 0f
        repeat(120) {
            bars.step(1f / 60, 0.8f)
            bars.heights.forEach { assertTrue(it in 0f..1f) }
            middle += bars.heights[11]; edge += bars.heights[0]
        }
        assertTrue(middle > edge * 1.5f)
    }

    @Test fun noJumpsBetweenFramesEvenWithBurstyLevels() {
        val bars = VoiceBars(23)
        val levels = floatArrayOf(0f, 1f, 0.1f, 0.9f, 0f, 0.7f)
        repeat(300) { frame ->
            val before = bars.heights.copyOf()
            bars.step(1f / 60, levels[frame % levels.size])
            bars.heights.indices.forEach { assertTrue("frame $frame bar $it", abs(bars.heights[it] - before[it]) < 0.35f) }
        }
    }

    @Test fun restsLowAndStillSwaysInSilence() {
        val bars = VoiceBars(23)
        run(bars, 1f, 0f)
        val first = bars.heights.copyOf()
        bars.heights.forEach { assertTrue(it < 0.12f) }
        run(bars, 0.3f, 0f)
        assertTrue(bars.heights.indices.any { abs(bars.heights[it] - first[it]) > 0.001f })
    }
}
