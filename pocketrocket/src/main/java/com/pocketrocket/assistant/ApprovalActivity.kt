package com.pocketrocket.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.pocketrocket.ui.rocket.ApprovalScreen
import com.pocketrocket.ui.theme.PocketRocketTheme
import com.pocketrocket.ui.theme.rememberDarkTheme

/**
 * The approval screen (mockup A-Approve), the same for voice, a typed "allow" and the fingerprint popup. It never shows over
 * the lock screen: Android asks for the unlock first, so the command is only ever seen by whoever unlocked. Leaving it means no.
 */
class ApprovalActivity : ComponentActivity() {

    private var open: Approvals.Open? = null
    private var voice: GateVoice? = null
    private var dropPrompt: (() -> Unit)? = null
    private var began = false
    private val notice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = Approvals.open.value
        if (current == null) {
            finish()
            return
        }
        open = current
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WakeWord.hold(HOLD, true)
        val settings = AssistantPrefs.settings(this)
        val route = AudioRoute(this)
        val gate = GateVoice(
            DictationRecogniser(this),
            TtsSpeaker(this) { settings.value.voice },
            conditions = {
                val s = settings.value
                GateVoice.Conditions(
                    quiet = s.isQuiet(),
                    canSpeak = VoiceMachine.speakCheck(s.isQuiet(), s.speaker, route.onHeadphones()) == SpeakCheck.Ok,
                    micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                )
            },
            onAnswer = { heard ->
                when (heard) {
                    GateAnswer.Yes -> Approvals.answer(this, current, Answered.AllowByVoice)
                    GateAnswer.No -> Approvals.answer(this, current, Answered.DenyByVoice)
                    GateAnswer.Unclear -> {
                        notice.value = ConfirmGate.UNCLEAR
                        voice?.tell(ConfirmGate.UNCLEAR)
                    }
                }
            },
        )
        voice = gate
        setContent {
            PocketRocketTheme(darkTheme = rememberDarkTheme()) {
                val now by Approvals.open.collectAsState()
                val hearing by gate.state.collectAsState()
                LaunchedEffect(now) { if (now !== current) finish() }
                ApprovalScreen(
                    current.approval,
                    hearing,
                    notice.value,
                    canListen = current.listen,
                    onDeny = { Approvals.answer(this, current, Answered.DenyByTap) },
                    onAllow = {
                        if (current.approval.protection == Protection.Fingerprint) askFingerprint(current)
                        else Approvals.answer(this, current, Answered.AllowByTap)
                    },
                    onListenAgain = { notice.value = null; gate.listen() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenLaunch.resumed(javaClass)
        val current = open ?: return
        if (began) return
        began = true
        when (current.approval.protection) {
            Protection.Fingerprint -> {
                if (current.listen) voice?.tell(current.approval.speech)
                askFingerprint(current)
            }
            Protection.SpokenYes -> if (current.listen) voice?.ask(current.approval.speech)
            Protection.Screen -> if (current.listen) voice?.tell(current.approval.speech)
        }
    }

    override fun onPause() {
        super.onPause()
        // The fingerprint prompt or a PIN screen can pause it and its voice can finish; anything else leaving stops the listening.
        if (dropPrompt == null) voice?.cancel()
    }

    /** Going away is a no, except to the PIN screen the prompt itself opened. */
    override fun onStop() {
        super.onStop()
        val current = open ?: return
        if (began && dropPrompt == null) Approvals.answer(this, current, Answered.NoAnswer)
    }

    override fun onDestroy() {
        dropPrompt?.invoke()
        dropPrompt = null
        voice?.release()
        if (open != null) WakeWord.hold(HOLD, false)
        super.onDestroy()
    }

    private fun askFingerprint(current: Approvals.Open) {
        if (dropPrompt != null) return
        dropPrompt = Fingerprint.ask(this, current.approval) { refused ->
            dropPrompt = null
            if (refused == null) Approvals.answer(this, current, Answered.AllowByFingerprint)
            // Dismissing it means no.
            else Approvals.answer(this, current, Answered.NoAnswer)
        }
    }

    private companion object {
        const val HOLD = "approval"
    }
}
