package com.pocketrocket.health

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pocketrocket.health.HealthState.NeedsAttention
import com.pocketrocket.health.HealthState.Ok
import com.pocketrocket.notifications.SessionNotifier
import com.pocketrocket.storage.SharedStorage

/** PERMISSIONS: notifications (and the two session channels), microphone, shared storage. */
internal object PermissionChecks {

    fun run(context: Context) = HealthGroup("PERMISSIONS", listOf(notifications(context), microphone(context), files(context)))

    private fun notifications(context: Context): HealthItem {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            return HealthItem("Notifications", NeedsAttention, "Off. You won’t hear when a session needs you", HealthFix.Open(SystemScreen.AppNotifications))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val off = listOf(SessionNotifier.CHANNEL_NEEDS_YOU to "Needs you", SessionNotifier.CHANNEL_FINISHED to "Finished")
                .filter { (id, _) -> manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
            if (off.isNotEmpty()) {
                val names = off.joinToString(" and ") { it.second }
                return HealthItem(
                    "Notifications", NeedsAttention, "$names ${if (off.size == 1) "channel is" else "channels are"} off",
                    HealthFix.Open(if (off.size == 1) SystemScreen.Channel(off[0].first) else SystemScreen.AppNotifications),
                )
            }
        }
        return HealthItem("Notifications", Ok, "On · Needs you and Finished channels")
    }

    private fun microphone(context: Context): HealthItem =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            HealthItem("Microphone", Ok, "Allowed for dictation")
        } else {
            HealthItem("Microphone", NeedsAttention, "Not allowed. Dictation and Rocket’s voice won’t work", HealthFix.Request(Manifest.permission.RECORD_AUDIO))
        }

    /** Asks Android for storage access; once given, the app links ~/storage itself (no terminal command). */
    private fun files(context: Context): HealthItem = when {
        !SharedStorage.granted(context) ->
            HealthItem("Files", NeedsAttention, "No access to shared storage", HealthFix.Request(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        SharedStorage.linkIfNeeded(context) -> HealthItem("Files", Ok, "Shared storage linked at ~/storage")
        // ~/storage holds files of its own (left alone) or linking failed
        else -> HealthItem("Files", NeedsAttention, "Allowed, but ~/storage isn’t linked. Move any files out of it, then Check again")
    }
}
