package com.pocketrocket.sessions.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The part of Claude Code's stream-json output a session needs (`claude -p --input-format stream-json
 * --output-format stream-json --verbose`). One stdout line is one JSON message; [StreamJsonParser] turns
 * it into zero or more of these. Everything else (hook events, rate-limit notices, usage) is ignored.
 */
sealed interface StreamEvent {

    /**
     * `system/init`: the conversation id (for `--resume`), model, cwd, permission mode and the names of the slash
     * commands (skills and plugin commands included). Sent each time a turn starts.
     */
    data class Init(
        val sessionId: String,
        val model: String?,
        val cwd: String?,
        val permissionMode: String? = null,
        val slashCommands: List<String> = emptyList(),
    ) : StreamEvent

    /**
     * Context used by the main conversation after one API call: input plus cache tokens of a top-level `assistant`
     * message's `usage` (the same total `get_context_usage` reports). [model] is the model that answered.
     */
    data class Usage(val contextTokens: Long, val model: String?) : StreamEvent

    /** `system/status`: `compacting` while /compact runs; [permissionMode] when the CLI changed mode (plan approved). */
    data class Status(val status: String?, val permissionMode: String?) : StreamEvent

    /** `system/compact_boundary`: the conversation was summarised from [preTokens] down to [postTokens]. */
    data class Compacted(val trigger: String?, val preTokens: Long, val postTokens: Long) : StreamEvent

    /** Output of a local slash command (`<local-command-stdout>` in a `user` line), tags stripped. */
    data class CommandOutput(val text: String) : StreamEvent

    /** A subagent (Task/Agent tool [parentToolUseId]) used a tool. */
    data class SubagentToolUse(val parentToolUseId: String, val id: String, val name: String, val input: JSONObject) : StreamEvent

    /** A subagent's tool finished, with what it gave back ([content]). */
    data class SubagentToolResult(val parentToolUseId: String, val toolUseId: String, val isError: Boolean, val content: String = "") : StreamEvent

    /** A text block the subagent wrote (its own messages; the main chat never shows them). */
    data class SubagentText(val parentToolUseId: String, val text: String) : StreamEvent

    /**
     * `system/task_started`, `task_progress` and `task_notification` for the subagent started by tool use [toolUseId]:
     * its task [taskId] (what `stop_task` takes), what it is doing now ([activity]), and at the end its [status]
     * (`completed`, `failed`, `stopped`, `killed`) and [summary]. Its numbers so far when the line has them: [tokens],
     * [toolUses] and, at the end, [durationMs].
     */
    data class SubagentUpdate(
        val toolUseId: String,
        val taskId: String? = null,
        val activity: String? = null,
        val status: String? = null,
        val summary: String? = null,
        val tokens: Long? = null,
        val toolUses: Int? = null,
        val durationMs: Long? = null,
    ) : StreamEvent

    /** The CLI's answer to one of our control requests ([requestId]); [response] on success, [error] otherwise. */
    data class ControlResponse(val requestId: String, val response: JSONObject?, val error: String?) : StreamEvent

    /**
     * A finished text block from an `assistant` message. With `--include-partial-messages` the CLI prints one
     * `assistant` line per content block, after that block's [TextDelta]s; [messageId] ties the two together.
     */
    data class AssistantText(val text: String, val messageId: String? = null) : StreamEvent

    /** `stream_event/content_block_delta` with a `text_delta`: a few more tokens of text block [index] of [messageId]. */
    data class TextDelta(val messageId: String, val index: Int, val text: String) : StreamEvent

    /** A `tool_use` block from an `assistant` message. */
    data class ToolUse(val id: String, val name: String, val input: JSONObject) : StreamEvent

    /**
     * One of our messages, echoed back (`--replay-user-messages`) when Claude Code reads it, with the [uuid] we sent.
     * A message sent mid-turn is read at Claude's next step, or once the turn ends.
     */
    data class UserEcho(val uuid: String) : StreamEvent

    /** A `tool_result` block, delivered inside a `user` message. */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean) : StreamEvent

    /** An assistant message the CLI made up itself to report a failure (`error` set, e.g. not logged in). */
    data class ApiError(val code: String, val text: String) : StreamEvent

    /**
     * `result`: the turn is over. [isError] covers API errors, max turns and the like. [interrupted] when the turn
     * was stopped (`terminal_reason` `aborted_*`). [contextWindow] is the answering model's window from `modelUsage`.
     */
    data class Result(
        val isError: Boolean,
        val text: String?,
        val sessionId: String?,
        val interrupted: Boolean = false,
        val contextWindow: Long? = null,
    ) : StreamEvent

    /** `control_request/can_use_tool`: Claude wants to use a tool and the host must answer allow or deny. */
    data class PermissionRequest(
        val requestId: String,
        val toolName: String,
        val input: JSONObject,
        val toolUseId: String?,
        val reason: String?,
        /** `permission_suggestions`: permission updates the CLI offers for "always allow", passed back as-is. */
        val suggestions: JSONArray = JSONArray(),
    ) : StreamEvent

    /** `prompt_suggestion`, after a turn's `result` (`--prompt-suggestions`): the message Claude Code predicts the user sends next. */
    data class PromptSuggestion(val text: String) : StreamEvent

    /** `control_cancel_request`: an earlier control request no longer needs an answer. */
    data class PermissionCancelled(val requestId: String) : StreamEvent

    /**
     * `system/task_started` with `task_type` `local_bash`: Bash call [toolUseId] runs as shell task [taskId], in the
     * [background] (`run_in_background`), or in the foreground until it ends or is moved there (2.1.316).
     */
    data class ShellStarted(val toolUseId: String, val taskId: String, val background: Boolean) : StreamEvent

    /** `system/task_updated` with `patch.is_backgrounded` true: task [taskId] was moved to the background. */
    data class TaskBackgrounded(val taskId: String) : StreamEvent

    /**
     * `system/task_notification`, for a shell: it ended with [status] (`completed`, `failed`, `stopped`). [outputFile]
     * holds everything it printed (empty for a foreground one); [exitCode] comes from the summary's "(exit code N)".
     */
    data class ShellEnded(val toolUseId: String, val status: String?, val exitCode: Int?, val outputFile: String?) : StreamEvent
}
