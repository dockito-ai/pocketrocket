package com.pocketrocket.workspaces

import android.content.Context
import com.pocketrocket.notifications.WorkspaceNotifier
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.workspaces.WorkspaceCli.AccountPicks
import com.pocketrocket.workspaces.WorkspaceCli.Command
import com.pocketrocket.workspaces.WorkspaceCli.Error
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs one `pocketrocket-workspace` request inside the app (decision 73), through the same code as the
 * screens: [Workspaces.add] (which binds the accounts, so ~/.gitconfig's includeIf and env.sh follow) and
 * [CloneManager] for clones. Add and clone only: nothing is removed, renamed or moved from a shell.
 * [out] gets the lines the script prints; the result is its exit code.
 */
object WorkspaceCliRunner {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2

    suspend fun run(context: Context, request: WorkspaceCli.Request, out: (String) -> Unit): Int {
        val command = try {
            WorkspaceCli.parseArgs(request.args)
        } catch (e: Error) {
            out("error: ${e.message}")
            return USAGE
        }
        return try {
            when (command) {
                is Command.Add -> add(context, request, command, out)
                is Command.Clone -> clone(context, request, command, out)
                is Command.ListAll -> { out(if (command.json) listJson() else listText()); OK }
            }
        } catch (e: Error) {
            out("error: ${e.message}")
            FAILED
        }
    }

    private suspend fun add(context: Context, request: WorkspaceCli.Request, cmd: Command.Add, out: (String) -> Unit): Int {
        val path = checkedPath(cmd.path, request.cwd)
        val dir = File(path)
        if (!dir.isDirectory) throw Error("There is no folder at ${Workspaces.tilde(path)}.")
        val all = Workspaces.all
        Workspaces.get(path)?.let {
            out("${it.name} (${Workspaces.tilde(path)}) is already a workspace. Nothing to do.")
            return OK
        }
        WorkspaceCli.containing(all, path)?.let {
            out("${Workspaces.tilde(path)} is already part of workspace ${it.name} (${Workspaces.tilde(it.path)}); it shows there. Nothing to do.")
            return OK
        }
        WorkspaceCli.inside(all, path).takeIf { it.isNotEmpty() }?.let { list ->
            throw Error("${Workspaces.tilde(path)} holds workspaces already (${list.joinToString { it.name }}). Add the repo folder itself.")
        }
        val accounts = accountsFor(path, request, cmd.accounts)
        val ws = Workspaces.add(path, cmd.name ?: dir.name, accounts.claude, accounts.github, accounts.dockito)
        runCatching { RepoStatus.refresh(context, ws) }
        out("Added ${ws.name} as a workspace: ${Workspaces.tilde(ws.path)} (${accountsLine(ws.path)}).")
        WorkspaceNotifier.added(context, ws, request.fromClaude)
        return OK
    }

    private suspend fun clone(context: Context, request: WorkspaceCli.Request, cmd: Command.Clone, out: (String) -> Unit): Int {
        val url = cmd.url.trim()
        if (url.isEmpty() || url.startsWith("-")) throw Error("Give the repo's URL.")
        val path = checkedPath(cmd.folder ?: RepoClone.nameFor(url), request.cwd)
        val target = File(path)
        if (target.exists() && target.list()?.isNotEmpty() != false && !RepoClone.isLeftoverOf(target, url)) {
            val hint = if (WorkspaceRepos.isRepo(target)) " If it is this repo, register it with: pocketrocket-workspace add ${Workspaces.tilde(path)}" else ""
            throw Error("${Workspaces.tilde(path)} already exists.$hint")
        }
        val accounts = accountsFor(path, request, cmd.accounts)
        val parent = target.parentFile!!.path
        val label = RepoClone.labelFor(url)
        val clone = CloneRequest(url, parent, target.name, accounts.github, cmd.branch, label, cmd.name, accounts.claude, accounts.dockito)
        val job = CloneManager.start(context, clone)
            ?: throw Error("Another clone is running in PocketRocket. Try again when it ends.")
        out("Cloning $label into ${Workspaces.tilde(path)}…")
        var phase: String? = null
        val end = CloneManager.jobs
            .map { list -> list.firstOrNull { it.id == job.id } }
            .onEach { j ->
                val p = j?.progress as? CloneProgress.Running ?: return@onEach
                if (p.phase != phase) { phase = p.phase; out("  ${p.phase}…") }
            }
            .first { it == null || !it.running }
        return when (val p = end?.progress) {
            is CloneProgress.Done -> {
                val ws = p.workspace?.let(Workspaces::get)
                when {
                    ws == null -> out("Cloned $label into ${Workspaces.tilde(p.dir.path)}.")
                    ws.path == p.dir.path -> {
                        out("Cloned $label and added ${ws.name} as a workspace: ${Workspaces.tilde(ws.path)} (${accountsLine(ws.path)}).")
                        WorkspaceNotifier.added(context, ws, request.fromClaude)
                    }
                    else -> out("Cloned $label into ${Workspaces.tilde(p.dir.path)}, part of workspace ${ws.name}.")
                }
                OK
            }
            is CloneProgress.Failed -> { out("error: Couldn't clone $label: ${p.message}"); FAILED }
            else -> { out("error: The clone of $label was cancelled in the app."); FAILED }
        }
    }

    /** [raw] resolved from the caller's folder and checked to be under ~/projects (also after symlinks). */
    private fun checkedPath(raw: String, cwd: String): String {
        val home = AccountPaths.home.path
        val root = Workspaces.projectsRoot
        val path = WorkspaceCli.resolve(raw, cwd, home)
        WorkspaceCli.checkUnderProjects(path, root.path)?.let { throw Error(it) }
        // A symlink inside ~/projects may point elsewhere: check where the nearest existing folder really is.
        val existing = generateSequence(File(path)) { it.parentFile }.firstOrNull { it.exists() }
        if (existing != null) {
            val real = runCatching { existing.canonicalPath }.getOrNull()
            val realRoot = runCatching { root.canonicalPath }.getOrNull()
            if (real != null && realRoot != null && real != realRoot && !real.startsWith("$realRoot/")) {
                throw Error("${Workspaces.tilde(path)} leads outside ~/projects.")
            }
        }
        return path
    }

    /** Flags first, then the target's or the caller's workspace (decision 73); each id is checked to exist. */
    private fun accountsFor(target: String, request: WorkspaceCli.Request, given: AccountPicks): AccountPicks {
        val explicit = AccountPicks(
            claude = given.claude?.let { WorkspaceCli.match("Claude", it, ClaudeAccounts.current.accounts).id },
            github = given.github?.let { WorkspaceCli.match("GitHub", it, GitHubAccounts.current.accounts).id },
            dockito = given.dockito?.let { WorkspaceCli.match("Dockito", it, DockitoAccounts.current.accounts).id },
        )
        val cwd = AccountPaths.normalize(request.cwd)
        val session = request.sessionFolder?.let(AccountPaths::normalize)
        val source = WorkspaceCli.accountSource(Workspaces.all, target, session, cwd)
        return WorkspaceCli.fill(explicit, source?.let { AccountWiring.bindingFor(it.path) })
    }

    /** "Claude: personal · GitHub: shankar · Dockito: me@x.com" as the folder resolves them now. */
    private fun accountsLine(path: String): String = listOfNotNull(
        "Claude: ${AccountWiring.claudeFor(path).name}",
        AccountWiring.githubFor(path)?.let { "GitHub: ${it.login}" },
        AccountWiring.dockitoFor(path)?.let { "Dockito: ${it.email}" },
    ).joinToString(" · ")

    private fun listText(): String = buildString {
        val all = Workspaces.all
        append(if (all.isEmpty()) "No workspaces yet.\n" else "Workspaces:\n")
        all.forEach { append("  ${it.name}  ${Workspaces.tilde(it.path)}  ${accountsLine(it.path)}\n") }
        append("Accounts (pass the id to --claude, --github or --dockito):\n")
        fun line(kind: String, ids: List<Pair<String, String>>, default: String?) {
            append("  $kind: ")
            append(if (ids.isEmpty()) "none" else ids.joinToString(", ") { (id, name) -> (if (id == name) id else "$id ($name)") + if (id == default) " default" else "" })
            append("\n")
        }
        line("Claude", ClaudeAccounts.current.accounts.map { it.id to it.name }, ClaudeAccounts.default.id)
        line("GitHub", GitHubAccounts.current.accounts.map { it.id to it.login }, GitHubAccounts.default?.id)
        line("Dockito", DockitoAccounts.current.accounts.map { it.id to it.name }, DockitoAccounts.default?.id)
    }.trimEnd()

    private fun listJson(): String {
        val workspaces = JSONArray(Workspaces.all.map { ws ->
            JSONObject().put("name", ws.name).put("path", ws.path)
                .put("claude", AccountWiring.claudeFor(ws.path).id)
                .put("github", AccountWiring.githubFor(ws.path)?.id ?: JSONObject.NULL)
                .put("dockito", AccountWiring.dockitoFor(ws.path)?.id ?: JSONObject.NULL)
        })
        fun accounts(list: List<Pair<String, String>>, default: String?) =
            JSONArray(list.map { (id, name) -> JSONObject().put("id", id).put("name", name).put("default", id == default) })
        val accounts = JSONObject()
            .put("claude", accounts(ClaudeAccounts.current.accounts.map { it.id to it.name }, ClaudeAccounts.default.id))
            .put("github", accounts(GitHubAccounts.current.accounts.map { it.id to it.login }, GitHubAccounts.default?.id))
            .put("dockito", accounts(DockitoAccounts.current.accounts.map { it.id to it.name }, DockitoAccounts.default?.id))
        return JSONObject().put("workspaces", workspaces).put("accounts", accounts).toString(2)
    }
}
