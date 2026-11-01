package com.pocketrocket.clilogin

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/** What a login is doing: waiting for the browser, for the pasted code, sending it, or signing out. */
enum class AuthAction { LoggingIn, WaitingForCode, SendingCode, LoggingOut }

data class LoginState(
    /** The CLI's own config has a login, read from disk. */
    val signedIn: Boolean = false,
    /** Who, once the config or the CLI has said. */
    val account: String? = null,
    val auth: AuthAction? = null,
    /** Output of the last login or logout, in memory only; sign-in addresses are taken out once it ends. */
    val log: List<String> = emptyList(),
    val failed: Boolean = false,
) {
    val busy: Boolean get() = auth != null
}

/**
 * One CLI's own login, run from its tool page (decisions 49 and 98): Wrangler, Vercel, Netlify, Firebase, the AWS CLI
 * and flyctl. Log in runs the CLI's login command, streams its output to the page and opens the phone's browser on the
 * address it prints. Then either the CLI finishes by itself once the login is allowed ([LoginSpec.code] null), or the
 * page takes the code the browser shows and hands it to the CLI ([CodeEntry]). Log out runs its logout command.
 * Signed in is read from the CLI's config file each time ([LoginSpec.signedIn]); the login itself stays in that file
 * and nowhere else. Each run gets a temp folder ([LoginSpec.environment]) deleted when it ends.
 */
class CliLogin(val spec: LoginSpec) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val runs = File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, "pocketrocket-login/${spec.key}")

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false

    /** Reads the CLI's config. Small file reads. */
    fun refresh() {
        val signedIn = spec.signedIn(home)
        _state.update { it.copy(signedIn = signedIn, account = if (signedIn) spec.account(home) ?: it.account else null) }
    }

    /**
     * [refresh], then asks the CLI who is signed in when its config doesn't say ([LoginSpec.whoamiArguments]; needs the
     * network, and without it the page just shows no name). Only while [installed].
     */
    fun refreshAccount(context: Context, installed: Boolean) {
        refresh()
        val s = _state.value
        // A login the phone killed midway could have left its run folder behind.
        if (!s.busy) runs.deleteRecursively()
        val whoami = spec.whoamiArguments
        if (!installed || !s.signedIn || s.account != null || s.busy || whoami == null) return
        val app = context.applicationContext
        scope.launch {
            val lines = mutableListOf<String>()
            val exit = run(app, whoami) { synchronized(lines) { lines += LoginText.clean(it) } }
            val account = if (exit == 0) spec.whoami(synchronized(lines) { lines.toList() }) else null
            if (account != null) _state.update { if (it.signedIn) it.copy(account = account) else it }
        }
    }

    fun login(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        cancelled = false
        _state.update { it.copy(auth = AuthAction.LoggingIn, log = emptyList(), failed = false) }
        Termux.startForegroundService(app)
        val code = spec.code
        scope.launch {
            var opened = false
            val exit = run(app, spec.loginArguments(home), keep = { process = it }, stdin = code == CodeEntry.Stdin) { raw ->
                val line = LoginText.clean(raw)
                appendLog(line)
                val url = spec.url.find(line)?.value
                if (url != null && !opened) {
                    opened = true
                    openBrowser(app, url)
                    if (code == CodeEntry.Stdin) _state.update { it.copy(auth = AuthAction.WaitingForCode) }
                }
            }
            process = null
            // Firebase's login prints the address and ends; the code goes to a second command.
            if (code is CodeEntry.Command && exit == 0 && opened && !cancelled) {
                _state.update { it.copy(auth = AuthAction.WaitingForCode) }
            } else {
                finish(app, exit)
            }
        }
    }

    /** Hands the code the browser showed to the CLI. Never logged or kept. */
    fun submitCode(context: Context, code: String) {
        val text = code.trim()
        if (text.isEmpty() || _state.value.auth != AuthAction.WaitingForCode) return
        val app = context.applicationContext
        _state.update { it.copy(auth = AuthAction.SendingCode) }
        when (val entry = spec.code) {
            CodeEntry.Stdin -> scope.launch {
                // The login run is still going and ends once the CLI has the code.
                val p = process ?: return@launch finish(app, -1)
                runCatching { p.outputStream.use { it.write("$text\n".toByteArray()) } }
            }
            is CodeEntry.Command -> scope.launch {
                val exit = run(app, entry.arguments(text)) { appendLog(LoginText.clean(it)) }
                finish(app, exit)
            }
            null -> Unit
        }
    }

    /** Stops a login that is waiting for the browser or the code. */
    fun cancel(context: Context) {
        cancelled = true
        val p = process
        if (p != null) {
            p.destroy()
        } else if (_state.value.auth == AuthAction.WaitingForCode) {
            finish(context.applicationContext, 1)
        }
    }

    fun logout(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        _state.update { it.copy(auth = AuthAction.LoggingOut, log = emptyList(), failed = false) }
        scope.launch {
            val exit = run(app, spec.logoutArguments) { appendLog(LoginText.clean(it)) }
            finish(app, exit)
        }
    }

    /** Forgets the account after the tool is removed (its uninstall signs out too). */
    fun forget() {
        if (!_state.value.busy) _state.update { LoginState(signedIn = spec.signedIn(home)) }
    }

    private fun finish(context: Context, exit: Int) {
        if (cancelled) spec.cancelled(home)
        val signedIn = spec.signedIn(home)
        _state.update { s ->
            s.copy(
                auth = null,
                signedIn = signedIn,
                account = if (signedIn) spec.account(home) ?: s.account else null,
                failed = exit != 0 && !cancelled,
                // The live log was the only place the sign-in address was kept, and the login is over.
                log = s.log.map(LoginText::withoutUrl),
            )
        }
        if (signedIn) refreshAccount(context, installed = true)
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_LINES)) }
    }

    private fun openBrowser(context: Context, url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) appendLog("Could not open a browser. Copy the link above into one.")
    }

    /** Runs the CLI to the end in its own temp folder, deleted afterwards. Blocking. */
    private fun run(
        context: Context,
        arguments: List<String>,
        keep: (Process) -> Unit = {},
        stdin: Boolean = false,
        onLine: (String) -> Unit,
    ): Int {
        val dir = File(runs, System.nanoTime().toString())
        return try {
            dir.mkdirs()
            val environment = mapOf("NO_COLOR" to "1", "FORCE_COLOR" to "0") + spec.environment(dir)
            val p = TermuxCommand.start(context, "$prefix/${spec.executable}", arguments.toTypedArray(), home, environment)
            keep(p)
            if (!stdin) p.outputStream.close()
            TermuxCommand.drain(p, onLine)
        } catch (e: Exception) {
            onLine("[error] ${e.message ?: e}")
            -1
        } finally {
            dir.deleteRecursively()
            runs.delete() // only goes when empty: another run may still be using it
        }
    }

    private companion object {
        const val LOG_LINES = 200
    }
}

/** One [CliLogin] per CLI for the whole app, so the tile, its page and the uninstall all see the same login. */
object CliLogins {
    private val logins = java.util.concurrent.ConcurrentHashMap<String, CliLogin>()

    fun of(spec: LoginSpec): CliLogin = logins.getOrPut(spec.key) { CliLogin(spec) }
}
