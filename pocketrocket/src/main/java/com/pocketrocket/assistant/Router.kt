package com.pocketrocket.assistant

import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import java.io.Serializable

/** A card under a reply, for what is too long to say. Each links to its session. Serializable so the conversation survives switching tabs. */
sealed interface RocketCard : Serializable {
    /** The real text: a command, what a session is asking, or how it failed. */
    data class Detail(val heading: String, val body: String, val note: String?, val sessionId: String, val sessionTitle: String) : RocketCard

    data class Context(val percent: Int, val tokens: String, val sessionId: String, val sessionTitle: String) : RocketCard

    data class Status(val rows: List<Row>) : RocketCard {
        data class Row(val title: String, val status: SessionStatus, val sessionId: String) : Serializable
    }

    /** What changed in a session's folder (mockup A-Show). Diffs are code: in memory only, gone after a restart. */
    data class Changes(val files: List<File>, val sessionId: String, val sessionTitle: String) : RocketCard {
        /** [lines] each keep their leading `+`, `-` or space. */
        data class File(val path: String, val added: Int, val deleted: Int, val lines: List<String>) : Serializable
    }
}

/** Something a reply also does, carried out by whoever asked ([askRocket]) so the Router stays free of side effects. */
sealed interface RocketAct {
    /** Stops the turn; the session stays. No Claude, and it works on a locked phone. */
    data class Stop(val sessionId: String) : RocketAct
    /** Reads git and answers with a [RocketCard.Changes]. No Claude. */
    data class ShowChanges(val sessionId: String) : RocketAct
    /** A plain setting, as Settings changes it. No Claude. A locked phone asks for a fingerprint first. */
    data class Change(val change: SettingChange) : RocketAct
}

/** A reply, and what comes with it. */
data class Routed(val reply: Reply, val act: RocketAct? = null, val toBrain: Boolean = false)

/** Rocket's answer: [text] to show, [speech] to say, and an optional [card]. */
data class Reply(val text: String, val speech: String = Spoken.sentence(text), val card: RocketCard? = null) : Serializable

/**
 * What a locked phone may show and say (`docs/assistant.md`, Safety rule): status yes, code no. Commands, paths and output
 * only ever travel in a [RocketCard.Detail] and the speech made from it, so dropping the two leaves the plain sentence.
 */
fun Reply.forLockedPhone(): Reply {
    if (card !is RocketCard.Detail && card !is RocketCard.Changes) return this
    val hidden = "$text Unlock to see it."
    return Reply(hidden, Spoken.sentence(hidden))
}

/**
 * Answers what can be answered from session state alone, with no Claude: what's happening, what does X want, how
 * full is X. X is matched against real session and folder names, however speech recognition spelled it. Anything
 * else goes to the brain ([Routed.toBrain]), and with Claude out of reach gets an honest "not yet". Permission prompts stay with the session that asked (decision 59): "allow" and "deny" aren't
 * answered here, and no word heard here can approve anything.
 */
object Router {
    private const val CANNOT = "I can't do that yet. For now I can tell you what's happening, what a session wants, and how full one is."
    const val GONE = "That request has already gone."
    const val START_AGENT = "Start an agent"
    private const val NOTHING_WAITING = "Nothing is waiting on you."
    private const val MAX_DETAIL = 200
    private const val MAX_CARD = 2_000
    private const val MAX_SPOKEN_LIST = 3
    private const val MAX_SPOKEN_WAITING = 2

