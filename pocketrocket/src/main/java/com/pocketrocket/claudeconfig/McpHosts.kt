package com.pocketrocket.claudeconfig

import android.content.Context
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.sessions.Session
import java.io.File

/** A running session's process as a [ControlChannel]. Never starts a process. */
class SessionControlChannel(private val session: Session) : ControlChannel {
    override suspend fun request(subtype: String, fields: Map<String, Any>, timeoutMs: Long): ControlReply {
        if (!session.alive.value) return ControlReply.NoAnswer("Claude Code isn't running in this session.")
        val answer = session.configRequest(subtype, fields, timeoutMs) ?: return ControlReply.NoAnswer("Claude Code didn't answer.")
        val body = answer.response
        return if (body != null) ControlReply.Success(body) else ControlReply.Refused(answer.error ?: "failed")
    }
}

/** Asks a running session. */
class SessionMcpHost(private val context: Context, private val session: Session) : McpHost {
    override val live = true

    override suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T =
        block(McpConnection(SessionControlChannel(session), session.processStartedAt, session.state.value.identity))

    /** Same folder and account as the session, so it sees what a new session there would. */
    override suspend fun freshStatus(serverName: String): McpState? {
        val state = session.state.value
        if (state.demo) return null
        val environment = state.profile.environment + AccountWiring.toolEnvFor(state.folder)
        return ClaudeConfigProbe.run(context, File(state.folder), environment) { probe ->
            McpWait.untilListed(probe, serverName)?.state
        }
    }
}

/** Asks a new short-lived process per [withChannel], for [environment] in [folder]. */
class ProbeMcpHost(private val context: Context, private val folder: File, private val environment: Map<String, String>) : McpHost {
    override val live = false

    override suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T =
        ClaudeConfigProbe.run(context, folder, environment) { probe ->
            block(McpConnection(probe, probe.startedAt, probe.identity()))
        }

    override suspend fun freshStatus(serverName: String): McpState? = withChannel { McpWait.untilListed(it.channel, serverName)?.state }
}
