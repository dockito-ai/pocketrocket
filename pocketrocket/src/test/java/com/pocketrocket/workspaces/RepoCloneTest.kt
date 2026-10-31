package com.pocketrocket.workspaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepoCloneTest {
    @Test fun readsCountsSizeAndSpeed() {
        assertEquals(
            CloneProgress.Running("Receiving objects", 45, 1234, 2742, "12.3 MB", "3.2 MB/s"),
            RepoClone.parse("Receiving objects:  45% (1234/2742), 12.34 MiB | 3.21 MiB/s"),
        )
    }

    @Test fun readsTheDoneLine() {
        assertEquals(
            CloneProgress.Running("Receiving objects", 100, 2742, 2742, "128 MB", "4.5 MB/s"),
            RepoClone.parse("Receiving objects: 100% (2742/2742), 128.40 MiB | 4.50 MiB/s, done."),
        )
    }

    @Test fun readsCountsWithoutSize() {
        assertEquals(CloneProgress.Running("Resolving deltas", 60, 600, 1000), RepoClone.parse("Resolving deltas:  60% (600/1000)"))
        assertEquals(CloneProgress.Running("Updating files", 50, 40, 80), RepoClone.parse("Updating files:  50% (40/80)"))
        assertEquals(CloneProgress.Running("Counting objects", 100, 12, 12), RepoClone.parse("remote: Counting objects: 100% (12/12), done."))
    }

    @Test fun ignoresOtherLines() {
        assertNull(RepoClone.parse("Cloning into 'pocketrocket'..."))
    }

    @Test fun listsBranchesWithTheDefaultFirst() {
        val lines = listOf(
            "ref: refs/heads/develop\tHEAD",
            "b1b3f9723831141a31a1a7252a213e216ea76e56\tHEAD",
            "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d\trefs/heads/main",
            "b1b3f9723831141a31a1a7252a213e216ea76e56\trefs/heads/develop",
            "b3cbd5bbd7e81436d2eee04537ea2b4c0cad4cdf\trefs/heads/feature/x",
        )
        assertEquals(RemoteBranches("develop", listOf("develop", "feature/x", "main")), RepoClone.parseBranches(lines))
    }

    @Test fun anEmptyRemoteHasNoBranches() {
        assertEquals(RemoteBranches(null, emptyList()), RepoClone.parseBranches(emptyList()))
    }

    @Test fun overallPercentRunsThroughThePhases() {
        assertNull(RepoClone.overallPercent(CloneProgress.Running("Counting objects", 100)))
        assertNull(RepoClone.overallPercent(CloneProgress.Running("Connecting", null)))
        assertEquals(0, RepoClone.overallPercent(CloneProgress.Running("Receiving objects", 0)))
        assertEquals(40, RepoClone.overallPercent(CloneProgress.Running("Receiving objects", 50)))
        assertEquals(80, RepoClone.overallPercent(CloneProgress.Running("Resolving deltas", 0)))
        assertEquals(95, RepoClone.overallPercent(CloneProgress.Running("Resolving deltas", 100)))
        assertEquals(100, RepoClone.overallPercent(CloneProgress.Running("Updating files", 100)))
    }

    @Test fun labelsAreOwnerAndRepo() {
        assertEquals("octocat/Hello-World", RepoClone.labelFor("https://github.com/octocat/Hello-World.git"))
        assertEquals("octocat/Hello-World", RepoClone.labelFor("git@github.com:octocat/Hello-World.git"))
        assertEquals("o/r", RepoClone.labelFor("https://github.com/o/r/"))
        assertEquals("solo", RepoClone.labelFor("https://example.com/solo.git"))
    }

    private fun halfClone(url: String): File = Files.createTempDirectory("clone").toFile().also { dir ->
        File(dir, ".git/refs/heads").mkdirs()
        File(dir, ".git/objects/pack/tmp_pack_x").apply { parentFile.mkdirs(); writeText("x") }
        File(dir, ".git/config").writeText("[core]\n\tbare = false\n[remote \"origin\"]\n\turl = $url\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n")
    }

    @Test fun aDeadCloneOfTheSameUrlIsLeftover() {
        val url = "git@github.com:o/r.git"
        assertTrue(RepoClone.isLeftoverOf(halfClone(url), url))
    }

    @Test fun anythingElseIsKept() {
        val url = "git@github.com:o/r.git"
        assertFalse(RepoClone.isLeftoverOf(halfClone(url), "git@github.com:o/other.git"))
        assertFalse(RepoClone.isLeftoverOf(halfClone(url).also { File(it, "README").writeText("") }, url))
        assertFalse(RepoClone.isLeftoverOf(halfClone(url).also { File(it, ".git/refs/heads/main").writeText("") }, url))
    }
}
