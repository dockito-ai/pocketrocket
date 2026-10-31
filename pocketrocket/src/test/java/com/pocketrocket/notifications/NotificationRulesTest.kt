package com.pocketrocket.notifications

import com.pocketrocket.sessions.PendingPermission
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRulesTest {

    private fun bash(command: String, summary: String = command.lineSequence().first().take(160), id: String = "r") =
        PendingPermission(id, "Bash", summary, JSONObject().put("command", command), null)

    @Test fun aLockedPhoneNeverAnswersAllow() {
        assertEquals(AllowTap.OpenSession, NotificationRules.onAllowTap(deviceLocked = true, bash("npm test"), "r"))
        assertEquals(AllowTap.Answer, NotificationRules.onAllowTap(deviceLocked = false, bash("npm test"), "r"))
    }

    @Test fun aStaleTapAnswersNothingEvenUnlocked() {
        assertEquals(AllowTap.Stale, NotificationRules.onAllowTap(deviceLocked = false, bash("npm test", id = "later"), "r"))
        assertEquals(AllowTap.Stale, NotificationRules.onAllowTap(deviceLocked = false, null, "r"))
        assertEquals(AllowTap.Stale, NotificationRules.onAllowTap(deviceLocked = true, null, "r"))
    }

    @Test fun unlockedAllowAnswersEveryKindOfRequest() {
        for (asked in listOf(bash("git status\nrm -rf ~"), bash("git push -f"), PendingPermission("r", "ExitPlanMode", "plan", JSONObject(), null)))
            assertEquals(AllowTap.Answer, NotificationRules.onAllowTap(deviceLocked = false, asked, "r"))
    }
}
