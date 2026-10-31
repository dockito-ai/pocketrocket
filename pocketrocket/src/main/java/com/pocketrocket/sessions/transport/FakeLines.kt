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

    /** [parent]: written by the subagent of that Agent call, as the CLI marks it (`parent_tool_use_id`). */
    fun text(text: String, parent: String? = null) = assistant(JSONObject().put("type", "text").put("text", text), parent)

    /** Returns the tool id with the line so the result can point back at it. [parent] as for [text]. */
    fun toolUse(name: String, input: JSONObject, parent: String? = null): Pair<String, String> {
        val id = "toolu_demo_${sessionId.take(4)}_${++toolCounter}"
        return id to assistant(JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input), parent)
    }

    /** [totals]: an Agent call's result carries its subagent's numbers (`tool_use_result`). */
    fun toolResult(toolUseId: String, content: String, isError: Boolean = false, parent: String? = null, totals: AgentTotals? = null) = JSONObject()
        .put("type", "user").put("session_id", sessionId).put("parent_tool_use_id", parent ?: JSONObject.NULL)
        .put("message", JSONObject().put("role", "user").put("content", JSONArray().put(
            JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", content).put("is_error", isError),
        )))
        .apply {
            if (totals != null) put("tool_use_result", JSONObject().put("totalTokens", totals.tokens).put("totalToolUseCount", totals.toolUses).put("totalDurationMs", totals.durationMs))
        }.toString()

    /** A subagent's numbers, as its task lines and its result report them. */
    data class AgentTotals(val tokens: Long, val toolUses: Int, val durationMs: Long = 0)

    /** `system/task_started` for the subagent of Agent call [toolUseId], running as task [taskId] (2.1.309's shape). */
    fun agentStarted(toolUseId: String, taskId: String, description: String) = task("task_started", toolUseId, taskId)
        .put("task_type", "local_agent").put("description", description).toString()

    /** `system/task_progress`: what the subagent is doing now, and its numbers so far. */
    fun agentProgress(toolUseId: String, taskId: String, activity: String, totals: AgentTotals) = task("task_progress", toolUseId, taskId)
        .put("description", activity).put("usage", JSONObject().put("total_tokens", totals.tokens).put("tool_uses", totals.toolUses)).toString()

    /** `system/task_notification`: the subagent ended with [status]. */
    fun agentEnded(toolUseId: String, taskId: String, status: String, summary: String, totals: AgentTotals) = task("task_notification", toolUseId, taskId)
        .put("status", status).put("summary", summary)
        .put("usage", JSONObject().put("total_tokens", totals.tokens).put("tool_uses", totals.toolUses).put("duration_ms", totals.durationMs)).toString()

    private fun task(subtype: String, toolUseId: String, taskId: String) = JSONObject()
        .put("type", "system").put("subtype", subtype).put("session_id", sessionId).put("task_id", taskId).put("tool_use_id", toolUseId)

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


    private fun assistant(block: JSONObject, parent: String?) = JSONObject()
        .put("type", "assistant").put("session_id", sessionId).put("parent_tool_use_id", parent ?: JSONObject.NULL)
        .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(block))).toString()
}
