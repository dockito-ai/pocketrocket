package com.pocketrocket.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Rewrites a staged restore so it fits the accounts on the phone, before anything moves. [AccountMerge] decides
 * who is who; this moves each account's staged files to the id it lands on, drops what the phone's newer login
 * keeps, and writes the merged lists, bindings and workspaces. Runs on files only, so a failure changes nothing.
 */
internal class StagedAccounts(private val stagedHome: File, private val phoneHome: File, private val work: File) {
    private val staged = AccountFiles(stagedHome)
    private val phone = AccountFiles(phoneHome)

    fun plan(): AccountPlan = AccountMerge.plan(
        staged.claudeAccounts(), staged.defaultOf(staged.claudeList),
        phone.claudeAccounts(), phone.defaultOf(phone.claudeList),
        staged.githubAccounts(), staged.defaultOf(staged.githubList),
        phone.githubAccounts(), phone.defaultOf(phone.githubList),
        staged.dockitoEmails(), stagedDockitoActive(),
        phone.dockitoEmails(), phoneDockitoActive(),
    )

    fun merge(plan: AccountPlan) {
        // Read while the staged folders are still under their old ids.
        val stagedClaude = staged.claudeAccounts()
        val stagedGitHub = rawAccounts(staged.githubList)
        moveClaude(plan)
        mergeGitHub(plan)
        mergeDockito(plan)
        writeClaudeList(plan, stagedClaude)
        writeGitHubList(plan, stagedGitHub)
        writeBindings(plan)
        writeWorkspaces()
        reapplyDefaults(plan)
    }

    // ---- Claude ----

    private fun moveClaude(plan: AccountPlan) {
        val hold = File(work, "remap").apply { deleteRecursively(); mkdirs() }
        // Through a holding folder, because accounts can swap ids: the backup's main may land elsewhere and another on main.
        plan.claude.forEachIndexed { i, a ->
            val slot = File(hold, "$i").apply { mkdirs() }
            moveIfThere(staged.claudeDir(a.fromId), File(slot, "dir"))
            moveIfThere(staged.claudeJson(a.fromId), File(slot, "json"))
        }
        plan.claude.forEachIndexed { i, a ->
            val slot = File(hold, "$i")
            moveIfThere(File(slot, "dir"), staged.claudeDir(a.toId))
            moveIfThere(File(slot, "json"), staged.claudeJson(a.toId))
            if (a.kind == MergeKind.Matched) keepPhoneLogin(a.toId)
        }
        hold.deleteRecursively()
    }

    /** The backup's `.claude.json` comes back with the phone's login fields, and the phone's credentials stay. */
    private fun keepPhoneLogin(id: String) {
        File(staged.claudeDir(id), ".credentials.json").delete()
        val target = staged.claudeJson(id)
        val theirs = AccountFiles.readJson(target) ?: return
        val ours = AccountFiles.readJson(phone.claudeJson(id))
        LOGIN_KEYS.forEach { key ->
            theirs.remove(key)
            if (ours != null && ours.has(key)) theirs.put(key, ours.get(key))
        }
        // MCP servers only on the phone stay; the backup's win where both have one.
        val mine = ours?.optJSONObject("mcpServers")
        if (mine != null) {
            val servers = theirs.optJSONObject("mcpServers") ?: JSONObject()
            mine.keys().forEach { if (!servers.has(it)) servers.put(it, mine.get(it)) }
            theirs.put("mcpServers", servers)
        }
        target.writeText(theirs.toString(2) + "\n")
    }

    private fun writeClaudeList(plan: AccountPlan, backup: List<ClaudeSide>) {
        val names = backup.associateBy { it.id }
        val restored = plan.claude.map { it.toId to (names[it.fromId]?.name ?: it.toId) }
        val kept = phone.claudeAccounts().filter { p -> restored.none { it.first == p.id } }.map { it.id to it.name }
        val all = restored + kept
        // Claude's first account comes first, as it does on a phone that never had a backup.
        val ordered = all.sortedBy { if (it.first == AccountFiles.MAIN_CLAUDE_ID) 0 else 1 }
        writeList(staged.claudeList, plan.claudeDefault, ordered.map { JSONObject().put("id", it.first).put("name", it.second) })
    }

