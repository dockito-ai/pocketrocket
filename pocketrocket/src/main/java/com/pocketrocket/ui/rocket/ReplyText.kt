package com.pocketrocket.ui.rocket

import com.pocketrocket.assistant.Reply
import com.pocketrocket.assistant.RocketCard
import com.pocketrocket.ui.sessions.label

/**
 * A reply as one chat message (decision 60): the sentence, then what used to go on a card, inline as Markdown the way the
 * session chat shows code and output. Commands, output and diffs go in code blocks; Claude's own long answer is shown as it
 * wrote it, in place of the short sentence that led into it.
 */
object ReplyText {
    const val NOT_KEPT = "_Not kept after a restart. Open the session to see it._"

    fun markdown(reply: Reply): String {
        val card = reply.card ?: return reply.text
        return when (card) {
            // The brain's answer: the card held all of it, and the sentence was only its start.
            is RocketCard.Detail -> if (card.sessionId.isEmpty() && card.body.isNotEmpty()) card.body else listOfNotNull(
                reply.text,
                if (card.body.isEmpty()) NOT_KEPT else fence(card.body),
                card.note,
            ).joinToString("\n\n")
            is RocketCard.Context -> "${reply.text}\n\n`${card.tokens}`"
            is RocketCard.Changes -> reply.text + card.files.joinToString("") { file ->
                "\n\n`${file.path}` +${file.added} −${file.deleted}" + (if (file.lines.isEmpty()) "" else "\n\n" + fence(file.lines.joinToString("\n")))
            }
            is RocketCard.Status -> reply.text + "\n\n" + card.rows.joinToString("\n") { "- **${it.title}**: ${it.status.label}" }
        }
    }

    /** A code block. A line of [body] that starts with a fence would end it early in [com.pocketrocket.ui.chat.MarkdownText], so it gets an invisible character first. */
    private fun fence(body: String): String =
        "```\n" + body.trimEnd().lines().joinToString("\n") { if (it.trimStart().startsWith("```")) "\u200B$it" else it } + "\n```"
}
