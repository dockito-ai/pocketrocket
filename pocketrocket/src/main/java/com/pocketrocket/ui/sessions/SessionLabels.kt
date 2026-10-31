package com.pocketrocket.ui.sessions

import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.runningWork
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

/**
 * A card's status words: [label], or "1 agent running", "2 shells running", "1 agent · 2 shells running" when only
 * subagents or background shells keep it working ([SessionState.shownStatus]).
 */
val SessionState.statusLabel: String
    get() = if (shownStatus != status) runningWork(runningAgents, runningShells) + " running" else status.label

/** Shows the Termux home as ~. */
fun displayPath(path: String): String = path.replace(TermuxConstants.TERMUX_HOME_DIR_PATH, "~")
