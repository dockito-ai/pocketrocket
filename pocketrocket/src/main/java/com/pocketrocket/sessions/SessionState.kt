package com.pocketrocket.sessions

import com.pocketrocket.claudeconfig.ClaudeIdentity
import com.pocketrocket.sessions.attachments.Attachment
import org.json.JSONObject

/** A tool use waiting for the user's answer. */
data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val summary: String,
    val input: JSONObject,
    val reason: String?,
    /** What "Always allow" would save, or null when the CLI offered nothing. */
    val alwaysAllow: AlwaysAllow? = null,
) {
    /** Claude finished planning and asks to start (ExitPlanMode): shown as a plan to approve. */
    val isPlan: Boolean get() = toolName == "ExitPlanMode"
}

/**
 * What to put in the chat box: a rewound message with its [attachments]. [key] changes each time so the same text
 * can land twice. [undo] instead puts back what the box held before the previous draft (Edit's Undo).
 */
data class Draft(val text: String, val key: Int, val attachments: List<Attachment> = emptyList(), val undo: Boolean = false)

/**
 * An Edit not yet sent to the CLI, so Undo can still take it back: the message was at [at], and [dropped] (it and
 * everything after it) left the chat.
 */
data class PendingRewind(val uuid: String, val at: Int, val dropped: List<ChatItem>)

/** Everything the UI shows about one session. Immutable; [Session] publishes a new copy per change. */
data class SessionState(
    val id: String,
    val folder: String,
    val title: String,
    val demo: Boolean,
    /** The Claude account this session runs as. */
    val profile: ClaudeProfile,
    /** Claude Code's conversation id, for `--resume`. */
    val conversationId: String,
    val status: SessionStatus = SessionStatus.Starting,
    val items: List<ChatItem> = emptyList(),
    val permission: PendingPermission? = null,
    /** The model the CLI says is in use (resolved id, like claude-sonnet-5-5). */
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.Manual,
    /** The models the CLI offers, for switching. Empty until its initialize reply. */
    val models: List<ModelOption> = emptyList(),
    /** The account and Claude Code version, from the process's initialize reply. Null until it answers. */
    val identity: ClaudeIdentity? = null,
    val context: ContextUsage? = null,
    /** Slash commands, skills and plugin commands the CLI reports, for the picker. */
    val commands: List<SlashCommand> = emptyList(),
    /** /compact is running. */
    val compacting: Boolean = false,
    /** Messages sent while Claude works, shown above the chat box until Claude Code reads them. */
    val queued: List<ChatItem.User> = emptyList(),
    /** Text to put back in the chat box (a rewound message). */
    val draft: Draft? = null,
    /** An Edit waiting a few seconds for Undo before the CLI is told. */
    val rewind: PendingRewind? = null,
    /** Items appended so far; keeps item ids unique when a rewind drops some. */
    val seq: Int = 0,
    /** The saved last line, shown until a restored session's history is loaded. */
    val preview: String? = null,
) {
    /** Claude is on a turn: working or waiting for a permission answer. */
    val busy: Boolean
        get() = status == SessionStatus.Working || status == SessionStatus.WaitingForPermission

    /** The model's name as the CLI's own list shows it ("Sonnet 5.5"), else a tidied id. */
    val modelName: String?
        get() = model?.let { id ->
            models.firstOrNull { it.resolvedModel == id && it.value != "default" }?.displayName ?: prettyModel(id)
        }

    /** Subagents at work now; the status line shows how many while any run. */
    val runningAgents: Int
        get() = items.count { it is ChatItem.Tool && it.agentRunning }

    /** Shells running in the background; the status line shows how many while any run. */
    val runningShells: Int
        get() = items.count { it is ChatItem.Tool && it.backgroundShell && it.shellRunning }

    /**
     * The status cards and tab dots show: a session whose turn ended while shells still run in the background is
     * still working, so it does not read Done while a build goes on.
     */
    val shownStatus: SessionStatus
        get() = if (runningShells > 0 && (status == SessionStatus.Done || status == SessionStatus.WaitingForInput)) SessionStatus.Working else status

    /** The line a session card shows: the latest thing said or done. */
    val lastLine: String
        get() = permission?.let { "Allow ${it.toolName}?  ${it.summary}" }
            ?: items.lastOrNull()?.let {
                when (it) {
                    is ChatItem.User -> it.text.ifEmpty { "📎 " + it.attachments.joinToString { a -> a.name } }
                    is ChatItem.Assistant -> it.text
                    is ChatItem.Tool -> "${it.name} · ${it.summary}"
                    is ChatItem.Error -> it.text
                    is ChatItem.Notice -> it.text
                }
            }
            ?: preview
            ?: "Say what to work on"
}

/** claude-haiku-4-5-20251001 -> Haiku 4.5; anything else is returned as is. */
internal fun prettyModel(id: String): String {
    val match = Regex("""^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?(?:\[1m])?$""").find(id) ?: return id
    val (family, major, minor) = match.destructured
    return family.replaceFirstChar { it.uppercase() } + " " + major + (if (minor.isNotEmpty()) ".$minor" else "")
}
