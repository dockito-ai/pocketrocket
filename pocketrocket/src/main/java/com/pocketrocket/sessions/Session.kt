package com.pocketrocket.sessions

import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.AttachmentMessage
import com.pocketrocket.sessions.protocol.StreamEvent
import com.pocketrocket.sessions.protocol.StreamJsonParser
import com.pocketrocket.sessions.protocol.StreamJsonWriter
import com.pocketrocket.sessions.transport.ClaudeTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One conversation with Claude Code: the live [state] built from what its process prints, and the
 * process itself, which may come and go. A session restored after an app restart starts [SessionStatus.Paused]
 * with no process; [start] (or sending a message) launches one through [connect], which resumes the
 * conversation when Claude Code already has it on disk.
 */
class Session(
    initial: SessionState,
    private val connect: (SessionState) -> ClaudeTransport,
    val createdAt: Long = System.currentTimeMillis(),
    lastActiveAt: Long = createdAt,
    model: String? = null,
) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    val id: String get() = _state.value.id

    private val _alive = MutableStateFlow(false)

    /** True while a Claude Code process runs for this session. */
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    @Volatile var lastActiveAt: Long = lastActiveAt
        private set

    private val lock = Any()
    private var transport: ClaudeTransport? = null
    private val controlIds = AtomicInteger()
    private val outbox = Executors.newSingleThreadScheduledExecutor { Thread(it, "session-outbox").apply { isDaemon = true } }
    /** Rewinds sent and not answered yet, by request id; the outbox waits on them. */
    private val awaiting = ConcurrentHashMap<String, CountDownLatch>()
    /** An Edit waiting for Undo before its rewind is sent. Guarded by [lock]. */
    private var queuedRewind: QueuedRewind? = null
    /** What waits for Claude's turn to end (Edit or Retry tapped while it works). Guarded by [lock]. */
    private val turnEnded = mutableListOf<() -> Unit>()
    /** The model picked in this session (or when starting it), sent again when a new process starts. */
    @Volatile private var chosenModel: String? = model
    /** Configuration requests not answered yet, by request id. */
    private val configReplies = ConcurrentHashMap<String, CompletableDeferred<StreamEvent.ControlResponse?>>()

    /** When the current process started (0 before the first). Connectors keep appearing for a while after it. */
    @Volatile var processStartedAt: Long = 0L
        private set

    /** Starts the process unless it already runs. */
    fun start() {
        val started = synchronized(lock) {
            if (transport != null) return
            _state.update { if (it.status == SessionStatus.Paused) it.copy(status = SessionStatus.Starting) else it }
            processStartedAt = System.currentTimeMillis()
            connect(_state.value).also { transport = it; _alive.value = true }
        }
        started.start(object : ClaudeTransport.Listener {
            override fun onLine(line: String) {
                val events = StreamJsonParser.parse(line)
                if (events.isEmpty()) return
                // A configuration reply is for whoever asked: it isn't activity, and the chat doesn't change.
                (events.singleOrNull() as? StreamEvent.ControlResponse)?.takeIf { it.requestId.startsWith(SessionReducer.CONFIG_ID) }?.let { reply ->
                    configReplies.remove(reply.requestId)?.complete(reply)
                    return
                }
                val now = System.currentTimeMillis()
                lastActiveAt = now
                events.forEach { event -> _state.update { SessionReducer.onEvent(it, event) } }
                // The reducer is pure; subagents get their clock times here.
                _state.update { ShellTasks.time(SessionReducer.timeAgents(it, now), now) }
                events.forEach { (it as? StreamEvent.ControlResponse)?.let { reply -> awaiting.remove(reply.requestId)?.countDown() } }
                if (events.any(::changesContext)) refreshContext()
                runAfterTurn()
            }

            override fun onExit(code: Int, message: String?) {
                synchronized(lock) {
                    if (transport !== started) return
                    transport = null
                    _alive.value = false
                }
                _state.update { SessionReducer.onExit(stopRuns(it), code, message) }
                releaseAwaiting()
                runAfterTurn()
            }
        })
        _state.update(SessionReducer::onReady)
        reapplyControls(started)
    }

    /** A new process starts on the account's defaults; put back the model and mode chosen in this session. */
    private fun reapplyControls(transport: ClaudeTransport) {
        val ids = controlIds.incrementAndGet()
        chosenModel?.let { transport.send(StreamJsonWriter.setModel(SessionReducer.RESTORE_MODEL_ID + ids, it)) }
        _state.value.mode.takeIf { it != PermissionMode.Manual }?.let {
            transport.send(StreamJsonWriter.setPermissionMode(SessionReducer.MODE_ID + ids, it.cli))
        }
    }

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val message = text.trim()
        if (message.isEmpty() && attachments.isEmpty()) return
        // An Edit still open to Undo goes first, so this message follows its rewind.
        flushRewind()
        start()
        lastActiveAt = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        _state.update { SessionReducer.onUserMessage(it, message, uuid, attachments) }
        val to = transport
        // One queue per session keeps messages in the order they were sent, while attachments (read from disk and
        // base64-encoded, which can take a moment) are built off the caller's thread.
        outbox.execute {
            val line = if (attachments.isEmpty()) StreamJsonWriter.userMessage(message, uuid)
            else runCatching { StreamJsonWriter.userMessage(AttachmentMessage.content(message, attachments), uuid) }
                .getOrElse { StreamJsonWriter.userMessage(message + "\n\n" + AttachmentMessage.note(attachments), uuid) }
            to?.send(line)
        }
    }

    /** Records in the chat that Rocket did something: "Sent through Rocket". */
    fun noteFromRocket(line: String) = _state.update { SessionReducer.onRocketNote(it, line) }

    /** Gives the session a name of the user's choosing; blank keeps the current one. */
    fun rename(title: String) {
        val name = title.trim().take(40).ifEmpty { return }
        _state.update { it.copy(title = name) }
    }

    /** Switches the model for the next turns; [value] is one of [SessionState.models]. */
    fun setModel(value: String) {
        chosenModel = value
        control { StreamJsonWriter.setModel(SessionReducer.MODEL_ID + it, value) }
    }

    /** Changes the permission mode at once, mid-turn too; the CLI confirms and the status line follows. */
    fun setMode(mode: PermissionMode) = control { StreamJsonWriter.setPermissionMode(SessionReducer.MODE_ID + it, mode.cli) }


    /** Summarises the conversation so far to free context (/compact). */
    fun compact() = send("/compact")

    /**
     * Edit: drops [message] and everything after it from the chat at once and puts it back in the chat box. The CLI
     * is only told after [UNDO_MS] (or before the next message), since a rewind it has done cannot be taken back;
     * until then [undoEdit] can. While Claude works it is stopped first. Files are not changed.
     */
    fun edit(message: ChatItem.User) {
        val uuid = message.uuid ?: return
        afterTurn {
            flushRewind()
            _state.update { SessionReducer.onEdit(it, message) }
            if (_state.value.rewind?.uuid == uuid) {
                val timer = outbox.schedule(Runnable { flushRewind() }, UNDO_MS, TimeUnit.MILLISECONDS)
                synchronized(lock) { queuedRewind = QueuedRewind(uuid, timer) }
            }
        }
    }

    /** Takes back an Edit the CLI has not been told about yet: the messages return and the chat box is as it was. */
    fun undoEdit() {
        val queued = synchronized(lock) { queuedRewind.also { queuedRewind = null } } ?: return
        queued.timer.cancel(false)
        _state.update(SessionReducer::onEditUndone)
    }

    /** Retry: rewinds to [message] and sends it again, text and attachments. While Claude works it is stopped first. */
    fun retry(message: ChatItem.User) {
        val uuid = message.uuid ?: return
        afterTurn {
            flushRewind()
            if (_state.value.items.none { it.id == message.id }) return@afterTurn
            _state.update { SessionReducer.onRetry(it, message) }
            sendRewind(uuid)
            send(message.text, message.attachments)
        }
    }

    /** Sends the Edit waiting for Undo, if any. */
    private fun flushRewind() {
        val queued = synchronized(lock) { queuedRewind.also { queuedRewind = null } } ?: return
        queued.timer.cancel(false)
        _state.update(SessionReducer::onRewindSent)
        sendRewind(queued.uuid)
    }

    /**
     * Asks the CLI to rewind to [uuid] through the outbox, which then waits for the answer (applied to [state] by the
     * time it arrives here) before it sends anything queued after it, so a message sent next lands on the rewound
     * conversation.
     */
    private fun sendRewind(uuid: String) {
        start()
        val to = transport
        val id = SessionReducer.REWIND_ID + controlIds.incrementAndGet()
        val answered = CountDownLatch(1)
        awaiting[id] = answered
        outbox.execute {
            to?.send(StreamJsonWriter.rewindConversation(id, uuid))
            answered.await(REWIND_WAIT_S, TimeUnit.SECONDS)
            awaiting.remove(id)
        }
    }

    /** Runs [action] now, or stops Claude's turn and runs it once the turn has ended. */
    private fun afterTurn(action: () -> Unit) {
        val now = synchronized(lock) {
            if (_state.value.busy) { turnEnded += action; false } else true
        }
        if (now) action() else interrupt()
    }

    private fun runAfterTurn() {
        val ready = synchronized(lock) {
            if (turnEnded.isEmpty() || _state.value.busy) emptyList() else turnEnded.toList().also { turnEnded.clear() }
        }
        ready.forEach { it() }
    }

    /** Approves Claude's plan (ExitPlanMode); [acceptEdits] also turns on Accept edits, like the terminal's first option. */
    fun answerPlan(approve: Boolean, acceptEdits: Boolean = false) {
        val asked = _state.value.permission?.takeIf { it.isPlan } ?: return
        _state.update { SessionReducer.onPermissionAnswered(it, approve, always = approve && acceptEdits) }
        val updates = if (approve && acceptEdits) JSONArray().put(JSONObject().put("type", "setMode").put("mode", PermissionMode.AcceptEdits.cli).put("destination", "session")) else null
        transport?.send(
            StreamJsonWriter.permissionAnswer(
                asked.requestId, approve, asked.input, updates,
                denyMessage = "The user wants to keep planning. Wait for their feedback.", interrupt = true,
            ),
        )
    }

    private fun refreshContext() = transport?.send(StreamJsonWriter.getContextUsage(SessionReducer.CONTEXT_ID + controlIds.incrementAndGet()))

    private fun changesContext(event: StreamEvent) = when (event) {
        is StreamEvent.Result, is StreamEvent.Compacted -> true
        is StreamEvent.ControlResponse -> event.response != null &&
            (event.requestId == SessionReducer.INIT_ID || event.requestId.startsWith(SessionReducer.MODEL_ID) || event.requestId.startsWith(SessionReducer.REWIND_ID))
        else -> false
    }

    /** Sends a control request, starting the process first if needed (its lines wait until it is up). */
    private fun control(line: (Int) -> String) {
        start()
        transport?.send(line(controlIds.incrementAndGet()))
    }

    /**
     * A configuration control request on the running process, answered to the caller and invisible to the chat. Unlike
     * the controls above it never starts a process. Null when none runs, or no answer came within [timeoutMs].
     */
    suspend fun configRequest(subtype: String, fields: Map<String, Any>, timeoutMs: Long): StreamEvent.ControlResponse? {
        val to = synchronized(lock) { transport } ?: return null
        val id = SessionReducer.CONFIG_ID + controlIds.incrementAndGet()
        val answer = CompletableDeferred<StreamEvent.ControlResponse?>()
        configReplies[id] = answer
        return try {
            to.send(StreamJsonWriter.controlRequest(id, subtype, fields))
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            configReplies.remove(id)
        }
    }

    /**
     * [always] allows and saves the CLI's suggested rule ([PendingPermission.alwaysAllow]) so it stops asking.
     * [byRocket]: the chat's line saying Rocket gave this answer.
     */
    fun answerPermission(allow: Boolean, always: Boolean = false, byRocket: String? = null) {
        val asked = _state.value.permission ?: return
        val updates = if (allow && always) asked.alwaysAllow?.updates else null
        _state.update { SessionReducer.onPermissionAnswered(it, allow, always = updates != null, byRocket = byRocket) }
        transport?.send(StreamJsonWriter.permissionAnswer(asked.requestId, allow, asked.input, updates))
    }

    /** Answers only if [requestId] is still the open question (a notification may be stale). */
    fun answerPermissionIfOpen(requestId: String, allow: Boolean, byRocket: String? = null): Boolean {
        if (_state.value.permission?.requestId != requestId) return false
        answerPermission(allow, byRocket = byRocket)
        return true
    }

    /** Subagents and shells end with the process. */
    private fun stopRuns(state: SessionState): SessionState {
        val now = System.currentTimeMillis()
        return ShellTasks.stopAll(SessionReducer.stopAgents(state, now), now)
    }

    /** Stops shell task [taskId] (the Background sheet's Stop). Nothing to do when no process runs: its tasks went with it. */
    fun stopTask(taskId: String) {
        transport?.send(StreamJsonWriter.controlRequest(SessionReducer.TASK_ID + controlIds.incrementAndGet(), "stop_task", mapOf("task_id" to taskId)))
    }

    /** Moves the foreground Bash call [toolUseId] to the background (ctrl+b in the terminal); the turn goes on without waiting. */
    fun moveToBackground(toolUseId: String) {
        transport?.send(StreamJsonWriter.controlRequest(SessionReducer.TASK_ID + controlIds.incrementAndGet(), "background_tasks", mapOf("tool_use_id" to toolUseId)))
    }

    fun interrupt() {
        transport?.send(StreamJsonWriter.interrupt("pr-interrupt-${controlIds.incrementAndGet()}"))
    }

    /** Queued messages go to Claude now. Through the outbox, since the CLI only acts on messages it already has. */
    fun sendNow() {
        if (_state.value.queued.isEmpty()) return
        val to = transport
        val id = "pr-interrupt-${controlIds.incrementAndGet()}"
        outbox.execute { to?.send(StreamJsonWriter.sendNow(id)) }
    }

    /** Ends the process but keeps the session, ready to resume. */
    fun pause() {
        stopProcess()
        _state.update { SessionReducer.onQueueLost(stopRuns(it)).copy(status = SessionStatus.Paused, permission = null) }
    }

    /** Ends the process for good. */
    fun close() = stopProcess()

    private fun stopProcess() {
        // An Edit the CLI was not told about yet is taken back rather than sent to a process that is going away.
        undoEdit()
        val running = synchronized(lock) {
            turnEnded.clear()
            transport.also { transport = null; _alive.value = false }
        }
        running?.close()
        releaseAwaiting()
    }

    /** No answer will come from a process that ended; let the outbox go on. */
    private fun releaseAwaiting() {
        awaiting.values.forEach { it.countDown() }
        awaiting.clear()
        configReplies.values.forEach { it.complete(null) }
        configReplies.clear()
    }

    /** Replaces the chat with history read from disk, while no process runs. */
    internal fun restoreHistory(items: List<ChatItem>) {
        if (items.isEmpty()) return
        _state.update { if (it.items.isEmpty() && it.status == SessionStatus.Paused) it.copy(items = items) else it }
    }

    private class QueuedRewind(val uuid: String, val timer: ScheduledFuture<*>)

    private companion object {
        /** How long an Edit stays open to Undo before the CLI is told. */
        const val UNDO_MS = 4_000L
        /** Longest the outbox holds later messages for a rewind's answer. */
        const val REWIND_WAIT_S = 15L
    }
}
