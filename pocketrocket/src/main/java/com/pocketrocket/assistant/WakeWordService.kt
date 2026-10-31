package com.pocketrocket.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.pocketrocket.notifications.WakeNotifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hey Rocket: a foreground service with the mic open and a detector on the phone (decision 54). [WakeWord] decides when it
 * runs and it stops itself when that ends. No audio is kept or sent: frames go to the detector and are overwritten, and
 * nothing heard is logged. It lets go of the mic while another app or a call has it, or while Rocket is listening or talking,
 * and takes it back after.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var watching: Job? = null
    private var mic: WakeMic? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown: WakeMicState? = null
    private var failedAt = 0L
    private var failures = 0
    private var coolingDown = false
    private var source = WakeSource.VoiceRecognition
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val recordings = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = evaluate()
    }

    /** Android has no callback for a call ending before API 31, and a failed mic needs another try. */
    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always foreground first: Android requires it soon after startForegroundService.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) WakeWord.turnOff(this)
        if (watching == null) {
            WakeWord.host = ::evaluate
            audio.registerAudioRecordingCallback(recordings, main)
            main.post(tick)
            watching = scope.launch {
                WakeWord.run.collect { wanted -> if (wanted) evaluate() else shutDown() }
            }
        } else {
            evaluate()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = runCatching {
        val notification = WakeNotifier.listening(this, WakeMicState.Closed)
        shown = WakeMicState.Closed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(WakeNotifier.LISTENING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(WakeNotifier.LISTENING_ID, notification)
        }
    }.onFailure { Log.w(TAG, "couldn't go foreground: ${it.javaClass.simpleName}") }.isSuccess

    /** Opens or closes the mic to match how things are now. */
    private fun evaluate() {
        if (!WakeWord.run.value) return
        val taken = WakeRules.micTaken(currentRecordings(), mic?.session, inCall())
        val retrying = failedAt != 0L && System.currentTimeMillis() - failedAt < (RETRY_MS shl failures.coerceAtMost(MAX_BACKOFF))
        val wanted = !taken && !WakeWord.held() && !coolingDown && !retrying
        when {
            wanted && mic == null -> openMic()
            !wanted && mic != null -> closeMic()
        }
        report(
            when {
                mic?.hearing == true -> WakeMicState.Open
                taken -> WakeMicState.Taken
                retrying -> WakeMicState.Failed
                else -> WakeMicState.Closed
            },
        )
    }

    private fun currentRecordings(): List<Recording> = runCatching {
        audio.activeRecordingConfigurations.map {
            Recording(it.clientAudioSessionId, it.clientAudioSource, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isClientSilenced)
        }
    }.getOrDefault(emptyList())

    private fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun openMic() {
        WakeWord.startTrial(this)
        WakeWord.reportSource(source.label, silentBefore = source != WakeSource.VoiceRecognition)
        val app = applicationContext
        mic = WakeMic(
            source = source,
            open = { SherpaWakeDetector.open(app) },
            onLevel = { db, gain -> WakeWord.reportLevel(db, gain) },
            onHearing = { main.post { failures = 0; failedAt = 0L; evaluate() } },
            onProven = { WakeWord.proven(app) },
            onHeard = { main.post(::heardWord) },
            onFailed = { why -> main.post { micFailed(why) } },
        ).also { it.start() }
        if (wakeLock == null) {
            // The processor has to stay awake to hear anything; Android's privacy dot shows the mic is on.
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketrocket:wakeword")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_LIMIT_MS) }
        }
    }

    private fun closeMic() {
        if (mic != null) WakeWord.endTrial(this)
        mic?.stop()
        mic = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun heardWord() {
        WakeWord.reportHeard()
        closeMic()
        // The popup takes its own hold; this keeps the mic free if it's slow or the person doesn't answer the notification.
        coolingDown = true
        main.postDelayed({ coolingDown = false; evaluate() }, COOLDOWN_MS)
        AssistantActivity.openFromWakeWord(this)
        evaluate()
    }

    private fun micFailed(why: String) {
        Log.w(TAG, "detector stopped: $why")
        closeMic()
        if (why == SILENT && source == WakeSource.VoiceRecognition) {
            // Not a failure of the detector: try the other source straight away.
            source = WakeSource.Mic
            WakeWord.reportSource(source.label, silentBefore = true)
            evaluate()
            return
        }
        failures++
        failedAt = System.currentTimeMillis()
        evaluate()
    }

    private fun report(state: WakeMicState) {
        WakeWord.reportMic(state)
        if (state == shown) return
        shown = state
        getSystemService(android.app.NotificationManager::class.java).notify(WakeNotifier.LISTENING_ID, WakeNotifier.listening(this, state))
    }

    private fun shutDown() {
        closeMic()
        WakeWord.reportMic(WakeMicState.Closed)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        closeMic()
        WakeWord.host = null
        WakeWord.reportMic(WakeMicState.Closed)
        main.removeCallbacksAndMessages(null)
        if (watching != null) audio.unregisterAudioRecordingCallback(recordings)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PocketRocketWake"
        private const val TICK_MS = 5_000L
        private const val RETRY_MS = 30_000L
        /** Doubling from 30 seconds to 16 minutes: loading it checks 29 MB of files each time. */
        private const val MAX_BACKOFF = 5
        private const val COOLDOWN_MS = 8_000L
        /** A cap in case something goes wrong; the next opening of the mic takes it again. */
        private const val WAKE_LOCK_LIMIT_MS = 12 * 60 * 60 * 1000L
        const val ACTION_STOP = "app.pocket.wake.STOP"

        fun start(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, WakeWordService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
                .onFailure { Log.w(TAG, "couldn't start: ${it.javaClass.simpleName}") }
        }

        /** Android lets a background app open a screen itself only with "Display over other apps" (or before Android 10). */
        fun canOpenPopupDirectly(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)
    }
}

internal const val SILENT = "silent"

/** Where the audio comes from. VOICE_RECOGNITION is meant for this; plain MIC is the fallback when a phone gives it only silence. */
internal enum class WakeSource(val id: Int, val label: String) {
    VoiceRecognition(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Voice recognition"),
    Mic(MediaRecorder.AudioSource.MIC, "Mic"),
}

/** The source gave only digital silence. */
internal class SilentMic : Exception("silent")

/** The mic and the detector on their own thread. What's read goes to the detector and is overwritten by the next read. */
private class WakeMic(
    private val source: WakeSource,
    private val open: () -> WakeDetector,
    /** Each frame's level from the mic in dBFS, and the gain put on it: for Settings' readout. */
    private val onLevel: (Float, Float) -> Unit,
    private val onHearing: () -> Unit,
    private val onProven: () -> Unit,
    private val onHeard: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val running = AtomicBoolean(true)
    private var worker: Thread? = null

    @Volatile var session: Int? = null
        private set

    @Volatile var hearing = false
        private set

    fun start() {
        worker = thread(name = "rocket-wake-word", isDaemon = true) {
            try {
                listen()
            } catch (t: Throwable) {
                // An UnsatisfiedLinkError from the library counts too: the app stays up and Hey Rocket says it couldn't start.
                if (running.get()) onFailed(if (t is SilentMic) SILENT else t.javaClass.simpleName)
            }
        }
    }

    /** Waits briefly: the recogniser opens the mic next, and some phones allow only one at a time. */
    fun stop() {
        running.set(false)
        worker?.takeIf { it !== Thread.currentThread() }?.join(JOIN_MS)
        worker = null
    }

    private fun listen() {
        val detector = open()
        try {
            if (running.get()) record(detector)
        } finally {
            detector.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(detector: WakeDetector) {
        val rate = SherpaWakeDetector.SAMPLE_RATE
        val frame = rate / 10
        val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 4)
        val record = AudioRecord(source.id, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        val samples = ShortArray(frame)
        val floats = FloatArray(frame)
        val gain = WakeGain()
        var frames = 0
        var anySound = false
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic didn't open")
            session = record.audioSessionId
            record.startRecording()
            while (running.get()) {
                val read = record.read(samples, 0, frame)
                if (read <= 0) throw IllegalStateException("mic read $read")
                for (i in 0 until read) {
                    floats[i] = samples[i] / 32768f
                    if (samples[i].toInt() != 0) anySound = true
                }
                // Some phones hand a source nothing but zeros: then it's the other source's turn.
                if (!anySound && frames >= SILENT_FRAMES) throw SilentMic()
                gain.apply(floats, read)
                val heard = detector.heard(if (read == frame) floats else floats.copyOf(read))
                onLevel(gain.inputDb, gain.gain)
                if (!hearing) {
                    hearing = true
                    onHearing()
                }
                if (++frames == PROVEN_FRAMES) onProven()
                if (heard) {
                    running.set(false)
                    onHeard()
                }
            }
        } finally {
            hearing = false
            session = null
            runCatching { record.stop() }
            record.release()
        }
    }

    private companion object {
        const val JOIN_MS = 300L
        /** Three seconds of exact zeros: no real mic is that quiet. */
        const val SILENT_FRAMES = 30
        /** 20 seconds of audio through the detector without a crash. */
        const val PROVEN_FRAMES = 200
    }
}
