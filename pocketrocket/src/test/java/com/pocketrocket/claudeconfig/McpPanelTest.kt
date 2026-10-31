package com.pocketrocket.claudeconfig

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPanelTest {

    /** Answers from a script per subtype; the last answer repeats. No script: unknown to this "version". */
    private class FakeChannel(private val script: Map<String, List<ControlReply>>) : ControlChannel {
        val asked = mutableListOf<String>()
        override suspend fun request(subtype: String, fields: Map<String, Any>, timeoutMs: Long): ControlReply {
            val index = asked.count { it == subtype }
            asked += subtype
            val answers = script[subtype] ?: return ControlReply.Refused("${ControlReply.UNSUPPORTED}: $subtype")
            return answers[minOf(index, answers.lastIndex)]
        }
    }

    private class FakeHost(override val live: Boolean, val channel: FakeChannel, val fresh: McpState? = null) : McpHost {
        var opened = 0
        var closed = 0
        override suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T {
            opened++
            try {
                return block(McpConnection(channel, 0L, null))
            } finally {
                closed++
            }
        }
        override suspend fun freshStatus(serverName: String): McpState? = fresh
    }

    private fun servers(vararg entries: String) = ControlReply.Success(JSONObject("""{"mcpServers":[${entries.joinToString(",")}]}"""))
    private fun connector(status: String) = """{"name":"claude.ai Drive","status":"$status","scope":"claudeai","transport":"claudeai-proxy"}"""
    private fun local(status: String) = """{"name":"mine","status":"$status","scope":"user","transport":"stdio"}"""
    private val done = ControlReply.Success(JSONObject())
    private val driveUrl = ControlReply.Success(JSONObject("""{"requiresUserAction":true,"callbackExpected":false,"authUrl":"https://claude.ai/start"}"""))
    private val drive = "claude.ai Drive"

    /** Runs [block] to the end of everything it starts, on a fake clock that [McpPanel]'s pauses move. */
    private fun run(host: McpHost, startAt: Long = 100_000, initial: McpPanelState = McpPanelState(), block: (McpPanel) -> Unit): McpPanelState {
        var time = startAt
        lateinit var panel: McpPanel
        runBlocking {
            panel = McpPanel(host, this, initial, now = { time }, pause = { time += it })
            block(panel)
        }
        return panel.state.value
    }

    @Test fun refreshesUntilNothingIsPending() {
        val channel = FakeChannel(mapOf("mcp_status" to listOf(servers(connector("pending")), servers(connector("pending")), servers(connector("needs-auth")))))
        val state = run(FakeHost(true, channel)) { it.refresh() }
        assertEquals(3, channel.asked.size)
        assertTrue(state.settled)
        assertFalse(state.refreshing)
        assertEquals(McpState.NeedsAuth, state.servers.single().state)
    }

    @Test fun waitsForConnectorsButNotForever() {
        val channel = FakeChannel(mapOf("mcp_status" to listOf(servers(local("connected")))))
        val state = run(FakeHost(false, channel), startAt = 0) { it.refresh() }
        assertTrue(state.settled)
        assertTrue("asked ${channel.asked.size} times", channel.asked.size in 3..12)
    }

    @Test fun stuckServersStopAtTheCap() {
        val channel = FakeChannel(mapOf("mcp_status" to listOf(servers(connector("pending")))))
        val state = run(FakeHost(true, channel)) { it.refresh() }
        assertTrue(state.settled)
        assertTrue("asked ${channel.asked.size} times", channel.asked.size < 20)
    }

    @Test fun unsupportedStatusIsNoted() {
        val state = run(FakeHost(true, FakeChannel(emptyMap()))) { it.refresh() }
        assertTrue(state.statusUnsupported)
        assertFalse(state.refreshing)
    }

    @Test fun noAnswerEndsInAProblemNotACrash() {
        val state = run(FakeHost(true, FakeChannel(mapOf("mcp_status" to listOf(ControlReply.NoAnswer("gone")))))) { it.refresh() }
        assertEquals("gone", state.statusProblem)
    }

    @Test fun aHostThatThrowsEndsInAProblem() {
        val host = object : McpHost {
            override val live = false
            override suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T = throw IllegalStateException("boom")
            override suspend fun freshStatus(serverName: String): McpState? = null
        }
        assertNotNull(run(host) { it.refresh() }.statusProblem)
    }

    @Test fun signInOpensTheLinkAndOffersRecheck() {
        val channel = FakeChannel(mapOf("mcp_authenticate" to listOf(driveUrl)))
        var opened: String? = null
        val state = run(FakeHost(true, channel)) { it.signIn(drive) { url -> opened = url; true } }
        assertEquals("https://claude.ai/start", opened)
        assertTrue(drive in state.awaitingBrowser)
        assertTrue(state.busy.isEmpty())
    }

    @Test fun probeWaitsForTheConnectorToBeListedBeforeSignIn() {
        val channel = FakeChannel(mapOf(
            "mcp_status" to listOf(servers(local("connected")), servers(local("connected"), connector("needs-auth"))),
            "mcp_authenticate" to listOf(driveUrl),
        ))
        val host = FakeHost(false, channel)
        run(host) { it.signIn(drive) { true } }
        assertEquals(listOf("mcp_status", "mcp_status", "mcp_authenticate"), channel.asked)
        assertEquals(host.opened, host.closed)
    }

    @Test fun signInThatCantStartOffersTheTerminalAndHidesTheAction() {
        val server = McpServer(drive, McpState.NeedsAuth, scope = "claudeai")
        val state = run(FakeHost(true, FakeChannel(emptyMap()))) { it.signIn(drive) { true } }
        assertTrue(state.notices[drive]!!.offerTerminal)
        assertFalse(state.canSignIn(server))
    }

    @Test fun noBrowserOffersTheTerminal() {
        val state = run(FakeHost(true, FakeChannel(mapOf("mcp_authenticate" to listOf(driveUrl))))) { it.signIn(drive) { false } }
        assertTrue(state.notices[drive]!!.offerTerminal)
        assertFalse(drive in state.awaitingBrowser)
    }

    @Test fun recheckConnectedInThisSession() {
        val channel = FakeChannel(mapOf("mcp_reconnect" to listOf(done), "mcp_status" to listOf(servers(connector("connected")))))
        val state = run(FakeHost(true, channel)) { it.recheck(drive) }
        assertEquals(ServerNotice.Kind.Success, state.notices[drive]!!.kind)
    }

    @Test fun recheckSignedInButThisSessionCantSeeItOffersANewSession() {
        val channel = FakeChannel(mapOf("mcp_reconnect" to listOf(ControlReply.Refused("Server status: needs-auth")), "mcp_status" to listOf(servers(connector("needs-auth")))))
        val state = run(FakeHost(true, channel, fresh = McpState.Connected)) { it.recheck(drive) }
        assertTrue(state.notices[drive]!!.offerNewSession)
    }

    @Test fun recheckStillSignedOutNamesTheEmail() {
        val channel = FakeChannel(mapOf("mcp_reconnect" to listOf(done), "mcp_status" to listOf(servers(connector("needs-auth")))))
        val known = McpPanelState(identity = ClaudeIdentity(com.pocketrocket.auth.ClaudeAccount("a@b.c", null, null), null))
        val state = run(FakeHost(true, channel, fresh = McpState.NeedsAuth), initial = known) { it.recheck(drive) }
        assertTrue(state.notices[drive]!!.text.contains("a@b.c"))
    }

    @Test fun recheckOnAProbeIsANewProcessThatEnds() {
        val channel = FakeChannel(mapOf("mcp_status" to listOf(servers(connector("connected")))))
        val host = FakeHost(false, channel)
        val state = run(host) { it.recheck(drive) }
        assertEquals(ServerNotice.Kind.Success, state.notices[drive]!!.kind)
        assertEquals(1, host.opened)
        assertEquals(1, host.closed)
        assertFalse("a probe doesn't reconnect", "mcp_reconnect" in channel.asked)
    }

    @Test fun toggleSaysItIsPerFolder() {
        val channel = FakeChannel(mapOf("mcp_toggle" to listOf(done), "mcp_status" to listOf(servers(local("disabled")))))
        val state = run(FakeHost(true, channel)) { it.setEnabled("mine", false) }
        assertTrue(state.notices["mine"]!!.text.contains("folder"))
    }

    @Test fun whatEachPlaceOffers() {
        val live = McpPanelState(live = true)
        val probe = McpPanelState(live = false)
        val mine = McpServer("mine", McpState.Connected, scope = "user")
        val connected = McpServer(drive, McpState.Connected, scope = "claudeai")
        val plugin = McpServer("plugin:p:s", McpState.Connected)
        assertTrue(live.canToggle(mine))
        assertFalse("connectors weren't measured", live.canToggle(connected))
        assertFalse("plugin servers weren't measured", live.canToggle(plugin))
        assertTrue("an off connector can come back on", live.canToggle(connected.copy(state = McpState.Disabled)))
        assertFalse(probe.canToggle(mine))
        assertFalse(probe.canReconnect(mine))
        assertTrue(live.canReconnect(mine))
        assertFalse(live.copy(unsupported = setOf(ControlRequests.MCP_RECONNECT)).canReconnect(mine))
        assertFalse(live.copy(unsupported = setOf(ControlRequests.MCP_TOGGLE)).canToggle(mine))
    }

    @Test fun aProbeDoesOneThingAtATime() {
        val channel = FakeChannel(mapOf("mcp_status" to listOf(servers(connector("needs-auth"))), "mcp_authenticate" to listOf(driveUrl)))
        run(FakeHost(false, channel)) {
            it.signIn(drive) { true }
            it.signIn("another") { true }
        }
        assertEquals(1, channel.asked.count { asked -> asked == "mcp_authenticate" })
    }
}
