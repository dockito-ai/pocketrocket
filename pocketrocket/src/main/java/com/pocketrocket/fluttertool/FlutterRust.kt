package com.pocketrocket.fluttertool

import android.content.Context
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.ToolScript
import com.pocketrocket.setup.finished
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
import java.net.HttpURLConnection
import java.net.URL

/** What the running (or last) job of the Flutter & Rust tool is doing. */
enum class FlutterAction(val removes: Boolean) {
    InstallAll(false), AddVersion(false), SetDefault(false), InstallRust(false),
    RemoveVersion(true), RemoveRust(true), UninstallAll(true),
}

/** One installed Flutter version: its folder name, whether it's the default, and its size once measured. */
data class FlutterVersion(val version: String, val isDefault: Boolean, val bytes: Long? = null)

data class FlutterRustState(
    val flutterInstalled: Boolean = false,
    val rustInstalled: Boolean = false,
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
    val installed: Boolean get() = flutterInstalled || rustInstalled
    val removing: Boolean get() = action?.removes == true
    val defaultVersion: String? get() = versions.firstOrNull { it.isDefault }?.version
    /** Every version measured: Flutter's part of the tool's size. */
    val flutterBytes: Long? get() = if (versions.isNotEmpty() && versions.all { it.bytes != null }) versions.sumOf { it.bytes ?: 0 } else null
}

/**
 * The optional Flutter & Rust tool (decision 74): Settings → Tools installs, adds and removes Flutter versions, sets
 * the default and installs or removes Rust, each through the setup script. Nothing is bundled; first-run setup never
 * touches it. Sessions need nothing extra: `flutter`, `dart`, `fvm` and `cargo` are in $PREFIX/bin, and the launcher
 * finds the version itself.
 */
object FlutterRust {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val fvm = File(home, FlutterTool.FVM_RELATIVE)
    private val versionsDir = File(fvm, "versions")
    private val defaultLink = File(fvm, "default")
    private val flutterMarker = File(fvm, FlutterTool.INSTALLED_MARKER)
    private val rustMarker = File(home, FlutterTool.RUST_MARKER_RELATIVE)

    private val _state = MutableStateFlow(FlutterRustState())
    val state: StateFlow<FlutterRustState> = _state.asStateFlow()

    fun flutterInstalled(): Boolean = flutterMarker.isFile
    fun rustInstalled(): Boolean = rustMarker.isFile

    /** The default version's folder name, from FVM's link, or null. */
    fun defaultVersion(): String? = runCatching {
        defaultLink.takeIf { File(it, "bin/flutter").isFile }?.canonicalFile?.name
    }.getOrNull()

    /** Files only: what is installed, versions and the default. Sizes already measured are kept. */
    fun refresh() {
        val default = defaultVersion()
        val names = FlutterTool.installedVersions(
            versionsDir.listFiles()?.filter { File(it, "bin/flutter").isFile }?.map { it.name }.orEmpty(),
        )
        _state.update { s ->
            val known = s.versions.associateBy { it.version }
            s.copy(
                flutterInstalled = flutterInstalled(),
                rustInstalled = rustInstalled(),
                checked = true,
                versions = names.map { FlutterVersion(it, it == default, known[it]?.bytes) },
            )
        }
    }

    /** Measures each version's folder. Blocking; a large version takes a few seconds. */
    fun measure() {
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

    /** Install: the newest stable Flutter (the default) and Rust. */
    fun installAll(context: Context) =
        run(context, "flutter-rust", FlutterAction.InstallAll, null, FlutterTool.installAllSteps(_state.value.latestStable))

    /** Adds a Flutter version; [makeDefault] also makes it the default (Updates does). */
    fun addVersion(context: Context, version: String, makeDefault: Boolean = false) =
        run(
            context, "flutter", FlutterAction.AddVersion, version, FlutterTool.flutterSteps(version),
            mapOf(FlutterTool.VERSION_ENV to version) + (if (makeDefault) mapOf(FlutterTool.DEFAULT_ENV to "1") else emptyMap()),
        )

    fun setDefault(context: Context, version: String) =
        run(context, "flutter-default", FlutterAction.SetDefault, version, FlutterTool.defaultSteps(version), mapOf(FlutterTool.VERSION_ENV to version))

    fun removeVersion(context: Context, version: String) =
        run(context, "flutter-remove-version", FlutterAction.RemoveVersion, version, FlutterTool.removeVersionSteps(version), mapOf(FlutterTool.VERSION_ENV to version))

    fun installRust(context: Context) = run(context, "rust", FlutterAction.InstallRust, null, FlutterTool.RUST_STEPS)

    fun removeRust(context: Context) = run(context, "rust-remove", FlutterAction.RemoveRust, null, FlutterTool.RUST_REMOVE_STEPS)

    fun uninstallAll(context: Context) {
        val s = _state.value
        val option = FlutterTool.uninstallOption(s.flutterInstalled, s.rustInstalled) ?: return
        run(context, option, FlutterAction.UninstallAll, null, FlutterTool.uninstallSteps(s.flutterInstalled, s.rustInstalled))
    }

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
