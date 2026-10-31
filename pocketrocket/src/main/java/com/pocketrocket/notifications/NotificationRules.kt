package com.pocketrocket.notifications

import com.pocketrocket.assistant.Protection
import com.pocketrocket.assistant.SensitiveActions
import com.pocketrocket.assistant.fullCommand
import com.pocketrocket.sessions.PendingPermission

/** What tapping Allow on a notification does. */
enum class AllowTap { Answer, OpenSession, Stale }

/** When a notification may approve anything. No Android, so it can be tested. */
object NotificationRules {
    /**
     * Allow only for what the notification really shows. Otherwise Deny and Review: a plan or a fingerprint request, or a command
     * longer than what's displayed, whose summary stops at the first line.
     */
    fun offersAllow(asked: PendingPermission): Boolean = !asked.isPlan && SensitiveActions.protectionOf(asked) != Protection.Fingerprint && commandIsWhollyShown(asked)

    /**
     * Checked at the tap, whatever the system did first. [deviceLocked] needs a real lock: a phone with no PIN has nothing to enforce.
     * The system's `setAuthenticationRequired` only exists from Android 12 and a phone's maker can change how it behaves, so this is the rule that counts.
     */
    fun onAllowTap(deviceLocked: Boolean, open: PendingPermission?, requestId: String): AllowTap = when {
        open?.requestId != requestId -> AllowTap.Stale
        deviceLocked || !offersAllow(open) -> AllowTap.OpenSession
        else -> AllowTap.Answer
    }

    private fun commandIsWhollyShown(asked: PendingPermission): Boolean = asked.toolName != "Bash" || asked.fullCommand().trim() == asked.summary.trim()
}
