package com.pocketrocket.sessions.history

import com.pocketrocket.profiles.ClaudeAccountEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A past Claude Code conversation that can be resumed. */
data class EarlierConversation(
    val conversationId: String,
    /** The folder it ran in (Claude Code's `cwd`). */
    val folder: String,
    /** The first thing typed, on one line. */
    val title: String,
    /** Last written, epoch ms. */
    val lastActive: Long,
    /** User and assistant messages, roughly as the chat shows them. */
    val messages: Int,
    val account: ClaudeAccountEntry,
)

/**
 * Lists the conversations Claude Code keeps for a folder and everything under it, from every Claude
 * account: `<config dir>/projects/<encoded cwd>/<id>.jsonl` (see [Transcript]). The same history Claude
 * Code shows on a laptop, so conversations started in a terminal are included. Reads only the start of
 * each file for the first prompt, and counts message lines without parsing them.
 */
object EarlierConversations {

    fun encode(path: String) = path.replace(Regex("[^A-Za-z0-9]"), "-")

    /** Newest first. [exclude] are conversation ids already open as sessions. */
    fun list(root: String, accounts: List<ClaudeAccountEntry>, exclude: Set<String> = emptySet(), limit: Int = 60): List<EarlierConversation> {
        val prefix = encode(root.trimEnd('/'))
        val files = accounts.flatMap { account ->
            File(account.folder, "projects").listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }.orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().map { it to account } }
        }
        return files
            .filter { (f, _) -> f.nameWithoutExtension !in exclude }
            .sortedByDescending { it.first.lastModified() }
            .asSequence()
            .mapNotNull { (f, account) -> read(f, account) }
            .filter { it.folder == root || it.folder.startsWith(root.trimEnd('/') + "/") }
            .take(limit)
            .toList()
    }

    private fun read(file: File, account: ClaudeAccountEntry): EarlierConversation? = runCatching {
        var title: String? = null
        var cwd: String? = null
        var messages = 0
        file.forEachLine { line ->
            val isUser = line.contains("\"type\":\"user\"")
            if (!isUser && !line.contains("\"type\":\"assistant\"")) return@forEachLine
            if (line.contains("\"isSidechain\":true") || line.contains("\"isMeta\":true")) return@forEachLine
            if (isUser && line.contains("\"tool_result\"")) return@forEachLine
            messages++
            if (title != null && cwd != null) return@forEachLine
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            if (cwd == null) cwd = json.optString("cwd").ifBlank { null }
            if (title == null && isUser) title = typed(json)
        }
        EarlierConversation(
            conversationId = file.nameWithoutExtension,
            folder = cwd ?: return@runCatching null,
            title = title ?: return@runCatching null,
            lastActive = file.lastModified(),
            messages = messages,
            account = account,
        )
    }.getOrNull()

    /** A typed prompt's text on one line; null for slash commands and their output. */
    private fun typed(json: JSONObject): String? {
        val text = when (val content = json.optJSONObject("message")?.opt("content")) {
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }
            else -> ""
        }.trim()
        if (text.isEmpty() || text.startsWith("<") || text.startsWith("[Request interrupted")) return null
        return text.replace(Regex("\\s+"), " ").take(120)
    }
}
