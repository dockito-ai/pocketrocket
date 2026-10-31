package com.pocketrocket.sessions.attachments

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What was handed to a session from outside its chat box (Share to PocketRocket), waiting for that session's
 * composer to pick it up: files go to its tray, text to its box.
 */
object PendingAttachments {
    data class Waiting(val attachments: List<Attachment> = emptyList(), val text: String? = null)

    private val _waiting = MutableStateFlow<Map<String, Waiting>>(emptyMap())
    val waiting: StateFlow<Map<String, Waiting>> = _waiting.asStateFlow()

    fun offer(sessionId: String, attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        _waiting.update { all ->
            val now = all[sessionId] ?: Waiting()
            all + (sessionId to now.copy(attachments = now.attachments + attachments))
        }
    }

    fun offerText(sessionId: String, text: String) {
        _waiting.update { all ->
            val now = all[sessionId] ?: Waiting()
            all + (sessionId to now.copy(text = listOfNotNull(now.text, text).joinToString("\n")))
        }
    }

    /** Removes and returns what waits for [sessionId], or null when nothing does. */
    fun take(sessionId: String): Waiting? {
        var taken: Waiting? = null
        _waiting.update { all -> taken = all[sessionId]; all - sessionId }
        return taken
    }
}