    private fun reapplyDefaults(plan: AccountPlan) {
        (plan.claude.map { it.toId }).distinct().forEach { id ->
            val settings = File(staged.claudeDir(id), "settings.json")
            if (Files.isRegularFile(settings.toPath(), LinkOption.NOFOLLOW_LINKS)) ClaudeDefaults.apply(settings)
        }
    }

    // ---- GitHub ----

    private fun mergeGitHub(plan: AccountPlan) {
        val hold = File(work, "remap-github").apply { deleteRecursively(); mkdirs() }
        plan.github.forEachIndexed { i, a -> moveIfThere(staged.githubDir(a.fromId), File(hold, "$i")) }
        plan.github.forEachIndexed { i, a ->
            val dir = staged.githubDir(a.toId)
            moveIfThere(File(hold, "$i"), dir)
            val config = File(dir, "gitconfig")
            if (a.fromId != a.toId && config.isFile) {
                // Its git config names its own key's path: <id>/id_ed25519.
                config.writeText(config.readText().replace("/github/${a.fromId}/", "/github/${a.toId}/"))
            }
            if (a.kind == MergeKind.Matched) {
                val onPhone = phone.githubDir(a.toId)
                // The phone's login and key stay, by simply not bringing the backup's along.
                if (File(onPhone, "gh").exists()) File(dir, "gh").deleteRecursively()
                if (File(onPhone, "id_ed25519").exists()) {
                    File(dir, "id_ed25519").delete()
                    File(dir, "id_ed25519.pub").delete()
                }
            }
        }
        hold.deleteRecursively()
    }

    private fun writeGitHubList(plan: AccountPlan, backup: List<JSONObject>) {
        val phoneRaw = rawAccounts(phone.githubList)
        val restored = plan.github.mapNotNull { a ->
            val theirs = backup.firstOrNull { it.optString("id") == a.fromId } ?: return@mapNotNull null
            val ours = phoneRaw.firstOrNull { it.optString("id") == a.toId }
            // The commit identity is config, so it comes back; the login and key title are the phone's.
            JSONObject(theirs.toString()).put("id", a.toId).apply {
                if (ours != null && a.kind == MergeKind.Matched) {
                    put("login", ours.optString("login"))
                    put("keyTitle", ours.optString("keyTitle"))
                }
            }
        }
        val ids = restored.map { it.optString("id") }
        val kept = phoneRaw.filter { it.optString("id") !in ids }
        if (restored.isEmpty() && kept.isEmpty()) return
        writeList(staged.githubList, plan.githubDefault, restored + kept)
    }

    // ---- Dockito ----

    private fun stagedDockitoActive() = activeIn(staged) ?: staged.defaultOf(staged.dockitoList)
    private fun phoneDockitoActive() = activeIn(phone) ?: phone.defaultOf(phone.dockitoList)

    private fun activeIn(files: AccountFiles): String? {
        val store = AccountFiles.readJson(File(files.dockitoDir, "accounts.json")) ?: return null
        return store.keys().asSequence().toList().firstNotNullOfOrNull { store.optJSONObject(it)?.optString("active")?.ifBlank { null } }
    }

