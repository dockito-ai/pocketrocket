package com.pocketrocket.notifications

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.ui.chat.SessionActivity

/**
 * Allow and Deny on a "Needs you" notification, answered without opening the app. Allow on a phone that's still locked, or on
 * something that takes more than a tap, approves nothing and opens the session instead, which sits behind the lock until it's undone.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        SessionManager.init(context)
        val session = SessionManager.get(sessionId)
        if (intent.getBooleanExtra(EXTRA_ALLOW, false)) {
            val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            when (NotificationRules.onAllowTap(locked, session?.state?.value?.permission, requestId)) {
                AllowTap.OpenSession -> {
                    runCatching { context.startActivity(SessionActivity.intent(context, sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return
                }
                AllowTap.Answer -> session?.answerPermissionIfOpen(requestId, true)
                AllowTap.Stale -> {}
            }
        } else {
            // A stale notification (the question was answered or the app restarted) answers nothing.
            session?.answerPermissionIfOpen(requestId, false)
        }
        SessionNotifier.cancel(context, sessionId)
    }

    companion object {
        private const val EXTRA_SESSION = "session_id"
        private const val EXTRA_REQUEST = "request_id"
        private const val EXTRA_ALLOW = "allow"

        fun intent(context: Context, sessionId: String, requestId: String, allow: Boolean): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction("com.pocketrocket.action.ANSWER_PERMISSION")
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_REQUEST, requestId)
                .putExtra(EXTRA_ALLOW, allow)
    }
}
