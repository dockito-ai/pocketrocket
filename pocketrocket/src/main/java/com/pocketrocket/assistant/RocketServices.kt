package com.pocketrocket.assistant

import android.content.Context
import android.content.pm.ApplicationInfo
import com.pocketrocket.git.BranchSwitch
import com.pocketrocket.git.GitOutcome
import com.pocketrocket.git.GitRepo
import com.pocketrocket.git.MergeMethod
import com.pocketrocket.git.PrFilter
import com.pocketrocket.git.PullRequests
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.sessions.PermissionMode
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.workspaces.CloneManager
import com.pocketrocket.workspaces.CloneRequest
import com.pocketrocket.workspaces.GhRepos
import com.pocketrocket.workspaces.RepoClone
import com.pocketrocket.workspaces.WorkspaceRepos
import com.pocketrocket.workspaces.Workspaces
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The local server, up only while the assistant is on. Random tokens for each run of the app. */
object RocketServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: McpServer? = null
    private var watching = false

    @Synchronized
    fun server(context: Context): McpServer {
        val app = context.applicationContext
        server?.let { return it }
        val started = McpServer(
            AssistantTools(AppToolHost(app)),
            hook = { AutoModeNet.decide(it, AssistantPrefs.settings(app).value.enabled) },
            scope = scope,
            rocketHook = { AutoModeNet.decideForRocket(it, phoneIsLocked(app), BrainFiles.scratch) },
        ).also(McpServer::start)
        server = started
        AgentHook.writeSettings(started)
        watch(app)
        return started
    }

    private fun watch(app: Context) {
        if (watching) return
        watching = true
        scope.launch {
            AssistantPrefs.settings(app).collect { if (!it.enabled) stop() }
        }
        // New workspaces are picked up on their own.
        scope.launch { Workspaces.state.drop(1).collect { ProjectScan.refresh(app) } }
    }

    /** Assistant off. Agents already running lose the hook until they restart: it fails open, and Settings says Auto isn't covered with Rocket off. */
    @Synchronized
    private fun stop() {
        server?.stop()
        server = null
        AssistantBrain.shutdown()
    }
}

/** What an agent's Claude Code gets on top of the user's own settings: the Auto safety net, and only while the assistant is on. */
object AgentHook {
    private val file: File get() = File(AssistantMemory.folder, "agent-hook.json")

    /** Rocket's own session's hook: the stricter [AutoModeNet.decideForRocket]. */
    val rocketFile: File get() = File(AssistantMemory.folder, "rocket-hook.json")

    fun arguments(context: Context): List<String> {
        if (!AssistantPrefs.settings(context).value.enabled) return emptyList()
        return runCatching {
            RocketServices.server(context)
            listOf("--settings", file.path)
        }.getOrDefault(emptyList())
    }

    internal fun writeSettings(server: McpServer) {
        AssistantMemory.folder.mkdirs()
        writePrivate(file, AutoModeNet.settings(server.port, server.hookToken))
        writePrivate(rocketFile, AutoModeNet.settings(server.port, server.hookToken, McpServer.ROCKET_HOOK_PATH))
    }
}

/** Fills in Settings › Assistant › Projects: each workspace and the repos in it, with a line from its README. */
object ProjectScan {
    private const val MAX_README = 8 * 1024

    fun refresh(@Suppress("UNUSED_PARAMETER") context: Context) {
        val found = Workspaces.all.flatMap { workspace ->
            val repos = runCatching { WorkspaceRepos.find(workspace.dir) }.getOrDefault(emptyList())
            buildList {
                add(entry(workspace.path, workspace.name))
                repos.filter { it.path != workspace.path }.forEach { add(entry(it.path, it.name)) }
            }
        }
        AssistantMemory.change { it.withProjects(found) }
    }

    private fun entry(path: String, name: String) = ProjectEntry(path, name, description(File(path)))

    private fun description(dir: File): String {
        val readme = listOf("README.md", "README", "readme.md").map { File(dir, it) }.firstOrNull { it.isFile } ?: return ""
        return runCatching {
            val start = CharArray(MAX_README)
            val read = readme.bufferedReader().use { it.read(start) }
            ProjectDescription.fromReadme(String(start, 0, read.coerceAtLeast(0)))
        }.getOrDefault("")
    }
}

/** The app, as the tools see it. Every action is one the app's own screens use. */
internal class AppToolHost(private val app: Context) : ToolHost {
    override val locked: Boolean get() = phoneIsLocked(app)
    override val debugBuild: Boolean get() = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    override val settings: SettingsHost = AppSettingsHost(app)

    override fun memory(): MemoryBook = AssistantMemory.book.value
    override fun changeMemory(edit: (MemoryBook) -> MemoryBook) = AssistantMemory.change(edit)
    override fun sessions(): List<SessionState> = SessionManager.sessions.value.map { it.state.value }

    override suspend fun confirm(action: RocketAction): Boolean = withContext(Dispatchers.Main) {
        val answered = Approvals.confirmOutcome(app, action, listen = AssistantBrain.spoken)
        if (answered == Answered.CantApprove) throw ToolRefused(CANT_APPROVE)
        answered.allowed
    }

    override fun folderIfUsable(path: String): String? {
        if ('/' !in path) return null
        val full = AccountPaths.normalize(path)
        return full.takeIf { (it == AccountPaths.home.path || it.startsWith(AccountPaths.home.path + "/")) && File(it).isDirectory }
    }

    override fun folderExists(path: String) = File(path).isDirectory
    override fun claudeAccounts() = ClaudeAccounts.state.value.accounts.map { it.id to it.name }
    override fun githubAccounts() = GitHubAccounts.state.value.accounts.map { it.id to it.name }
    override fun githubAccountFor(folder: String) = AccountWiring.githubFor(folder)?.id

    override fun startSession(folder: String, loginId: String?, model: String?, mode: PermissionMode): SessionState {
        val profile = loginId?.let { ClaudeAccounts.get(it)?.profile }
        return SessionManager.startClaude(app, File(folder), profile, model, mode).state.value
    }

    private fun session(id: String) = SessionManager.get(id) ?: throw ToolRefused("That session has gone.")
    override fun send(sessionId: String, text: String) = session(sessionId).send(text)
    override fun compact(sessionId: String) = session(sessionId).compact()
    override fun stop(sessionId: String) = session(sessionId).interrupt()
    override fun setModel(sessionId: String, model: String) = session(sessionId).setModel(model)
    override fun setMode(sessionId: String, mode: PermissionMode) = session(sessionId).setMode(mode)
    override fun record(sessionId: String, line: String) = session(sessionId).noteFromRocket(line)
    override fun log(text: String, how: String, sessionId: String?) = ActivityLog.add(app, text, how, null, sessionId)

    override fun clone(url: String, parent: String, accountId: String?, branch: String?): String? {
        val label = RepoClone.labelFor(url)
        return CloneManager.start(app, CloneRequest(url, parent, RepoClone.nameFor(url), accountId, branch, label))?.let { label }
    }

    override fun defaultCloneFolder(): String = Workspaces.projectsRoot.path

}
