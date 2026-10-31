package com.pocketrocket.github

import android.content.Context
import android.os.Build
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.GitHubAccountEntry
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.termux.TermuxCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread

/** Where connecting a GitHub account stands. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Starting : ConnectState

    /** gh is polling GitHub: the user enters [code] at [url]. */
    data class WaitingForCode(val code: String, val url: String) : ConnectState

    /** Signed in; setting up the key and identity. [step] says what is happening. */
    data class Finishing(val step: String) : ConnectState
    data class Connected(val account: GitHubAccountEntry) : ConnectState
    data class Failed(val message: String) : ConnectState
}

/**
 * Connects one GitHub account, one at a time (decision 21):
 * 1. `gh auth login --web` into a fresh GH_CONFIG_DIR. Without a terminal gh prints a one-time code and
 *    GitHub's device page and polls; the app shows the code with Copy and opens the page in a Custom Tab.
 * 2. `gh api user` for the login, id and name; the folder becomes ~/.pocketrocket/accounts/github/<login>/.
 * 3. A new ed25519 key for this account only, added to it with `gh ssh-key add`.
 * 4. Its git config file: name, noreply email, core.sshCommand with that key and IdentitiesOnly=yes.
 * Reconnecting the same login replaces its gh login and keeps its key. The token stays in gh's own
 * hosts.yml in that folder; nothing here reads or logs it.
 */
object GitHubConnect {
    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val pending get() = File(AccountPaths.root, "github/.connecting")
    private var process: Process? = null
    private var attempt = 0

    private val CODE = Regex("one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})")
    private val URL = Regex("(https://github\\.com/login/device\\S*)")

    @Synchronized
    fun start(context: Context) {
        stop()
        val id = ++attempt
        _state.value = ConnectState.Starting
        val app = context.applicationContext
        thread(name = "pocketrocket-gh-connect") {
            val result = runCatching { connect(app, id) }.getOrElse { ConnectState.Failed(it.message ?: "Something went wrong. Try again.") }
            finish(id, result)
        }
    }

    @Synchronized
    fun cancel() {
        attempt++
        stop()
        _state.value = ConnectState.Idle
    }

    /** Clears a finished state once the screen has moved on. */
    @Synchronized
    fun reset() {
        if (process == null) _state.value = ConnectState.Idle
    }

    private fun connect(context: Context, id: Int): ConnectState {
        val work = pending.apply { deleteRecursively(); mkdirs() }
        val ghDir = File(work, "gh")

        // 1. Device-code sign-in.
        val p = GhCli.start(
            context, ghDir, "auth", "login", "--web", "--hostname", "github.com", "--git-protocol", "ssh",
            "--skip-ssh-key", "--scopes", "admin:public_key,read:user,user:email",
        )
        synchronized(this) {
            if (id != attempt) { p.destroy(); return ConnectState.Idle }
            process = p
        }
        p.outputStream.close()
        var code: String? = null
        var url: String? = null
        var lastError: String? = null
        val exit = TermuxCommand.drain(p) { line ->
            CODE.find(line)?.let { code = it.groupValues[1] }
            URL.find(line)?.let { url = it.groupValues[1] }
            if (line.contains("error", ignoreCase = true) || line.startsWith("X ")) lastError = line.trim()
            val c = code
            val u = url
            if (c != null && u != null) setIf(id, ConnectState.Starting, ConnectState.WaitingForCode(c, u))
        }
        synchronized(this) { if (process === p) process = null }
        if (id != attempt) return ConnectState.Idle
        if (exit != 0) return ConnectState.Failed(lastError?.let { "GitHub sign-in didn't finish: $it" } ?: "GitHub sign-in didn't finish. Try again.")

        // 2. Who signed in.
        progress(id, "Reading your GitHub profile")
        val (userExit, userLines) = GhCli.run(context, ghDir, "api", "user", "--jq", "\"\\(.login)\\t\\(.id)\\t\\(.name // \"\")\"")
        val fields = userLines.firstOrNull { it.count { ch -> ch == '\t' } == 2 }?.split('\t')
        if (userExit != 0 || fields == null) return ConnectState.Failed("Signed in, but gh could not read the account. Try again.")
        val (login, userId, fullName) = fields
        val accountId = AccountPaths.slug(login)
        val dir = AccountPaths.githubDir(accountId)
        dir.mkdirs()
        File(dir, "gh").deleteRecursively()
        if (!ghDir.renameTo(File(dir, "gh"))) return ConnectState.Failed("Could not save the gh login. Try again.")
        work.deleteRecursively()
        val existing = GitHubAccounts.get(accountId)
        val entry = GitHubAccountEntry(
            id = accountId, login = login,
            userName = existing?.userName ?: fullName.ifBlank { login },
            userEmail = existing?.userEmail ?: GitIdentity.noreplyEmail(userId.toLong(), login),
            keyTitle = existing?.keyTitle ?: "PocketRocket ${Build.MODEL} ($accountId)",
        )

        // 3. This account's own SSH key, added to GitHub.
        if (!entry.sshKey.exists()) {
            progress(id, "Making an SSH key for $login")
            val (kExit, _) = GhCli.runTool(
                context, GhCli.bin("ssh-keygen"),
                arrayOf("-q", "-t", "ed25519", "-N", "", "-C", "pocketrocket-$accountId", "-f", entry.sshKey.path),
            )
            if (kExit != 0) return ConnectState.Failed("Could not make an SSH key.")
        }
        progress(id, "Adding the key to $login")
        val (addExit, addLines) = GhCli.run(context, entry.ghConfigDir, "ssh-key", "add", entry.sshKey.path + ".pub", "--title", entry.keyTitle)
        val alreadyThere = addLines.any { it.contains("already", ignoreCase = true) }
        if (addExit != 0 && !alreadyThere) return ConnectState.Failed("Signed in, but GitHub did not take the SSH key. Try again.")

        // 4. Identity and wiring.
        progress(id, "Setting your commit identity")
        GitIdentity.write(entry)
        GitHubAccounts.put(entry)
        return ConnectState.Connected(entry)
    }

    @Synchronized
    private fun setIf(id: Int, expected: ConnectState, next: ConnectState) {
        if (id == attempt && _state.value == expected) _state.value = next
    }

    @Synchronized
    private fun progress(id: Int, step: String) {
        if (id == attempt) _state.value = ConnectState.Finishing(step)
    }

    @Synchronized
    private fun finish(id: Int, state: ConnectState) {
        if (id == attempt) _state.value = state
    }

    @Synchronized
    private fun stop() {
        process?.destroy()
        process = null
    }
}
