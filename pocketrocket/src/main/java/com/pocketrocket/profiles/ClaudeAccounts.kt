package com.pocketrocket.profiles

import com.pocketrocket.sessions.ClaudeProfile
import org.json.JSONObject
import java.io.File

/**
 * One Claude subscription on this phone: a named Claude Code config dir. The first account ([MAIN_ID])
 * is Claude Code's default ~/.claude, so the sign-in from setup carries over; added ones get
 * ~/.pocketrocket/accounts/claude/<id>/. Renaming changes only [name]; the folder keeps its id so running
 * sessions and transcripts stay where they are.
 */
data class ClaudeAccountEntry(override val id: String, override val name: String) : AccountEntry {
    val isMain: Boolean get() = id == ClaudeAccounts.MAIN_ID

    /** The CLAUDE_CONFIG_DIR to pass, or null for Claude Code's default ~/.claude. */
    val configDir: File? get() = if (isMain) null else AccountPaths.claudeDir(id)

    /** The folder holding this account's Claude Code files, whichever it is. */
    val folder: File get() = configDir ?: AccountPaths.defaultClaudeDir

    /** What a session carries to run as this account. */
    val profile: ClaudeProfile get() = ClaudeProfile(name, configDir?.path)
}

object ClaudeAccounts {
    const val MAIN_ID = "main"

    private val registry = AccountRegistry(
        file = AccountPaths.claudeList,
        read = { o -> ClaudeAccountEntry(o.getString("id"), o.optString("name").ifBlank { o.getString("id") }) },
        write = { JSONObject().put("id", it.id).put("name", it.name) },
        seed = { listOf(ClaudeAccountEntry(MAIN_ID, "personal")) },
        onChange = { AccountWiring.refresh() },
    )

    val state get() = registry.state
    val current: AccountList<ClaudeAccountEntry> get() = registry.current
    val default: ClaudeAccountEntry get() = current.default ?: ClaudeAccountEntry(MAIN_ID, "personal")

    fun get(id: String?): ClaudeAccountEntry? = current.get(id)

    /** The account whose config dir is [configDir] (null: ~/.claude), for sessions saved before accounts existed. */
    fun forConfigDir(configDir: String?): ClaudeAccountEntry? =
        current.accounts.firstOrNull { it.configDir?.path == configDir }

    /** Adds an account called [name] with an empty config dir, ready to sign in. */
    fun add(name: String): ClaudeAccountEntry {
        val entry = ClaudeAccountEntry(registry.freeId(name, reserved = setOf(MAIN_ID)), name.trim())
        entry.folder.mkdirs()
        registry.put(entry)
        return entry
    }

    fun rename(id: String, name: String) {
        if (name.isNotBlank()) registry.update(id) { it.copy(name = name.trim()) }
    }

    fun setDefault(id: String) = registry.setDefault(id)

    /**
     * Forgets an added account and deletes its config dir (sign it out first). The first account stays;
     * it can only be signed out. Folders bound to it fall back to the default.
     */
    fun remove(id: String) {
        val entry = get(id) ?: return
        if (entry.isMain) return
        AccountWiring.forget(claude = id)
        registry.remove(id)
        entry.configDir?.deleteRecursively()
    }
}
