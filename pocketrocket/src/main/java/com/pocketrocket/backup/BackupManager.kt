package com.pocketrocket.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.util.Log
import com.pocketrocket.MainActivity
import com.pocketrocket.keepalive.BackupService
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.SetupStep
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.setup.finish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class BackupKind { Backup, Restore }

/** A backup or restore running or just ended, for the screen and the notification. */
data class BackupJob(
    val kind: BackupKind,
    /** The steps, in the form [com.pocketrocket.ui.setup.StepList] draws. */
    val steps: SetupState,
    val item: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val cancelled: Boolean = false,
) {
    val running: Boolean get() = steps.phase == SetupPhase.Running
    val done: Boolean get() = steps.phase == SetupPhase.Done
    val failed: Boolean get() = steps.phase == SetupPhase.Failed
    val percent: Int? get() = steps.progress
}

/**
 * Backup and restore outlive the screen, as clones do (decision 42): the process owns the job, [BackupService]
 * keeps it in the foreground with the progress notification, and the screen and the notification read [job].
 * One at a time. A passphrase stays in memory only for the job, and is never logged or put in an intent.
 */
object BackupManager {
    private const val TAG = "PocketRocketBackup"
    private const val MIN_UPDATE_MS = 200L
    private const val RESTART_DELAY_MS = 900L
    private const val RESTART_ALARM_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)

    private val _job = MutableStateFlow<BackupJob?>(null)
    val job: StateFlow<BackupJob?> get() = _job.asStateFlow()

    private val home: File get() = AccountPaths.home

    /** Always the one file, in Downloads/PocketRocket (decision 53). */
    val file: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PocketRocket/pocketrocket.backup")

    /** Sessions with a live Claude process, idle or not. Saved, paused ones have no process and don't count. */
    fun sessionsRunning(): Int = SessionManager.sessions.value.count { it.alive.value && !it.state.value.demo }

    /** What a backup would hold right now. Slow: call it off the main thread. */
    internal fun scan(context: Context): BackupPlan = BackupScanner(home, RepoGit(context.applicationContext)::facts).scan()

    /** How much a backup of [totalBytes] may need free, against what shared storage has. */
    fun neededSpace(totalBytes: Long): Long = BackupRunner.neededFor(totalBytes)

    fun freeSpace(): Long = file.parentFile?.takeIf { it.exists() }?.usableSpace ?: Environment.getExternalStorageDirectory().usableSpace

    /** Opens a picked backup far enough to say what's in it. Throws a [BackupException] with what to show. Slow. */
    fun preview(context: Context, uri: Uri, passphrase: CharArray): RestorePreview =
        BackupRunner.preview({ open(context, uri) }, passphrase, home)

    private fun open(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't open the file.")

    /** Starts a backup. [passphrase] is wiped when it ends. False while another job runs. */
    fun backup(context: Context, passphrase: CharArray): Boolean {
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        return start(app, BackupKind.Backup, BackupRunner.BACKUP_STEPS, passphrase, onDone = { announce(app) }) { events ->
            BackupRunner.backup(file, home, passphrase, version, RepoGit(app)::facts, events, cancelled::get)
        }
    }

    /**
     * Android's file picker lists Downloads from the media database, and a file written directly isn't in it
     * until it is scanned: without this the backup is there but Restore's picker shows an empty folder.
     */
    private fun announce(app: Context) {
        MediaScannerConnection.scanFile(app, arrayOf(file.path), arrayOf("application/octet-stream"), null)
    }

    /** Starts a restore from [uri]; the app restarts when it worked. False while another job runs. */
    fun restore(context: Context, uri: Uri, passphrase: CharArray): Boolean {
        val app = context.applicationContext
        return start(app, BackupKind.Restore, BackupRunner.RESTORE_STEPS, passphrase, onDone = { restart(app) }) { events ->
            BackupRunner.restore({ open(app, uri) }, passphrase, home, ::sessionsRunning, events, cancelled::get)
        }
    }

    private fun start(app: Context, kind: BackupKind, titles: List<String>, passphrase: CharArray, onDone: suspend () -> Unit, run: (BackupEvents) -> Unit): Boolean {
        synchronized(this) {
            if (_job.value?.running == true) return false
            cancelled.set(false)
            _job.value = BackupJob(kind, SetupState(phase = SetupPhase.Running, totalSteps = titles.size))
        }
        BackupService.start(app)
        scope.launch {
            val events = Reporter(titles)
            try {
                run(events)
                _job.value = _job.value?.let { it.copy(steps = it.steps.finish(StepStatus.Done).copy(phase = SetupPhase.Done, progress = null, detail = null)) }
                onDone()
            } catch (e: BackupCancelled) {
                fail(failureText(kind, e), cancelled = true)
            } catch (e: Exception) {
                // The class only: a message can name files, and nothing about what's in them should reach the log.
                Log.w(TAG, "$kind failed: ${e.javaClass.simpleName}")
                fail(failureText(kind, e), cancelled = false)
            } finally {
                passphrase.fill('\u0000')
            }
        }
        return true
    }

    private fun fail(message: String, cancelled: Boolean) {
        _job.value = _job.value?.let {
            it.copy(steps = it.steps.finish(StepStatus.Failed).copy(phase = SetupPhase.Failed, error = message, progress = null, detail = null), cancelled = cancelled)
        }
    }

    internal fun failureText(kind: BackupKind, e: Exception): String {
        val safe = if (kind == BackupKind.Backup) "Your old backup is untouched." else "Nothing on this phone has changed."
        val why = when (e) {
            is BackupCancelled -> "Cancelled."
            is NotEnoughSpace -> "Not enough space. It needs up to ${sizeText(e.needed)} and there is ${sizeText(e.free)} free."
            is SessionsRunning -> "${if (e.count == 1) "1 session is" else "${e.count} sessions are"} running. End them first."
            is BackupException -> e.message.orEmpty()
            else -> (if (kind == BackupKind.Backup) "Couldn't back up: " else "Couldn't restore: ") + (e.message ?: "something went wrong.")
        }
        return "${endSentence(why)} $safe"
    }

    fun cancel() = cancelled.set(true)

    /** Forgets a job that ended, once it has been seen. */
    fun dismiss() {
        if (_job.value?.running != true) _job.value = null
    }

    /** Undoes a restore cut off mid-move by the process dying. Run at app start, before anything reads accounts. */
    fun recoverInterrupted() {
        val root = home.parentFile ?: return
        runCatching {
            val work = File(root, BackupRunner.WORK_DIR)
            if (work.exists() && _job.value?.running != true) {
                RestoreInstaller(work, root).recover()
                BackupRunner.clear(work)
            }
        }
    }

    /**
     * The accounts and workspaces are loaded once per process, so a restore ends with a new one, whose start also
     * regenerates env.sh and ~/.gitconfig's managed block from the merged bindings. In the background Android may
     * not let the activity open: the process ends anyway and the notification says what happened.
     */
    private suspend fun restart(app: Context) {
        delay(RESTART_DELAY_MS)
        // An activity started from this process is blocked once it's in the background (API 34+), and the process
        // is about to die, so ask the system to open it: an alarm's PendingIntent is allowed to.
        runCatching {
            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + RESTART_ALARM_MS, open), open)
        }
        delay(RESTART_DELAY_MS / 3)
        Process.killProcess(Process.myPid())
    }

    private class Reporter(private val titles: List<String>) : BackupEvents {
        private var lastPost = 0L

        override fun step(index: Int) {
            _job.value = _job.value?.let { job ->
                val steps = job.steps.finish(StepStatus.Done)
                job.copy(
                    steps = steps.copy(
                        steps = steps.steps + SetupStep(titles[index], StepStatus.Running),
                        progress = null, detail = null, stepStartedAt = System.currentTimeMillis(), lastLineAt = System.currentTimeMillis(),
                    ),
                    item = "", bytes = 0, total = 0,
                )
            }
            lastPost = 0
        }

        override fun progress(bytes: Long, total: Long, item: String) {
            val now = System.currentTimeMillis()
            if (now - lastPost < MIN_UPDATE_MS) return
            lastPost = now
            val percent = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
            val sizes = "${sizeText(bytes.coerceAtMost(total))} of ${sizeText(total)}"
            _job.value = _job.value?.let { job ->
                job.copy(
                    steps = job.steps.copy(progress = percent, detail = if (item.isEmpty()) sizes else "$item · $sizes", lastLineAt = now),
                    item = item, bytes = bytes, total = total,
                )
            }
        }
    }

    /** "8 KB", "640 MB", "1.2 GB". */
    fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

/** The step titles and where a restore can no longer be cancelled, for the screen. */
object BackupRunnerSteps {
    val backup: List<String> get() = BackupRunner.BACKUP_STEPS
    val restore: List<String> get() = BackupRunner.RESTORE_STEPS
    /** Steps started by the time files move: from here a cancel would do nothing. */
    const val RESTORE_POINT_OF_NO_RETURN = 3
}

/** Adds a full stop unless the text already ends in punctuation. */
internal fun endSentence(text: String): String {
    val t = text.trimEnd()
    return if (t.isEmpty() || t.last() in ".!?…:;") t else "$t."
}
