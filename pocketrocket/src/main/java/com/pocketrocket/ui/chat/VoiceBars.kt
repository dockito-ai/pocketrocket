package com.pocketrocket.ui.chat

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * How the listening panel's bars move, stepped once a frame. The recognizer reports loudness in uneven bursts, so it
 * goes through an envelope first: quick to rise with a syllable, slower to fall back, like a VU meter. Each bar then
 * rides that envelope with its own slow sway (two sines at different speeds and phases), so the row flows like music
 * bars instead of jumping to new random heights on every reading. Pure, so it's tested without a screen.
 */
class VoiceBars(private val count: Int) {
    /** Each bar's height, 0 (resting) to 1 (full). */
    val heights = FloatArray(count) { IDLE }

    /** The smoothed loudness, 0 to 1; the glow behind the bars swells with it. */
    var envelope = 0f
        private set

    private var time = 0f
    // Golden-angle phases spread the bars' sways so no two neighbours move together.
    private val phase = FloatArray(count) { it * 2.39996f }

    /** Moves everything on by [seconds] towards the latest [level] (0 to 1). */
    fun step(seconds: Float, level: Float) {
        val dt = seconds.coerceIn(0f, MAX_STEP)
        time += dt
        val target = level.coerceIn(0f, 1f)
        envelope += (target - envelope) * follow(if (target > envelope) ATTACK else RELEASE, dt)
        val mid = (count - 1) / 2f
        for (i in 0 until count) {
            val centre = if (mid == 0f) 1f else 1f - abs(i - mid) / mid
            val sway = 0.5f + 0.25f * sin(time * SLOW + phase[i]) + 0.25f * sin(time * FAST + phase[i] * 1.7f)
            val voice = envelope * (0.35f + 0.65f * centre) * (0.5f + 0.5f * sway)
            val goal = IDLE * (0.7f + 0.3f * sway) + (1f - IDLE) * voice
            heights[i] += (goal - heights[i]) * follow(BAR, dt)
        }
    }

    // Exponential approach that's the same at any frame rate: the share of the gap closed in dt.
    private fun follow(perSecond: Float, dt: Float) = 1f - exp(-perSecond * dt)

    companion object {
        const val IDLE = 0.09f
        private const val ATTACK = 28f
        private const val RELEASE = 7f
        private const val BAR = 20f
        private const val SLOW = 5.3f
        private const val FAST = 8.9f
        /** A stalled frame (app paused) moves at most this far, so the bars don't leap when it comes back. */
        private const val MAX_STEP = 0.1f
    }
}
