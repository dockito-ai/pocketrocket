package com.pocketrocket.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * The safety net for agents in Auto (decision 57). Claude Code posts each `PreToolUse` here, and for a command on the sensitive
 * list the answer is "ask": Claude Code then sends an ordinary permission request, which takes the fingerprint step 4 put on it.
 *
 * Measured on Claude Code 2.1.308: `permission_mode` in the hook's input follows a mid-session switch, "ask" reaches
 * `can_use_tool` in Auto, and an HTTP hook that fails (app gone, a 500) lets the tool run. So it fails open if the app
 * isn't there, and it reads what's written: a script that force-pushes isn't caught. Settings says so too.
 */
object AutoModeNet {
    /** `bypassPermissions` reads as Auto in the app, so it's covered like Auto. */
    private val COVERED = setOf("auto", "bypassPermissions")
    const val REASON = "On Rocket's sensitive list: needs your fingerprint."
    const val SETTINGS_NOTE = "A safety net, not a guarantee: it reads what's written, so a script that force-pushes gets past it. Protected branches on GitHub are the real protection."

    /** What Claude Code should do with the hook's input. Anything it doesn't recognise as on the list stays as it was, or every browser step in Auto would stop. */
    fun decide(input: JSONObject, enabled: Boolean): JSONObject {
        if (!enabled || input.optString("permission_mode") !in COVERED) return PASS
        if (input.optString("tool_name") != "Bash") return PASS
        val command = input.optJSONObject("tool_input")?.optString("command").orEmpty()
        if (!SensitiveActions.commandIsSensitive(command)) return PASS
        return ask(REASON)
    }

    /**
     * The same hook for Rocket's own session (decision 61), which runs in Auto with a shell and his MCP servers. Stricter than
     * for an agent, whatever its mode: a command on the list asks, and so does a push, merge or new PR, so Claude Code sends a
     * permission request that [AssistantBrain] passes to [Approvals] (fingerprint, or a yes). An MCP tool that isn't Rocket's
     * and doesn't read as a lookup asks too, since it could send a message. On a locked phone anything that acts asks, so only
     * a fingerprint lets it run. Rocket's own tools pass: each checks itself. [scratch]: where removing files loses nothing.
     */
    fun decideForRocket(input: JSONObject, locked: Boolean, scratch: List<String>): JSONObject {
        val tool = input.optString("tool_name")
        if (tool.startsWith(ROCKET_TOOLS) || tool in LOOKUPS) return PASS
        // The app's own settings and Rocket's wiring: changing them could switch a safety rule off, so they're never just done.
        val given = input.optJSONObject("tool_input")?.toString().orEmpty().replace("\\/", "/")
        if (APP_FILES.any { it in given }) return ask(REASON)
        if (tool == "Bash") {
            val command = input.optJSONObject("tool_input")?.optString("command").orEmpty()
            val where = SensitiveActions.Scratch(scratch, input.optString("cwd").ifBlank { null })
            return when {
                command.isBlank() || SensitiveActions.commandIsSensitive(command, where) -> ask(REASON)
                SensitiveActions.commandNeedsYes(command) -> ask(NEEDS_YES)
                locked -> ask(LOCKED)
                else -> PASS
            }
        }
        if (tool.startsWith("mcp__")) {
            return when {
                locked -> ask(LOCKED)
                LOOKUP_NAME.containsMatchIn(tool.substringAfterLast("__")) -> PASS
                else -> ask(REASON)
            }
        }
        // Edits, subagents and anything else act, so a locked phone asks. A subagent's own tool calls come through here too.
        return if (locked) ask(LOCKED) else PASS
    }

    private fun ask(reason: String) = JSONObject().put(
        "hookSpecificOutput",
        JSONObject().put("hookEventName", "PreToolUse").put("permissionDecision", "ask").put("permissionDecisionReason", reason),
    )

    const val NEEDS_YES = "Push, merge and opening a pull request need his yes."
    const val LOCKED = "The phone is locked: acting needs his fingerprint."
    private const val ROCKET_TOOLS = "mcp__${McpServer.SERVER_NAME}__"

    /** Where the app keeps its switches, and Rocket's own wiring. */
    private val APP_FILES = listOf("shared_prefs", "/databases/", "rocket-hook.json", "agent-hook.json", "rocket-mcp.json", "rocket-wake")

    /** Built-in tools that only read, and so need nothing even on a locked phone. */
    private val LOOKUPS = setOf("Read", "Glob", "Grep", "LS", "NotebookRead", "TodoWrite", "WebSearch", "WebFetch", "BashOutput", "ToolSearch", "ListMcpResourcesTool", "ReadMcpResourceTool")

    /** An MCP tool named like a lookup. Its server picked the name, not Claude. */
    private val LOOKUP_NAME = Regex("""^(?i:get|list|read|search|fetch|query|find|describe|view|show|lookup|whoami)(?:_|-|$|[A-Z])""")

    /** The settings for `--settings`, which Claude Code adds to the user's own: it fires alongside theirs and nothing of theirs is written. [path]: the agents' hook or Rocket's. */
    fun settings(port: Int, token: String, path: String = McpServer.HOOK_PATH): String = JSONObject().put(
        "hooks",
        JSONObject().put(
            "PreToolUse",
            JSONArray().put(
                JSONObject().put(
                    "hooks",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "http")
                            .put("url", "http://${McpServer.LOOPBACK}:$port$path")
                            .put("headers", JSONObject().put("Authorization", "Bearer $token"))
                            .put("timeout", 10),
                    ),
                ),
            ),
        ),
    ).toString()

    private val PASS get() = JSONObject()
}
