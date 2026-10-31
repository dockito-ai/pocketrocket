package com.pocketrocket.files

import com.pocketrocket.files.FileRules.Crumb
import com.pocketrocket.files.FileRules.DeleteGuard
import com.pocketrocket.files.FileRules.RenameKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision 78: back, the path bar, names, and which deletes and renames need care. */
class FileRulesTest {
    private val home = "/data/data/app.pocket/files/home"
    private val projects = "$home/projects"
    private val ws = "$projects/work/app"

    @Test fun insideMeansTheFolderOrBelowIt() {
        assertTrue(FileRules.isInside(home, home))
        assertTrue(FileRules.isInside("$home/a/b", home))
        assertFalse(FileRules.isInside("${home}2/a", home))
        assertFalse(FileRules.isInside("/data/data/app.pocket/files", home))
    }

    @Test fun backGoesUpUntilWhereItOpened() {
        assertEquals("$ws/src", FileRules.upFrom("$ws/src/main", ws, home))
        assertEquals(ws, FileRules.upFrom("$ws/src", ws, home))
        assertNull(FileRules.upFrom(ws, ws, home))
    }

    @Test fun backAboveWhereItOpenedGoesUpToHomeThenLeaves() {
        // Opened at a workspace, then the path bar went to ~/projects.
        assertEquals(home, FileRules.upFrom(projects, ws, home))
        assertNull(FileRules.upFrom(home, ws, home))
        assertNull(FileRules.upFrom("/etc", ws, home))
    }

    @Test fun pathBarFromHome() {
        assertEquals(listOf(Crumb("~", home)), FileRules.crumbs(home, home))
        assertEquals(
            listOf(Crumb("~", home), Crumb("projects", projects), Crumb("work", "$projects/work"), Crumb("app", ws)),
            FileRules.crumbs(ws, home),
        )
        assertEquals(listOf(Crumb("/etc", "/etc")), FileRules.crumbs("/etc", home))
    }

    @Test fun names() {
        assertNull(FileRules.nameProblem("notes.md"))
        assertNull(FileRules.nameProblem(".env"))
        assertNull(FileRules.nameProblem("with space"))
        listOf("", "  ", ".", "..", "a/b", "../x", " lead", "trail ", "a\u0000b", "a\nb", "x".repeat(256), "é".repeat(128)).forEach {
            assertNotNull("'$it' should be refused", FileRules.nameProblem(it))
        }
        assertNull(FileRules.nameProblem("x".repeat(255)))
    }

    private val protectedRoots = listOf(home, projects, ws)

    @Test fun deletingHomeProjectsOrAWorkspaceIsSerious() {
        assertEquals(DeleteGuard.Serious, FileRules.deleteGuard(home, false, protectedRoots))
        assertEquals(DeleteGuard.Serious, FileRules.deleteGuard(projects, false, protectedRoots))
        assertEquals(DeleteGuard.Serious, FileRules.deleteGuard(ws, false, protectedRoots))
    }

    @Test fun deletingAFolderHoldingAWorkspaceIsSerious() {
        assertEquals(DeleteGuard.Serious, FileRules.deleteGuard("$projects/work", false, protectedRoots))
    }

    @Test fun deletingInsideAWorkspaceOrALinkIsNormal() {
        assertEquals(DeleteGuard.Normal, FileRules.deleteGuard("$ws/src", false, protectedRoots))
        assertEquals(DeleteGuard.Normal, FileRules.deleteGuard("$home/notes.md", false, protectedRoots))
        assertEquals(DeleteGuard.Normal, FileRules.deleteGuard("$projects/workshop", false, protectedRoots))
        // A link to a folder holding workspaces: only the link goes.
        assertEquals(DeleteGuard.Normal, FileRules.deleteGuard("$projects/work", true, protectedRoots))
        // ...unless the link is itself the workspace.
        assertEquals(DeleteGuard.Serious, FileRules.deleteGuard(ws, true, protectedRoots))
    }

    @Test fun renames() {
        val fixed = listOf(home, projects)
        val workspaces = listOf(ws)
        assertEquals(RenameKind.Refused, FileRules.renameKind(home, false, fixed, workspaces))
        assertEquals(RenameKind.Refused, FileRules.renameKind(projects, false, fixed, workspaces))
        assertEquals(RenameKind.Workspace, FileRules.renameKind(ws, false, fixed, workspaces))
        assertEquals(RenameKind.Workspace, FileRules.renameKind("$projects/work", false, fixed, workspaces))
        assertEquals(RenameKind.Plain, FileRules.renameKind("$projects/work", true, fixed, workspaces))
        assertEquals(RenameKind.Plain, FileRules.renameKind("$ws/src", false, fixed, workspaces))
        assertEquals(RenameKind.Plain, FileRules.renameKind("$home/notes.md", false, fixed, workspaces))
    }
}
