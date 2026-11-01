package com.pocketrocket.androidbuild

import android.content.Context
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.setup.ToolScript
import com.pocketrocket.setup.finished
import com.pocketrocket.termux.DpkgStatus
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

    /** Under home: the Gradle init script that keeps the plugin on the shim (setup script's ANDROID_NDK_INIT_SCRIPT). */
    const val INIT_SCRIPT_RELATIVE = ".gradle/init.d/pocketrocket-android-ndk.gradle"
    const val TOOLCHAIN_RELATIVE = "build/cmake/android.toolchain.cmake"

    /** What the shim points at (the setup script's ANDROID_NDK_PACKAGES), as the tile names them. */
    val PACKAGES = listOf("clang" to "clang", "cmake" to "CMake", "ninja" to "Ninja", "ndk-sysroot" to "NDK sysroot")

    /** The version folders the setup script made, the one place the list lives. Newest first. */
    fun servedVersions(folders: List<String>): List<String> =
        folders.filter { it.firstOrNull()?.isDigit() == true }.sortedByDescending { it.substringBefore('.').toIntOrNull() ?: 0 }

    /**
     * The tool as it is now, whoever set it up. It is on the phone while the shim folder is ([prefix] holds it). It
     * works when clang, CMake, Ninja and the sysroot are installed (dpkg), the shim has its toolchain file, at least
     * one version folder claims a revision, and Gradle has the init script ([home] holds it).
     */
    fun presence(prefix: File, home: File, pkgs: Map<String, DpkgStatus.Package>): ToolPresence {
        val shim = File(prefix, SHIM_RELATIVE)
        if (!shim.isDirectory) return ToolPresence.Absent
        val versions = File(prefix, VERSIONS_RELATIVE).listFiles()?.filter { File(it, "source.properties").isFile }?.map { it.name }.orEmpty()
        val folders = File(shim, TOOLCHAIN_RELATIVE).isFile && servedVersions(versions).isNotEmpty()
        return ToolPresence.of(
            true,
            ToolPresence.missingPackages(pkgs, PACKAGES) + listOfNotNull(
                "NDK folders".takeUnless { folders },
                "Gradle setup".takeUnless { File(home, INIT_SCRIPT_RELATIVE).isFile },
            ),
        )
    }
}

/** The optional Android NDK tool (decision 50), on top of the Android tool. Settings → Tools installs and removes it through the setup script. */
object AndroidNdk {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val _state = MutableStateFlow(AndroidBuildState())
    val state: StateFlow<AndroidBuildState> = _state.asStateFlow()

    /** What is on the phone now (the shim, dpkg, Gradle's init script), whoever put it there. Blocking: small reads. */
    fun presence(): ToolPresence = AndroidNdkTool.presence(
        File(prefix), File(TermuxConstants.TERMUX_HOME_DIR_PATH), DpkgStatus.read(AndroidNdkTool.PACKAGES.map { it.first }.toSet()),
    )

    /** Reads [presence] off the main thread. */
    fun refresh() {
        scope.launch {
            val p = presence()
            _state.update { it.copy(installed = p.present, problem = p.problem, checked = true) }
        }
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
            val onDisk = presence()
            val ok = exit == 0 && sawDone && if (remove) !onDisk.present else onDisk.complete
            _state.update { s ->
                s.copy(
                    installed = onDisk.present,
                    problem = onDisk.problem,
                    checked = true,
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
        }
    }
}
