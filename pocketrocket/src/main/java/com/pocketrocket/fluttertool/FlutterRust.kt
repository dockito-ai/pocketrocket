package com.pocketrocket.fluttertool

import android.content.Context
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.setup.ToolScript
import com.pocketrocket.setup.finished
import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.termux.Termux
import com.pocketrocket.usage.AndroidWalkFs
import com.pocketrocket.usage.walkDisk
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import java.net.URL

/** Which tool a job belongs to: Flutter & Dart or Rust (decision 87). They share one job runner. */
enum class FlutterPart { Flutter, Rust }

/**
 * What the running (or last) job is doing. [whole] jobs install or remove their tool and show on its page as the
 * tool's own job; the others (a version added, the default set, a version removed) run inside the Flutter page's cards.
 */
enum class FlutterAction(val removes: Boolean, val part: FlutterPart, val whole: Boolean = false) {
    InstallFlutter(false, FlutterPart.Flutter, whole = true),
    AddVersion(false, FlutterPart.Flutter),
    SetDefault(false, FlutterPart.Flutter),
    RemoveVersion(true, FlutterPart.Flutter),
    /** Release builds, the opt-in piece on Flutter's page (decision 106). */
    AddReleaseBuilds(false, FlutterPart.Flutter),
    RemoveReleaseBuilds(true, FlutterPart.Flutter),
    UninstallFlutter(true, FlutterPart.Flutter, whole = true),
    InstallRust(false, FlutterPart.Rust, whole = true),
    RemoveRust(true, FlutterPart.Rust, whole = true),
}

/** One installed Flutter version: its folder name, whether it's the default, and its size once measured. */
data class FlutterVersion(val version: String, val isDefault: Boolean, val bytes: Long? = null)

data class FlutterRustState(
    /** Some of Flutter is on the phone ([FlutterTool.presence]), whoever put it there. */
    val flutterInstalled: Boolean = false,
    /** What keeps Flutter from working ("Dart SDK missing"); Repair runs the install again. */
    val flutterProblem: String? = null,
    /** Release builds ([FlutterTool.releaseBuilds]), read from disk like the rest. */
    val releaseBuilds: ReleaseBuilds = ReleaseBuilds.Off,
    /** Rust's packages installed now (`rust`, `rust-std-*`), from dpkg's status, whoever installed them. */
    val rustPackages: Map<String, DpkgStatus.Package> = emptyMap(),
    val checked: Boolean = false,
    val versions: List<FlutterVersion> = emptyList(),
    /** Recent stable releases for Add version, newest first; empty until read. */
    val releases: List<FlutterRelease> = emptyList(),
    val latestStable: String? = null,
    val releasesFailed: Boolean = false,
    val job: SetupState? = null,
    val action: FlutterAction? = null,
    /** The version the job is about (Add, Set default, Remove). */
    val target: String? = null,
    /** The steps the job prints, listed before they start. */
    val steps: List<String> = emptyList(),
    val outcome: String? = null,
    val failed: Boolean = false,
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    val rustInstalled: Boolean get() = FlutterTool.rustInstalled(rustPackages)
    val removing: Boolean get() = action?.removes == true
    /** The running or last job belongs to [part]. */
    fun isFor(part: FlutterPart): Boolean = action?.part == part
    val defaultVersion: String? get() = versions.firstOrNull { it.isDefault }?.version
    /** Every version measured: Flutter's part of the tool's size. */
    val flutterBytes: Long? get() = if (versions.isNotEmpty() && versions.all { it.bytes != null }) versions.sumOf { it.bytes ?: 0 } else null
}

/**
 * The optional Flutter & Dart and Rust tools (decisions 75 and 87): Settings → Tools installs, adds and removes Flutter
 * versions and sets the default, and separately installs or removes Rust, each through the setup script. One job runs
 * at a time across both. Nothing is bundled; first-run setup never
 * touches it. Sessions need nothing extra: `flutter`, `dart`, `fvm` and `cargo` are in $PREFIX/bin, and the launcher
 * finds the version itself.
 */
