package com.pocketrocket.sessions

/**
 * A moment worth telling the user about, between two states of a session: it posts a session notification
 * ([com.pocketrocket.notifications.SessionNotifier]) and marks the session [Unseen].
 */
enum class SessionNews {
    /** Claude asks for a permission (a new request, or a different one). */
    Asked,

    /** The session went from working to ready for a message. */
    Ready,

    /**
     * The work is done: the turn has ended and no subagent or background shell still runs. A turn that ends while
     * some do is not news yet; the last one finishing is (decision 79).
     */
    Done,

    /** The turn failed or the process died, told at once even while agents or shells run. */
    Error;

    companion object {
        private val RUNNING = setOf(SessionStatus.Working, SessionStatus.WaitingForPermission, SessionStatus.Starting)

        fun of(previous: SessionState, current: SessionState): SessionNews? {
            val was = previous.status
            val now = current.status
            return when {
                now == SessionStatus.WaitingForPermission &&
                    (was != SessionStatus.WaitingForPermission || previous.permission?.requestId != current.permission?.requestId) -> Asked
                now == SessionStatus.WaitingForInput && was == SessionStatus.Working -> Ready
                now == SessionStatus.Error -> if (was in RUNNING) Error else null
                current.shownStatus == SessionStatus.Done && previous.shownStatus in RUNNING -> Done
                else -> null
            }
        }
    }
}
