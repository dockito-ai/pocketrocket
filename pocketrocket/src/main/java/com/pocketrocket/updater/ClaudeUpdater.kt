package com.pocketrocket.updater

import android.content.Context
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.setup.SetupLine
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupScript
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.setup.finish
import com.pocketrocket.setup.on
import com.pocketrocket.termux.Termux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Claude Code screen and the Settings row show. */
data class UpdaterState(
    val installed: String? = null,
    /** The older version kept for rollback. */
    val kept: String? = null,
    val latest: String? = null,
    val stable: String? = null,
    val checking: Boolean = false,
    /** When downloads.claude.ai last answered, epoch millis; 0 never. */
    val checkedAt: Long = 0,
    /** The running update or rollback ("Updating to 2.1.312"), with the script's steps. */
    val jobTitle: String? = null,
    val job: SetupState? = null,
    /** How the last update or rollback ended, for the screen. */
    val outcome: Outcome? = null,
    /** The version sessions still run after an update, and those sessions' ids. */
    val staleVersion: String? = null,
    val staleIds: Set<String> = emptySet(),
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    val updateAvailable: Boolean get() = installed != null && ClaudeVersions.isNewer(latest, installed)
}

data class Outcome(val message: String, val failed: Boolean)

/**
 * The app's Claude Code updater (R9): the only one, since Claude Code runs with autoUpdates false and
 * DISABLE_AUTOUPDATER=1. Checks downloads.claude.ai at most once a day when the app opens, and installs or
 * rolls back only when asked, by running the setup script's Claude Code part (`--only claude`,
 * `--switch X.Y.Z`): download, manifest check, smoke test, `current` switch and pruning all stay in the script.
 * Running sessions keep the binary they started with; [restartStale] restarts them on the new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ClaudeUpdater {
    private const val PREFS = "pocketrocket"
    private const val KEY_CHECKED_AT = "claude_checked_at"
    private const val KEY_LATEST = "claude_latest"
    private const val KEY_STABLE = "claude_stable"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdaterState())
    val state: StateFlow<UpdaterState> = _state.asStateFlow()
    @Volatile private var started = false

    /**
     * App start (and each time Settings shows): loads the last check and the installed versions, and asks
     * downloads.claude.ai again when the last answer is a day old. Never installs anything.
     */
    fun onAppOpen(context: Context) {
        val app = context.applicationContext
        if (!started) {
            started = true
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _state.update {
                it.copy(
                    checkedAt = prefs.getLong(KEY_CHECKED_AT, 0),
                    latest = prefs.getString(KEY_LATEST, null),
                    stable = prefs.getString(KEY_STABLE, null),
                )
            }
            scope.launch { dropEndedSessions() }
        }
        scope.launch { refreshInstalled() }
        if (System.currentTimeMillis() - _state.value.checkedAt >= DAY_MS) check(app)
    }

    /** Re-reads the installed versions and asks downloads.claude.ai for `latest` and `stable`. */
    fun check(context: Context) {
        if (_state.value.checking) return
        val app = context.applicationContext
        _state.update { it.copy(checking = true) }
        scope.launch {
            refreshInstalled()
            val latest = ClaudeVersions.fetch("latest")
            val stable = ClaudeVersions.fetch("stable")
            val now = System.currentTimeMillis()
            if (latest != null) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_CHECKED_AT, now).putString(KEY_LATEST, latest).putString(KEY_STABLE, stable).apply()
            }
            _state.update {
                if (latest == null) it.copy(checking = false)
                else it.copy(checking = false, latest = latest, stable = stable, checkedAt = now)
            }
        }
    }

    /** Installs [version] (verify, smoke test, switch). A version that fails its smoke test is not made current. */
    fun update(context: Context, version: String) =
        runScript(context, "Updating to $version", listOf("--only", "claude"), mapOf("POCKETROCKET_CLAUDE_VERSION" to version), version)

    /** Switches back to the kept [version]; the script smoke-tests it and switches back if it fails. */
    fun switchTo(context: Context, version: String) =
        runScript(context, "Switching back to $version", listOf("--switch", version), emptyMap(), version)

    private fun runScript(context: Context, title: String, options: List<String>, env: Map<String, String>, target: String) {
        if (_state.value.running) return
        val app = context.applicationContext
        val before = ClaudeVersions.installed()
        _state.update { it.copy(jobTitle = title, job = SetupState(phase = SetupPhase.Running), outcome = null) }
        Termux.startForegroundService(app)
        scope.launch {
            var sawDone = false
            var lastWarning: String? = null
            val exit = runCatching {
                SetupScript.run(app, options, env) { raw ->
                    val line = SetupLine.parse(raw)
                    if (line == SetupLine.Done) sawDone = true
                    if (line is SetupLine.Log && raw.startsWith("[warn] ") && !raw.startsWith("[warn] keeping")) {
                        lastWarning = raw.removePrefix("[warn] ")
                    }
                    synchronized(this@ClaudeUpdater) { _state.update { s -> s.copy(job = s.job?.on(line)) } }
                }
            }.getOrElse { e ->
                _state.update { s -> s.copy(job = s.job?.on(SetupLine.Error(e.message ?: e.toString()))) }
                -1
            }
            val after = ClaudeVersions.installed()
            val ok = exit == 0 && sawDone
            val outcome = when {
                ok && after == target -> Outcome(
                    if (options.first() == "--switch") "Back on Claude Code $target. It started fine on this phone."
                    else "Claude Code $target is installed and passed its checks.",
                    failed = false,
                )
                ok -> Outcome(
                    "Claude Code $target did not pass its checks on this phone, so ${after ?: "the old version"} stays. " +
                        (lastWarning ?: ""), failed = true,
                )
                else -> Outcome(
                    (_state.value.job?.error ?: "Stopped unexpectedly (exit code $exit).") +
                        (after?.let { " Claude Code $it stays." } ?: ""), failed = true,
                )
            }
            val changed = after != null && after != before
            val stale = if (changed) liveSessionIds() else emptySet()
            _state.update { s ->
                s.copy(
                    installed = after,
                    kept = ClaudeVersions.kept(),
                    job = s.job?.finish(if (ok) StepStatus.Done else StepStatus.Failed)
                        ?.copy(phase = if (ok) SetupPhase.Done else SetupPhase.Failed, progress = null, detail = null),
                    outcome = outcome,
                    staleVersion = if (changed && stale.isNotEmpty()) before else s.staleVersion,
                    staleIds = if (changed) s.staleIds + stale else s.staleIds,
                )
            }
        }
    }

    /** Sessions still running the version from before the last update, live. */
    fun staleSessions() = combine(_state, SessionManager.sessions) { s, list -> list.filter { it.id in s.staleIds } }

    /**
     * Restarts the stale sessions that are between turns: end the process, then start it again, which
     * resumes the conversation (`--resume`) on the new `current`. Busy ones are left alone and stay listed.
     */
    fun restartStale(): Int {
        val idle = SessionManager.sessions.value.filter { it.id in _state.value.staleIds && it.isBetweenTurns() }
        idle.forEach { session ->
            session.pause()
            session.start()
        }
        return idle.size
    }

    private fun Session.isBetweenTurns() = state.value.status.let {
        it == SessionStatus.Done || it == SessionStatus.WaitingForInput || it == SessionStatus.Error
    }

    private fun refreshInstalled() {
        val installed = ClaudeVersions.installed()
        val kept = ClaudeVersions.kept()
        _state.update { it.copy(installed = installed, kept = kept) }
    }

    private fun liveSessionIds(): Set<String> =
        SessionManager.sessions.value.filter { it.alive.value && !it.state.value.demo }.map { it.id }.toSet()

    /** A stale session whose process ends (paused, closed, restarted) runs the new version next time. */
    private suspend fun dropEndedSessions() {
        SessionManager.sessions
            .flatMapLatest { list ->
                if (list.isEmpty()) flowOf(emptySet())
                else combine(list.map { s -> s.alive.map { alive -> s.id.takeIf { alive } } }) { ids -> ids.filterNotNull().toSet() }
            }
            .collect { alive ->
                _state.update { s ->
                    val left = s.staleIds.intersect(alive)
                    if (left == s.staleIds) s else s.copy(staleIds = left, staleVersion = if (left.isEmpty()) null else s.staleVersion)
                }
            }
    }
}