object FlutterRust {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val fvm = File(home, FlutterTool.FVM_RELATIVE)
    private val versionsDir = File(fvm, "versions")
    private val defaultLink = File(fvm, "default")
    private val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
    private val measuring = AtomicBoolean(false)
    private val measureAgain = AtomicBoolean(false)

    private val _state = MutableStateFlow(FlutterRustState())
    val state: StateFlow<FlutterRustState> = _state.asStateFlow()

    /** Flutter's versions, FVM and the launcher as they are now, whoever put them there ([FlutterTool.presence]). */
    fun flutterPresence(): ToolPresence = FlutterTool.presence(prefix, home)

    /** Some of Flutter is on the phone, working or not: Uninstall is offered. */
    fun flutterInstalled(): Boolean = flutterPresence().present

    /** Rust's packages from dpkg's status (one small file read). */
    private fun rustPackages(): Map<String, DpkgStatus.Package> = DpkgStatus.read(FlutterTool::isRustPackage)

    /** `rust` is on the phone now, however it got there. Blocking; for the Updates row. */
    fun rustInstalled(): Boolean = FlutterTool.rustInstalled(rustPackages())

    /** The default version's folder name, from FVM's link, or null. */
    fun defaultVersion(): String? = runCatching {
        defaultLink.takeIf { File(it, "bin/flutter").isFile }?.canonicalFile?.name
    }.getOrNull()

    /** Files only: what is installed (Rust from dpkg), versions and the default. Sizes already measured are kept. */
    fun refresh() {
        val default = defaultVersion()
        val rust = rustPackages()
        val flutter = flutterPresence()
        val release = FlutterTool.releaseBuilds(prefix, home)
        val names = FlutterTool.installedVersions(
            versionsDir.listFiles()?.filter { File(it, "bin/flutter").isFile }?.map { it.name }.orEmpty(),
        )
        _state.update { s ->
            val known = s.versions.associateBy { it.version }
            s.copy(
                flutterInstalled = flutter.present,
                flutterProblem = flutter.problem,
                releaseBuilds = release,
                rustPackages = rust,
                checked = true,
                versions = names.map { FlutterVersion(it, it == default, known[it]?.bytes) },
            )
        }
    }

    /**
     * Measures each version's folder. Blocking; a large version takes a few seconds. One walk at a time: a call while
     * one runs (the page opening as a job ends) leaves it to that walk, which then goes round once more.
     */
    fun measure() {
        measureAgain.set(true)
        while (measureAgain.get() && measuring.compareAndSet(false, true)) {
            try {
                while (measureAgain.getAndSet(false)) measureVersions()
            } finally {
                measuring.set(false)
            }
        }
    }

    private fun measureVersions() {
        refresh()
        _state.value.versions.filter { it.bytes == null }.forEach { v ->
            val bytes = runCatching { walkDisk(AndroidWalkFs, File(versionsDir, v.version).path, Unit, emptyMap()).bytes[Unit] ?: 0L }.getOrNull()
            _state.update { s -> s.copy(versions = s.versions.map { if (it.version == v.version) it.copy(bytes = bytes) else it }) }
        }
    }

    /** Reads Flutter's release list for Add version and Updates. Blocking. */
    fun loadReleases(): Boolean {
        val json = runCatching { fetch(FlutterTool.RELEASES_URL) }.getOrNull()
        val releases = json?.let(FlutterTool::stableReleases).orEmpty()
        val latest = json?.let(FlutterTool::latestStable)
        _state.update { it.copy(releases = releases.ifEmpty { it.releases }, latestStable = latest ?: it.latestStable, releasesFailed = releases.isEmpty()) }
        return releases.isNotEmpty()
    }

    /** [loadReleases] in the background. */
    fun loadReleasesLater() {
        scope.launch { loadReleases() }
    }

    /** Install Flutter & Dart: the newest stable Flutter (the default), its Dart SDK and FVM. */
    fun installFlutter(context: Context) =
        run(context, "flutter", FlutterAction.InstallFlutter, null, FlutterTool.flutterSteps(_state.value.latestStable))

