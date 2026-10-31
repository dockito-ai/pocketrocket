package com.pocketrocket.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pocketrocket.dictation.Dictation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [Recogniser] over [Dictation], which already prefers the phone's on-device recogniser. Some recognisers never answer
 * after being told to stop, so a stop gives up after a few seconds and the loop uses the words so far.
 */
class DictationRecogniser(context: Context) : Recogniser {
    private val main = Handler(Looper.getMainLooper())
    private var listener: Recogniser.Listener? = null
    private val giveUp = Runnable { end(null) }

    private val dictation = Dictation(context, object : Dictation.Listener {
        override fun onText(text: String, final: Boolean) { listener?.onHeard(text, final) }
        override fun onLevel(level: Float) { listener?.onLevel(level) }
        override fun onEnd(error: String?) = end(error)
    })

    override val isAvailable: Boolean get() = dictation.isAvailable

    override fun start(listener: Recogniser.Listener) {
        this.listener = listener
        dictation.start()
    }

    override fun stop() {
        dictation.stop()
        main.postDelayed(giveUp, STOP_TIMEOUT_MS)
    }

    override fun cancel() {
        main.removeCallbacks(giveUp)
        listener = null
        dictation.release()
    }

    private fun end(error: String?) {
        main.removeCallbacks(giveUp)
        val ending = listener
        listener = null
        dictation.release()
        ending?.onEnd(error)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 4_000L
    }
}

/** Where speech would come out right now. */
class AudioRoute(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * True when speech goes to something on you. Bluetooth can't tell earbuds from a car or a speaker, so all Bluetooth
     * audio counts as headphones.
     */
    fun onHeadphones(): Boolean {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audio.getAudioDevicesForAttributes(TtsSpeaker.ATTRIBUTES).map { it.type }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }
        return types.any { it in PRIVATE_OUTPUTS }
    }

    /** Calls [onChange] when an output comes or goes; returns how to stop. */
    fun watch(onChange: () -> Unit): () -> Unit {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onChange()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onChange()
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return { audio.unregisterAudioDeviceCallback(callback) }
    }

    private companion object {
        val PRIVATE_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}

/**
 * [Speaker] over Android's TextToSpeech, on-device voices only, with audio focus so music dips while it talks. [voiceId]
 * is read for each utterance; a voice that has gone falls back to the engine's own, if that is on-device too.
 */
class TtsSpeaker(context: Context, private val voiceId: () -> String?) : Speaker {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var engine: String? = null
    private var ready = false
    private var serial = 0
    private var current: Utterance? = null
    private var focus: AudioFocusRequest? = null

    private class Utterance(val id: String, val text: String, val listener: Speaker.Listener)

    private val focusChange = AudioManager.OnAudioFocusChangeListener { change ->
        // Ducking is fine: a notification ping can talk over it. Anything longer, like a call, wins.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) fail("Stopped speaking: another app took the audio.")
    }

    override fun speak(text: String, listener: Speaker.Listener) {
        stop()
        current = Utterance("rocket-${++serial}", text, listener)
        val choice = Voices.engineOf(voiceId())
        if (tts == null || engine != choice) {
            connect(choice)
        } else if (ready) {
            play()
        }
    }

    override fun stop() {
        current = null
        tts?.stop()
        abandonFocus()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun connect(choice: String?) {
        tts?.shutdown()
        ready = false
        engine = choice
        lateinit var created: TextToSpeech
        created = TextToSpeech(app, { status -> main.post { connected(created, status) } }, choice)
        created.setAudioAttributes(ATTRIBUTES)
        created.setOnUtteranceProgressListener(progress)
        tts = created
    }

    private fun connected(which: TextToSpeech, status: Int) {
        if (which !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            tts = null
            which.shutdown()
            fail("Not spoken: this phone's text-to-speech didn't start.")
            return
        }
        ready = true
        if (current != null) play()
    }

    private fun play() {
        val utterance = current ?: return
        val engine = tts ?: return
        if (!useVoice(engine)) return fail("Not spoken: no on-device voice is installed, and Rocket doesn't use cloud voices.")
        if (!takeFocus()) return fail("Not spoken: another app has the audio.")
        if (engine.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, null, utterance.id) != TextToSpeech.SUCCESS) fail("Not spoken: text-to-speech failed.")
    }

