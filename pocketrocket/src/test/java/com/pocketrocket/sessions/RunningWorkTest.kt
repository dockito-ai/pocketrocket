package com.pocketrocket.sessions

import com.pocketrocket.ui.sessions.statusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision 79: a session isn't done while its subagents or background shells still run. */
class RunningWorkTest {

    private fun state(status: SessionStatus, agents: Int = 0, shells: Int = 0): SessionState {
        val items = (1..agents).map { ChatItem.Tool("a$it", "Agent", "explore", "{}", agent = AgentRun("explore $it")) } +
            (1..shells).map { ChatItem.Tool("b$it", "Bash", "build", "{}", result = "running", shell = ShellRun("t$it", background = true)) }
        return SessionState(
            id = "s", folder = "/w", title = "w", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
            status = status, items = items,
        )
    }

    @Test fun finishedTurnWithAgentsOrShellsRunningShowsAsWorking() {
        assertEquals(SessionStatus.Working, state(SessionStatus.Done, agents = 1).shownStatus)
        assertEquals(SessionStatus.Working, state(SessionStatus.Done, shells = 1).shownStatus)
        assertEquals(SessionStatus.Working, state(SessionStatus.WaitingForInput, agents = 2).shownStatus)
        assertEquals(SessionStatus.Done, state(SessionStatus.Done).shownStatus)
        // A question, an error and a pause still show as they are.
        assertEquals(SessionStatus.WaitingForPermission, state(SessionStatus.WaitingForPermission, agents = 1).shownStatus)
        assertEquals(SessionStatus.Error, state(SessionStatus.Error, agents = 1).shownStatus)
        assertEquals(SessionStatus.Paused, SessionStatus.Paused.shownWith(3))
    }

    @Test fun finishedAgentNoLongerCounts() {
        val ended = state(SessionStatus.Done, agents = 1).let { s ->
            s.copy(items = s.items.map { (it as ChatItem.Tool).copy(agent = it.agent!!.copy(status = "completed")) })
        }
        assertEquals(0, ended.runningAgents)
        assertEquals(SessionStatus.Done, ended.shownStatus)
    }

    @Test fun cardLabelSaysWhatStillRuns() {
        assertEquals("1 agent running", state(SessionStatus.Done, agents = 1).statusLabel)
        assertEquals("2 agents running", state(SessionStatus.Done, agents = 2).statusLabel)
        assertEquals("1 shell running", state(SessionStatus.Done, shells = 1).statusLabel)
        assertEquals("2 shells running", state(SessionStatus.Done, shells = 2).statusLabel)
        assertEquals("1 agent · 2 shells running", state(SessionStatus.Done, agents = 1, shells = 2).statusLabel)
        // Mid-turn it's just Working, and with nothing left running it's Done.
        assertEquals("Working", state(SessionStatus.Working, agents = 1).statusLabel)
        assertEquals("Done", state(SessionStatus.Done).statusLabel)
    }

    @Test fun runningWorkWords() {
        assertEquals("", runningWork(0, 0))
        assertEquals("1 agent", runningWork(1, 0))
        assertEquals("3 shells", runningWork(0, 3))
        assertEquals("2 agents · 1 shell", runningWork(2, 1))
    }

    @Test fun doneWaitsForTheLastAgentOrShell() {
        val working = state(SessionStatus.Working, agents = 1)
        // The turn ends while the agent still runs: nothing yet.
        val turnEnded = state(SessionStatus.Done, agents = 1)
        assertEquals(null, SessionNews.of(working, turnEnded))
        // Output from the agent while it runs: still nothing.
        assertEquals(null, SessionNews.of(turnEnded, turnEnded.copy(title = "w2")))
        // The agent finishes: done, once.
        val finished = state(SessionStatus.Done)
        assertEquals(SessionNews.Done, SessionNews.of(turnEnded, finished))
        assertEquals(null, SessionNews.of(finished, finished))
        // Agent and shell: done only when the second one ends.
        val both = state(SessionStatus.Done, agents = 1, shells = 1)
        assertEquals(null, SessionNews.of(both, state(SessionStatus.Done, shells = 1)))
        assertEquals(SessionNews.Done, SessionNews.of(state(SessionStatus.Done, shells = 1), finished))
    }

    @Test fun plainTurnEndAndErrorsStillTellAtOnce() {
        assertEquals(SessionNews.Done, SessionNews.of(state(SessionStatus.Working), state(SessionStatus.Done)))
        assertEquals(SessionNews.Error, SessionNews.of(state(SessionStatus.Working, agents = 1), state(SessionStatus.Error, agents = 1)))
        assertEquals(null, SessionNews.of(state(SessionStatus.Done), state(SessionStatus.Error)))
        assertEquals(null, SessionNews.of(state(SessionStatus.Paused), state(SessionStatus.Done)))
    }

    @Test fun questionsAreNewsWhileAgentsRun() {
        val asked = state(SessionStatus.WaitingForPermission, agents = 1)
            .copy(permission = PendingPermission("r1", "Bash", "ls", org.json.JSONObject(), null))
        assertEquals(SessionNews.Asked, SessionNews.of(state(SessionStatus.Working, agents = 1), asked))
        assertEquals(SessionNews.Ready, SessionNews.of(state(SessionStatus.Working), state(SessionStatus.WaitingForInput)))
    }
}
