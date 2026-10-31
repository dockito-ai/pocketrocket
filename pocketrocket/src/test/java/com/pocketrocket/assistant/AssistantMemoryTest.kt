package com.pocketrocket.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMemoryTest {
    private val nimbus = ProjectEntry("/home/projects/acme/project_nimbus", "project_nimbus", "Client app")
    private val notes = ProjectEntry("/home/projects/notes", "notes")

    private fun saved(s: Saved) = (s as Saved.Yes).book

    @Test fun capsNotesAtTwenty() {
        var book = MemoryBook()
        repeat(MemoryBook.MAX_NOTES) { book = saved(book.remember("note number $it")) }
        assertEquals(20, book.notes.size)
        assertEquals(Saved.Full, book.remember("one too many"))
        assertEquals(20, saved(book.forget(book.notes.first().id).remember("room now")).notes.size)
    }

    @Test fun refusesSecrets() {
        listOf(
            "my token is abc123", "password: hunter2", "the api key = zzz", "use ghp_abcdefghijklmnop12345", "sk-ant-api03-abcdefghij",
            "AKIAABCDEFGHIJKLMNOP", "xoxb-1234-5678-abcd", "-----BEGIN RSA PRIVATE KEY-----", "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8",
            "eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0",
        ).forEach { assertEquals(it, Saved.LooksLikeSecret, MemoryBook().remember(it)) }
        assertEquals(Saved.LooksLikeSecret, saved(MemoryBook().remember("x", id = "a")).edit("a", "my password is hunter2"))
        assertEquals(Saved.LooksLikeSecret, MemoryBook().addNickname("ghp_abcdefghijklmnop12345", nimbus.path))
    }

    @Test fun keepsOrdinaryWords() {
        listOf("use Opus in nimbus", "the token refresh work is in api-server", "passwords page is in notes", "prefer short replies").forEach {
            assertTrue(it, MemoryBook().remember(it) is Saved.Yes)
        }
    }

    @Test fun refusesDuplicatesAndEmptiness() {
        val book = saved(MemoryBook().remember("Use Opus"))
        assertEquals(Saved.Duplicate, book.remember("use opus"))
        assertEquals(Saved.Empty, book.remember("   "))
        assertEquals(Saved.Empty, book.edit(book.notes[0].id, " "))
    }

    @Test fun cutsALongNote() = assertEquals(MemoryBook.MAX_NOTE, saved(MemoryBook().remember("word ".repeat(100))).notes[0].text.length)

    @Test fun nicknamesResolveWithoutClaude() {
        var book = MemoryBook(projects = listOf(nimbus, notes))
        book = saved(book.addNickname("nimbus", nimbus.path))
        book = saved(book.addNickname("acme v2 client facing app", nimbus.path))
        assertEquals(nimbus.path, book.nicknameTarget("Nimbus"))
        assertEquals(nimbus.path, book.nicknameTarget("Acme V2 client-facing app"))
        assertEquals(nimbus.path, book.nicknameTarget("nimbas"))
        assertNull(book.nicknameTarget("something else entirely"))
        assertNull(book.nicknameTarget(""))
    }

    @Test fun theSameNicknameMoves() {
        var book = saved(MemoryBook().addNickname("app", nimbus.path))
        book = saved(book.addNickname("APP", notes.path))
        assertEquals(1, book.nicknames.size)
        assertEquals(notes.path, book.nicknameTarget("app"))
        assertEquals(emptyList<Nickname>(), book.forgetNickname("app").nicknames)
    }

    @Test fun projectsResolveByNicknameThenNameThenSound() {
        val book = saved(MemoryBook(projects = listOf(nimbus, notes)).addNickname("nimbus", nimbus.path))
        assertEquals(listOf(nimbus), ProjectNames.resolve("nimbus", book))
        assertEquals(listOf(notes), ProjectNames.resolve("Notes", book))
        assertEquals(listOf(notes), ProjectNames.resolve("nodes", book))
        assertEquals(emptyList<ProjectEntry>(), ProjectNames.resolve("kubernetes cluster", book))
    }

    @Test fun twoProjectsCanMatch() {
        val book = MemoryBook(projects = listOf(ProjectEntry("/a/web-app", "web-app"), ProjectEntry("/a/web-site", "web-site")))
        assertEquals(2, ProjectNames.resolve("web", book).size)
    }

    @Test fun aRescanKeepsSwitchesAndNicknames() {
        var book = MemoryBook(projects = listOf(nimbus, notes)).setEnabled(notes.path, false)
        book = saved(book.addNickname("n", notes.path))
        val next = book.withProjects(listOf(notes.copy(description = "new"), ProjectEntry("/x", "x")))
        assertFalse(next.projects.first { it.path == notes.path }.enabled)
        assertTrue(next.projects.first { it.path == "/x" }.enabled)
        assertEquals("new", next.projects.first { it.path == notes.path }.description)
        assertEquals(1, next.nicknames.size)
    }

    @Test fun switchCoversWhatsInsideAProject() {
        val book = MemoryBook(projects = listOf(ProjectEntry("/w", "w", enabled = false), ProjectEntry("/w/inner", "inner")))
        assertFalse(book.enabledFor("/w"))
        assertFalse(book.enabledFor("/w/other"))
        assertTrue(book.enabledFor("/w/inner/src"))
        assertTrue(book.enabledFor("/elsewhere"))
    }

    @Test fun descriptionIsOnlyProse() {
        assertEquals("A client app for homes.", ProjectDescription.fromReadme("# Nimbus\n\n![badge](x)\n\nA client app for homes.\n\n## Setup\nmore"))
        assertEquals("", ProjectDescription.fromReadme("# Only a title"))
        assertEquals("", ProjectDescription.fromReadme("Set token: abc123def"))
        assertTrue(ProjectDescription.fromReadme("x ".repeat(200)).length <= 161)
    }

    @Test fun roundTripsAndRefusesAHandEditedSecret() {
        val book = MemoryBook(listOf(Note("a", "hi", habit = true)), listOf(Nickname("n", "/p")), listOf(ProjectEntry("/p", "p", "d", enabled = false)))
        assertEquals(book, MemoryCodec.decode(MemoryCodec.encode(book)))
        val edited = """{"notes":[{"id":"a","text":"my password is hunter2"},{"id":"b","text":"fine"}]}"""
        assertEquals(listOf("fine"), MemoryCodec.decode(edited).notes.map { it.text })
    }
}
