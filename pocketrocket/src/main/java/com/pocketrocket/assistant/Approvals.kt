package com.pocketrocket.assistant

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import com.pocketrocket.notifications.RocketNotifier
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One approval at a time on the screen [ApprovalActivity] shows. The `ask_to_*` tools call [confirm]; a chat's fingerprint Allow calls
 * [allowPermission], and Rocket's own requests [askPermission]. Being ignored for [DEADLINE_MS] is a no. Everything a session is told goes through
 * `answerPermissionIfOpen`, so an answer can't land on a later request.
 */
object Approvals {
    private const val DEADLINE_MS = 60_000L

    /** The approval on the screen now. [listen]: it may speak and hear; typed requests don't. */
    class Open(val approval: Approval, val listen: Boolean) {
        internal val answer = CompletableDeferred<Answered>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val turn = Mutex()
    private val _open = MutableStateFlow<Open?>(null)
    val open: StateFlow<Open?> = _open.asStateFlow()

    /** For Rocket's own actions. True only if he said yes, or gave a fingerprint where one is needed. Nothing else counts. */
    suspend fun confirm(context: Context, action: RocketAction, listen: Boolean = true): Boolean = confirmOutcome(context, action, listen).allowed

    /** As [confirm], with how it ended. [Answered.CantApprove] means it was never shown. */
    suspend fun confirmOutcome(context: Context, action: RocketAction, listen: Boolean = true): Answered {
        val app = context.applicationContext
        val approval = ConfirmGate.ofAction(action)
        val answered = ask(app, listen, build = { approval }, gone = null)
        ActivityLog.add(app, ApprovalWords.logLine(answered, approval.record), ApprovalWords.logHow(answered), approval.detail)
        return answered
    }

    /** Asks about [requestId] and answers it if he says so. Stale or already answered: nothing happens. */
    fun allowPermission(context: Context, sessionId: String, requestId: String, listen: Boolean) {
        val app = context.applicationContext
        val already = _open.value?.approval
        if (already?.requestId == requestId) return show(app)
        scope.launch { askPermission(app, sessionId, requestId, listen) }
    }

    /** Asks about [requestId], answers it if he says so, and says how it ended. Claude has no way to answer it. */
    suspend fun askPermission(context: Context, sessionId: String, requestId: String, listen: Boolean): Answered {
        val session = SessionManager.get(sessionId) ?: return Answered.Gone
        return askPermission(context, session, requestId, listen)
    }

    /** As above, for a session in no list: Rocket's own, whose risky commands its hook turns into requests (decision 61). */
    suspend fun askPermission(context: Context, session: Session, requestId: String, listen: Boolean): Answered {
        val app = context.applicationContext
        val sessionId = session.id
        var approval: Approval? = null
        val answered = ask(
            app, listen,
            build = {
                val state = session.state.value
                state.permission?.takeIf { it.requestId == requestId }?.let { ConfirmGate.ofPermission(state, it) }?.also { approval = it }
            },
            gone = { session.state.first { it.permission?.requestId != requestId } },
        )
        val asked = approval ?: return Answered.Gone
        if (answered == Answered.Gone) return answered
        val applied = if (answered.allowed || answered.denied) {
            session.answerPermissionIfOpen(requestId, answered.allowed, ApprovalWords.chatLine(answered, time(app)))
        } else true
        if (!applied) return Answered.Gone
        ActivityLog.add(app, ApprovalWords.logLine(answered, asked.record), ApprovalWords.logHow(answered), asked.detail, sessionId)
        return answered
    }

    /** From the approval screen. Refused, quietly, if [ConfirmGate.accepts] says this kind of answer can't approve this. */
    fun answer(context: Context, open: Open, answered: Answered) {
        if (ConfirmGate.accepts(open.approval.protection, answered, phoneIsLocked(context))) open.answer.complete(answered)
    }

    private suspend fun ask(app: Context, listen: Boolean, build: () -> Approval?, gone: (suspend () -> Unit)?): Answered = turn.withLock {
        val approval = build() ?: return@withLock Answered.Gone
        // Nothing to ask with: say no before anything is shown, so a screen that opens and closes can't be mistaken for a question.
        if (approval.protection == Protection.Fingerprint && !Fingerprint.available(app)) return@withLock Answered.CantApprove
        val open = Open(approval, listen)
        _open.value = open
        val watching = gone?.let { scope.launch { it(); open.answer.complete(Answered.Gone) } }
        show(app)
        try {
            withTimeoutOrNull(DEADLINE_MS) { open.answer.await() } ?: Answered.NoAnswer
        } finally {
            watching?.cancel()
            _open.value = null
            RocketNotifier.clearApproval(app)
        }
    }

    /** On screen where Android allows it; otherwise it leaves a notification that opens it. Android asks for the unlock before opening. */
    private fun show(app: Context) {
        val intent = Intent(app, ApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android may refuse the screen without saying so (just after leaving the app, or Xiaomi's pop-up switch): the notification follows then.
        ScreenLaunch.open(app, ApprovalActivity::class.java, intent, canTry = canOpenNow(app) && !phoneIsLocked(app)) {
            if (_open.value != null) RocketNotifier.approval(app, intent)
        }
    }

    private fun time(app: Context) = DateFormat.getTimeFormat(app).format(System.currentTimeMillis())
}
