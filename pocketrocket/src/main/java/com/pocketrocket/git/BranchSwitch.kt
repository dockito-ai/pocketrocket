package com.pocketrocket.git

import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import java.io.File

/** Switching branch safely: what the git panel and Rocket's `switch_branch` both do. */
object BranchSwitch {
    /**
     * Keep: stash everything with a marker naming the branch, switch, and put the stash back if the switch fails.
     * After any switch, changes kept earlier for the new branch come back.
     */
    suspend fun run(repo: GitRepo, switch: suspend (GitRepo) -> GitOutcome, keep: Boolean): GitOutcome {
        val from = if (keep) repo.headBranch() else null
        if (from != null) {
            val stashed = repo.stashFor(from)
            if (!stashed.ok) return stashed
        }
        val switched = switch(repo)
        if (!switched.ok) {
            if (from != null) repo.restoreStash(from)
            return switched
        }
        val restored = repo.headBranch()?.let { repo.restoreStash(it) }
        val first = switched.message.lines().firstOrNull { it.isNotBlank() } ?: "Switched"
        return when {
            restored == null -> GitOutcome(true, first)
            restored.ok -> GitOutcome(true, "$first. Your kept changes are back.")
            else -> GitOutcome(false, "$first, but the kept changes could not be put back:\n${restored.message}")
        }
    }

    /** The title of a session working (or waiting on a permission) in [root]. Worktrees are other folders. */
    fun workingSession(root: File): String? {
        val path = root.path
        return SessionManager.sessions.value.map { it.state.value }.firstOrNull { s ->
            (s.status == SessionStatus.Working || s.status == SessionStatus.WaitingForPermission) &&
                (s.folder == path || s.folder.startsWith("$path/")) && !s.folder.startsWith("$path/.worktrees/")
        }?.title
    }
}
