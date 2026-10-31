package com.pocketrocket.assistant

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import com.pocketrocket.sessions.SessionManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** True on a locked phone, with or without a PIN: code stays hidden either way. */
fun phoneIsLocked(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java).let { it.isKeyguardLocked || it.isDeviceLocked }

/** Whether a screen can come up now: the app is in front, or Android lets it open over others. */
fun canOpenNow(context: Context): Boolean {
    val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
    return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND || WakeWordService.canOpenPopupDirectly(context)
}

private val askScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private val pendingIds = AtomicLong()

/**
 * The answer to [question] from live sessions, or from the brain for what they can't answer, added to the [Conversation]. The one
 * way a question gets answered, typed or said, tab or popup. A stop happens here (prompts stay in their session, decision 59). [say] gets what to speak once the reply is known, on the main thread.
 */
fun askRocket(context: Context, question: String, spoken: Boolean = false, say: (String) -> Unit = {}) {
    val app = context.applicationContext
    val states = SessionManager.sessions.value.map { it.state.value }
    val settings = AssistantPrefs.settings(app).value
    val routed = Router.route(question, states, phoneIsLocked(app), AssistantMemory.book.value, AssistantBrain.askedLast)
    val asked = question.trim()
    if (routed.toBrain && settings.enabled) return askBrain(app, asked, spoken, say)
    val reply = when (val act = routed.act) {
        is RocketAct.Stop -> {
            val stopped = SessionManager.get(act.sessionId)
            stopped?.interrupt()
            ActivityLog.add(app, routed.reply.text, if (spoken) "By voice" else "Typed", null, act.sessionId)
            routed.reply
        }
        is RocketAct.ShowChanges -> return showChanges(app, asked, act.sessionId, routed.reply, say)
        is RocketAct.Change -> return changeSetting(app, asked, act.change, spoken, routed.reply, say)
        else -> routed.reply
    }
    Conversation.add(app, Turn(asked, reply))
    say(reply.speech)
}

/** Waits on Claude in a turn of its own, so the screen shows the question while it thinks. */
private fun askBrain(app: Context, question: String, spoken: Boolean, say: (String) -> Unit) {
    val pending = pendingIds.incrementAndGet()
    val waiting = if (AssistantBrain.isCold) "One moment." else "Thinking."
    Conversation.add(app, Turn(question, Reply(waiting, speech = ""), pending = pending))
    askScope.launch {
        val reply = BrainReply.shape(AssistantBrain.ask(app, question, spoken), phoneIsLocked(app))
        Conversation.resolve(app, pending, reply)
        say(reply.speech)
    }
}

private fun showChanges(app: Context, question: String, sessionId: String, looking: Reply, say: (String) -> Unit) {
    val pending = pendingIds.incrementAndGet()
    Conversation.add(app, Turn(question, looking, pending = pending))
    askScope.launch {
        val state = SessionManager.get(sessionId)?.state?.value
        val reply = if (state == null) Reply(Router.GONE) else SessionChanges.reply(app, state)
        Conversation.resolve(app, pending, reply)
        say(reply.speech)
    }
}

/** A setting Router understood. Locked, it takes a fingerprint, as [SettingsTools] does: anyone nearby can talk to the phone. */
private fun changeSetting(app: Context, question: String, change: SettingChange, spoken: Boolean, done: Reply, say: (String) -> Unit) {
    fun apply(): Reply {
        SettingsControl.apply(app, change)
        ActivityLog.add(app, change.said, if (spoken) "By voice" else "Typed")
        return done
    }
    if (!phoneIsLocked(app)) {
        val reply = apply()
        Conversation.add(app, Turn(question, reply))
        say(reply.speech)
        return
    }
    val pending = pendingIds.incrementAndGet()
    Conversation.add(app, Turn(question, Reply("Checking with you.", speech = ""), pending = pending))
    askScope.launch {
        val reply = if (Approvals.confirm(app, RocketAction.WhileLocked(change.asked), listen = spoken)) apply() else Reply("Not changed.")
        Conversation.resolve(app, pending, reply)
        say(reply.speech)
    }
}

/** A [VoiceLoop] wired to the phone's speech and the shared [Conversation], for the tab and the popup alike. */
fun rocketVoiceLoop(context: Context, askForMic: () -> Unit): VoiceLoop {
    val app = context.applicationContext
    val route = AudioRoute(app)
    val settings = AssistantPrefs.settings(app)
    val speaker = TtsSpeaker(app) { settings.value.voice }
    return VoiceLoop(
        DictationRecogniser(app),
        speaker,
        MicVoiceWatcher(app),
        object : VoiceHost {
            override val quiet get() = settings.value.isQuiet()
            override val speakerOn get() = settings.value.speaker
            override val onHeadphones get() = route.onHeadphones()
            override val micAllowed get() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            override fun askForMic() = askForMic()
            override fun answer(heard: String, say: (String) -> Unit) {
                // Claude takes a few seconds to wake after a pause.
                if (AssistantBrain.isCold && Router.route(heard, emptyList(), book = AssistantMemory.book.value, brainAsked = AssistantBrain.askedLast).toBrain && !quiet && (speakerOn || onHeadphones)) {
                    speaker.speak("One moment.", object : Speaker.Listener {
                        override fun onStarted() {}
                        override fun onDone() {}
                        override fun onFailed(reason: String) {}
                    })
                }
                askRocket(app, heard, spoken = true, say = say)
            }
            override fun notSpoken(reason: String) = Conversation.noteLast(app, reason)
        },
    )
}
