package com.pocketrocket.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceState { Idle, Listening, Thinking, Speaking }

/** What the Rocket tab shows of the loop: the state, the words so far, and why it last went back to idle. */
data class VoiceModel(
    val state: VoiceState = VoiceState.Idle,
    val heard: String = "",
    /** The mic was tapped: the recogniser is finishing, and running out of words now isn't a failure. */
    val stopping: Boolean = false,
    val notice: String? = null,
)

/** Whether the mic may open. */
enum class MicCheck { Ready, Quiet, NoRecogniser, NoPermission }

/** Whether a reply may be said out loud right now. */
enum class SpeakCheck { Ok, Quiet, SpeakerOff }

sealed interface VoiceEvent {
    data class MicTapped(val check: MicCheck) : VoiceEvent
    data object MicRefused : VoiceEvent
    data class Heard(val text: String, val final: Boolean) : VoiceEvent
    /** Listening ended; [error] is a short reason for the person, or null. */
    data class RecognitionEnded(val error: String?) : VoiceEvent
    data class Answered(val speech: String, val check: SpeakCheck) : VoiceEvent
    data object SpeechStarted : VoiceEvent
    data object SpeechDone : VoiceEvent
    data class SpeechFailed(val reason: String) : VoiceEvent
    /** Someone started talking over the reply. */
    data object VoiceDetected : VoiceEvent
    /** Quiet, Speaker or the audio output changed. */
    data class ConditionsChanged(val quiet: Boolean, val check: SpeakCheck) : VoiceEvent
    /** Something else took over: a typed question, or the app going away. */
    data object Cancel : VoiceEvent
}

sealed interface VoiceEffect {
    data object AskForMic : VoiceEffect
    data object StartListening : VoiceEffect
    data object StopListening : VoiceEffect
    data object CancelListening : VoiceEffect
    data class Answer(val heard: String) : VoiceEffect
    data class Speak(val speech: String) : VoiceEffect
    data object StopSpeaking : VoiceEffect
    data object WatchForVoice : VoiceEffect
    data object StopWatching : VoiceEffect
    data class NotSpoken(val reason: String) : VoiceEffect
}

data class VoiceStep(val model: VoiceModel, val effects: List<VoiceEffect> = emptyList())

/** The loop's rules, with no Android in them. Every way out of a state ends in idle or the next state; late events from a state already left are ignored. */
object VoiceMachine {
    const val QUIET = "Quiet is on, so I'm not listening. You can still type."
    const val QUIET_STOPPED = "Quiet is on, so I stopped listening."
    const val NO_RECOGNISER = "Speech recognition isn't available on this phone. You can still type."
    const val MIC_REFUSED = "I need the microphone to hear you. You can still type."
    const val NO_MATCH = "Didn't catch that. Try again."
    const val NOT_SPOKEN_QUIET = "Not spoken: Quiet is on."
    const val NOT_SPOKEN_SPEAKER = "Not spoken: Speaker is off and no headphones are connected."
    const val STOPPED_QUIET = "Stopped speaking: Quiet is on."
    const val STOPPED_SPEAKER = "Stopped speaking: Speaker is off and the headphones are gone."

    fun micCheck(quiet: Boolean, recogniser: Boolean, micAllowed: Boolean): MicCheck = when {
        quiet -> MicCheck.Quiet
        !recogniser -> MicCheck.NoRecogniser
        !micAllowed -> MicCheck.NoPermission
        else -> MicCheck.Ready
    }

    fun speakCheck(quiet: Boolean, speaker: Boolean, headphones: Boolean): SpeakCheck = when {
        quiet -> SpeakCheck.Quiet
        !speaker && !headphones -> SpeakCheck.SpeakerOff
        else -> SpeakCheck.Ok
    }

    fun next(model: VoiceModel, event: VoiceEvent): VoiceStep = when (model.state) {
        VoiceState.Idle -> idle(model, event)
        VoiceState.Listening -> listening(model, event)
        VoiceState.Thinking -> thinking(model, event)
        VoiceState.Speaking -> speaking(model, event)
    }

    private fun idle(model: VoiceModel, event: VoiceEvent): VoiceStep = when (event) {
        is VoiceEvent.MicTapped -> when (event.check) {
            MicCheck.Ready -> listen()
            MicCheck.Quiet -> rest(QUIET)
            MicCheck.NoRecogniser -> rest(NO_RECOGNISER)
            MicCheck.NoPermission -> VoiceStep(VoiceModel(), listOf(VoiceEffect.AskForMic))
        }
        VoiceEvent.MicRefused -> rest(MIC_REFUSED)
        else -> VoiceStep(model)
    }

