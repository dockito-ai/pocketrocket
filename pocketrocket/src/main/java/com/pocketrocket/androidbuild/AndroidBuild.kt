package com.pocketrocket.androidbuild

import android.content.Context
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
 * The optional Android build tool (decision 45). Settings → Tools installs and removes it through the setup script;
 * nothing is bundled and first-run setup never touches it.
 */
object AndroidBuild {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val marker = File("$prefix/${AndroidBuildTool.SDK_RELATIVE}", AndroidBuildTool.INSTALLED_MARKER)

    private val _state = MutableStateFlow(AndroidBuildState())
    val state: StateFlow<AndroidBuildState> = _state.asStateFlow()

    fun installed(): Boolean = marker.isFile

    fun refresh() {
        _state.update { it.copy(installed = installed(), checked = true) }
    }

    /** For a new session's process; its Bash tool inherits it. Empty until the tool is installed. */
    fun environment(): Map<String, String> = if (installed()) AndroidBuildTool.environment(prefix) else emptyMap()

    /** Only called after the user accepted the SDK license on the tool page. */
    fun install(context: Context) = run(context, remove = false)

    fun remove(context: Context) = run(context, remove = true)

    private fun run(context: Context, remove: Boolean) {
        if (_state.value.running) return
        val app = context.applicationContext
        val option = if (remove) "android-build-remove" else "android-build"
        val environment = if (remove) emptyMap() else mapOf(SdkLicense.HASH_ENV to SdkLicense.ACCEPTED_HASH)
        _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null, failed = false, removing = remove) }
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option, environment) { change -> _state.update { s -> s.copy(job = s.job?.let(change)) } }
            val onDisk = installed()
            val ok = exit == 0 && sawDone && onDisk != remove
            _state.update { s ->
                s.copy(
                    installed = onDisk,
                    checked = true,
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
        }
    }
}

data class AndroidBuildState(
    val installed: Boolean = false,
    val checked: Boolean = false,
    val job: SetupState? = null,
    val outcome: String? = null,
    val failed: Boolean = false,
    /** The last run was a removal, not an install. */
    val removing: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
}
