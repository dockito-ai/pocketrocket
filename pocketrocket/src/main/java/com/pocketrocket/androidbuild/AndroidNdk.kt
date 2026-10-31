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

/** The fixed facts of the Android NDK tool (decision 50). Sizes are measured on the emulator, docs/android-ndk-spike.md. */
object AndroidNdkTool {
    const val ID = "android-ndk"
    const val SHIM_RELATIVE = "${AndroidBuildTool.SDK_RELATIVE}/ndk-shim"
    const val VERSIONS_RELATIVE = "${AndroidBuildTool.SDK_RELATIVE}/ndk"

    const val DOWNLOAD_MB = 96
    const val INSTALLED_MB = 607

    val INSTALL_STEPS = listOf("Checking the device", "Installing clang, CMake and Ninja", "Setting up the NDK folders")
    val REMOVE_STEPS = listOf("Checking the device", "Removing clang, CMake and Ninja")

    /** The version folders the setup script made, the one place the list lives. Newest first. */
    fun servedVersions(folders: List<String>): List<String> =
        folders.filter { it.firstOrNull()?.isDigit() == true }.sortedByDescending { it.substringBefore('.').toIntOrNull() ?: 0 }
}

/** The optional Android NDK tool (decision 50), on top of the Android tool. Settings → Tools installs and removes it through the setup script. */
object AndroidNdk {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val marker = File("$prefix/${AndroidNdkTool.SHIM_RELATIVE}", AndroidBuildTool.INSTALLED_MARKER)

    private val _state = MutableStateFlow(AndroidBuildState())
    val state: StateFlow<AndroidBuildState> = _state.asStateFlow()

    fun installed(): Boolean = marker.isFile

    fun refresh() {
        _state.update { it.copy(installed = installed(), checked = true) }
    }

    fun servedVersions(): List<String> =
        AndroidNdkTool.servedVersions(File("$prefix/${AndroidNdkTool.VERSIONS_RELATIVE}").list()?.toList().orEmpty())

    fun install(context: Context) = run(context, remove = false)

    fun remove(context: Context) = run(context, remove = true)

    private fun run(context: Context, remove: Boolean) {
        if (_state.value.running) return
        val app = context.applicationContext
        val option = if (remove) "${AndroidNdkTool.ID}-remove" else AndroidNdkTool.ID
        _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null, failed = false, removing = remove) }
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option, emptyMap()) { change -> _state.update { s -> s.copy(job = s.job?.let(change)) } }
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
