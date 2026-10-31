package com.pocketrocket.claudeconfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A Claude Code process to ask, while [McpHost.withChannel] runs. [startedAt] is when that process started. */
class McpConnection(val channel: ControlChannel, val startedAt: Long, val identity: ClaudeIdentity?)

/** Where a [McpPanel] gets its answers: a running session ([SessionMcpHost]) or a short-lived process ([ProbeMcpHost]). */
interface McpHost {
    /** A running session, as opposed to a process that lives only for one [withChannel]. */
    val live: Boolean

    suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T

    /** [serverName]'s state in a brand-new process, which sees sign-ins a running one doesn't. Null when unknown. */
    suspend fun freshStatus(serverName: String): McpState?
}

/** Waits for one server to be listed and done connecting, since a new process lists connectors late. */
object McpWait {
    const val TIMEOUT_MS = 40_000L

    suspend fun untilListed(
        channel: ControlChannel,
        serverName: String,
        now: () -> Long = System::currentTimeMillis,
        pause: suspend (Long) -> Unit = { delay(it) },
        onServers: (List<McpServer>) -> Unit = {},
    ): McpServer? {
        val began = now()
        var attempt = 0
        while (now() - began < TIMEOUT_MS) {
            val reply = channel.mcpStatus()
            if (reply is ControlReply.Refused) return null
            val servers = (reply as? ControlReply.Success)?.let { McpParser.status(it.body) }
            if (servers != null) {
                onServers(servers)
                servers.firstOrNull { it.name == serverName && it.state != McpState.Pending }?.let { return it }
            }
            pause(McpRefresh.delayMs(attempt++))
        }
        return null
    }
}

/**
 * The server list and its actions over one [McpHost], for as long as [scope] lives: when the screen goes, so does any
 * short-lived process it started. Nothing happens until a method is called. Every failure ends in [state], never in
 * an exception. Call from one thread ([scope] on the main thread in the app); [signIn]'s `open` runs on it too.
 */
