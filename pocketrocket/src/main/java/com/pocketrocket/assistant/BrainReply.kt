package com.pocketrocket.assistant

/** How the brain's turn ended. */
sealed interface BrainAnswer {
    data class Said(val text: String) : BrainAnswer
    /** Claude can't be reached, or its limit is reached. [why] is the CLI's own sentence, which says when a limit resets. */
    data class Failed(val why: String, val limit: Boolean) : BrainAnswer
}

/**
 * Turns Claude's words into a [Reply] under the same rules as everything else Rocket shows. What looks like code goes in a
 * [RocketCard.Detail], which never reaches the disk and isn't read out. On a locked phone nothing Claude wrote is said or shown
 * at all, since its words can quote anything, so the screen comes after an unlock.
 */
object BrainReply {
    const val LOCKED_SHOWN = "I have an answer."
    const val LOCKED = "$LOCKED_SHOWN Unlock to see it."
    private const val NOT_SIMPLE = "Status, allow, deny and stop still work."
    private const val MAX_SPOKEN = 280
    private const val HEADING = "Rocket"
    private const val ON_SCREEN = "It's on screen."
    private val CODE = listOf(
        Regex("""`"""),
        Regex("""(?m)^\s*\$ """),
        Regex("""(?:^|\s)(?:~|\.{0,2})?/[\w.-]+/[\w./-]+"""),
        Regex("""(?:^|\s)--?[a-zA-Z][\w-]*(?:\s|$)"""),
        Regex("""\b(?:git|gh|npm|sudo|rm|curl) [a-z-]+"""),
    )

    fun looksLikeCode(text: String): Boolean = '\n' in text.trim() && text.lines().count { it.isNotBlank() } > 3 || CODE.any { it.containsMatchIn(text) } || SecretCheck.looksSecret(text)

    fun shape(answer: BrainAnswer, locked: Boolean): Reply = when (answer) {
        is BrainAnswer.Failed -> if (answer.limit) failed("I've reached the Claude limit.", answer.why, locked) else failed("I can't reach Claude right now.", answer.why, locked)
        is BrainAnswer.Said -> said(answer.text.trim(), locked)
    }

    private fun failed(first: String, why: String, locked: Boolean): Reply {
        // The CLI's error can name paths, so it's only added when it's plain.
        val extra = why.trim().takeIf { it.isNotEmpty() && !locked && !looksLikeCode(it) }?.let { " ${it.take(120)}" }.orEmpty()
        return Reply("$first$extra $NOT_SIMPLE")
    }

    private fun said(text: String, locked: Boolean): Reply {
        if (text.isEmpty()) return Reply("I don't have anything to add.")
        // Kept for the tab after an unlock, in memory only; the popup drops the card and adds "Unlock to see it" while locked.
        if (locked) return Reply(LOCKED_SHOWN, LOCKED, RocketCard.Detail(HEADING, text, null, "", ""))
        if (!looksLikeCode(text) && text.length <= MAX_SPOKEN) return Reply(text)
        // The first sentence, if that much can be said, and the rest on the card.
        val first = text.lineSequence().first().substringBefore(". ").trim().let { if (it.endsWith(".")) it else "$it." }
        val lead = first.takeIf { it.length <= MAX_SPOKEN && !looksLikeCode(it) } ?: "Here's what I found."
        return Reply(lead, Spoken.sentence("$lead $ON_SCREEN"), RocketCard.Detail(HEADING, text, null, "", ""))
    }
}
