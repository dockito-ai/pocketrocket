package com.pocketrocket.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Decision 78: text or binary, images by name, packages, image sampling, and reading text with the 1 MB limit. */
class FileKindsTest {
    private val dir: File = Files.createTempDirectory("kinds").toFile()

    @After fun tearDown() { dir.deleteRecursively() }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun plainTextIsText() {
        assertFalse(FileKinds.looksBinary("fun main() {\n\tprintln(\"hi\")\r\n}\n".toByteArray()))
        assertFalse(FileKinds.looksBinary("नमस्ते, こんにちは, émoji 🚀\n".toByteArray()))
        assertFalse(FileKinds.looksBinary("\u001b[32mgreen\u001b[0m log\n".toByteArray()))
        assertFalse(FileKinds.looksBinary(ByteArray(0)))
    }

    @Test fun nulMeansBinary() {
        assertTrue(FileKinds.looksBinary(bytes(0x50, 0x4B, 0x03, 0x04, 0x00, 0x14)))
    }

    @Test fun manyControlCharactersMeanBinary() {
        assertTrue(FileKinds.looksBinary(ByteArray(100) { if (it % 5 == 0) 0x01 else 0x41 }))
        assertFalse(FileKinds.looksBinary(ByteArray(100) { if (it == 0) 0x01 else 0x41 }))
    }

    @Test fun invalidUtf8MeansBinary() {
        assertTrue(FileKinds.looksBinary(ByteArray(64) { (0x80 + it).toByte() }))
        assertEquals(1, FileKinds.invalidUtf8(bytes(0x41, 0xFF, 0x41), 3))
    }

    @Test fun aCharacterCutOffAtTheEndIsFine() {
        val text = "ab🚀".toByteArray()
        assertEquals(0, FileKinds.invalidUtf8(text, text.size - 2))
        assertFalse(FileKinds.looksBinary(text, text.size - 1))
    }

    @Test fun onlyTheGivenLengthCounts() {
        val sample = ByteArray(16) { 0x41 }.also { it[10] = 0 }
        assertFalse(FileKinds.looksBinary(sample, 10))
        assertTrue(FileKinds.looksBinary(sample, 11))
    }

    @Test fun namesDecideImagesAndArchives() {
        assertEquals(FileKind.Image, FileKinds.byName("Shot.PNG"))
        assertEquals(FileKind.Image, FileKinds.byName("a.jpeg"))
        assertEquals(FileKind.Other, FileKinds.byName("app.apk"))
        assertEquals(FileKind.Other, FileKinds.byName("x.tar.gz"))
        assertNull(FileKinds.byName("logo.svg"))
        assertNull(FileKinds.byName("Makefile"))
        assertEquals(FileKind.Text, FileKinds.of("Makefile", "all:\n\tgcc\n".toByteArray()))
        assertEquals(FileKind.Other, FileKinds.of("blob", bytes(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00)))
    }

    @Test fun packages() {
        assertTrue(FileKinds.isPackage("app-release.APK"))
        assertTrue(FileKinds.isPackage("bundle.aab"))
        assertTrue(FileKinds.isPackage("download", "application/vnd.android.package-archive"))
        assertFalse(FileKinds.isPackage("notes.txt", "text/plain"))
    }

    @Test fun sampleSizes() {
        assertEquals(1, FileKinds.sampleSize(1080, 2400, 4096))
        assertEquals(2, FileKinds.sampleSize(4000, 3000, 2048))
        assertEquals(4, FileKinds.sampleSize(12000, 9000, 4096))
        assertEquals(1, FileKinds.sampleSize(100, 100, 0))
    }

    @Test fun readsSmallTextWhole() {
        val f = File(dir, "a.txt").apply { writeText("one\ntwo\n") }
        assertEquals(TextFile("one\ntwo\n", 8, truncated = false), FileText.read(f))
        assertEquals(FileKind.Text, FileKinds.of(f))
    }

    @Test fun bigTextStopsAtTheLimitOnALineEnd() {
        val f = File(dir, "big.log").apply { writeText("0123456789\n".repeat(20)) }
        val read = FileText.read(f, limit = 25)!!
        assertTrue(read.truncated)
        assertEquals(220L, read.size)
        assertEquals("0123456789\n0123456789\n", read.text)
    }

    @Test fun binaryFilesAreNotRead() {
        val f = File(dir, "data").apply { writeBytes(bytes(1, 2, 0, 4)) }
        assertNull(FileText.read(f))
        assertEquals(FileKind.Other, FileKinds.of(f))
    }

    @Test fun emptyFileIsEmptyText() {
        val f = File(dir, "empty").apply { writeText("") }
        assertEquals(TextFile("", 0, false), FileText.read(f))
    }
}
