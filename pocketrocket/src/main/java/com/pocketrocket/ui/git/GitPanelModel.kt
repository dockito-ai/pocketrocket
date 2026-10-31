package com.pocketrocket.ui.git

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketrocket.git.Branch
import com.pocketrocket.git.BranchSwitch
import com.pocketrocket.git.BranchList
import com.pocketrocket.git.ChangeArea
import com.pocketrocket.git.Commit
import com.pocketrocket.git.FileChange
import com.pocketrocket.git.GitException
import com.pocketrocket.git.GitOutcome
import com.pocketrocket.git.GitRepo
import com.pocketrocket.git.GitStatus
import com.pocketrocket.git.GraphCommit
import com.pocketrocket.git.GraphLayout
import com.pocketrocket.git.GraphRef
import com.pocketrocket.git.GraphRow
import com.pocketrocket.git.MergeMethod
import com.pocketrocket.git.PrFile
import com.pocketrocket.git.PrFilter
import com.pocketrocket.git.PullRequest
import com.pocketrocket.git.PullRequests
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The panel's tabs; Files opens the browser instead. */
enum class PanelTab { Changes, Graph, Prs }

/**
 * A switch waiting for an answer, because there are [changed] uncommitted files on [from] or a session
 * ([workingSession], its title) is working in this folder. [run] does the switch itself.
 */
class SwitchRequest(
    val target: String,
    val changed: Int,
    val from: String?,
    val workingSession: String?,
    val run: suspend (GitRepo) -> GitOutcome,
)

/**
 * State of the git viewer for one repo: status and recent commits, branches, refreshed on demand, plus the
 * write actions. Diff and Browse load their own data straight from [repo].
 */
class GitPanelModel(application: Application) : AndroidViewModel(application) {

    var repo by mutableStateOf<GitRepo?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<GitStatus?>(null); private set
    var commits by mutableStateOf<List<Commit>>(emptyList()); private set
    var loading by mutableStateOf(false); private set

    /** A push, pull or commit in flight ("Pushing…"), shown on the panel. */
    var busy by mutableStateOf<String?>(null); private set

    /** Last action's result for a snackbar; cleared once shown. */
    var message by mutableStateOf<String?>(null)

    /** Which panel tab is showing, kept here so it survives a trip to a diff and back. */
    var tab by mutableStateOf(PanelTab.Changes)

    var branches by mutableStateOf(BranchList.Empty); private set

    /** "main": the branch others are merged into, and the only one Delete checks against. */
    var defaultBranch by mutableStateOf<String?>(null); private set

    /** Local branches already merged into [defaultBranch]. */
    var merged by mutableStateOf<Set<String>>(emptySet()); private set

    /** The branch sheet is open. */
    var showBranches by mutableStateOf(false)

    /** A switch that needs the user's answer (uncommitted changes or a busy session); see [SwitchDialog]. */
    var switchRequest by mutableStateOf<SwitchRequest?>(null)

    /** The Graph tab shows every branch (true) or HEAD's history. */
    var graphAll by mutableStateOf(true); private set
    var graph by mutableStateOf<List<GraphCommit>>(emptyList()); private set
    var graphRows by mutableStateOf<List<GraphRow>>(emptyList()); private set

    /** The whole history is loaded; no more pages. */
    var graphEnd by mutableStateOf(false); private set
    private var graphJob: Job? = null

    /** gh for this repo, as its workspace's GitHub account. */
    var prs by mutableStateOf<PullRequests?>(null); private set

    /** Why the PRs tab has nothing to show (no account, not GitHub, offline...), or null. */
    var prNote by mutableStateOf<String?>(null); private set
    var prFilter by mutableStateOf(PrFilter.Open); private set

    /** The PRs under [prFilter]; null while loading. */
    var prList by mutableStateOf<List<PullRequest>?>(null); private set

    /** Open PRs, for the tab count and the PR numbers on branches; null until loaded or when unavailable. */
    var openPrs by mutableStateOf<List<PullRequest>?>(null); private set
    var toReview by mutableStateOf<Int?>(null); private set

