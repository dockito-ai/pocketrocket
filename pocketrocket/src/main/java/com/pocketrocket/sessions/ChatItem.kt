package com.pocketrocket.sessions

import com.pocketrocket.sessions.attachments.Attachment

/** One entry in a session's chat. Ids are stable so lists keep scroll and expansion state. */
sealed interface ChatItem {
    val id: String

    /** [uuid] is the message's id in Claude Code's transcript (what a rewind targets); null when unknown. */
    data class User(
        override val id: String,
        val text: String,
        val uuid: String? = null,
        val attachments: List<Attachment> = emptyList(),
    ) : ChatItem

    /**
     * Claude's text. While [streaming], [text] grows with each delta of block [block] of API message
     * [messageId]; the full `assistant` message then replaces it with the final text.
     */
    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
        val messageId: String? = null,
        val block: Int = 0,
    ) : ChatItem

    /**
     * A tool call and, once it arrives, its result. [result] is null while the tool runs. A Task/Agent call carries
     * its subagent's progress in [agent]; a Bash call Claude Code runs as a task carries its [shell].
     */
    data class Tool(
        override val id: String,
        val name: String,
        val summary: String,
        val inputJson: String,
        val result: String? = null,
        val isError: Boolean = false,
        val agent: AgentRun? = null,
        val shell: ShellRun? = null,
    ) : ChatItem

    /** Something went wrong: an API error, a failed turn or the process exiting. */
    data class Error(override val id: String, val text: String) : ChatItem

    /** A quiet line from PocketRocket itself, like "Interrupted" or "Allowed Bash". [byRocket]: it records something Rocket did for you. */
    data class Notice(override val id: String, val text: String, val byRocket: Boolean = false) : ChatItem
}
