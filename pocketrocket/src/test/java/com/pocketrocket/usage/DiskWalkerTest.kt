package com.pocketrocket.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiskWalkerTest {

    /** A fake tree: path to entry, with folder contents worked out from the paths. */
    private class FakeFs(val entries: Map<String, WalkFs.Entry>, val unreadable: Set<String> = emptySet()) : WalkFs {
        val listed = mutableListOf<String>()
        override fun lstat(path: String) = entries[path]
        override fun list(path: String): Array<String>? {
            listed += path
            if (path in unreadable) return null
            return entries.keys.filter { it.startsWith("$path/") && '/' !in it.removePrefix("$path/") }
                .map { it.removePrefix("$path/") }.toTypedArray()
        }
    }

    private var nextInode = 1L
    private fun dir() = WalkFs.Entry(true, 0, 1, nextInode++, 2)
    private fun file(bytes: Long, inode: Long = nextInode++, links: Long = 1) = WalkFs.Entry(false, bytes, 1, inode, links)

    @Test fun `each path takes its kind or its parent's, so every byte lands once`() {
        val fs = FakeFs(
            mapOf(
                "/d" to dir(),
                "/d/loose" to file(1),
                "/d/usr" to dir(),
                "/d/usr/lib" to file(10),
                "/d/usr/tmp" to dir(),
                "/d/usr/tmp/x" to file(100),
            ),
        )
        val result = walkDisk(fs, "/d", "other", mapOf("usr" to "packages", "usr/tmp" to "cache"))
        assertEquals(mapOf("other" to 1L, "packages" to 10L, "cache" to 100L), result.bytes)
    }

    @Test fun `a link to shared storage counts as the link and is never entered`() {
        // lstat says a link is not a folder, so its target is never listed however big it is.
        val fs = FakeFs(
            mapOf(
                "/d" to dir(),
                "/d/storage" to dir(),
                "/d/storage/shared" to file(4),
                "/d/storage/shared/huge" to file(999_999_999),
            ),
        )
        val result = walkDisk(fs, "/d", "other", emptyMap())
        assertEquals(4L, result.bytes["other"])
        assertFalse("/d/storage/shared" in fs.listed)
    }

    @Test fun `a hard linked file counts once`() {
        val fs = FakeFs(mapOf("/d" to dir(), "/d/a" to file(10, inode = 500, links = 2), "/d/b" to file(10, inode = 500, links = 2)))
        assertEquals(10L, walkDisk(fs, "/d", "other", emptyMap()).bytes["other"])
    }

    @Test fun `an unreadable folder is counted and the walk goes on`() {
        val fs = FakeFs(mapOf("/d" to dir(), "/d/locked" to dir(), "/d/ok" to file(7)), unreadable = setOf("/d/locked"))
        val result = walkDisk(fs, "/d", "other", emptyMap())
        assertEquals(1, result.unreadable)
        assertEquals(7L, result.bytes["other"])
    }

    @Test fun `the real layout sorts Claude Code out of home and tmp out of packages`() {
        val fs = FakeFs(
            mapOf(
                "/d" to dir(),
                "/d/files" to dir(),
                "/d/files/home" to dir(),
                "/d/files/home/code" to file(1),
                "/d/files/home/.claude.json" to file(2),
                "/d/files/home/.pocketrocket" to dir(),
                "/d/files/home/.pocketrocket/claude" to dir(),
                "/d/files/home/.pocketrocket/claude/bin" to file(4),
                "/d/files/usr" to dir(),
                "/d/files/usr/lib" to file(8),
                "/d/files/usr/tmp" to dir(),
                "/d/files/usr/tmp/x" to file(16),
                "/d/cache" to dir(),
                "/d/cache/apt" to file(32),
            ),
        )
        val bytes = walkDisk(fs, "/d", StorageKind.Other, StorageUsage.FOLDERS).bytes
        assertEquals(1L, bytes[StorageKind.Home])
        assertEquals(6L, bytes[StorageKind.ClaudeCode])
        assertEquals(8L, bytes[StorageKind.Packages])
        assertEquals(48L, bytes[StorageKind.Cache])
    }
}
