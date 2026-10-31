package com.pocketrocket.assistant

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Brings quiet speech up to the level the keyword model expects. The model has no level normalisation of its own: measured
 * with sherpa-onnx 2.0.1 on a computer, the same synthetic "Hey Rocket" heard 18 of 22 times at about -15 dBFS fell to 12 at
 * -35 and 2 at -45, and a phone's VOICE_RECOGNITION source has no gain control by design, so speech across a room sits down
 * there. The gain follows a slowly falling peak of each frame's level, so a word is lifted as a whole and silence isn't
 * lifted past [MAX_GAIN]. Plain arithmetic, so it's tested without Android.
 */
class WakeGain {
    private var envelope = TARGET / MAX_GAIN

    /** The last frame's level as it came from the mic, in dBFS; [SILENT_DB] for digital silence. */
    var inputDb: Float = SILENT_DB
        private set

    /** The gain used on the last frame. */
    var gain: Float = 1f
        private set

    /** Scales [frame] in place, -1 to 1. */
    fun apply(frame: FloatArray, size: Int = frame.size) {
        var sum = 0.0
        for (i in 0 until size) sum += frame[i] * frame[i]
        val rms = if (size > 0) sqrt(sum / size).toFloat() else 0f
        inputDb = dbfs(rms)
        envelope = maxOf(rms, envelope * DECAY)
        gain = (TARGET / envelope).coerceIn(MIN_GAIN, MAX_GAIN)
        for (i in 0 until size) frame[i] = (frame[i] * gain).coerceIn(-1f, 1f)
    }

    companion object {
        /** A loud frame of speech ends up about here: -20 dBFS. */
        const val TARGET = 0.1f
        const val MAX_GAIN = 30f
        const val MIN_GAIN = 0.5f
        /** Per 100 ms frame: the gain comes back up over a few seconds after a loud sound. */
        const val DECAY = 0.95f
        const val SILENT_DB = -120f

        fun dbfs(rms: Float): Float = if (rms <= 0f) SILENT_DB else (20 * log10(rms.toDouble())).toFloat().coerceAtLeast(SILENT_DB)
    }
}