    private val OVERALL = Regex(
        """^(?:what(?:'s| is|s) (?:happening|going on|up|running|new|waiting)|status|how(?:'s| is|s) (?:it going|everything(?: going)?)|how are things(?: going)?|what needs (?:me|you|my attention|attention)|(?:does |is )?anything (?:needs? me|needs? you|waiting|for me|happening))(?: right now| now| today)?$""",
    )
    private val STATUS_OF = listOf(
        Regex("""^what(?:'s| is|s) (?:happening|going on|up) (?:with|in|on|at) (.+)$"""),
        Regex("""^status (?:of|for|on) (.+)$"""),
    )
    /** "How is the weather" isn't about a session, so this one only counts when the name is a real one. */
    private val HOW_IS = Regex("""^how(?:'s| is|s) (.+?)(?: doing| going)?$""")
    private val WANTS = listOf(
        Regex("""^what (?:does|did|do) (.+?) (?:want|need)(?: from me| me to do| to do)?$"""),
        Regex("""^what(?:'s| is|s) (.+?) (?:want|wanting|asking(?: for| me| about)?|requesting|waiting (?:for|on)|stuck on|blocked on)$"""),
    )
    private val FULL = listOf(
        Regex("""^how full is (.+)$"""),
        Regex("""^how much (?:context|memory|room|space)(?: is| does| has)?(?: left)?(?: in| on| for| of)? (.+?)(?: have| has| got| using| used| use| left)?$"""),
        Regex("""^(?:the )?context (?:of|for|on|in) (.+)$"""),
        Regex("""^(.+?)(?:'s|s)? context$"""),
    )
    private val FULL_OF_IT = Regex("""^how much (?:context|memory|room|space)(?: is)? left$""")
    private val STOP = Regex("""^(?:stop|interrupt|cancel) (.+)$""")
    private val CHANGES = listOf(
        Regex("""^(?:show(?: me)? )?what(?:'s| has| is|s)? changed(?: (?:in|on|for) (.+))?$"""),
        Regex("""^show(?: me)?(?: the)? (?:changes|diffs?)(?: (?:in|on|for) (.+))?$"""),
        Regex("""^what (?:did|has) (.+?) (?:change|changed)$"""),
    )
    /** A plain yes or no with nothing waiting may be answering something the brain just asked. */
    private val BARE = setOf("yes", "yeah", "yep", "ok", "okay", "go ahead", "do it", "no", "nope")
    private val PRONOUNS = setOf("it", "this", "that", "this one", "that one", "this session", "that session", "the session", "claude")
    private val FILE_TOOLS = setOf("Edit", "MultiEdit", "NotebookEdit", "Write", "Read")

    /** Rocket's reply to [question], from [sessions] as they are now. [locked]: the phone is locked, so no code. */
    fun reply(question: String, sessions: List<SessionState>, locked: Boolean = false, book: MemoryBook = MemoryBook()): Reply =
        route(question, sessions, locked, book).reply

    /**
     * The reply and what comes with it. [locked]: the phone is locked, so no code. [book]: nicknames, read with no Claude.
     * [brainAsked]: the brain's last words were a question, so a bare yes or no with nothing waiting answers that. It can't
     * approve anything either way: approvals are the gate's, and the brain has no way to give one.
     */
    fun route(question: String, sessions: List<SessionState>, locked: Boolean = false, book: MemoryBook = MemoryBook(), brainAsked: Boolean = false): Routed {
        val q = tidy(question)
        if (brainAsked && q in BARE) return Routed(Reply(CANNOT), toBrain = true)
        SettingPhrases.refusal(q)?.let { return Routed(Reply(it)) }
        SettingPhrases.change(q)?.let { return Routed(Reply(it.said), RocketAct.Change(it)) }
        STOP.find(q)?.let { stop(it.groupValues[1], sessions, book)?.let { routed -> return routed } }
        CHANGES.firstNotNullOfOrNull { it.find(q) }?.let { return changes(it.groupValues[1], sessions, locked, book) }
        val answer = answer(q, sessions, book) ?: return Routed(Reply(CANNOT), toBrain = true)
        return Routed(answer.let { if (locked) it.forLockedPhone() else it })
    }

    /** Null when the name isn't a session: "stop listening" isn't for here. */
    private fun stop(name: String, sessions: List<SessionState>, book: MemoryBook): Routed? {
        val busy = sessions.filter { it.busy }
        val found = if (name in PRONOUNS) busy else matches(name, sessions, book)
        return when (found.size) {
            0 -> if (name in PRONOUNS) Routed(Reply("Nothing is running.")) else null
            1 -> if (!book.enabledFor(found.single().folder)) Routed(Reply(switchedOff(found.single()))) else Routed(Reply("Stopped ${found.single().title}."), RocketAct.Stop(found.single().id))
            else -> Routed(Reply("Which one: ${titles(found)}?"))
        }
    }

    private fun changes(name: String, sessions: List<SessionState>, locked: Boolean, book: MemoryBook): Routed {
        var routed: Routed? = null
        val reply = about(name.ifEmpty { "it" }, sessions, book) { s ->
            if (!book.enabledFor(s.folder)) Reply(switchedOff(s))
            else if (locked) Reply("Unlock to see what changed in ${s.title}.")
            else Reply("Looking at what changed in ${s.title}.", speech = "").also { routed = Routed(it, RocketAct.ShowChanges(s.id)) }
        }
        return routed ?: Routed(reply)
    }

    private fun switchedOff(s: SessionState) = "${s.title} is switched off for Rocket in Settings, so I can only tell you its status."

