package com.pocketrocket.sessions

import com.pocketrocket.androidbuild.AndroidBuild
import android.content.Context
import com.pocketrocket.keepalive.ProcessMemory
import com.pocketrocket.keepalive.SessionKeepAlive
import com.pocketrocket.assistant.AgentHook
import com.pocketrocket.notifications.RocketNotifier
import com.pocketrocket.notifications.SessionNotifier
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.sessions.attachments.Attachments
import com.pocketrocket.sessions.demo.DemoConversation
import com.pocketrocket.sessions.history.Transcript
import com.pocketrocket.sessions.store.SavedSession
import com.pocketrocket.sessions.store.SessionStore
import com.pocketrocket.sessions.transport.ClaudeProcessTransport
import com.pocketrocket.sessions.transport.FakeClaudeTransport
import com.pocketrocket.termux.Termux
import com.pocketrocket.workspaces.WorkspaceCli
import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every session for the life of the process (decision 14). Real sessions are saved to a
 * [SessionStore] on every change and come back [SessionStatus.Paused] after a restart; opening one resumes
 * it. While any session process runs, Termux's foreground service stays up ([SessionKeepAlive]), and
 * status changes become notifications ([SessionNotifier]).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
object SessionManager {

    /**
     * A session the app runs for itself (Rocket's brain). It isn't in [sessions], so it's in no list and gets no notification
     * or saved entry. [arguments] go on its command line in place of an agent's hook.
     */
    class Hidden(val conversationId: String, val arguments: List<String>, val environment: Map<String, String> = emptyMap(), val title: String? = null)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchers = ConcurrentHashMap<String, Job>()
    private lateinit var app: Context
    private var store: SessionStore? = null
    private val hidden = java.util.Collections.newSetFromMap(ConcurrentHashMap<Session, Boolean>())

