package com.pocketrocket.setup

import android.content.Context

/** Remembers that first-run setup finished, so it is not shown again. */
object SetupPrefs {
    private const val FILE = "pocketrocket"
    private const val KEY_DONE = "setup_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}
