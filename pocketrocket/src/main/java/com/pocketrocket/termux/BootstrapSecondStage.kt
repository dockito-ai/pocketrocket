package com.pocketrocket.termux

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * The bootstrap's second stage (package maintainer scripts). Termux runs it from the first login shell;
 * setup needs it done before it installs packages, so it runs here first. It runs at most once: the
 * script leaves a lock file behind.
 */
object BootstrapSecondStage {
    private val dir = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/termux/termux-bootstrap/second-stage")
    private val script = File(dir, "termux-bootstrap-second-stage.sh")
    private val lock = File(dir, "termux-bootstrap-second-stage.sh.lock")

    /** Returns the exit code, or 0 when there is nothing to do. */
    fun runIfNeeded(context: Context, onLine: (String) -> Unit): Int {
        if (!script.exists() || lock.exists()) return 0
        val bash = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/bash"
        return TermuxCommand.run(context, bash, arrayOf(script.path), File(TermuxConstants.TERMUX_HOME_DIR_PATH), onLine)
    }
}
