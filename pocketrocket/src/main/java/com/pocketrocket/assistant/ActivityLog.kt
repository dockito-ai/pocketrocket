package com.pocketrocket.assistant

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One thing Rocket did. [text] and [how] are written by the app and hold no command; [command] stays in memory only. */
data class ActivityEntry(val at: Long, val text: String, val how: String, val command: String? = null, val sessionId: String? = null)

/**
 * What Rocket did and approved, for Settings › Assistant › Activity. Kept the way [Conversation] is: in the app's private
 * files, away from backups, with the real command in memory only and nothing logged, since commands can contain secrets.
 */
object ActivityLog {
    private const val FILE = "rocket-activity.json"

    private var flow: MutableStateFlow<List<ActivityEntry>>? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "rocket-activity").apply { isDaemon = true } }

    fun entries(context: Context): StateFlow<List<ActivityEntry>> = state(context).asStateFlow()

    fun add(context: Context, text: String, how: String, command: String? = null, sessionId: String? = null) {
        val state = state(context)
        state.update { ActivityCodec.capped(it + ActivityEntry(System.currentTimeMillis(), text, how, command, sessionId)) }
        val saved = state.value
        val file = file(context)
        writer.execute { runCatching { writeWhole(file, ActivityCodec.encode(saved)) } }
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<List<ActivityEntry>> = flow ?: MutableStateFlow(
        runCatching { ActivityCodec.decode(file(context).readText()) }.getOrDefault(emptyList()),
    ).also { flow = it }

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)
}

/** The log as JSON. A [ActivityEntry.command] never goes in; anything unreadable is dropped. */
object ActivityCodec {
    const val MAX_ENTRIES = 200

    fun capped(entries: List<ActivityEntry>) = entries.takeLast(MAX_ENTRIES)

    fun encode(entries: List<ActivityEntry>): String = JSONArray().apply {
        capped(entries).forEach { e ->
            put(JSONObject().put("at", e.at).put("text", e.text).put("how", e.how).put("session", e.sessionId ?: JSONObject.NULL))
        }
    }.toString()

    fun decode(text: String): List<ActivityEntry> {
        val all = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until all.length()).mapNotNull { i ->
            runCatching {
                val o = all.getJSONObject(i)
                ActivityEntry(o.getLong("at"), o.getString("text"), o.getString("how"), null, if (o.isNull("session")) null else o.getString("session"))
            }.getOrNull()
        }
    }
}
