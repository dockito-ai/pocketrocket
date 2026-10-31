package com.pocketrocket.sessions.history

import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.AttachmentMessage
import com.pocketrocket.sessions.protocol.StreamJsonParser
import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Claude Code's own record of a conversation: `<config dir>/projects/<encoded cwd>/<session id>.jsonl`,
 * where the cwd is encoded by turning every character that is not a letter or digit into `-`
 * (`/data/data/app.pocket/files/home` -> `-data-data-app-pocket-files-home`). Checked on 2.1.312.
 *
 * Its `assistant` lines and tool-result `user` lines have the same shape as stream-json output, so they go
 * through [StreamJsonParser] and [SessionReducer] like live output and the chat looks as it did. Typed
 * prompts are `user` lines whose content is a string. Only the branch ending at the newest message is shown (a
 * rewind leaves the dropped branch in the file). Subagent (`isSidechain`) and meta lines are skipped,
 * as is everything else the file holds (attachments, API request records, queue operations).
 */
object Transcript {

    fun file(state: SessionState): File? {
        val projects = File(state.profile.configDir ?: "$TERMUX_HOME_DIR_PATH/.claude", "projects")
        val name = "${state.conversationId}.jsonl"
        val direct = File(projects, state.folder.replace(Regex("[^A-Za-z0-9]"), "-")).resolve(name)
        if (direct.isFile) return direct
        // The CLI may have encoded a different spelling of the same folder; look in every project.
        return projects.listFiles()?.map { it.resolve(name) }?.firstOrNull { it.isFile }
    }

    fun exists(state: SessionState): Boolean = file(state) != null

    /** The chat as it stood, replayed onto [state] (whose items should be empty). */
    fun load(state: SessionState): List<ChatItem> {
        val file = file(state) ?: return emptyList()
        // Every record links to the one before it (parentUuid). A rewind starts a new branch from an earlier
        // message and leaves the old branch in the file, so only the chain ending at the newest message is replayed.
        val entries = LinkedHashMap<String, Entry>()
        var leaf: String? = null
        file.forEachLine { line ->
            // Most lines are bulky records with no place in the chain; avoid parsing them.
            if (!line.contains("\"parentUuid\"")) return@forEachLine
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val uuid = json.stringOrNull("uuid") ?: return@forEachLine
            if (json.optBoolean("isSidechain")) return@forEachLine
            val type = json.optString("type")
            val message = type == "user" || type == "assistant"
            val parent = json.stringOrNull("parentUuid") ?: json.stringOrNull("logicalParentUuid")
            // isCompactSummary: the summary a compaction feeds Claude; it was never said in the chat.
            val shown = message && !json.optBoolean("isMeta") && !json.optBoolean("isCompactSummary") && !json.optBoolean("isVisibleInTranscriptOnly")
            entries[uuid] = Entry(parent, json.takeIf { shown }, line)
            if (message) leaf = uuid
        }
        val chain = HashSet<String>()
        var at = leaf
        while (at != null && chain.add(at)) at = entries[at]?.parent
        var replay = state
        entries.forEach { (uuid, entry) ->
            val json = entry.json ?: return@forEach
            if (uuid !in chain) return@forEach
            replay = if (json.optString("type") == "user") onUser(replay, json, entry.line) else onStream(replay, entry.line)
        }
        // No process runs for replayed history: a subagent with no result was cut off. Replayed ones are never clocked.
        return SessionReducer.stopAgents(replay, System.currentTimeMillis()).items
    }

    /** One record of the chain; [json] is kept only for chat messages. A compaction boundary links on by its logical parent. */
    private class Entry(val parent: String?, val json: JSONObject?, val line: String)

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun onUser(state: SessionState, json: JSONObject, line: String): SessionState {
        val content = json.optJSONObject("message")?.opt("content")
        val texts = when (content) {
            is String -> listOf(content)
            is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.map { it.optString("text") }
            else -> emptyList()
        }
        // A message sent with attachments ends in a note of their paths; show them again if the files are still there.
        // The note is always the last block, so typed text that happens to look like one stays text.
        val paths = texts.lastOrNull()?.let(AttachmentMessage::pathsIn)
        val typed = (if (paths != null) texts.dropLast(1) else texts).joinToString("\n").trim()
        if (paths != null) {
            val files = paths.mapNotNull { Attachment.fromFile(it) }
            val shown = typed.ifEmpty { if (files.isEmpty()) "(attachment no longer on this phone)" else "" }
            return SessionReducer.onUserMessage(state, shown, json.optString("uuid").ifBlank { null }, files, queue = false)
        }
        return when {
            typed.startsWith("[Request interrupted") ->
                state.copy(items = state.items + ChatItem.Notice("${state.id}-${maxOf(state.seq, state.items.size)}", "Interrupted"), seq = maxOf(state.seq, state.items.size) + 1)
            // Slash commands and their local output are stored as tagged text; they were never chat.
            typed.startsWith("<") -> state
            // The line's uuid is what a rewind targets.
            typed.isNotEmpty() -> SessionReducer.onUserMessage(state, typed, json.optString("uuid").ifBlank { null }, queue = false)
            else -> onStream(state, line)
        }
    }

    private fun onStream(state: SessionState, line: String): SessionState =
        StreamJsonParser.parse(line).fold(state) { s, event -> SessionReducer.onEvent(s, event) }
}
