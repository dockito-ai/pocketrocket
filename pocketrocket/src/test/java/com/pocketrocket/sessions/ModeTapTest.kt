package com.pocketrocket.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeTapTest {
    /** Like shift+tab in Claude Code: every mode, accept edits included (decision 95). */
    @Test fun tappingGoesThroughEveryModeAndRound() {
        assertEquals(PermissionMode.Manual, PermissionMode.Auto.next)
        assertEquals(PermissionMode.AcceptEdits, PermissionMode.Manual.next)
        assertEquals(PermissionMode.Plan, PermissionMode.AcceptEdits.next)
        assertEquals(PermissionMode.Auto, PermissionMode.Plan.next)
    }
}
