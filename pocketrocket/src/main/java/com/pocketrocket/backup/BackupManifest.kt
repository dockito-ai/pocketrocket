package com.pocketrocket.backup

import org.json.JSONArray
import org.json.JSONObject

/** One line of "what goes in". [key] is one of [BackupManifest]'s PART_ constants. */
data class BackupPart(val key: String, val bytes: Long, val files: Int)

data class ManifestWorkspace(val path: String, val name: String)

/**
 * The backup's own description, always its first entry, so Restore can show what's inside after reading only
 * the start of the file. Everything here comes from an untrusted file: it is used for showing, and every
 * path in it is checked again before use.
 */
data class BackupManifest(
    val createdAt: Long,
    val appVersion: String,
    val totalBytes: Long,
    val totalFiles: Int,
    val parts: List<BackupPart>,
    val workspaces: List<ManifestWorkspace>,
    /** Folders outside ~/projects that the backup holds, as archive names. */
    val workspaceRoots: List<String>,
    /** The repos it holds, as archive names, for the replace warning. */
    val repos: List<String>,
    /** What could not go in and why, for the screen. */
    val leftOut: List<String>,
    val claude: List<ClaudeSide>,
    val github: List<GitHubSide>,
    val dockito: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("version", CONTENT_VERSION)
        .put("createdAt", createdAt)
        .put("appVersion", appVersion)
        .put("totalBytes", totalBytes)
        .put("totalFiles", totalFiles)
        .put("parts", JSONArray(parts.map { JSONObject().put("key", it.key).put("bytes", it.bytes).put("files", it.files) }))
        .put("workspaces", JSONArray(workspaces.map { JSONObject().put("path", it.path).put("name", it.name) }))
        .put("workspaceRoots", JSONArray(workspaceRoots))
        .put("repos", JSONArray(repos))
        .put("leftOut", JSONArray(leftOut))
        .put("claude", JSONArray(claude.map {
            JSONObject().put("id", it.id).put("name", it.name).put("signedIn", it.signedIn)
                .put("email", it.email ?: JSONObject.NULL).put("org", it.org ?: JSONObject.NULL)
        }))
        .put("github", JSONArray(github.map { JSONObject().put("id", it.id).put("login", it.login) }))
        .put("dockito", JSONArray(dockito))
        .toString()

    companion object {
        const val ENTRY = "manifest.json"
        const val CONTENT_VERSION = 1
        const val MAX_SIZE = 8 * 1024 * 1024

        const val PART_REPOS = "repos"
        const val PART_CLAUDE = "claude"
        const val PART_GITHUB = "github"
        const val PART_DOCKITO = "dockito"
        const val PART_LISTS = "lists"

        /** Null when [text] isn't a manifest; throws [UnsupportedBackup] when it is a newer one. */
        fun parse(text: String): BackupManifest? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (o.optInt("version") > CONTENT_VERSION) throw UnsupportedBackup()
            if (o.optInt("version") < 1) return null
            return runCatching {
                BackupManifest(
                    createdAt = o.getLong("createdAt"),
                    appVersion = o.optString("appVersion"),
                    totalBytes = o.getLong("totalBytes"),
                    totalFiles = o.getInt("totalFiles"),
                    parts = o.getJSONArray("parts").objects().map { BackupPart(it.getString("key"), it.getLong("bytes"), it.getInt("files")) },
                    workspaces = o.getJSONArray("workspaces").objects().map { ManifestWorkspace(it.getString("path"), it.getString("name")) },
                    workspaceRoots = o.getJSONArray("workspaceRoots").strings(),
                    repos = o.getJSONArray("repos").strings(),
                    leftOut = o.optJSONArray("leftOut")?.strings().orEmpty(),
                    claude = o.getJSONArray("claude").objects().map {
                        ClaudeSide(it.getString("id"), it.optString("name"), it.textOrNull("email"), it.textOrNull("org"), it.optBoolean("signedIn"))
                    },
                    github = o.getJSONArray("github").objects().map { GitHubSide(it.getString("id"), it.getString("login")) },
                    dockito = o.getJSONArray("dockito").strings(),
                )
            }.getOrNull()
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        private fun JSONObject.textOrNull(key: String) = if (isNull(key)) null else optString(key).ifBlank { null }
    }
}
