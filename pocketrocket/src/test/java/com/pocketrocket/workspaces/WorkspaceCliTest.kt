package com.pocketrocket.workspaces

import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.profiles.FolderBinding
import com.pocketrocket.workspaces.WorkspaceCli.AccountPicks
import com.pocketrocket.workspaces.WorkspaceCli.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCliTest {
    private fun file(vararg fields: String) = fields.joinToString("") { it + "\u0000" }

    @Test fun readsARequestFileAsTheScriptWritesIt() {
        val r = WorkspaceCli.parseRequest(file(WorkspaceCli.VERSION, "cwd=/h/projects/a", "session=/h/projects/b", "claude=1", "--", "add", "my repo", ""))!!
        assertEquals("/h/projects/a", r.cwd)
        assertEquals("/h/projects/b", r.sessionFolder)
        assertTrue(r.fromClaude)
        assertEquals(listOf("add", "my repo", ""), r.args)
    }

    @Test fun emptyFieldsAndNoArguments() {
        val r = WorkspaceCli.parseRequest(file(WorkspaceCli.VERSION, "cwd=/x", "session=", "claude=", "--"))!!
        assertNull(r.sessionFolder)
        assertFalse(r.fromClaude)
        assertEquals(emptyList<String>(), r.args)
    }

    @Test fun refusesOtherVersionsAndBrokenFiles() {
        assertNull(WorkspaceCli.parseRequest(file("pocketrocket-workspace/0", "cwd=/x", "--", "list")))
        assertNull(WorkspaceCli.parseRequest(file(WorkspaceCli.VERSION, "cwd=/x", "list")))
        assertNull(WorkspaceCli.parseRequest(file(WorkspaceCli.VERSION, "cwd=relative", "--", "list")))
        assertNull(WorkspaceCli.parseRequest(""))
    }

    @Test fun parsesAddWithFlagsBothWays() {
        assertEquals(
            Command.Add("app", "My app", AccountPicks(claude = "work", github = "shankar", dockito = "me@x.com")),
            WorkspaceCli.parseArgs(listOf("add", "--name", "My app", "app", "--github=shankar", "--claude", "work", "--dockito", "me@x.com")),
        )
    }

    @Test fun parsesCloneAndList() {
        assertEquals(
            Command.Clone("git@github.com:o/r.git", null, "dev", null, AccountPicks()),
            WorkspaceCli.parseArgs(listOf("clone", "git@github.com:o/r.git", "--branch", "dev")),
        )
        assertEquals(Command.Clone("u", "work/r", null, "R", AccountPicks(github = "g")), WorkspaceCli.parseArgs(listOf("clone", "u", "work/r", "--name", "R", "--github", "g")))
        assertEquals(Command.ListAll(json = true), WorkspaceCli.parseArgs(listOf("list", "--json")))
        assertEquals(Command.ListAll(json = false), WorkspaceCli.parseArgs(listOf("list")))
    }

    @Test fun refusesWhatItDoesNotDo() {
        listOf(
            emptyList(),
            listOf("remove", "x"),
            listOf("rename", "x", "y"),
            listOf("add"),
            listOf("add", "a", "b"),
            listOf("add", "a", "--branch", "x"),
            listOf("add", "a", "--github"),
            listOf("add", "a", "--github="),
            listOf("clone"),
            listOf("clone", "u", "a", "b"),
            listOf("list", "x"),
        ).forEach { args -> assertThrows("$args", WorkspaceCli.Error::class.java) { WorkspaceCli.parseArgs(args) } }
    }

    @Test fun resolvesPathsFromTheCallersFolder() {
        assertEquals("/h/projects/work/app", WorkspaceCli.resolve("app", "/h/projects/work", "/h"))
        assertEquals("/h/projects/app", WorkspaceCli.resolve("../app/", "/h/projects/work", "/h"))
        assertEquals("/h/projects/x", WorkspaceCli.resolve("~/projects/x", "/tmp", "/h"))
        assertEquals("/h", WorkspaceCli.resolve("~", "/tmp", "/h"))
        assertEquals("/abs/y", WorkspaceCli.resolve("/abs/./y", "/tmp", "/h"))
    }

    @Test fun onlyUnderProjects() {
        val root = "/h/projects"
        assertNull(WorkspaceCli.checkUnderProjects("/h/projects/a", root))
        assertNull(WorkspaceCli.checkUnderProjects("/h/projects/work/acme/app", root))
        assertTrue(WorkspaceCli.checkUnderProjects("/h/projects", root) != null)
        assertTrue(WorkspaceCli.checkUnderProjects("/h/projects-old/a", root) != null)
        assertTrue(WorkspaceCli.checkUnderProjects("/h/a", root) != null)
        assertTrue(WorkspaceCli.checkUnderProjects(WorkspaceCli.resolve("../../etc", "/h/projects/a", "/h"), root) != null)
    }

    private val acme = Workspace("/h/projects/work/acme/app", "app")
    private val notes = Workspace("/h/projects/personal/notes", "notes")
    private val all = listOf(acme, notes)

    @Test fun accountsComeFromTheTargetsWorkspaceThenTheSessionsThenTheFolders() {
        // Cloning into an existing workspace: that workspace's accounts.
        assertEquals(acme, WorkspaceCli.accountSource(all, "/h/projects/work/acme/app/lib", "/h/projects/personal/notes", "/h"))
        // A new folder: the session's workspace, even when the shell has moved elsewhere.
        assertEquals(notes, WorkspaceCli.accountSource(all, "/h/projects/work/acme/web", "/h/projects/personal/notes/sub", "/h/projects/work/acme/app"))
        // No session (a terminal): the folder the command runs in.
        assertEquals(acme, WorkspaceCli.accountSource(all, "/h/projects/work/acme/web", null, "/h/projects/work/acme/app/src"))
        // Neither: the defaults.
        assertNull(WorkspaceCli.accountSource(all, "/h/projects/work/acme/web", null, "/h/projects/work/acme"))
    }

    @Test fun givenAccountsWinAndTheRestFollowTheSource() {
        val binding = FolderBinding("/h/projects/personal/notes", claude = "work", github = null, dockito = "me@x.com")
        assertEquals(AccountPicks("work", "other", "me@x.com"), WorkspaceCli.fill(AccountPicks(github = "other"), binding))
        assertEquals(AccountPicks("mine", null, "me@x.com"), WorkspaceCli.fill(AccountPicks(claude = "mine"), binding))
        assertEquals(AccountPicks(), WorkspaceCli.fill(AccountPicks(), null))
    }

    @Test fun workspacesInsideAFolder() {
        assertEquals(listOf(acme), WorkspaceCli.inside(all, "/h/projects/work"))
        assertEquals(emptyList<Workspace>(), WorkspaceCli.inside(all, "/h/projects/work/acme/app"))
        assertEquals(acme, WorkspaceCli.containing(all, "/h/projects/work/acme/app"))
        assertNull(WorkspaceCli.containing(all, "/h/projects/work/acme/application"))
    }

    @Test fun matchesAccountsByIdOrName() {
        val accounts = listOf(ClaudeAccountEntry("main", "personal"), ClaudeAccountEntry("work", "Acme"))
        assertEquals("work", WorkspaceCli.match("Claude", "work", accounts).id)
        assertEquals("work", WorkspaceCli.match("Claude", "acme", accounts).id)
        assertEquals("main", WorkspaceCli.match("Claude", "Personal", accounts).id)
        val e = assertThrows(WorkspaceCli.Error::class.java) { WorkspaceCli.match("Claude", "nope", accounts) }
        assertTrue(e.message!!.contains("main (personal)"))
        assertThrows(WorkspaceCli.Error::class.java) { WorkspaceCli.match("GitHub", "x", emptyList<ClaudeAccountEntry>()) }
    }
}
