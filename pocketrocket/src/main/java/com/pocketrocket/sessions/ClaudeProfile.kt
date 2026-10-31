package com.pocketrocket.sessions

/**
 * Which Claude account a session runs as: a separate Claude Code config dir, passed to that session's
 * process as CLAUDE_CONFIG_DIR. Sessions with different profiles run side by side. [configDir] null
 * means the normal ~/.claude. Made from a Claude account (profiles/ClaudeAccounts) when a session starts.
 */
data class ClaudeProfile(val name: String, val configDir: String? = null) {

    /** The environment a session's process gets on top of Termux's. */
    val environment: Map<String, String>
        get() = configDir?.let { mapOf("CLAUDE_CONFIG_DIR" to it) } ?: emptyMap()

    companion object {
        val Default = ClaudeProfile("default")
    }
}
