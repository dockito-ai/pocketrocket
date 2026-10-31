package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.transport.ClaudeTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeChannelTest {

    private class FakeTransport(private val answer: (id: String) -> String?) : ClaudeTransport {
        val sent = mutableListOf<String>()
        var closes = 0
        var listener: ClaudeTransport.Listener? = null
        override fun start(listener: ClaudeTransport.Listener) { this.listener = listener }
        override fun send(line: String) {
            sent += line
            answer(JSONObject(line).getString("request_id"))?.let { listener?.onLine(it) }
        }
        override fun close() { closes++ }
    }

    private fun success(id: String, body: String = "{}") = """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$body}}"""
    private fun error(id: String, message: String) = """{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"$message"}}"""

    @Test fun sendsControlRequestsAndNothingElse() = runBlocking {
        val transport = FakeTransport { success(it) }
        val probe = ProbeChannel(transport, 60_000).apply { start() }
        probe.mcpStatus()
        probe.mcpToggle("x", false)
        probe.close()
        assertEquals(2, transport.sent.size)
        transport.sent.forEach { assertEquals("control_request", JSONObject(it).getString("type")) }
        val toggle = JSONObject(transport.sent[1]).getJSONObject("request")
        assertEquals("mcp_toggle", toggle.getString("subtype"))
        assertEquals("x", toggle.getString("serverName"))
        assertFalse(toggle.getBoolean("enabled"))
    }

    @Test fun anUnknownRequestIsAFeatureDetectionSignal() = runBlocking {
        val probe = ProbeChannel(FakeTransport { error(it, "Unsupported control request subtype: mcp_status") }, 60_000).apply { start() }
        val reply = probe.mcpStatus()
        probe.close()
        assertTrue((reply as ControlReply.Refused).unsupported)
    }

    @Test fun otherErrorsArentUnsupported() = runBlocking {
        val probe = ProbeChannel(FakeTransport { error(it, "Server not found: x") }, 60_000).apply { start() }
        val reply = probe.mcpReconnect("x")
        probe.close()
        assertFalse((reply as ControlReply.Refused).unsupported)
    }

    @Test fun noAnswerTimesOut() = runBlocking {
        val probe = ProbeChannel(FakeTransport { null }, 60_000).apply { start() }
        val reply = probe.request("mcp_status", timeoutMs = 10)
        probe.close()
        assertTrue(reply is ControlReply.NoAnswer)
    }

    @Test fun aProcessThatStoppedAnswersAtOnce() = runBlocking {
        val transport = FakeTransport { null }
        val probe = ProbeChannel(transport, 60_000).apply { start() }
        transport.listener!!.onExit(1, null)
        assertTrue(probe.mcpStatus() is ControlReply.NoAnswer)
        assertTrue(transport.sent.isEmpty())
        assertEquals(null, probe.identity())
        probe.close()
    }

    @Test fun identityComesFromTheHandshake() = runBlocking {
        val transport = FakeTransport { null }
        val probe = ProbeChannel(transport, 60_000).apply { start() }
        transport.listener!!.onLine(success("pr-init", """{"account":{"email":"a@b.c"},"claude_code_version":"2.1.314"}"""))
        assertEquals("a@b.c", probe.identity()?.account?.email)
        probe.close()
    }

    @Test fun closingTwiceClosesOnce() {
        val transport = FakeTransport { null }
        val probe = ProbeChannel(transport, 60_000).apply { start() }
        probe.close()
        probe.close()
        assertEquals(1, transport.closes)
    }

    @Test fun theWatchdogEndsAForgottenProbe() {
        val transport = FakeTransport { null }
        ProbeChannel(transport, 20).start()
        val deadline = System.currentTimeMillis() + 5_000
        while (transport.closes == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, transport.closes)
    }
}
