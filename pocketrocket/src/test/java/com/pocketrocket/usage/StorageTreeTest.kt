package com.pocketrocket.usage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StorageTreeTest {

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
    private fun dir(bytes: Long = 4) = WalkFs.Entry(true, bytes, 1, nextInode++, 2)
    private fun file(bytes: Long, inode: Long = nextInode++, links: Long = 1) = WalkFs.Entry(false, bytes, 1, inode, links)
    private fun link(bytes: Long = 0) = WalkFs.Entry(false, bytes, 1, nextInode++, 1, isLink = true)

    private val base = mapOf(
        "/d" to dir(),
        "/d/files" to dir(),
        "/d/files/usr" to dir(),
        "/d/files/usr/bin" to dir(),
        "/d/files/usr/bin/git" to file(1000),
        "/d/files/usr/bin/git-add" to file(1000, inode = 900, links = 2),
        "/d/files/usr/bin/git-commit" to file(1000, inode = 900, links = 2),
        "/d/files/usr/bin/vim" to file(300),
        "/d/files/usr/bin/view" to link(4),
        "/d/files/usr/lib" to dir(),
        "/d/files/usr/lib/node_modules" to dir(),
        "/d/files/usr/lib/node_modules/claude.js" to file(7000),
        "/d/files/usr/share" to dir(),
        "/d/files/usr/share/doc" to dir(),
        "/d/files/usr/tmp" to dir(),
        "/d/files/usr/tmp/x" to file(50),
        "/d/files/usr/var" to dir(),
        "/d/files/usr/var/lib" to dir(),
        "/d/files/usr/var/lib/dpkg" to dir(),
        "/d/files/usr/var/lib/dpkg/info" to dir(),
        "/d/files/usr/var/lib/dpkg/info/git.list" to file(8),
        "/d/files/usr/var/lib/dpkg/info/git.md5sums" to file(8),
        "/d/files/usr/var/lib/dpkg/info/vim:aarch64.list" to file(8),
        "/d/files/home" to dir(),
        "/d/files/home/code" to dir(),
        "/d/files/home/code/app" to dir(),
        "/d/files/home/code/app/main.kt" to file(70),
        "/d/files/home/storage" to link(8),
        "/d/files/home/.claude.json" to file(16),
        "/d/files/home/.claude" to dir(),
        "/d/files/home/.claude/x" to file(32),
        "/d/files/home/.pocketrocket" to dir(),
        "/d/files/home/.pocketrocket/state" to file(11),
        "/d/files/home/.pocketrocket/claude" to dir(),
        "/d/files/home/.pocketrocket/claude/bin" to file(64),
        "/d/files/home/.pocketrocket/accounts" to dir(),
        "/d/files/home/.cache" to dir(),
        "/d/files/home/.cache/y" to file(128),
        "/d/cache" to dir(),
        "/d/cache/z" to file(256),
        "/d/shared_prefs" to dir(),
        "/d/shared_prefs/a.xml" to file(512),
    )

    private val lists = mapOf(
        "/d/files/usr/var/lib/dpkg/info/git.list" to "/.\n/data/data/com.termux/files/usr/bin\n/data/data/com.termux/files/usr/bin/git\n" +
            "/data/data/com.termux/files/usr/bin/git-add\n/data/data/com.termux/files/usr/bin/git-commit\n/data/data/app.pocket/files/usr/bin/gone\n",
        "/d/files/usr/var/lib/dpkg/info/vim:aarch64.list" to "/data/data/app.pocket/files/usr/bin/vim\n/data/data/app.pocket/files/usr/bin/view\n/data/data/app.pocket/files/usr/bin/git\n",
    )

    /** A tree over [entries] the way the screen gets one, after the walk that makes the totals. */
    private fun treeOf(entries: Map<String, WalkFs.Entry>, fs: FakeFs = FakeFs(entries), apkBytes: Long = 0): StorageTree {
        val links = HardLinks()
        val bytes = walkDisk(fs, "/d", StorageKind.Other, StorageUsage.FOLDERS, links).bytes.toMutableMap()
        bytes[StorageKind.Other] = (bytes[StorageKind.Other] ?: 0L) + apkBytes
        return StorageTree(fs, "/d", bytes, links) { lists[it] }
    }

    /** Opens every row under [node], checking at each level that the rows add up to the row that opened them. */
    private fun assertAddsUp(tree: StorageTree, node: StorageNode) {
        if (!node.opens) return
        val rows = runBlocking { tree.children(node) }
        assertEquals("rows of ${node.name}: ${rows.map { it.name to it.bytes }}", node.bytes, rows.sumOf { it.bytes })
        rows.forEach { assertAddsUp(tree, it) }
    }

    private fun root(tree: StorageTree, kind: StorageKind) = tree.roots.first { it.name == kind.label }
    private fun List<StorageNode>.named(name: String) = first { it.name == name }

    @Test fun `every level of every kind adds up to the row that opened it`() {
        val tree = treeOf(base, apkBytes = 5000)
        tree.roots.forEach { assertAddsUp(tree, it) }
    }

    @Test fun `packages are sized by what their files take, largest first, and a shared path counts once`(): Unit = runBlocking {
        val tree = treeOf(base)
        val rows = tree.children(root(tree, StorageKind.Packages))
        val names = rows.map { it.name }
        assertEquals(listOf("Not from a package", "git", "vim"), names)
        // git-add and git-commit are one file on disk, and vim's claim on bin/git loses to git.
        assertEquals(2000L, rows.named("git").bytes)
        assertEquals(304L, rows.named("vim").bytes)
    }

    @Test fun `a package opens to its largest files`(): Unit = runBlocking {
        val tree = treeOf(base)
        val git = tree.children(root(tree, StorageKind.Packages)).named("git")
        val files = tree.children(git).filter { it.bytes > 0 }
        assertEquals(setOf(1000L), files.map { it.bytes }.toSet())
        assertTrue(files.all { it.name.startsWith("bin/") && !it.opens })
    }

    @Test fun `Not from a package holds unowned files and folders, not package files or tmp`(): Unit = runBlocking {
        val tree = treeOf(base)
        val unowned = tree.children(root(tree, StorageKind.Packages)).named("Not from a package")
        val top = tree.children(unowned)
        val lib = top.named("lib")
        assertEquals(7008L, lib.bytes)
        assertTrue(lib.opens)
        assertFalse(top.any { it.name == "tmp" })
        // bin holds only package files, share holds nothing at all: both just folder entries, pooled.
        assertFalse(top.any { it.name == "bin" || it.name == "share" })
        assertEquals("2 folders of package files", top.first { it.name.contains("folders of package files") }.name)
        assertNotNull(tree.children(lib).named("node_modules"))
    }

    @Test fun `a folder under a kind leaves out what belongs to other kinds`(): Unit = runBlocking {
        val tree = treeOf(base)
        val rows = tree.children(root(tree, StorageKind.Home))
        assertFalse(rows.any { it.name == ".claude" || it.name == ".claude.json" || it.name == ".cache" })
        val pocket = rows.named(".pocketrocket")
        assertEquals(15L, pocket.bytes)
        val inside = tree.children(pocket).map { it.name }
        assertFalse("claude" in inside || "accounts" in inside)
        assertTrue("state" in inside)
    }

    @Test fun `a kind in several places opens to those places`(): Unit = runBlocking {
        val tree = treeOf(base)
        val names = tree.children(root(tree, StorageKind.Cache)).map { it.name }.toSet()
        assertEquals(setOf("cache", "~/.cache", "\$PREFIX/tmp"), names)
    }

    @Test fun `a link is a plain row, never entered`(): Unit = runBlocking {
        val fs = FakeFs(base + mapOf("/d/files/home/storage/huge" to file(999_999_999)))
        val tree = treeOf(fs.entries, fs)
        val storage = tree.children(root(tree, StorageKind.Home)).named("storage")
        assertFalse(storage.opens)
        assertEquals(8L, storage.bytes)
        assertFalse("/d/files/home/storage" in fs.listed)
        assertEquals("Link, not followed", storage.note)
    }

    @Test fun `App and other ends with what the walk can't see`(): Unit = runBlocking {
        val tree = treeOf(base, apkBytes = 5000)
        val rows = tree.children(root(tree, StorageKind.Other))
        assertEquals(5000L, rows.named("The app itself").bytes)
        assertFalse(rows.named("The app itself").opens)
        assertEquals(516L, rows.named("shared_prefs").bytes)
    }

    @Test fun `an unreadable folder is a row saying so`(): Unit = runBlocking {
        val fs = FakeFs(base, unreadable = setOf("/d/files/home/code"))
        val tree = treeOf(base, fs)
        val code = tree.children(root(tree, StorageKind.Home)).named("code")
        assertEquals("Some folders inside can’t be read", code.note)
        val rows = tree.children(code)
        assertEquals(listOf("Can’t read this folder"), rows.map { it.name })
        assertEquals(code.bytes, rows.sumOf { it.bytes })
    }

    @Test fun `a hard link split across folders adds up on both sides`() {
        val entries = base + mapOf(
            "/d/files/home/one" to dir(),
            "/d/files/home/one/a" to file(100, inode = 800, links = 2),
            "/d/files/home/two" to dir(),
            "/d/files/home/two/b" to file(100, inode = 800, links = 2),
        )
        val tree = treeOf(entries)
        assertAddsUp(tree, root(tree, StorageKind.Home))
    }

    @Test fun `an empty folder says so`(): Unit = runBlocking {
        val tree = treeOf(base + ("/d/files/home/empty" to dir()))
        val empty = tree.children(root(tree, StorageKind.Home)).named("empty")
        assertEquals(listOf("Empty folder"), tree.children(empty).map { it.name })
    }

    @Test fun `a cancelled load stops`() {
        val tree = treeOf(base)
        val job = Job().apply { cancel() }
        try {
            runBlocking(job) { tree.children(root(tree, StorageKind.Home)) }
            fail("should have been cancelled")
        } catch (_: CancellationException) {
        }
    }

    @Test fun `the rest merge into one row with their combined size`() {
        val largest = LargestRows(limit = 2)
        listOf(5L, 1L, 9L, 2L, 7L).forEach { largest.add(StorageNode("p/$it", "n$it", it)) }
        val rows = largest.rows("p")
        assertEquals(listOf("n9", "n7", "3 smaller items"), rows.map { it.name })
        assertEquals(8L, rows.last().bytes)
        assertEquals(24L, largest.total)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
    }

    @Test fun `nothing merges when it all fits`() {
        val largest = LargestRows(limit = 2)
        largest.add(StorageNode("p/a", "a", 1))
        assertEquals(listOf("a"), largest.rows("p").map { it.name })
    }

    @Test fun `list file names and lines parse`() {
        assertEquals("git", dpkgPackageName("git.list"))
        assertEquals("libc", dpkgPackageName("libc:aarch64.list"))
        assertNull(dpkgPackageName("git.md5sums"))
        assertNull(dpkgRelativePath("/."))
        assertEquals("files/usr/bin/git", dpkgRelativePath("/data/data/com.termux/files/usr/bin/git"))
        assertEquals("files/usr/bin/git", dpkgRelativePath("/data/data/app.pocket/files/usr/bin/git"))
        assertEquals("files/usr/lib", dpkgRelativePath("  /data/data/app.pocket/files/usr/lib/ "))
    }

    @Test fun `a path in two lists goes to the first package by name`() {
        val owners = dpkgOwners(listOf("zed" to "/x/files/usr/a\n/x/files/usr/b", "abc" to "/x/files/usr/a"))
        assertEquals(mapOf("files/usr/a" to "abc", "files/usr/b" to "zed"), owners)
    }

    @Test fun `only open rows show their children, and depth grows`() {
        val leaf = StorageNode("a/b/c", "c", 1)
        val mid = StorageNode("a/b", "b", 1)
        val top = StorageNode("a", "a", 1)
        val rows = flattenTree(listOf(top)) { node -> if (node === top) listOf(mid) else if (node === mid) listOf(leaf) else null }
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), rows.map { it.node.name to it.depth })
        assertEquals(listOf("a"), flattenTree(listOf(top)) { null }.map { it.node.name })
    }

    @Test fun `places read as a terminal would name them, shortened`() {
        assertEquals("~/code", UsageText.place("files/home/code"))
        assertEquals("\$PREFIX/lib", UsageText.place("files/usr/lib"))
        assertEquals("…/c/d/e", UsageText.place("files/home/a/b/c/d/e"))
        assertEquals("app data", UsageText.place(""))
    }

    @Test fun `the phone line says which figures are the phone and which the app`() {
        val line = UsageText.phoneLine(PhoneShare(128_000_000_000, 84_000_000_000), 5_200_000_000, UsageText::storage)
        assertEquals("Phone 128.0 GB total · 84.0 GB used · PocketRocket 5.2 GB", line)
        assertEquals("Phone 128.0 GB total · 84.0 GB used", UsageText.phoneLine(PhoneShare(128_000_000_000, 84_000_000_000), null, UsageText::storage))
    }

    @Test fun `the bar's parts stay in range and the app's part stays within the phone's use`() {
        val phone = PhoneShare(100, 40)
        assertEquals(0.4f, phone.usedFraction(), 0.001f)
        assertEquals(0.1f, phone.partFraction(10), 0.001f)
        assertEquals(0.4f, phone.partFraction(90), 0.001f)
        assertEquals(0f, PhoneShare(0, 0).usedFraction(), 0f)
    }
}
