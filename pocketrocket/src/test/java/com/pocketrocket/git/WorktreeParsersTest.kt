package com.pocketrocket.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `git worktree list --porcelain -z` as git 2.51 printed it on the phone (31 Oct 2026), paths shortened. */
class WorktreeParsersTest {

    private val z = '\u0000'

    private val output = listOf(
        "worktree /h/pocketrocket", "HEAD 2a042af8f83a021ea045a965c91e12a500ae233a", "branch refs/heads/spike/flutter", "",
        "worktree /h/pocketrocket-bg", "HEAD 48f37825ecefa8704959df8135158a7a02d78e42", "branch refs/heads/feature/worktrees", "",
        "worktree /h/pocketrocket/.claude/worktrees/agent-a4446e15a08259ec8", "HEAD 88cda72faf5c1b9edbf97bbe84e05c0f8af5cdf4",
        "branch refs/heads/feature/flutter-rust-tool", "locked claude agent agent-a4446e15a08259ec8", "",
        "worktree /h/.worktrees/pocketrocket-pr-12", "HEAD 1111111111111111111111111111111111111111", "detached", "locked", "",
        "worktree /h/.worktrees/gone", "HEAD 2222222222222222222222222222222222222222", "branch refs/heads/old", "prunable gitdir file points to non-existent location", "",
    ).joinToString(z.toString()) + z

    @Test fun parsesEveryWorktree() {
        val trees = GitParsers.worktrees(output)
        assertEquals(5, trees.size)
        assertTrue(trees[0].main)
        assertEquals("spike/flutter", trees[0].branch)
        assertEquals(listOf(true, false, false, false, false), trees.map { it.main })
        assertEquals("feature/worktrees", trees[1].branch)
        assertNull(trees[1].locked)
        assertEquals("claude agent agent-a4446e15a08259ec8", trees[2].locked)
        // A PR review: detached, locked without a reason.
        assertNull(trees[3].branch)
        assertEquals("", trees[3].locked)
        assertEquals("gitdir file points to non-existent location", trees[4].prunable)
        assertEquals("2222222222222222222222222222222222222222", trees[4].head)
    }

    @Test fun emptyOutput() {
        assertEquals(emptyList<Worktree>(), GitParsers.worktrees(""))
    }

    @Test fun renamesCountOnce() {
        val status = listOf(" M a.kt", "R  new.kt", "old.kt", "?? notes.md").joinToString(z.toString()) + z
        assertEquals(3, GitParsers.changeCount(status))
        assertEquals(0, GitParsers.changeCount(""))
    }
}
