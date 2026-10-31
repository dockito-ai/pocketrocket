package com.pocketrocket.files

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** How sizes, counts and times read in the file manager. */
object FileFormat {
    private val units = listOf("KB", "MB", "GB", "TB")

    /** "0 B", "900 B", "1.5 KB", "12 KB", "3.4 MB": binary units, one decimal below 10. */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val shown = if (value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else value.toLong().toString()
        return "$shown ${units[unit]}"
    }

    /** "Empty", "1 item", "12 items"; "Can't read" when unknown. */
    fun items(count: Int?): String = when (count) {
        null -> "Can't read"
        0 -> "Empty"
        1 -> "1 item"
        else -> "$count items"
    }

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * When a file changed, short: "14:05" today, "Yesterday", "12 Oct" this year, "12 Oct 2025" before.
     * [now] and [zone] are there for tests.
     */
    fun modified(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        if (epochMillis <= 0) return ""
        val then = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
        val today = Calendar.getInstance(zone).apply { timeInMillis = now }
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val day = "${then.get(Calendar.DAY_OF_MONTH)} ${months[then.get(Calendar.MONTH)]}"
        return when {
            sameDay(then, today) -> String.format(Locale.US, "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
            sameDay(then, yesterday) -> "Yesterday"
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> day
            else -> "$day ${then.get(Calendar.YEAR)}"
        }
    }

    /** A row's second line: "12 KB · 14:05" for a file, "3 items · 12 Oct" for a folder, "link" added for a symlink. */
    fun details(entry: FileEntry, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        listOfNotNull(
            if (entry.isDir) items(entry.items) else size(entry.size),
            modified(entry.modified, now, zone).ifEmpty { null },
            "link".takeIf { entry.isLink },
        ).joinToString(" · ")
}
