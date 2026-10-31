package com.pocketrocket.backup

import android.content.Context
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants
import java.io.File
import kotlin.concurrent.thread

/** Asks the prefix's git what [RepoRules] needs to know about a repo. Null when git can't say. */
internal class RepoGit(private val context: Context) {
    private val git = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "git").path

    fun facts(repo: File): RepoFacts? {
        val ignored = paths(repo, "ls-files", "-z", "--others", "--ignored", "--exclude-standard", "--directory") ?: return null
        val tracked = paths(repo, "ls-files", "-z") ?: return null
        return RepoFacts(
            ignored = ignored.filter { it.endsWith("/") }.map { it.trimEnd('/') }.toSet(),
            tracked = tracked.flatMap(::alwaysFoldersIn).toSet(),
        )
    }

    /** The folders named in [RepoRules.ALWAYS] on the way to [path]. */
    private fun alwaysFoldersIn(path: String): List<String> {
        val parts = path.split('/').dropLast(1)
        return parts.indices.filter { parts[it] in RepoRules.ALWAYS }.map { parts.subList(0, it + 1).joinToString("/") }
    }

    private fun paths(repo: File, vararg args: String): List<String>? = runCatching {
        val process = TermuxCommand.start(context, git, arrayOf("-C", repo.path) + args, repo)
        process.outputStream.close()
        // Nothing reads stderr otherwise, and a full pipe would stop git.
        val errors = thread(isDaemon = true) { runCatching { process.errorStream.readBytes() } }
        val output = process.inputStream.readBytes()
        val exit = process.waitFor()
        errors.join()
        if (exit != 0) null else String(output).split('\u0000').filter { it.isNotEmpty() }
    }.getOrNull()
}
