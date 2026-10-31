package com.pocketrocket.dockito

import android.content.Context
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.DockitoAccountEntry
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.setup.SetupLine
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupScript
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.setup.finish
import com.pocketrocket.setup.on
import com.pocketrocket.termux.Termux
import com.pocketrocket.termux.TermuxCommand
import com.pocketrocket.updater.ClaudeVersions
import com.pocketrocket.updater.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Where a Dockito sign-in stands. [accountId] is the account signing in again, null for a new one. */
sealed interface DockitoLoginState {
    data object Idle : DockitoLoginState
    data class Starting(val accountId: String?) : DockitoLoginState

    /** The CLI printed its link (the screen opens it) and waits; [code] is the one to check there. */
    data class Waiting(val accountId: String?, val link: String, val code: String?) : DockitoLoginState
    data class Failed(val message: String) : DockitoLoginState
}

/** What Dockito's tool page (Settings › Tools › Dockito, decision 64) shows. */
data class DockitoState(
    val installed: String? = null,
    /** The installed version has been read at least once, so a null [installed] means it isn't there. */
    val loaded: Boolean = false,
    val latest: DockitoRelease? = null,
    val checking: Boolean = false,
    /** The formula could not be read on the last check. */
    val checkFailed: Boolean = false,
    val jobTitle: String? = null,
    val job: SetupState? = null,
    val outcome: Outcome? = null,
    /** whoami per account id (its email); missing until it has answered. */
    val statuses: Map<String, DockitoStatus> = emptyMap(),
    val login: DockitoLoginState = DockitoLoginState.Idle,
    /** Accounts being signed out. */
    val signingOut: Set<String> = emptySet(),
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    val updateAvailable: Boolean get() = installed != null && ClaudeVersions.isNewer(latest?.version, installed)

    /** The installed CLI has its own accounts (0.0.1-beta.27 and newer); older ones must update first. */
    val accountsSupported: Boolean get() = DockitoCli.supports(installed)
    val signingIn: Boolean get() = login is DockitoLoginState.Starting || login is DockitoLoginState.Waiting
}

/**
 * Dockito's CLI: installed by first-run setup (decision 40), updated from Settings › Updates (decision 41). One binary, installed
 * and updated through the setup script's `--only dockito` (download from Dockito's Homebrew tap, SHA-256
 * from the formula, glibc patch, smoke test), 0.0.1-beta.27 or newer. Accounts are the CLI's own: all
 * logins in one DOCKITO_CONFIG_DIR, listed by `dockito accounts`, the one in use is the default, and a
 * workspace can bind its own by email (DOCKITO_ACCOUNT, through AccountWiring). The Dockito skill is added to every Claude account. Updates
 * never install on their own.
 */
