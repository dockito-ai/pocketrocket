package com.pocketrocket.ui.chat

/** The box's text after an insert, and where the inserted words sit in it (for the brief highlight and the cursor). */
data class Inserted(val text: String, val start: Int, val end: Int)

/**
 * Puts dictated [words] into [text] at the cursor ([selStart] to [selEnd]; a selection is replaced), with a space
 * either side where they would otherwise touch other words. Nothing to insert leaves the text as it is. Pure.
 */
fun insertAtCursor(text: String, selStart: Int, selEnd: Int, words: String): Inserted {
    val from = minOf(selStart, selEnd).coerceIn(0, text.length)
    val to = maxOf(selStart, selEnd).coerceIn(0, text.length)
    val spoken = words.trim()
    if (spoken.isEmpty()) return Inserted(text, from, from)
    val before = text.substring(0, from)
    val after = text.substring(to)
    val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val trail = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
    val start = before.length + lead.length
    return Inserted(before + lead + spoken + trail + after, start, start + spoken.length)
}

/**
 * The model chip under the box: "Opus 5.5 · medium", the effort only when the model has levels and one is known.
 * Before Claude Code says which model it runs, "Model".
 */
fun modelChipText(modelName: String?, effort: String?, effortLevels: List<String>): String {
    val name = modelName?.takeIf(String::isNotBlank) ?: "Model"
    val level = effort?.takeIf { it.isNotBlank() && effortLevels.isNotEmpty() }
    return if (level == null) name else "$name · $level"
}
