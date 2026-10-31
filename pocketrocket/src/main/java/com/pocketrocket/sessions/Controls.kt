package com.pocketrocket.sessions

import org.json.JSONArray
import org.json.JSONObject

/** Claude Code's permission modes, as the CLI names them (`--permission-mode`, `set_permission_mode`). */
enum class PermissionMode(val cli: String, val label: String, val description: String) {
    Manual("default", "Manual", "Asks before every edit and command"),
    AcceptEdits("acceptEdits", "Accept edits", "Edits freely, asks before commands"),
    Plan("plan", "Plan", "Reads and plans, changes nothing until you approve"),
    Auto("auto", "Auto", "Runs on its own, asks only for risky actions"),
    ;

    /** The next mode in the shift+tab cycle. */
    val next: PermissionMode get() = entries[(ordinal + 1) % entries.size]

    /** The next mode when the mode under the chat box is tapped: auto, manual, plan and round again (decision 77). */
    val tapNext: PermissionMode get() = when (this) {
        Auto -> Manual
        Manual -> Plan
        Plan, AcceptEdits -> Auto
    }

    companion object {
        /** `bypassPermissions` and `dontAsk` have no button here; they read as the nearest one. */
        fun of(cli: String?): PermissionMode? = when (cli) {
            null -> null
            "bypassPermissions" -> Auto
            "dontAsk" -> Manual
            else -> entries.firstOrNull { it.cli == cli }
        }
    }
}

/**
 * A model the CLI offers (`models` in its initialize reply). [value] is what `set_model` takes. [effortLevels] is empty
 * when it reports none (`supportedEffortLevels`, measured on 2.1.314).
 */
data class ModelOption(val value: String, val displayName: String, val description: String, val resolvedModel: String?, val effortLevels: List<String> = emptyList()) {
    companion object {
        fun listFrom(models: JSONArray?): List<ModelOption> = models.objects().mapNotNull {
            val value = it.optString("value").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val levels = it.optJSONArray("supportedEffortLevels")
            ModelOption(
                value, it.optString("displayName").ifBlank { value }, it.optString("description"), it.optString("resolvedModel").ifBlank { null },
                if (levels == null) emptyList() else (0 until levels.length()).mapNotNull { i -> levels.optString(i).takeIf(String::isNotBlank) },
            )
        }
    }
}

/** A slash command, skill or plugin command the CLI reports. */
data class SlashCommand(val name: String, val description: String = "", val argumentHint: String = "") {
    companion object {
        /** From the initialize reply: `commands` minus `terminal_slash_commands` (those need the terminal's UI). */
        fun listFrom(reply: JSONObject): List<SlashCommand> {
            val terminalOnly = reply.optJSONArray("terminal_slash_commands").strings().toSet()
            return reply.optJSONArray("commands").objects()
                .map { SlashCommand(it.optString("name"), it.optString("description"), it.optString("argumentHint")) }
                .filter { it.name.isNotBlank() && it.name !in terminalOnly }
        }
    }
}

/**
 * Tokens in the conversation now, of the model's [window] (null until the CLI reports it). [afterCompact] while
 * [used] comes from a compaction and no reply has been counted since.
 */
data class ContextUsage(val used: Long, val window: Long? = null, val afterCompact: Boolean = false)

/** One tool call a subagent made, shown nested in its card. */
data class AgentStep(val id: String, val name: String, val summary: String, val done: Boolean = false, val isError: Boolean = false)

/**
 * What a Task/Agent tool call's subagent is doing: its [description], what it reports doing now ([activity]),
 * its own tool calls ([steps]) and, at the end, its [status] and [summary].
 *
 * The numbers on its card: [tokens], [toolUses] and [durationMs] as the CLI reports them (null until it does), and
 * [startedAt]/[endedAt], wall-clock times [Session] stamps when it sees the agent start and end. A replayed agent has
 * no stamps, so its card never ticks. A [background] agent's tool result comes at once; it runs until its notification.
 */
data class AgentRun(
    val description: String,
    val type: String? = null,
    val activity: String? = null,
    val steps: List<AgentStep> = emptyList(),
    val status: String? = null,
    val summary: String? = null,
    val background: Boolean = false,
    val tokens: Long? = null,
    val toolUses: Int? = null,
    val durationMs: Long? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) {
    /** Tool calls so far: the CLI's count, else the nested steps seen. */
    val tools: Int get() = toolUses ?: steps.size

    /** Time taken: the CLI's own figure once it ends, else the stamps ([now] while it runs); null when unknown. */
    fun elapsedMs(running: Boolean, now: Long): Long? {
        if (!running && durationMs != null) return durationMs
        val start = startedAt ?: return durationMs
        return ((if (running) now else endedAt ?: return durationMs) - start).coerceAtLeast(0)
    }
}

/** True while this Task/Agent call's subagent still works: no final status, not stopped, and no result yet (a background one: seen starting live). */
val ChatItem.Tool.agentRunning: Boolean
    get() {
        val run = agent ?: return false
        if (run.status != null || run.endedAt != null || isError) return false
        return result == null || (run.background && run.startedAt != null)
    }

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