    private fun listening(model: VoiceModel, event: VoiceEvent): VoiceStep = when (event) {
        // The first tap finishes with the words so far; a second stops waiting for them.
        is VoiceEvent.MicTapped ->
            if (model.stopping) rest(null, VoiceEffect.CancelListening)
            else VoiceStep(model.copy(stopping = true), listOf(VoiceEffect.StopListening))
        is VoiceEvent.Heard -> when {
            event.text.isBlank() -> VoiceStep(model)
            event.final -> answer(event.text)
            else -> VoiceStep(model.copy(heard = event.text))
        }
        is VoiceEvent.RecognitionEnded -> when {
            // Tapping can make a recogniser report no match even with words on screen; those words are what was meant.
            model.heard.isNotBlank() && (event.error == null || model.stopping) -> answer(model.heard)
            model.stopping -> rest(null)
            else -> rest(event.error ?: NO_MATCH)
        }
        is VoiceEvent.ConditionsChanged -> if (event.quiet) rest(QUIET_STOPPED, VoiceEffect.CancelListening) else VoiceStep(model)
        VoiceEvent.Cancel -> rest(null, VoiceEffect.CancelListening)
        else -> VoiceStep(model)
    }

    private fun thinking(model: VoiceModel, event: VoiceEvent): VoiceStep = when (event) {
        is VoiceEvent.Answered -> when {
            event.speech.isBlank() -> rest(null)
            event.check == SpeakCheck.Quiet -> rest(null, VoiceEffect.NotSpoken(NOT_SPOKEN_QUIET))
            event.check == SpeakCheck.SpeakerOff -> rest(null, VoiceEffect.NotSpoken(NOT_SPOKEN_SPEAKER))
            else -> VoiceStep(VoiceModel(VoiceState.Speaking), listOf(VoiceEffect.Speak(event.speech)))
        }
        is VoiceEvent.MicTapped, VoiceEvent.Cancel -> rest(null)
        else -> VoiceStep(model)
    }

    private fun speaking(model: VoiceModel, event: VoiceEvent): VoiceStep = when (event) {
        VoiceEvent.SpeechStarted -> VoiceStep(model, listOf(VoiceEffect.WatchForVoice))
        // Request and response: when the reply is said, it's done, and the mic stays closed until he opens it (decision 59).
        VoiceEvent.SpeechDone -> rest(null, VoiceEffect.StopWatching)
        is VoiceEvent.SpeechFailed -> rest(null, VoiceEffect.StopWatching, VoiceEffect.NotSpoken(event.reason))
        is VoiceEvent.MicTapped, VoiceEvent.Cancel -> rest(null, VoiceEffect.StopSpeaking, VoiceEffect.StopWatching)
        VoiceEvent.VoiceDetected -> listen(VoiceEffect.StopSpeaking, VoiceEffect.StopWatching)
        is VoiceEvent.ConditionsChanged -> when (event.check) {
            SpeakCheck.Ok -> VoiceStep(model)
            SpeakCheck.Quiet -> rest(null, VoiceEffect.StopSpeaking, VoiceEffect.StopWatching, VoiceEffect.NotSpoken(STOPPED_QUIET))
            SpeakCheck.SpeakerOff -> rest(null, VoiceEffect.StopSpeaking, VoiceEffect.StopWatching, VoiceEffect.NotSpoken(STOPPED_SPEAKER))
        }
        else -> VoiceStep(model)
    }

    private fun listen(vararg first: VoiceEffect) = VoiceStep(VoiceModel(VoiceState.Listening), first.toList() + VoiceEffect.StartListening)

    private fun answer(heard: String) = VoiceStep(VoiceModel(VoiceState.Thinking, heard = heard.trim()), listOf(VoiceEffect.Answer(heard.trim())))

    private fun rest(notice: String?, vararg effects: VoiceEffect) = VoiceStep(VoiceModel(notice = notice), effects.toList())
}

/** Speech to text. Main thread only. */
interface Recogniser {
    val isAvailable: Boolean
    fun start(listener: Listener)
    /** Finish with the words heard so far. */
    fun stop()
    /** Drop everything; no more calls to the listener. */
    fun cancel()

    interface Listener {
        fun onHeard(text: String, final: Boolean)
        /** Input loudness, 0 to 1. */
        fun onLevel(level: Float)
        fun onEnd(error: String?)
    }
}

/** Text to speech. Listener calls come on the main thread, and none after [stop]. */
interface Speaker {
    fun speak(text: String, listener: Listener)
    fun stop()
    fun release()

    interface Listener {
        fun onStarted()
        fun onDone()
        fun onFailed(reason: String)
    }
}

/** Notices someone talking over a reply. [onVoice] comes on the main thread, at most once per [start]. */
interface VoiceWatcher {
    fun start(onVoice: () -> Unit)
    fun stop()
}

/** What the loop asks of where it lives, read fresh each time. */
interface VoiceHost {
    val quiet: Boolean
    val speakerOn: Boolean
    val onHeadphones: Boolean
    val micAllowed: Boolean

    /** Ask for the mic, then call [VoiceLoop.micAnswered]. */
    fun askForMic()

