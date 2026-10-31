package com.pocketrocket.claudeconfig

import java.io.File

/** Where to open a terminal, and what to type there to reach the right account's `/mcp`. */
data class TerminalHint(val folder: File, val command: String)

/**
 * The fallback when sign-in can't be started from the app. A terminal's account comes from its folder (env.sh, set again
 * before every prompt), so when that isn't the account in question the command has to say so. Pure.
 */
object TerminalFallback {
    /** [accountDir] and [folderAccountDir] are CLAUDE_CONFIG_DIR values, null for Claude Code's default. */
    fun hint(folder: File, accountDir: String?, folderAccountDir: String?) = TerminalHint(folder, command(accountDir, folderAccountDir))

    fun command(accountDir: String?, folderAccountDir: String?): String = when {
        accountDir == folderAccountDir -> "claude"
        accountDir == null -> "env -u CLAUDE_CONFIG_DIR claude"
        else -> "CLAUDE_CONFIG_DIR='" + accountDir.replace("'", "'\\''") + "' claude"
    }
}
