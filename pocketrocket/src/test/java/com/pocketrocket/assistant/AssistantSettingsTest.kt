package com.pocketrocket.assistant

import com.pocketrocket.profiles.AccountList
import com.pocketrocket.profiles.ClaudeAccountEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSettingsTest {

    private val accounts = AccountList(listOf(ClaudeAccountEntry("main", "personal"), ClaudeAccountEntry("work", "work")), "main")

    @Test fun startsOffWithHeyRocketOffAndTheBrainOnSonnet() {
        val d = AssistantSettings()
        // Decision 110: hidden and off.
        assertFalse(d.enabled)
        // Decision 62: off until he switches it on. Decision 61: Sonnet.
        assertFalse(d.wakeWord)
        assertEquals("sonnet", d.model)
        assertFalse(d.speaker)
        assertFalse(d.isQuiet())
    }

    @Test fun quietEndsAtItsTime() {
        val s = AssistantSettings(quietUntil = 1_000)
        assertTrue(s.isQuiet(now = 999))
        assertFalse(s.isQuiet(now = 1_000))
    }

    @Test fun loginOffersDefaultThenEveryAccount() {
        assertEquals(listOf(null to "Default (personal)", "main" to "personal", "work" to "work"), AssistantSettings().loginChoices(accounts))
    }

    @Test fun aRemovedLoginFallsBackToDefault() {
        val gone = AssistantSettings(loginId = "gone")
        assertEquals("Default (personal)", gone.loginLabel(accounts))
        assertEquals("personal", gone.loginName(accounts))
        assertEquals("work", AssistantSettings(loginId = "work").loginName(accounts))
    }
}
