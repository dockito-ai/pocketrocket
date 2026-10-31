package com.pocketrocket.claudeconfig

import android.content.Context
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.sessions.transport.ClaudeProcessTransport
import java.io.File

/**
 * A short-lived `claude -p` for control requests when no session is open: no user message, no model call, no usage.
 * [run] always ends the process. Costs a Claude Code process for its length, so only on demand, never in the background.
 */
object ClaudeConfigProbe {
    /** Longest any probe lives: the longest wait in [McpPanel], and a little. */
    const val CAP_MS = McpPanel.HOLD_MS + 2 * McpWait.TIMEOUT_MS

    /**
     * An empty folder for looking at an account as a whole. Not `~` or a repo: servers in a folder's `.mcp.json` start
     * with the process, and a folder's local servers aren't the account's.
     */
    val neutralFolder: File get() = File(AccountPaths.root, "connections/probe")

    /** Starts the process for [block] and ends it afterwards, however [block] ends. [environment]: see `ClaudeCli.environment`. */
    suspend fun <T> run(context: Context, folder: File, environment: Map<String, String>, block: suspend (ProbeChannel) -> T): T {
        folder.mkdirs()
        val probe = ProbeChannel(ClaudeProcessTransport.controlOnly(context.applicationContext, folder, environment), CAP_MS)
        try {
            probe.start()
            return block(probe)
        } finally {
            probe.close()
        }
    }
}
