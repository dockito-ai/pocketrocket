package com.pocketrocket.claudeconfig

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes measured with Claude Code 2.1.314 (throwaway config, fake secrets, 30 Oct 2026). */
class McpParserTest {

    private val withSecrets = JSONObject(
        """{"mcpServers":[
            {"name":"probe-http","status":"failed",
             "error":"TypeError dialing http://127.0.0.1:9/mcp?token=URLSECRET said HEADERSECRET (ECONNREFUSED)",
             "config":{"type":"http","url":"http://127.0.0.1:9/mcp?token=URLSECRET","headers":{"Authorization":"Bearer HEADERSECRET"}},
             "transport":"http","scope":"user","source":"user"},
            {"name":"probe-stdio","status":"connected",
             "config":{"type":"stdio","command":"node","args":["s.js","--api-key","ARGSECRET"],"env":{"K":"ENVSECRET"}},
             "transport":"stdio","scope":"local",
             "tools":[{"name":"a","description":"TOOLDESCRIPTION"},{"name":"b"}],
             "serverInfo":{"name":"x","version":"1.2"}}
        ]}""",
    )

    private val secrets = listOf("URLSECRET", "HEADERSECRET", "ARGSECRET", "ENVSECRET", "TOOLDESCRIPTION")

    @Test fun secretFieldsDontSurviveParsing() {
        val servers = McpParser.status(withSecrets)!!
        val everything = servers.toString() + servers.map { it.displayName + it.sourceLabel + it.error }
        secrets.forEach { assertFalse("$it survived", everything.contains(it)) }
    }

    @Test fun secretFieldsDontReachTheCache() {
        val saved = ConnectionsCodec.encode(ConnectionsSnapshot(McpParser.status(withSecrets)!!, null, 1L))
        secrets.forEach { assertFalse("$it was saved", saved.contains(it)) }
    }

    @Test fun errorsKeepTheirMeaningWithoutTheSecret() {
        val error = McpParser.status(withSecrets)!![0].error!!
        assertTrue(error.contains("ECONNREFUSED"))
        assertTrue(error.contains("http://127.0.0.1:9/mcp"))
        assertTrue(error.contains("[hidden]"))
    }

    @Test fun wantedFieldsAreKept() {
        val stdio = McpParser.status(withSecrets)!![1]
        assertEquals(McpState.Connected, stdio.state)
        assertEquals(2, stdio.toolCount)
        assertEquals("1.2", stdio.serverVersion)
        assertEquals("This folder", stdio.sourceLabel)
    }

    @Test fun oddRepliesDontThrow() {
        assertNull(McpParser.status(JSONObject()))
        val servers = McpParser.status(JSONObject("""{"mcpServers":[{"status":"connected"},{"name":"new","status":"sleeping"},3]}"""))!!
        assertEquals(McpState.Unknown, servers.single().state)
    }

    @Test fun connectorNamesAndKinds() {
        val connector = McpServer("claude.ai Google Drive", McpState.NeedsAuth, scope = "claudeai", transport = "claudeai-proxy")
        assertTrue(connector.isConnector)
        assertEquals("Google Drive", connector.displayName)
        val plugin = McpServer("plugin:design:slack", McpState.NeedsAuth)
        assertTrue(plugin.isPlugin)
        assertEquals("design", plugin.pluginName)
        assertEquals("slack", plugin.displayName)
    }

    @Test fun connectorSignInReply() {
        val start = McpParser.authStart(JSONObject("""{"requiresUserAction":true,"callbackExpected":false,"authUrl":"https://claude.ai/api/organizations/o/mcp/start-auth/i?product_surface=x"}"""))
        assertTrue(start.url!!.startsWith("https://claude.ai/"))
        assertFalse(start.callbackExpected)
        assertFalse("the URL stays out of logs", start.toString().contains("claude.ai"))
    }

    @Test fun aLinkThatIsntWebIsNotOpened() {
        assertNull(McpParser.authStart(JSONObject("""{"requiresUserAction":true,"authUrl":"file:///etc/passwd"}""")).url)
    }
}
