package com.pocketrocket.clitools

import android.content.Context
import com.pocketrocket.clilogin.CliLogin
import com.pocketrocket.clilogin.CliLogins
import com.pocketrocket.node.NpmGlobal
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupScript
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

/** What an npm tool's job does: the tile's Install (and Repair), its Updates row's Update, or Uninstall. */
enum class NpmAction { Install, Update, Remove }

data class NpmToolsState(
    val checked: Boolean = false,
    /** What is on the phone now, read from npm's folders; a tool that isn't there has no entry. */
    val present: Map<NpmTool, NpmPresence> = emptyMap(),
    val job: SetupState? = null,
    /** The tool the running (or last) job is for. */
    val tool: NpmTool? = null,
    val action: NpmAction = NpmAction.Install,
    val outcome: String? = null,
    val failed: Boolean = false,
    /** Each tool's newest release from the last `npm view`, kept across restarts. */
    val latest: Map<NpmTool, String> = emptyMap(),
    /** The tools whose `npm view` is running now. */
    val checking: Set<NpmTool> = emptySet(),
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    val removing: Boolean get() = action == NpmAction.Remove
    val updating: Boolean get() = action == NpmAction.Update
    fun isFor(t: NpmTool): Boolean = tool == t
    fun installed(t: NpmTool): Boolean = t in present
    fun version(t: NpmTool): String? = present[t]?.version

    /** npm has a newer release than the one on the phone (never offered while it needs Repair). */
    fun updateAvailable(t: NpmTool): Boolean = present[t]?.problem == null && NpmGlobal.newer(version(t), latest[t])

    /** [t]'s update is running, or was the last job and failed. */
    fun updateJob(t: NpmTool): SetupState? = job?.takeIf { isFor(t) && updating }
}

/**
 * Installs, updates and removes the npm deploy CLIs (decision 98) through the setup script, one at a time (npm's global
 * folder takes one writer), and holds each one's login ([login]). [refresh] reads npm's folders and the logins' config
 * files, so the tiles follow what a terminal or a Claude session installs, removes, or signs in to. [check] asks npm
 * for the newest release of an installed one; the answer is kept, so opening the app asks nothing.
 */
object NpmTools {
    private const val PREFS = "pocketrocket"
    private const val KEY_LATEST = "npm_latest_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
    private val _state = MutableStateFlow(NpmToolsState())
    val state: StateFlow<NpmToolsState> = _state.asStateFlow()
    @Volatile private var cacheRead = false

    fun login(t: NpmTool): CliLogin = CliLogins.of(t.login)

    fun refresh() {
        scope.launch { read() }
    }

    /** [refresh], plus the newest releases the last check found. No network. */
    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!cacheRead) {
                cacheRead = true
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val cached = NpmTool.entries.mapNotNull { t -> prefs.getString(KEY_LATEST + t.option, null)?.let { t to it } }.toMap()
                _state.update { it.copy(latest = cached + it.latest) }
            }
            read()
        }
    }

    private fun read() {
        val present = NpmTool.entries.mapNotNull { t -> t.presence(prefix)?.let { t to it } }.toMap()
        _state.update { it.copy(present = present, checked = true) }
        NpmTool.entries.forEach { login(it).refresh() }
    }

    /**
     * Asks npm for [t]'s newest release (`--only <option>-check`, one `npm view`). Only while it is on the phone: one
     * that isn't installed has no row and costs no request. Blocking; returns when npm has answered.
     */
    fun checkBlocking(context: Context, t: NpmTool) {
        read()
        synchronized(this) {
            val s = _state.value
            if (!s.installed(t) || t in s.checking || (s.running && s.isFor(t))) return
            _state.update { it.copy(checking = it.checking + t) }
        }
        val app = context.applicationContext
        val lines = mutableListOf<String>()
        val exit = runCatching { SetupScript.run(app, listOf("--only", t.checkOption)) { synchronized(lines) { lines += it } } }.getOrDefault(-1)
        val latest = NpmGlobal.viewedVersion(synchronized(lines) { lines.toList() })?.takeIf { exit == 0 }
        if (latest != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LATEST + t.option, latest).apply()
        _state.update { s -> s.copy(checking = s.checking - t, latest = if (latest != null) s.latest + (t to latest) else s.latest) }
    }

    fun install(context: Context, t: NpmTool) = run(context, t, NpmAction.Install)

    /** `npm install -g <package>@latest`, with the install's steps and progress. */
    fun update(context: Context, t: NpmTool) = run(context, t, NpmAction.Update)

    fun remove(context: Context, t: NpmTool) = run(context, t, NpmAction.Remove)

    /** Retry: the last job again. */
    fun retry(context: Context) {
        val s = _state.value
        s.tool?.let { run(context, it, s.action) }
    }

    private fun run(context: Context, t: NpmTool, action: NpmAction) {
        synchronized(this) {
            if (_state.value.running || login(t).state.value.busy) return
            _state.update { it.copy(job = SetupState(phase = SetupPhase.Running), tool = t, action = action, outcome = null, failed = false) }
        }
        val app = context.applicationContext
        val option = when (action) {
            NpmAction.Install -> t.option
            NpmAction.Update -> t.updateOption
            NpmAction.Remove -> t.removeOption
        }
        val target = _state.value.latest[t]
        Termux.startForegroundService(app)
        scope.launch {
            val (exit, sawDone) = ToolScript.run(app, option) { change ->
                _state.update { s -> s.copy(job = s.job?.let(change)) }
            }
            read()
            if (action == NpmAction.Remove) login(t).forget()
            val now = _state.value.present[t]
            val ok = exit == 0 && sawDone && when (action) {
                NpmAction.Remove -> now == null
                NpmAction.Install -> now != null && now.problem == null
                // npm may have published again since the check; anything at least as new as the check is fine.
                NpmAction.Update -> now != null && now.problem == null && !NpmGlobal.newer(now.version, target)
            }
            _state.update { s ->
                s.copy(
                    job = s.job?.finished(ok),
                    outcome = if (ok) null else s.job?.error ?: "Stopped unexpectedly (exit code $exit).",
                    failed = !ok,
                    // What was just installed is the newest npm had, as far as this run knows.
                    latest = if (ok && action == NpmAction.Update && now?.version != null && NpmGlobal.newer(s.latest[t], now.version)) s.latest + (t to now.version) else s.latest,
                )
            }
        }
    }
}
