package com.pocketrocket.termux

import android.app.Activity

/** Installs Termux's bootstrap ($PREFIX) if missing, then runs [whenDone] on the main thread. Backed by TermuxInstaller. */
fun interface BootstrapInstaller {
    fun install(activity: Activity, whenDone: Runnable)
}
