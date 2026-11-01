package com.pocketrocket.ui.chat

import com.pocketrocket.sessions.ChatItem

/** Where the Copy button under Claude's reply goes, and what it copies. */
internal object TurnReplies {
    /**
     * For each finished turn, the id of its last [ChatItem.Assistant] mapped to all of Claude's text in that turn,
     * joined by blank lines (tool calls left out). A turn runs from one user message to the next; the last turn counts
     * as finished only when Claude isn't [working]. A turn whose text is still streaming gets no button.
     */
    fun copyable(items: List<ChatItem>, working: Boolean): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val texts = mutableListOf<ChatItem.Assistant>()
        fun close() {
            val last = texts.lastOrNull()
            if (last != null && texts.none { it.streaming }) {
                val text = texts.joinToString("\n\n") { it.text.trim() }.trim()
                if (text.isNotEmpty()) out[last.id] = text
            }
            texts.clear()
        }
        items.forEach { item ->
            when (item) {
                is ChatItem.User -> close()
                is ChatItem.Assistant -> texts += item
                else -> Unit
            }
        }
        if (!working) close()
        return out
    }
}
