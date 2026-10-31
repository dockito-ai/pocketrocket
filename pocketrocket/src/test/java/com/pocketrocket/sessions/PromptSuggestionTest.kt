package com.pocketrocket.sessions

import com.pocketrocket.sessions.protocol.StreamJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suggestion line is a copy of what Claude Code 2.1.316 printed with `--prompt-suggestions` (31 Oct 2026). */
class PromptSuggestionTest {

    private fun start() = SessionState(
        id = "s", folder = "/w", title = "w", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
    )

    private fun SessionState.feed(vararg lines: String): SessionState =
        lines.fold(this) { state, line -> StreamJsonParser.parse(line).fold(state, SessionReducer::onEvent) }

    private val reply = """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Should I proceed?"}]}}"""
    private val result = """{"type":"result","subtype":"success","is_error":false,"result":"Should I proceed?","session_id":"c"}"""
    private val suggestion = """{"type":"prompt_suggestion","suggestion":"go ahead","uuid":"1529ed89-b4ba-4e00-8f1f-9ecb0bda12b8"}"""

    @Test fun suggestionAfterTheTurnIsKept() {
        val state = start().feed(reply, result, suggestion)
        assertEquals("go ahead", state.suggestion)
        assertEquals(SessionStatus.Done, state.status)
    }

    @Test fun sendingAnythingDropsIt() {
        val state = SessionReducer.onUserMessage(start().feed(reply, result, suggestion), "go ahead", uuid = "u1")
        assertNull(state.suggestion)
        assertTrue(state.items.last() is ChatItem.User)
    }

    @Test fun aTurnClaudeStartsItselfDropsIt() {
        // A background task ended while idle: Claude Code reports it in a turn of its own.
        val state = start().feed(reply, result, suggestion, """{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"The build passed."}]}}""")
        assertNull(state.suggestion)
    }

    @Test fun blankSuggestionIsIgnored() {
        assertTrue(StreamJsonParser.parse("""{"type":"prompt_suggestion","suggestion":"  "}""").isEmpty())
    }
}
