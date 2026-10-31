package com.pocketrocket.workspaces

import android.content.Context
import com.pocketrocket.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** One repo in a workspace, as its card shows it. */
data class RepoSummary(
    val dir: File,
    /** The folder's path inside the workspace ("app", "libs/shared"), or the workspace name for the root. */
    val name: String,
    val branch: String,
    /** Files changed, staged or not, untracked included. */
    val changed: Int,
    val ahead: Int,
    val behind: Int,
    val hasUpstream: Boolean,
    /** "a91c2e Add token retry", or null before the first commit. */
    val lastCommit: String?,
)

/** Finds the repos in a workspace and reads their state through the git CLI. */
object WorkspaceRepos {
    /** Repo folders: the workspace itself if it is one, else repos up to two levels down. Cheap, no git calls. */
    fun find(root: File): List<File> {
        if (isRepo(root)) return listOf(root)
        val found = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            RepoScan.children(dir).forEach {
                if (isRepo(it)) found += it else if (depth < 2) walk(it, depth + 1)
            }
        }
        walk(root, 1)
        return found
    }

    fun isRepo(dir: File) = RepoScan.isRepo(dir)

    /** Every repo's summary, read in parallel. Repos git can't read are left out. */
    suspend fun summaries(context: Context, root: File): List<RepoSummary> = coroutineScope {
        val dirs = withContext(Dispatchers.IO) { find(root) }
        dirs.map { dir -> async { runCatching { summary(context, root, dir) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    suspend fun summary(context: Context, root: File, dir: File): RepoSummary {
        val repo = GitRepo.open(context, dir)
        val status = repo.status()
        val last = runCatching { repo.log(count = 1) }.getOrNull()?.firstOrNull()
        val b = status.branch
        return RepoSummary(
            dir = dir,
            name = if (dir.path == root.path) root.name else dir.path.removePrefix(root.path + "/"),
            branch = b.head ?: b.oid?.take(7) ?: "no commits",
            changed = status.changes.distinctBy { it.path }.size,
            ahead = b.ahead,
            behind = b.behind,
            hasUpstream = b.upstream != null,
            lastCommit = last?.let { "${it.shortSha} ${it.subject}" },
        )
    }
}

/** The last repo summaries read per workspace path, so screens show them at once and refresh in place. */
object RepoStatus {
    private val _all = MutableStateFlow<Map<String, List<RepoSummary>>>(emptyMap())
    val all: StateFlow<Map<String, List<RepoSummary>>> = _all.asStateFlow()

    suspend fun refresh(context: Context, workspace: Workspace) {
        val list = WorkspaceRepos.summaries(context, workspace.dir)
        _all.update { it + (workspace.path to list) }
    }

    suspend fun refreshAll(context: Context, workspaces: List<Workspace>) = coroutineScope {
        workspaces.map { async { refresh(context, it) } }.awaitAll()
    }
}

/** Reads a folder's current branch straight from .git/HEAD, without starting git. For session cards. */
object GitHead {
    fun branch(folder: String): String? {
        var dir: File? = File(folder)
        while (dir != null) {
            val git = File(dir, ".git")
            val gitDir = when {
                git.isDirectory -> git
                git.isFile -> git.readTextOrNull()?.trim()?.removePrefix("gitdir:")?.trim()?.let { File(it).let { f -> if (f.isAbsolute) f else File(dir, it) } }
                else -> null
            }
            if (gitDir != null) {
                val head = File(gitDir, "HEAD").readTextOrNull()?.trim() ?: return null
                return if (head.startsWith("ref:")) head.removePrefix("ref:").trim().removePrefix("refs/heads/") else head.take(7)
            }
            dir = dir.parentFile
        }
        return null
    }

    /** The repo folder [folder] is in, or null. */
    fun repoRoot(folder: String): File? {
        var dir: File? = File(folder)
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()
}