    /**
     * Dockito's CLI keeps every login in one folder: accounts.json (per host: `active`, `accounts`) and a file per
     * login in tokens/. The backup's store comes back as the base; the phone's logins go on top of it, so its
     * tokens win for the same email, its other logins stay and its active login stays active.
     */
    private fun mergeDockito(plan: AccountPlan) {
        val onPhone = phone.dockitoDir
        if (onPhone.isDirectory) {
            val target = staged.dockitoDir.apply { mkdirs() }
            File(onPhone, "tokens").listFiles().orEmpty().forEach { token ->
                val tokens = File(target, "tokens").apply { mkdirs() }
                val copy = File(tokens, token.name)
                token.copyTo(copy, overwrite = true)
                runCatching { Files.setPosixFilePermissions(copy.toPath(), Files.getPosixFilePermissions(token.toPath())) }
            }
            val ours = AccountFiles.readJson(File(onPhone, "accounts.json"))
            if (ours != null) {
                val merged = AccountFiles.readJson(File(target, "accounts.json")) ?: JSONObject()
                ours.keys().forEach { host ->
                    val mine = ours.optJSONObject(host) ?: return@forEach
                    val theirs = merged.optJSONObject(host)
                    merged.put(host, if (theirs == null) mine else mergeHost(theirs, mine))
                }
                File(target, "accounts.json").writeText(merged.toString(2) + "\n")
            }
        }
        // The app's copy of the list, refreshed from the CLI whenever Dockito's tool page opens.
        val phoneRaw = rawAccounts(phone.dockitoList)
        val backupRaw = rawAccounts(staged.dockitoList)
        val byEmail = linkedMapOf<String, JSONObject>()
        (backupRaw + phoneRaw).forEach { o -> o.optString("email").lowercase().ifBlank { null }?.let { byEmail[it] = o } }
        if (byEmail.isNotEmpty()) writeList(staged.dockitoList, plan.dockitoDefault, byEmail.values.toList())
    }

    private fun mergeHost(theirs: JSONObject, mine: JSONObject): JSONObject {
        val byEmail = linkedMapOf<String, JSONObject>()
        listOf(theirs, mine).forEach { side ->
            val logins = side.optJSONArray("accounts") ?: JSONArray()
            (0 until logins.length()).mapNotNull(logins::optJSONObject).forEach { byEmail[it.optString("email").lowercase()] = it }
        }
        val active = mine.optString("active").ifBlank { theirs.optString("active") }
        return JSONObject().put("active", active).put("accounts", JSONArray(byEmail.values.toList()))
    }

    // ---- Bindings and workspaces ----

    private fun writeBindings(plan: AccountPlan) {
        val merged = AccountMerge.bindings(plan, readBindings(staged.bindings), readBindings(phone.bindings), staged.workspaceList().map { it.path })
        if (merged.isEmpty()) return
        val json = JSONObject().put("bindings", JSONArray(merged.map {
            JSONObject().put("folder", it.folder)
                .put("claude", it.claude ?: JSONObject.NULL)
                .put("github", it.github ?: JSONObject.NULL)
                .put("dockito", it.dockito ?: JSONObject.NULL)
        }))
        write(staged.bindings, json)
    }

    private fun readBindings(file: File): List<AccountMerge.Binding> {
        val array = AccountFiles.readJson(file)?.optJSONArray("bindings") ?: return emptyList()
        fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).ifBlank { null }
        return (0 until array.length()).mapNotNull(array::optJSONObject).mapNotNull { o ->
            o.text("folder")?.let { AccountMerge.Binding(it, o.text("claude"), o.text("github"), o.text("dockito")) }
        }
    }

    /** Workspaces on the phone and not in the backup stay. Where both have one, the backup's name wins. */
    private fun writeWorkspaces() {
        val backup = staged.workspaceList()
        val paths = backup.map { it.path }.toSet()
        val all = (backup + phone.workspaceList().filter { it.path !in paths }).sortedBy { it.path }
        if (all.isEmpty()) return
        write(staged.workspaces, JSONObject().put("workspaces", JSONArray(all.map { JSONObject().put("path", it.path).put("name", it.name) })))
    }

    // ---- Files ----

    private fun rawAccounts(file: File): List<JSONObject> {
        val array = AccountFiles.readJson(file)?.optJSONArray("accounts") ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }

    private fun writeList(file: File, default: String?, accounts: List<JSONObject>) =
        write(file, JSONObject().put("default", default ?: JSONObject.NULL).put("accounts", JSONArray(accounts)))

    private fun write(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
    }

    private fun moveIfThere(from: File, to: File) {
        if (!Files.exists(from.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw java.io.IOException("Couldn't arrange the staged accounts.")
    }

    private companion object {
        /** The fields of `.claude.json` that say who is signed in. */
        val LOGIN_KEYS = listOf("oauthAccount", "userID", "primaryApiKey", "customApiKeyResponses", "hasAvailableSubscription")
    }
}