    /** Restores saved sessions (no processes) and starts saving, notifying and keep-alive. Called once at app start. */
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        app = context.applicationContext
        val saved = SessionStore(File(app.filesDir, "pocketrocket/sessions.json"))
        store = saved
        SessionNotifier.init(app)
        RocketNotifier.init(app)
        val restored = saved.load().map(::restore)
        restored.forEach(::watch)
        _sessions.value = restored
        scope.launch(Dispatchers.IO) {
            restored.forEach { it.restoreHistory(runCatching { Transcript.load(it.state.value) }.getOrDefault(emptyList())) }
        }
        scope.launch(Dispatchers.IO) { savedSnapshots().debounce(300).collect { runCatching { saved.save(it) } } }
        scope.launch { liveSnapshots().collect { SessionKeepAlive.update(app, it) } }
        ProcessMemory.start(app, scope)
    }

    fun get(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    /**
     * Starts a real Claude Code conversation in [folder], signed in as [profile], or as the folder's Claude
     * account (its workspace's, else the default) when null. [model] (a [ModelOption.value]; null for the
     * account's default) and [mode] are applied as the process starts, like ones picked mid-session.
     * [id] is given when files were already attached for this session ([Attachments.newSessionId]).
     */
    fun startClaude(
        context: Context,
        folder: File = File(TERMUX_HOME_DIR_PATH),
        profile: ClaudeProfile? = null,
        model: String? = null,
        mode: PermissionMode = PermissionMode.Manual,
        id: String = Attachments.newSessionId(),
        hiddenAs: Hidden? = null,
    ): Session {
        init(context)
        val chosen = profile ?: AccountWiring.claudeFor(folder.path).profile
        if (hiddenAs != null) {
            val base = newState(folder.path, demo = false, chosen, hiddenAs.conversationId)
            val state = base.copy(title = hiddenAs.title ?: base.title, mode = mode, status = SessionStatus.Paused)
            return Session(state, { connect(it, hiddenAs.arguments, hiddenAs.environment) }, model = model?.takeIf { it != "default" }).also {
                hidden += it
                it.start()
            }
        }
        val state = newState(folder.path, demo = false, chosen, UUID.randomUUID().toString()).copy(id = id, mode = mode)
        val session = Session(state, { connect(it) }, model = model?.takeIf { it != "default" })
        add(session)
        session.start()
        return session
    }

    /**
     * Brings back an earlier Claude Code conversation ([conversationId], from its transcript in [folder]) as a
     * paused session with its history; opening it resumes it with `--resume`. An open session for the same
     * conversation is returned instead of a second one.
     */
    fun resumeClaude(context: Context, folder: File, profile: ClaudeProfile, conversationId: String, title: String? = null): Session {
        init(context)
        _sessions.value.firstOrNull { it.state.value.conversationId == conversationId }?.let { return it }
        val base = newState(folder.path, demo = false, profile, conversationId)
        val state = base.copy(title = title?.take(40)?.ifBlank { null } ?: base.title, status = SessionStatus.Paused)
        val session = Session(state, { connect(it) })
        add(session)
        scope.launch(Dispatchers.IO) {
            session.restoreHistory(runCatching { Transcript.load(session.state.value) }.getOrDefault(emptyList()))
        }
        return session
    }

    /** Starts a scripted conversation that needs no login; [prompt] is sent right away when given. */
    fun startDemo(conversation: DemoConversation): Session {
        val conversationId = UUID.randomUUID().toString()
        val state = newState(conversation.folder, demo = true, conversation.profile, conversationId)
        val session = Session(state, { FakeClaudeTransport(conversation.script(conversationId)) })
        add(session)
        session.start()
        conversation.prompt?.let(session::send)
        return session
    }

    /** Ends a session: kills its process and removes it from the grid and the saved list. */
    fun close(id: String) {
        val session = get(id) ?: return
        session.close()
        watchers.remove(id)?.cancel()
        SessionNotifier.cancel(app, id)
        Unseen.forget(id)
        _sessions.update { list -> list.filterNot { it.id == id } }
    }

    /** Stops every process but keeps the sessions, resumable (the Exit button on Termux's notification). */
    fun pauseAll() {
        _sessions.value.filterNot { it.state.value.demo }.forEach(Session::pause)
        hidden.forEach(Session::pause)
    }

    /** A hidden session is done with: its process ends, and it's forgotten. Its transcript stays for `--resume`. */
    fun dropHidden(session: Session) {
        session.close()
        hidden -= session
    }

    /**
     * The real process: a fresh conversation, or `--resume` when Claude Code already has it on disk.
     * An ordinary session gets the agent hook, and the note on `pocketrocket-workspace` in Claude's system prompt (decision 73).
     * Rocket's hidden session leaves the note out: it clones with its own `clone_repo` tool and delegates other work to sessions.
     */
    private fun connect(state: SessionState): ClaudeProcessTransport =
        connect(state, AgentHook.arguments(app) + listOf("--append-system-prompt", WorkspaceCli.CLAUDE_NOTE))

    private fun connect(state: SessionState, arguments: List<String>, extraEnvironment: Map<String, String> = emptyMap()): ClaudeProcessTransport {
        Termux.startForegroundService(app)
        val resume = Transcript.exists(state)
        // The session's Claude account, plus the GitHub account its folder is bound to (GH_CONFIG_DIR), and its
        // folder for pocketrocket-workspace, which takes accounts from the session's workspace (decision 73).
        val environment = state.profile.environment + AccountWiring.toolEnvFor(state.folder) + AndroidBuild.environment() +
            (WorkspaceCli.SESSION_FOLDER_ENV to state.folder) + extraEnvironment
        return ClaudeProcessTransport(app, File(state.folder), state.conversationId, resume, environment, extraArguments = arguments)
    }

    private fun restore(saved: SavedSession): Session {
        val state = SessionState(
            id = saved.id, folder = saved.folder, title = saved.title, demo = false, profile = saved.profile,
            conversationId = saved.conversationId, status = SessionStatus.Paused, preview = saved.lastLine.ifEmpty { null },
        )
        return Session(state, { connect(it) }, saved.createdAt, saved.lastActiveAt)
    }

    private fun add(session: Session) {
        watch(session)
        _sessions.update { it + session }
    }

    /** Turns each status change into a notification and an unseen mark, when it calls for one, and keeps the CLI's model list for New session. */
    private fun watch(session: Session) {
        watchers[session.id] = scope.launch {
            var previous = session.state.value
            session.state.collect { state ->
                SessionNotifier.onChange(app, previous, state)
                Unseen.onChange(previous, state)
                if (state.models != previous.models && !state.demo) ModelCatalog.remember(app, state.models)
                previous = state
            }
        }
    }

    private fun savedSnapshots(): Flow<List<SavedSession>> = _sessions.flatMapLatest { list ->
        val real = list.filterNot { it.state.value.demo }
        if (real.isEmpty()) flowOf(emptyList())
        else combine(real.map { s -> s.state.map { toSaved(s, it) } }) { it.toList() }
    }.distinctUntilChanged()

    private fun liveSnapshots(): Flow<List<SessionKeepAlive.Live>> = _sessions.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { s -> combine(s.alive, s.state) { alive, state -> SessionKeepAlive.Live(state.id, state.conversationId, alive && !state.demo, state.status, state.title, state.demo, state.runningShells, state.runningAgents) } }) { it.toList() }
    }.distinctUntilChanged()

    private fun toSaved(session: Session, state: SessionState) = SavedSession(
        id = state.id, conversationId = state.conversationId, title = state.title, folder = state.folder, profile = state.profile,
        createdAt = session.createdAt, lastActiveAt = session.lastActiveAt, lastStatus = state.status, lastLine = state.lastLine,
    )

    private fun newState(folder: String, demo: Boolean, profile: ClaudeProfile, conversationId: String): SessionState {
        val name = folder.trimEnd('/').substringAfterLast('/')
        val base = if (folder.trimEnd('/') == TERMUX_HOME_DIR_PATH) "home" else name
        val taken = _sessions.value.map { it.state.value.title }.toSet()
        val title = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        return SessionState(id = UUID.randomUUID().toString().take(8), folder = folder, title = title, demo = demo, profile = profile, conversationId = conversationId)
    }
}
