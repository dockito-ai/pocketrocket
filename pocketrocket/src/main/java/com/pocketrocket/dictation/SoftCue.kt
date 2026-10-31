package com.pocketrocket.dictation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dictation's own quiet sounds (decision 81), made in code rather than shipped: a soft two-note rise when listening
 * starts and the same falling when it ends. Sine tones with gentle fades at a low level, on the sonification usage so
 * the phone's system-sounds volume and silent mode apply. Anything that goes wrong just means no sound.
 */
object SoftCue {
    enum class Kind { Start, End }

    private const val RATE = 22_050

    fun play(kind: Kind) {
        runCatching {
            val samples = when (kind) {
                Kind.Start -> tones(587.33, 880.0)
                Kind.End -> tones(880.0, 587.33)
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, (samples.size * 1000L / RATE) + 200)
        }
    }

    /** How long a cue lasts, so the mic can open after it rather than hear it. */
    const val LENGTH_MS = 170L

    /** Two short notes, each faded in and out so they don't click. */
    private fun tones(first: Double, second: Double): ShortArray {
        val note = (RATE * 0.075).toInt()
        val out = ShortArray(note * 2)
        listOf(first, second).forEachIndexed { n, freq ->
            for (i in 0 until note) {
                val fade = minOf(1.0, i / (note * 0.25), (note - i) / (note * 0.4))
                val value = sin(2 * PI * freq * i / RATE) * fade * 0.18
                out[n * note + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
