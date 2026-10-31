package com.pocketrocket.assistant

/**
 * How far a heard name is from a real one, by sound rather than spelling: recognisers write what they hear, so
 * "pocked rocket", "nodes" and "web two" should still find pocketrocket, notes and web 2.
 */
object SpokenNames {
    private val WORD_BREAK = Regex("""[^\p{L}\d]+""")
    private val NUMBERS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )
    /** Words a recogniser writes for a number; only as the last word, so "go to server" keeps its "to". */
    private val SOUNDS_LIKE_NUMBER = mapOf("won" to "1", "to" to "2", "too" to "2", "for" to "4", "ate" to "8")

    /** The name's sound: numbers as digits, then letters that sound alike folded together. */
    fun sound(text: String): String {
        val words = text.lowercase().split(WORD_BREAK).filter { it.isNotEmpty() }
        val joined = words.mapIndexed { i, word ->
            NUMBERS[word] ?: SOUNDS_LIKE_NUMBER[word]?.takeIf { i == words.lastIndex && i > 0 } ?: word
        }.joinToString("")
        val folded = joined.replace("ph", "f").replace("ck", "k").replace('c', 'k').replace('q', 'k').replace('z', 's').replace('y', 'i')
        return folded.filterIndexed { i, ch -> i == 0 || ch != folded[i - 1] }
    }

    /** The number of sound edits between [heard] and [name], or null when they're too far apart to be the same name. */
    fun distance(heard: String, name: String): Int? {
        val a = sound(heard)
        val b = sound(name)
        if (a.isEmpty() || b.isEmpty()) return null
        val allowed = allowed(maxOf(a.length, b.length))
        return edits(a, b).takeIf { it <= allowed }
    }

    /** Short names have little to go on, so they must sound the same; longer ones can slip more. */
    private fun allowed(length: Int) = when {
        length < 5 -> 0
        length < 9 -> 1
        length < 14 -> 2
        else -> 3
    }

    private fun edits(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ca ->
            val row = IntArray(b.length + 1)
            row[0] = i + 1
            b.forEachIndexed { j, cb ->
                row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (ca == cb) 0 else 1)
            }
            previous = row
        }
        return previous[b.length]
    }
}
