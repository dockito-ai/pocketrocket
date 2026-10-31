package com.pocketrocket.claudeconfig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MemoryFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, MemoryFile.NAME)

    private fun open(): MemoryRead.Text = MemoryFile.read(file) as MemoryRead.Text

    @Test fun aMissingFileIsEmptyAndSavingCreatesIt() {
        val read = open()
        assertEquals("", read.text)
        assertTrue(read.stamp.missing)
        assertTrue(MemoryFile.save(file, "hello", read.stamp) is MemorySave.Saved)
        assertEquals("hello", file.readText())
    }

    @Test fun savingCreatesAMissingParent() {
        val deep = File(folder.root, "a/b/${MemoryFile.NAME}")
        assertTrue(MemoryFile.save(deep, "x", MemoryStamp.Missing) is MemorySave.Saved)
        assertEquals("x", deep.readText())
    }

    @Test fun textRoundTripsExactly() {
        val text = "﻿line one\r\nline two é\n\n"
        file.writeText(text)
        val read = open()
        assertEquals(text, read.text)
        assertTrue(MemoryFile.save(file, read.text, read.stamp) is MemorySave.Saved)
        assertEquals(text, file.readText())
    }

    @Test fun aChangeUnderneathIsAConflictAndWritesNothing() {
        file.writeText("mine")
        val read = open()
        file.writeText("theirs")
        val result = MemoryFile.save(file, "edited", read.stamp)
        assertTrue(result is MemorySave.Conflict)
        assertEquals("theirs", file.readText())
    }

    @Test fun aDeletionUnderneathIsAConflict() {
        file.writeText("mine")
        val read = open()
        file.delete()
        val result = MemoryFile.save(file, "edited", read.stamp) as MemorySave.Conflict
        assertTrue(result.current.missing)
        assertFalse(file.exists())
    }

    @Test fun aFileAppearingUnderneathIsAConflict() {
        val read = open()
        file.writeText("theirs")
        assertTrue(MemoryFile.save(file, "edited", read.stamp) is MemorySave.Conflict)
        assertEquals("theirs", file.readText())
    }

    @Test fun aTouchIsNotAChange() {
        file.writeText("same")
        val read = open()
        file.setLastModified(1_000_000L)
        assertTrue(MemoryFile.save(file, "edited", read.stamp) is MemorySave.Saved)
    }

    @Test fun theConflictsStampAllowsADeliberateOverwrite() {
        file.writeText("mine")
        val read = open()
        file.writeText("theirs")
        val conflict = MemoryFile.save(file, "edited", read.stamp) as MemorySave.Conflict
        assertTrue(MemoryFile.save(file, "edited", conflict.current) is MemorySave.Saved)
        assertEquals("edited", file.readText())
    }

    @Test fun savingLeavesNoTempFile() {
        file.writeText("a")
        MemoryFile.save(file, "b", open().stamp)
        assertEquals(listOf(MemoryFile.NAME), folder.root.list()!!.toList())
    }

    @Test fun aLinkIsFollowedAndStaysALink() {
        val real = File(folder.newFolder("real"), "notes.md").apply { writeText("old") }
        Files.createSymbolicLink(file.toPath(), real.toPath())
        assertTrue(MemoryFile.save(file, "new", open().stamp) is MemorySave.Saved)
        assertEquals("new", real.readText())
        assertTrue(Files.isSymbolicLink(file.toPath()))
    }

    @Test fun aLargeFileIsRefusedWithoutReadingIt() {
        file.writeBytes(ByteArray(MemoryFile.MAX_BYTES + 1) { 'a'.code.toByte() })
        val read = MemoryFile.read(file) as MemoryRead.TooLarge
        assertEquals((MemoryFile.MAX_BYTES + 1).toLong(), read.bytes)
    }

    @Test fun exactlyTheLimitOpens() {
        file.writeBytes(ByteArray(MemoryFile.MAX_BYTES) { 'a'.code.toByte() })
        assertEquals(MemoryFile.MAX_BYTES, open().text.length)
    }

    @Test fun tooMuchTextIsNotSaved() {
        file.writeText("keep")
        val result = MemoryFile.save(file, "a".repeat(MemoryFile.MAX_BYTES + 1), open().stamp)
        assertTrue(result is MemorySave.Failed)
        assertEquals("keep", file.readText())
    }

    @Test fun whatIsntUtf8IsntText() {
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals(MemoryRead.NotText, MemoryFile.read(file))
        file.writeBytes(byteArrayOf(0x61, 0x00, 0x62))
        assertEquals(MemoryRead.NotText, MemoryFile.read(file))
    }

    @Test fun aFolderInItsPlaceIsRefusedOnBothSides() {
        file.mkdirs()
        assertTrue(MemoryFile.read(file) is MemoryRead.Unreadable)
        assertTrue(MemoryFile.save(file, "x", MemoryStamp.Missing) is MemorySave.Conflict)
    }

    /** Everything it starts runs to the end before the call returns. */
    private fun opened() = MemoryPanel(file, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined).also { it.load() }

    @Test fun panelStartsCleanAndTypingMakesItDirty() {
        file.writeText("text")
        val panel = opened()
        assertEquals("text", panel.state.value.text)
        assertFalse(panel.state.value.dirty)
        assertFalse(panel.state.value.canSave)
        panel.edit("text!")
        assertTrue(panel.state.value.dirty)
        assertTrue(panel.state.value.canSave)
        panel.edit("text")
        assertFalse(panel.state.value.dirty)
    }

    @Test fun panelWritesOnlyOnSave() {
        file.writeText("text")
        val panel = opened()
        panel.edit("changed")
        assertEquals("text", file.readText())
        panel.save()
        assertEquals("changed", file.readText())
        assertFalse(panel.state.value.dirty)
        assertTrue(panel.state.value.justSaved)
    }

    @Test fun panelSavesTwiceWithoutAConflictWithItself() {
        val panel = opened()
        panel.edit("one")
        panel.save()
        panel.edit("two")
        panel.save()
        assertNull(panel.state.value.conflict)
        assertEquals("two", file.readText())
    }

    @Test fun panelConflictKeepsTheTextAndTheOtherEdit() {
        file.writeText("text")
        val panel = opened()
        panel.edit("mine")
        file.writeText("theirs")
        panel.save()
        val state = panel.state.value
        assertNotNull(state.conflict)
        assertEquals("mine", state.text)
        assertTrue(state.dirty)
        assertFalse(state.canSave)
        assertEquals("theirs", file.readText())
    }

    @Test fun panelConflictCanLoadTheOtherEdit() {
        file.writeText("text")
        val panel = opened()
        panel.edit("mine")
        file.writeText("theirs")
        panel.save()
        panel.load()
        assertEquals("theirs", panel.state.value.text)
        assertFalse(panel.state.value.dirty)
        assertNull(panel.state.value.conflict)
    }

    @Test fun panelOverwriteReplacesWhatTheConflictSawAndNothingNewer() {
        file.writeText("text")
        val panel = opened()
        panel.edit("mine")
        file.writeText("theirs")
        panel.save()
        file.writeText("theirs again")
        panel.overwrite()
        assertEquals("theirs again", file.readText())
        assertNotNull(panel.state.value.conflict)
        panel.overwrite()
        assertEquals("mine", file.readText())
        assertNull(panel.state.value.conflict)
        assertFalse(panel.state.value.dirty)
    }

    @Test fun panelKeepEditingAsksAgainOnTheNextSave() {
        file.writeText("text")
        val panel = opened()
        panel.edit("mine")
        file.writeText("theirs")
        panel.save()
        panel.keepEditing()
        assertTrue(panel.state.value.canSave)
        panel.save()
        assertNotNull(panel.state.value.conflict)
        assertEquals("theirs", file.readText())
    }

    @Test fun panelRefusesWhatItCantEdit() {
        file.writeBytes(ByteArray(MemoryFile.MAX_BYTES + 1))
        val panel = opened()
        assertNotNull(panel.state.value.refused)
        assertFalse(panel.state.value.editable)
        panel.edit("x")
        assertFalse(panel.state.value.canSave)
        panel.save()
        assertEquals((MemoryFile.MAX_BYTES + 1).toLong(), file.length())
    }

    @Test fun panelKeepsTheTextWhenASaveFails() {
        file.writeText("text")
        val panel = opened()
        panel.edit("a".repeat(MemoryFile.MAX_BYTES + 1))
        panel.save()
        assertNotNull(panel.state.value.problem)
        assertTrue(panel.state.value.dirty)
        assertEquals("text", file.readText())
    }
}
