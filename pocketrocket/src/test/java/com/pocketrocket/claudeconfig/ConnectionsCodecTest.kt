package com.pocketrocket.claudeconfig

import com.pocketrocket.auth.ClaudeAccount
import com.pocketrocket.sessions.protocol.StreamJsonWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionsCodecTest {

    @Test fun roundTrips() {
        val snapshot = ConnectionsSnapshot(
            listOf(
                McpServer("claude.ai Drive", McpState.NeedsAuth, "claudeai", "claudeai-proxy", "mcp.example", "oops", "CODE", 3, "1.0"),
                McpServer("bare", McpState.Connected),
            ),
            ClaudeIdentity(ClaudeAccount("a@b.c", "Org", "max"), "2.1.314"),
            42L,
        )
        assertEquals(snapshot, ConnectionsCodec.decode(ConnectionsCodec.encode(snapshot)))
    }

    @Test fun noIdentityRoundTrips() {
        val snapshot = ConnectionsSnapshot(emptyList(), null, 1L)
        assertEquals(snapshot, ConnectionsCodec.decode(ConnectionsCodec.encode(snapshot)))
    }

    @Test fun rubbishIsNull() {
        assertNull(ConnectionsCodec.decode("not json"))
        assertNull(ConnectionsCodec.decode("{}"))
    }

    @Test fun terminalCommandNamesTheAccountOnlyWhenItMustFor() {
        assertEquals("claude", TerminalFallback.command("/a", "/a"))
        assertEquals("claude", TerminalFallback.command(null, null))
        assertEquals("CLAUDE_CONFIG_DIR='/a' claude", TerminalFallback.command("/a", null))
        assertEquals("env -u CLAUDE_CONFIG_DIR claude", TerminalFallback.command(null, "/a"))
        assertEquals("CLAUDE_CONFIG_DIR='/a'\\''b' claude", TerminalFallback.command("/a'b", null))
    }

    @Test fun writerBuildsAControlRequest() {
        val line = JSONObject(StreamJsonWriter.controlRequest("id", "mcp_toggle", mapOf("serverName" to "x", "enabled" to true)))
        assertEquals("control_request", line.getString("type"))
        assertEquals("id", line.getString("request_id"))
        val request = line.getJSONObject("request")
        assertEquals("mcp_toggle", request.getString("subtype"))
        assertEquals(true, request.getBoolean("enabled"))
    }
}
