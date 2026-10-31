package com.pocketrocket.git

/** Branch line of `git status -b`: name (null when detached), upstream and how far apart they are. */
data class BranchInfo(
    val head: String?,
    val oid: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
)

/** Which part of the index a change sits in. */
enum class ChangeArea { Staged, Unstaged, Untracked, Conflicted }

/**
 * One changed file. [kind] is git's letter (M, A, D, R, C, T, U, ?). [added] and [deleted] are line
 * counts from `--numstat`, null for binary files or when not counted (untracked).
 */
data class FileChange(
    val path: String,
    val kind: Char,
    val area: ChangeArea,
    val originalPath: String? = null,
    val added: Int? = null,
    val deleted: Int? = null,
)

data class GitStatus(val branch: BranchInfo, val changes: List<FileChange>) {
    fun inArea(area: ChangeArea) = changes.filter { it.area == area }
}

data class Commit(
    val sha: String,
    val shortSha: String,
    val author: String,
    /** Seconds since the epoch. */
    val time: Long,
    val subject: String,
    val pushed: Boolean = true,
)

/** One entry of `git ls-tree`: a file ([isDir] false) or a folder. [path] is relative to the repo root. */
data class TreeEntry(val path: String, val isDir: Boolean, val isSubmodule: Boolean = false) {
    val name: String get() = path.substringAfterLast('/')
}

/** A parsed unified diff of one file, flattened into display rows. */
data class Diff(val rows: List<DiffRow>, val added: Int, val deleted: Int, val binary: Boolean, val truncated: Boolean)

sealed interface DiffRow {
    /** `@@ -a,b +c,d @@ context` */
    data class Hunk(val header: String) : DiffRow

    /** [kind] is ' ', '+' or '-'. Old and new line numbers; one is null for added or removed lines. */
    data class Line(val kind: Char, val oldNumber: Int?, val newNumber: Int?, val text: String) : DiffRow

    /** "\ No newline at end of file" and similar notes. */
    data class Note(val text: String) : DiffRow
}

/** What a diff is taken against. */
sealed interface DiffSource {
    data class WorkingTree(val path: String) : DiffSource
    data class Index(val path: String) : DiffSource
    data class Untracked(val path: String) : DiffSource
    data class InCommit(val sha: String, val path: String) : DiffSource
}

/** Contents of a file at HEAD; [text] is null for binary files. */
data class FileBlob(val path: String, val text: String?, val sizeBytes: Int, val truncated: Boolean)

/** Result of a write action (stage, commit, push...): success and git's own words. */
data class GitOutcome(val ok: Boolean, val message: String)

/**
 * A branch from `git for-each-ref`: a local one (refs/heads, [remote] false) or a remote-tracking one
 * ("origin/chore/kotlin"). [ahead] and [behind] are against [upstream]; [worktree] is the folder it is checked
 * out in (this one or another worktree), null when it is not checked out. [time] is the tip's commit time.
 */
data class Branch(
    val name: String,
    val remote: Boolean,
    val sha: String,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val upstreamGone: Boolean = false,
    val time: Long = 0L,
    val current: Boolean = false,
    val worktree: String? = null,
    val author: String = "",
    val subject: String = "",
) {
    /** The name without the remote: "chore/kotlin" for "origin/chore/kotlin". */
    val shortName: String get() = if (remote) name.substringAfter('/') else name
}

/** Local branches newest first, then remote branches that have no local branch, newest first. */
data class BranchList(val local: List<Branch>, val remoteOnly: List<Branch>) {
    val current: Branch? get() = local.firstOrNull { it.current }

    companion object {
        val Empty = BranchList(emptyList(), emptyList())
    }
}

/** A name on a commit in the graph: a local branch, a remote-tracking branch or a tag. [head] marks HEAD's branch. */
data class GraphRef(val name: String, val kind: Kind, val head: Boolean = false) {
    enum class Kind { Local, Remote, Tag }

    /** For a remote branch, the name without the remote ("chore/kotlin"). */
    val shortName: String get() = if (kind == Kind.Remote) name.substringAfter('/') else name
}

/** One commit of `git log --topo-order` for the graph: its parents and the names pointing at it. */
data class GraphCommit(
    val sha: String,
    val parents: List<String>,
    val refs: List<GraphRef>,
    val author: String,
    /** Seconds since the epoch. */
    val time: Long,
    val subject: String,
) {
    val shortSha: String get() = sha.take(7)

    fun toCommit() = Commit(sha, shortSha, author, time, subject)
}

/**
 * One entry of `git worktree list`: its folder, the commit it is on and its [branch] (null when detached, as a PR
 * review is). The first one is the [main] checkout. [locked] holds the lock's reason ("claude agent agent-a44…"),
 * empty when locked without one; [prunable] says why git thinks its folder is gone.
 */
data class Worktree(
    val path: String,
    val head: String,
    val branch: String?,
    val main: Boolean = false,
    val locked: String? = null,
    val prunable: String? = null,
)
