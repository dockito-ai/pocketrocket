package com.pocketrocket.dictation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.random.Random

/**
 * Debug builds only, where the phone (an emulator) has no speech recognizer: feeds a made-up sentence through the same
 * [Transcript] and [SilenceTimer] as [ContinuousDictation], so the listening panel can be checked end to end. A pause
 * mid-way stands in for a recognizer restart; after the words it goes quiet, so "Still there?" and the 15 s end show.
 */
class FakeDictation(private val listener: ContinuousDictation.Listener) : DictationSource {
    private val main = Handler(Looper.getMainLooper())
    private val words = "make the cards in settings use sixteen dp of spacing. and check it looks right in dark mode too".split(" ")
    private val transcript = Transcript()
    private val silence = SilenceTimer()
    private var index = 0
    private var running = false

    override val listening: Boolean get() = running

    override fun start() {
        if (running) return
        running = true
        index = 0
        transcript.clear()
        silence.start(now())
        SoftCue.play(SoftCue.Kind.Start)
        main.post(step)
    }

    override fun finish() = end(keep = true)

    override fun cancel() = end(keep = false)

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val speaking = index < words.size
            if (speaking) {
                transcript.guess((transcript.partial + " " + words[index++]).trim())
                // A run settles every few words, as a restart after a pause would.
                if (index % 6 == 0 || index == words.size) transcript.settle(transcript.partial)
                silence.heard(now())
                listener.onTranscript(transcript.settled, transcript.partial)
            }
            listener.onLevel(if (speaking) 0.35f + Random.nextFloat() * 0.65f else Random.nextFloat() * 0.06f)
            val state = silence.state(now())
            listener.onTick(silence.clock(now()), state)
            if (state == SilenceTimer.State.Over) return finish()
            main.postDelayed(this, if (index % 6 == 0 && speaking) 900 else 300)
        }
    }

    private fun end(keep: Boolean) {
        if (!running) return
        running = false
        main.removeCallbacks(step)
        SoftCue.play(SoftCue.Kind.End)
        val text = transcript.text.takeIf { keep && it.isNotBlank() }
        transcript.clear()
        listener.onEnd(text, null)
    }

    private fun now() = SystemClock.elapsedRealtime()
}

/** What the listening panel drives: real continuous dictation, or the debug fake. */
interface DictationSource {
    val listening: Boolean
    fun start()
    fun finish()
    fun cancel()
}
