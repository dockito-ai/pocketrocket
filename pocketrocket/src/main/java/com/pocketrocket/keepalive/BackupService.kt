package com.pocketrocket.keepalive

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.pocketrocket.backup.BackupJob
import com.pocketrocket.backup.BackupManager
import com.pocketrocket.notifications.BackupNotifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a backup or restore runs, as [CloneService] does for clones: its notification is
 * the progress, with Cancel, and a partial wake lock keeps it going with the screen off. It posts the result
 * and stops itself when the job ends.
 */
class BackupService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_CANCEL) BackupManager.cancel()
        if (watching == null) watching = scope.launch { BackupManager.job.collect(::onJob) }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        BackupNotifier.init(this)
        val notification = BackupNotifier.progress(this, BackupManager.job.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(BackupNotifier.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(BackupNotifier.PROGRESS_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketrocket:backup")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_LIMIT_MS) }
        }
    }

    private fun onJob(job: BackupJob?) {
        if (job?.running == true) {
            val now = System.currentTimeMillis()
            if (now - lastPost < MIN_UPDATE_MS) return
            lastPost = now
            getSystemService(NotificationManager::class.java).notify(BackupNotifier.PROGRESS_ID, BackupNotifier.progress(this, job))
            return
        }
        if (job != null) BackupNotifier.result(this, job)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.pocket.backup.CANCEL"
        private const val MIN_UPDATE_MS = 700L
        private const val WAKE_LOCK_LIMIT_MS = 4 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, BackupService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancelIntent(context: Context): PendingIntent {
            val intent = Intent(context, BackupService::class.java).setAction(ACTION_CANCEL)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }
    }
}
