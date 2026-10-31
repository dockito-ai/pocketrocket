package com.pocketrocket.notifications

import com.pocketrocket.notifications.SessionNotifier.Reaction
import com.pocketrocket.sessions.AgentRun
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNotifierTest {

    private fun state(status: SessionStatus, agents: Int = 0, ask: String? = null) = SessionState(
        id = "s", folder = "/w", title = "w", demo = false, profile = ClaudeProfile.Default, conversationId = "c", status = status,
        items = (1..agents).map { ChatItem.Tool("a$it", "Agent", "explore", "{}", agent = AgentRun("explore")) },
        permission = ask?.let { PendingPermission(it, "Bash", "ls", JSONObject(), null) },
    )

    /** Feeds [states] in order and keeps what each change did. */
    private fun reactions(vararg states: SessionState) = states.toList().zipWithNext { a, b -> SessionNotifier.reaction(a, b) }

    @Test fun turnEndsWithAnAgentRunningThenTheAgentFinishesGivesOneDone() {
        val seen = reactions(
            state(SessionStatus.Working),
            state(SessionStatus.Working, agents = 1),
            state(SessionStatus.Done, agents = 1),
            state(SessionStatus.Done, agents = 1),
            state(SessionStatus.Done),
            state(SessionStatus.Done),
        )
        assertEquals(listOf(null, null, null, Reaction.Finished, null), seen)
    }

    @Test fun plainTurnEndIsDoneAtOnce() {
        assertEquals(Reaction.Finished, SessionNotifier.reaction(state(SessionStatus.Working), state(SessionStatus.Done)))
    }

    @Test fun anErrorIsToldAtOnceEvenWithAgentsRunning() {
        assertEquals(Reaction.Finished, SessionNotifier.reaction(state(SessionStatus.Working, agents = 1), state(SessionStatus.Error, agents = 1)))
    }

    @Test fun questionsStillNotifyAndAnsweredOnesGoAway() {
        assertEquals(Reaction.NeedsYou, SessionNotifier.reaction(state(SessionStatus.Working, agents = 1), state(SessionStatus.WaitingForPermission, agents = 1, ask = "r1")))
        assertEquals(Reaction.NeedsYou, SessionNotifier.reaction(state(SessionStatus.WaitingForPermission, ask = "r1"), state(SessionStatus.WaitingForPermission, ask = "r2")))
        assertEquals(Reaction.Cancel, SessionNotifier.reaction(state(SessionStatus.WaitingForPermission, ask = "r1"), state(SessionStatus.Working)))
        // Answered, and the turn ended with an agent still running: the question goes, "is done" waits.
        assertEquals(Reaction.Cancel, SessionNotifier.reaction(state(SessionStatus.WaitingForPermission, agents = 1, ask = "r1"), state(SessionStatus.Done, agents = 1)))
        // Answered and the turn ended with nothing running: done replaces the question.
        assertEquals(Reaction.Finished, SessionNotifier.reaction(state(SessionStatus.WaitingForPermission, ask = "r1"), state(SessionStatus.Done)))
    }
}
