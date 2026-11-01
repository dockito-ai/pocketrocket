package com.pocketrocket.wrangler

import android.content.Context
import com.pocketrocket.clilogin.CliLogin
import com.pocketrocket.clilogin.CliLogins
import com.pocketrocket.node.NpmGlobal
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.setup.ToolScript
import com.pocketrocket.setup.finished
import com.pocketrocket.termux.Termux
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * The optional Wrangler tool (decision 49). Settings → Tools installs and removes it through the setup script; first-run
 * setup never touches it. Sign-in runs Wrangler's own OAuth flow through the shared CLI login ([login], decision 98).
 * The token stays in Wrangler's config file. The sign-in address lives only in the live log, in memory: Wrangler's debug
 * log for these runs goes to a temp folder deleted after.
 */
object Wrangler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val dir = File("$prefix/${WranglerTool.PACKAGE_RELATIVE}")

    private val _state = MutableStateFlow(WranglerState())
    val state: StateFlow<WranglerState> = _state.asStateFlow()

    /** Its Cloudflare login, from its page. */
    val login: CliLogin get() = CliLogins.of(WranglerLogin)

    /** Wrangler's folder as it is now, whoever installed it ([WranglerTool.presence]). Small file reads. */
    fun presence(): ToolPresence = WranglerTool.presence(dir)

    /** What's on disk now, and its versions from npm's package files. */
    private fun read(s: WranglerState): WranglerState {
        val p = presence()
        return s.copy(
            installed = p.present,
            problem = p.problem,
            version = WranglerTool.packageVersion(dir).takeIf { p.present },
            workerdVersion = WranglerTool.packageVersion(File(dir, WranglerTool.WORKERD_PACKAGE_RELATIVE)).takeIf { p.present },
            checked = true,
        )
    }

    fun refresh() {
        _state.update(::read)
        login.refresh()
    }

    /** Asks Wrangler which account the login belongs to. Needs the network; without it the page just shows no address. */
    fun refreshAccount(context: Context) {
        _state.update(::read)
        val s = _state.value
        login.refreshAccount(context, s.installed && s.problem == null)
    }

    fun install(context: Context) = run(context, remove = false)

    /**
     * Its Updates row: the install again, which puts in the version this PocketRocket pins ([WranglerTool.VERSION]) with
     * its Android workerd. Wrangler is pinned because each release needs a workerd built for Android, so it only has an
     * update when an app update moves the pin past what is on the phone.
     */
    fun update(context: Context) = run(context, remove = false, isUpdate = true)

    fun remove(context: Context) = run(context, remove = true)

    private fun run(context: Context, remove: Boolean, isUpdate: Boolean = false) {
        if (_state.value.running || login.state.value.busy) return
        val app = context.applicationContext
        val option = if (remove) "wrangler-remove" else "wrangler"
        _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null, failed = false, removing = remove, updating = isUpdate) }
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option) { change -> _state.update { s -> s.copy(job = s.job?.let(change)) } }
            val onDisk = presence()
            val ok = exit == 0 && sawDone && if (remove) !onDisk.present else onDisk.complete
            if (remove) login.forget()
            _state.update { s ->
                read(s).copy(
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
        }
    }
}

data class WranglerState(
    /** npm's folder for Wrangler is there ([ToolPresence.present]). */
    val installed: Boolean = false,
    /** What keeps it from working fully ("local dev missing"); Repair runs the install again. */
    val problem: String? = null,
    /** Wrangler's and its workerd's versions, from their package.json files. */
    val version: String? = null,
    val workerdVersion: String? = null,
    val checked: Boolean = false,
    val job: SetupState? = null,
    val outcome: String? = null,
    val failed: Boolean = false,
    /** The last run was a removal, not an install. */
    val removing: Boolean = false,
    /** The last run was its Updates row's update (the install, to the pinned version). */
    val updating: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running

    /** This PocketRocket pins a newer Wrangler than the one on the phone. Not while it needs Repair. */
    val updateAvailable: Boolean get() = installed && problem == null && NpmGlobal.newer(version, WranglerTool.VERSION)
}
