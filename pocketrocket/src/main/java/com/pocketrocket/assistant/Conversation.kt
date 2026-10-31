package com.pocketrocket.assistant

import android.content.Context
import com.pocketrocket.sessions.SessionStatus
import java.io.File
import java.io.Serializable
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One question, typed or said, and Rocket's reply; [note] says when a spoken one wasn't heard. [pending]: the real reply is on its way ([Conversation.resolve]). */
data class Turn(val question: String, val reply: Reply, val note: String? = null, val pending: Long? = null) : Serializable

/**
 * What was said and shown, one conversation for the tab and the popup, kept across restarts in the app's private files.
 * A card's real text (commands, output) stays in memory only and never reaches the disk ([ConversationCodec]).
 * Nothing here is logged: commands can contain secrets.
 */
object Conversation {
    private const val FILE = "rocket-conversation.json"

    private var flow: MutableStateFlow<List<Turn>>? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "rocket-conversation").apply { isDaemon = true } }

    fun turns(context: Context): StateFlow<List<Turn>> = state(context).asStateFlow()

    fun add(context: Context, turn: Turn) = change(context) { ConversationCodec.capped(it + turn) }

    /** Puts the real reply in the turn that was waiting for it, wherever it now is. */
    fun resolve(context: Context, pending: Long, reply: Reply) = change(context) { all ->
        all.map { if (it.pending == pending) it.copy(reply = reply, pending = null) else it }
    }

    /** Start fresh: nothing said so far stays, on screen or on disk. */
    fun clear(context: Context) = change(context) { emptyList() }

    /** The last reply wasn't said, or was cut off, for [reason]. */
    fun noteLast(context: Context, reason: String) = change(context) { all ->
        if (all.isEmpty()) all else all.dropLast(1) + all.last().copy(note = reason)
    }

    private fun change(context: Context, edit: (List<Turn>) -> List<Turn>) {
        val state = state(context)
        state.update(edit)
        val saved = state.value
        val file = file(context)
        writer.execute { runCatching { writeWhole(file, ConversationCodec.encode(saved)) } }
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<List<Turn>> = flow ?: MutableStateFlow(read(file(context))).also { flow = it }

    private fun read(file: File): List<Turn> = runCatching { ConversationCodec.decode(file.readText()) }.getOrDefault(emptyList())

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)
}

/** Whole or not at all: a kill mid-write leaves the last good copy. */
internal fun writeWhole(file: File, text: String) {
    val partial = File(file.path + ".tmp")
    partial.writeText(text)
    partial.renameTo(file)
}

/** The conversation as JSON. Anything it can't read is dropped rather than failing the whole file. */
object ConversationCodec {
    const val MAX_TURNS = 50

    fun capped(turns: List<Turn>): List<Turn> = turns.takeLast(MAX_TURNS)

    fun encode(turns: List<Turn>): String = JSONArray().apply {
        // A turn still waiting for its reply wouldn't get one after a restart.
        capped(turns).filter { it.pending == null }.forEach { turn ->
            put(
                JSONObject()
                    .put("question", turn.question)
                    .put("text", turn.reply.text)
                    .put("speech", if (turn.reply.card is RocketCard.Detail || turn.reply.card is RocketCard.Changes) "" else turn.reply.speech)
                    .put("note", turn.note ?: JSONObject.NULL)
                    .put("card", turn.reply.card?.let(::encodeCard) ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun decode(text: String): List<Turn> {
        val all = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until all.length()).mapNotNull { i -> runCatching { decodeTurn(all.getJSONObject(i)) }.getOrNull() }
    }

    private fun decodeTurn(o: JSONObject) = Turn(
        question = o.getString("question"),
        reply = Reply(o.getString("text"), o.optString("speech", ""), o.optJSONObject("card")?.let(::decodeCard)),
        note = if (o.isNull("note")) null else o.getString("note"),
    )

    /** A [RocketCard.Detail] loses its body and note, [RocketCard.Changes] becomes one with no body, and the reply its speech, which is the command in words: what a session ran or printed isn't written to disk. */
    private fun encodeCard(card: RocketCard): JSONObject = when (card) {
        is RocketCard.Detail -> JSONObject().put("kind", "detail").put("heading", card.heading).put("id", card.sessionId).put("title", card.sessionTitle)
        is RocketCard.Changes -> JSONObject().put("kind", "detail").put("heading", "Changes").put("id", card.sessionId).put("title", card.sessionTitle)
        is RocketCard.Context -> JSONObject().put("kind", "context").put("percent", card.percent).put("tokens", card.tokens).put("id", card.sessionId).put("title", card.sessionTitle)
        is RocketCard.Status -> JSONObject().put("kind", "status").put(
            "rows",
            JSONArray().apply { card.rows.forEach { put(JSONObject().put("title", it.title).put("status", it.status.name).put("id", it.sessionId)) } },
        )
    }

    private fun decodeCard(o: JSONObject): RocketCard? = when (o.optString("kind")) {
        "detail" -> RocketCard.Detail(o.getString("heading"), "", null, o.getString("id"), o.getString("title"))
        "context" -> RocketCard.Context(o.getInt("percent"), o.getString("tokens"), o.getString("id"), o.getString("title"))
        "status" -> o.getJSONArray("rows").let { rows ->
            RocketCard.Status(
                (0 until rows.length()).mapNotNull { i ->
                    val row = rows.getJSONObject(i)
                    val status = SessionStatus.entries.firstOrNull { it.name == row.getString("status") } ?: return@mapNotNull null
                    RocketCard.Status.Row(row.getString("title"), status, row.getString("id"))
                },
            )
        }
        else -> null
    }
}
