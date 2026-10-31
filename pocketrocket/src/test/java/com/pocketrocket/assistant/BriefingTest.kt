package com.pocketrocket.assistant

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingTest {

    private fun session(status: SessionStatus, tool: String? = null) = SessionState(
        id = "a", folder = "~/a", title = "a", demo = false, profile = ClaudeProfile.Default, conversationId = "c", status = status,
        permission = tool?.let { asked(it) },
    )

    private fun asked(tool: String) = PendingPermission("r", tool, "s", JSONObject(), null)

    @Test fun theHeadlinePutsWhatNeedsYouFirst() {
        val needs = session(SessionStatus.WaitingForPermission, "Bash")
        val failed = session(SessionStatus.Error)
        val working = session(SessionStatus.Working)
        val done = session(SessionStatus.Done)
        assertEquals("One thing needs you.", Briefing.headline(listOf(done, failed, needs)))
        assertEquals("Two things need you.", Briefing.headline(listOf(needs, needs)))
        assertEquals("12 things need you.", Briefing.headline(List(12) { needs }))
        assertEquals("One session failed.", Briefing.headline(listOf(working, failed)))
        assertEquals("Two sessions are working.", Briefing.headline(listOf(working, working, done)))
        assertEquals("All clear for now.", Briefing.headline(listOf(done, session(SessionStatus.Paused))))
        assertEquals("No sessions yet.", Briefing.headline(emptyList()))
    }

    @Test fun requestsAreSaidInWords() {
        assertEquals("x wants to run a command", Briefing.request("x", asked("Bash")))
        assertEquals("x has a plan to approve", Briefing.request("x", asked("ExitPlanMode")))
        assertEquals("x wants to use mcp__db__query", Briefing.request("x", asked("mcp__db__query")))
    }
}
