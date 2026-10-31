package com.pocketrocket.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens one of Rocket's screens from the background, with a notification when it doesn't come up. Android can refuse a
 * background start without saying so: just after leaving the app it still reads as in front, and Xiaomi's "Display pop-up
 * windows while running in the background" blocks it even with "Display over other apps". So a screen asked for is checked
 * a moment later, and if it hasn't resumed, the fallback (a notification) is posted after all.
 */
object ScreenLaunch {
    private const val TAG = "PocketRocketLaunch"
    const val CHECK_MS = 1_500L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val resumedAt = ConcurrentHashMap<String, Long>()

    /** From the screen's onResume. */
    fun resumed(screen: Class<*>) {
        resumedAt[screen.name] = SystemClock.elapsedRealtime()
    }

    /** [intent] for [screen] if Android may allow it now ([canTry]); otherwise, or if it didn't come up, [fallback]. */
    fun open(context: Context, screen: Class<*>, intent: Intent, canTry: Boolean, fallback: () -> Unit) {
        val app = context.applicationContext
        if (!canTry) return fallback()
        val asked = SystemClock.elapsedRealtime()
        val started = runCatching { app.startActivity(intent) }.onFailure { Log.w(TAG, "start refused: ${it.javaClass.simpleName}") }.isSuccess
        if (!started) return fallback()
        main.postDelayed({ if (cameUp(resumedAt[screen.name], asked)) Unit else { Log.i(TAG, "${screen.simpleName} didn't come up; posting a notification"); fallback() } }, CHECK_MS)
    }

    /** Whether a screen resumed at [resumed] answers a launch asked for at [asked]. */
    internal fun cameUp(resumed: Long?, asked: Long): Boolean = resumed != null && resumed >= asked
}
