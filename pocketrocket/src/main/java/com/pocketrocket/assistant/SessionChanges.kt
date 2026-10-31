package com.pocketrocket.assistant

import android.content.Context
import com.pocketrocket.git.ChangeArea
import com.pocketrocket.git.DiffRow
import com.pocketrocket.git.DiffSource
import com.pocketrocket.git.FileChange
import com.pocketrocket.git.GitRepo
import com.pocketrocket.sessions.SessionState
import java.io.File

/** "Show what changed": read from git by the app, with no Claude. The sentence is the app's and only counts; the diff goes on the card. */
object SessionChanges {
    private const val MAX_FILES = 3
    private const val MAX_LINES = 12
    private val NUMBERS = listOf("No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")

    suspend fun reply(context: Context, state: SessionState): Reply {
        val repo = runCatching { GitRepo.open(context, File(state.folder)) }.getOrElse { return Reply("${state.title} isn't in a git repo, so I can't tell.") }
        val changes = runCatching { repo.status().changes }.getOrElse { return Reply("I couldn't read what changed in ${state.title}.") }
        val files = changes.distinctBy { it.path }
        if (files.isEmpty()) return Reply("Nothing has changed in ${state.title}.")
        val shown = files.take(MAX_FILES).map { change ->
            val diff = runCatching { repo.diff(sourceOf(change)) }.getOrNull()
            val lines = diff?.rows.orEmpty().filterIsInstance<DiffRow.Line>().filter { it.kind == '+' || it.kind == '-' }.take(MAX_LINES).map { "${it.kind}${it.text}" }
            RocketCard.Changes.File(change.path, change.added ?: diff?.added ?: 0, change.deleted ?: diff?.deleted ?: 0, lines)
        }
        return Reply(sentence(files.size, MAX_FILES), card = RocketCard.Changes(shown, state.id, state.title))
    }

    internal fun sentence(count: Int, shown: Int): String {
        val number = NUMBERS.getOrNull(count) ?: count.toString()
        val noun = if (count == 1) "file" else "files"
        return "$number $noun changed." + if (count > shown) " Here are the first $shown." else ""
    }

    private fun sourceOf(change: FileChange): DiffSource = when (change.area) {
        ChangeArea.Staged -> DiffSource.Index(change.path)
        ChangeArea.Untracked -> DiffSource.Untracked(change.path)
        ChangeArea.Unstaged, ChangeArea.Conflicted -> DiffSource.WorkingTree(change.path)
    }
}
