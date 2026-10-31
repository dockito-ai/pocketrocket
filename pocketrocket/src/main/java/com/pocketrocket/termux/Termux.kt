package com.pocketrocket.termux

import android.content.Context
import android.content.Intent
import com.termux.shared.termux.TermuxConstants.TERMUX_APP
import java.io.File

/** Opens the parts of the Termux app that PocketRocket reuses. */
object Termux {

    /** Opens the terminal screen (TermuxActivity). */
    fun openTerminal(context: Context) {
        context.startActivity(Intent().setClassName(context, TERMUX_APP.TERMUX_ACTIVITY_NAME))
    }

    /**
     * Opens a new terminal session in [folder], named [name], through Termux's own execute intent (no
     * executable: a login shell). The shell's ~/.bashrc sources the accounts' env.sh, which sets
     * CLAUDE_CONFIG_DIR and GH_CONFIG_DIR for the folder (AccountWiring), so the terminal runs with the
     * workspace's accounts without any change to Termux.
     */
    fun openTerminalIn(context: Context, folder: File, name: String? = null) {
        val intent = Intent(TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
            .setClassName(context, TERMUX_APP.TERMUX_SERVICE_NAME)
            .putExtra(TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, folder.path)
            .putExtra(TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION, TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY.toString())
        name?.let { intent.putExtra(TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME, it) }
        context.startService(intent)
    }

    /** Starts Termux's foreground service so long work keeps running while the app is in the background. */
    fun startForegroundService(context: Context) {
        context.startService(Intent().setClassName(context, TERMUX_APP.TERMUX_SERVICE_NAME))
    }
}
