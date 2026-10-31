package com.pocketrocket.git

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One repo folder, read through the git CLI. Read-only apart from stage, unstage, commit, push and
 * pull. Every function suspends on the IO dispatcher. Read failures throw [GitException] with git's
 * message; write actions return a [GitOutcome].
 */
class GitRepo(context: Context, val root: File) {

    private val git = GitCli(context.applicationContext, root)

    val name: String get() = root.name

    /** Branch, ahead/behind and changed files with line counts (three git calls, run in parallel). */
    suspend fun status(): GitStatus = io {
        coroutineScope {
            val status = async { read("status", "--porcelain=v2", "-b", "-z", "--untracked-files=all") }
            val unstaged = async { git.run("diff", "--numstat", "-z").out }
            val staged = async { git.run("diff", "--cached", "--numstat", "-z").out }
            val parsed = GitParsers.status(status.await())
            var changes = GitParsers.withCounts(parsed.changes, ChangeArea.Unstaged, GitParsers.numstat(unstaged.await()))
            changes = GitParsers.withCounts(changes, ChangeArea.Staged, GitParsers.numstat(staged.await()))
            parsed.copy(changes = changes)
        }
    }

    /** The last [count] commits on HEAD, newest first; those not yet on the upstream are marked. */
    suspend fun log(count: Int = 50, upstream: String? = null): List<Commit> = io {
        val result = git.run("log", "-n", count.toString(), "--format=${GitParsers.LOG_FORMAT}")
        // A repo with no commits yet has no log; that is not an error.
        if (!result.ok) return@io emptyList()
        val unpushed = if (upstream == null) emptySet()
        else git.run("rev-list", "-n", count.toString(), "@{u}..HEAD").out.lines().filter { it.isNotBlank() }.toSet()
        GitParsers.log(result.out, unpushed)
    }

    /** Files changed by one commit. */
    suspend fun commitFiles(sha: String): List<FileChange> = io {
        GitParsers.commitFiles(read("show", "--format=", "-z", "--raw", "--numstat", "--no-renames", sha))
    }

    /** The full message of one commit. */
    suspend fun commitMessage(sha: String): String = io { read("show", "-s", "--format=%B", sha).trim() }

    suspend fun diff(source: DiffSource): Diff = io {
        val result = when (source) {
            is DiffSource.WorkingTree -> git.run("diff", "--no-ext-diff", "--", source.path)
            is DiffSource.Index -> git.run("diff", "--cached", "--no-ext-diff", "--", source.path)
            // Exit code 1 means "differs", which is the point here.
            is DiffSource.Untracked -> git.run("diff", "--no-index", "--no-ext-diff", "--", "/dev/null", source.path)
            is DiffSource.InCommit -> git.run("show", "--format=", "--no-ext-diff", source.sha, "--", source.path)
        }
        if (result.exitCode > 1 || (result.exitCode == 1 && source !is DiffSource.Untracked)) throw GitException(result.err)
        GitParsers.diff(result.out).let { if (result.outTruncated) it.copy(truncated = true) else it }
    }

    /** Entries of one folder at HEAD ("" for the root). Empty for a repo with no commits. */
    suspend fun tree(dir: String = ""): List<TreeEntry> = io {
        val args = if (dir.isEmpty()) arrayOf("ls-tree", "-z", "HEAD") else arrayOf("ls-tree", "-z", "HEAD", "--", "$dir/")
        val result = git.run(*args)
        if (result.ok) GitParsers.lsTree(result.out) else emptyList()
    }

    /** A file at HEAD, up to [maxBytes]. Binary files (a NUL in the first 8 KB) come back without text. */
    suspend fun file(path: String, maxBytes: Int = 1024 * 1024): FileBlob = io {
        val result = git.run("show", "HEAD:$path", maxOutBytes = maxBytes)
        if (!result.ok && !result.outTruncated) throw GitException(result.err)
        val binary = result.out.take(8192).contains('\u0000')
        FileBlob(path, if (binary) null else result.out, result.out.length, result.outTruncated)
    }

