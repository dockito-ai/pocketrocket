package com.pocketrocket.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketrocket.R
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.ui.chat.SessionActivity
import java.util.Collections

/**
 * Session notifications (BRD R11): "Needs you" (high importance) when Claude asks for a permission, and
 * "Finished" (default importance) when a turn ends Done or Error. Only for sessions not on screen; one
 * notification per session, replaced as it moves on, grouped under a summary. Tapping opens that session.
 * A permission notification also carries Deny, and Allow where [NotificationRules] says it can show what's being allowed, or
 * Review where it can't. Private on the lock screen: the title stays, and what a session asked or printed doesn't.
 * Allow asks the system for the unlock first, and [NotificationActionReceiver] checks again.
 */
object SessionNotifier {

    internal const val CHANNEL_NEEDS_YOU = "pocketrocket_needs_you"
    internal const val CHANNEL_FINISHED = "pocketrocket_finished"
    private const val GROUP = "com.pocketrocket.SESSIONS"
    private const val SUMMARY_ID = 0x5E550000

    /** The session the user is looking at, if any; it gets no notifications. */
    @Volatile private var onScreen: String? = null
    private val posted = Collections.synchronizedSet(mutableSetOf<String>())

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "A session is waiting for your answer" },
                NotificationChannel(CHANNEL_FINISHED, "Finished", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "A session finished its turn or hit an error" },
            ),
        )
    }

    /** Called with every state change of a session. */
    fun onChange(context: Context, previous: SessionState, current: SessionState) {
        val was = previous.status
        val now = current.status
        val asked = now == SessionStatus.WaitingForPermission &&
            (was != SessionStatus.WaitingForPermission || previous.permission?.requestId != current.permission?.requestId)
        val running = was == SessionStatus.Working || was == SessionStatus.WaitingForPermission || was == SessionStatus.Starting
        when {
            asked -> post(context, current, needsYou = true)
            now == SessionStatus.WaitingForInput && was == SessionStatus.Working -> post(context, current, needsYou = true)
            (now == SessionStatus.Done || now == SessionStatus.Error) && running -> post(context, current, needsYou = false)
            // Answered elsewhere (in the app or another device): the question is gone.
            was == SessionStatus.WaitingForPermission && now != SessionStatus.WaitingForPermission -> cancel(context, current.id)
        }
    }

    /** The user is now looking at [sessionId] (null: at nothing); its notification goes away. */
    fun isOnScreen(sessionId: String) = onScreen == sessionId

    fun setOnScreen(context: Context, sessionId: String?) {
        onScreen = sessionId
        sessionId?.let { cancel(context, it) }
    }

    fun cancel(context: Context, sessionId: String) {
        if (!posted.remove(sessionId)) return
        val manager = manager(context)
        manager.cancel(idOf(sessionId))
        if (posted.isEmpty()) manager.cancel(SUMMARY_ID)
    }

    private fun post(context: Context, state: SessionState, needsYou: Boolean) {
        if (state.id == onScreen) return
        val manager = manager(context)
        val title = when {
            needsYou -> "${state.title} needs you"
            state.status == SessionStatus.Error -> "${state.title} hit an error"
            else -> "${state.title} is done"
        }
        val text = state.lastLine
        val builder = builder(context, if (needsYou) CHANNEL_NEEDS_YOU else CHANNEL_FINISHED, high = needsYou)
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(state.profile.name)
            .setContentIntent(openIntent(context, state.id))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(if (needsYou) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, needsYou, title))
        state.permission?.takeIf { needsYou }?.let { asked ->
            // Always Deny, Allow, Review (decision 76). Allow answers from here only what the notification fully shows;
            // otherwise the tap opens the session to approve there (NotificationRules.onAllowTap).
            builder.addAction(action(context, state.id, asked.requestId, allow = false))
            builder.addAction(action(context, state.id, asked.requestId, allow = true))
            builder.addAction(Notification.Action.Builder(null, "Review", openIntent(context, state.id)).build())
        }
        posted.add(state.id)
        manager.notify(idOf(state.id), builder.build())
        manager.notify(SUMMARY_ID, summary(context))
    }

    /** What a lock screen set to hide sensitive content shows instead. It has no buttons, so nothing is answered from it. */
    private fun publicVersion(context: Context, needsYou: Boolean, title: String): Notification =
        builder(context, if (needsYou) CHANNEL_NEEDS_YOU else CHANNEL_FINISHED, high = needsYou)
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setContentTitle(title)
            .setContentText("Unlock to see it.")
            .build()

    private fun summary(context: Context): Notification =
        builder(context, CHANNEL_FINISHED, high = false)
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setContentTitle("PocketRocket sessions")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN) }
            .build()

    private fun action(context: Context, sessionId: String, requestId: String, allow: Boolean): Notification.Action {
        val intent = NotificationActionReceiver.intent(context, sessionId, requestId, allow)
        val pending = PendingIntent.getBroadcast(
            context, idOf(sessionId) * 2 + (if (allow) 1 else 0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, if (allow) "Allow" else "Deny", pending)
        // Android 12 on: a locked phone asks for the unlock before the tap arrives. Deny needs none (docs/assistant.md, Safety rule).
        if (allow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(true)
        return action.build()
    }

    /** Opens [sessionId]'s chat; also the Open button on the service notification. */
    internal fun openIntent(context: Context, sessionId: String): PendingIntent =
        PendingIntent.getActivity(
            context, idOf(sessionId), SessionActivity.intent(context, sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @Suppress("DEPRECATION")
    private fun builder(context: Context, channel: String, high: Boolean): Notification.Builder =
        (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel)
            else Notification.Builder(context).setPriority(if (high) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
                .setDefaults(Notification.DEFAULT_ALL)
        ).setColor(ACCENT)

    /** The rocket and app name are tinted in PocketRocket's orange (PrDark.accent). */
    private const val ACCENT = 0xFFFF8A3D.toInt()

    /** Stable per session, clear of Termux's own notification ids. */
    private fun idOf(sessionId: String) = 0x5E55 + (sessionId.hashCode() and 0xFFFFF)

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
