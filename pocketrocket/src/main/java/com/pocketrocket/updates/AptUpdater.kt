package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.pkgtools.PackageTools
import com.pocketrocket.setup.SetupLine
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupScript
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.setup.finish
import com.pocketrocket.setup.on
import com.pocketrocket.termux.Termux
import com.pocketrocket.termux.TermuxCommand
import com.pocketrocket.updater.Outcome
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

/** `apt-cache policy <package>`'s two lines that matter: what is installed and what apt would install. */
data class AptPolicy(val installed: String?, val candidate: String?) {
    /** apt only offers a candidate other than the installed one when it is newer (no pinning here). */
    val updateAvailable: Boolean get() = installed != null && candidate != null && candidate != installed

    companion object {
        private val FIELD = Regex("""^\s*(Installed|Candidate):\s*(\S+)\s*$""")

        fun parse(lines: List<String>): AptPolicy {
            val fields = lines.mapNotNull { FIELD.find(it)?.destructured }.associate { (k, v) -> k to v.takeIf { it != "(none)" } }
            return AptPolicy(fields["Installed"], fields["Candidate"])
        }

        /** A dpkg version as shown: no epoch ("1:2.51.0" reads "2.51.0"). */
        fun display(version: String?): String? = version?.substringAfter(':')
    }
}

data class AptUpdateState(
    val policy: AptPolicy? = null,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
    val job: SetupState? = null,
    val outcome: Outcome? = null,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
}

/** Git on the Updates screen (decision 41). */
val GitUpdater = AptUpdater("git", "Git")

/** Rust on the Updates screen, while `rust` is on the phone however it got there (decisions 75, 87 and 92). */
val RustUpdater = AptUpdater("rust", "Rust") { FlutterRust.rustInstalled() }

/** Python and Bun, while they're on the phone however they got there (decision 92). */
val PythonUpdater = AptUpdater("python", "Python") { PackageTools.installedNow(PackageTool.Python) }
val BunUpdater = AptUpdater("bun", "Bun") { PackageTools.installedNow(PackageTool.Bun) }

/**
 * One Termux package on the Updates screen: versions from apt, the update through the setup script's
 * `--only <package>-update`, which upgrades it alone with the apt progress phases. Loading reads the package
 * index already on the phone; a check refreshes it first (`--only <package>-check`). Nothing runs on its own.
 */
class AptUpdater(private val pkg: String, private val name: String, private val isInstalled: () -> Boolean = { true }) {
    /** The steps `--only <package>-update` prints. */
    val steps = listOf("Checking the device", "Updating $name")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AptUpdateState())
    val state: StateFlow<AptUpdateState> = _state.asStateFlow()
    private val aptCache = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "apt-cache")

    /** Reads `apt-cache policy <package>` from the index on the phone; no network. */
    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!isInstalled()) return@launch forget()
            val policy = policy(app) ?: return@launch
            _state.update { if (it.checking || it.running) it else it.copy(policy = policy) }
        }
    }

    /** Refreshes apt's index, then reads the policy again. Blocking; call from a background thread. */
    fun checkBlocking(context: Context) {
        if (_state.value.checking || _state.value.running) return
        // Not installed (Rust removed, or never added): no row, and no apt-get update for nothing.
        if (!isInstalled()) return forget()
        val app = context.applicationContext
        _state.update { it.copy(checking = true) }
        val lines = mutableListOf<String>()
        // One apt-get update at a time: Check now asks every package at once, and apt locks its lists.
        val exit = synchronized(APT_LOCK) {
            runCatching { SetupScript.run(app, listOf("--only", "$pkg-check")) { synchronized(lines) { lines += it } } }.getOrDefault(-1)
        }
        val policy = AptPolicy.parse(synchronized(lines) { lines.toList() })
        val ok = exit == 0 && policy.installed != null
        _state.update { it.copy(checking = false, checkFailed = !ok, policy = if (ok) policy else it.policy) }
    }

    /** Upgrades the package through the setup script, with its steps and live progress. */
    fun update(context: Context) {
        if (_state.value.running) return
        val app = context.applicationContext
        val target = _state.value.policy?.candidate
        _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), outcome = null) }
        Termux.startForegroundService(app)
        scope.launch {
            var sawDone = false
            val exit = runCatching {
                SetupScript.run(app, listOf("--only", "$pkg-update")) { raw ->
                    val line = SetupLine.parse(raw)
                    if (line == SetupLine.Done) sawDone = true
                    synchronized(this@AptUpdater) { _state.update { s -> s.copy(job = s.job?.on(line)) } }
                }
            }.getOrElse { e ->
                _state.update { s -> s.copy(job = s.job?.on(SetupLine.Error(e.message ?: e.toString()))) }
                -1
            }
            val after = policy(app)
            val ok = exit == 0 && sawDone
            val outcome = if (ok) Outcome("$name ${AptPolicy.display(after?.installed ?: target)} is installed.", failed = false)
            else Outcome(
                (_state.value.job?.error ?: "Stopped unexpectedly (exit code $exit).") +
                    (after?.installed?.let { " $name ${AptPolicy.display(it)} stays." } ?: ""),
                failed = true,
            )
            _state.update { s ->
                s.copy(
                    policy = after ?: s.policy,
                    job = s.job?.finish(if (ok) StepStatus.Done else StepStatus.Failed)
                        ?.copy(phase = if (ok) SetupPhase.Done else SetupPhase.Failed, progress = null, detail = null),
                    outcome = outcome,
                )
            }
        }
    }

    /** Drops what was read while the package was installed, so its row goes. */
    private fun forget() = _state.update { if (it.running) it else it.copy(policy = null, checkFailed = false, outcome = null) }

    private fun policy(context: Context): AptPolicy? = runCatching {
        if (!aptCache.exists()) return null
        val lines = mutableListOf<String>()
        val exit = TermuxCommand.run(context, aptCache.path, arrayOf("policy", pkg), File(TermuxConstants.TERMUX_HOME_DIR_PATH), { synchronized(lines) { lines += it } })
        AptPolicy.parse(synchronized(lines) { lines.toList() }).takeIf { exit == 0 && it.installed != null }
    }.getOrNull()
}

private val APT_LOCK = Any()
