package com.pocketrocket.dictation

/**
 * The words of one listening, across the recognizer restarts that keep it going through pauses. Each recognizer run
 * streams guesses for its own stretch of speech ([partial], replaced each time) and ends with its settled words, which
 * join [settled]. A run that ends without settling (no match, a timeout) keeps its last guess rather than losing it.
 * Pure, so it can be tested off the phone.
 */
class Transcript {
    var settled: String = ""
        private set
    var partial: String = ""
        private set

    /** Everything heard so far, settled words then the current guess. */
    val text: String get() = join(settled, partial)

    val isEmpty: Boolean get() = text.isBlank()

    /** A guess for the current run. Returns true when it differs from the last one (counts as hearing speech). */
    fun guess(words: String): Boolean {
        val tidy = words.trim()
        if (tidy == partial) return false
        partial = tidy
        return tidy.isNotEmpty()
    }

    /** The current run's settled words; null when it settled on nothing (then its last guess is kept). */
    fun settle(words: String?) {
        val tidy = words?.trim().orEmpty()
        settled = join(settled, tidy.ifEmpty { partial })
        partial = ""
    }

    /** The run ended without a result: keep what it guessed. */
    fun endRun() = settle(null)

    fun clear() {
        settled = ""
        partial = ""
    }

    companion object {
        /** Two pieces of speech with one space between; blank pieces drop out. */
        fun join(a: String, b: String): String = when {
            a.isBlank() -> b.trim()
            b.isBlank() -> a.trim()
            else -> a.trim() + " " + b.trim()
        }

        /**
         * ✕ asks before throwing away more than a sentence: a sentence end followed by more words, or more than
         * [MAX_WORDS] words (recognizers often leave punctuation out).
         */
        fun worthAsking(text: String): Boolean {
            val tidy = text.trim()
            if (tidy.isEmpty()) return false
            if (tidy.split(Regex("\\s+")).size > MAX_WORDS) return true
            return Regex("[.!?]\\s+\\S").containsMatchIn(tidy)
        }

        private const val MAX_WORDS = 12
    }
}