    /** Adds a Flutter version. */
    fun addVersion(context: Context, version: String) =
        run(context, "flutter", FlutterAction.AddVersion, version, FlutterTool.flutterSteps(version), mapOf(FlutterTool.VERSION_ENV to version))

    /**
     * Updates' way: adds [version], makes it the default and removes the old default [replaces] once the new one works.
     * Other versions stay; an older one comes back with `fvm install <version>`.
     */
    fun updateDefault(context: Context, version: String, replaces: String) =
        run(
            context, "flutter", FlutterAction.AddVersion, version, FlutterTool.flutterSteps(version),
            mapOf(FlutterTool.VERSION_ENV to version, FlutterTool.DEFAULT_ENV to "1", FlutterTool.REPLACE_ENV to replaces),
        )

    fun setDefault(context: Context, version: String) =
        run(context, "flutter-default", FlutterAction.SetDefault, version, FlutterTool.defaultSteps(version), mapOf(FlutterTool.VERSION_ENV to version))

    fun removeVersion(context: Context, version: String) =
        run(context, "flutter-remove-version", FlutterAction.RemoveVersion, version, FlutterTool.removeVersionSteps(version), mapOf(FlutterTool.VERSION_ENV to version))

    /** Adds Release builds: Termux's qemu-x86_64 and an x86_64 C library, so release and profile APKs build here. */
    fun addReleaseBuilds(context: Context) =
        run(context, "flutter-release", FlutterAction.AddReleaseBuilds, null, FlutterTool.RELEASE_STEPS)

    fun removeReleaseBuilds(context: Context) =
        run(context, "flutter-release-remove", FlutterAction.RemoveReleaseBuilds, null, FlutterTool.RELEASE_REMOVE_STEPS)

    /**
     * Repair on Flutter's page: when only Release builds are half there, their own install puts them back; anything
     * else runs Flutter's install again (which also puts back half-there Release builds).
     */
    fun repairFlutter(context: Context) =
        if (FlutterTool.repairsOnlyReleaseBuilds(_state.value.flutterProblem)) addReleaseBuilds(context) else installFlutter(context)

    fun installRust(context: Context) = run(context, "rust", FlutterAction.InstallRust, null, FlutterTool.RUST_STEPS)

    fun removeRust(context: Context) = run(context, "rust-remove", FlutterAction.RemoveRust, null, FlutterTool.RUST_REMOVE_STEPS)

    /** Uninstall Flutter & Dart: every version, FVM and the caches, and Release builds. Rust stays. */
    fun uninstallFlutter(context: Context) =
        run(context, "flutter-remove", FlutterAction.UninstallFlutter, null, FlutterTool.FLUTTER_REMOVE_STEPS)

    /** Retry: the last job again, the same way. */
    fun retry(context: Context) {
        val last = lastRun ?: return
        run(context, last.option, last.action, last.target, last.steps, last.environment)
    }

    /** Waits for the running job to end, for the Updates page; returns whether it worked. */
    suspend fun awaitJob(): Boolean {
        state.first { !it.running }
        return !_state.value.failed
    }

    private class Run(val option: String, val action: FlutterAction, val target: String?, val steps: List<String>, val environment: Map<String, String>)

    @Volatile private var lastRun: Run? = null

    private fun run(
        context: Context,
        option: String,
        action: FlutterAction,
        target: String?,
        steps: List<String>,
        environment: Map<String, String> = emptyMap(),
    ) {
        synchronized(this) {
            if (_state.value.running) return
            _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), action = action, target = target, steps = steps, outcome = null, failed = false) }
            lastRun = Run(option, action, target, steps, environment)
        }
        val app = context.applicationContext
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option, environment) { change -> _state.update { s -> s.copy(job = s.job?.let(change)) } }
            val ok = exit == 0 && sawDone
            refresh()
            _state.update { s ->
                s.copy(
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                )
            }
            measure()
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
