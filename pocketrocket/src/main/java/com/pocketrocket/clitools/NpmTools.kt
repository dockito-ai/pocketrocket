package com.pocketrocket.clitools

import android.content.Context
import com.pocketrocket.clilogin.CliLogin
import com.pocketrocket.clilogin.CliLogins
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

data class NpmToolsState(
    val checked: Boolean = false,
    /** What is on the phone now, read from npm's folders; a tool that isn't there has no entry. */
    val present: Map<NpmTool, NpmPresence> = emptyMap(),
    val job: SetupState? = null,
    /** The tool the running (or last) job is for. */
    val tool: NpmTool? = null,
    val removing: Boolean = false,
    val outcome: String? = null,
    val failed: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    fun isFor(t: NpmTool): Boolean = tool == t
    fun installed(t: NpmTool): Boolean = t in present
}

/**
 * Installs and removes the npm deploy CLIs (decision 98) through the setup script, one at a time, and holds each one's
 * login ([login]). [refresh] reads npm's folders and the logins' config files, so the tiles follow what a terminal or
 * a Claude session installs, removes, or signs in to.
 */
object NpmTools {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
    private val _state = MutableStateFlow(NpmToolsState())
    val state: StateFlow<NpmToolsState> = _state.asStateFlow()

    fun login(t: NpmTool): CliLogin = CliLogins.of(t.login)

    fun refresh() {
        scope.launch { read() }
    }

    private fun read() {
        val present = NpmTool.entries.mapNotNull { t -> t.presence(prefix)?.let { t to it } }.toMap()
        _state.update { it.copy(present = present, checked = true) }
        NpmTool.entries.forEach { login(it).refresh() }
    }

    fun install(context: Context, t: NpmTool) = run(context, t, remove = false)

    fun remove(context: Context, t: NpmTool) = run(context, t, remove = true)

    /** Retry: the last job again. */
    fun retry(context: Context) {
        val s = _state.value
        s.tool?.let { run(context, it, s.removing) }
    }

    private fun run(context: Context, t: NpmTool, remove: Boolean) {
        synchronized(this) {
            if (_state.value.running || login(t).state.value.busy) return
            _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), tool = t, removing = remove, outcome = null, failed = false) }
        }
        val app = context.applicationContext
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, if (remove) t.removeOption else t.option) { change ->
                _state.update { s -> s.copy(job = s.job?.let(change)) }
            }
            read()
            if (remove) login(t).forget()
            val now = _state.value.present[t]
            val ok = exit == 0 && sawDone && if (remove) now == null else now != null && now.problem == null
            _state.update { s ->
                s.copy(
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
        }
    }
}
