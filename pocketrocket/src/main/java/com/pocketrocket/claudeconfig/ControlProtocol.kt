package com.pocketrocket.claudeconfig

import org.json.JSONObject

/**
 * The answer to one configuration control request. These requests aren't a stable contract
 * (`docs/chat-sessions.md`), so nothing here may assume a reply: [Refused] and [NoAnswer] are normal outcomes.
 * Neither prints a reply body, since some replies hold secrets (`mcp_status` `config`).
 */
sealed interface ControlReply {
    /** Claude Code did it. [body] is empty for requests with nothing to say. Parse it at once; never keep or log it. */
    class Success(val body: JSONObject) : ControlReply {
        override fun toString() = "Success"
    }

    /** Claude Code answered with an error. */
    data class Refused(val message: String) : ControlReply {
        /** This version doesn't know the request: hide the action or fall back to the terminal. */
        val unsupported: Boolean get() = message.startsWith(ControlReply.UNSUPPORTED)
    }

    /** No answer came: it timed out, or no process runs. */
    data class NoAnswer(val reason: String) : ControlReply

    companion object {
        /** What Claude Code answers for a subtype it doesn't know, measured on 2.1.314. */
        const val UNSUPPORTED = "Unsupported control request subtype"
    }
}

/**
 * A way to send control requests to one Claude Code process, and nothing else: there is no way to send a user
 * message through it, so it can't start a model call. [com.pocketrocket.claudeconfig.SessionControlChannel] is a
 * running session; [ClaudeConfigProbe] is a short-lived process for when no session is open.
 */
interface ControlChannel {
    suspend fun request(subtype: String, fields: Map<String, Any> = emptyMap(), timeoutMs: Long = ControlRequests.TIMEOUT_MS): ControlReply
}

/** The control requests used so far, and how long to wait for them. Measured fields: `docs/chat-sessions.md`. */
object ControlRequests {
    const val MCP_STATUS = "mcp_status"
    const val MCP_RECONNECT = "mcp_reconnect"
    const val MCP_TOGGLE = "mcp_toggle"
    const val MCP_AUTHENTICATE = "mcp_authenticate"
    const val GET_SKILLS_DIALOG = "get_skills_dialog"
    const val RELOAD_SKILLS = "reload_skills"
    const val GET_SETTINGS = "get_settings"
    const val RELOAD_OUTPUT_STYLES = "reload_output_styles"
    const val APPLY_FLAG_SETTINGS = "apply_flag_settings"
    const val UPDATE_SETTINGS = "update_settings"

    /** A status reply takes about 0.1 s, but a process still starting answers only once it is up. */
    const val TIMEOUT_MS = 20_000L

    /** Reconnecting answers once the server has connected or failed. */
    const val CONNECT_TIMEOUT_MS = 45_000L
}

suspend fun ControlChannel.mcpStatus(): ControlReply = request(ControlRequests.MCP_STATUS)

suspend fun ControlChannel.mcpReconnect(serverName: String): ControlReply =
    request(ControlRequests.MCP_RECONNECT, mapOf("serverName" to serverName), ControlRequests.CONNECT_TIMEOUT_MS)

/** Persists per folder, not per account: `projects[<cwd>].disabledMcpServers` in the account's `.claude.json`. */
suspend fun ControlChannel.mcpToggle(serverName: String, enabled: Boolean): ControlReply =
    request(ControlRequests.MCP_TOGGLE, mapOf("serverName" to serverName, "enabled" to enabled))

suspend fun ControlChannel.mcpAuthenticate(serverName: String): ControlReply =
    request(ControlRequests.MCP_AUTHENTICATE, mapOf("serverName" to serverName))

suspend fun ControlChannel.skillsDialog(): ControlReply = request(ControlRequests.GET_SKILLS_DIALOG)

/** Picks up skills changed on disk: [skillsDialog] doesn't until this has run (measured on 2.1.314). */
suspend fun ControlChannel.reloadSkills(): ControlReply = request(ControlRequests.RELOAD_SKILLS)

suspend fun ControlChannel.getSettings(): ControlReply = request(ControlRequests.GET_SETTINGS)

suspend fun ControlChannel.outputStyles(): ControlReply = request(ControlRequests.RELOAD_OUTPUT_STYLES)

/**
 * This process only, nothing written. Answers success for anything, even a style or level that doesn't exist, so offer
 * only what Claude Code listed and read the result back with [getSettings] (measured on 2.1.314).
 */
suspend fun ControlChannel.applyOutputStyle(style: String): ControlReply =
    request(ControlRequests.APPLY_FLAG_SETTINGS, mapOf("settings" to JSONObject().put("outputStyle", style)))

suspend fun ControlChannel.applyEffort(level: String): ControlReply =
    request(ControlRequests.APPLY_FLAG_SETTINGS, mapOf("settings" to JSONObject().put("effortLevel", level)))

/** Writes `<cwd>/.claude/settings.local.json`. One of only two writes Claude Code allows here, and it can't be removed this way. */
suspend fun ControlChannel.saveOutputStyleForFolder(style: String): ControlReply =
    request(ControlRequests.UPDATE_SETTINGS, mapOf("source" to "localSettings", "settings" to JSONObject().put("outputStyle", style)))

/** Writes the account's `settings.json`, under `modelSettings.<current model>`. New processes use it; this one doesn't change. */
suspend fun ControlChannel.saveEffortForAccount(level: String): ControlReply =
    request(ControlRequests.UPDATE_SETTINGS, mapOf("source" to "userSettings", "settings" to JSONObject().put("effortLevel", level)))