class McpPanel(
    private val host: McpHost,
    private val scope: CoroutineScope,
    initial: McpPanelState = McpPanelState(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(initial.copy(live = host.live))
    val state: StateFlow<McpPanelState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** Asks `mcp_status` until nothing is pending, gently ([McpRefresh]). A refresh under way is replaced. */
    fun refresh() {
        val previous = refreshJob
        previous?.cancel()
        refreshJob = scope.launch {
            previous?.join()
            try {
                _state.update(McpPanelReducer::refreshStarted)
                host.withChannel { connection -> poll(connection) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { McpPanelReducer.statusFailed(it, ControlReply.NoAnswer(COULD_NOT_ASK)) }
            } finally {
                _state.update(McpPanelReducer::refreshEnded)
            }
        }
    }

    private suspend fun poll(connection: McpConnection) {
        _state.update { McpPanelReducer.identity(it, connection.identity) }
        val began = now()
        var attempt = 0
        var misses = 0
        while (true) {
            val reply = connection.channel.mcpStatus()
            val servers = (reply as? ControlReply.Success)?.let { McpParser.status(it.body) }
            if (servers == null) {
                if (reply is ControlReply.NoAnswer && ++misses < MAX_MISSES) {
                    pause(McpRefresh.delayMs(attempt++))
                    continue
                }
                _state.update { McpPanelReducer.statusFailed(it, reply) }
                return
            }
            misses = 0
            val settled = McpRefresh.settled(servers, now() - connection.startedAt) || now() - began >= McpRefresh.MAX_MS
            _state.update { McpPanelReducer.statusReceived(it, servers, settled, now()) }
            if (settled) return
            pause(McpRefresh.delayMs(attempt++))
        }
    }

    /** Runs [work] for one server, with its busy mark on and whatever it returns as the server's notice. */
    private fun action(name: String, busy: McpBusy, work: suspend () -> ServerNotice?) {
        val current = _state.value
        if (name in current.busy || current.actionsBlocked) return
        _state.update { McpPanelReducer.actionStarted(it, name, busy) }
        scope.launch {
            var notice: ServerNotice? = null
            try {
                notice = work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = problem(COULD_NOT_ASK, offerTerminal = true)
            } finally {
                _state.update { McpPanelReducer.actionEnded(it, name, notice) }
            }
        }
    }

    /** `mcp_reconnect`; live sessions only. */
    fun reconnect(name: String) {
        if (!host.live) return
        action(name, McpBusy.Reconnect) {
            val reply = host.withChannel { it.channel.mcpReconnect(name) }
            refresh()
            refusal(reply, ControlRequests.MCP_RECONNECT, "Couldn't reconnect")
        }
    }

    /** `mcp_toggle`; live sessions only. It sticks to the session's folder, which the notice says. */
    fun setEnabled(name: String, enabled: Boolean) {
        if (!host.live) return
        action(name, McpBusy.Toggle) {
            val reply = host.withChannel { it.channel.mcpToggle(name, enabled) }
            refresh()
            refusal(reply, ControlRequests.MCP_TOGGLE, "Couldn't change it") ?: ServerNotice(
                if (enabled) "On again in this folder." else "Off in this folder, on this account. Other folders keep it on. Later sessions here stay off until you turn it back on.",
                ServerNotice.Kind.Info,
            )
        }
    }

    /**
     * `mcp_authenticate`, then [open] with the URL it returns. A connector's sign-in happens on claude.ai and nothing
     * comes back, so the row then offers [recheck]. A local OAuth server needs the asking process alive until its
     * redirect, so a probe stays up for it, for [HOLD_MS] at most.
     */
    fun signIn(name: String, open: (String) -> Boolean) = action(name, McpBusy.SignIn) {
        if (!host.live) stopRefresh()
        host.withChannel { connection ->
            _state.update { McpPanelReducer.identity(it, connection.identity) }
            val listed = if (host.live) null else McpWait.untilListed(connection.channel, name, now, pause) { servers ->
                _state.update { McpPanelReducer.statusReceived(it, servers, it.settled, now()) }
            }
            when {
                !host.live && listed == null -> problem("Claude Code didn't list $name in time.", offerTerminal = true)
                listed?.state == McpState.Connected -> ServerNotice("Already connected.", ServerNotice.Kind.Success)
                else -> startSignIn(connection, name, open)
            }
        }
    }

    private suspend fun startSignIn(connection: McpConnection, name: String, open: (String) -> Boolean): ServerNotice? {
        val reply = connection.channel.mcpAuthenticate(name)
        if (reply !is ControlReply.Success) {
            return refusal(reply, ControlRequests.MCP_AUTHENTICATE, "Couldn't start sign-in")?.copy(offerTerminal = true)
        }
        val start = McpParser.authStart(reply.body)
        val url = start.url
        return when {
            url == null && !start.requiresUserAction -> { refresh(); null }
            url == null -> problem("Claude Code didn't give a sign-in link.", offerTerminal = true)
            !open(url) -> problem("No browser could open the sign-in page.", offerTerminal = true)
            else -> {
                _state.update { McpPanelReducer.browserOpened(it, name) }
                if (!host.live && start.callbackExpected) holdForRedirect(connection, name) else null
            }
        }
    }

    /** Keeps a probe alive through the browser step of a local OAuth sign-in, which connects by itself on the redirect. */
    private suspend fun holdForRedirect(connection: McpConnection, name: String): ServerNotice {
        _state.update { McpPanelReducer.waitingForBrowser(it, name) }
        val began = now()
        while (now() - began < HOLD_MS) {
            pause(HOLD_ASK_MS)
            val servers = (connection.channel.mcpStatus() as? ControlReply.Success)?.let { McpParser.status(it.body) } ?: continue
            _state.update { McpPanelReducer.statusReceived(it, servers, it.settled, now()) }
            if (servers.any { it.name == name && it.state == McpState.Connected }) return ServerNotice("Connected.", ServerNotice.Kind.Success)
        }
        return problem("The sign-in didn't finish in time. Try again.", offerTerminal = true)
    }

    /**
     * After the browser step. A live session tries `mcp_reconnect` and asks again; if it still doesn't see the server
     * connected, a new process settles whether the sign-in worked and this session just can't see it. A probe is a
     * new process anyway.
     */
    fun recheck(name: String) = action(name, McpBusy.Recheck) {
        if (host.live) {
            host.withChannel { connection ->
                val reply = connection.channel.mcpReconnect(name)
                if (reply is ControlReply.Refused && reply.unsupported) _state.update { McpPanelReducer.unsupported(it, ControlRequests.MCP_RECONNECT) }
                askOnce(connection)
            }
        } else {
            stopRefresh()
            host.withChannel { connection -> poll(connection) }
            _state.update { it.copy(settled = true) }
        }
        when {
            stateOf(name) == McpState.Connected -> ServerNotice(
                if (host.live) "Connected. This session can use it now." else "Connected. Sessions already running need a new session to use it.",
                ServerNotice.Kind.Success,
            )
            !host.live -> if (_state.value.statusProblem != null) problem("Couldn't check: ${_state.value.statusProblem}", offerTerminal = true) else stillNotSignedIn()
            else -> when (host.freshStatus(name)) {
                McpState.Connected -> ServerNotice(
                    "Signed in. This session started before that and can't use it, so start a new session.",
                    ServerNotice.Kind.Info, offerNewSession = true,
                )
                null -> ServerNotice("Couldn't tell from here. A new session will show whether it worked.", ServerNotice.Kind.Info, offerNewSession = true)
                else -> stillNotSignedIn()
            }
        }
    }

    private fun stillNotSignedIn() = problem(
        "Still not signed in. Check the browser is signed in to claude.ai as ${_state.value.signInEmail}, then sign in again.",
        offerTerminal = true,
    )

    private suspend fun askOnce(connection: McpConnection) {
        val reply = connection.channel.mcpStatus()
        val servers = (reply as? ControlReply.Success)?.let { McpParser.status(it.body) }
        _state.update {
            if (servers == null) McpPanelReducer.statusFailed(it, reply)
            else McpPanelReducer.statusReceived(it, servers, it.settled, now())
        }
    }

    private fun stateOf(name: String): McpState? = _state.value.servers.firstOrNull { it.name == name }?.state

    private suspend fun stopRefresh() {
        val running = refreshJob ?: return
        running.cancel()
        running.join()
    }

    /** Null when [reply] was a success; else what to tell the user, noting a request this version doesn't know. */
    private fun refusal(reply: ControlReply, subtype: String, what: String): ServerNotice? = when (reply) {
        is ControlReply.Success -> null
        is ControlReply.Refused -> {
            if (reply.unsupported) _state.update { McpPanelReducer.unsupported(it, subtype) }
            problem(if (reply.unsupported) "This version of Claude Code can't do that." else "$what: ${McpParser.scrub(reply.message)}")
        }
        is ControlReply.NoAnswer -> problem("$what: ${reply.reason}")
    }

    private fun problem(text: String, offerTerminal: Boolean = false) = ServerNotice(text, ServerNotice.Kind.Problem, offerTerminal)

    companion object {
        /** How long a probe waits for a local OAuth redirect. */
        const val HOLD_MS = 5 * 60_000L
        private const val HOLD_ASK_MS = 5_000L
        private const val MAX_MISSES = 2
        private const val COULD_NOT_ASK = "Couldn't ask Claude Code."
    }
}
