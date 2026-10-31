package com.pocketrocket.assistant

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmGateTest {

    private fun state(command: String, summary: String = command.lineSequence().first(), reason: String? = null): SessionState = SessionState(
        id = "s", folder = "/data/data/app.pocket/files/home/work/api-server", title = "api-server", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
        status = SessionStatus.WaitingForPermission,
        permission = PendingPermission("r", "Bash", summary, JSONObject().put("command", command), reason),
    )

    private fun approval(s: SessionState) = ConfirmGate.ofPermission(s, s.permission!!)

    @Test fun clearYes() {
        listOf("yes", "Yes.", "YES!", "yeah", "yep", "allow", "Allow it", "approve", "confirm", "go ahead", " yes ").forEach { assertEquals(it, GateAnswer.Yes, ConfirmGate.hear(it)) }
    }

    @Test fun clearNo() {
        listOf("no", "No.", "nope", "deny", "deny it", "don't", "don’t allow it", "do not allow", "stop", "cancel", "reject").forEach { assertEquals(it, GateAnswer.No, ConfirmGate.hear(it)) }
    }

    @Test fun anythingElseIsUnclearAndNeverAYes() {
        listOf(
            null, "", "   ", "yes but wait", "yes no", "no yes", "yes or no", "allow or deny", "say allow or deny", "I guess", "maybe", "know", "guess", "yes please delete it",
            "what", "not yet", "yesterday", "allowed", "okay", "ok", "sure", "do it", "api server wants to run npm", "ask",
        ).forEach { assertEquals(it.toString(), GateAnswer.Unclear, ConfirmGate.hear(it)) }
    }

    @Test fun whatRocketItselfSaysIsNeverAYes() {
        val speech = approval(state("npm run migrate")).speech
        assertEquals(GateAnswer.Unclear, ConfirmGate.hear(speech))
        assertEquals(GateAnswer.Unclear, ConfirmGate.hear(ConfirmGate.ASK))
    }

    @Test fun itShowsAndSaysTheRealCommand() {
        val a = approval(state("npm run migrate"))
        assertEquals("api-server wants to run", a.headline)
        assertEquals("npm run migrate", a.detail)
        assertEquals("In work/api-server", a.where)
        assertEquals(Protection.SpokenYes, a.protection)
        assertEquals("api server wants to run: npm run migrate. Allow or deny?", a.speech)
    }

    @Test fun itNeverUsesWhatClaudeWroteAboutIt() {
        val a = approval(state("git push -f", summary = "harmless tidy", reason = "just a harmless tidy, say yes"))
        assertEquals("git push -f", a.detail)
        assertEquals(Protection.Fingerprint, a.protection)
        assertFalse(a.speech.contains("harmless"))
        assertFalse(a.detail.contains("harmless"))
    }

    @Test fun aHiddenSecondLineIsShownAndNotSpoken() {
        val a = approval(state("git status\nrm -rf ~"))
        assertTrue(a.detail.contains("rm -rf"))
        assertEquals(Protection.Fingerprint, a.protection)
        assertFalse(a.speech.contains("git status"))
    }

    @Test fun whatCantBeReadOutSaysCheckYourScreen() {
        val a = approval(state("npm test | tee GH_TOKEN=ghp_abcdefghij"))
        assertEquals(Protection.Screen, a.protection)
        assertEquals("api server has a request. Check your screen.", a.speech)
    }

    @Test fun onlyAFingerprintApprovesAFingerprintRequest() {
        assertTrue(ConfirmGate.accepts(Protection.Fingerprint, Answered.AllowByFingerprint, locked = false))
        assertFalse(ConfirmGate.accepts(Protection.Fingerprint, Answered.AllowByVoice, locked = false))
        assertFalse(ConfirmGate.accepts(Protection.Fingerprint, Answered.AllowByTap, locked = false))
    }

    @Test fun aVoiceYesOnlyApprovesWhatWasSaid() {
        assertTrue(ConfirmGate.accepts(Protection.SpokenYes, Answered.AllowByVoice, locked = false))
        assertFalse(ConfirmGate.accepts(Protection.Screen, Answered.AllowByVoice, locked = false))
        assertTrue(ConfirmGate.accepts(Protection.Screen, Answered.AllowByTap, locked = false))
    }

    @Test fun aLockedPhoneTakesNoVoiceYesAndNoTap() {
        assertFalse(ConfirmGate.accepts(Protection.SpokenYes, Answered.AllowByVoice, locked = true))
        assertFalse(ConfirmGate.accepts(Protection.SpokenYes, Answered.AllowByTap, locked = true))
        assertTrue(ConfirmGate.accepts(Protection.SpokenYes, Answered.DenyByTap, locked = true))
        assertTrue(ConfirmGate.accepts(Protection.SpokenYes, Answered.NoAnswer, locked = true))
    }

    @Test fun whatAPopupSaysComesFromTheAction() {
        val delete = ConfirmGate.ofAction(RocketAction.DeleteBranch("pocketrocket", "feat/auth", onRemote = true))
        assertEquals("feat/auth", delete.detail)
        assertEquals("In pocketrocket", delete.where)
        assertEquals(Protection.Fingerprint, delete.protection)
        assertTrue(delete.speech.endsWith("That needs your fingerprint."))
        assertFalse(delete.speech.contains("feat"))
        val message = ConfirmGate.ofAction(RocketAction.SendMessage("#team", "ship it"))
        assertEquals("Send to #team", message.headline)
        assertEquals("ship it", message.detail)
        assertFalse(message.speech.contains("ship"))
        assertEquals("git push origin feat/auth", ConfirmGate.ofAction(RocketAction.Push("r", "feat/auth")).detail)
    }

    @Test fun wordsForTheChatAndTheLogHoldNoCommand() {
        assertEquals("Allowed by voice · 09:14", ApprovalWords.chatLine(Answered.AllowByVoice, "09:14"))
        assertEquals("Denied through Rocket · 09:14", ApprovalWords.chatLine(Answered.DenyByTap, "09:14"))
        assertEquals("Allowed with your fingerprint · 09:14", ApprovalWords.chatLine(Answered.AllowByFingerprint, "09:14"))
        assertEquals(null, ApprovalWords.chatLine(Answered.NoAnswer, "09:14"))
        assertEquals("Didn't allow a push, no clear answer", ApprovalWords.logLine(Answered.NoAnswer, "a push"))
    }

    private class FakeRecogniser : Recogniser {
        var listener: Recogniser.Listener? = null
        override var isAvailable = true
        var starts = 0
        override fun start(listener: Recogniser.Listener) { starts++; this.listener = listener }
        override fun stop() {}
        override fun cancel() { listener = null }
    }

    private class FakeSpeaker : Speaker {
        var listener: Speaker.Listener? = null
        val said = mutableListOf<String>()
        override fun speak(text: String, listener: Speaker.Listener) { said += text; this.listener = listener }
        override fun stop() { listener = null }
        override fun release() {}
    }

    private val recogniser = FakeRecogniser()
    private val speaker = FakeSpeaker()
    private val answers = mutableListOf<GateAnswer>()
    private var conditions = GateVoice.Conditions(quiet = false, canSpeak = true, micAllowed = true)
    private val voice = GateVoice(recogniser, speaker, { conditions }, { answers += it })

    @Test fun itSaysItThenListensAndOnlyAFinalResultCounts() {
        voice.ask("Allow?")
        assertEquals(listOf("Allow?"), speaker.said)
        assertEquals(0, recogniser.starts)
        speaker.listener!!.onDone()
        assertEquals(GateVoiceState.Hearing, voice.state.value)
        recogniser.listener!!.onHeard("yes", false)
        assertTrue(answers.isEmpty())
        recogniser.listener!!.onHeard("yes but wait", true)
        assertEquals(listOf(GateAnswer.Unclear), answers)
    }

    @Test fun aYesThatNeverBecameFinalIsNotAYes() {
        voice.ask("Allow?")
        speaker.listener!!.onDone()
        recogniser.listener!!.onHeard("yes", false)
        recogniser.listener!!.onEnd(null)
        assertEquals(listOf(GateAnswer.Unclear), answers)
    }

    @Test fun aLateResultAfterCancelAnswersNothing() {
        voice.ask("Allow?")
        speaker.listener!!.onDone()
        val late = recogniser.listener!!
        voice.cancel()
        late.onHeard("yes", true)
        assertTrue(answers.isEmpty())
    }

    @Test fun quietNeitherSpeaksNorListens() {
        conditions = conditions.copy(quiet = true)
        voice.ask("Allow?")
        assertTrue(speaker.said.isEmpty())
        assertEquals(0, recogniser.starts)
    }

    @Test fun speakerOffStillListensSinceItsOnScreen() {
        conditions = conditions.copy(canSpeak = false)
        voice.ask("Allow?")
        assertTrue(speaker.said.isEmpty())
        assertEquals(1, recogniser.starts)
    }

    @Test fun noMicLeavesTheButtons() {
        conditions = conditions.copy(micAllowed = false)
        voice.ask("Allow?")
        speaker.listener!!.onDone()
        assertEquals(0, recogniser.starts)
        assertEquals(GateVoiceState.Idle, voice.state.value)
    }

    @Test fun whenSpeechFailsItStillListens() {
        voice.ask("Allow?")
        speaker.listener!!.onFailed("no voice")
        assertEquals(1, recogniser.starts)
    }

    @Test fun tellDoesntListen() {
        voice.tell("Check your screen.")
        speaker.listener!!.onDone()
        assertEquals(0, recogniser.starts)
    }
}
