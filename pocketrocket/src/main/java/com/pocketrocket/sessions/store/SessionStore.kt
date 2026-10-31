package com.pocketrocket.sessions.store

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.SessionStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What survives an app restart for one session. The conversation itself lives in Claude Code's transcript. */
data class SavedSession(
    val id: String,
    val conversationId: String,
    val title: String,
    val folder: String,
    val profile: ClaudeProfile,
    val createdAt: Long,
    val lastActiveAt: Long,
    val lastStatus: SessionStatus,
    val lastLine: String,
)

/**
 * The session list as a small JSON file (`files/pocketrocket/sessions.json`). Written whole on every
 * change, through a temp file and a rename so a crash never leaves half a file.
 */
class SessionStore(private val file: File) {

    fun load(): List<SavedSession> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val sessions = JSONObject(file.readText()).optJSONArray("sessions") ?: JSONArray()
            (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(sessions: List<SavedSession>) {
        val json = JSONObject().put("version", 1).put("sessions", JSONArray(sessions.map(::toJson)))
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        temp.renameTo(file)
    }

    private fun toJson(s: SavedSession) = JSONObject()
        .put("id", s.id)
        .put("conversationId", s.conversationId)
        .put("title", s.title)
        .put("folder", s.folder)
        .put("profile", JSONObject().put("name", s.profile.name).put("configDir", s.profile.configDir ?: JSONObject.NULL))
        .put("createdAt", s.createdAt)
        .put("lastActiveAt", s.lastActiveAt)
        .put("lastStatus", s.lastStatus.name)
        .put("lastLine", s.lastLine)

    private fun fromJson(o: JSONObject): SavedSession? {
        val id = o.optString("id").ifEmpty { return null }
        val conversationId = o.optString("conversationId").ifEmpty { return null }
        val profile = o.optJSONObject("profile")
        return SavedSession(
            id = id,
            conversationId = conversationId,
            title = o.optString("title", "session"),
            folder = o.optString("folder"),
            profile = ClaudeProfile(
                profile?.optString("name")?.ifEmpty { null } ?: ClaudeProfile.Default.name,
                profile?.takeUnless { it.isNull("configDir") }?.optString("configDir")?.ifEmpty { null },
            ),
            createdAt = o.optLong("createdAt"),
            lastActiveAt = o.optLong("lastActiveAt"),
            lastStatus = runCatching { SessionStatus.valueOf(o.optString("lastStatus")) }.getOrDefault(SessionStatus.Done),
            lastLine = o.optString("lastLine"),
        )
    }
}
