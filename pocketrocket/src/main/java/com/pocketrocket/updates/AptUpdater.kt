package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.cpp.CppTool
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
import com.pocketrocket.termux.DpkgStatus
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

/**
 * `apt-cache policy <package>`'s two lines that matter: what is installed and what apt would install. [label] names the
 * package when its row follows more than one ("Kotlin 2.4.21" on the Java row).
 */
data class AptPolicy(val installed: String?, val candidate: String?, val label: String? = null) {
    /** apt only offers a candidate other than the installed one when it is newer (no pinning here). */
    val updateAvailable: Boolean get() = installed != null && candidate != null && candidate != installed

    /** The versions as the row shows them: no epoch, after the package's [label] when there is one. */
    val shownInstalled: String? get() = shown(installed)
    val shownCandidate: String? get() = shown(candidate)
    private fun shown(version: String?): String? = display(version)?.let { v -> label?.let { "$it $v" } ?: v }

    companion object {
        private val FIELD = Regex("""^\s*(Installed|Candidate):\s*(\S+)\s*$""")
        private val HEADER = Regex("""^(\S+):\s*$""")

        fun parse(lines: List<String>): AptPolicy {
            val fields = lines.mapNotNull { FIELD.find(it)?.destructured }.associate { (k, v) -> k to v.takeIf { it != "(none)" } }
            return AptPolicy(fields["Installed"], fields["Candidate"])
        }

        /** `apt-cache policy <packages…>`: each package's block (it starts with "<package>:"), by package. */
        fun parseEach(lines: List<String>): Map<String, AptPolicy> {
            val blocks = linkedMapOf<String, MutableList<String>>()
            var block: MutableList<String>? = null
            lines.forEach { line ->
                val header = HEADER.matchEntire(line)
                if (header != null) block = blocks.getOrPut(header.groupValues[1]) { mutableListOf() } else block?.add(line)
            }
            return blocks.mapValues { parse(it.value) }
        }

        /**
         * One row's view of [packages] (package to name, in the row's order): the first installed one with an update,
         * else the first installed one; null when none is installed. Named ([label]) when the row follows more than one.
         */
        fun follow(lines: List<String>, packages: List<Pair<String, String>>): AptPolicy? {
            val each = parseEach(lines).ifEmpty { mapOf(packages.first().first to parse(lines)) }
            val named = packages.size > 1
            val installed = packages.mapNotNull { (pkg, name) -> each[pkg]?.takeIf { it.installed != null }?.copy(label = name.takeIf { named }) }
            return installed.firstOrNull { it.updateAvailable } ?: installed.firstOrNull()
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

/**
 * Node.js and npm (decision 103): Termux's nodejs-lts, which first-run setup installs and which brings npm with it.
 * Its row goes while nodejs-lts isn't there (a terminal swapped it for `nodejs`), rather than offering someone else's Node.
 */
val NodeUpdater = AptUpdater("nodejs-lts", "Node.js and npm") { dpkgHas("nodejs-lts") }

/** Headless Chrome's Chromium (decision 103), from the x11 repository, while it's installed. Its MCP server is pinned. */
val ChromiumUpdater = AptUpdater("chromium", "Headless Chrome") { dpkgHas("chromium") }

/**
 * C and C++ (decision 103): Termux's clang, LLVM, CMake, Ninja, make and the rest ([CppTool.PACKAGES]), one version each,
 * moved by apt like the other package tools. The row names the package it shows ("CMake 4.2.0").
 */
val CppUpdater = AptUpdater("clang", "C and C++", CppTool.PACKAGES) { dpkgHas("clang") }

/** Whether dpkg has [pkg] installed now. One small file read. */
private fun dpkgHas(pkg: String): Boolean = DpkgStatus.read(setOf(pkg)).isNotEmpty()

/**
 * A package tool's packages (Python, Bun, decision 92; the languages, decision 98; [PackageTool.updated]), while it's
 * on the phone however it got there. The script's `--only <package>-check` and `-update` go by its main package's name.
 */
fun packageToolUpdater(t: PackageTool, name: String) = AptUpdater(t.main, name, t.updated) { PackageTools.installedNow(t) }

/**
 * apt's package index, refreshed at most once however many rows ask: one per Check now ([Updates.checkAll]), so the
 * apt rows share one `apt-get update` (`--only apt-refresh`). The first [fresh] refreshes; the others wait for it and
 * get its answer, a failure included.
 */
class AptIndex(refresh: () -> Boolean) {
    private val refreshed by lazy(refresh)

    /** Whether the index is fresh now: refreshes it the first time. Blocking. */
    fun fresh(): Boolean = refreshed

    companion object {
        fun of(context: Context): AptIndex {
            val app = context.applicationContext
            return AptIndex {
                // apt locks its lists: one apt-get update at a time, whoever asks.
                synchronized(APT_LOCK) { runCatching { SetupScript.run(app, listOf("--only", "apt-refresh")) {} }.getOrDefault(-1) == 0 }
            }
        }
    }
}

/**
 * One Termux package, or a few that are one tool ([packages], package to name), on the Updates screen: versions from
 * apt, the update through the setup script's `--only <pkg>-update`, which upgrades them alone with the apt progress
 * phases. Loading reads the package index already on the phone; a check reads it after [AptIndex] has refreshed it
 * (`--only <pkg>-check`). Nothing runs on its own.
 */
class AptUpdater(
    private val pkg: String,
    private val name: String,
    private val packages: List<Pair<String, String>> = listOf(pkg to name),
    private val isInstalled: () -> Boolean = { true },
) {
    /** The steps `--only <pkg>-update` prints. */
    val steps = listOf("Checking the device", "Updating $name")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AptUpdateState())
    val state: StateFlow<AptUpdateState> = _state.asStateFlow()
    private val aptCache = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "apt-cache")

    /** Reads `apt-cache policy <packages>` from the index on the phone; no network. */
    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!isInstalled()) return@launch forget()
            val policy = policy(app) ?: return@launch
            _state.update { if (it.checking || it.running) it else it.copy(policy = policy) }
        }
    }

    /** Has [index] refreshed apt's index (its own when alone), then reads the policy again. Blocking; call from a background thread. */
    fun checkBlocking(context: Context, index: AptIndex = AptIndex.of(context)) {
        if (_state.value.checking || _state.value.running) return
        // Not installed (Rust removed, or never added): no row, and no apt-get update for nothing.
        if (!isInstalled()) return forget()
        val app = context.applicationContext
        _state.update { it.copy(checking = true) }
        val lines = mutableListOf<String>()
        val exit = if (!index.fresh()) -1 else runCatching {
            SetupScript.run(app, listOf("--only", "$pkg-check"), mapOf(APT_FRESH to "1")) { synchronized(lines) { lines += it } }
        }.getOrDefault(-1)
        val policy = AptPolicy.follow(synchronized(lines) { lines.toList() }, packages)
        val ok = exit == 0 && policy != null
        _state.update { it.copy(checking = false, checkFailed = !ok, policy = if (ok) policy else it.policy) }
    }

    /** Upgrades the packages through the setup script, with its steps and live progress. */
    fun update(context: Context) {
        if (_state.value.running) return
        val app = context.applicationContext
        val target = _state.value.policy?.shownCandidate
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
            val outcome = if (ok) Outcome("${named(after?.shownInstalled ?: target)} is installed.", failed = false)
            else Outcome(
                (_state.value.job?.error ?: "Stopped unexpectedly (exit code $exit).") +
                    (after?.shownInstalled?.let { " ${named(it)} stays." } ?: ""),
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

    /** "Git 2.51.0"; a row that follows several packages already names the one it shows ("Kotlin 2.4.21"). */
    private fun named(version: String?) = if (packages.size > 1) version else "$name $version"

    /** Drops what was read while the package was installed, so its row goes. */
    private fun forget() = _state.update { if (it.running) it else it.copy(policy = null, checkFailed = false, outcome = null) }

    private fun policy(context: Context): AptPolicy? = runCatching {
        if (!aptCache.exists()) return null
        val lines = mutableListOf<String>()
        val args = arrayOf("policy") + packages.map { it.first }
        val exit = TermuxCommand.run(context, aptCache.path, args, File(TermuxConstants.TERMUX_HOME_DIR_PATH), { synchronized(lines) { lines += it } })
        AptPolicy.follow(synchronized(lines) { lines.toList() }, packages).takeIf { exit == 0 }
    }.getOrNull()
}

private val APT_LOCK = Any()

/** Tells `--only <pkg>-check` that [AptIndex] has just refreshed apt's index, so it only reads it. */
private const val APT_FRESH = "POCKETROCKET_APT_FRESH"