    /** The chosen voice, else the best on-device one ([Voices.best]). Never one that needs the network. */
    private fun useVoice(engine: TextToSpeech): Boolean {
        val installed = runCatching { engine.voices }.getOrNull().orEmpty().filter { Voices.isOnDevice(it.isNetworkConnectionRequired, it.features.orEmpty()) }
        val name = Voices.nameOf(voiceId())
        val voice = installed.firstOrNull { it.name == name }
            ?: Voices.best(installed, java.util.Locale.getDefault(), { it.locale }, { it.quality })
            ?: return false
        return engine.setVoice(voice) == TextToSpeech.SUCCESS
    }

    @Suppress("DEPRECATION")
    private fun takeFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRIBUTES)
                .setOnAudioFocusChangeListener(focusChange, main)
                .build()
            focus = request
            audio.requestAudioFocus(request)
        } else {
            audio.requestAudioFocus(focusChange, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let(audio::abandonAudioFocusRequest)
            focus = null
        } else {
            audio.abandonAudioFocus(focusChange)
        }
    }

    private fun fail(reason: String) {
        val utterance = current ?: return
        stop()
        utterance.listener.onFailed(reason)
    }

    /** Called on the engine's thread, and after [stop] for what it cut off, so only the current utterance counts. */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(id: String) = onMain(id) { it.listener.onStarted() }

        override fun onDone(id: String) = finished(id)

        override fun onStop(id: String, interrupted: Boolean) = finished(id)

        @Deprecated("Deprecated in Java")
        override fun onError(id: String) = onMain(id) { fail("Not spoken: text-to-speech failed.") }

        override fun onError(id: String, errorCode: Int) = onMain(id) { fail("Not spoken: text-to-speech failed.") }

        private fun finished(id: String) = onMain(id) {
            current = null
            abandonFocus()
            it.listener.onDone()
        }

        private fun onMain(id: String, run: (Utterance) -> Unit) {
            main.post { current?.takeIf { it.id == id }?.let(run) }
        }
    }

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/**
 * [VoiceWatcher] on the mic, through the voice-call audio path so the phone's echo cancelling takes out what it's saying.
 * Only loudness is looked at; the audio isn't kept or sent. If the mic can't open, tapping still stops the reply.
 */
class MicVoiceWatcher(context: Context) : VoiceWatcher {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var running: AtomicBoolean? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(onVoice: () -> Unit) {
        stop()
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val flag = AtomicBoolean(true)
        running = flag
        worker = thread(name = "rocket-voice-watch", isDaemon = true) {
            runCatching { watch(flag) { main.post { if (flag.get()) { stop(); onVoice() } } } }
        }
    }

    override fun stop() {
        running?.set(false)
        running = null
        // The recogniser opens the mic next, and some phones allow only one at a time.
        worker?.takeIf { it !== Thread.currentThread() }?.join(JOIN_MS)
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun watch(flag: AtomicBoolean, onVoice: () -> Unit) {
        val frame = RATE * SpeechOnset.FRAME_MS / 1000
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 4)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (record.state != AudioRecord.STATE_INITIALIZED) return record.release()
        val echo = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull() else null
        runCatching { echo?.enabled = true }
        val onset = SpeechOnset()
        val samples = ShortArray(frame)
        try {
            record.startRecording()
            while (flag.get()) {
                val read = record.read(samples, 0, frame)
                if (read <= 0) break
                if (onset.feed(dbfs(samples, read))) {
                    onVoice()
                    break
                }
            }
        } finally {
            echo?.release()
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun dbfs(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / count) / Short.MAX_VALUE
        return if (rms <= 0.0) SILENCE_DB else (20 * log10(rms)).toFloat()
    }

    private companion object {
        const val RATE = 16_000
        const val JOIN_MS = 150L
        const val SILENCE_DB = -96f
    }
}
