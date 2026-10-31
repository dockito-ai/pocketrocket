package com.pocketrocket.sessions

import java.io.File

/** The checked-out branch of the repo holding a folder, read from `.git/HEAD` (no git process). */
object GitHead {

    /** The branch name, a short commit id when detached, or null outside a repo. */
    fun branch(folder: String): String? {
        var dir: File? = File(folder)
        while (dir != null) {
            head(dir)?.let { file ->
                val ref = runCatching { file.readText().trim() }.getOrNull() ?: return null
                return if (ref.startsWith("ref: refs/heads/")) ref.removePrefix("ref: refs/heads/") else ref.take(7)
            }
            dir = dir.parentFile
        }
        return null
    }

    /** `.git/HEAD`, or for a worktree or submodule the HEAD in the dir its `.git` file points to. */
    private fun head(dir: File): File? {
        val git = File(dir, ".git")
        return when {
            git.isDirectory -> File(git, "HEAD").takeIf { it.isFile }
            git.isFile -> runCatching { git.readText().trim() }.getOrNull()
                ?.takeIf { it.startsWith("gitdir:") }
                ?.removePrefix("gitdir:")?.trim()
                ?.let { path -> File(if (path.startsWith("/")) File(path) else File(dir, path), "HEAD") }
                ?.takeIf { it.isFile }
            else -> null
        }
    }
}
