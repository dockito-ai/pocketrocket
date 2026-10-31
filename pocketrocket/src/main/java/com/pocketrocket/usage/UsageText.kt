package com.pocketrocket.usage

import java.util.Locale

/** How usage figures read on screen. */
object UsageText {

    private const val PLACE_PARTS = 3

    /** Storage in decimal units, as Android's own storage screens show it. */
    fun storage(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return when {
            gb >= 1 -> "${one(gb)} GB"
            bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
            else -> "${bytes / 1_000} KB"
        }
    }

    /** Where a path in the app's data folder is, as a terminal would name it, shortened to its last folders. */
    fun place(relative: String): String {
        val named = when {
            relative.isEmpty() -> "app data"
            relative == "files/home" || relative.startsWith("files/home/") -> "~" + relative.removePrefix("files/home")
            relative == "files/usr" || relative.startsWith("files/usr/") -> "\$PREFIX" + relative.removePrefix("files/usr")
            else -> relative
        }
        val parts = named.split('/')
        return if (parts.size <= PLACE_PARTS) named else "…/" + parts.takeLast(PLACE_PARTS).joinToString("/")
    }

    /** `Phone 128 GB total · 84 GB used · PocketRocket 5.2 GB`, with [format] the section's way of writing sizes. */
    fun phoneLine(phone: PhoneShare, mine: Long?, format: (Long) -> String): String =
        listOfNotNull(
            "Phone ${format(phone.totalBytes)} total",
            "${format(phone.usedBytes)} used",
            mine?.let { "PocketRocket ${format(it)}" },
        ).joinToString(" · ")

    /** Memory in binary units, as /proc and Android's memory screens count it. */
    fun ram(kb: Long): String {
        val gb = kb / (1024.0 * 1024.0)
        return if (gb >= 1) "${one(gb)} GB" else "${kb / 1024} MB"
    }

    /** The Settings row: `2.4 GB · 640 MB RAM`. */
    fun summary(storageBytes: Long?, ramKb: Long?): String =
        listOfNotNull(storageBytes?.let(::storage), ramKb?.let { "${ram(it)} RAM" }).joinToString(" · ").ifEmpty { "Storage and memory" }

    /** `2h 14m`, `3m 12s`, `45s`. */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    fun mah(value: Double): String = if (value < 0.1) "Under 0.1 mAh" else "${one(value)} mAh"

    /** The sentence under a battery figure, with the period it covers. */
    fun batteryNote(reading: BatteryReading.Figure): String {
        val share = reading.capacityMah?.let { capacity ->
            val percent = reading.mah / capacity * 100
            " ${if (percent < 0.1) "Under 0.1" else "About ${one(percent)}"}% of a $capacity mAh battery."
        }.orEmpty()
        return "Android’s estimate ${period(reading.onBattery)}.$share"
    }

    /** `since the last charge (2m 16s on battery)`. */
    fun period(onBattery: String?): String =
        "since the last charge" + (onBattery?.let { " ($it on battery)" } ?: "")

    private fun one(value: Double) = String.format(Locale.US, "%.1f", value)
}
