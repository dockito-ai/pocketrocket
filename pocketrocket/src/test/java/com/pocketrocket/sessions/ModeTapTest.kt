package com.pocketrocket.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeTapTest {
    @Test fun tappingGoesAutoManualPlanAndRound() {
        assertEquals(PermissionMode.Manual, PermissionMode.Auto.tapNext)
        assertEquals(PermissionMode.Plan, PermissionMode.Manual.tapNext)
        assertEquals(PermissionMode.Auto, PermissionMode.Plan.tapNext)
        assertEquals(PermissionMode.Auto, PermissionMode.AcceptEdits.tapNext)
    }
}
