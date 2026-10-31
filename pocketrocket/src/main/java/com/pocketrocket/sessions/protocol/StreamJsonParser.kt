package com.pocketrocket.sessions.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Parses one stdout line of Claude Code's stream-json output. Lines that are not JSON give nothing. */
object StreamJsonParser {

    fun parse(line: String): List<StreamEvent> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val json = try { JSONObject(trimmed) } catch (_: JSONException) { return emptyList() }
        return when (json.optString("type")) {
            "system" -> listOfNotNull(system(json))
            "assistant" -> assistant(json)
            "stream_event" -> listOfNotNull(streamEvent(json))
            "user" -> user(json)
            "result" -> listOf(result(json))
            "control_request" -> listOfNotNull(controlRequest(json))
            "control_response" -> listOfNotNull(controlResponse(json))
            "control_cancel_request" -> listOf(StreamEvent.PermissionCancelled(json.optString("request_id")))
            else -> emptyList()
        }
    }

    private fun system(json: JSONObject): StreamEvent? = when (json.optString("subtype")) {
        "init" -> StreamEvent.Init(
            json.optString("session_id"), json.optStringOrNull("model"), json.optStringOrNull("cwd"),
            json.optStringOrNull("permissionMode"),
            json.optJSONArray("slash_commands")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty(),
        )
        "status" -> StreamEvent.Status(json.optStringOrNull("status"), json.optStringOrNull("permissionMode"))
        "compact_boundary" -> json.optJSONObject("compact_metadata").let {
            StreamEvent.Compacted(it?.optStringOrNull("trigger"), it?.optLong("pre_tokens") ?: 0, it?.optLong("post_tokens") ?: 0)
        }
        "task_started", "task_progress" -> json.optStringOrNull("tool_use_id")?.let {
            StreamEvent.SubagentUpdate(it, activity = json.optStringOrNull("description"), tokens = json.taskNumber("total_tokens") ?: json.taskNumber("subagent_tokens"), toolUses = json.taskNumber("tool_uses")?.toInt())
        }
        "task_notification" -> json.optStringOrNull("tool_use_id")?.let {
            StreamEvent.SubagentUpdate(
                it, status = json.optStringOrNull("status"), summary = json.optStringOrNull("summary"),
                tokens = json.taskNumber("total_tokens") ?: json.taskNumber("subagent_tokens"), toolUses = json.taskNumber("tool_uses")?.toInt(), durationMs = json.taskNumber("duration_ms"),
            )
        }
        else -> null
    }

    private fun assistant(json: JSONObject): List<StreamEvent> {
        val message = json.optJSONObject("message") ?: return emptyList()
        val content = message.optJSONArray("content") ?: return emptyList()
        // Subagent output carries a parent tool id: only its tool calls are shown, nested in the Task card.
        json.optStringOrNull("parent_tool_use_id")?.let { parent ->
            return content.objects().filter { it.optString("type") == "tool_use" }.map {
                StreamEvent.SubagentToolUse(parent, it.optString("id"), it.optString("name"), it.optJSONObject("input") ?: JSONObject())
            }
        }
        val error = json.optStringOrNull("error")
        if (error != null) return listOf(StreamEvent.ApiError(error, textOf(content)))
        val messageId = message.optStringOrNull("id")
        val blocks = content.objects().mapNotNull { block ->
            when (block.optString("type")) {
                // Blank text is still passed on when it has a message id: it closes a streamed block.
                "text" -> block.optString("text").takeIf { it.isNotBlank() || messageId != null }
                    ?.let { StreamEvent.AssistantText(it, messageId) }
                "tool_use" -> StreamEvent.ToolUse(block.optString("id"), block.optString("name"), block.optJSONObject("input") ?: JSONObject())
                else -> null
            }
        }
        return listOfNotNull(usage(message)) + blocks
    }

    private fun usage(message: JSONObject): StreamEvent.Usage? {
        val usage = message.optJSONObject("usage") ?: return null
        val tokens = usage.optLong("input_tokens") + usage.optLong("cache_creation_input_tokens") + usage.optLong("cache_read_input_tokens")
        return if (tokens > 0) StreamEvent.Usage(tokens, message.optStringOrNull("model")) else null
    }

    /** `--include-partial-messages`: only text deltas matter; tool input and thinking deltas wait for the full message. */
    private fun streamEvent(json: JSONObject): StreamEvent? {
        if (json.optStringOrNull("parent_tool_use_id") != null) return null
        val event = json.optJSONObject("event") ?: return null
        if (event.optString("type") != "content_block_delta") return null
        val delta = event.optJSONObject("delta") ?: return null
        if (delta.optString("type") != "text_delta") return null
        val messageId = json.optStringOrNull("api_message_id") ?: return null
        return StreamEvent.TextDelta(messageId, event.optInt("index"), delta.optString("text"))
    }

    private fun user(json: JSONObject): List<StreamEvent> {
        val echo = json.optStringOrNull("uuid")?.takeIf { json.optBoolean("isReplay") }?.let { StreamEvent.UserEcho(it) }
        return listOfNotNull(echo) + userContent(json)
    }

    private fun userContent(json: JSONObject): List<StreamEvent> {
        val content = json.optJSONObject("message")?.opt("content")
        if (content is String) return listOfNotNull(commandOutput(content))
        if (content !is JSONArray) return emptyList()
        val results = content.objects().filter { it.optString("type") == "tool_result" }
        json.optStringOrNull("parent_tool_use_id")?.let { parent ->
            return results.map { StreamEvent.SubagentToolResult(parent, it.optString("tool_use_id"), it.optBoolean("is_error")) }
        }
        return results.map {
            val body = it.opt("content")
            val text = if (body is JSONArray) textOf(body) else body?.toString().orEmpty()
            StreamEvent.ToolResult(it.optString("tool_use_id"), text, it.optBoolean("is_error"))
        } + listOfNotNull(results.singleOrNull()?.let { agentTotals(json, it.optString("tool_use_id")) })
    }

    /**
     * One of a subagent's numbers (`total_tokens` or, as the CLI's own notice says, `subagent_tokens`; `tool_uses`;
     * `duration_ms`) from a `task_*` line: in its `usage` object, or on the line itself. Not checked against a live line
     * yet, so either place counts and a missing one is null.
     */
    private fun JSONObject.taskNumber(key: String): Long? = optJSONObject("usage")?.optLongOrNull(key) ?: optLongOrNull(key)

    /**
     * An Agent tool's result line carries the subagent's totals (`toolUseResult` in the transcript, checked on 2.1.312;
     * `tool_use_result` in stream-json): tokens, tool calls and time taken.
     */
    private fun agentTotals(json: JSONObject, toolUseId: String): StreamEvent.SubagentUpdate? {
        val totals = json.optJSONObject("toolUseResult") ?: json.optJSONObject("tool_use_result") ?: return null
        val tokens = totals.optLongOrNull("totalTokens")
        val tools = totals.optLongOrNull("totalToolUseCount")?.toInt()
        val duration = totals.optLongOrNull("totalDurationMs")
        if (tokens == null && tools == null && duration == null) return null
        return StreamEvent.SubagentUpdate(toolUseId, tokens = tokens, toolUses = tools, durationMs = duration)
    }

    private val localOutput = Regex("<local-command-std(?:out|err)>([\\s\\S]*?)</local-command-std(?:out|err)>")

    private fun commandOutput(content: String): StreamEvent? {
        val text = localOutput.find(content)?.groupValues?.get(1)?.trim() ?: return null
        return text.takeIf { it.isNotEmpty() }?.let { StreamEvent.CommandOutput(it) }
    }

    private fun result(json: JSONObject): StreamEvent.Result {
        val window = json.optJSONObject("modelUsage")?.let { usage ->
            usage.keys().asSequence().mapNotNull { usage.optJSONObject(it)?.optLong("contextWindow")?.takeIf { w -> w > 0 } }.maxOrNull()
        }
        return StreamEvent.Result(
            isError = json.optBoolean("is_error"),
            text = json.optStringOrNull("result"),
            sessionId = json.optStringOrNull("session_id"),
            interrupted = json.optString("terminal_reason").startsWith("aborted"),
            contextWindow = window,
        )
    }

    private fun controlRequest(json: JSONObject): StreamEvent? {
        val request = json.optJSONObject("request") ?: return null
        if (request.optString("subtype") != "can_use_tool") return null
        return StreamEvent.PermissionRequest(
            requestId = json.optString("request_id"),
            toolName = request.optString("tool_name"),
            input = request.optJSONObject("input") ?: JSONObject(),
            toolUseId = request.optStringOrNull("tool_use_id"),
            reason = request.optStringOrNull("description") ?: request.optStringOrNull("decision_reason"),
            suggestions = request.optJSONArray("permission_suggestions") ?: JSONArray(),
        )
    }

    private fun controlResponse(json: JSONObject): StreamEvent? {
        val response = json.optJSONObject("response") ?: return null
        val id = response.optStringOrNull("request_id") ?: return null
        return if (response.optString("subtype") == "success") {
            StreamEvent.ControlResponse(id, response.optJSONObject("response") ?: JSONObject(), null)
        } else {
            StreamEvent.ControlResponse(id, null, response.optStringOrNull("error") ?: "failed")
        }
    }

    private fun textOf(blocks: JSONArray): String =
        blocks.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key, -1).takeIf { it >= 0 }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
