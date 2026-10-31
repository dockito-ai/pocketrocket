package com.pocketrocket.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Whether PocketRocket may post notifications, and asking once. The app targets an API level below 33,
 * so Android has no runtime prompt to show for POST_NOTIFICATIONS; when notifications are off, the
 * rationale leads to the app's notification settings instead.
 */
object NotificationPermission {

    private const val PREFS = "pocketrocket_notifications"
    private const val ASKED = "asked"

    fun enabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** True when notifications are off and the user has not been asked yet. */
    fun shouldAsk(context: Context): Boolean =
        !enabled(context) && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }

    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