    /** Null when it takes Claude. */
    private fun answer(q: String, sessions: List<SessionState>, book: MemoryBook): Reply? {
        WANTS.name(q)?.let { return wants(it, sessions, book) }
        if (FULL_OF_IT.matches(q)) return full("it", sessions, book)
        FULL.name(q)?.let { return full(it, sessions, book) }
        if (OVERALL.matches(q)) return overall(sessions)
        STATUS_OF.name(q)?.let { name -> return about(name, sessions, book, ::status) }
        HOW_IS.name(q)?.let { name ->
            if (name in PRONOUNS || matches(name, sessions, book).isNotEmpty()) return about(name, sessions, book, ::status)
        }
        return null
    }

    /** Questions worth a chip now: the overview, then the first request, a session's fill and what changed when there are any, then starting an agent. */
    fun suggestions(sessions: List<SessionState>): List<String> = buildList {
        add(Briefing.WHATS_HAPPENING)
        (sessions.firstOrNull { it.context != null } ?: sessions.firstOrNull())?.let { add("How full is ${it.title}?") }
        sessions.firstOrNull()?.let { add("Show what changed in ${it.title}") }
        add(START_AGENT)
    }

    /** A card of real sessions, under sentences for a few, or under the headline with only what needs you said for more. */
    private fun overall(sessions: List<SessionState>): Reply {
        if (sessions.isEmpty()) return Reply("No sessions yet. Start one from the Sessions tab.")
        val ordered = sessions.sortedBy { it.permission == null }
        val card = RocketCard.Status(ordered.map { RocketCard.Status.Row(it.title, it.status, it.id) })
        if (ordered.size <= MAX_SPOKEN_LIST) return Reply(ordered.joinToString(" ") { line(it) + "." }, card = card)
        val headline = Briefing.headline(sessions)
        val waiting = ordered.filter { it.permission != null }.take(MAX_SPOKEN_WAITING).joinToString(" ") { line(it) + "." }
        return Reply(headline, Spoken.sentence("$headline $waiting") + " The rest are on screen.", card)
    }

    private fun status(s: SessionState): Reply = when {
        s.permission != null -> Reply(line(s) + ".")
        s.status == SessionStatus.Error -> {
            val text = line(s) + "."
            if (s.lastLine.isBlank()) Reply(text)
            // What failed can quote anything, so it's shown and not read out.
            else Reply(text, Spoken.sentence("$text The details are on screen."), RocketCard.Detail("Last output", card(s.lastLine), null, s.id, s.title))
        }
        else -> Reply(line(s) + ".")
    }

    private fun line(s: SessionState): String = s.permission?.let { Briefing.request(s.title, it) } ?: "${s.title} ${Briefing.doing(s.status)}"

    private fun wants(name: String, sessions: List<SessionState>, book: MemoryBook): Reply {
        if (name in PRONOUNS) {
            val waiting = sessions.filter { it.permission != null }
            return when (waiting.size) {
                0 -> Reply(NOTHING_WAITING)
                1 -> request(waiting.single())
                else -> Reply("${waiting.size} sessions are waiting on you: ${titles(waiting)}. Which one?")
            }
        }
        return about(name, sessions, book, ::request)
    }

    /** The real request goes in a card; aloud it's the short version. */
    internal fun request(s: SessionState): Reply {
        val asked = s.permission ?: return Reply("${s.title} isn't waiting on you. It ${Briefing.doing(s.status)}.")
        val text = Briefing.request(s.title, asked) + "."
        val reason = asked.reason?.takeIf { it.isNotBlank() }?.let { "Reason: ${brief(it)}" }
        // A reason can quote anything, so with no command to show it gets the card and stays out of the sentence.
        // The whole command when it's there: the summary stops at the first line, and the rest could be anything.
        val shown = asked.fullCommand().ifBlank { asked.summary }
        val card = shown.takeIf { it.isNotBlank() }?.let { RocketCard.Detail(heading(asked.toolName), card(it), reason, s.id, s.title) }
            ?: reason?.let { RocketCard.Detail(heading(asked.toolName), it, null, s.id, s.title) }
        val onScreen = if (card != null) " It's on screen." else ""
        val speech = when {
            asked.toolName == "Bash" -> Spoken.command(shown)?.let { "${Spoken.name(s.title)} wants to $it." } ?: Spoken.sentence(text + onScreen)
            asked.toolName in FILE_TOOLS && card != null -> Spoken.sentence(text.removeSuffix(".")) + ": " + Spoken.name(asked.summary.trimEnd('/').substringAfterLast('/')) + "."
            else -> Spoken.sentence(text + onScreen)
        }
        return Reply(text = text, speech = Spoken.fit(speech), card = card)
    }

