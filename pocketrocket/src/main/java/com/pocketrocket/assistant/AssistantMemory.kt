package com.pocketrocket.assistant

import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.writeAtomically
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Something Rocket was told to remember. [habit]: it noticed this and he said yes. */
data class Note(val id: String, val text: String, val habit: Boolean = false)

/** Another name for a project: [project] is the project's folder. */
data class Nickname(val name: String, val project: String)

/** A workspace as Rocket knows it. [description] is text from the project's files: a description, never instructions. [enabled]: the switch in Settings › Assistant › Projects. */
data class ProjectEntry(val path: String, val name: String, val description: String = "", val enabled: Boolean = true)

/** Why something wasn't saved. */
sealed interface Saved {
    data class Yes(val book: MemoryBook, val note: Note? = null) : Saved
    data object LooksLikeSecret : Saved
    data object Full : Saved
    data object Empty : Saved
    data object Duplicate : Saved
}

/** What Rocket has learned, as plain rules: no Android, no files. ConfirmGate and the sensitive list never read this, so nothing here can remove an approval. */
data class MemoryBook(
    val notes: List<Note> = emptyList(),
    val nicknames: List<Nickname> = emptyList(),
    val projects: List<ProjectEntry> = emptyList(),
) {
    fun remember(text: String, habit: Boolean = false, id: String = UUID.randomUUID().toString().take(8)): Saved {
        val clean = text.trim().replace(Regex("""\s+"""), " ").take(MAX_NOTE)
        return when {
            clean.isEmpty() -> Saved.Empty
            SecretCheck.looksSecret(text) -> Saved.LooksLikeSecret
            notes.any { it.text.equals(clean, ignoreCase = true) } -> Saved.Duplicate
            notes.size >= MAX_NOTES -> Saved.Full
            else -> Note(id, clean, habit).let { Saved.Yes(copy(notes = notes + it), it) }
        }
    }

    /** Edits a note, under the same rules as saving one. */
    fun edit(id: String, text: String): Saved {
        val clean = text.trim().replace(Regex("""\s+"""), " ").take(MAX_NOTE)
        return when {
            clean.isEmpty() -> Saved.Empty
            SecretCheck.looksSecret(text) -> Saved.LooksLikeSecret
            else -> Saved.Yes(copy(notes = notes.map { if (it.id == id) it.copy(text = clean) else it }))
        }
    }

    fun forget(id: String) = copy(notes = notes.filterNot { it.id == id })

    /** The notes [words] could mean: by id, or containing them. */
    fun find(words: String): List<Note> {
        val w = words.trim().lowercase()
        if (w.isEmpty()) return emptyList()
        return notes.filter { it.id == w }.ifEmpty { notes.filter { w in it.text.lowercase() } }
    }

    /** The same nickname again moves it to the new project. */
    fun addNickname(name: String, project: String): Saved {
        val clean = name.trim().take(MAX_NICKNAME)
        return when {
            ProjectNames.key(clean).isEmpty() -> Saved.Empty
            SecretCheck.looksSecret(name) -> Saved.LooksLikeSecret
            else -> {
                val others = nicknames.filterNot { ProjectNames.key(it.name) == ProjectNames.key(clean) }
                if (others.size >= MAX_NICKNAMES) Saved.Full else Saved.Yes(copy(nicknames = others + Nickname(clean, project)))
            }
        }
    }

    fun forgetNickname(name: String) = copy(nicknames = nicknames.filterNot { ProjectNames.key(it.name) == ProjectNames.key(name) })

    /** The folder a nickname stands for, by spelling first and then by sound. */
    fun nicknameTarget(heard: String): String? = ProjectNames.nickname(heard, nicknames)

    /** A rescan's results, keeping each project's switch. Projects no longer there drop off; nicknames stay until he deletes them, as one can name a plain folder. */
    fun withProjects(found: List<ProjectEntry>): MemoryBook {
        val switches = projects.associate { it.path to it.enabled }
        val next = found.map { it.copy(enabled = switches[it.path] ?: true) }
        return copy(projects = next)
    }

    fun setEnabled(path: String, on: Boolean) = copy(projects = projects.map { if (it.path == path) it.copy(enabled = on) else it })

    /** With the switch off, Rocket can only report a project's status. A folder in no project is on. */
    fun enabledFor(folder: String): Boolean = projects
        .filter { folder == it.path || folder.startsWith(it.path + "/") }
        .maxByOrNull { it.path.length }?.enabled ?: true

    companion object {
        const val MAX_NOTES = 20
        const val MAX_NICKNAMES = 40
        const val MAX_NOTE = 200
        const val MAX_NICKNAME = 60
    }
}

