package com.pocketrocket.sessions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sessions with news you have not looked at yet: Claude asked for something, finished its turn or hit an error while
 * the session was not on screen. The mark stays (on its chip and its Home card) until you open that session.
 * In memory only: after an app restart every session starts seen.
 */
object Unseen {

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** The session the user is looking at, if any; news there is seen as it happens. */
    @Volatile private var onScreen: String? = null

    /** Called with every state change of a session. */
    fun onChange(previous: SessionState, current: SessionState) {
        if (current.demo || current.id == onScreen || !isNews(previous, current)) return
        _ids.update { it + current.id }
    }

    /** The user is now looking at [sessionId] (null: at nothing); its news is seen. */
    fun setOnScreen(sessionId: String?) {
        onScreen = sessionId
        sessionId?.let(::forget)
    }

    fun forget(sessionId: String) = _ids.update { it - sessionId }

    /** The same moments that post a session notification: a question, the end of a turn, an error. */
    private fun isNews(previous: SessionState, current: SessionState): Boolean {
        val was = previous.status
        val now = current.status
        val running = was == SessionStatus.Working || was == SessionStatus.WaitingForPermission || was == SessionStatus.Starting
        return when (now) {
            SessionStatus.WaitingForPermission ->
                was != SessionStatus.WaitingForPermission || previous.permission?.requestId != current.permission?.requestId
            SessionStatus.WaitingForInput -> was == SessionStatus.Working
            SessionStatus.Done, SessionStatus.Error -> running
            else -> false
        }
    }
}
