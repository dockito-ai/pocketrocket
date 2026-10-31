package com.pocketrocket.workspaces

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Decision 72: repos under ~/projects that aren't workspaces, found by [RepoScan.notWorkspaces]. */
class RepoScanTest {
    private val root: File = Files.createTempDirectory("projects").toFile()

    @After fun tearDown() { root.deleteRecursively() }

    private fun repo(path: String) = File(root, path).also { File(it, ".git").mkdirs() }
    private fun dir(path: String) = File(root, path).also { it.mkdirs() }
    private fun scan(vararg workspaces: String) =
        RepoScan.notWorkspaces(root, workspaces.map { File(root, it).path }).map { it.relativeTo(root).path }

    @Test fun findsReposNextToWorkspacesAtAnyLevel() {
        repo("work/acme/app")
        repo("work/acme/cloned-by-claude")
        repo("personal/deep/er/still")
        repo("top")
        assertEquals(listOf("personal/deep/er/still", "top", "work/acme/cloned-by-claude"), scan("work/acme/app"))
    }

    @Test fun neverLooksInsideWorkspacesOrRepos() {
        repo("work/ws/inner")       // inside a workspace: part of it
        dir("work/ws/plain")
        repo("work/r")
        repo("work/r/vendored")     // inside a repo
        assertEquals(listOf("work/r"), scan("work/ws"))
    }

    @Test fun aRepoHoldingWorkspacesIsWalkedThroughNotOffered() {
        repo("mono")
        repo("mono/packages/a")
        repo("mono/packages/b")
        assertEquals(listOf("mono/packages/b"), scan("mono/packages/a"))
    }

    @Test fun skipsHiddenBuildAndDependencyFolders() {
        repo(".hidden/r")
        repo("app/node_modules/dep")
        repo("app/build/x")
        repo("app/.claude/worktrees/w")
        dir("app/src")
        assertEquals(emptyList<String>(), scan())
    }

    @Test fun stopsAtTheDepthLimitAndSkipsSymlinks() {
        repo("a/b/c/d/e/f/g/too-deep")
        repo("a/ok")
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            File(outside, "r/.git").mkdirs()
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            assertEquals(listOf("a/ok"), scan())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test fun aWorkspaceThatIsItselfARepoIsNotListed() {
        repo("work/app")
        assertEquals(emptyList<String>(), scan("work/app"))
    }

    @Test fun linkedWorktreesAreNotOffered() {
        repo("personal/app")
        // `git worktree add ../app-feature`: its .git is a file pointing into app's .git/worktrees.
        dir("personal/app-feature")
        File(root, "personal/app-feature/.git").writeText("gitdir: ${File(root, "personal/app/.git/worktrees/app-feature").path}\n")
        // A file .git that isn't a worktree link (a moved repo's gitdir) is still a repo.
        dir("personal/elsewhere")
        File(root, "personal/elsewhere/.git").writeText("gitdir: /some/other/place/.git\n")
        assertEquals(listOf("personal/elsewhere"), scan("personal/app"))
    }
}
