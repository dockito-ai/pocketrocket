package com.pocketrocket.ui.sessions

import com.pocketrocket.sessions.SessionStatus
import com.termux.shared.termux.TermuxConstants

/** A status in words, as the cards and chips show it. Its colour is `PrTheme.colors.status(...)` (ui/theme). */
val SessionStatus.label: String
    get() = when (this) {
        SessionStatus.Starting -> "Starting"
        SessionStatus.Working -> "Working"
        SessionStatus.WaitingForInput -> "Ready"
        SessionStatus.WaitingForPermission -> "Needs you"
        SessionStatus.Done -> "Done"
        SessionStatus.Error -> "Error"
        SessionStatus.Paused -> "Paused"
    }

/** Shows the Termux home as ~. */
fun displayPath(path: String): String = path.replace(TermuxConstants.TERMUX_HOME_DIR_PATH, "~")
