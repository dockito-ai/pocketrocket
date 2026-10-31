package com.pocketrocket.claudeconfig

/** A line under one server: how the last action went, and what to offer next. */
data class ServerNotice(
    val text: String,
    val kind: Kind,
    val offerTerminal: Boolean = false,
    val offerNewSession: Boolean = false,
) {
    enum class Kind { Info, Success, Problem }
}

/** What is under way for one server. */
enum class McpBusy { SignIn, WaitingForBrowser, Recheck, Reconnect, Toggle }

/** Everything a server list shows. Immutable; [McpPanel] publishes a new copy per change. */
data class McpPanelState(
    /** Asking a running session, so Reconnect and on/off mean something. False for a short-lived probe. */
    val live: Boolean = false,
    val identity: ClaudeIdentity? = null,
    val servers: List<McpServer> = emptyList(),
    /** At least one list has arrived (or came from the cache). */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Servers have finished loading; see [McpRefresh]. */
    val settled: Boolean = false,
    val checkedAt: Long? = null,
    val statusProblem: String? = null,
    /** Control requests this Claude Code refused as unknown: their actions are hidden. */
    val unsupported: Set<String> = emptySet(),
    val busy: Map<String, McpBusy> = emptyMap(),
    val notices: Map<String, ServerNotice> = emptyMap(),
    /** Sign-in opened in the browser and not seen connected yet: these offer Re-check. */
    val awaitingBrowser: Set<String> = emptySet(),
) {
    val listing: McpListing get() = McpGroups.of(servers, settled)

    val statusUnsupported: Boolean get() = ControlRequests.MCP_STATUS in unsupported

    /** A probe is one process at a time, so one action at a time. */
    val actionsBlocked: Boolean get() = !live && busy.isNotEmpty()

    fun canSignIn(server: McpServer): Boolean =
        server.state == McpState.NeedsAuth && ControlRequests.MCP_AUTHENTICATE !in unsupported

    /** A short-lived process connects everything as it starts, so Reconnect only applies to a live session. */
    fun canReconnect(server: McpServer): Boolean =
        live && ControlRequests.MCP_RECONNECT !in unsupported && (server.state == McpState.Connected || server.state == McpState.Failed)

    /**
     * The switch: measured safe on servers configured locally. Connectors and plugin servers weren't measured, so they
     * can only be turned back on when already off. A probe has no folder that matters, so a live session only.
     */
    fun canToggle(server: McpServer): Boolean {
        if (!live || ControlRequests.MCP_TOGGLE in unsupported) return false
        return when (server.state) {
            McpState.Disabled -> true
            McpState.Connected, McpState.Failed, McpState.NeedsAuth -> !server.isConnector && !server.isPlugin
            McpState.Pending, McpState.Unknown -> false
        }
    }

    /** The email the browser must be signed in with, in words that work when it is unknown. */
    val signInEmail: String get() = identity?.account?.email ?: "this account's email"
}

/** Pure state changes for [McpPanel]. */
object McpPanelReducer {

    fun refreshStarted(state: McpPanelState) = state.copy(refreshing = true, settled = false, statusProblem = null)

    fun refreshEnded(state: McpPanelState) = state.copy(refreshing = false, settled = state.settled || state.loaded)

    fun identity(state: McpPanelState, identity: ClaudeIdentity?) = if (identity == null) state else state.copy(identity = identity)

    fun statusReceived(state: McpPanelState, servers: List<McpServer>, settled: Boolean, now: Long): McpPanelState {
        val connected = servers.filter { it.state == McpState.Connected }.map { it.name }.toSet()
        return state.copy(
            servers = servers, loaded = true, settled = settled, checkedAt = now, statusProblem = null,
            awaitingBrowser = state.awaitingBrowser - connected,
        )
    }

    /** `mcp_status` didn't give a list. What is on screen stays. */
    fun statusFailed(state: McpPanelState, reply: ControlReply): McpPanelState = when (reply) {
        is ControlReply.Refused ->
            if (reply.unsupported) unsupported(state, ControlRequests.MCP_STATUS)
            else state.copy(statusProblem = McpParser.scrub(reply.message))
        is ControlReply.NoAnswer -> state.copy(statusProblem = reply.reason)
        is ControlReply.Success -> state.copy(statusProblem = "Claude Code answered without a list of servers.")
    }

    fun unsupported(state: McpPanelState, subtype: String) = state.copy(unsupported = state.unsupported + subtype)

    fun actionStarted(state: McpPanelState, name: String, busy: McpBusy) =
        state.copy(busy = state.busy + (name to busy), notices = state.notices - name)

    fun actionEnded(state: McpPanelState, name: String, notice: ServerNotice?) = state.copy(
        busy = state.busy - name,
        notices = if (notice == null) state.notices else state.notices + (name to notice),
    )

    fun waitingForBrowser(state: McpPanelState, name: String) = state.copy(busy = state.busy + (name to McpBusy.WaitingForBrowser))

    fun browserOpened(state: McpPanelState, name: String) = state.copy(awaitingBrowser = state.awaitingBrowser + name)
}
