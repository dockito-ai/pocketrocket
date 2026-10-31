package com.pocketrocket.workspaces

import android.content.Context
import com.pocketrocket.keepalive.CloneService
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.GitHubAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Clones that outlive the screen and the activity (decision 42). The app's process owns git, not a
 * composable: [CloneQueue] runs it on the app's own scope, [CloneService] keeps the process in the
 * foreground with the progress notification while a clone runs, and the clone screen and the
 * notification read [jobs]. When a clone lands, the repo is made part of a workspace here, the same
 * way the screen used to do it, so the result is ready whether anyone is watching or not.
 */
object CloneManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private lateinit var app: Context

    private val queue = CloneQueue(scope, { request -> run(request) }, capacity = 1)

    val jobs: StateFlow<List<CloneJob>> get() = queue.jobs

    /** The clone the screen shows: the running one, else the latest result. */
    fun current(): CloneJob? = queue.jobs.value.let { list -> list.firstOrNull { it.running } ?: list.lastOrNull() }

    fun full(): Boolean = queue.full()

    /** Starts [request] when a slot is free; the service and its notification start with it. */
    fun start(context: Context, request: CloneRequest): CloneJob? {
        app = context.applicationContext
        return queue.start(request)?.also { startService() }
    }

    fun cancel(id: Int) = queue.cancel(id)

    fun retry(context: Context, id: Int): CloneJob? {
        app = context.applicationContext
        return queue.retry(id)?.also { startService() }
    }

    fun dismiss(id: Int) = queue.dismiss(id)

    /**
     * The clone already runs; the service only keeps it alive. Android 12+ may refuse a foreground service
     * started from the background (a clone asked for by `pocketrocket-workspace` while the app isn't on
     * screen); the clone then goes on in the process sessions keep alive, without its own notification.
     */
    private fun startService() {
        runCatching { CloneService.start(app) }.onFailure { android.util.Log.w("PocketRocketClone", "clone service not started", it) }
    }

    private fun run(request: CloneRequest): Flow<CloneProgress> {
        val parent = File(request.parent)
        val account = request.accountId?.let(GitHubAccounts::get) ?: AccountWiring.githubFor(request.parent)
        return RepoClone.clone(app, request.url, parent, request.name, account, request.branch).map { p ->
            if (p is CloneProgress.Done) p.copy(workspace = settle(request, p.dir)) else p
        }
    }

    /**
     * Puts a fresh clone in a workspace: the one it landed in, or a new one of its own. A repo cloned as
     * another account than its folder's is bound to that account, so push and pull use it too.
     */
    private suspend fun settle(request: CloneRequest, dir: File): String {
        val folderAccount = AccountWiring.githubFor(request.parent)
        val cloneAs = request.accountId?.let(GitHubAccounts::get) ?: folderAccount
        val parent = AccountWiring.bindingFor(request.parent)
        val other = cloneAs != null && cloneAs.id != folderAccount?.id
        val ws = Workspaces.containing(dir.path)
            ?.also { if (other) AccountWiring.bind(dir.path, parent?.claude, cloneAs!!.id, parent?.dockito) }
            ?: Workspaces.add(
                dir.path, request.workspaceName ?: dir.name,
                claude = request.claude, github = if (other) cloneAs!!.id else parent?.github, dockito = request.dockito,
            )
        runCatching { RepoStatus.refresh(app, ws) }
        return ws.path
    }
}
