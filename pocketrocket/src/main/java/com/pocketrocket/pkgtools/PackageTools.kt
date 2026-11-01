package com.pocketrocket.pkgtools

import android.content.Context
import com.pocketrocket.androidbuild.AndroidBuildTool
import com.pocketrocket.clilogin.CliLogins
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
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

data class PackageToolsState(
    val checked: Boolean = false,
    /** The watched packages installed now, from dpkg's status. */
    val packages: Map<String, DpkgStatus.Package> = emptyMap(),
    /** The profile.d files and the default java, read with dpkg's status. */
    val disk: PackageDisk = PackageDisk(),
    val job: SetupState? = null,
    /** The tool the running (or last) job is for. */
    val tool: PackageTool? = null,
    val removing: Boolean = false,
    /** The job is a [PackageTool.repair], not an install. */
    val repairing: Boolean = false,
    val outcome: String? = null,
    val failed: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    fun isFor(t: PackageTool): Boolean = tool == t
    fun installed(t: PackageTool): Boolean = t.installed(packages)
    fun problem(t: PackageTool): String? = t.problem(packages, disk)
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
        _state.update { it.copy(packages = pkgs, disk = disk(), checked = true) }
        PackageTool.entries.forEach { t -> t.login?.let { CliLogins.of(it).refresh() } }
    }

    private val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)

    private fun disk() = PackageDisk(
        profiles = PackageTool.entries.mapNotNull { it.shell?.file }.filter { File(prefix, "etc/profile.d/$it").isFile }.toSet(),
        javaDefault = runCatching { File(prefix, "bin/java").canonicalPath }.getOrNull(),
        gradleJdks = GradleJdks.read(prefix, File(TermuxConstants.TERMUX_HOME_DIR_PATH, AndroidBuildTool.GRADLE_PROPERTIES_RELATIVE)),
    )

    /**
     * What Claude sessions need from the package tools (decision 98): each [ShellSetting] whose program is on the phone
     * now, however it got there. Terminals get the same from the profile.d files. A few file checks.
     */
    fun environment(): Map<String, String> =
        PackageTool.entries.mapNotNull { it.shell }.filter { File(prefix, "bin/${it.binary}").exists() }
            .fold(emptyMap()) { env, s -> env + s.environment }

    fun install(context: Context, t: PackageTool) = run(context, t, remove = false)

    fun remove(context: Context, t: PackageTool) = run(context, t, remove = true)

    /** Repair: its [PackageTool.repair] job when it has one (Java's), else the install again. */
    fun repair(context: Context, t: PackageTool) = run(context, t, remove = false, repair = t.repair != null)

    /** Retry: the last job again. */
    fun retry(context: Context) {
        val s = _state.value
        s.tool?.let { run(context, it, s.removing, s.repairing) }
    }

    private fun run(context: Context, t: PackageTool, remove: Boolean, repair: Boolean = false) {
        synchronized(this) {
            if (_state.value.running || t.login?.let { CliLogins.of(it).state.value.busy } == true) return
            _state.update {
                it.copy(job = SetupState(phase = SetupPhase.Running), tool = t, removing = remove, repairing = repair, outcome = null, failed = false)
            }
        }
        val app = context.applicationContext
        Termux.startForegroundService(app)
        val option = when {
            remove -> t.removeOption
            repair -> t.repair?.option ?: t.option
            else -> t.option
        }
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option) { change ->
                _state.update { s -> s.copy(job = s.job?.let(change)) }
            }
            read()
            if (remove) t.login?.let { CliLogins.of(it).forget() }
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
