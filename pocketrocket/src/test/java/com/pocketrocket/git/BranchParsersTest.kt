package com.pocketrocket.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchParsersTest {

    /** One `for-each-ref --format=[GitParsers.BRANCH_FORMAT]` record. */
    private fun ref(
        ref: String, sha: String = "a1", upstream: String = "", track: String = "", time: Long = 0,
        head: Boolean = false, worktree: String = "", author: String = "Shankar", subject: String = "Subject",
    ) = listOf(ref, sha, upstream, track, time.toString(), if (head) "*" else " ", worktree, author, subject)
        .joinToString("\u001f") + "\u001e\n"

    @Test
    fun localNewestFirstWithTracking() {
        val out = ref("refs/heads/main", upstream = "origin/main", time = 100, worktree = "/repo") +
            ref("refs/heads/feat/attachments", upstream = "origin/feat/attachments", track = "ahead 2", time = 300, head = true, worktree = "/repo") +
            ref("refs/heads/fix/retry", upstream = "origin/fix/retry", track = "ahead 1, behind 3", time = 200, worktree = "/ws/.worktrees/app-fix-retry") +
            ref("refs/heads/old", upstream = "origin/old", track = "gone", time = 50)
        val list = GitParsers.branches(out)
        assertEquals(listOf("feat/attachments", "fix/retry", "main", "old"), list.local.map { it.name })
        val feat = list.local[0]
        assertTrue(feat.current)
        assertEquals(2, feat.ahead)
        assertEquals(0, feat.behind)
        assertEquals("feat/attachments", list.current?.name)
        val fix = list.local[1]
        assertEquals(1, fix.ahead)
        assertEquals(3, fix.behind)
        assertEquals("/ws/.worktrees/app-fix-retry", fix.worktree)
        assertTrue(list.local[3].upstreamGone)
        assertNull(list.local[3].worktree)
        assertFalse(list.local[2].remote)
    }

    @Test
    fun remoteOnlyLeavesOutTrackedAndHead() {
        val out = ref("refs/heads/main", upstream = "origin/main", time = 100) +
            ref("refs/heads/feat/x", time = 90) +
            ref("refs/remotes/origin/HEAD", time = 100) +
            ref("refs/remotes/origin/main", time = 100) +
            ref("refs/remotes/origin/feat/x", time = 90) +
            ref("refs/remotes/origin/chore/kotlin", time = 120, author = "dependabot[bot]") +
            ref("refs/remotes/origin/docs", time = 130)
        val list = GitParsers.branches(out)
        assertEquals(listOf("origin/docs", "origin/chore/kotlin"), list.remoteOnly.map { it.name })
        val kotlin = list.remoteOnly[1]
        assertTrue(kotlin.remote)
        assertEquals("chore/kotlin", kotlin.shortName)
        assertEquals("dependabot[bot]", kotlin.author)
    }

    @Test
    fun subjectWithSeparatorsAndEmptyOutput() {
        assertEquals(BranchList.Empty, GitParsers.branches(""))
        val list = GitParsers.branches(ref("refs/heads/main", subject = "Fix: a, b; c"))
        assertEquals("Fix: a, b; c", list.local.single().subject)
    }

    @Test
    fun stashMarker() {
        val out = "stash@{0}\u001fOn main: wip\n" +
            "stash@{1}\u001fOn feat/x: pocketrocket:feat/x\n" +
            "stash@{2}\u001fOn feat/xy: pocketrocket:feat/xy\n"
        assertEquals("stash@{1}", GitParsers.stashFor(out, "feat/x"))
        assertEquals("stash@{2}", GitParsers.stashFor(out, "feat/xy"))
        assertNull(GitParsers.stashFor(out, "main"))
        assertNull(GitParsers.stashFor("", "main"))
    }
}
