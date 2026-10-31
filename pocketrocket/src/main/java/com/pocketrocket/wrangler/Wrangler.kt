package com.pocketrocket.wrangler

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.ToolScript
import com.pocketrocket.setup.finished
import com.pocketrocket.termux.Termux
import com.pocketrocket.termux.TermuxCommand
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
 * setup never touches it. Sign-in runs Wrangler's own OAuth flow. The token stays in Wrangler's config file. The sign-in
 * address lives only in the live log, in memory: Wrangler's debug log for these runs goes to a temp folder deleted after.
 */
object Wrangler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val marker = File("$prefix/${WranglerTool.PACKAGE_RELATIVE}", WranglerTool.INSTALLED_MARKER)
    private val config = File(home, WranglerTool.CONFIG_RELATIVE)
    private val debugLogs = File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, "pocketrocket-wrangler")

    private val _state = MutableStateFlow(WranglerState())
    val state: StateFlow<WranglerState> = _state.asStateFlow()

    @Volatile private var loginProcess: Process? = null

    fun installed(): Boolean = marker.isFile

    private fun signedIn(): Boolean = WranglerTool.hasLogin(runCatching { config.readText() }.getOrNull())

    fun refresh() {
        _state.update { it.copy(installed = installed(), signedIn = signedIn(), checked = true) }
    }

    /** Asks Wrangler which account the login belongs to. Needs the network; without it the page just shows no address. */
    fun refreshAccount(context: Context) {
        refresh()
        val s = _state.value
        // A sign-in the phone killed midway could have left its debug log, with the address in it, in the temp folder.
        if (!s.authBusy) debugLogs.deleteRecursively()
        if (!s.installed || !s.signedIn || s.account != null || s.authBusy) return
        val app = context.applicationContext
        scope.launch {
            val lines = mutableListOf<String>()
            val exit = wrangler(app, "whoami") { synchronized(lines) { lines += it } }
            if (exit == 0) _state.update { it.copy(account = WranglerTool.email(lines)) }
        }
    }

    fun install(context: Context) = run(context, remove = false)

    fun remove(context: Context) = run(context, remove = true)

    private fun run(context: Context, remove: Boolean) {
        if (_state.value.running || _state.value.authBusy) return
        val app = context.applicationContext
        val option = if (remove) "wrangler-remove" else "wrangler"
        _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null, failed = false, removing = remove, authLog = emptyList()) }
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option) { change -> _state.update { s -> s.copy(job = s.job?.let(change)) } }
            val onDisk = installed()
            val ok = exit == 0 && sawDone && onDisk != remove
            _state.update { s ->
                s.copy(
                    installed = onDisk,
                    signedIn = signedIn(),
                    account = if (remove) null else s.account,
                    checked = true,
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
        }
    }

    /**
     * Runs `wrangler login`, streams its output to the page's log and opens the phone's browser on the address it prints.
     * Wrangler waits on localhost for the browser to come back, so the foreground service keeps it alive meanwhile.
     */
    fun login(context: Context) {
        if (_state.value.authBusy || _state.value.running) return
        val app = context.applicationContext
        _state.update { it.copy(auth = AuthAction.LoggingIn, authLog = emptyList(), authFailed = false) }
        Termux.startForegroundService(app)
        scope.launch {
            var opened = false
            val exit = wrangler(app, "login", "--browser=false", keep = { loginProcess = it }) { raw ->
                appendLog(WranglerTool.clean(raw))
                val url = WranglerTool.oauthUrl(raw)
                if (url != null && !opened) {
                    opened = true
                    openBrowser(app, url)
                }
            }
            loginProcess = null
            finishAuth(app, exit)
        }
    }

    /** Stops a sign-in that is waiting for the browser. */
    fun cancelLogin() {
        loginProcess?.destroy()
    }

    /** Runs `wrangler logout`, which revokes the login and clears it from Wrangler's config. */
    fun logout(context: Context) {
        if (_state.value.authBusy || _state.value.running) return
        val app = context.applicationContext
        _state.update { it.copy(auth = AuthAction.LoggingOut, authLog = emptyList(), authFailed = false) }
        scope.launch {
            val exit = wrangler(app, "logout") { appendLog(WranglerTool.clean(it)) }
            finishAuth(app, exit)
        }
    }

    private fun finishAuth(context: Context, exit: Int) {
        val signedIn = signedIn()
        _state.update { s ->
            s.copy(
                auth = null,
                signedIn = signedIn,
                account = if (signedIn) s.account else null,
                authFailed = exit != 0,
                // The live log was the only place the sign-in address was kept, and the sign-in is over.
                authLog = s.authLog.map(WranglerTool::withoutUrl),
            )
        }
        if (signedIn) refreshAccount(context)
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(authLog = (it.authLog + line).takeLast(LOG_LINES)) }
    }

    private fun openBrowser(context: Context, url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) appendLog("Could not open a browser. Copy the link above into one.")
    }

    /** Runs Wrangler to the end with its debug log kept out of its config folder and deleted afterwards. Blocking. */
    private fun wrangler(
        context: Context,
        vararg arguments: String,
        keep: (Process) -> Unit = {},
        onLine: (String) -> Unit,
    ): Int {
        val logs = File(debugLogs, System.nanoTime().toString())
        return try {
            val environment = mapOf("WRANGLER_LOG_PATH" to File(logs, "wrangler.log").path, "NO_COLOR" to "1", "WRANGLER_SEND_METRICS" to "false")
            val process = TermuxCommand.start(context, "$prefix/bin/wrangler", arrayOf(*arguments), home, environment)
            keep(process)
            process.outputStream.close()
            TermuxCommand.drain(process, onLine)
        } catch (e: Exception) {
            onLine("[error] ${e.message ?: e}")
            -1
        } finally {
            logs.deleteRecursively()
            debugLogs.delete() // only goes when empty: another run may still be using it
        }
    }

    private const val LOG_LINES = 200
}

enum class AuthAction { LoggingIn, LoggingOut }

data class WranglerState(
    val installed: Boolean = false,
    val checked: Boolean = false,
    val job: SetupState? = null,
    val outcome: String? = null,
    val failed: Boolean = false,
    /** The last run was a removal, not an install. */
    val removing: Boolean = false,
    val signedIn: Boolean = false,
    /** The signed-in account's email, once Wrangler has said it. */
    val account: String? = null,
    val auth: AuthAction? = null,
    /** Output of the last sign-in or sign-out, in memory only. */
    val authLog: List<String> = emptyList(),
    val authFailed: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    val authBusy: Boolean get() = auth != null
}
