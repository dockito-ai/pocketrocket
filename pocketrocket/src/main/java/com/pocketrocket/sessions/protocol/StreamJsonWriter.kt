package com.pocketrocket.sessions.protocol

import org.json.JSONArray
import org.json.JSONObject

/** Builds the stdin lines a session sends to Claude Code in stream-json input mode. */
object StreamJsonWriter {

    /** The handshake the Agent SDK sends first; the CLI answers with its models, commands and settings. */
    fun initialize(requestId: String): String = control(requestId, JSONObject().put("subtype", "initialize"))

    /**
     * A user turn. Claude Code queues it if a turn is still running. [uuid] becomes the message's id in the
     * transcript, so the conversation can be rewound to it later.
     */
    fun userMessage(text: String, uuid: String? = null): String = user(text, uuid)

    /** A user turn made of content blocks (text, images, documents), for messages with attachments. */
    fun userMessage(content: JSONArray, uuid: String? = null): String = user(content, uuid)

    private fun user(content: Any, uuid: String?): String = JSONObject()
        .put("type", "user")
        .apply { if (uuid != null) put("uuid", uuid) }
        .put("message", JSONObject().put("role", "user").put("content", content))
        .toString()

    /**
     * Answers a `can_use_tool` request. Allow passes the tool input back unchanged. [updatedPermissions] (taken from
     * the request's `permission_suggestions`) makes it "always allow": the CLI applies those rules and stops asking.
     * A deny with [interrupt] also ends the turn, so Claude waits for the user (Keep planning).
     */
    fun permissionAnswer(
        requestId: String,
        allow: Boolean,
        input: JSONObject,
        updatedPermissions: JSONArray? = null,
        denyMessage: String = "The user denied this from PocketRocket.",
        interrupt: Boolean = false,
    ): String {
        val decision = if (allow) {
            JSONObject().put("behavior", "allow").put("updatedInput", input)
                .apply { if (updatedPermissions != null && updatedPermissions.length() > 0) put("updatedPermissions", updatedPermissions) }
        } else {
            JSONObject().put("behavior", "deny").put("message", denyMessage).apply { if (interrupt) put("interrupt", true) }
        }
        return JSONObject()
            .put("type", "control_response")
            .put("response", JSONObject().put("subtype", "success").put("request_id", requestId).put("response", decision))
            .toString()
    }

    /** Stops the running turn, like Esc in the terminal. */
    fun interrupt(requestId: String): String = control(requestId, JSONObject().put("subtype", "interrupt"))

    /**
     * Gets the waiting messages to Claude now, like ctrl+enter in the terminal. Unlike [interrupt] the CLI chooses how
     * the turn makes way, and only stops it if it has to. A CLI too old to know this treats it as [interrupt], which
     * also runs the waiting messages next.
     */
    fun sendNow(requestId: String): String =
        control(requestId, JSONObject().put("subtype", "interrupt").put("send_now", true))

    /** Switches the model for the next turns. [model] is a `value` from the initialize reply's `models`. */
    fun setModel(requestId: String, model: String): String =
        control(requestId, JSONObject().put("subtype", "set_model").put("model", model))

    /** `default`, `acceptEdits`, `plan` or `auto` (also `bypassPermissions`, `dontAsk`). Applies at once. */
    fun setPermissionMode(requestId: String, mode: String): String =
        control(requestId, JSONObject().put("subtype", "set_permission_mode").put("mode", mode))

    /** Context used and the model's window. `summary` answers from the last usage and local counts, no API calls. */
    fun getContextUsage(requestId: String): String =
        control(requestId, JSONObject().put("subtype", "get_context_usage").put("detail", "summary"))

    /**
     * Drops user message [uuid] and everything after it from the conversation (Esc Esc in the terminal). The reply's
     * `prefillText` is that message's text. Files on disk are not touched.
     */
    fun rewindConversation(requestId: String, uuid: String): String =
        control(requestId, JSONObject().put("subtype", "rewind_conversation").put("target_message_uuid", uuid))

    /** Any other control request, by [subtype] with its [fields]. The configuration requests go through here (`claudeconfig/`). */
    fun controlRequest(requestId: String, subtype: String, fields: Map<String, Any> = emptyMap()): String =
        control(requestId, JSONObject().put("subtype", subtype).apply { fields.forEach { (key, value) -> put(key, value) } })

    private fun control(requestId: String, request: JSONObject): String = JSONObject()
        .put("type", "control_request")
        .put("request_id", requestId)
        .put("request", request)
        .toString()
}
