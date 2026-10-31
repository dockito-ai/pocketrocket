package com.pocketrocket.notifications

import com.pocketrocket.sessions.PendingPermission

/** What tapping Allow on a notification does. */
enum class AllowTap { Answer, OpenSession, Stale }

/** What an Allow tap does. No Android, so it can be tested. */
object NotificationRules {
    /**
     * Checked at the tap, whatever the system did first. Allow answers whatever the request is (decision 76); only a
     * locked phone opens the session instead. [deviceLocked] needs a real lock: a phone with no PIN has no lock to need.
     * The system's `setAuthenticationRequired` only exists from Android 12 and a phone's maker can change how it behaves.
     */
    fun onAllowTap(deviceLocked: Boolean, open: PendingPermission?, requestId: String): AllowTap = when {
        open?.requestId != requestId -> AllowTap.Stale
        deviceLocked -> AllowTap.OpenSession
        else -> AllowTap.Answer
    }
}
