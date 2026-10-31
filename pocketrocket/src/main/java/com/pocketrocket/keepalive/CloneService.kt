package com.pocketrocket.keepalive

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.pocketrocket.notifications.CloneNotifier
import com.pocketrocket.workspaces.CloneManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app's process, and so git, alive while a clone runs (decision 42): a small foreground service
 * whose notification is the clone's progress, with a partial wake lock so the screen going off doesn't
 * stall it. It watches [CloneManager.jobs], posts each result as its own notification, and stops itself
 * once no clone runs. Separate from TermuxService, whose notification belongs to sessions and terminals.
 */
class CloneService : Service() {

    private val scope = MainScope()
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastKey: String? = null
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always foreground first: Android requires it soon after startForegroundService.
        goForeground()
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_CANCEL -> CloneManager.cancel(id)
            ACTION_RETRY -> { CloneNotifier.clearResult(this, id); CloneManager.retry(this, id) }
        }
        if (watching == null) watching = scope.launch { CloneManager.jobs.collect { onJobs() } }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val notification = CloneNotifier.progress(this, CloneManager.jobs.value.firstOrNull { it.running })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(CloneNotifier.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(CloneNotifier.PROGRESS_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketrocket:clone")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_LIMIT_MS) }
        }
    }

    private fun onJobs() {
        val jobs = CloneManager.jobs.value
        jobs.filter { !it.running && announced.add(it.id) }.forEach { CloneNotifier.result(this, it) }
        val running = jobs.firstOrNull { it.running }
        if (running == null) {
            Log.i(TAG, "no clone running, stopping")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Android drops updates posted faster than a few a second; the bar only needs about one.
        val key = "${running.id} ${running.progress}"
        val now = System.currentTimeMillis()
        if (key == lastKey || now - lastPost < MIN_UPDATE_MS) return
        lastKey = key
        lastPost = now
        getSystemService(android.app.NotificationManager::class.java).notify(CloneNotifier.PROGRESS_ID, CloneNotifier.progress(this, running))
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PocketRocketClone"
        /** Jobs whose result has been posted, for the process's life (a later service must not post them again). */
        private val announced = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        const val ACTION_CANCEL = "app.pocket.clone.CANCEL"
        const val ACTION_RETRY = "app.pocket.clone.RETRY"
        private const val EXTRA_ID = "job"
        private const val MIN_UPDATE_MS = 700L
        /** A clone that runs longer than this is left to Android's normal rules. */
        private const val WAKE_LOCK_LIMIT_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, CloneService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Cancel or Retry on a clone notification. */
        fun actionIntent(context: Context, action: String, jobId: Int): PendingIntent {
            val intent = Intent(context, CloneService::class.java).setAction(action).putExtra(EXTRA_ID, jobId)
            val code = jobId * 2 + if (action == ACTION_RETRY) 1 else 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getService(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }
    }
}
