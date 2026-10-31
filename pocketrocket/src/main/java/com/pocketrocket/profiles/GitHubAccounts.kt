package com.pocketrocket.profiles

import com.pocketrocket.github.GitIdentity
import org.json.JSONObject
import java.io.File

/**
 * One GitHub account on this phone (decision 21): its own gh login (GH_CONFIG_DIR), its own ed25519 key
 * added to the account, and a git config file with the commit identity and that key. [id] is the
 * lowercased login and names its folder.
 */
data class GitHubAccountEntry(
    override val id: String,
    val login: String,
    /** Commit author name. */
    val userName: String,
    /** Commit author email; the account's noreply address unless edited. */
    val userEmail: String,
    /** The title the key was added to GitHub with. */
    val keyTitle: String,
) : AccountEntry {
    override val name: String get() = login
    val ghConfigDir: File get() = AccountPaths.ghConfigDir(id)
    val sshKey: File get() = AccountPaths.sshKey(id)
    val gitConfig: File get() = AccountPaths.gitConfig(id)
}

object GitHubAccounts {
    private val registry = AccountRegistry(
        file = AccountPaths.githubList,
        read = { o ->
            GitHubAccountEntry(
                id = o.getString("id"), login = o.getString("login"),
                userName = o.optString("userName"), userEmail = o.optString("userEmail"), keyTitle = o.optString("keyTitle"),
            )
        },
        write = {
            JSONObject().put("id", it.id).put("login", it.login)
                .put("userName", it.userName).put("userEmail", it.userEmail).put("keyTitle", it.keyTitle)
        },
        onChange = { AccountWiring.refresh() },
    )

    val state get() = registry.state
    val current: AccountList<GitHubAccountEntry> get() = registry.current
    val default: GitHubAccountEntry? get() = current.default

    fun get(id: String?): GitHubAccountEntry? = current.get(id)

    /** Adds or replaces an account (reconnecting the same login replaces it). */
    fun put(entry: GitHubAccountEntry) = registry.put(entry)

    fun setDefault(id: String) = registry.setDefault(id)

    /** Changes the commit identity and rewrites the account's git config. */
    fun editIdentity(id: String, userName: String, userEmail: String) {
        registry.update(id) { it.copy(userName = userName.trim(), userEmail = userEmail.trim()) }
        get(id)?.let(GitIdentity::write)
    }

    /**
     * Forgets the account on this phone: its gh login, key and git config are deleted and folders bound
     * to it fall back to the default. The key stays listed on GitHub until removed there.
     */
    fun remove(id: String) {
        get(id) ?: return
        AccountWiring.forget(github = id)
        registry.remove(id)
        AccountPaths.githubDir(id).deleteRecursively()
    }
}
