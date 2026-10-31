package com.pocketrocket.dictation

/**
 * How long since speech was last heard, and what that means (decision 81): listening as normal, "Still there?" from
 * [askAfterMs], and over at [endAfterMs]. Times are any monotonic clock in milliseconds. Pure.
 */
class SilenceTimer(
    private val askAfterMs: Long = ASK_AFTER_MS,
    private val endAfterMs: Long = END_AFTER_MS,
) {
    enum class State { Listening, StillThere, Over }

    private var lastHeard = 0L
    private var startedAt = 0L

    fun start(now: Long) {
        startedAt = now
        lastHeard = now
    }

    fun heard(now: Long) {
        if (now > lastHeard) lastHeard = now
    }

    fun state(now: Long): State {
        val quiet = now - lastHeard
        return when {
            quiet >= endAfterMs -> State.Over
            quiet >= askAfterMs -> State.StillThere
            else -> State.Listening
        }
    }

    /** "0:07": time since [start], for the panel's clock. */
    fun clock(now: Long): String {
        val seconds = ((now - startedAt).coerceAtLeast(0) / 1000)
        return "${seconds / 60}:" + (seconds % 60).toString().padStart(2, '0')
    }

    companion object {
        const val ASK_AFTER_MS = 10_000L
        const val END_AFTER_MS = 15_000L
    }
}
