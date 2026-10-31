package com.pocketrocket.git

import android.content.Context
import com.pocketrocket.github.GhCli
import com.pocketrocket.profiles.AccountWiring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * A repo's pull requests through `gh`, run in the repo folder as the repo's GitHub account: its workspace's
 * GH_CONFIG_DIR (decision 21), never `gh auth switch`. Reads throw [GitException] with gh's last line; actions
 * return a [GitOutcome]. Nothing creates a PR (decision 27). Every function suspends on the IO dispatcher.
 */
class PullRequests(context: Context, val root: File) {

    private val app = context.applicationContext
    private val account = AccountWiring.githubFor(root.path)
    private val env = account?.let { GhCli.environment(it.ghConfigDir) + ("GIT_TERMINAL_PROMPT" to "0") }

    /** The account's login, for "you", Mine and "You were asked to review". */
    val me: String? get() = account?.login

    /**
     * Why there are no PRs to show here, or null when gh can be asked: no GitHub account, no gh, or no github.com
     * remote. Quick and offline.
     */
    suspend fun unavailable(): String? = withContext(Dispatchers.IO) {
        when {
            account == null -> "Connect a GitHub account in Settings to see pull requests."
            !File(GhCli.bin("gh")).exists() -> "gh is not installed, so pull requests can't be shown."
            "github.com" !in GitCli(app, root).run("remote", "-v").out -> "This repo has no GitHub remote, so there are no pull requests."
            else -> null
        }
    }

    suspend fun list(filter: PrFilter, limit: Int = 50, fields: String = PrParsers.LIST_FIELDS): List<PullRequest> = io {
        PrParsers.list(read("pr", "list", *filter.args.toTypedArray(), "-L", limit.toString(), "--json", fields))
    }

    /** The PR of the branch checked out here, or null (none, or any error). */
    suspend fun forCurrentBranch(): PullRequest? = io {
        runCatching { PrParsers.pr(JSONObject(read("pr", "view", "--json", "number,title,state,isDraft,headRefName,statusCheckRollup"))) }.getOrNull()
    }

    suspend fun detail(number: Int): PrDetail = io { PrParsers.detail(read("pr", "view", number.toString(), "--json", PrParsers.VIEW_FIELDS)) }

    suspend fun files(number: Int): List<PrFile> = io { PrParsers.files(read("pr", "diff", number.toString(), "--color=never")) }

    suspend fun mergeMethods(): MergeMethods = io {
        PrParsers.mergeMethods(read("repo", "view", "--json", "squashMergeAllowed,mergeCommitAllowed,rebaseMergeAllowed"))
    }

    suspend fun merge(number: Int, method: MergeMethod, deleteBranch: Boolean): GitOutcome =
        if (deleteBranch) act("pr", "merge", number.toString(), method.flag, "--delete-branch")
        else act("pr", "merge", number.toString(), method.flag)

    /** Opens a PR from the current branch, which has to be pushed. */
    suspend fun create(title: String, body: String, base: String?): GitOutcome =
        if (base == null) act("pr", "create", "--title", title, "--body", body)
        else act("pr", "create", "--title", title, "--body", body, "--base", base)

    suspend fun close(number: Int) = act("pr", "close", number.toString())

    suspend fun ready(number: Int) = act("pr", "ready", number.toString())

    /** `gh pr review`: approve (with an optional comment) or request changes (comment required). */
    suspend fun review(number: Int, approve: Boolean, body: String): GitOutcome {
        val verdict = if (approve) "--approve" else "--request-changes"
        return if (body.isBlank()) act("pr", "review", number.toString(), verdict)
        else act("pr", "review", number.toString(), verdict, "--body", body.trim())
    }

    /** `gh pr checkout` in this folder (or [dir], a worktree), as [branch] when given. */
    suspend fun checkout(number: Int, dir: File = root, branch: String? = null): GitOutcome =
        if (branch == null) act("pr", "checkout", number.toString(), dir = dir)
        else act("pr", "checkout", number.toString(), "--branch", branch, dir = dir)

    private fun gh(args: Array<out String>, dir: File = root): GitCli.Result {
        val environment = env ?: throw GitException("No GitHub account for this repo.")
        return GitCli.exec(app, GhCli.bin("gh"), arrayOf(*args), dir, environment)
    }

    private fun read(vararg args: String): String {
        val r = gh(args)
        if (!r.ok) throw GitException(r.err.lines().lastOrNull { it.isNotBlank() } ?: "gh exited with ${r.exitCode}")
        return r.out
    }

    private suspend fun act(vararg args: String, dir: File = root): GitOutcome = io {
        runCatching {
            val r = gh(args, dir)
            GitOutcome(r.ok, listOf(r.out.trim(), r.err).filter { it.isNotBlank() }.joinToString("\n").ifBlank { if (r.ok) "Done" else "gh exited with ${r.exitCode}" })
        }.getOrElse { GitOutcome(false, it.message ?: "gh failed") }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
