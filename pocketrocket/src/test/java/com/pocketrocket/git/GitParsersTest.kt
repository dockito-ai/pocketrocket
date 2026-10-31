package com.pocketrocket.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitParsersTest {

    private fun z(vararg records: String) = records.joinToString("") { it + "\u0000" }

    @Test
    fun status() {
        val out = z(
            "# branch.oid 1234567890abcdef",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -1",
            "1 M. N... 100644 100644 100644 aaa bbb src/a.kt",
            "1 .M N... 100644 100644 100644 aaa bbb src/b.kt",
            "1 MM N... 100644 100644 100644 aaa bbb with space.txt",
            "2 R. N... 100644 100644 100644 aaa bbb R100 new.kt", "old.kt",
            "u UU N... 100644 100644 100644 100644 aaa bbb ccc conflict.kt",
            "? notes.txt",
        )
        val s = GitParsers.status(out)
        assertEquals(BranchInfo("main", "1234567890abcdef", "origin/main", 2, 1), s.branch)
        assertEquals(listOf("src/a.kt", "with space.txt", "new.kt"), s.inArea(ChangeArea.Staged).map { it.path })
        assertEquals(listOf("src/b.kt", "with space.txt"), s.inArea(ChangeArea.Unstaged).map { it.path })
        assertEquals("old.kt", s.inArea(ChangeArea.Staged).last().originalPath)
        assertEquals('R', s.inArea(ChangeArea.Staged).last().kind)
        assertEquals(listOf("conflict.kt"), s.inArea(ChangeArea.Conflicted).map { it.path })
        assertEquals(listOf("notes.txt"), s.inArea(ChangeArea.Untracked).map { it.path })
    }

    @Test
    fun detachedAndInitial() {
        val s = GitParsers.status(z("# branch.oid (initial)", "# branch.head (detached)"))
        assertNull(s.branch.head)
        assertNull(s.branch.oid)
        assertNull(s.branch.upstream)
    }

    @Test
    fun numstatWithRenameAndBinary() {
        val counts = GitParsers.numstat(z("3\t1\tsrc/a.kt", "-\t-\timg.png", "2\t0\t", "old.kt", "new.kt"))
        assertEquals(3 to 1, counts["src/a.kt"])
        assertEquals(null to null, counts["img.png"])
        assertEquals(2 to 0, counts["new.kt"])
    }

    @Test
    fun commitFiles() {
        val out = z(":100644 100644 aaa bbb M", "src/a.kt", ":000000 100644 000 ccc A", "b.kt") +
            "\n" + z("4\t2\tsrc/a.kt", "7\t0\tb.kt")
        val files = GitParsers.commitFiles(out)
        assertEquals(listOf("src/a.kt", "b.kt"), files.map { it.path })
        assertEquals(listOf('M', 'A'), files.map { it.kind })
        assertEquals(4, files[0].added)
        assertEquals(7, files[1].added)
    }

    @Test
    fun log() {
        val out = "abc123\u001fabc\u001fAda\u001f1700000000\u001fFirst\u001e\ndef456\u001fdef\u001fBob\u001f1700000100\u001fSecond: with | chars\u001e\n"
        val commits = GitParsers.log(out, unpushed = setOf("abc123"))
        assertEquals(2, commits.size)
        assertFalse(commits[0].pushed)
        assertTrue(commits[1].pushed)
        assertEquals("Second: with | chars", commits[1].subject)
    }

    @Test
    fun lsTreeFoldersFirst() {
        val out = z("100644 blob aaa\tsrc/z.kt", "040000 tree bbb\tsrc/lib", "100644 blob ccc\tsrc/A.kt", "160000 commit ddd\tsrc/sub")
        val entries = GitParsers.lsTree(out)
        assertEquals(listOf("lib", "A.kt", "sub", "z.kt"), entries.map { it.name })
        assertTrue(entries[0].isDir)
        assertTrue(entries[2].isSubmodule)
    }

    @Test
    fun diffNumbers() {
        val out = """
            diff --git a/f b/f
            index 1..2 100644
            --- a/f
            +++ b/f
            @@ -10,3 +10,4 @@ fun x()
             keep
            -old
            +new
            +more
             tail
            \ No newline at end of file
        """.trimIndent()
        val d = GitParsers.diff(out)
        assertEquals(2, d.added)
        assertEquals(1, d.deleted)
        val lines = d.rows.filterIsInstance<DiffRow.Line>()
        assertEquals(DiffRow.Line(' ', 10, 10, "keep"), lines[0])
        assertEquals(DiffRow.Line('-', 11, null, "old"), lines[1])
        assertEquals(DiffRow.Line('+', null, 11, "new"), lines[2])
        assertEquals(DiffRow.Line(' ', 12, 13, "tail"), lines[4])
        assertTrue(d.rows.last() is DiffRow.Note)
    }

    @Test
    fun diffTruncates() {
        val body = (1..100).joinToString("\n") { "+line $it" }
        val d = GitParsers.diff("@@ -0,0 +1,100 @@\n$body", maxRows = 10)
        assertTrue(d.truncated)
        assertEquals(10, d.rows.size)
    }
}
