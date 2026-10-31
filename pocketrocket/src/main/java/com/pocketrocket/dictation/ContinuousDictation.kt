package com.pocketrocket.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer

/**
 * Dictation that pauses don't end (decision 81). Android's recognizer gives up after a second or so of quiet; each
 * time it ends on its own (a result, no match, a speech timeout) this starts it again at once and keeps the words in
 * one [Transcript]. The recognizer's own beeps are muted for the whole listening ([RecognizerMute]); one [SoftCue] of
 * our own plays before the mic opens and one after it closes.
 *
 * It ends on [finish] (keep the words), [cancel] (throw them away), after [SilenceTimer.END_AFTER_MS] with no speech
 * (keeps them), or on an error a restart can't fix. Main thread only.
 */
class ContinuousDictation(context: Context, private val listener: Listener) : DictationSource {

    interface Listener {
        /** The words so far: [settled] in text colour, [partial] still being guessed. */
        fun onTranscript(settled: String, partial: String)

        fun onLevel(level: Float)

        /** Every quarter second: the clock and whether to ask "Still there?". */
        fun onTick(clock: String, state: SilenceTimer.State)

        /**
         * Listening is over. [text]: the words to keep (null when discarded or nothing was heard). [error]: why it
         * stopped, when it was a problem rather than ✓, ✕ or the silence.
         */
        fun onEnd(text: String?, error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val mute = RecognizerMute(context)
    private val transcript = Transcript()
    private val silence = SilenceTimer()
    private var running = false
    private var finishing = false
    /** Runs that ended almost as soon as they opened with nothing heard, to stop a failing recognizer from spinning. */
    private var fastFails = 0
    private var openedAt = 0L

    private val engine = Dictation(context, object : Dictation.Listener {
        override fun onText(text: String, final: Boolean) {
            if (!running) return
            if (final) {
                transcript.settle(text)
                silence.heard(now())
            } else if (transcript.guess(text)) {
                silence.heard(now())
            }
            fastFails = 0
            publish()
        }

        override fun onLevel(level: Float) { if (running) listener.onLevel(level) }

        override fun onSpeech() { if (running) silence.heard(now()) }

        override fun onEnd(error: String?) = runEnded(engineError())
    })

    val isAvailable: Boolean get() = engine.isAvailable

    override val listening: Boolean get() = running

    override fun start() {
        if (running) return
        transcript.clear()
        running = true
        finishing = false
        fastFails = 0
        silence.start(now())
        publish()
        SoftCue.play(SoftCue.Kind.Start)
        // The mic opens after the cue, so the cue isn't heard as speech; the recognizer's own sounds are muted by then.
        main.postDelayed(openMic, SoftCue.LENGTH_MS)
        main.post(tick)
    }

    /** ✓: stop and keep the words. Waits briefly for the recognizer's last settled words. */
    override fun finish() {
        if (!running || finishing) return
        finishing = true
        main.removeCallbacks(openMic)
        engine.stop()
        main.postDelayed(finishNow, FINISH_WAIT_MS)
    }

    /** ✕, back, or the screen going away: stop and throw the words away. */
    override fun cancel() = end(keep = false, error = null)

    private val openMic = Runnable {
        if (!running) return@Runnable
        mute.mute()
        openedAt = now()
        engine.start(cue = false)
    }

    private val finishNow = Runnable { end(keep = true, error = null) }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val state = silence.state(now())
            listener.onTick(silence.clock(now()), state)
            if (state == SilenceTimer.State.Over && !finishing) {
                finish()
                return
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun engineError(): Int? = engine.lastError

    /** One recognizer run ended. Start the next one unless we're done or it can't go on. */
    private fun runEnded(error: Int?) {
        if (!running) return
        transcript.endRun()
        publish()
        if (finishing) {
            main.removeCallbacks(finishNow)
            end(keep = true, error = null)
            return
        }
        if (error != null && error !in RESTARTABLE) {
            end(keep = true, error = engineMessage(error))
            return
        }
        fastFails = if (now() - openedAt < FAST_FAIL_MS) fastFails + 1 else 0
        if (fastFails > MAX_FAST_FAILS) {
            end(keep = true, error = "Speech recognition keeps stopping. Try again.")
            return
        }
        val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || fastFails > 0) BUSY_RETRY_MS else 0L
        main.postDelayed(openMic, delay)
    }

    private fun end(keep: Boolean, error: String?) {
        if (!running) return
        running = false
        finishing = false
        main.removeCallbacks(openMic)
        main.removeCallbacks(finishNow)
        main.removeCallbacks(tick)
        engine.release()
        val wasMuted = mute.active
        mute.restore()
        // The end cue after unmuting; a little later when muted, so the recognizer's stop sound has passed meanwhile.
        main.postDelayed({ SoftCue.play(SoftCue.Kind.End) }, if (wasMuted) 120L else 0L)
        val text = transcript.text.takeIf { keep && it.isNotBlank() }
        transcript.clear()
        listener.onEnd(text, error)
    }

    /** Short reason for an error that ends dictation; same wording as one-shot dictation. */
    private fun engineMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection here."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available right now."
        else -> "Speech recognition stopped (code $error)."
    }

    private fun publish() = listener.onTranscript(transcript.settled, transcript.partial)

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TICK_MS = 250L
        const val FINISH_WAIT_MS = 900L
        const val BUSY_RETRY_MS = 400L
        const val FAST_FAIL_MS = 800L
        const val MAX_FAST_FAILS = 5
        /** Ends a restart puts right: no words this time, a quiet stretch, or a recognizer still letting go. */
        val RESTARTABLE = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
    }
}
