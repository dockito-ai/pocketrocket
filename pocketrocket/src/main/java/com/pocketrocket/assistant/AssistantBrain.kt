package com.pocketrocket.assistant

import android.content.Context
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.writeAtomically
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.PermissionMode
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The hidden Claude Code session anything [Router] can't answer goes to: one continuing conversation in
 * `~/.pocketrocket/assistant`, on the login and model picked in Settings. It starts on the first request that needs it, stops
 * after [IDLE_MS] and comes back with `--resume`. An ordinary session with his setup and a shell, plus [AssistantTools], in Auto
 * (decision 61, [BrainFiles.arguments]). Its hook turns risky commands into permission requests, which go to [Approvals].
 */
object AssistantBrain {
    private const val TITLE = "Rocket"
    private const val IDLE_MS = 5 * 60_000L
    private const val TURN_MS = 5 * 60_000L
    private const val ASKED_FOR_MS = 2 * 60_000L
    private const val PREFS = "pocketrocket"
    private const val KEY_CONVERSATION = "assistant_brain_conversation"
    private const val KEY_ACCOUNT = "assistant_brain_account"
    private val LIMIT = Regex("""(?i)\b(?:limit|usage)\b""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turn = Mutex()
    private val _session = MutableStateFlow<Session?>(null)
    /** For the memory bar. Null until it first starts in this run of the app. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    private var asker: Job? = null
    private val asking = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private var idleTimer: Job? = null
    private var running: Running? = null
    @Volatile private var askedAt = 0L
    /** Whether the turn in progress was said: its approvals may listen. */
    @Volatile var spoken: Boolean = false
        private set

    private data class Running(val account: String, val model: String)

    /** True when the next question has to wake Claude: it says "one moment". */
    val isCold: Boolean get() = _session.value?.alive?.value != true

    /** The brain's last words were a question, a moment ago. */
    val askedLast: Boolean get() = System.currentTimeMillis() - askedAt < ASKED_FOR_MS

    suspend fun ask(context: Context, question: String, spoken: Boolean): BrainAnswer = turn.withLock {
        this.spoken = spoken
        idleTimer?.cancel()
        val app = context.applicationContext
        val session = try {
            withContext(Dispatchers.IO) { ready(app).also { it.send(question) } }
        } catch (e: Exception) {
            return@withLock BrainAnswer.Failed(e.message.orEmpty(), limit = false)
        }
        val ended = withTimeoutOrNull(TURN_MS) { session.state.first { !it.busy } }
        scheduleIdle(session)
        if (ended == null) {
            session.interrupt()
            return@withLock BrainAnswer.Failed("It took too long.", limit = false)
        }
        answerIn(ended, question).also {
            askedAt = if (it is BrainAnswer.Said && it.text.trim().endsWith("?")) System.currentTimeMillis() else 0L
        }
    }

    /** Quick controls' Compact. Nothing happens if there's no conversation yet. */
    fun compact(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getString(KEY_CONVERSATION, null) == null) return
        scope.launch {
            turn.withLock {
                idleTimer?.cancel()
                val session = runCatching { ready(app).also(Session::compact) }.getOrNull() ?: return@withLock
                withTimeoutOrNull(TURN_MS) { session.state.first { !it.busy && !it.compacting } }
                scheduleIdle(session)
            }
        }
    }

    /** A new conversation: Claude's memory of this one and what's on screen both go. Saved notes stay. */
    fun startFresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            turn.withLock {
                drop()
                prefs(app).edit().remove(KEY_CONVERSATION).apply()
                askedAt = 0L
            }
        }
        Conversation.clear(app)
    }

    /** Assistant off: nothing runs. */
    fun shutdown() {
        scope.launch { turn.withLock { drop() } }
    }

    private fun answerIn(state: SessionState, question: String): BrainAnswer {
        val from = state.items.indexOfLast { it is ChatItem.User && it.text == question.trim() }
        val after = state.items.drop(from + 1)
        val said = after.filterIsInstance<ChatItem.Assistant>().joinToString("\n") { it.text }.trim()
        val error = after.filterIsInstance<ChatItem.Error>().lastOrNull()?.text
        return when {
            said.isNotEmpty() -> BrainAnswer.Said(said)
            error != null || state.status == SessionStatus.Error -> (error ?: "").let { BrainAnswer.Failed(it, limit = LIMIT.containsMatchIn(it)) }
            else -> BrainAnswer.Said("")
        }
    }

    /** The session to send to, started or still running, with today's rules and notes in its folder. */
    private fun ready(app: Context): Session {
        val settings = AssistantPrefs.settings(app).value
        val account = ClaudeAccounts.get(settings.loginId) ?: ClaudeAccounts.default
        val wanted = Running(account.id, settings.model)
        _session.value?.takeIf { running == wanted }?.let { return it }
        drop()
        val server = RocketServices.server(app)
        ProjectScan.refresh(app)
        val folder = AssistantMemory.folder.apply { mkdirs() }
        File(folder, BrainFiles.SCRATCH).mkdirs()
        writeAtomically(File(folder, "CLAUDE.md"), BrainFiles.claudeMd(AssistantMemory.book.value))
        val config = File(folder, "rocket-mcp.json")
        writePrivate(config, BrainFiles.mcpConfig(server.port, server.mcpToken))
        val hidden = SessionManager.Hidden(
            conversationFor(app, account.id), BrainFiles.arguments(config.path, AgentHook.rocketFile.path), BrainFiles.ENVIRONMENT, title = TITLE,
        )
        // Auto, so it doesn't ask him at each step (decision 61); its hook still turns anything risky into a request.
        val session = SessionManager.startClaude(app, folder, account.profile, settings.model, PermissionMode.Auto, hiddenAs = hidden)
        running = wanted
        _session.value = session
        asker = scope.launch { session.state.collect { s -> s.permission?.let { askHim(app, session, it.requestId) } } }
        return session
    }

    /**
     * A request from Rocket's own session goes to [Approvals], which shows the real command and takes a fingerprint or a yes as
     * [SensitiveActions] says. Anything but an allow is a no, so Claude hears back and the turn doesn't hang on it.
     */
    private fun askHim(app: Context, session: Session, requestId: String) {
        if (!asking.add(requestId)) return
        scope.launch(Dispatchers.Main) {
            val answered = runCatching { Approvals.askPermission(app, session, requestId, listen = spoken) }.getOrDefault(Answered.NoAnswer)
            if (!answered.allowed) session.answerPermissionIfOpen(requestId, false)
        }
    }

    /** One conversation per login (decision 44): a changed login starts a new one. */
    private fun conversationFor(app: Context, accountId: String): String {
        val p = prefs(app)
        val saved = p.getString(KEY_CONVERSATION, null)
        if (saved != null && p.getString(KEY_ACCOUNT, null) == accountId) return saved
        return UUID.randomUUID().toString().also { p.edit().putString(KEY_CONVERSATION, it).putString(KEY_ACCOUNT, accountId).apply() }
    }

    private fun scheduleIdle(session: Session) {
        idleTimer = scope.launch {
            delay(IDLE_MS)
            // Stops the process only: the next question brings it back with --resume, the context bar keeps its last reading.
            turn.withLock { if (!session.state.value.busy) session.pause() }
        }
    }

    private fun drop() {
        idleTimer?.cancel()
        asker?.cancel()
        asking.clear()
        _session.value?.let(SessionManager::dropHidden)
        _session.value = null
        running = null
    }

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Only this app's user can read it. Other processes run as that user too, which is why no tool grants anything on its own. */
internal fun writePrivate(file: File, text: String) {
    writeAtomically(file, text)
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
}
