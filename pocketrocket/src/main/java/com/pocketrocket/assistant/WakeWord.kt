package com.pocketrocket.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.pocketrocket.notifications.WakeNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The mic as [WakeWordService] has it. [Open] only once audio is really reaching the detector. */
enum class WakeMicState { Closed, Open, Taken, Failed }

/** Something recording right now, as Android reports it. */
data class Recording(val session: Int, val source: Int, val silenced: Boolean = false)

/** What the Hey Rocket switch shows. */
data class WakeView(val on: Boolean, val note: String)

/**
 * What Settings › Assistant shows under Hey Rocket, so a test on the phone says where it stops: is audio arriving, how loud,
 * from which source, and was the word heard. Nothing heard is kept: only levels and times.
 */
data class WakeDebug(
    /** The mic's level now, before the gain, in dBFS. */
    val levelDb: Float = WakeGain.SILENT_DB,
    /** The loudest of the last few seconds, in dBFS. */
    val peakDb: Float = WakeGain.SILENT_DB,
    val gain: Float = 1f,
    val source: String? = null,
    /** VOICE_RECOGNITION gave only zeros, so it moved to MIC. */
    val sourceWasSilent: Boolean = false,
    val heardCount: Int = 0,
    val lastHeardAt: Long = 0L,
)

/** Decision 62's rules, with no Android in them. */
object WakeRules {
    /** MediaRecorder.AudioSource.HOTWORD, a hidden constant: the phone's own assistant listening in the background gives way to us, so it isn't "another app taking the mic". */
    private const val SOURCE_HOTWORD = 1999

    /** Whether the service should be running: only while he has switched it on (decision 62), never on its own because of a session or a charger. */
    fun shouldRun(s: AssistantSettings, installed: Boolean, micAllowed: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        s.enabled && s.wakeWord && !s.isQuiet(now) && installed && micAllowed

    /** True when someone other than the session [own] records, or our own recording has been silenced for someone else, or a call is on. */
    fun micTaken(recordings: List<Recording>, own: Int?, inCall: Boolean): Boolean =
        inCall || recordings.any { if (it.session == own) it.silenced else it.source != SOURCE_HOTWORD }

    fun view(install: WakeInstall, s: AssistantSettings, micAllowed: Boolean, mic: WakeMicState, problem: Boolean, now: Long = System.currentTimeMillis()): WakeView = when {
        install == WakeInstall.Unsupported -> WakeView(false, "Not available on this phone")
        install is WakeInstall.Failed -> WakeView(false, install.message)
        install is WakeInstall.Downloading -> WakeView(false, "Downloading ${install.done * 100 / install.total.coerceAtLeast(1)}%")
        install == WakeInstall.Unpacking -> WakeView(false, "Unpacking")
        install == WakeInstall.NotInstalled -> WakeView(false, "Off, needs a ${WakeFiles.megabytes(WakeFiles.downloadBytes)} download")
        problem -> WakeView(false, "Off, it may have crashed the app")
        !s.wakeWord -> WakeView(false, "Off")
        !micAllowed -> WakeView(false, "Off, needs the microphone")
        s.isQuiet(now) -> WakeView(true, "On, paused by Quiet")
        mic == WakeMicState.Open -> WakeView(true, "Listening")
        mic == WakeMicState.Taken -> WakeView(true, "On, another app has the mic")
        mic == WakeMicState.Failed -> WakeView(false, "Couldn't start")
        else -> WakeView(true, "On")
    }
}

/**
 * Decides when [WakeWordService] runs, for the life of the process: only while Hey Rocket is switched on (decision 62). It
 * starts the service and the service stops itself when [run] goes false. Nothing here wakes a dead process: after Android or
 * the phone's maker kills the app, listening resumes the next time the app opens (decision 8: nothing starts on boot).
 */
object WakeWord {
    private const val PREF_TRIAL = "assistant_wake_trial"
    private const val PREF_PROVEN = "assistant_wake_proven"
    private const val PEAK_FRAMES = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val micAllowed = MutableStateFlow(false)
    private val holds = MutableStateFlow(emptySet<String>())
    private val problem = MutableStateFlow(false)
    private val _mic = MutableStateFlow(WakeMicState.Closed)
    private val _run = MutableStateFlow(false)
    private val _view = MutableStateFlow(WakeView(false, "Off"))
    private val _debug = MutableStateFlow(WakeDebug())
    private val peaks = ArrayDeque<Float>()
    private var started = false

