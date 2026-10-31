package com.pocketrocket.sessions

import com.pocketrocket.sessions.protocol.StreamJsonParser
import com.pocketrocket.ui.chat.shellState
import com.pocketrocket.ui.chat.stillRunning
import com.pocketrocket.ui.sessions.statusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lines are trimmed copies of what Claude Code 2.1.316 printed in stream-json mode (31 Oct 2026). */
class ShellTasksTest {

    private fun start() = SessionState(
        id = "s", folder = "/w", title = "w", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
    )

    private fun SessionState.feed(vararg lines: String): SessionState =
        lines.fold(this) { state, line -> StreamJsonParser.parse(line).fold(state, SessionReducer::onEvent) }

    private fun SessionState.tool() = items.single() as ChatItem.Tool

    private val out = "/tmp/claude/s/tasks/bgea28m19.output"

    private val bgCall = """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_A","name":"Bash","input":{"command":"sleep 8; echo done-bg","description":"Background sleep then echo marker","run_in_background":true}}]},"parent_tool_use_id":null}"""
    private val bgStarted = """{"type":"system","subtype":"task_started","task_id":"bgea28m19","tool_use_id":"toolu_A","description":"Background sleep then echo marker","is_backgrounded":true,"task_type":"local_bash"}"""
    private val bgResult = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_A","type":"tool_result","content":"Command running in background with ID: bgea28m19. Output is being written to: $out. You will be notified when it completes.","is_error":false}]},"parent_tool_use_id":null,"tool_use_result":{"backgroundTaskId":"bgea28m19"}}"""

    @Test fun backgroundShellRunsPastItsResultUntilItsNotification() {
        var state = ShellTasks.time(start().feed(bgCall, bgStarted, bgResult), 1_000)
        val run = state.tool().shell!!
        assertEquals("bgea28m19", run.taskId)
        assertTrue(run.background)
        assertEquals(out, run.outputFile)
        assertEquals(1_000L, run.startedAt)
        // The result came at once, but the shell still runs: it counts, and the card ticks.
        assertTrue(state.tool().shellRunning)
        assertEquals(1, state.runningShells)
        assertEquals("background · 1:01", shellState(run, running = true, now = 62_000))

        state = state.feed(
            """{"type":"system","subtype":"task_updated","task_id":"bgea28m19","patch":{"status":"completed","end_time":1793436800231}}""",
            """{"type":"system","subtype":"task_notification","task_id":"bgea28m19","tool_use_id":"toolu_A","status":"completed","output_file":"$out","summary":"Background command \"Background sleep then echo marker\" completed (exit code 0)"}""",
        )
        state = ShellTasks.time(state, 9_000)
        val ended = state.tool().shell!!
        assertFalse(state.tool().shellRunning)
        assertEquals(0, state.runningShells)
        assertEquals(0, ended.exitCode)
        assertEquals(9_000L, ended.endedAt)
        assertEquals("✓ · 0:08", shellState(ended, running = false, now = 99_000))
        assertEquals("✓ exit 0 · 0:08", shellState(ended, running = false, now = 99_000, withExit = true))
        // A shell's notification leaves the agent side alone.
        assertNull(state.tool().agent)
    }

    @Test fun failedAndStoppedShells() {
        val failed = start().feed(
            bgCall, bgStarted, bgResult,
            """{"type":"system","subtype":"task_notification","task_id":"bgea28m19","tool_use_id":"toolu_A","status":"failed","output_file":"$out","summary":"Background command \"x\" failed (exit code 1)"}""",
        ).tool().shell!!
        assertTrue(failed.failed)
        assertEquals(1, failed.exitCode)
        assertEquals("✗ exit 1", shellState(failed, running = false, now = 0))

        // Stop from the Background sheet: the CLI says killed, then stopped.
        val stopped = start().feed(
            bgCall, bgStarted, bgResult,
            """{"type":"system","subtype":"task_updated","task_id":"bgea28m19","patch":{"status":"killed","end_time":1793437273809}}""",
            """{"type":"system","subtype":"task_notification","task_id":"bgea28m19","tool_use_id":"toolu_A","status":"stopped","output_file":"$out","summary":"Background sleep then echo marker"}""",
        ).tool().shell!!
        assertTrue(stopped.stopped)
        assertFalse(stopped.failed)
        assertEquals("stopped", shellState(stopped, running = false, now = 0))
    }

