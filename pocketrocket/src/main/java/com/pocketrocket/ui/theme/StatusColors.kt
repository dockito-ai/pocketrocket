package com.pocketrocket.ui.theme

import com.pocketrocket.sessions.SessionStatus

/**
 * The one status → colour map (mockup Main, Session): amber needs you, blue working (and starting),
 * green done or ready, red error, grey paused.
 */
fun PrColors.status(status: SessionStatus) = when (status) {
    SessionStatus.WaitingForPermission -> amber
    SessionStatus.Working, SessionStatus.Starting -> blue
    SessionStatus.Done, SessionStatus.WaitingForInput -> green
    SessionStatus.Error -> red
    SessionStatus.Paused -> textMuted
}

/** The edge of a session card in that status (mockup Main): amber or red when it needs a look, quiet otherwise. */
fun PrColors.statusEdge(status: SessionStatus) = when (status) {
    SessionStatus.WaitingForPermission -> amberEdge
    SessionStatus.Error -> redBorder
    else -> iconTile
}

/** The chip fill behind a status label: the status colour at 14 % for needs you, 12 % otherwise. */
fun PrColors.statusFill(status: SessionStatus) = status(status).copy(alpha = if (status == SessionStatus.WaitingForPermission) 0.14f else 0.12f)
