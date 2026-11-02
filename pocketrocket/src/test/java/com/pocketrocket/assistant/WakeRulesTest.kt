package com.pocketrocket.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WakeRulesTest {

    private val on = AssistantSettings(enabled = true, wakeWord = true)

    private fun run(s: AssistantSettings = on, installed: Boolean = true, mic: Boolean = true) =
        WakeRules.shouldRun(s, installed, mic, now = 1_000)

    @Test fun listensOnlyWhileSwitchedOn() {
        // Decision 62: no sessions-or-charging rule; switched on, it listens, and nothing else starts it.
        assertTrue(run())
        assertFalse(run(on.copy(wakeWord = false)))
    }

    @Test fun anySwitchOffStopsIt() {
        assertFalse(run(on.copy(enabled = false)))
        assertFalse(run(on.copy(wakeWord = false)))
        assertFalse(run(on.copy(quietUntil = 2_000)))
        assertFalse(run(installed = false))
        assertFalse(run(mic = false))
    }

    @Test fun quietThatHasEndedDoesNotStopIt() {
        assertTrue(run(on.copy(quietUntil = 500)))
    }

    @Test fun anotherAppOrACallTakesTheMic() {
        assertFalse(WakeRules.micTaken(emptyList(), own = null, inCall = false))
        assertTrue(WakeRules.micTaken(emptyList(), own = null, inCall = true))
        assertTrue(WakeRules.micTaken(listOf(Recording(9, 1)), own = 7, inCall = false))
    }

    @Test fun ourOwnRecordingDoesNotCountUnlessSilenced() {
        assertFalse(WakeRules.micTaken(listOf(Recording(7, 6)), own = 7, inCall = false))
        assertTrue(WakeRules.micTaken(listOf(Recording(7, 6, silenced = true)), own = 7, inCall = false))
    }

    @Test fun thePhonesOwnHotwordListenerIsNotAnotherApp() {
        assertFalse(WakeRules.micTaken(listOf(Recording(9, 1999)), own = 7, inCall = false))
    }

    private fun view(install: WakeInstall = WakeInstall.Installed, s: AssistantSettings = on, allowed: Boolean = true, mic: WakeMicState = WakeMicState.Open, problem: Boolean = false) =
        WakeRules.view(install, s, allowed, mic, problem, now = 1_000)

    @Test fun neverSaysListeningUnlessTheMicIsOpen() {
        assertEquals(WakeView(true, "Listening"), view())
        assertFalse(view(mic = WakeMicState.Closed).note == "Listening")
        assertFalse(view(mic = WakeMicState.Taken).note == "Listening")
        assertFalse(view(mic = WakeMicState.Failed).on)
    }

    @Test fun theDefaultOnSwitchReadsOffUntilItsDownloaded() {
        assertFalse(view(install = WakeInstall.NotInstalled).on)
        assertTrue(view(install = WakeInstall.NotInstalled).note.contains("MB"))
        assertFalse(view(install = WakeInstall.Unsupported).on)
    }

    @Test fun saysWhyItIsntListening() {
        assertEquals("On, paused by Quiet", view(s = on.copy(quietUntil = 2_000)).note)
        assertEquals("Off", view(s = on.copy(wakeWord = false)).note)
        assertFalse(view(allowed = false).on)
        assertFalse(view(problem = true).on)
        assertEquals("Downloading 50%", view(install = WakeInstall.Downloading(5, 10)).note)
    }

    @Test fun everyDownloadIsOfficialAndPinned() {
        WakeFiles.ARCHIVES.forEach { archive ->
            assertTrue(archive.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
            assertTrue(archive.sha256.matches(Regex("[0-9a-f]{64}")))
            archive.files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
        }
        assertEquals("53 MB", WakeFiles.megabytes(WakeFiles.downloadBytes))
        assertEquals("29 MB", WakeFiles.megabytes(WakeFiles.installedBytes))
    }

    @Test fun aPinOnlyMatchesItsExactFile() {
        val file = File.createTempFile("pin", ".bin").apply { writeText("abc"); deleteOnExit() }
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(PinnedFile("a", "a", 3, sha).matches(file))
        assertFalse(PinnedFile("a", "a", 3, "0".repeat(64)).matches(file))
        assertFalse(PinnedFile("a", "a", 4, sha).matches(file))
    }
}
