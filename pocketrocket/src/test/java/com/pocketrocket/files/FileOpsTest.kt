package com.pocketrocket.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Decision 78: new folder, rename and delete on a real temporary folder, with symlinks pointing out of it. */
class FileOpsTest {
    private val base: File = Files.createTempDirectory("files").toFile().canonicalFile
    private val home = File(base, "home").apply { mkdirs() }
    private val outside = File(base, "outside").apply { mkdirs() }

    @After fun tearDown() {
        // Never follows links either, so the test can't wander off.
        FileOps.delete(base)
    }

    private fun file(parent: File, path: String, text: String = "x") = File(parent, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun link(at: File, to: File) = at.also { it.parentFile.mkdirs(); Files.createSymbolicLink(it.toPath(), to.toPath()) }

    @Test fun symlinksAreSeen() {
        val target = file(outside, "t.txt")
        assertTrue(FileOps.isSymlink(link(File(home, "l.txt"), target)))
        assertTrue(FileOps.isSymlink(link(File(home, "dirlink"), outside)))
        assertFalse(FileOps.isSymlink(target))
        assertFalse(FileOps.isSymlink(outside))
        assertTrue(FileListing.entry(File(home, "dirlink")).isLink)
    }

    @Test fun deletingAFolderNeverFollowsLinksOutOfIt() {
        val keep = file(outside, "keep/precious.txt")
        val tree = File(home, "tree")
        file(tree, "a.txt")
        file(tree, "sub/deeper/b.txt")
        link(File(tree, "sub/to-outside"), outside)
        link(File(tree, "to-file"), keep)
        assertNull(FileOps.delete(tree))
        assertFalse(tree.exists())
        assertTrue(keep.exists())
        assertEquals("x", keep.readText())
    }

    @Test fun deletingALinkRemovesOnlyTheLink() {
        val keep = file(outside, "keep/precious.txt")
        val l = link(File(home, "shared"), File(outside, "keep"))
        assertNull(FileOps.delete(l))
        assertFalse(home.list()!!.contains("shared"))
        assertTrue(keep.exists())
    }

    @Test fun deletingADanglingLink() {
        val l = link(File(home, "gone"), File(outside, "nothing-here"))
        assertNull(FileOps.delete(l))
        assertFalse(home.list()!!.contains("gone"))
    }

    @Test fun deletingAFile() {
        val f = file(home, "notes.md")
        assertNull(FileOps.delete(f))
        assertFalse(f.exists())
    }

    @Test fun contentsCountsWithoutFollowingLinks() {
        val tree = File(home, "tree")
        file(tree, "a.txt")
        file(tree, "sub/b.txt")
        file(outside, "many/1"); file(outside, "many/2"); file(outside, "many/3")
        link(File(tree, "sub/l"), File(outside, "many"))
        assertEquals(FileOps.Contents(files = 3, folders = 1, capped = false), FileOps.contents(tree))
        assertTrue(FileOps.contents(tree, cap = 2).capped)
    }

    @Test fun newFolder() {
        assertNull(FileOps.newFolder(home, "src"))
        assertTrue(File(home, "src").isDirectory)
        assertNotNull(FileOps.newFolder(home, "src"))
        assertNotNull(FileOps.newFolder(home, "../escape"))
        assertFalse(File(base, "escape").exists())
        assertNotNull(FileOps.newFolder(home, ""))
    }

    @Test fun renameStaysInTheFolderAndNeverReplaces() {
        val a = file(home, "a.txt", "a")
        val b = file(home, "b.txt", "b")
        assertNotNull(FileOps.rename(a, "b.txt"))
        assertEquals("b", b.readText())
        assertNotNull(FileOps.rename(a, "../a.txt"))
        assertNotNull(FileOps.rename(a, "sub/a.txt"))
        assertNull(FileOps.rename(a, "c.txt"))
        assertEquals("a", File(home, "c.txt").readText())
        assertFalse(a.exists())
    }

    @Test fun renameNeverReplacesADanglingLink() {
        val a = file(home, "a.txt")
        link(File(home, "l"), File(outside, "nothing"))
        assertNotNull(FileOps.rename(a, "l"))
        assertTrue(a.exists())
    }
}
