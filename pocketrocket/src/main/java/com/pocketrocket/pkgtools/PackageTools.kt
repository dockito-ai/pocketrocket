package com.pocketrocket.pkgtools

import android.content.Context
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.ToolScript
import com.pocketrocket.setup.finished
import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.termux.Termux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PackageToolsState(
    val checked: Boolean = false,
    /** The watched packages installed now, from dpkg's status. */
    val packages: Map<String, DpkgStatus.Package> = emptyMap(),
    val job: SetupState? = null,
    /** The tool the running (or last) job is for. */
    val tool: PackageTool? = null,
    val removing: Boolean = false,
    val outcome: String? = null,
    val failed: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    fun isFor(t: PackageTool): Boolean = tool == t
    fun installed(t: PackageTool): Boolean = t.installed(packages)
}

/**
 * Installs and removes the package tools (decision 92) through the setup script, one job at a time (apt allows one).
 * [refresh] reads dpkg's status, so the tiles follow what a terminal or a Claude session installs or removes.
 */
object PackageTools {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PackageToolsState())
    val state: StateFlow<PackageToolsState> = _state.asStateFlow()

    /** Reads dpkg's status now. Blocking (one file read); for the Updates rows' "is it installed". */
    fun installedNow(t: PackageTool): Boolean = t.installed(DpkgStatus.read(t.watched))

    /** Reads dpkg's status in the background. */
    fun refresh() {
        scope.launch { read() }
    }

    private fun read() {
        val pkgs = DpkgStatus.read(PackageTool.allWatched)
        _state.update { it.copy(packages = pkgs, checked = true) }
    }

    fun install(context: Context, t: PackageTool) = run(context, t, remove = false)

    fun remove(context: Context, t: PackageTool) = run(context, t, remove = true)

    /** Retry: the last job again. */
    fun retry(context: Context) {
        val s = _state.value
        s.tool?.let { run(context, it, s.removing) }
    }

    private fun run(context: Context, t: PackageTool, remove: Boolean) {
        synchronized(this) {
            if (_state.value.running) return
            _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), tool = t, removing = remove, outcome = null, failed = false) }
        }
        val app = context.applicationContext
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, if (remove) t.removeOption else t.option) { change ->
                _state.update { s -> s.copy(job = s.job?.let(change)) }
            }
            read()
            // An uninstall may leave what other packages need (Python for Headless Chrome), so only the script's word counts.
            val ok = exit == 0 && sawDone && (remove || _state.value.installed(t))
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