object Dockito {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DockitoState())
    val state: StateFlow<DockitoState> = _state.asStateFlow()

    private val attempt = AtomicInteger()
    @Volatile private var loginProcess: Process? = null

    /** The line added to each Claude account's copy of the skill (once). */
    internal const val SKILL_NOTE_MARK = "<!-- pocketrocket-dockito-accounts -->"
    internal const val SKILL_NOTE = "$SKILL_NOTE_MARK\n\n" +
        "On this phone (PocketRocket) there can be several Dockito accounts. `dockito accounts` lists them; " +
        "to act as another one for a command, add `--account <email>`. Without it, dockito runs as this " +
        "folder's account (DOCKITO_ACCOUNT, set by PocketRocket) or the default.\n"

    /** Settings shows: the installed version, cheaply, with no network. */
    fun load() {
        scope.launch { _state.update { it.copy(installed = DockitoCli.installed(), loaded = true) } }
    }

    /** Dockito's tool page opens: the installed version and who each account is, without the formula. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val installed = DockitoCli.installed()
            _state.update { it.copy(installed = installed, loaded = true) }
            if (installed != null) refreshAccounts(app)
        }
    }

    /** The Updates screen checks: installed version, the formula's latest, and who each account is. */
    fun check(context: Context) {
        val app = context.applicationContext
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            val installed = DockitoCli.installed()
            _state.update { it.copy(installed = installed, loaded = true) }
            val latest = DockitoFormula.fetch()
            _state.update { it.copy(checking = false, latest = latest ?: it.latest, checkFailed = latest == null) }
            if (installed != null) refreshAccounts(app)
        }
    }

    /** Installs or updates to [release]. Then refreshes the skill in every Claude account. */
    fun install(context: Context, release: DockitoRelease) {
        if (_state.value.running) return
        val app = context.applicationContext
        val before = DockitoCli.installed()
        val title = if (before == null) "Installing Dockito ${release.version}" else "Updating to ${release.version}"
        _state.update { it.copy(jobTitle = title, job = SetupState(phase = SetupPhase.Running), outcome = null) }
        Termux.startForegroundService(app)
        scope.launch {
            var sawDone = false
            val env = mapOf(
                "POCKETROCKET_DOCKITO_VERSION" to release.version,
                "POCKETROCKET_DOCKITO_URL" to release.url,
                "POCKETROCKET_DOCKITO_SHA256" to release.sha256,
            )
            val exit = runCatching {
                SetupScript.run(app, listOf("--only", "dockito"), env) { raw ->
                    val line = SetupLine.parse(raw)
                    if (line == SetupLine.Done) sawDone = true
                    synchronized(this@Dockito) { _state.update { s -> s.copy(job = s.job?.on(line)) } }
                }
            }.getOrElse { e ->
                _state.update { s -> s.copy(job = s.job?.on(SetupLine.Error(e.message ?: e.toString()))) }
                -1
            }
            val after = DockitoCli.installed()
            val ok = exit == 0 && sawDone && after == release.version
            val outcome = if (ok) Outcome("Dockito ${release.version} is installed. Type dockito in a terminal.", failed = false)
            else Outcome((_state.value.job?.error ?: "Stopped unexpectedly (exit code $exit).") + (after?.let { " Dockito $it stays." } ?: ""), failed = true)
            _state.update { s ->
                s.copy(
                    installed = after,
                    job = s.job?.finish(if (ok) StepStatus.Done else StepStatus.Failed)
                        ?.copy(phase = if (ok) SetupPhase.Done else SetupPhase.Failed, progress = null, detail = null),
                    outcome = outcome,
                )
            }
            if (after != null) {
                refreshAccounts(app)
                // A new CLI may carry a newer skill: init again everywhere.
                if (ok) addSkillEverywhere(app, onlyMissing = false)
            }
        }
    }

    /**
     * Reads the logins from the CLI (`dockito accounts --json`) into DockitoAccounts, asks whoami for each,
     * then makes sure every Claude account has the skill. Blocking; nothing on a CLI without accounts.
     */
    fun refreshAccounts(context: Context) {
        val app = context.applicationContext
        if (!DockitoCli.supports(DockitoCli.installed())) return
        val logins = DockitoCli.accounts(app) ?: return
        DockitoAccounts.sync(logins)
        _state.update { s -> s.copy(statuses = s.statuses.filterKeys { id -> logins.accounts.any { it.id == id } }) }
        logins.accounts.forEach { account ->
            val status = DockitoCli.status(app, account)
            _state.update { it.copy(statuses = it.statuses + (account.id to status)) }
        }
        addSkillEverywhere(app, onlyMissing = true)
    }

    /**
     * Starts `dockito login --add --no-browser` (plain `login` while there is none), for a new account or
     * to sign [account] in again, waits for its link (and code), then for it to finish. The screen opens
     * the link; the CLI saves the login in the shared store itself, and the list is read again from it.
     * Nothing it prints is logged.
     */
    fun signIn(context: Context, account: DockitoAccountEntry? = null) {
        val app = context.applicationContext
        cancelSignIn()
        val id = attempt.incrementAndGet()
        _state.update { it.copy(login = DockitoLoginState.Starting(account?.id)) }
        thread(name = "pocketrocket-dockito-login") { runLogin(app, id, account) }
    }

    fun cancelSignIn() {
        attempt.incrementAndGet()
        loginProcess?.destroy()
        loginProcess = null
        _state.update { it.copy(login = DockitoLoginState.Idle) }
    }

    fun clearFailure() {
        if (_state.value.login is DockitoLoginState.Failed) _state.update { it.copy(login = DockitoLoginState.Idle) }
    }

    /** Makes [account] the default: `dockito switch <email>`, the login in use wherever no workspace picked one. */
    fun setDefault(context: Context, account: DockitoAccountEntry) {
        val app = context.applicationContext
        scope.launch {
            DockitoCli.makeDefault(app, account)
            refreshAccounts(app)
        }
    }

    /** Signs [account] out (`dockito logout --account <email>`); workspaces bound to it use the default. */
    fun signOut(context: Context, account: DockitoAccountEntry) {
        val app = context.applicationContext
        _state.update { it.copy(signingOut = it.signingOut + account.id) }
        scope.launch {
            DockitoCli.logout(app, account)
            refreshAccounts(app)
            _state.update { it.copy(signingOut = it.signingOut - account.id) }
        }
    }

    /** A Claude account was just added: give it the skill when a Dockito account is signed in. */
    fun onClaudeAccountAdded(context: Context, claudeConfigDir: File) {
        val app = context.applicationContext
        scope.launch {
            if (!canInit()) return@launch
            claudeConfigDir.mkdirs()
            if (DockitoCli.addSkill(app, claudeConfigDir)) addSkillNote(claudeConfigDir)
        }
    }

    private fun runLogin(context: Context, id: Int, account: DockitoAccountEntry?) {
        val before = DockitoAccounts.current.accounts.map { it.id }.toSet()
        val args = if (before.isEmpty()) arrayOf("login", "--no-browser") else arrayOf("login", "--add", "--no-browser")
        val p = try {
            DockitoCli.start(context, null, args)
        } catch (e: Exception) {
            if (id == attempt.get()) _state.update { it.copy(login = DockitoLoginState.Failed("Could not start Dockito. Is it installed?")) }
            return
        }
        if (id != attempt.get()) { p.destroy(); return }
        loginProcess = p
        p.outputStream.close()
        var link: String? = null
        var code: String? = null
        var lastLine: String? = null
        val exit = TermuxCommand.drain(p) { line ->
            synchronized(this) {
                val clean = DockitoLoginOutput.clean(line)
                if (clean.isNotEmpty()) lastLine = clean
                if (link == null) link = DockitoLoginOutput.link(clean)
                if (code == null) code = DockitoLoginOutput.code(clean)
                val l = link
                if (l != null && id == attempt.get()) _state.update { it.copy(login = DockitoLoginState.Waiting(account?.id, l, code)) }
            }
        }
        if (loginProcess === p) loginProcess = null
        if (id != attempt.get()) return
        if (exit == 0) {
            refreshAccounts(context)
            _state.update { it.copy(login = DockitoLoginState.Idle) }
        } else {
            val why = lastLine?.takeIf { !it.contains("https://") }
            _state.update { it.copy(login = DockitoLoginState.Failed(why ?: "Sign-in did not finish. Try again.")) }
        }
    }

    /** `dockito init` needs a login, or it would start a sign-in. */
    private fun canInit(): Boolean = DockitoCli.supports(DockitoCli.installed()) && DockitoAccounts.current.accounts.isNotEmpty()

    /**
     * `dockito init --agent claude-code` with each Claude account's CLAUDE_CONFIG_DIR, so every account's
     * sessions find the Dockito skill, plus the note on Dockito accounts. [onlyMissing] skips Claude
     * accounts that already have it.
     */
    private fun addSkillEverywhere(context: Context, onlyMissing: Boolean) {
        if (!canInit()) return
        ClaudeAccounts.current.accounts.forEach { account ->
            val dir = account.folder
            val skill = File(dir, "skills/dockito/SKILL.md")
            if (onlyMissing && skill.exists() && skill.readText().contains(SKILL_NOTE_MARK)) return@forEach
            dir.mkdirs()
            if (DockitoCli.addSkill(context, dir)) addSkillNote(dir)
        }
    }

    private fun addSkillNote(claudeConfigDir: File) = runCatching {
        val skill = File(claudeConfigDir, "skills/dockito/SKILL.md")
        if (skill.isFile && !skill.readText().contains(SKILL_NOTE_MARK)) {
            val text = skill.readText()
            skill.writeText(text.trimEnd() + "\n\n" + SKILL_NOTE)
        }
    }
}
