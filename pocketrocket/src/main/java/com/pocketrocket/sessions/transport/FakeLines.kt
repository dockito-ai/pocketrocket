package com.pocketrocket.sessions.transport

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-written stream-json lines in the shapes Claude Code 2.1 prints, trimmed to the fields that
 * matter. Used by fake conversations; the real parser reads them like any other line.
 */
class FakeLines(private val sessionId: String, private val cwd: String) {

    private var toolCounter = 0

    companion object {
        /** A made-up `mcp_status` reply, one server per state, so the Status sheet shows something in a demo session. */
        fun mcpStatus(): JSONObject {
            fun server(name: String, status: String, scope: String, transport: String) =
                JSONObject().put("name", name).put("status", status).put("scope", scope).put("transport", transport)
            return JSONObject().put("mcpServers", JSONArray()
                .put(server("chrome-devtools", "connected", "user", "stdio").put("tools", JSONArray().put(JSONObject().put("name", "take_screenshot"))))
                .put(server("claude.ai Gmail", "connected", "claudeai", "claudeai-proxy"))
                .put(server("claude.ai Google Drive", "needs-auth", "claudeai", "claudeai-proxy"))
                .put(server("my-server", "failed", "user", "stdio").put("error", "Connection closed"))
                .put(server("notes", "disabled", "local", "stdio")))
        }
    }

    fun init() = JSONObject()
        .put("type", "system").put("subtype", "init")
        .put("session_id", sessionId).put("cwd", cwd).put("model", "claude-opus-5-5")
        .put("permissionMode", "default").toString()

    fun text(text: String) = assistant(JSONObject().put("type", "text").put("text", text))

    /** Returns the tool id with the line so the result can point back at it. */
    fun toolUse(name: String, input: JSONObject): Pair<String, String> {
        val id = "toolu_demo_${sessionId.take(4)}_${++toolCounter}"
        return id to assistant(JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input))
    }

    fun toolResult(toolUseId: String, content: String, isError: Boolean = false) = JSONObject()
        .put("type", "user").put("session_id", sessionId).put("parent_tool_use_id", JSONObject.NULL)
        .put("message", JSONObject().put("role", "user").put("content", JSONArray().put(
            JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", content).put("is_error", isError),
        ))).toString()

    /** [allowRule] adds a `permission_suggestions` entry like the CLI's, so the card offers "Always allow". */
    fun permission(requestId: String, toolName: String, input: JSONObject, toolUseId: String, allowRule: String? = null) = JSONObject()
        .put("type", "control_request").put("request_id", requestId)
        .put("request", JSONObject().put("subtype", "can_use_tool").put("tool_name", toolName)
            .put("input", input).put("tool_use_id", toolUseId)
            .apply {
                if (allowRule != null) put("permission_suggestions", JSONArray().put(
                    JSONObject().put("type", "addRules").put("behavior", "allow").put("destination", "localSettings")
                        .put("rules", JSONArray().put(JSONObject().put("toolName", toolName).put("ruleContent", allowRule))),
                ))
            }).toString()

    fun result(text: String, isError: Boolean = false) = JSONObject()
        .put("type", "result").put("subtype", if (isError) "error_during_execution" else "success")
        .put("is_error", isError).put("result", text).put("session_id", sessionId).toString()

    private fun assistant(block: JSONObject) = JSONObject()
        .put("type", "assistant").put("session_id", sessionId).put("parent_tool_use_id", JSONObject.NULL)
        .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(block))).toString()
}
