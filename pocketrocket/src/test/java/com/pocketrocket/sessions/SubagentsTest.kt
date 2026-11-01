package com.pocketrocket.sessions

import com.pocketrocket.sessions.protocol.StreamJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A subagent's own record (decision 78). Line shapes as Claude Code 2.1.309 printed them (31 Oct 2026). */
class SubagentsTest {

    private fun start() = SessionState(
        id = "s", folder = "/w", title = "w", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
    )

    private fun SessionState.feed(vararg lines: String): SessionState =
        lines.fold(this) { state, line -> StreamJsonParser.parse(line).fold(state, SessionReducer::onEvent) }

    private fun SessionState.tool(id: String) = items.first { it.id == id } as ChatItem.Tool

    private fun agentCall(id: String, description: String, prompt: String, type: String = "general-purpose", background: Boolean = false) =
        """{"type":"assistant","message":{"id":"m-$id","content":[{"type":"tool_use","id":"$id","name":"Agent","input":{"description":"$description","subagent_type":"$type","prompt":"$prompt","run_in_background":$background}}]},"parent_tool_use_id":null}"""

    private fun started(id: String, task: String) =
        """{"type":"system","subtype":"task_started","task_id":"$task","task_type":"local_agent","tool_use_id":"$id","description":"x"}"""

    private fun text(parent: String, text: String) =
        """{"type":"assistant","message":{"id":"m","content":[{"type":"text","text":"$text"}]},"parent_tool_use_id":"$parent"}"""

    private fun use(parent: String, id: String, name: String, path: String) =
        """{"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"$id","name":"$name","input":{"file_path":"$path"}}]},"parent_tool_use_id":"$parent"}"""

