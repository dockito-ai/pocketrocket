package com.pocketrocket.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketrocket.MainActivity
import com.pocketrocket.R
import com.pocketrocket.backup.BackupJob
import com.pocketrocket.backup.BackupKind
import com.pocketrocket.keepalive.BackupService

/** "Backing up dockito-api · 42%" with a bar and Cancel, then "Backed up" or "Couldn't back up". Same for restore. */
object BackupNotifier {
    private const val CHANNEL_PROGRESS = "pocketrocket_backup"
    private const val CHANNEL_RESULT = "pocketrocket_backup_result"
    const val PROGRESS_ID = 0x0B10_0000
    private const val RESULT_ID = 0x0B10_0001
    private const val ACCENT = 0xFFFF8A3D.toInt()

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Backup and restore", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "A backup or restore is running"; setShowBadge(false) },
                NotificationChannel(CHANNEL_RESULT, "Backup finished", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "A backup or restore finished or failed" },
            ),
        )
    }

    fun progress(context: Context, job: BackupJob?): Notification {
        val verb = if (job?.kind == BackupKind.Restore) "Restoring" else "Backing up"
        val title = listOfNotNull("$verb${job?.item?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()}", job?.percent?.let { "$it%" }).joinToString(" · ")
        return builder(context, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(job?.steps?.detail ?: job?.steps?.steps?.lastOrNull()?.title ?: "Starting")
            .setProgress(100, job?.percent ?: 0, job?.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openIntent(context))
            .addAction(Notification.Action.Builder(null, "Cancel", BackupService.cancelIntent(context)).build())
            .build()
    }

    fun result(context: Context, job: BackupJob) {
        val restore = job.kind == BackupKind.Restore
        val builder = builder(context, CHANNEL_RESULT).setContentIntent(openIntent(context)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
        when {
            job.done -> builder.setContentTitle(if (restore) "Restored" else "Backed up")
                .setContentText(if (restore) "Open PocketRocket to carry on." else "Saved in Downloads/PocketRocket.")
            job.cancelled -> builder.setContentTitle(if (restore) "Restore cancelled" else "Backup cancelled").setContentText(job.steps.error)
            else -> builder.setContentTitle(if (restore) "Couldn't restore" else "Couldn't back up")
                .setContentText(job.steps.error)
                .setStyle(Notification.BigTextStyle().bigText(job.steps.error))
        }
        manager(context).notify(RESULT_ID, builder.build())
    }

    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, RESULT_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("DEPRECATION")
    private fun builder(context: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setColor(ACCENT)

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
