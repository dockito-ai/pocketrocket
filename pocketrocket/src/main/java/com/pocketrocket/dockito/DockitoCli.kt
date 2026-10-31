package com.pocketrocket.dockito

import android.content.Context
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.DockitoAccountEntry
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.profiles.DockitoLogins
import com.pocketrocket.termux.TermuxCommand
import com.pocketrocket.updater.ClaudeVersions
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Dockito's CLI on this phone: where it is installed and how the app runs it. One binary and one login
 * store (DOCKITO_CONFIG_DIR, see DockitoAccounts) for everyone; the CLI keeps each login's token there
 * (a file, no keychain on Android) and picks one by email. The app never reads or copies a token; it
 * only asks the CLI (`accounts`, `whoami`). Commands that act as one account take it.
 */
object DockitoCli {
    private val launcher = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "dockito")

    /** The oldest CLI with its own accounts (`accounts`, `login --add`, `switch`, `--account`). */
    const val MIN_VERSION = "0.0.1-beta.27"

    /** ~/.pocketrocket/dockito/<version>/dockito, with `current` pointing at the one in use. */
    val dir: File get() = File(AccountPaths.home, ".pocketrocket/dockito")

    fun supports(version: String?): Boolean = version != null && ClaudeVersions.compare(version, MIN_VERSION) >= 0

    /** The version `current` points at, or null when Dockito is not installed. */
    fun installed(): String? = runCatching {
        File(dir, "current").canonicalFile.name
            .takeIf { ClaudeVersions.isVersion(it) && File(dir, "$it/dockito").exists() && launcher.exists() }
    }.getOrNull()

    /** Starts `dockito <arguments>` as [account] (the login in use when null), stdin open. */
    fun start(context: Context, account: DockitoAccountEntry?, arguments: Array<String>, extra: Map<String, String> = emptyMap()): Process =
        TermuxCommand.start(context, launcher.path, arguments, AccountPaths.home, DockitoAccounts.env(account) + extra)

    /** Runs `dockito <arguments>` to the end: the exit code and the output lines. Blocking. */
    fun run(context: Context, account: DockitoAccountEntry?, arguments: Array<String>, extra: Map<String, String> = emptyMap()): Pair<Int, List<String>> {
        if (!launcher.exists()) return -1 to listOf("Dockito is not installed.")
        val lines = mutableListOf<String>()
        val process = start(context, account, arguments, extra)
        process.outputStream.close()
        val exit = TermuxCommand.drain(process) { synchronized(lines) { lines += it } }
        return exit to lines
    }

    /** The logins on this phone (`dockito accounts --json`), or null when the CLI could not say. Blocking. */
    fun accounts(context: Context): DockitoLogins? {
        val (exit, lines) = run(context, null, arrayOf("accounts", "--json"))
        return if (exit == 0) DockitoAccounts.parse(lines.joinToString("\n")) else null
    }

    /** Who [account]'s login is and what it can reach: asks `dockito whoami --json`. Blocking. */
    fun status(context: Context, account: DockitoAccountEntry): DockitoStatus {
        val (exit, lines) = run(context, account, arrayOf("whoami", "--json"))
        return DockitoStatus.from(exit, lines.joinToString("\n"), hasLogin = true)
    }

    /** `dockito switch <email>`: makes [account] the login in use, which is the default. Blocking. */
    fun makeDefault(context: Context, account: DockitoAccountEntry): Boolean =
        run(context, null, arrayOf("switch", account.email)).first == 0

    /** `dockito logout --account <email>`: signs [account] out and removes its token. Blocking. */
    fun logout(context: Context, account: DockitoAccountEntry): Boolean =
        run(context, null, arrayOf("logout", "--account", account.email)).first == 0

    /**
     * Adds (or refreshes) the Dockito skill in one Claude account's config dir: `dockito init --agent
     * claude-code` writes it to $CLAUDE_CONFIG_DIR/skills. A login must exist, or init would start a
     * sign-in. Blocking.
     */
    fun addSkill(context: Context, claudeConfigDir: File): Boolean =
        run(
            context, null, arrayOf("init", "--agent", "claude-code", "--no-browser"),
            mapOf("CLAUDE_CONFIG_DIR" to claudeConfigDir.path),
        ).first == 0
}
