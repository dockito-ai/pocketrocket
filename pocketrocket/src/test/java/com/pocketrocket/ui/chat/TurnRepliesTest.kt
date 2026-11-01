package com.pocketrocket.ui.chat

import com.pocketrocket.sessions.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnRepliesTest {
    private fun user(id: String) = ChatItem.User(id, "q")
    private fun text(id: String, t: String, streaming: Boolean = false) = ChatItem.Assistant(id, t, streaming = streaming)
    private fun tool(id: String) = ChatItem.Tool(id, "Bash", "ls", "{}", result = "ok")

    @Test fun `a turn's text is joined under its last reply, tools left out`() {
        val items = listOf(user("u1"), text("a1", "Looking."), tool("t1"), text("a2", "Done.\n"), user("u2"), text("a3", "Hi"))
        assertEquals(mapOf("a2" to "Looking.\n\nDone.", "a3" to "Hi"), TurnReplies.copyable(items, working = false))
    }

    @Test fun `the turn still running gets no button`() {
        val items = listOf(user("u1"), text("a1", "One"), user("u2"), text("a2", "Two"))
        assertEquals(mapOf("a1" to "One"), TurnReplies.copyable(items, working = true))
    }

    @Test fun `streaming text and empty turns get no button`() {
        assertEquals(emptyMap<String, String>(), TurnReplies.copyable(listOf(user("u1"), text("a1", "Par", streaming = true)), working = false))
        assertEquals(emptyMap<String, String>(), TurnReplies.copyable(listOf(user("u1"), tool("t1"), user("u2")), working = false))
    }
}
