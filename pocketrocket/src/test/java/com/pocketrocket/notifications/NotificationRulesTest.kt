package com.pocketrocket.notifications

import com.pocketrocket.sessions.PendingPermission
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {

    private fun bash(command: String, summary: String = command.lineSequence().first().take(160), id: String = "r") =
        PendingPermission(id, "Bash", summary, JSONObject().put("command", command), null)

    @Test fun allowIsOfferedForAWhollyShownOrdinaryCommand() {
        assertTrue(NotificationRules.offersAllow(bash("npm test")))
    }

    @Test fun reviewInsteadForWhatItCantShowOrApprove() {
        assertFalse(NotificationRules.offersAllow(bash("git status\nrm -rf ~")))
        assertFalse(NotificationRules.offersAllow(bash("npm test " + "x ".repeat(120))))
        assertFalse(NotificationRules.offersAllow(bash("git push -f")))
        assertFalse(NotificationRules.offersAllow(PendingPermission("r", "ExitPlanMode", "plan", JSONObject(), null)))
        assertFalse(NotificationRules.offersAllow(PendingPermission("r", "mcp__slack__send", "hi", JSONObject(), null)))
    }

    @Test fun aLockedPhoneNeverAnswersAllow() {
        assertEquals(AllowTap.OpenSession, NotificationRules.onAllowTap(deviceLocked = true, bash("npm test"), "r"))
        assertEquals(AllowTap.Answer, NotificationRules.onAllowTap(deviceLocked = false, bash("npm test"), "r"))
    }

    @Test fun aStaleTapAnswersNothingEvenUnlocked() {
        assertEquals(AllowTap.Stale, NotificationRules.onAllowTap(deviceLocked = false, bash("npm test", id = "later"), "r"))
        assertEquals(AllowTap.Stale, NotificationRules.onAllowTap(deviceLocked = false, null, "r"))
        assertEquals(AllowTap.Stale, NotificationRules.onAllowTap(deviceLocked = true, null, "r"))
    }

    @Test fun anAllowThatShouldntBeThereStillApprovesNothing() {
        assertEquals(AllowTap.OpenSession, NotificationRules.onAllowTap(deviceLocked = false, bash("git push -f"), "r"))
    }
}
