package com.pocketrocket.setup

import android.content.Context
import com.pocketrocket.termux.Termux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An optional tool's state: what is on the phone, and its running or last install or removal. */
data class ScriptToolState(
    /** Some of the tool is on the phone ([ToolPresence.present]). */
    val installed: Boolean = false,
    /** What keeps it from working, when something does ("Gradle missing"); Repair runs the install again. */
    val problem: String? = null,
    val checked: Boolean = false,
    val job: SetupState? = null,
    val outcome: String? = null,
    val failed: Boolean = false,
    /** The last run was a removal, not an install. */
    val removing: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
}

/**
 * An optional tool installed by the setup script's `--only <option>` and removed by `--only <option>-remove`
 * (Android, Android NDK, C and C++). [presence] reads what is on the phone now, whoever put it there (decision 93);
 * nothing saved at install counts. A removal that may leave packages other packages need ([removalMayKeep]) is judged
 * by the script's word alone. [afterRun] runs once a job ends, for tools whose job changes another's.
 */
class ScriptTool(
    private val option: String,
    private val presence: () -> ToolPresence,
    private val removalMayKeep: Boolean = false,
    private val afterRun: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ScriptToolState())
    val state: StateFlow<ScriptToolState> = _state.asStateFlow()

    /** Reads [presence] off the main thread. */
    fun refresh() {
        scope.launch { read() }
    }

    private fun read(): ToolPresence {
        val p = presence()
        _state.update { it.copy(installed = p.present, problem = p.problem, checked = true) }
        return p
    }

    fun install(context: Context, environment: Map<String, String> = emptyMap()) {
        run(context, remove = false, environment)
    }

    fun remove(context: Context) {
        run(context, remove = true, emptyMap())
    }

    private fun run(context: Context, remove: Boolean, environment: Map<String, String>) {
        synchronized(this) {
            if (_state.value.running) return
            _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null, failed = false, removing = remove) }
        }
        val app = context.applicationContext
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, if (remove) "$option-remove" else option, environment) { change ->
                _state.update { s -> s.copy(job = s.job?.let(change)) }
            }
            val onDisk = read()
            val ok = exit == 0 && sawDone && if (remove) removalMayKeep || !onDisk.present else onDisk.complete
            _state.update { s ->
                s.copy(
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
            afterRun()
        }
    }
}
