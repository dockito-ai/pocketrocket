package com.pocketrocket.workspaces

import com.pocketrocket.files.FileOps
import java.io.File

/** Finding repo folders by walking the disk: no git calls, so it stays cheap. */
object RepoScan {
    /** Folders never walked into: dependencies and build output (hidden folders are skipped too). */
    val skip = setOf("node_modules", "build", "dist", "target", "vendor")

    fun isRepo(dir: File) = File(dir, ".git").exists()

    /**
     * A linked worktree: an extra checkout of another repo, whose `.git` is a file pointing into that repo's
     * `.git/worktrees/`. It belongs to its repo's workspace (shown in the Git screen's Worktrees), not on its own.
     */
    fun isLinkedWorktree(dir: File): Boolean {
        val git = File(dir, ".git")
        if (!git.isFile) return false
        val target = runCatching { git.readText() }.getOrNull()?.trim()?.removePrefix("gitdir:")?.trim() ?: return false
        return target.trimEnd('/').substringBeforeLast('/').endsWith("/.git/worktrees")
    }

    /** Subfolders worth walking into: not hidden, not in [skip]. */
    internal fun children(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in skip }?.sortedBy { it.name.lowercase() }.orEmpty()

    /**
     * Repos under [root] that aren't workspaces and aren't in one (decision 72): cloned from a terminal or by
     * Claude, so the Workspaces list can offer to make them workspaces. [workspaces] are their paths. Walks
     * folders at any depth up to [maxDepth], skipping hidden ones, [skip] and symlinks, and never descends
     * into a workspace or a repo (a repo with workspaces inside it is walked through instead, so workspaces
     * are never nested). Linked worktrees are left out: they belong to the repo they were made from.
     */
    fun notWorkspaces(root: File, workspaces: List<String>, maxDepth: Int = 6): List<File> {
        val found = mutableListOf<File>()
        fun holdsWorkspace(dir: File) = workspaces.any { it.startsWith(dir.path + "/") }
        fun walk(dir: File, depth: Int) {
            children(dir).filterNot(FileOps::isSymlink).forEach { child ->
                when {
                    child.path in workspaces -> Unit
                    isLinkedWorktree(child) -> Unit
                    isRepo(child) && !holdsWorkspace(child) -> found += child
                    depth < maxDepth -> walk(child, depth + 1)
                }
            }
        }
        walk(root, 1)
        return found
    }
}
