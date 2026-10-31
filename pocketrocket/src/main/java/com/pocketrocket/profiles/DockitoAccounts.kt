package com.pocketrocket.profiles

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Dockito login on this phone. Dockito's CLI keeps every login in one DOCKITO_CONFIG_DIR
 * ([AccountPaths.dockitoConfig]) and picks one by email (DOCKITO_ACCOUNT or `--account`), so the email is
 * the [id] and what workspace bindings store. [name] and [username] are what `dockito accounts` said, kept
 * so lists and pickers need no process. No token here.
 */
data class DockitoAccountEntry(
    val email: String,
    override val name: String,
    val username: String,
) : AccountEntry {
    override val id: String get() = email
}

/** What `dockito accounts --json` lists: the logins, and the email of the one in use (the default). */
data class DockitoLogins(val accounts: List<DockitoAccountEntry>, val active: String?)

/**
 * Dockito accounts. The CLI owns the list (`dockito accounts`, `login --add`, `switch`, `logout
 * --account`); the app keeps a copy in dockito.json, refreshed with [sync] after every change, so pickers,
 * bindings and `dockito-accounts` work offline. The default is the CLI's login in use.
 */
object DockitoAccounts {
    private val registry = AccountRegistry(
        file = AccountPaths.dockitoList,
        read = { o ->
            val email = o.optString("email").ifBlank { null } ?: return@AccountRegistry null
            DockitoAccountEntry(email, o.optString("name").ifBlank { email }, o.optString("username").ifBlank { email })
        },
        write = { JSONObject().put("email", it.email).put("name", it.name).put("username", it.username) },
        onChange = { AccountWiring.refresh() },
    )

    val state get() = registry.state
    val current: AccountList<DockitoAccountEntry> get() = registry.current
    val default: DockitoAccountEntry? get() = current.default

    fun get(id: String?): DockitoAccountEntry? = current.get(id)

    /**
     * The environment that makes the CLI use the shared login store and, given [entry], that login. The
     * one place that says how an account is chosen; sessions, terminals (env.sh) and the app's own CLI
     * calls all go through it.
     */
    fun env(entry: DockitoAccountEntry? = null): Map<String, String> = buildMap {
        put("DOCKITO_CONFIG_DIR", AccountPaths.dockitoConfig.path)
        entry?.let { put("DOCKITO_ACCOUNT", it.email) }
    }

    /**
     * Takes the CLI's list as the truth: accounts gone from it (signed out anywhere) are dropped from
     * workspace bindings, so those folders use the default again.
     */
    fun sync(logins: DockitoLogins) {
        val emails = logins.accounts.map { it.email }.toSet()
        AccountWiring.bindings.value.mapNotNull { it.dockito }.toSet().filter { it !in emails }.forEach { AccountWiring.forget(dockito = it) }
        registry.replace(logins.accounts, logins.active)
    }

    /**
     * Reads `dockito accounts --json`: an array of `{email, name, username, active}` (`[]` when signed
     * out). Null when the output is not that list.
     */
    fun parse(output: String): DockitoLogins? {
        val start = output.indexOf('[')
        val end = output.lastIndexOf(']')
        if (start < 0 || end < start) return null
        val array = runCatching { JSONArray(output.substring(start, end + 1)) }.getOrNull() ?: return null
        var active: String? = null
        val accounts = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val email = o.optString("email").ifBlank { null } ?: return@mapNotNull null
            if (o.optBoolean("active")) active = email
            val username = o.optString("username").ifBlank { email }
            DockitoAccountEntry(email, o.optString("name").ifBlank { username }, username)
        }
        return DockitoLogins(accounts, active)
    }
}
