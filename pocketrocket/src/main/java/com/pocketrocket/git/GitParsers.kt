package com.pocketrocket.git

/**
 * Pure parsers for git's machine-readable output. Every command is run with `-z`, so fields are
 * NUL-separated and paths come through unquoted. No Android here, so these can be unit-tested.
 */
object GitParsers {

    private const val NUL = '\u0000'

    /** Record separator and field separator used in [LOG_FORMAT]. */
    private const val RS = '\u001e'
    private const val FS = '\u001f'

    /** `git log --format=` for [log]: full sha, short sha, author, unix time, subject. */
    const val LOG_FORMAT = "%H%x1f%h%x1f%an%x1f%at%x1f%s%x1e"

    /** `git status --porcelain=v2 -b -z`. Changes come back without line counts; see [withCounts]. */
    fun status(output: String): GitStatus {
        var head: String? = null
        var oid: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val changes = mutableListOf<FileChange>()
        val tokens = output.split(NUL)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i++]
            if (t.isEmpty()) continue
            when (t[0]) {
                '#' -> {
                    val parts = t.split(' ')
                    when (parts.getOrNull(1)) {
                        "branch.oid" -> oid = parts.getOrNull(2)?.takeIf { it != "(initial)" }
                        "branch.head" -> head = parts.getOrNull(2)?.takeIf { it != "(detached)" }
                        "branch.upstream" -> upstream = parts.getOrNull(2)
                        "branch.ab" -> {
                            ahead = parts.getOrNull(2)?.removePrefix("+")?.toIntOrNull() ?: 0
                            behind = parts.getOrNull(3)?.removePrefix("-")?.toIntOrNull() ?: 0
                        }
                    }
                }
                // 1 XY sub mH mI mW hH hI path
                '1' -> ordinary(t.split(' ', limit = 9), pathIndex = 8, original = null, into = changes)
                // 2 XY sub mH mI mW hH hI Xscore path, then the original path as the next token
                '2' -> ordinary(t.split(' ', limit = 10), pathIndex = 9, original = tokens.getOrNull(i++), into = changes)
                // u XY sub m1 m2 m3 mW h1 h2 h3 path
                'u' -> t.split(' ', limit = 11).getOrNull(10)?.let { changes += FileChange(it, 'U', ChangeArea.Conflicted) }
                '?' -> changes += FileChange(t.substring(2), '?', ChangeArea.Untracked)
            }
        }
        return GitStatus(BranchInfo(head, oid, upstream, ahead, behind), changes)
    }

    private fun ordinary(fields: List<String>, pathIndex: Int, original: String?, into: MutableList<FileChange>) {
        val xy = fields.getOrNull(1) ?: return
        val path = fields.getOrNull(pathIndex) ?: return
        if (xy[0] != '.') into += FileChange(path, xy[0], ChangeArea.Staged, original)
        if (xy.length > 1 && xy[1] != '.') into += FileChange(path, xy[1], ChangeArea.Unstaged, original)
    }

    /** `git diff --numstat -z` (or `--cached`, or `show --numstat`): path to (added, deleted); null counts for binary. */
    fun numstat(output: String): Map<String, Pair<Int?, Int?>> {
        val result = HashMap<String, Pair<Int?, Int?>>()
        val tokens = output.split(NUL)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i++]
            if (t.isEmpty()) continue
            val parts = t.trimStart('\n').split('\t', limit = 3)
            if (parts.size < 3) continue
            // A rename: "a\td\t" followed by the old and new paths.
            val path = if (parts[2].isEmpty()) tokens.getOrNull(i + 1).also { i += 2 } else parts[2]
            if (path != null) result[path] = parts[0].toIntOrNull() to parts[1].toIntOrNull()
        }
        return result
    }

    /** Adds `--numstat` counts to the changes of one area. */
    fun withCounts(changes: List<FileChange>, area: ChangeArea, counts: Map<String, Pair<Int?, Int?>>): List<FileChange> =
        changes.map { c -> if (c.area != area) c else counts[c.path]?.let { (a, d) -> c.copy(added = a, deleted = d) } ?: c }

    /**
     * Files of one commit from `git show --format= -z --raw --numstat <sha>`: raw records (":… M\0path\0")
     * give the kind, numstat records give the counts.
     */
    fun commitFiles(output: String): List<FileChange> {
        val kinds = LinkedHashMap<String, Pair<Char, String?>>()
        val tokens = output.split(NUL)
        val counts = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i++].trimStart('\n')
            if (t.isEmpty()) continue
            if (t[0] == ':') {
                val kind = t.substringAfterLast(' ').firstOrNull() ?: 'M'
                if (kind == 'R' || kind == 'C') {
                    val old = tokens.getOrNull(i++)
                    val new = tokens.getOrNull(i++) ?: continue
                    kinds[new] = kind to old
                } else {
                    val path = tokens.getOrNull(i++) ?: continue
                    kinds[path] = kind to null
                }
            } else {
                // Hand numstat records (with their rename tails) back to [numstat].
                counts.append(t).append(NUL)
                if (t.endsWith('\t')) {
                    counts.append(tokens.getOrNull(i++)).append(NUL).append(tokens.getOrNull(i++)).append(NUL)
                }
            }
        }
        val numbers = numstat(counts.toString())
        return kinds.map { (path, kind) ->
            val n = numbers[path]
            FileChange(path, kind.first, ChangeArea.Staged, kind.second, n?.first, n?.second)
        }
    }

    /** `git log --format=[LOG_FORMAT]`. */
    fun log(output: String, unpushed: Set<String> = emptySet()): List<Commit> =
        output.split(RS).mapNotNull { record ->
            val f = record.trimStart('\n').split(FS)
            if (f.size < 5) null
            else Commit(f[0], f[1], f[2], f[3].toLongOrNull() ?: 0L, f[4], pushed = f[0] !in unpushed)
        }

    /**
     * `git for-each-ref --format=` for [branches] over refs/heads and refs/remotes: ref, sha, upstream, ahead/behind
     * ("ahead 2, behind 1" or "gone"), commit time, "*" for HEAD, worktree folder, author, subject.
     */
    const val BRANCH_FORMAT = "%(refname)%1f%(objectname)%1f%(upstream:short)%1f%(upstream:track,nobracket)%1f" +
        "%(committerdate:unix)%1f%(HEAD)%1f%(worktreepath)%1f%(authorname)%1f%(contents:subject)%1e"

    /**
     * `git for-each-ref --format=[BRANCH_FORMAT] refs/heads refs/remotes`: local branches newest first, then the
     * remote branches no local branch has (by name or as its upstream), newest first. `origin/HEAD` is left out.
     */
    fun branches(output: String): BranchList {
        val all = output.split(RS).mapNotNull { record ->
            val f = record.trimStart('\n').split(FS)
            if (f.size < 9) return@mapNotNull null
            val ref = f[0]
            val remote = ref.startsWith("refs/remotes/")
            if (!remote && !ref.startsWith("refs/heads/")) return@mapNotNull null
            if (remote && ref.endsWith("/HEAD")) return@mapNotNull null
            val track = f[3]
            Branch(
                name = ref.removePrefix("refs/heads/").removePrefix("refs/remotes/"),
                remote = remote,
                sha = f[1],
                upstream = f[2].ifEmpty { null },
                ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                upstreamGone = track == "gone",
                time = f[4].toLongOrNull() ?: 0L,
                current = f[5] == "*",
                worktree = f[6].ifEmpty { null },
                author = f[7],
                subject = f[8],
            )
        }
        val local = all.filter { !it.remote }.sortedByDescending { it.time }
        val taken = local.mapNotNull { it.upstream }.toSet() + local.map { it.name }
        val remoteOnly = all.filter { it.remote && it.name !in taken && it.shortName !in taken }.sortedByDescending { it.time }
        return BranchList(local, remoteOnly)
    }

    /**
     * `git worktree list --porcelain -z`: one NUL-ended line per attribute (`worktree <path>`, `HEAD <sha>`,
     * `branch refs/heads/<name>`, `detached`, `locked [reason]`, `prunable [reason]`), an empty one between worktrees.
     * The first is the main checkout.
     */
    fun worktrees(output: String): List<Worktree> {
        val result = mutableListOf<Worktree>()
        var current: Worktree? = null
        fun flush() { current?.let { result += it.copy(main = result.isEmpty()) }; current = null }
        for (line in output.split(NUL)) {
            val key = line.substringBefore(' ')
            val value = if (' ' in line) line.substringAfter(' ') else ""
            when (key) {
                "" -> flush()
                "worktree" -> { flush(); current = Worktree(value, "", null) }
                "HEAD" -> current = current?.copy(head = value)
                "branch" -> current = current?.copy(branch = value.removePrefix("refs/heads/"))
                "locked" -> current = current?.copy(locked = value)
                "prunable" -> current = current?.copy(prunable = value.ifEmpty { "folder is gone" })
            }
        }
        flush()
        return result
    }

    /** Files in `git status --porcelain -z`: a rename or copy (`R`, `C`) is followed by its old path, which isn't counted. */
    fun changeCount(output: String): Int {
        var count = 0
        var skip = false
        for (entry in output.split(NUL)) {
            if (skip) { skip = false; continue }
            if (entry.length < 4) continue
            count++
            if (entry[0] == 'R' || entry[0] == 'C') skip = true
        }
        return count
    }

    /** The stash message that keeps a branch's uncommitted changes while it is switched away from. */
    const val STASH_MARKER = "pocketrocket:"

    /**
     * `git stash list --format=%gd%x1f%gs`: the entry ("stash@{2}") holding [branch]'s changes, put there with the
     * message `pocketrocket:<branch>` (git shows it as "On <branch>: pocketrocket:<branch>"), or null.
     */
    fun stashFor(output: String, branch: String): String? = output.lineSequence().mapNotNull { line ->
        val (ref, subject) = line.split(FS).takeIf { it.size >= 2 } ?: return@mapNotNull null
        ref.takeIf { subject == STASH_MARKER + branch || subject.endsWith(": $STASH_MARKER$branch") }
    }.firstOrNull()

    /** `git log --decorate=full --format=` for [graph]: sha, parents, decorations, author, unix time, subject. */
    const val GRAPH_FORMAT = "%H%x1f%P%x1f%D%x1f%an%x1f%at%x1f%s%x1e"

    /** `git log --topo-order --decorate=full --format=[GRAPH_FORMAT]`. */
    fun graph(output: String): List<GraphCommit> =
        output.split(RS).mapNotNull { record ->
            val f = record.trimStart('\n').split(FS)
            if (f.size < 6) null
            else GraphCommit(
                sha = f[0],
                parents = f[1].split(' ').filter { it.isNotEmpty() },
                refs = refs(f[2]),
                author = f[3],
                time = f[4].toLongOrNull() ?: 0L,
                subject = f[5],
            )
        }

    /**
     * `%D` with `--decorate=full`: "HEAD -> refs/heads/main, refs/remotes/origin/main, tag: refs/tags/v1". Local
     * branches first, then remote ones, then tags; `origin/HEAD`, the stash and a bare detached HEAD are left out.
     */
    fun refs(decorations: String): List<GraphRef> =
        decorations.split(", ").mapNotNull { raw ->
            val head = raw.startsWith("HEAD -> ")
            val d = raw.removePrefix("HEAD -> ").trim()
            when {
                d.startsWith("tag: refs/tags/") -> GraphRef(d.removePrefix("tag: refs/tags/"), GraphRef.Kind.Tag)
                d.startsWith("refs/heads/") -> GraphRef(d.removePrefix("refs/heads/"), GraphRef.Kind.Local, head)
                d.startsWith("refs/remotes/") && !d.endsWith("/HEAD") -> GraphRef(d.removePrefix("refs/remotes/"), GraphRef.Kind.Remote)
                else -> null
            }
        }.sortedBy { it.kind.ordinal }

    /** `git ls-tree -z HEAD [dir/]`: folders first, then files, each by name. */
    fun lsTree(output: String): List<TreeEntry> =
        output.split(NUL).mapNotNull { record ->
            val tab = record.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val meta = record.substring(0, tab).split(' ')
            val type = meta.getOrNull(1)
            TreeEntry(record.substring(tab + 1), isDir = type == "tree", isSubmodule = type == "commit")
        }.sortedWith(compareBy<TreeEntry>({ !it.isDir }, { it.name.lowercase() }))

    /**
     * Unified diff of one file into rows: hunk headers, lines with old and new numbers, notes.
     * Stops after [maxRows] rows and marks the result truncated.
     */
    fun diff(output: String, maxRows: Int = 50_000): Diff {
        val rows = ArrayList<DiffRow>()
        var added = 0
        var deleted = 0
        var binary = false
        var inHunk = false
        var old = 0
        var new = 0
        var truncated = false
        val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
        for (line in output.lineSequence()) {
            if (rows.size >= maxRows) { truncated = true; break }
            when {
                line.startsWith("@@") -> {
                    val m = hunk.find(line)
                    old = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    new = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    inHunk = true
                    rows += DiffRow.Hunk(line)
                }
                line.startsWith("diff --git") -> inHunk = false
                !inHunk -> if (line.startsWith("Binary files")) binary = true
                line.startsWith("+") -> { rows += DiffRow.Line('+', null, new++, line.substring(1)); added++ }
                line.startsWith("-") -> { rows += DiffRow.Line('-', old++, null, line.substring(1)); deleted++ }
                line.startsWith(" ") -> rows += DiffRow.Line(' ', old++, new++, line.substring(1))
                line.startsWith("\\") -> rows += DiffRow.Note(line.removePrefix("\\ "))
                line.isEmpty() -> Unit
            }
        }
        return Diff(rows, added, deleted, binary, truncated)
    }
}