    /** The open PR of the checked-out branch, for the branch card. */
    var branchPr by mutableStateOf<PullRequest?>(null); private set

    /** Bumped after a PR action, so an open PR screen loads again. */
    var prVersion by mutableStateOf(0); private set
    private val prFileCache = HashMap<Int, List<PrFile>>()

    private var folder: String? = null

    fun open(path: String) {
        if (folder == path) return
        folder = path
        viewModelScope.launch {
            try {
                val opened = GitRepo.open(getApplication(), File(path))
                repo = opened
                prs = PullRequests(getApplication(), opened.root)
                refresh()
                refreshPrs()
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    // Pull requests: every gh failure only hides PR UI, with a short note in the PRs tab.

    /** Loads the open PRs, the review count, the current branch's PR and the list under [prFilter]. */
    fun refreshPrs() {
        val prs = prs ?: return
        viewModelScope.launch {
            val reason = prs.unavailable()
            if (reason != null) {
                prNote = reason
                openPrs = null; prList = emptyList(); toReview = null; branchPr = null
                return@launch
            }
            val branch = async { prs.forCurrentBranch() }
            val review = async { runCatching { prs.list(PrFilter.ToReview, limit = 100, fields = "number").size }.getOrNull() }
            try {
                val open = prs.list(PrFilter.Open)
                openPrs = open
                if (prFilter == PrFilter.Open) prList = open
                prNote = null
            } catch (e: Exception) {
                openPrs = null
                prNote = "Couldn't get pull requests from GitHub: ${e.message}"
            }
            toReview = review.await()
            branchPr = branch.await()?.takeIf { it.isOpen }
            if (prFilter != PrFilter.Open && prNote == null) showPrs(prFilter, force = true)
        }
    }

    fun showPrs(filter: PrFilter, force: Boolean = false) {
        if (filter == prFilter && !force && prList != null) return
        prFilter = filter
        val prs = prs ?: return
        if (filter == PrFilter.Open && openPrs != null) { prList = openPrs; return }
        prList = null
        viewModelScope.launch {
            val list = try { prs.list(filter) } catch (e: Exception) { prNote = "Couldn't get pull requests from GitHub: ${e.message}"; emptyList() }
            if (prFilter == filter) prList = list
        }
    }

    /** The open PR whose branch is [branch] ("feat/x"), if known. */
    fun prFor(branch: String): PullRequest? = openPrs?.firstOrNull { it.headRef == branch }

    /** "#42 open" or "#37 draft" for the branch sheet. */
    fun prLabel(branch: String): String? = prFor(branch)?.let { "#${it.number} ${if (it.isDraft) "draft" else "open"}" }

    /** A PR's files with their diffs, from `gh pr diff`, kept while the screen is open. */
    suspend fun prFiles(number: Int): List<PrFile> =
        prFileCache[number] ?: (prs ?: throw GitException("No GitHub account")).files(number).also { prFileCache[number] = it }

    /** Checks out a PR in this folder, with the same questions as switching a branch. */
    fun checkoutPr(number: Int) {
        val prs = prs ?: return
        requestSwitch("PR #$number") { prs.checkout(number) }
    }

    fun mergePr(number: Int, method: MergeMethod, deleteBranch: Boolean) = prAct("Merging #$number…") { it.merge(number, method, deleteBranch) }

    fun closePr(number: Int) = prAct("Closing #$number…") { it.close(number) }

    fun markReady(number: Int) = prAct("Marking #$number ready…") { it.ready(number) }

    fun reviewPr(number: Int, approve: Boolean, body: String) =
        prAct(if (approve) "Approving #$number…" else "Requesting changes on #$number…") { it.review(number, approve, body) }

    /**
     * Review with Claude: a worktree on the PR (gh checks it out there, under a pr-<n> branch when its own branch is
     * taken by another folder), then [onReady] with the folder.
     */
    fun reviewInWorktree(number: Int, onReady: (File) -> Unit) {
        val repo = repo ?: return
        val prs = prs ?: return
        val dir = worktreeDir(repo.root, "pr-$number")
        act("Preparing PR #$number…") {
            val added = it.addDetachedWorktree(dir)
            if (!added.ok) return@act added
            var out = prs.checkout(number, dir)
            if (!out.ok) out = prs.checkout(number, dir, branch = "pr-$number")
            if (!out.ok) { it.removeWorktree(dir); return@act out }
            onReady(dir)
            out
        }
    }

    private fun prAct(label: String, action: suspend (PullRequests) -> GitOutcome) {
        val prs = prs ?: return
        act(label, after = { prVersion++; prFileCache.clear(); refreshPrs() }) { action(prs) }
    }

    /** After a switch: the new branch's PR for the branch card. */
    private fun refreshBranchPr() {
        val prs = prs ?: return
        if (prNote != null) return
        viewModelScope.launch { branchPr = prs.forCurrentBranch()?.takeIf { it.isOpen } }
    }

    fun refresh() {
        val repo = repo ?: return
        viewModelScope.launch {
            loading = true
            try {
                val s = repo.status()
                val log = async { repo.log(upstream = s.branch.upstream) }
                val list = async { repo.branches() }
                val base = repo.defaultBranch()
                val mergedSet = base?.let { repo.mergedInto(it) }.orEmpty()
                status = s
                commits = log.await()
                branches = list.await()
                defaultBranch = base
                merged = mergedSet
                error = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
        if (tab == PanelTab.Graph || graph.isNotEmpty()) loadGraph(reset = true)
    }

    // Graph.

    fun showGraph(all: Boolean) {
        if (all == graphAll && graph.isNotEmpty()) return
        graphAll = all
        graph = emptyList()
        graphRows = emptyList()
        loadGraph(reset = true)
    }

    /** The next page of the graph, when scrolled near the end. */
    fun loadMoreGraph() {
        if (!graphEnd && graphJob?.isActive != true) loadGraph(reset = false)
    }

    /**
     * Loads the graph: from the top again when [reset] (as many commits as were showing, at least one page), else the
     * next page. Lanes are laid out again over everything loaded, off the main thread.
     */
    fun loadGraph(reset: Boolean) {
        val repo = repo ?: return
        graphJob?.cancel()
        graphJob = viewModelScope.launch {
            val all = graphAll
            val skip = if (reset) 0 else graph.size
            val count = if (reset) maxOf(GRAPH_PAGE, graph.size) else GRAPH_PAGE
            val page = repo.graph(all, skip, count)
            val list = if (reset) page else graph + page
            val rows = withContext(Dispatchers.Default) { GraphLayout.layout(list) }
            if (all != graphAll) return@launch
            graph = list
            graphRows = rows
            graphEnd = page.size < count
        }
    }

    /** Checks out the branch a graph label names: the local branch, or a remote one as a new tracking branch. */
    fun checkout(ref: GraphRef) {
        val local = branches.local.firstOrNull { it.name == ref.shortName }
        val remote = branches.remoteOnly.firstOrNull { it.name == ref.name }
        when {
            local != null -> switchTo(local)
            remote != null -> switchTo(remote)
            ref.kind == GraphRef.Kind.Remote -> requestSwitch(ref.shortName) { it.track(ref.name) }
            else -> requestSwitch(ref.name) { it.switchTo(ref.name) }
        }
    }

    fun toggleStage(change: FileChange) = act(null) {
        if (change.area == ChangeArea.Staged) it.unstage(change.path) else it.stage(change.path)
    }

    fun stageAll() = act(null) { it.stageAll() }

    fun commit(message: String) = act("Committing…") { it.commit(message) }

    fun push() = act("Pushing…") { it.push(hasUpstream = status?.branch?.upstream != null) }

    fun pull() = act("Pulling…") { it.pull() }

    // Branches.

    /** Switches to a local branch, or checks out a remote one as a new tracking branch. */
    fun switchTo(branch: Branch) =
        requestSwitch(branch.shortName) { if (branch.remote) it.track(branch.name) else it.switchTo(branch.name) }

    /** A new branch from HEAD, switched to; uncommitted changes come along, so no question asked. */
    fun newBranch(name: String) = act("Creating $name…") { it.createBranch(name) }

    /** A new branch at [sha] (the graph's "New branch here"), switched to like any other switch. */
    fun newBranchAt(name: String, sha: String) = requestSwitch(name) { it.createBranch(name, sha) }

    fun deleteBranch(name: String) = act("Deleting $name…") { it.deleteBranch(name) }

    /**
     * Runs a switch ([run]) straight away when the folder is clean and no session works in it; otherwise asks
     * first through [switchRequest].
     */
    fun requestSwitch(target: String, run: suspend (GitRepo) -> GitOutcome) {
        val s = status ?: return
        val changed = s.changes.distinctBy { it.path }.size
        val working = workingSession()
        if (changed == 0 && working == null) act("Switching…", after = ::refreshBranchPr) { BranchSwitch.run(it, run, keep = false) }
        else switchRequest = SwitchRequest(target, changed, s.branch.head, working, run)
    }

    /** The answer to [switchRequest]: [keep] stashes the changes on the current branch first. */
    fun confirmSwitch(keep: Boolean) {
        val request = switchRequest ?: return
        switchRequest = null
        act("Switching…", after = ::refreshBranchPr) { BranchSwitch.run(it, request.run, keep && request.changed > 0 && request.from != null) }
    }

    /** The title of a session working (or waiting on a permission) in this folder, or null. */
    fun workingSession(): String? = repo?.root?.let(BranchSwitch::workingSession)

    /** The title of a session open in [folder], for "used by" on a branch checked out in a worktree. */
    fun sessionIn(folder: String): String? =
        SessionManager.sessions.value.map { it.state.value }.firstOrNull { it.folder == folder }?.title

    /**
     * Prepares a folder for a new session on [name]: the folder it is already checked out in, else a new worktree
     * (an existing local branch, a remote one tracked as a new local branch, or a new branch from HEAD). Calls
     * [onReady] with the folder once it exists.
     */
    fun worktreeFor(name: String, onReady: (File) -> Unit) {
        val repo = repo ?: return
        val local = branches.local.firstOrNull { it.name == name }
        local?.worktree?.let { onReady(File(it)); return }
        val remote = if (local == null) branches.remoteOnly.firstOrNull { it.name == name || it.shortName == name } else null
        val dir = worktreeDir(repo.root, local?.name ?: remote?.shortName ?: name)
        act("Creating worktree…") {
            val outcome = when {
                local != null -> it.addWorktree(dir, local.name)
                remote != null -> it.addWorktree(dir, remote.name, remote = true)
                else -> it.addWorktree(dir, name, create = true)
            }
            if (outcome.ok) onReady(dir)
            outcome
        }
    }

    /** Runs a write action, reports failures (and long-running successes), then refreshes. */
    private fun act(label: String?, after: (() -> Unit)? = null, action: suspend (GitRepo) -> GitOutcome) {
        val repo = repo ?: return
        if (busy != null) return
        viewModelScope.launch {
            busy = label ?: ""
            val outcome = try { action(repo) } catch (e: Exception) { GitOutcome(false, e.message ?: "Failed") }
            busy = null
            if (!outcome.ok) message = outcome.message
            else if (label != null) message = outcome.message.lines().firstOrNull { it.isNotBlank() } ?: outcome.message
            refresh()
            after?.invoke()
        }
    }

    companion object {
        /** Commits per page of the graph. */
        const val GRAPH_PAGE = 200

        /**
         * Where a worktree for [branch] goes: `<workspace>/.worktrees/<repo>-<branch>` (beside the repo when it is in
         * no workspace), so sessions there keep the workspace's accounts. A taken name gets -2, -3...
         */
        fun worktreeDir(root: File, branch: String): File {
            val base = Workspaces.containing(root.path)?.dir ?: root.parentFile ?: root
            val parent = File(base, ".worktrees")
            val stem = "${root.name}-${branch.replace(Regex("[^A-Za-z0-9._-]"), "-")}"
            var dir = File(parent, stem)
            var n = 2
            while (dir.exists()) dir = File(parent, "$stem-${n++}")
            return dir
        }
    }
}
