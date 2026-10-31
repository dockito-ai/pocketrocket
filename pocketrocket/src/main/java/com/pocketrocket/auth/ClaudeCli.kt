package com.pocketrocket.auth

import android.content.Context
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Runs Claude Code's own `claude auth` commands through the launcher in $PREFIX/bin.
 * Every call takes the config dir to act on: null means Claude Code's default (~/.claude), a profile
 * (T4) passes its own dir and it is handed over as CLAUDE_CONFIG_DIR. Credentials stay in Claude Code's
 * files; nothing here reads or stores them.
 */
object ClaudeCli {
    private val launcher = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "claude")
    val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)

    fun environment(configDir: File?, extra: Map<String, String> = emptyMap()): Map<String, String> =
        buildMap {
            configDir?.let { put("CLAUDE_CONFIG_DIR", it.path) }
            putAll(extra)
        }

    /** Starts `claude <arguments>` with stdin open. */
    fun start(context: Context, arguments: Array<String>, configDir: File?, extraEnvironment: Map<String, String> = emptyMap()): Process =
        TermuxCommand.start(context, launcher.path, arguments, home, environment(configDir, extraEnvironment))

    /** Runs `claude <arguments>` to the end. Returns the exit code and the output lines. Blocking. */
    fun run(context: Context, arguments: Array<String>, configDir: File?): Pair<Int, List<String>> {
        if (!launcher.exists()) return -1 to listOf("Claude Code is not installed yet.")
        val lines = mutableListOf<String>()
        val process = start(context, arguments, configDir)
        process.outputStream.close()
        val exit = TermuxCommand.drain(process) { synchronized(lines) { lines += it } }
        return exit to lines
    }

    /** The signed-in account, or null when signed out or Claude Code could not tell. Blocking. */
    fun status(context: Context, configDir: File?): ClaudeAccount? {
        val (_, lines) = run(context, arrayOf("auth", "status", "--json"), configDir)
        return ClaudeAccount.fromStatusJson(lines.joinToString("\n"))
    }

    /** Claude Code's own logout. Returns true when it succeeded. Blocking. */
    fun logout(context: Context, configDir: File?): Boolean =
        run(context, arrayOf("auth", "logout"), configDir).first == 0
}