    /** The service, while it runs, told at once when a hold changes so the mic is free before the recogniser wants it. */
    @Volatile var host: (() -> Unit)? = null

    val run: StateFlow<Boolean> = _run.asStateFlow()
    val view: StateFlow<WakeView> = _view.asStateFlow()

    /** Settings' readout. */
    val debug: StateFlow<WakeDebug> = _debug.asStateFlow()

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        WakeNotifier.init(app)
        WakeModel.refresh(app)
        micAllowed.value = hasMic(app)
        // A native crash takes the whole app with it, sessions too, so one is allowed once and not on every start after.
        val prefs = prefs(app)
        if (prefs.getBoolean(PREF_TRIAL, false)) {
            prefs.edit().putBoolean(PREF_TRIAL, false).commit()
            AssistantPrefs.update(app) { it.copy(wakeWord = false) }
            problem.value = true
        }
        val settings = AssistantPrefs.settings(app)
        scope.launch {
            combine(settings, WakeModel.state, micAllowed) { s, install, mic ->
                WakeRules.shouldRun(s, install == WakeInstall.Installed, mic)
            }.distinctUntilChanged().collect { wanted ->
                _run.value = wanted
                if (wanted) WakeWordService.start(app)
            }
        }
        scope.launch {
            combine(settings, WakeModel.state, micAllowed, _mic, problem) { s, install, allowed, mic, bad ->
                WakeRules.view(install, s, allowed, mic, bad)
            }.collect { _view.value = it }
        }
    }

    /** Turning it on again after a crash means trying again. */
    fun turnOn(context: Context) {
        problem.value = false
        refresh(context)
        AssistantPrefs.update(context) { it.copy(wakeWord = true) }
    }

    fun turnOff(context: Context) {
        problem.value = false
        AssistantPrefs.update(context) { it.copy(wakeWord = false) }
    }

    /** After the mic permission may have changed. */
    fun refresh(context: Context) {
        micAllowed.value = hasMic(context)
    }

    /** Rocket itself needs the mic, or is talking: the detector lets go until every [key] has let go. Main thread. */
    fun hold(key: String, held: Boolean) {
        val before = holds.value
        holds.update { if (held) it + key else it - key }
        if (holds.value != before) host?.invoke()
    }

    fun held(): Boolean = holds.value.isNotEmpty()

    /** Who has Rocket's voice or mic now; empty when it's free. */
    val holdKeys: StateFlow<Set<String>> = holds.asStateFlow()

    /** From the service. */
    internal fun reportMic(state: WakeMicState) {
        _mic.value = state
    }

    /** From the mic thread, every frame. The peak covers the last [PEAK_FRAMES]. */
    internal fun reportLevel(db: Float, gain: Float) {
        val peak = synchronized(peaks) {
            peaks.addLast(db)
            while (peaks.size > PEAK_FRAMES) peaks.removeFirst()
            peaks.max()
        }
        _debug.update { it.copy(levelDb = db, peakDb = peak, gain = gain) }
    }

    internal fun reportSource(label: String, silentBefore: Boolean) = _debug.update { it.copy(source = label, sourceWasSilent = silentBefore) }

    internal fun reportHeard() = _debug.update { it.copy(heardCount = it.heardCount + 1, lastHeardAt = System.currentTimeMillis()) }

    /** Before native code first runs for this download. [proven] clears it once audio has gone through without a crash. */
    internal fun startTrial(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(PREF_PROVEN, false)) prefs.edit().putBoolean(PREF_TRIAL, true).commit()
    }

    /** The mic closed normally before the trial finished, so nothing crashed. */
    internal fun endTrial(context: Context) {
        prefs(context).edit().putBoolean(PREF_TRIAL, false).apply()
    }

    internal fun proven(context: Context) {
        prefs(context).edit().putBoolean(PREF_TRIAL, false).putBoolean(PREF_PROVEN, true).apply()
    }

    private fun hasMic(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("pocketrocket", Context.MODE_PRIVATE)
}
