package com.pocketrocket.auth

import android.content.Context
import android.net.Uri
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Where a sign-in stands. */
sealed interface LoginState {
    data object Idle : LoginState
    data object Starting : LoginState

    /**
     * Claude Code is waiting. [browserUrl] signs in through Claude Code's localhost callback (no copying);
     * [manualUrl] is the same sign-in ending on a page that shows a code to paste. [state] is the OAuth state,
     * which the pasted code ends with, so the clipboard can be matched to this attempt.
     */
    data class Waiting(val attempt: Int, val browserUrl: String?, val manualUrl: String?, val state: String?) : LoginState
    data object Verifying : LoginState
    data class SignedIn(val account: ClaudeAccount) : LoginState
    data class Failed(val message: String) : LoginState
}

/**
 * One Claude sign-in at a time, driving Claude Code's own `claude auth login --claudeai`.
 *
 * Claude Code opens its sign-in URL through $BROWSER; we point BROWSER at a tiny script that hands the URL
 * back to the app, which opens it in a Custom Tab. That URL redirects to Claude Code's localhost callback
 * server, so when the user finishes in the browser Claude Code saves the credentials itself and exits 0.
 * The fallback is the manual URL Claude Code also prints: it ends on a page showing a code, which we write
 * to Claude Code's stdin ("Paste code here if prompted"). Codes and tokens are never logged or kept.
 */
object ClaudeLogin {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private const val OPENER = "pocketrocket-open-url"
    private val openerFile = File(ClaudeCli.home, ".pocketrocket/bin/$OPENER")

    private var process: Process? = null
    private var attempt = 0

    /** Starts a new sign-in for [configDir] (null is Claude Code's default), ending any running one. */
    @Synchronized
    fun start(context: Context, configDir: File? = null) {
        stopProcess()
        val app = context.applicationContext
        val id = ++attempt
        _state.value = LoginState.Starting
        thread(name = "pocketrocket-login") { runLogin(app, configDir, id) }
    }

    /** Sends a code from the "paste the code" page to Claude Code. */
    @Synchronized
    fun submitCode(code: String) {
        val p = process ?: return
        if (_state.value !is LoginState.Waiting) return
        _state.value = LoginState.Verifying
        thread(name = "pocketrocket-login-code") {
            runCatching { p.outputStream.apply { write((code.trim() + "\n").toByteArray()); flush() } }
        }
    }

    /** Stops the sign-in and goes back to Idle. */
    @Synchronized
    fun cancel() {
        attempt++
        stopProcess()
        _state.value = LoginState.Idle
    }

    /** Clears a finished state (signed in or failed) once the screen has moved on. */
    @Synchronized
    fun reset() {
        if (process == null) _state.value = LoginState.Idle
    }

    /** True when [text] looks like the code Claude's callback page shows for this attempt. */
    fun looksLikeCode(text: String?, waiting: LoginState.Waiting): Boolean {
        val t = text?.trim() ?: return false
        if (!CODE.matches(t)) return false
        return waiting.state == null || t.substringAfter('#') == waiting.state
    }

    private val CODE = Regex("^[A-Za-z0-9_\\-.~]{16,}#[A-Za-z0-9_\\-.~]{16,}$")
    private val VISIT = Regex("(https://\\S+)")

    private fun runLogin(context: Context, configDir: File?, id: Int) {
        val urlFile = File(context.cacheDir, "claude-login-$id.url").apply { delete() }
        val p = try {
            writeOpener()
            ClaudeCli.start(
                context, arrayOf("auth", "login", "--claudeai"), configDir,
                mapOf("BROWSER" to openerFile.path, "POCKETROCKET_URL_FILE" to urlFile.path),
            )
        } catch (e: Exception) {
            finish(id, LoginState.Failed("Could not start Claude Code. Is setup finished?"))
            return
        }
        synchronized(this) {
            if (id != attempt) { p.destroy(); return }
            process = p
        }

        val manualUrl = AtomicReference<String?>(null)
        val exited = AtomicBoolean(false)
        var failure: String? = null
        val waiter = thread(name = "pocketrocket-login-url") {
            // The localhost URL arrives through the opener script; the manual one is printed on stdout.
            val deadline = System.currentTimeMillis() + 20_000
            var browserUrl: String? = null
            while (System.currentTimeMillis() < deadline && id == attempt && !exited.get()) {
                browserUrl = urlFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.startsWith("https://") }
                if (browserUrl != null && manualUrl.get() != null) break
                if (browserUrl == null && manualUrl.get() != null && System.currentTimeMillis() > deadline - 15_000) break
                Thread.sleep(100)
            }
            urlFile.delete()
            val any = browserUrl ?: manualUrl.get()
            synchronized(this) {
                if (id != attempt || exited.get() || _state.value != LoginState.Starting) return@thread
                _state.value = if (any == null) LoginState.Failed("Claude Code did not start the sign-in. Try again.")
                else LoginState.Waiting(id, browserUrl, manualUrl.get(), Uri.parse(any).getQueryParameter("state"))
            }
        }

        val exit = TermuxCommand.drain(p) { line ->
            val clean = line.replace(ANSI, "").trim()
            if (clean.contains("visit:")) VISIT.find(clean)?.let { manualUrl.set(it.value) }
            if (clean.startsWith("Login failed:")) failure = clean.removePrefix("Login failed:").trim()
            else if (clean.contains("Login failed:")) failure = clean.substringAfter("Login failed:").trim()
        }
        exited.set(true)
        waiter.join()
        synchronized(this) { if (process === p) process = null }
        if (id != attempt) return

        if (exit == 0) {
            _state.value = LoginState.Verifying
            val account = ClaudeCli.status(context, configDir)
            finish(id, if (account != null) LoginState.SignedIn(account) else LoginState.Failed("Signed in, but Claude Code does not report an account. Try again."))
        } else {
            finish(id, LoginState.Failed(failure ?: "Sign-in did not finish. Try again."))
        }
    }

    @Synchronized
    private fun finish(id: Int, state: LoginState) {
        if (id == attempt) _state.value = state
    }

    @Synchronized
    private fun stopProcess() {
        process?.let { p -> runCatching { p.outputStream.close() }; p.destroy() }
        process = null
    }

    /** The $BROWSER Claude Code calls: writes the URL where the app is waiting for it. Opens nothing. */
    private fun writeOpener() {
        val script = "#!${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/sh\n" +
            "[ -n \"\$POCKETROCKET_URL_FILE\" ] && printf '%s\\n' \"\$1\" > \"\$POCKETROCKET_URL_FILE\"\n"
        openerFile.parentFile?.mkdirs()
        if (!openerFile.exists() || openerFile.readText() != script) openerFile.writeText(script)
        openerFile.setExecutable(true, true)
    }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
}
