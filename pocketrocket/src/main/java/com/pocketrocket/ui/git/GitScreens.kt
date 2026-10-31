package com.pocketrocket.ui.git

import android.content.Context
import android.content.Intent
import com.pocketrocket.git.Commit
import com.pocketrocket.git.DiffSource
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.ui.chat.SessionActivity
import java.io.File

/**
 * The way into the git viewer. Phase B's workspace page calls [open] with a repo folder; the panel
 * then leads to diffs, commits and file browsing on its own.
 */
object GitScreens {

    fun intent(context: Context, repoPath: String): Intent =
        Intent(context, GitActivity::class.java).putExtra(GitActivity.EXTRA_REPO, repoPath)

    /** Opens the Git panel for the repo at [repoPath] (any folder inside a repo works). */
    fun open(context: Context, repoPath: String) {
        context.startActivity(intent(context, repoPath))
    }

    /** Opens the repo's files at HEAD (back goes to its panel). */
    fun browse(context: Context, repoPath: String) {
        context.startActivity(intent(context, repoPath).putExtra(GitActivity.EXTRA_BROWSE, true))
    }

    /** Opens the Git panel with its branch sheet up (the branch in a session's status line). */
    fun openBranches(context: Context, repoPath: String) {
        context.startActivity(intent(context, repoPath).putExtra(GitActivity.EXTRA_BRANCHES, true))
    }

    /**
     * Starts a Claude session in [folder] (a worktree on a branch), named [title], sends [prompt] as its first
     * message when given, and opens it.
     */
    fun startSession(context: Context, folder: File, title: String, prompt: String? = null) {
        val session = SessionManager.startClaude(context, folder)
        session.rename(title)
        prompt?.let(session::send)
        SessionActivity.open(context, session.id)
    }
}

/** The screens inside the git viewer, kept as a back stack by [GitActivity]. */
sealed interface GitRoute {
    data object Panel : GitRoute
    data class CommitDetail(val commit: Commit) : GitRoute
    data class Diff(val source: DiffSource, val title: String) : GitRoute
    data class Browse(val dir: String) : GitRoute
    data class File(val path: String) : GitRoute
    data class Pr(val number: Int) : GitRoute
    data class PrDiff(val number: Int, val path: String) : GitRoute
}