    /** Show the reply to [heard], now or once Claude has answered, and hand over what to say. Main thread. */
    fun answer(heard: String, say: (String) -> Unit)

    /** The reply just shown wasn't said, or was cut off, for [reason]. */
    fun notSpoken(reason: String)
}

/**
 * Runs [VoiceMachine] against the real speech parts. Main thread only. What was heard and said stays out of the logs:
 * commands can contain secrets.
 */
class VoiceLoop(
    private val recogniser: Recogniser,
    private val speaker: Speaker,
    private val watcher: VoiceWatcher,
    private val host: VoiceHost,
) {
    private val _model = MutableStateFlow(VoiceModel())
    val model: StateFlow<VoiceModel> = _model.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Input loudness while listening, 0 to 1. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val queue = ArrayDeque<VoiceEvent>()
    private var running = false

    fun tapMic() = dispatch(VoiceEvent.MicTapped(micCheck()))

    fun micAnswered(granted: Boolean) = if (granted) tapMic() else dispatch(VoiceEvent.MicRefused)

    fun conditionsChanged() = dispatch(VoiceEvent.ConditionsChanged(host.quiet, speakCheck()))

    fun cancel() = dispatch(VoiceEvent.Cancel)

    fun release() {
        cancel()
        speaker.release()
    }

    private fun micCheck() = VoiceMachine.micCheck(host.quiet, recogniser.isAvailable, host.micAllowed)

    private fun speakCheck() = VoiceMachine.speakCheck(host.quiet, host.speakerOn, host.onHeadphones)

    /** One event at a time, even when an effect raises another, so effects run in the order the machine gave them. */
    private fun dispatch(event: VoiceEvent) {
        queue.addLast(event)
        if (running) return
        running = true
        try {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                val step = VoiceMachine.next(_model.value, next)
                _model.value = step.model
                if (step.model.state != VoiceState.Listening) _level.value = 0f
                step.effects.forEach(::run)
            }
        } finally {
            running = false
        }
    }

    private var answering = 0

    private fun run(effect: VoiceEffect) {
        when (effect) {
            VoiceEffect.AskForMic -> host.askForMic()
            VoiceEffect.StartListening -> recogniser.start(heard)
            VoiceEffect.StopListening -> recogniser.stop()
            VoiceEffect.CancelListening -> recogniser.cancel()
            is VoiceEffect.Answer -> {
                // Claude can take a while. An answer to something he's since cancelled or asked over isn't said.
                val asked = ++answering
                host.answer(effect.heard) { speech -> if (asked == answering) dispatch(VoiceEvent.Answered(speech, speakCheck())) }
            }
            is VoiceEffect.Speak -> speaker.speak(effect.speech, said)
            VoiceEffect.StopSpeaking -> speaker.stop()
            VoiceEffect.WatchForVoice -> watcher.start { dispatch(VoiceEvent.VoiceDetected) }
            VoiceEffect.StopWatching -> watcher.stop()
            is VoiceEffect.NotSpoken -> host.notSpoken(effect.reason)
        }
    }

    private val heard = object : Recogniser.Listener {
        override fun onHeard(text: String, final: Boolean) = dispatch(VoiceEvent.Heard(text, final))
        override fun onLevel(level: Float) { _level.value = level }
        override fun onEnd(error: String?) = dispatch(VoiceEvent.RecognitionEnded(error))
    }

    private val said = object : Speaker.Listener {
        override fun onStarted() = dispatch(VoiceEvent.SpeechStarted)
        override fun onDone() = dispatch(VoiceEvent.SpeechDone)
        override fun onFailed(reason: String) = dispatch(VoiceEvent.SpeechFailed(reason))
    }
}

/**
 * Decides, from loudness alone, that someone started talking over a reply. Android's speech recognition can't run
 * alongside the reply without hearing it, so this only has to know "someone spoke" and the recogniser then takes over.
 * It sets its bar from the loudest of the reply's first moments, so its own voice through the speaker doesn't count, and
 * errs towards not interrupting.
 */
class SpeechOnset {
    private var frames = 0
    private var loud = 0
    private var floor = Float.NEGATIVE_INFINITY

    /** Feed each frame's level in dBFS; true when someone is talking. */
    fun feed(db: Float): Boolean {
        frames++
        if (frames <= CALIBRATION_FRAMES) {
            floor = maxOf(floor, db)
            return false
        }
        if (db > maxOf(floor + MARGIN_DB, MIN_DB)) {
            loud++
            return loud >= VOICE_FRAMES
        }
        loud = 0
        floor = maxOf(db, floor - DECAY_DB)
        return false
    }

    companion object {
        /** Frames are 30 ms. */
        const val FRAME_MS = 30
        private const val CALIBRATION_FRAMES = 14
        private const val VOICE_FRAMES = 8
        private const val MARGIN_DB = 14f
        private const val MIN_DB = -38f
        private const val DECAY_DB = 0.02f
    }
}