    @Test fun foregroundShellMovedToTheBackground() {
        var state = start().feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_F","name":"Bash","input":{"command":"for i in 1 2 3; do echo line ${'$'}i; sleep 1; done","description":"Print lines"}}]},"parent_tool_use_id":null}""",
            """{"type":"system","subtype":"task_started","task_id":"bskvya6c2","tool_use_id":"toolu_F","description":"Print lines","is_backgrounded":false,"task_type":"local_bash"}""",
        )
        assertTrue(state.tool().shellRunning)
        assertFalse(state.tool().backgroundShell)
        assertEquals(0, state.runningShells)
        state = state.feed(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bskvya6c2","task_type":"local_bash","description":"Print lines"}]}""",
            """{"type":"system","subtype":"task_updated","task_id":"bskvya6c2","patch":{"is_backgrounded":true}}""",
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-task-1","response":{"backgrounded":true}}}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_F","type":"tool_result","content":"Command was manually backgrounded by user with ID: bskvya6c2. Output is being written to: /tmp/claude/s/tasks/bskvya6c2.output. If it is still running"}]},"parent_tool_use_id":null}""",
        )
        assertTrue(state.tool().backgroundShell)
        assertTrue(state.tool().shellRunning)
        assertEquals(1, state.runningShells)
        assertEquals("/tmp/claude/s/tasks/bskvya6c2.output", state.tool().shell!!.outputFile)
    }

    @Test fun foregroundShellEndsWithItsResult() {
        val state = start().feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_G","name":"Bash","input":{"command":"sleep 12"}}]},"parent_tool_use_id":null}""",
            """{"type":"system","subtype":"task_started","task_id":"bhgfnvo92","tool_use_id":"toolu_G","description":"Foreground sleep","is_backgrounded":false,"task_type":"local_bash"}""",
            """{"type":"system","subtype":"task_notification","task_id":"bhgfnvo92","tool_use_id":"toolu_G","status":"completed","output_file":"","summary":"Foreground sleep"}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_G","type":"tool_result","content":"(Bash completed with no output)","is_error":false}]},"parent_tool_use_id":null}""",
        )
        val run = state.tool().shell!!
        assertFalse(state.tool().shellRunning)
        assertNull(run.outputFile)
        assertNull(run.exitCode)
        assertFalse(run.failed)
    }

    @Test fun refusedControlRequestSaysSo() {
        val state = start().feed("""{"type":"control_response","response":{"subtype":"error","request_id":"pr-task-3","error":"No task found"}}""")
        assertEquals("Couldn't change the background task: No task found", (state.items.single() as ChatItem.Notice).text)
    }

    @Test fun processEndStopsRunningShells() {
        val state = ShellTasks.time(start().feed(bgCall, bgStarted, bgResult), 1_000)
        val stopped = ShellTasks.stopAll(state, 5_000)
        assertFalse(stopped.tool().shellRunning)
        assertEquals("stopped", stopped.tool().shell!!.status)
        assertEquals(5_000L, stopped.tool().shell!!.endedAt)
        // Nothing running: the same state back.
        assertTrue(ShellTasks.stopAll(stopped, 9_000) === stopped)
    }

    @Test fun subagentTaskLinesStillReachTheAgent() {
        val state = start().feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_A","name":"Agent","input":{"description":"Read a.txt","prompt":"Read a.txt"}}]},"parent_tool_use_id":null}""",
            """{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_A","description":"Reading","task_type":"local_agent"}""",
        )
        assertEquals("Reading", state.tool().agent!!.activity)
        assertNull(state.tool().shell)
    }

    @Test fun outputTailDropsColoursAndRedraws() {
        assertEquals("BUILD SUCCESSFUL\n50% done", ShellOutput.clean("\u001B[32mBUILD SUCCESSFUL\u001B[0m\r\n10%\r50% done"))
        val file = File.createTempFile("shell", ".output")
        try {
            file.writeText((1..300).joinToString("\n") { "line $it" } + "\n\n[exited with code 0]\n")
            val tail = ShellOutput.tail(file.path)!!
            assertEquals(ShellOutput.TAIL_LINES, tail.size)
            assertEquals("[exited with code 0]", tail.last())
            assertEquals(listOf("[exited with code 0]"), ShellOutput.tail(file.path, lines = 1))
        } finally {
            file.delete()
        }
        assertNull(ShellOutput.tail("/nonexistent/x.output"))
    }

    @Test fun idleSessionWithARunningShellShowsAsWorking() {
        val running = start().feed(bgCall, bgStarted, bgResult).copy(status = SessionStatus.Done)
        assertEquals(SessionStatus.Working, running.shownStatus)
        assertEquals("1 shell running", running.statusLabel)
        val ended = ShellTasks.stopAll(running, 1)
        assertEquals(SessionStatus.Done, ended.shownStatus)
        // Needing an answer still wins.
        assertEquals(SessionStatus.WaitingForPermission, running.copy(status = SessionStatus.WaitingForPermission).shownStatus)
    }

    @Test fun countsAndWords() {
        assertEquals("2 background commands and 1 agent", stillRunning(2, 1))
        assertEquals("1 background command", stillRunning(1, 0))
        assertNull(stillRunning(0, 0))
        assertEquals("1 agent · 2 shells", runningWork(1, 2))
    }
}