    private fun heading(tool: String) = when (tool) {
        "Bash" -> "Command"
        "ExitPlanMode" -> "Plan"
        in FILE_TOOLS -> "File"
        else -> tool
    }

    private fun full(name: String, sessions: List<SessionState>, book: MemoryBook): Reply = about(name, sessions, book) { s ->
        val used = s.context
        val window = used?.window?.takeIf { it > 0 }
        when {
            used == null && s.status == SessionStatus.Paused -> Reply("${s.title} is paused, so I don't have its context. Opening it resumes it.")
            used == null -> Reply("${s.title} hasn't reported its context yet.")
            window == null -> Reply("${s.title} has used ${SessionReducer.tokens(used.used)} tokens. Claude Code hasn't said how many fit.")
            else -> {
                val percent = (used.used * 100 / window).toInt()
                Reply(
                    "${s.title} is $percent% full.",
                    card = RocketCard.Context(percent, "${SessionReducer.tokens(used.used)} of ${SessionReducer.tokens(window)} tokens", s.id, s.title),
                )
            }
        }
    }

    /** Runs [say] on the one session [name] means, or says why it can't tell which. */
    private fun about(name: String, sessions: List<SessionState>, book: MemoryBook, say: (SessionState) -> Reply): Reply {
        if (sessions.isEmpty()) return Reply("No sessions yet. Start one from the Sessions tab.")
        if (name in PRONOUNS) return sessions.singleOrNull()?.let(say) ?: Reply("Which session: ${titles(sessions)}?")
        val found = matches(name, sessions, book)
        return when (found.size) {
            0 -> book.nicknameTarget(name)?.let { Reply("No session in ${it.substringAfterLast('/')}. You have ${titles(sessions)}.") }
                ?: Reply("I don't see a session called $name. You have ${titles(sessions)}.")
            1 -> say(found.single())
            else -> Reply("Which one: ${titles(found)}?")
        }
    }

    /** The best tier that matches: a saved nickname, a session's title, then its folder's name, then a folder on its path, then part of either name, then what sounds closest. */
    internal fun matches(name: String, sessions: List<SessionState>, book: MemoryBook = MemoryBook()): List<SessionState> {
        val cleaned = name.removePrefix("the ").removePrefix("session ").removeSuffix(" session")
        book.nicknameTarget(cleaned)?.let { target -> sessions.filter { it.folder == target || it.folder.startsWith("$target/") }.ifEmpty { null }?.let { return it } }
        val wanted = key(cleaned)
        if (wanted.isEmpty()) return emptyList()
        val tiers: List<(SessionState) -> Boolean> = listOf(
            { key(it.title) == wanted },
            { key(folderName(it)) == wanted },
            { s -> pathFolders(s).any { key(it) == wanted } },
            { wanted in key(it.title) || wanted in key(folderName(it)) },
        )
        return tiers.firstNotNullOfOrNull { tier -> sessions.filter(tier).ifEmpty { null } } ?: soundsClosest(cleaned, sessions)
    }

    private fun soundsClosest(heard: String, sessions: List<SessionState>): List<SessionState> {
        val near = sessions.mapNotNull { s ->
            listOf(s.title, folderName(s)).mapNotNull { SpokenNames.distance(heard, it) }.minOrNull()?.let { s to it }
        }
        val best = near.minOfOrNull { it.second } ?: return emptyList()
        return near.filter { it.second == best }.map { it.first }
    }

    private fun folderName(s: SessionState) = s.folder.trimEnd('/').substringAfterLast('/')

    /** The folders under the home folder (the phone's own path above it means nothing to anyone). */
    private fun pathFolders(s: SessionState) = s.folder.substringAfter("/home/", s.folder).removePrefix("~/").split('/')

    /** Spaces and punctuation don't count, so "api server" finds api-server. */
    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun titles(sessions: List<SessionState>) = sessions.joinToString(", ") { it.title }

    private fun brief(text: String): String = cut(text, MAX_DETAIL)

    private fun card(text: String): String = cut(text, MAX_CARD)

    private fun cut(text: String, max: Int): String = text.trim().let { if (it.length > max) it.take(max).trimEnd() + "…" else it }

    /** Lower case, curly quotes straightened, no closing punctuation, no calling out Rocket's name first. */
    private fun tidy(question: String): String =
        question.lowercase().replace('’', '\'').trim().trimEnd('?', '!', '.', ' ')
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^(?:hey rocket,? |rocket,? )"""), "")
            .removePrefix("please ")

    private fun Regex.name(q: String): String? = find(q)?.groupValues?.get(1)

    private fun List<Regex>.name(q: String): String? = firstNotNullOfOrNull { it.name(q) }
}
