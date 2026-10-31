package com.pocketrocket.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLoopTest {

    private class FakeRecogniser : Recogniser {
        var listener: Recogniser.Listener? = null
        override var isAvailable = true
        var stops = 0
        var cancels = 0
        var starts = 0
        override fun start(listener: Recogniser.Listener) { starts++; this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() { cancels++; listener = null }
    }

    private class FakeSpeaker : Speaker {
        var listener: Speaker.Listener? = null
        val said = mutableListOf<String>()
        var stops = 0
        var released = false
        override fun speak(text: String, listener: Speaker.Listener) { said += text; this.listener = listener }
        override fun stop() { stops++; listener = null }
        override fun release() { released = true }
    }

    private class FakeWatcher : VoiceWatcher {
        var onVoice: (() -> Unit)? = null
        override fun start(onVoice: () -> Unit) { this.onVoice = onVoice }
        override fun stop() { onVoice = null }
    }

    private class FakeHost : VoiceHost {
        override var quiet = false
        override var speakerOn = true
        override var onHeadphones = false
        override var micAllowed = true
        var asked = 0
        val heard = mutableListOf<String>()
        val notSpoken = mutableListOf<String>()
        var speech = "the reply"
        override fun askForMic() { asked++ }
        var holdAnswer = false
        val held = mutableListOf<(String) -> Unit>()
        override fun answer(heard: String, say: (String) -> Unit) {
            this.heard += heard
            if (holdAnswer) held += say else say(speech)
        }
        override fun notSpoken(reason: String) { notSpoken += reason }
    }

    private val recogniser = FakeRecogniser()
    private val speaker = FakeSpeaker()
    private val watcher = FakeWatcher()
    private val host = FakeHost()
    private val loop = VoiceLoop(recogniser, speaker, watcher, host)
    private val state get() = loop.model.value.state
    private val notice get() = loop.model.value.notice

    private fun speakReply() {
        loop.tapMic()
        recogniser.listener!!.onHeard("what's happening", final = true)
    }

    @Test fun micListensThenAnswersThenSpeaksThenRests() {
        assertEquals(VoiceState.Idle, state)
        loop.tapMic()
        assertEquals(VoiceState.Listening, state)
        assertEquals(1, recogniser.starts)
        recogniser.listener!!.onHeard("what's", final = false)
        assertEquals("what's", loop.model.value.heard)
        recogniser.listener!!.onHeard("what's happening", final = true)
        assertEquals(listOf("what's happening"), host.heard)
        assertEquals(VoiceState.Speaking, state)
        assertEquals(listOf("the reply"), speaker.said)
        speaker.listener!!.onStarted()
        assertTrue(watcher.onVoice != null)
        speaker.listener!!.onDone()
        assertEquals(VoiceState.Idle, state)
        assertNull(notice)
        assertNull(watcher.onVoice)
    }

    @Test fun theEndOfListeningAfterAFinalResultChangesNothing() {
        speakReply()
        recogniser.listener!!.onEnd(null)
        assertEquals(VoiceState.Speaking, state)
        assertEquals(1, host.heard.size)
    }

    @Test fun thinkingIsAStepOfItsOwn() {
        val step = VoiceMachine.next(VoiceModel(VoiceState.Listening), VoiceEvent.Heard("status", final = true))
        assertEquals(VoiceState.Thinking, step.model.state)
        assertEquals(listOf<VoiceEffect>(VoiceEffect.Answer("status")), step.effects)
        val tapped = VoiceMachine.next(step.model, VoiceEvent.MicTapped(MicCheck.Ready))
        assertEquals(VoiceState.Idle, tapped.model.state)
        // A reply arriving after that stays silent.
        assertEquals(emptyList<VoiceEffect>(), VoiceMachine.next(tapped.model, VoiceEvent.Answered("late", SpeakCheck.Ok)).effects)
    }

    @Test fun tapWhileListeningFinishesWithTheWordsSoFar() {
        loop.tapMic()
        recogniser.listener!!.onHeard("how full", final = false)
        loop.tapMic()
        assertEquals(1, recogniser.stops)
        assertEquals(VoiceState.Listening, state)
        recogniser.listener!!.onEnd("Didn't catch that. Try again.")
        assertEquals(listOf("how full"), host.heard)
        assertNull(notice)
    }

    @Test fun tapWhileListeningWithNothingHeardJustStops() {
        loop.tapMic()
        loop.tapMic()
        recogniser.listener!!.onEnd("Didn't hear anything.")
        assertEquals(VoiceState.Idle, state)
        assertNull(notice)
        assertTrue(host.heard.isEmpty())
    }

    @Test fun aSecondTapStopsWaitingForARecogniserThatDoesntAnswer() {
        loop.tapMic()
        loop.tapMic()
        loop.tapMic()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, recogniser.cancels)
    }

    @Test fun tapWhileSpeakingStopsAndRests() {
        speakReply()
        val listener = speaker.listener!!
        listener.onStarted()
        loop.tapMic()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, speaker.stops)
        assertNull(watcher.onVoice)
        assertEquals(1, recogniser.starts)
    }

    @Test fun talkingOverAReplyStopsItAndListens() {
        speakReply()
        speaker.listener!!.onStarted()
        watcher.onVoice!!.invoke()
        assertEquals(VoiceState.Listening, state)
        assertEquals(1, speaker.stops)
        assertNull(watcher.onVoice)
        assertEquals(2, recogniser.starts)
    }

    @Test fun aRefusedMicSaysSoAndRests() {
        host.micAllowed = false
        loop.tapMic()
        assertEquals(1, host.asked)
        assertEquals(VoiceState.Idle, state)
        assertEquals(0, recogniser.starts)
        loop.micAnswered(false)
        assertEquals(VoiceMachine.MIC_REFUSED, notice)
        assertEquals(VoiceState.Idle, state)
    }

    @Test fun anAllowedMicStartsListening() {
        host.micAllowed = false
        loop.tapMic()
        host.micAllowed = true
        loop.micAnswered(true)
        assertEquals(VoiceState.Listening, state)
    }

    @Test fun noRecogniserSaysSo() {
        recogniser.isAvailable = false
        loop.tapMic()
        assertEquals(VoiceMachine.NO_RECOGNISER, notice)
        assertEquals(VoiceState.Idle, state)
    }

    @Test fun recognitionErrorsAreShownAsTheyCame() {
        loop.tapMic()
        recogniser.listener!!.onEnd("Speech recognition needs a connection here.")
        assertEquals("Speech recognition needs a connection here.", notice)
        assertEquals(VoiceState.Idle, state)
    }

    @Test fun anErrorMidSentenceDoesntGuessFromPartWords() {
        loop.tapMic()
        recogniser.listener!!.onHeard("what does", final = false)
        recogniser.listener!!.onEnd("Speech recognition failed (code 5).")
        assertTrue(host.heard.isEmpty())
        assertEquals("Speech recognition failed (code 5).", notice)
    }

    @Test fun noMatchSaysSo() {
        loop.tapMic()
        recogniser.listener!!.onEnd(null)
        assertEquals(VoiceMachine.NO_MATCH, notice)
        assertEquals(VoiceState.Idle, state)
    }

    @Test fun aNoticeClearsOnTheNextTry() {
        loop.tapMic()
        recogniser.listener!!.onEnd(null)
        loop.tapMic()
        assertNull(notice)
    }

    @Test fun quietKeepsTheMicClosed() {
        host.quiet = true
        loop.tapMic()
        assertEquals(VoiceMachine.QUIET, notice)
        assertEquals(0, recogniser.starts)
    }

    @Test fun quietStopsListening() {
        loop.tapMic()
        host.quiet = true
        loop.conditionsChanged()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, recogniser.cancels)
        assertEquals(VoiceMachine.QUIET_STOPPED, notice)
    }

    @Test fun quietStopsSpeakingAndSaysSoOnTheReply() {
        speakReply()
        host.quiet = true
        loop.conditionsChanged()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, speaker.stops)
        assertEquals(listOf(VoiceMachine.STOPPED_QUIET), host.notSpoken)
    }

    @Test fun speakerOffWithoutHeadphonesShowsAndDoesntSpeak() {
        host.speakerOn = false
        speakReply()
        assertEquals(VoiceState.Idle, state)
        assertTrue(speaker.said.isEmpty())
        assertEquals(listOf(VoiceMachine.NOT_SPOKEN_SPEAKER), host.notSpoken)
        assertEquals(1, host.heard.size)
    }

    @Test fun speakerOffWithHeadphonesSpeaks() {
        host.speakerOn = false
        host.onHeadphones = true
        speakReply()
        assertEquals(VoiceState.Speaking, state)
        assertEquals(1, speaker.said.size)
    }

    @Test fun headphonesComingOutStopsTheReplyRatherThanUsingTheSpeaker() {
        host.speakerOn = false
        host.onHeadphones = true
        speakReply()
        host.onHeadphones = false
        loop.conditionsChanged()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, speaker.stops)
        assertEquals(listOf(VoiceMachine.STOPPED_SPEAKER), host.notSpoken)
    }

    @Test fun aChangeThatStillAllowsSpeakingLeavesItAlone() {
        speakReply()
        loop.conditionsChanged()
        assertEquals(VoiceState.Speaking, state)
        assertEquals(0, speaker.stops)
    }

    @Test fun aFailedVoiceSaysSoOnTheReply() {
        speakReply()
        speaker.listener!!.onFailed("Not spoken: text-to-speech failed.")
        assertEquals(VoiceState.Idle, state)
        assertEquals(listOf("Not spoken: text-to-speech failed."), host.notSpoken)
    }

    @Test fun lateEventsFromAStateAlreadyLeftAreIgnored() {
        val idle = VoiceModel()
        listOf(
            VoiceEvent.Heard("x", true), VoiceEvent.RecognitionEnded("e"), VoiceEvent.SpeechDone, VoiceEvent.SpeechStarted,
            VoiceEvent.SpeechFailed("e"), VoiceEvent.VoiceDetected, VoiceEvent.Answered("x", SpeakCheck.Ok), VoiceEvent.Cancel,
            VoiceEvent.ConditionsChanged(true, SpeakCheck.Quiet),
        ).forEach {
            val step = VoiceMachine.next(idle, it)
            assertEquals(it.toString(), idle, step.model)
            assertTrue(it.toString(), step.effects.isEmpty())
        }
    }

    @Test fun cancelFromAnywhereStopsWhateverIsRunning() {
        loop.tapMic()
        loop.cancel()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, recogniser.cancels)
        speakReply()
        loop.cancel()
        assertEquals(VoiceState.Idle, state)
        assertEquals(1, speaker.stops)
    }

    @Test fun releaseStopsAndFreesTheVoice() {
        speakReply()
        loop.release()
        assertEquals(VoiceState.Idle, state)
        assertTrue(speaker.released)
    }

    @Test fun anEmptyReplyIsntSpoken() {
        host.speech = ""
        speakReply()
        assertEquals(VoiceState.Idle, state)
        assertTrue(speaker.said.isEmpty())
        assertTrue(host.notSpoken.isEmpty())
    }

    @Test fun checksPutQuietFirst() {
        assertEquals(MicCheck.Quiet, VoiceMachine.micCheck(quiet = true, recogniser = false, micAllowed = false))
        assertEquals(MicCheck.NoRecogniser, VoiceMachine.micCheck(quiet = false, recogniser = false, micAllowed = false))
        assertEquals(MicCheck.NoPermission, VoiceMachine.micCheck(quiet = false, recogniser = true, micAllowed = false))
        assertEquals(SpeakCheck.Quiet, VoiceMachine.speakCheck(quiet = true, speaker = true, headphones = true))
        assertEquals(SpeakCheck.Ok, VoiceMachine.speakCheck(quiet = false, speaker = true, headphones = false))
    }

    @Test fun onsetIgnoresTheRepliesOwnEchoAndFindsAVoice() {
        val onset = SpeechOnset()
        repeat(14) { assertFalse(onset.feed(-30f)) }
        repeat(100) { assertFalse(onset.feed(-32f)) }
        // A short noise isn't a voice.
        repeat(4) { assertFalse(onset.feed(-5f)) }
        assertFalse(onset.feed(-32f))
        val spoke = (1..8).map { onset.feed(-5f) }
        assertEquals(listOf(false, false, false, false, false, false, false, true), spoke)
    }

    @Test fun onsetNeedsARealVoiceEvenInSilence() {
        val onset = SpeechOnset()
        repeat(14) { onset.feed(-90f) }
        repeat(50) { assertFalse(onset.feed(-60f)) }
    }

    @Test fun voicesKeepToThePhone() {
        assertTrue(Voices.isOnDevice(needsNetwork = false, features = emptySet()))
        assertFalse(Voices.isOnDevice(needsNetwork = true, features = emptySet()))
        assertFalse(Voices.isOnDevice(needsNetwork = false, features = setOf("notInstalled")))
        assertEquals("com.google.android.tts", Voices.engineOf(Voices.id("com.google.android.tts", "en-us-x-iol-local")))
        assertEquals("en-us-x-iol-local", Voices.nameOf(Voices.id("com.google.android.tts", "en-us-x-iol-local")))
        assertNull(Voices.engineOf(null))
        assertNull(Voices.nameOf(null))
    }

    @Test fun voicesListThePhonesLanguageFirstThenBest() {
        val de = VoiceOption("d", "German", "", "de", 500)
        val low = VoiceOption("l", "English", "", "en", 300)
        val high = VoiceOption("h", "English", "", "en", 400)
        assertEquals(listOf(high, low, de), Voices.sorted(listOf(de, low, high), "en"))
    }

    @Test fun afterSpeakingAReplyItStopsAndDoesntListenAgain() {
        val speaking = VoiceMachine.next(VoiceModel(VoiceState.Thinking, heard = "status"), VoiceEvent.Answered("All clear.", SpeakCheck.Ok))
        assertEquals(VoiceState.Speaking, speaking.model.state)
        val done = VoiceMachine.next(speaking.model, VoiceEvent.SpeechDone)
        assertEquals(VoiceState.Idle, done.model.state)
        assertFalse(VoiceEffect.StartListening in done.effects)
    }

    @Test fun aSlowAnswerToSomethingCancelledIsntSaid() {
        host.holdAnswer = true
        loop.tapMic()
        recogniser.listener!!.onHeard("start an agent", true)
        assertEquals(VoiceState.Thinking, loop.model.value.state)
        loop.cancel()
        host.held.single()("Started it.")
        assertTrue(speaker.said.isEmpty())
        assertEquals(VoiceState.Idle, loop.model.value.state)
    }

    @Test fun aSlowAnswerIsSaidWhenItComes() {
        host.holdAnswer = true
        loop.tapMic()
        recogniser.listener!!.onHeard("start an agent", true)
        host.held.single()("Started it.")
        assertEquals(listOf("Started it."), speaker.said)
    }
}