    suspend fun stage(path: String) = write("add", "--", path)

    suspend fun unstage(path: String) = write("restore", "--staged", "--", path)

    suspend fun stageAll() = write("add", "-A")

    suspend fun commit(message: String) = write("commit", "-m", message)

    /** Pushes the branch, setting the upstream the first time. */
    suspend fun push(hasUpstream: Boolean) = if (hasUpstream) write("push") else write("push", "-u", "origin", "HEAD")

    suspend fun pull() = write("pull", "--ff-only")

    // Branches (R17, decision 27).

    /** Local and remote-tracking branches with their tips, upstream distance and worktree. */
    suspend fun branches(): BranchList = io {
        val r = git.run("for-each-ref", "--format=${GitParsers.BRANCH_FORMAT}", "refs/heads", "refs/remotes")
        if (r.ok) GitParsers.branches(r.out) else BranchList.Empty
    }

    /** The branch HEAD is on, or null when detached. */
    suspend fun headBranch(): String? = io { git.run("symbolic-ref", "--short", "-q", "HEAD").out.trim().ifEmpty { null } }

    /** The default branch ("main"): origin's HEAD, else a local main or master. */
    suspend fun defaultBranch(): String? = io {
        val remote = git.run("symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD").out.trim()
        if (remote.isNotEmpty()) return@io remote.substringAfter('/')
        listOf("main", "master").firstOrNull { git.run("show-ref", "--verify", "-q", "refs/heads/$it").ok }
    }

    /** Local branches whose tips are already in [base] (safe to delete with `-d`). */
    suspend fun mergedInto(base: String): Set<String> = io {
        val r = git.run("branch", "--format=%(refname:short)", "--merged", base)
        if (r.ok) r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    }

    suspend fun switchTo(branch: String) = write("switch", branch)

    /** Checks out a remote branch ("origin/x") as a new local branch that tracks it. */
    suspend fun track(remoteBranch: String) = write("switch", "--track", remoteBranch)

    /** Creates [name] at [start] (HEAD when null) and switches to it; uncommitted changes come along. */
    suspend fun createBranch(name: String, start: String? = null) =
        if (start == null) write("switch", "-c", name) else write("switch", "-c", name, start)

    suspend fun deleteBranch(name: String) = write("branch", "-d", name)

    suspend fun deleteRemoteBranch(name: String, remote: String = "origin") = write("push", remote, "--delete", name)

    /** Puts every uncommitted change, untracked files too, in a stash marked with [branch] ([GitParsers.STASH_MARKER]). */
    suspend fun stashFor(branch: String) = write("stash", "push", "-u", "-m", GitParsers.STASH_MARKER + branch)

    /** Pops the stash [stashFor] left for [branch], if there is one; null when there is none. */
    suspend fun restoreStash(branch: String): GitOutcome? {
        val ref = io { GitParsers.stashFor(git.run("stash", "list", "--format=%gd%x1f%gs").out, branch) } ?: return null
        return write("stash", "pop", ref)
    }

    /**
     * A new worktree at [dir] on [branch]: an existing local branch, a remote one ("origin/x", tracked as a new
     * local branch), or a new branch from HEAD when [create]. Returns git's outcome.
     */
    suspend fun addWorktree(dir: File, branch: String, create: Boolean = false, remote: Boolean = false): GitOutcome {
        ignoreIfInside(dir)
        return when {
            create -> write("worktree", "add", "-b", branch, dir.path)
            remote -> write("worktree", "add", "--track", "-b", branch.substringAfter('/'), dir.path, branch)
            else -> write("worktree", "add", dir.path, branch)
        }
    }

    /** A new worktree at [dir] on HEAD with no branch (a PR is then checked out in it). */
    suspend fun addDetachedWorktree(dir: File): GitOutcome {
        ignoreIfInside(dir)
        return write("worktree", "add", "--detach", dir.path, "HEAD")
    }

