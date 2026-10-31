package com.pocketrocket.browser

import android.content.Context
import com.pocketrocket.auth.ClaudeCli
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
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
 * The optional headless Chromium tool (decision 37). Settings → Tools and the end of first-run setup
 * install and remove it through the setup script. Once it is installed, every Claude account gets the
 * Chrome DevTools MCP server.
 */
object Chromium {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
    private val browserBin = File(binDir, "chromium-browser")
    private val mcpBin = File(binDir, "chrome-devtools-mcp")

    private val _state = MutableStateFlow(ChromiumState())
    val state: StateFlow<ChromiumState> = _state.asStateFlow()

    /** True when both the browser and the MCP server from the last install are on disk. */
    fun installed(): Boolean = browserBin.isFile && mcpBin.isFile

    fun refresh() {
        _state.update { it.copy(installed = installed(), checked = true) }
    }

    /** A Claude account was just added. Give it the server when the tool is already installed. */
    fun onClaudeAccountAdded(context: Context, account: ClaudeAccountEntry) {
        if (!installed()) return
        val app = context.applicationContext
        scope.launch { wireAccount(app, account) }
    }

    fun install(context: Context) = run(context, remove = false)

    fun remove(context: Context) = run(context, remove = true)

    private fun run(context: Context, remove: Boolean) {
        if (_state.value.running) return
        val app = context.applicationContext
        val option = if (remove) "chromium-remove" else "chromium"
        _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null, failed = false, removing = remove, claudeChanged = false) }
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option) { change -> _state.update { s -> s.copy(job = s.job?.let(change)) } }
            val onDisk = installed()
            val packagesOk = exit == 0 && sawDone && (if (remove) !onDisk else onDisk)
            val wireError = if (packagesOk && !remove) wireAll(app) else null
            if (packagesOk && remove) unwireAll(app)
            val ok = packagesOk && wireError == null
            val outcome = when {
                ok && remove -> "Chromium is removed. Claude no longer has the browser."
                ok -> "Chromium is installed. Each Claude session gets its own headless browser."
                wireError != null -> "Chromium is installed, but it was not wired into Claude. $wireError"
                else -> _state.value.job?.error ?: "Stopped unexpectedly (exit code $exit)."
            }
            _state.update { s ->
                s.copy(
                    installed = onDisk,
                    checked = true,
                    job = s.job?.finished(packagesOk),
                    outcome = outcome,
                    failed = !ok,
                    // Accounts are only wired or unwired once the packages step succeeded.
                    claudeChanged = packagesOk,
                )
            }
        }
    }

    /** Adds the server for every Claude account. Returns an error, or null when each one was saved. */
    private fun wireAll(context: Context): String? {
        val problems = ClaudeAccounts.current.accounts.mapNotNull { account ->
            wireAccount(context, account)?.let { "${account.name}: $it" }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun wireAccount(context: Context, account: ClaudeAccountEntry): String? {
        ClaudeCli.run(context, ChromiumMcp.removeArguments(), account.configDir)
        val (code, lines) = ClaudeCli.run(context, ChromiumMcp.addArguments(binDir), account.configDir)
        if (code == 0) return null
        return lines.lastOrNull()?.take(200) ?: "claude mcp add exited $code"
    }

    private fun unwireAll(context: Context) {
        ClaudeAccounts.current.accounts.forEach { account ->
            ClaudeCli.run(context, ChromiumMcp.removeArguments(), account.configDir)
        }
    }
}

data class ChromiumState(
    val installed: Boolean = false,
    val checked: Boolean = false,
    val job: SetupState? = null,
    val outcome: String? = null,
    val failed: Boolean = false,
    /** The last run was a removal, not an install. */
    val removing: Boolean = false,
    /** The last run got as far as changing the Claude accounts' MCP servers. */
    val claudeChanged: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
}
