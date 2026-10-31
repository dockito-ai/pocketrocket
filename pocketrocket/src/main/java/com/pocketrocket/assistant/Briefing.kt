package com.pocketrocket.assistant

import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus

/** Session state in words, for [Router]'s answers. */
object Briefing {
    /** The first suggestion in an empty Rocket chat. */
    const val WHATS_HAPPENING = "What's happening?"

    private val COUNTS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")

    /** One line for many sessions: the most pressing thing first. */
    fun headline(sessions: List<SessionState>): String {
        val needs = sessions.count { it.permission != null }
        val failed = sessions.count { it.status == SessionStatus.Error }
        val working = sessions.count { it.shownStatus == SessionStatus.Working || it.shownStatus == SessionStatus.Starting }
        return when {
            needs > 0 -> "${count(needs)} ${if (needs == 1) "thing needs" else "things need"} you."
            failed > 0 -> "${count(failed)} ${if (failed == 1) "session" else "sessions"} failed."
            working > 0 -> "${count(working)} ${if (working == 1) "session is" else "sessions are"} working."
            sessions.isEmpty() -> "No sessions yet."
            else -> "All clear for now."
        }
    }

    /** "api-server wants to run a command". */
    fun request(title: String, asked: PendingPermission): String = "$title ${wants(asked)}"

    /** "is working", "failed": what comes after a session's name. */
    fun doing(status: SessionStatus): String = when (status) {
        SessionStatus.Starting -> "is starting"
        SessionStatus.Working -> "is working"
        SessionStatus.WaitingForInput -> "is ready"
        SessionStatus.WaitingForPermission -> "is waiting on you"
        SessionStatus.Done -> "is done"
        SessionStatus.Error -> "failed"
        SessionStatus.Paused -> "is paused"
    }

    private fun wants(asked: PendingPermission): String = when (asked.toolName) {
        "Bash" -> "wants to run a command"
        "Edit", "MultiEdit", "NotebookEdit" -> "wants to edit a file"
        "Write" -> "wants to write a file"
        "Read" -> "wants to read a file"
        "WebFetch", "WebSearch" -> "wants to look something up online"
        "Task", "Agent" -> "wants to start a subagent"
        "ExitPlanMode" -> "has a plan to approve"
        else -> "wants to use ${asked.toolName}"
    }

    private fun count(n: Int) = COUNTS.getOrElse(n) { "$n" }
}
