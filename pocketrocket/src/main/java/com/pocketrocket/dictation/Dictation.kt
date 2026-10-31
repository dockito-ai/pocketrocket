package com.pocketrocket.dictation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech to text through Android's SpeechRecognizer (decision 15): the on-device recognizer where the phone has
 * one, else the default service. Partial results stream to [Listener.onText]; it stops on tap ([stop]) or when the
 * speaker goes quiet. Nothing is recorded or kept here, and nothing is sent anywhere by PocketRocket.
 * Main thread only, like SpeechRecognizer itself.
 */
class Dictation(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** The words so far for this utterance; [final] once recognition ends. */
        fun onText(text: String, final: Boolean)

        /** Input loudness, 0 to 1, for the listening animation. */
        fun onLevel(level: Float)

        /** Listening ended: finished, stopped or failed. [error] is a short user-facing reason, or null. */
        fun onEnd(error: String?)

        /** The recognizer heard the start of speech. */
        fun onSpeech() {}
    }

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var heardSomething = false
    private var stopping = false
    private var firstTry = true
    /** The on-device recognizer failed here once; later starts go straight to the default service. */
    private var onDeviceFailed = false

    /** The recognizer's error code for the last [Listener.onEnd], or null when it ended with a result or a stop. */
    var lastError: Int? = null
        private set

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Starts listening. [cue]: a soft sound first so you know it is listening (the recognizer sounds when it stops but,
     * on many phones, not when it starts); continuous dictation plays its own and passes false.
     */
    fun start(cue: Boolean = true) {
        release()
        if (cue) SoftCue.play(SoftCue.Kind.Start)
        open(preferOnDevice = !onDeviceFailed)
    }

    private fun open(preferOnDevice: Boolean) {
        firstTry = preferOnDevice
        onDevice = preferOnDevice && onDeviceAvailable()
        heardSomething = false
        stopping = false
        lastError = null
        val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        created.setRecognitionListener(callbacks)
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, firstTry)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Stops listening; the words heard so far arrive as a final result. */
    fun stop() {
        stopping = true
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.apply { setRecognitionListener(null); cancel(); destroy() }
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { heardSomething = true; listener.onSpeech() }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        // rmsdB runs roughly -2 (quiet) to 10 (loud).
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { listener.onText(it, final = false) }
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let { listener.onText(it, final = true) }
            release()
            listener.onEnd(null)
        }

        override fun onError(error: Int) {
            release()
            // The on-device (or offline) model may be missing for this language; the default service online can still do it.
            if (firstTry && !stopping && error in fallbackErrors()) {
                if (onDevice) onDeviceFailed = true
                open(preferOnDevice = false)
                return
            }
            lastError = error
            listener.onEnd(message(error))
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun fallbackErrors(): Set<Int> = buildSet {
        add(SpeechRecognizer.ERROR_SERVER)
        add(SpeechRecognizer.ERROR_CLIENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            add(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            add(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        }
    }

    private fun message(error: Int): String? = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            if (heardSomething) "Didn't catch that. Try again." else "Didn't hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection here."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again."
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        ) "Speech recognition doesn't support this language yet." else "Speech recognition failed (code $error)."
    }
}
