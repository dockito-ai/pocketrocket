package com.pocketrocket.ui.git

import com.pocketrocket.git.GitRepo
import com.pocketrocket.git.Worktree
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A worktree as the Worktrees screen shows it: uncommitted files ([changed], null when unreadable), last commit,
 * whether its branch is [merged] into the default branch, the [sessions] open in it (titles) and whether one is
 * [working], and whether it is the folder this panel is [here] for.
 */
data class WorktreeRow(
    val tree: Worktree,
    val changed: Int?,
    val lastCommit: Long?,
    val merged: Boolean,
    val sessions: List<String>,
    val working: Boolean,
    val here: Boolean,
) {
    /** The main checkout and the folder this panel looks at stay. */
    val removable: Boolean get() = !tree.main && !here

    /** Safe to clean up in one go: merged, nothing uncommitted, no session, not locked by an agent. */
    val tidy: Boolean
        get() = removable && tree.prunable == null && tree.locked == null && tree.branch != null && changed == 0 && merged && sessions.isEmpty()

    /** "~/projects/x/.worktrees/x-feat" for the folder; the last part reads as the name. */
    val name: String get() = tree.path.trimEnd('/').substringAfterLast('/')
}

object WorktreeRows {
    /** Every worktree of [repo] with its details, read in parallel; [merged] are branches merged into the default one. */
    suspend fun load(repo: GitRepo, merged: Set<String>): List<WorktreeRow> = coroutineScope {
        val sessions = SessionManager.sessions.value.map { it.state.value }
        repo.worktrees().map { tree ->
            async {
                val inside = sessions.filter { it.folder == tree.path || it.folder.startsWith(tree.path.trimEnd('/') + "/") }
                    // A session in a worktree nested inside the main checkout belongs to that worktree, not to main.
                    .filter { !tree.main || !it.folder.contains("/.worktrees/") && !it.folder.contains("/.claude/worktrees/") }
                val gone = tree.prunable != null
                WorktreeRow(
                    tree = tree,
                    changed = if (gone) null else repo.changesIn(tree.path),
                    lastCommit = if (gone) null else repo.lastCommitIn(tree.path),
                    merged = tree.branch != null && tree.branch in merged,
                    sessions = inside.map { it.title },
                    working = inside.any { it.shownStatus == SessionStatus.Working || it.shownStatus == SessionStatus.WaitingForPermission },
                    here = tree.path.trimEnd('/') == repo.root.path.trimEnd('/'),
                )
            }
        }.awaitAll()
    }
}
