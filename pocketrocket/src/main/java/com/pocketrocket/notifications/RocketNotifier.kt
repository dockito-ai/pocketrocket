package com.pocketrocket.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketrocket.R

/** The notification that opens the approval screen when Rocket can't put it up itself. Says nothing of what's being asked. */
object RocketNotifier {
    private const val CHANNEL = "pocketrocket_rocket_needs_you"
    private const val ID = 0x0D10_0002
    private const val ACCENT = 0xFFFF8A3D.toInt()
    private const val TIMEOUT_MS = 60_000L

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Rocket needs your answer", NotificationManager.IMPORTANCE_HIGH).apply { description = "Opens the screen where you approve what Rocket asks" },
        )
    }

    @Suppress("DEPRECATION")
    fun approval(context: Context, open: Intent) {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        val notification = builder
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setColor(ACCENT)
            .setContentTitle("Rocket needs your answer")
            .setContentText("Open to see what it's asking. Ignoring this means no.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTimeoutAfter(TIMEOUT_MS) }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }

    fun clearApproval(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}
