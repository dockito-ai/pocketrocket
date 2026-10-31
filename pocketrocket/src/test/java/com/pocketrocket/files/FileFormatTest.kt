package com.pocketrocket.files

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Decision 80: how the file manager orders entries and writes sizes and times. */
class FileFormatTest {
    private fun entry(name: String, dir: Boolean = false) = FileEntry(name, "/h/$name", dir, false, 0, 0)

    @Test fun foldersFirstThenFilesByNameIgnoringCase() {
        val sorted = FileListing.sorted(listOf(entry("b.txt"), entry("Zeta", dir = true), entry("A.md"), entry("alpha", dir = true), entry(".git", dir = true), entry("a.md")))
        assertEquals(listOf(".git", "alpha", "Zeta", "A.md", "a.md", "b.txt"), sorted.map { it.name })
    }

    @Test fun hiddenEntriesOnlyWhenAsked() {
        val all = listOf(entry(".env"), entry("app", dir = true), entry(".claude", dir = true))
        assertEquals(listOf("app"), FileListing.visible(all, showHidden = false).map { it.name })
        assertEquals(3, FileListing.visible(all, showHidden = true).size)
    }

    @Test fun sizes() {
        assertEquals("0 B", FileFormat.size(0))
        assertEquals("1023 B", FileFormat.size(1023))
        assertEquals("1 KB", FileFormat.size(1024))
        assertEquals("1.5 KB", FileFormat.size(1536))
        assertEquals("12 KB", FileFormat.size(12 * 1024 + 300))
        assertEquals("3.4 MB", FileFormat.size((3.4 * 1024 * 1024).toLong()))
        assertEquals("10 MB", FileFormat.size(10 * 1024 * 1024 - 1))
        assertEquals("2 GB", FileFormat.size(2L * 1024 * 1024 * 1024))
    }

    @Test fun itemCounts() {
        assertEquals("Empty", FileFormat.items(0))
        assertEquals("1 item", FileFormat.items(1))
        assertEquals("7 items", FileFormat.items(7))
        assertEquals("Can't read", FileFormat.items(null))
    }

    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0) =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    @Test fun times() {
        val now = at(2026, 10, 31, 15, 30)
        assertEquals("09:05", FileFormat.modified(at(2026, 10, 31, 9, 5), now, zone))
        assertEquals("Yesterday", FileFormat.modified(at(2026, 10, 30, 23, 59), now, zone))
        assertEquals("12 Oct", FileFormat.modified(at(2026, 10, 12), now, zone))
        assertEquals("1 Jan", FileFormat.modified(at(2026, 1, 1), now, zone))
        assertEquals("31 Dec 2025", FileFormat.modified(at(2025, 12, 31), now, zone))
        assertEquals("", FileFormat.modified(0, now, zone))
    }

    @Test fun yesterdayAcrossNewYear() {
        assertEquals("Yesterday", FileFormat.modified(at(2025, 12, 31, 20), at(2026, 1, 1, 8), zone))
    }

    @Test fun rowDetails() {
        val now = at(2026, 10, 31, 15, 30)
        val file = FileEntry("a.kt", "/h/a.kt", false, false, 2048, at(2026, 10, 31, 9, 5))
        val dir = FileEntry("src", "/h/src", true, false, 0, at(2026, 10, 12), items = 3)
        val link = FileEntry("shared", "/h/shared", true, true, 0, at(2026, 10, 12), items = 1)
        assertEquals("2 KB · 09:05", FileFormat.details(file, now, zone))
        assertEquals("3 items · 12 Oct", FileFormat.details(dir, now, zone))
        assertEquals("1 item · 12 Oct · link", FileFormat.details(link, now, zone))
    }
}
