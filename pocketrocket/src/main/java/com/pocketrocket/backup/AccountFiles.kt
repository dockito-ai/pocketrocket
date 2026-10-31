package com.pocketrocket.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A Claude account in a backup or on the phone. [email] and [org] say who it is; both come from `.claude.json`. */
data class ClaudeSide(val id: String, val name: String, val email: String?, val org: String?, val signedIn: Boolean)

/** A GitHub account in a backup or on the phone. [login] says who it is. */
data class GitHubSide(val id: String, val login: String)

/**
 * Reads the account files under a home folder directly, the phone's or a staged one: the app's registries
 * hold one fixed location each and are loaded once, and a staged copy has to be read before it is in place.
 * Reads names and identities, never a token. Where an account's files live is written down in AccountPaths.
 */
internal class AccountFiles(private val home: File) {
    private val accounts = File(home, ".pocketrocket/accounts")
    val claudeList = File(accounts, "claude.json")
    val githubList = File(accounts, "github.json")
    val dockitoList = File(accounts, "dockito.json")
    val bindings = File(accounts, "bindings.json")
    val workspaces = File(home, ".pocketrocket/workspaces.json")
    val dockitoDir = File(accounts, "dockito")

    fun claudeDir(id: String) = if (id == MAIN_CLAUDE_ID) File(home, ".claude") else File(accounts, "claude/$id")
    fun claudeJson(id: String) = if (id == MAIN_CLAUDE_ID) File(home, ".claude.json") else File(claudeDir(id), ".claude.json")
    fun githubDir(id: String) = File(accounts, "github/$id")

    /** The first account is always there, as in ClaudeAccounts, even before claude.json is written. */
    fun claudeAccounts(): List<ClaudeSide> {
        val listed = listObjects(claudeList).mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            side(id, o.optString("name").ifBlank { id })
        }
        return listed.ifEmpty { listOf(side(MAIN_CLAUDE_ID, "personal")) }
    }

    private fun side(id: String, name: String): ClaudeSide {
        val who = readJson(claudeJson(id))?.optJSONObject("oauthAccount")
        val email = who?.optString("emailAddress")?.trim()?.lowercase()?.ifEmpty { null }
        val org = who?.optString("organizationUuid")?.ifBlank { null }
        return ClaudeSide(id, name, email, org, email != null || File(claudeDir(id), ".credentials.json").exists())
    }

    fun githubAccounts(): List<GitHubSide> = listObjects(githubList).mapNotNull { o ->
        val id = o.optString("id").ifBlank { return@mapNotNull null }
        val login = o.optString("login").ifBlank { return@mapNotNull null }
        GitHubSide(id, login)
    }

    /** The Dockito logins, from the CLI's own accounts.json where it exists, else the app's copy of the list. */
    fun dockitoEmails(): List<String> {
        val store = readJson(File(dockitoDir, "accounts.json"))
        val inStore = store?.keys()?.asSequence()?.toList().orEmpty().flatMap { host ->
            val logins = store!!.optJSONObject(host)?.optJSONArray("accounts") ?: JSONArray()
            (0 until logins.length()).mapNotNull { logins.optJSONObject(it)?.optString("email")?.ifBlank { null } }
        }
        return (inStore + listObjects(dockitoList).mapNotNull { it.optString("email").ifBlank { null } }).map { it.lowercase() }.distinct()
    }

    fun defaultOf(list: File): String? = readJson(list)?.let { if (it.isNull("default")) null else it.optString("default").ifBlank { null } }

    fun workspaceList(): List<ManifestWorkspace> {
        val array = readJson(workspaces)?.optJSONArray("workspaces") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path").ifBlank { return@mapNotNull null }
            ManifestWorkspace(path, o.optString("name").ifBlank { File(path).name })
        }
    }

    private fun listObjects(file: File): List<JSONObject> {
        val array = readJson(file)?.optJSONArray("accounts") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    companion object {
        const val MAIN_CLAUDE_ID = "main"

        fun readJson(file: File): JSONObject? =
            if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null
    }
}