    private fun result(parent: String?, id: String, content: String, error: Boolean = false) =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$id","type":"tool_result","content":"$content","is_error":$error}]},"parent_tool_use_id":${parent?.let { "\"$it\"" } ?: "null"}}"""

    private fun ended(id: String, task: String, status: String) =
        """{"type":"system","subtype":"task_notification","task_id":"$task","tool_use_id":"$id","status":"$status","summary":"x"}"""

    @Test fun promptAndTaskAreKept() {
        val state = start().feed(agentCall("toolu_A", "Map the code", "Find the theme files"), started("toolu_A", "task-a"))
        val run = state.tool("toolu_A").agent!!
        assertEquals("Find the theme files", run.prompt)
        assertEquals("general-purpose", run.type)
        assertEquals("task-a", run.taskId)
        assertEquals(AgentState.Running, state.tool("toolu_A").agentState)
    }

    @Test fun twoAgentsAtOnceEachKeepTheirOwnSteps() {
        val state = start().feed(
            agentCall("toolu_A", "First", "a"),
            agentCall("toolu_B", "Second", "b"),
            started("toolu_A", "ta"), started("toolu_B", "tb"),
            text("toolu_A", "A looks first"),
            use("toolu_B", "toolu_B1", "Read", "/w/b.txt"),
            use("toolu_A", "toolu_A1", "Read", "/w/a.txt"),
            result("toolu_A", "toolu_A1", "alpha"),
            text("toolu_B", "B reads"),
            result("toolu_B", "toolu_B1", "no such file", error = true),
        )
        val a = state.tool("toolu_A").agent!!
        val b = state.tool("toolu_B").agent!!
        // Nothing of theirs reaches the main chat.
        assertEquals(2, state.items.size)
        assertEquals(listOf("A looks first", "toolu_A1"), a.log.map { (it as? ChatItem.Assistant)?.text ?: it.id })
        assertEquals("alpha", a.steps.single().result)
        assertEquals(listOf("toolu_B1", "B reads"), b.log.map { (it as? ChatItem.Assistant)?.text ?: it.id })
        assertTrue(b.steps.single().isError)
        assertEquals("a.txt", a.steps.single().summary)
        assertEquals(1, a.tools)
    }

    @Test fun finalAnswerIsTheReportWithoutItsFrame() {
        val state = start().feed(
            agentCall("toolu_A", "First", "a"), started("toolu_A", "ta"),
            text("toolu_A", "Working on it"),
            ended("toolu_A", "ta", "completed"),
            result(null, "toolu_A", "The agent finished. The report follows:\\n  All good.\\n  Two files.\\nagentId: a1"),
        )
        val tool = state.tool("toolu_A")
        assertEquals(AgentState.Done, tool.agentState)
        assertEquals("All good.\nTwo files.", Subagents.finalAnswer(tool))
    }

    @Test fun backgroundAgentAnswersWithItsLastMessage() {
        var state = start().feed(agentCall("toolu_A", "First", "a", background = true), started("toolu_A", "ta"))
        state = SessionReducer.timeAgents(state, 1_000).feed(result(null, "toolu_A", "Async agent launched successfully."))
        assertNull(Subagents.finalAnswer(state.tool("toolu_A")))
        state = state.feed(text("toolu_A", "Half way"), text("toolu_A", "Done: 3 files"), ended("toolu_A", "ta", "completed"))
        assertEquals("Done: 3 files", Subagents.finalAnswer(state.tool("toolu_A")))
    }

    @Test fun aStoppedAgentReadsStoppedWithNoAnswer() {
        // stop_task on 2.1.309: the agent's notification says stopped, then its call's result is an interruption.
        val state = start().feed(
            agentCall("toolu_A", "First", "a"), started("toolu_A", "ta"),
            text("toolu_A", "Started"),
            use("toolu_A", "toolu_A1", "Read", "/w/a.txt"),
            ended("toolu_A", "ta", "stopped"),
            result(null, "toolu_A", "[Request interrupted by user for tool use]", error = true),
        )
        val tool = state.tool("toolu_A")
        assertEquals(AgentState.Stopped, tool.agentState)
        assertNull(Subagents.finalAnswer(tool))
        assertEquals(0, state.runningAgents)
        assertTrue(tool.agent!!.steps.single().isError)
    }

    @Test fun aLongAgentKeepsOnlyItsLatestSteps() {
        val extra = 5
        val lines = (1..Subagents.LOG_KEEP + extra).map { use("toolu_A", "toolu_A$it", "Read", "/w/$it.txt") }
        val state = start().feed(agentCall("toolu_A", "Long", "a"), *lines.toTypedArray(), result("toolu_A", "toolu_A${Subagents.LOG_KEEP + extra}", "x".repeat(Subagents.RESULT_KEEP + 10)))
        val run = state.tool("toolu_A").agent!!
        assertEquals(Subagents.LOG_KEEP, run.log.size)
        assertEquals(extra, run.dropped)
        assertEquals("toolu_A${extra + 1}", run.log.first().id)
        assertEquals(Subagents.LOG_KEEP + extra, run.tools)
        assertTrue(run.steps.last().result!!.endsWith("… 10 more characters not kept"))
        // Message ids stay unique after the oldest go.
        val more = state.feed(text("toolu_A", "one"), text("toolu_A", "two")).tool("toolu_A").agent!!
        assertEquals(more.log.size, more.log.map { it.id }.toSet().size)
        assertTrue(Subagents.transcript(state.tool("toolu_A")).contains("(5 earlier steps not kept)"))
    }

    @Test fun copyAllReadsAsText() {
        val state = start().feed(
            agentCall("toolu_A", "Map the code", "Find the theme", type = "Explore"), started("toolu_A", "ta"),
            text("toolu_A", "Looking"),
            use("toolu_A", "toolu_A1", "Read", "/w/a.txt"),
            result("toolu_A", "toolu_A1", "line one\\nline two"),
            ended("toolu_A", "ta", "completed"),
            result(null, "toolu_A", "Found it."),
        )
        assertEquals(
            "Agent (Explore): Map the code\n\nPrompt:\nFind the theme\n\nLooking\n\n● Read a.txt\n  ⎿ line one\n    line two\n\nFinal answer:\nFound it.\n",
            Subagents.transcript(state.tool("toolu_A")),
        )
        assertEquals("1 earlier step not kept", Subagents.earlier(1))
    }

    private fun liveAgents(vararg tasks: Pair<String, String>) =
        """{"type":"system","subtype":"background_tasks_changed","tasks":[${tasks.joinToString(",") { (id, type) -> """{"task_id":"$id","task_type":"$type","description":"x"}""" }}]}"""

    private fun launched(id: String, agentId: String) = result(null, id, "Async agent launched successfully.\\nagentId: $agentId (internal ID)")

    @Test fun anEndedAgentSentAMessageRunsAgainOnItsCard() {
        var state = start().feed(
            agentCall("toolu_A", "Sweep", "a", background = true), started("toolu_A", "a1"), launched("toolu_A", "a1"),
            ended("toolu_A", "a1", "killed"),
        )
        assertEquals(AgentState.Stopped, state.tool("toolu_A").agentState)
        assertEquals(0, state.runningAgents)

        // SendMessage brings it back: the CLI starts the task again under the first tool use.
        state = SessionReducer.timeAgents(state.feed(started("toolu_A", "a1")), now = 5_000)
        val run = state.tool("toolu_A").agent!!
        assertEquals(AgentState.Running, state.tool("toolu_A").agentState)
        assertEquals(1, state.runningAgents)
        assertNull(run.status)
        assertEquals(5_000L, run.startedAt)

        state = state.feed(ended("toolu_A", "a1", "completed"))
        assertEquals(AgentState.Done, state.tool("toolu_A").agentState)
        assertEquals(0, state.runningAgents)
    }

    @Test fun theLiveTaskListBringsBackAnAgentStoppedByARestart() {
        // A restart stopped the card; its task id is only in the launch result.
        val stopped = SessionReducer.stopAgents(
            start().feed(agentCall("toolu_A", "Sweep", "a", background = true), launched("toolu_A", "a1")), now = 0,
        )
        assertEquals("a1", stopped.tool("toolu_A").agent!!.taskId)
        assertEquals(0, stopped.runningAgents)

        // A shell with that id, or another agent, does not count.
        assertEquals(0, stopped.feed(liveAgents("a1" to "local_bash", "a2" to "local_agent")).runningAgents)

        val live = stopped.feed(liveAgents("a1" to "local_agent"))
        assertEquals(AgentState.Running, live.tool("toolu_A").agentState)
        assertEquals(1, live.runningAgents)
    }

    @Test fun aRunningAgentIsLeftAsItIsByTheLiveTaskList() {
        val running = start().feed(agentCall("toolu_A", "Sweep", "a", background = true), started("toolu_A", "a1"))
        assertEquals(running, running.feed(liveAgents("a1" to "local_agent")))
    }
}
