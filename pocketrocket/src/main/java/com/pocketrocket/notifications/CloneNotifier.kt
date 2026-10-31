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
import com.pocketrocket.keepalive.CloneService
import com.pocketrocket.workspaces.CloneJob
import com.pocketrocket.workspaces.CloneProgress
import com.pocketrocket.workspaces.RepoClone
import com.pocketrocket.workspaces.Workspaces

/**
 * Clone notifications (decision 42): while git runs, "Cloning owner/repo · 42%" with a bar and Cancel (the
 * clone service's own foreground notification); when it ends, "Cloned owner/repo" or "Couldn't clone
 * owner/repo" with Retry. Tapping either opens the app on the clone: the repo's workspace when it landed,
 * the clone screen otherwise.
 */
object CloneNotifier {

    private const val CHANNEL_PROGRESS = "pocketrocket_cloning"
    private const val CHANNEL_RESULT = "pocketrocket_clone_result"

    /** The clone service's foreground notification. */
    const val PROGRESS_ID = 0x0C10_0000
    private fun resultId(jobId: Int) = 0x0C10_0000 + jobId

    private const val ACCENT = 0xFFFF8A3D.toInt()

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Cloning", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "A repo is being cloned"; setShowBadge(false) },
                NotificationChannel(CHANNEL_RESULT, "Clone finished", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "A clone finished or failed" },
            ),
        )
    }

    /** "Cloning owner/repo · 42%" with the phase underneath. [job] null: the service is just starting. */
    fun progress(context: Context, job: CloneJob?): Notification {
        val p = job?.progress as? CloneProgress.Running
        val overall = p?.let(RepoClone::overallPercent)
        val builder = builder(context, CHANNEL_PROGRESS)
            .setContentTitle(progressTitle(job?.request?.label, overall))
            .setContentText(p?.let(::phaseLine) ?: "Starting")
            .setProgress(100, overall ?: 0, overall == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        job?.let {
            builder.setContentIntent(openIntent(context, it.id))
            builder.addAction(Notification.Action.Builder(null, "Cancel", CloneService.actionIntent(context, CloneService.ACTION_CANCEL, it.id)).build())
        }
        return builder.build()
    }

    /** Posts the end of [job]: done (opens the repo) or failed (opens the error, with Retry). */
    fun result(context: Context, job: CloneJob) {
        val label = job.request.label
        val builder = builder(context, CHANNEL_RESULT)
            .setContentIntent(openIntent(context, job.id))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
        when (val p = job.progress) {
            is CloneProgress.Done -> builder.setContentTitle("Cloned $label").setContentText("In ${Workspaces.tilde(p.dir.path)}")
            is CloneProgress.Failed -> builder.setContentTitle("Couldn't clone $label")
                .setContentText(p.message)
                .setStyle(Notification.BigTextStyle().bigText(p.message))
                .addAction(Notification.Action.Builder(null, "Retry", CloneService.actionIntent(context, CloneService.ACTION_RETRY, job.id)).build())
            is CloneProgress.Running -> return
        }
        manager(context).notify(resultId(job.id), builder.build())
    }

    /** The result has been seen in the app. */
    fun clearResult(context: Context, jobId: Int) = manager(context).cancel(resultId(jobId))

    internal fun progressTitle(label: String?, overall: Int?): String =
        listOfNotNull("Cloning ${label ?: "repo"}", overall?.let { "$it%" }).joinToString(" · ")

    /** "Receiving objects · 3.2 MB/s", "Resolving deltas", "Connecting". */
    internal fun phaseLine(p: CloneProgress.Running): String = listOfNotNull(p.phase, p.speed).joinToString(" · ")

    /** Opens MainActivity on clone [jobId] ([MainActivity.EXTRA_CLONE]). */
    private fun openIntent(context: Context, jobId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CLONE, jobId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, resultId(jobId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("DEPRECATION")
    private fun builder(context: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setColor(ACCENT)

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
