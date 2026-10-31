package com.pocketrocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision 80: screens under the file manager follow a folder it renamed or deleted. */
class RoutesTest {
    private val ws = "/h/projects/work/app"

    @Test fun movedRoutesFollowTheFolder() {
        assertEquals("ws|/h/projects/work/web", Routes.moved(Routes.workspace(ws), ws, "/h/projects/work/web"))
        assertEquals("ws|/h/projects/job/app", Routes.moved(Routes.workspace(ws), "/h/projects/work", "/h/projects/job"))
        assertEquals("session-new|/h/projects/job/app/src|new", Routes.moved(Routes.newSession("$ws/src"), "/h/projects/work", "/h/projects/job"))
    }

    @Test fun otherRoutesStayPut() {
        assertEquals("home", Routes.moved("home", ws, "/x"))
        assertEquals("ws|/h/projects/work/application", Routes.moved("ws|/h/projects/work/application", ws, "/x"))
        assertEquals(Routes.TOOLS, Routes.moved(Routes.TOOLS, ws, "/x"))
    }

    @Test fun insideAFolder() {
        assertTrue(Routes.inside(Routes.workspace(ws), ws))
        assertTrue(Routes.inside(Routes.workspaceSettings(ws), "/h/projects"))
        assertFalse(Routes.inside(Routes.workspace("/h/projects/work/application"), ws))
        assertFalse(Routes.inside("home", ws))
    }
}