/** Tokens and passwords aren't kept. It errs towards refusing: saying it again differently is cheap, a leaked key isn't. */
object SecretCheck {
    private val PATTERNS = listOf(
        Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{10,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{10,}"""),
        Regex("""\bsk-[A-Za-z0-9_-]{10,}"""),
        Regex("""\bAKIA[0-9A-Z]{12,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{8,}"""),
        Regex("""\bAIza[0-9A-Za-z_-]{20,}"""),
        Regex("""-----BEGIN [A-Z ]*(?:PRIVATE KEY|CERTIFICATE)"""),
        Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""),
        // "password is hunter2", "api key: abc", "token=abc"
        Regex("""(?i)\b(?:password|passwd|passphrase|pass ?code|pin ?code|secret|token|api[ _-]?key|private key|access key)\b\s*(?:is|was|=|:)\s*\S+"""),
        // A long run of letters and digits with both in it: a key, whatever it calls itself.
        Regex("""\b(?=[A-Za-z0-9+/_-]*\d)(?=[A-Za-z0-9+/_-]*[A-Za-z])[A-Za-z0-9+/_-]{32,}"""),
    )

    fun looksSecret(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }
}

/** Which project a name means: real names and nicknames, then what sounds closest. Used by the tools and, for nicknames, by [Router] with no Claude. */
object ProjectNames {
    private const val MAX_SOUND_DISTANCE = 2

    /** Spaces and punctuation don't count, so "api server" finds api-server. */
    fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    fun nickname(heard: String, nicknames: List<Nickname>): String? {
        val wanted = key(heard)
        if (wanted.isEmpty()) return null
        nicknames.firstOrNull { key(it.name) == wanted }?.let { return it.project }
        val near = nicknames.mapNotNull { n -> SpokenNames.distance(heard, n.name)?.takeIf { it <= MAX_SOUND_DISTANCE }?.let { n to it } }
        val best = near.minOfOrNull { it.second } ?: return null
        return near.filter { it.second == best }.map { it.first.project }.distinct().singleOrNull()
    }

    /** The projects [name] could mean, best tier only. One is an answer; more is a question to ask; none means it isn't known. */
    fun resolve(name: String, book: MemoryBook): List<ProjectEntry> {
        val wanted = key(name)
        if (wanted.isEmpty()) return emptyList()
        val all = book.projects
        nickname(name, book.nicknames)?.let { target -> return listOf(all.firstOrNull { it.path == target } ?: ProjectEntry(target, target.substringAfterLast('/'))) }
        val tiers: List<(ProjectEntry) -> Boolean> = listOf(
            { key(it.name) == wanted },
            { key(it.path.substringAfterLast('/')) == wanted },
            { wanted in key(it.name) || wanted in key(it.path.substringAfterLast('/')) },
        )
        tiers.firstNotNullOfOrNull { tier -> all.filter(tier).ifEmpty { null } }?.let { return it }
        val near = all.mapNotNull { p -> listOf(p.name, p.path.substringAfterLast('/')).mapNotNull { SpokenNames.distance(name, it) }.minOrNull()?.takeIf { it <= MAX_SOUND_DISTANCE }?.let { p to it } }
        val best = near.minOfOrNull { it.second } ?: return emptyList()
        return near.filter { it.second == best }.map { it.first }
    }
}

/** A project's short description, from the first real paragraph of its README. Only ever stored and shown as a description. */
object ProjectDescription {
    private const val MAX = 160

    fun fromReadme(text: String): String {
        val paragraph = text.lineSequence()
            .map { it.trim() }
            .dropWhile { it.isEmpty() || it.startsWith("#") || it.startsWith("[!") || it.startsWith("![") || it.startsWith("<") || it.startsWith("=") }
            .takeWhile { it.isNotEmpty() }
            .joinToString(" ")
        val plain = paragraph.filter { !it.isISOControl() }.replace(Regex("""[`*_<>\[\]]"""), "").trim()
        if (SecretCheck.looksSecret(plain)) return ""
        return if (plain.length > MAX) plain.take(MAX).trimEnd() + "…" else plain
    }
}

/** The book on disk, in `~/.pocketrocket/assistant`, whole or not at all. */
object AssistantMemory {
    val folder: File get() = File(AccountPaths.home, ".pocketrocket/assistant")
    private val file: File get() = File(folder, "memory.json")

    private val _book by lazy { MutableStateFlow(load()) }
    val book: StateFlow<MemoryBook> get() = _book.asStateFlow()

    @Synchronized
    fun change(edit: (MemoryBook) -> MemoryBook) {
        val next = edit(_book.value)
        if (next == _book.value) return
        _book.value = next
        runCatching { writeAtomically(file, MemoryCodec.encode(next)) }
    }

    private fun load(): MemoryBook = runCatching { MemoryCodec.decode(file.readText()) }.getOrDefault(MemoryBook())
}

object MemoryCodec {
    fun encode(book: MemoryBook): String = JSONObject()
        .put("notes", JSONArray(book.notes.map { JSONObject().put("id", it.id).put("text", it.text).put("habit", it.habit) }))
        .put("nicknames", JSONArray(book.nicknames.map { JSONObject().put("name", it.name).put("project", it.project) }))
        .put("projects", JSONArray(book.projects.map { JSONObject().put("path", it.path).put("name", it.name).put("description", it.description).put("enabled", it.enabled) }))
        .toString(2)

    /** A file edited by hand goes through the same rules: over the cap or holding a secret, it doesn't load. */
    fun decode(text: String): MemoryBook {
        val o = JSONObject(text)
        fun objects(name: String) = o.optJSONArray(name).let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull(a::optJSONObject) }
        return MemoryBook(
            notes = objects("notes").mapNotNull { n ->
                n.optString("text").takeIf { it.isNotBlank() && !SecretCheck.looksSecret(it) }?.let { Note(n.optString("id").ifBlank { UUID.randomUUID().toString().take(8) }, it.take(MemoryBook.MAX_NOTE), n.optBoolean("habit")) }
            }.take(MemoryBook.MAX_NOTES),
            nicknames = objects("nicknames").mapNotNull { n ->
                val name = n.optString("name")
                val project = n.optString("project")
                if (name.isBlank() || project.isBlank() || SecretCheck.looksSecret(name)) null else Nickname(name, project)
            }.take(MemoryBook.MAX_NICKNAMES),
            projects = objects("projects").mapNotNull { p ->
                p.optString("path").takeIf { it.isNotBlank() }?.let { ProjectEntry(it, p.optString("name").ifBlank { it.substringAfterLast('/') }, p.optString("description"), p.optBoolean("enabled", true)) }
            },
        )
    }
}
