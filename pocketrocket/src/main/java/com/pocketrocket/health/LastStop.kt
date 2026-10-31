package com.pocketrocket.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * When and why Android last stopped PocketRocket (ApplicationExitInfo, Android 11+), for the Health banner.
 *
 * Two small facts are kept across process deaths: when the current and the previous process started
 * ([onProcessStart]), and how many sessions had a running Claude Code process at the last change
 * ([recordRunning]). A stop is shown only when it ended the previous process, i.e. it came after that
 * process started, so an old kill never lingers. Shankar's own swipe or force-stop is labelled as his.
 */
object LastStop {

    private const val PREFS = "pocketrocket_health"
    private const val START = "process_start"
    private const val PREVIOUS_START = "previous_process_start"
    private const val RUNNING = "running_sessions"
    private const val PAUSED_AT_START = "paused_at_start"

    /** One stop, in plain words. [byUser] when Shankar swiped the app away or force-stopped it. */
    data class Stop(val at: Long, val reason: String, val byUser: Boolean, val sessionsPaused: Int)

    @Volatile private var lastRecorded = -1

    /** From PocketRocket.init: this process started. Keeps the previous start and the sessions it had running. */
    fun onProcessStart(context: Context) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong(PREVIOUS_START, prefs.getLong(START, 0))
            .putLong(START, System.currentTimeMillis())
            .putInt(PAUSED_AT_START, prefs.getInt(RUNNING, 0))
            .putInt(RUNNING, 0)
            .apply()
        lastRecorded = 0
    }

    /** Sessions with a live process now; written only when the number changes. */
    fun recordRunning(context: Context, count: Int) {
        if (count == lastRecorded) return
        lastRecorded = count
        prefs(context).edit().putInt(RUNNING, count).apply()
    }

    /** The stop that ended the previous process, or null (none, Android 10 or older, or nothing recorded). */
    fun read(context: Context): Stop? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = prefs(context)
        val previousStart = prefs.getLong(PREVIOUS_START, 0)
        val start = prefs.getLong(START, Long.MAX_VALUE)
        if (previousStart == 0L) return null
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
        }.getOrDefault(emptyList())
        val exit = exits
            .filter { it.processName == context.packageName && it.timestamp in previousStart until start }
            .maxByOrNull { it.timestamp } ?: return null
        val (reason, byUser) = describe(exit)
        return Stop(exit.timestamp, reason, byUser, prefs.getInt(PAUSED_AT_START, 0))
    }

    /** Plain words for an exit reason, and whether it was the user's own doing. */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun describe(exit: ApplicationExitInfo): Pair<String, Boolean> {
        val why = exit.description.orEmpty().lowercase()
        return when {
            "remove task" in why || "swipe" in why -> "swiped away from recent apps" to true
            // killBackgroundProcesses (`am kill`, cleaner apps) is also USER_REQUESTED, but not Shankar's doing
            "kill background" in why || "kill all background" in why -> "background apps were cleared" to false
            exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED -> "force stop" to true
            exit.reason == ApplicationExitInfo.REASON_USER_STOPPED -> "stopped from the task manager" to true
            "battery" in why || "power" in why || "restrict" in why || "bg_restrict" in why -> "battery saver" to false
            else -> reasonWords(exit.reason, why) to false
        }
    }

    private fun reasonWords(reason: Int, why: String): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much background battery use"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "the app crashed"
        ApplicationExitInfo.REASON_ANR -> "the app stopped responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "it failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission was changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
        ApplicationExitInfo.REASON_EXIT_SELF -> "it closed itself"
        ApplicationExitInfo.REASON_SIGNALED -> "a kill signal"
        ApplicationExitInfo.REASON_FREEZER -> "frozen in the background"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the app was disabled or changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "an app update"
        else -> when {
            "kill background" in why || "kill all background" in why -> "clearing background apps"
            "cached" in why || "empty" in why || "trim" in why -> "freeing memory"
            "phantom" in why || "excessive cpu" in why -> "too many background processes"
            why.isNotBlank() -> why
            else -> "no reason given"
        }
    }

    /** "Android stopped PocketRocket at 3:12 am (battery saver). 2 sessions paused." */
    fun sentence(context: Context, stop: Stop): String {
        val at = time(context, stop.at)
        val head = if (stop.byUser) "PocketRocket was stopped at $at (${stop.reason})."
        else "Android stopped PocketRocket at $at (${stop.reason})."
        val paused = when (stop.sessionsPaused) {
            0 -> ""
            1 -> " 1 session paused."
            else -> " ${stop.sessionsPaused} sessions paused."
        }
        return head + paused
    }

    /** "Sessions were stopped last night", "… today at", "… yesterday", "… on 27 Oct". */
    fun title(stop: Stop, now: Long = System.currentTimeMillis()): String {
        val subject = if (stop.sessionsPaused > 0) "Sessions were stopped" else "PocketRocket was stopped"
        val then = Calendar.getInstance().apply { timeInMillis = stop.at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = daysBetween(then, today)
        val hour = then.get(Calendar.HOUR_OF_DAY)
        val night = hour >= 21 || hour < 6
        val `when` = when {
            night && (days == 0 && hour < 6 || days == 1 && hour >= 21) -> "last night"
            days == 0 -> "today"
            days == 1 -> "yesterday"
            else -> "on " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(stop.at))
        }
        return "$subject $`when`"
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        fun dayStart(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((dayStart(b) - dayStart(a)) / 86_400_000L).toInt()
    }

    /** The phone's local time: "3:12 am", or "03:12" on a 24-hour phone. */
    private fun time(context: Context, at: Long): String =
        if (DateFormat.is24HourFormat(context)) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at)).lowercase()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