    suspend fun removeWorktree(dir: File) = write("worktree", "remove", "--force", dir.path)

    /** Every worktree of this repo, the main checkout first. */
    suspend fun worktrees(): List<Worktree> = io { GitParsers.worktrees(read("worktree", "list", "--porcelain", "-z")) }

    /** Uncommitted files in the worktree at [path] (untracked too); null when git can't read it. */
    suspend fun changesIn(path: String): Int? = io {
        val r = git.run("-C", path, "status", "--porcelain", "-z")
        if (r.ok) GitParsers.changeCount(r.out) else null
    }

    /** When the worktree at [path] last got a commit (unix seconds); null when unknown. */
    suspend fun lastCommitIn(path: String): Long? = io { git.run("-C", path, "log", "-1", "--format=%ct").out.trim().toLongOrNull() }

    /** Removes a worktree whatever it holds; a locked one (a Claude agent's) needs force twice. */
    suspend fun removeWorktree(path: String, locked: Boolean): GitOutcome =
        if (locked) write("worktree", "remove", "--force", "--force", path) else write("worktree", "remove", "--force", path)

    /** Forgets worktrees whose folders are gone. */
    suspend fun pruneWorktrees() = write("worktree", "prune")

    /** A worktree folder inside this repo (a workspace that is itself the repo) goes in .git/info/exclude, so it never shows as untracked. */
    private suspend fun ignoreIfInside(dir: File) = io {
        val relative = dir.path.removePrefix(root.path + "/").takeIf { it != dir.path } ?: return@io
        val top = relative.substringBefore('/')
        val common = git.run("rev-parse", "--git-common-dir").out.trim().ifEmpty { return@io }
        val exclude = File(File(root, common).takeIf { !File(common).isAbsolute } ?: File(common), "info/exclude")
        runCatching {
            val lines = if (exclude.exists()) exclude.readLines() else emptyList()
            if ("/$top/" !in lines) {
                exclude.parentFile?.mkdirs()
                exclude.appendText((if (lines.isNotEmpty() && exclude.readText().last() != '\n') "\n" else "") + "/$top/\n")
            }
        }
    }

    // Graph.

    /**
     * Commits for the graph in topological order, [count] from [skip]: HEAD's history, or every branch, remote
     * branch and tag when [all] (not the stash).
     */
    suspend fun graph(all: Boolean, skip: Int = 0, count: Int = 200): List<GraphCommit> = io {
        val scope = if (all) arrayOf("--branches", "--remotes", "--tags", "HEAD") else arrayOf("HEAD")
        val r = git.run("log", "--topo-order", "--decorate=full", "--format=${GitParsers.GRAPH_FORMAT}", "--skip=$skip", "-n", count.toString(), *scope)
        if (r.ok) GitParsers.graph(r.out) else emptyList()
    }

    private suspend fun write(vararg args: String): GitOutcome = io {
        val r = git.run(*args)
        GitOutcome(r.ok, listOf(r.out.trim(), r.err).filter { it.isNotBlank() }.joinToString("\n").ifBlank { if (r.ok) "Done" else "git exited with ${r.exitCode}" })
    }

    private fun read(vararg args: String): String {
        val r = git.run(*args)
        if (!r.ok) throw GitException(r.err.ifBlank { "git ${args.first()} exited with ${r.exitCode}" })
        return r.out
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** The repo that contains [folder] (its top level), or a [GitException] saying why there is none. */
        suspend fun open(context: Context, folder: File): GitRepo = withContext(Dispatchers.IO) {
            if (!GitCli.executable.exists()) throw GitException("git is not installed. Run `pkg install git` in a terminal.")
            if (!folder.isDirectory) throw GitException("${folder.path} does not exist.")
            val r = GitCli(context.applicationContext, folder).run("rev-parse", "--show-toplevel")
            if (!r.ok) throw GitException("${folder.name} is not a git repository.")
            GitRepo(context, File(r.out.trim()))
        }
    }
}

class GitException(message: String) : Exception(message)
