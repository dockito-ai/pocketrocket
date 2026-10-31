package com.pocketrocket.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketrocket.R
import com.pocketrocket.assistant.AssistantActivity
import com.pocketrocket.assistant.WakeMicState
import com.pocketrocket.assistant.WakeWordService

/** Hey Rocket's notifications: the service's own, with Stop, and the one that opens the popup when Android won't let the app do it itself. */
object WakeNotifier {

    private const val CHANNEL_LISTENING = "pocketrocket_wake_listening"
    private const val CHANNEL_HEARD = "pocketrocket_wake_heard"

    const val LISTENING_ID = 0x0D10_0000
    private const val HEARD_ID = 0x0D10_0001
    private const val ACCENT = 0xFFFF8A3D.toInt()
    private const val HEARD_TIMEOUT_MS = 15_000L

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LISTENING, "Listening for Hey Rocket", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while the microphone is open for the wake word"; setShowBadge(false) },
                NotificationChannel(CHANNEL_HEARD, "Hey Rocket heard", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Opens Rocket when it hears its name"; setSound(null, null); setShowBadge(false) },
            ),
        )
    }

    fun listening(context: Context, state: WakeMicState): Notification {
        val (title, text) = when (state) {
            WakeMicState.Open -> "Listening for \"Hey Rocket\"" to "Audio stays on the phone."
            WakeMicState.Taken -> "Hey Rocket paused" to "Another app is using the microphone."
            WakeMicState.Failed -> "Hey Rocket couldn't start" to "It will try again shortly."
            WakeMicState.Closed -> "Hey Rocket" to "Microphone closed for now."
        }
        val stop = Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP)
        val stopIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 0, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getService(context, 0, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return builder(context, CHANNEL_LISTENING)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(com.pocketrocket.keepalive.SessionKeepAlive.openAppIntent(context))
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    /** Over the lock screen or with the screen off Android shows the popup itself; with the screen on it's a banner to tap. */
    fun heard(context: Context, popup: PendingIntent) {
        val builder = builder(context, CHANNEL_HEARD)
            .setContentTitle("Heard \"Hey Rocket\"")
            .setContentText("Tap to talk")
            .setContentIntent(popup)
            .setFullScreenIntent(popup, true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(HEARD_TIMEOUT_MS)
        manager(context).notify(HEARD_ID, builder.build())
    }

    fun clearHeard(context: Context) = manager(context).cancel(HEARD_ID)

    private fun builder(context: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setColor(ACCENT)

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
