package com.pocketrocket.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

class BackupFormatTest {
    private val pass = "correct horse".toCharArray()
    private val chunk = 1024

    private fun seal(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        EncryptingOutput(out, pass, iterations = 1000, chunkSize = chunk).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, passphrase: CharArray = pass): ByteArray =
        DecryptingInput(ByteArrayInputStream(file), passphrase).use { it.readBytes() }

    private val headerSize = 8 + 2 + 1 + 4 + 1 + 16 + 4 + 7 + 16

    /** The file's chunks, each with its size prefix. */
    private fun chunks(file: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var at = headerSize
        while (at < file.size) {
            val size = ByteBuffer.wrap(file, at, 4).int + 4
            list += file.copyOfRange(at, at + size)
            at += size
        }
        return list
    }

    private fun rebuilt(file: ByteArray, parts: List<ByteArray>) =
        parts.fold(file.copyOfRange(0, headerSize)) { all, part -> all + part }

    @Test fun round_trips_every_size() {
        listOf(0, 1, chunk - 1, chunk, chunk + 1, chunk * 5, chunk * 5 + 7).forEach { n ->
            val data = Random(n).nextBytes(n)
            assertArrayEquals(data, open(seal(data)))
        }
    }

    @Test fun wrong_passphrase_is_told_apart_from_damage() {
        assertThrows(WrongPassphrase::class.java) { open(seal(ByteArray(10)), "not it".toCharArray()) }
    }

    @Test fun a_changed_byte_in_a_chunk_is_damage() {
        val file = seal(Random(1).nextBytes(chunk * 3))
        file[headerSize + chunk + 40] = (file[headerSize + chunk + 40] + 1).toByte()
        assertThrows(DamagedBackup::class.java) { open(file) }
    }

    @Test fun reordered_chunks_are_damage() {
        val file = seal(Random(2).nextBytes(chunk * 3 + 5))
        val parts = chunks(file)
        assertThrows(DamagedBackup::class.java) { open(rebuilt(file, listOf(parts[1], parts[0]) + parts.drop(2))) }
    }

    @Test fun cut_at_a_chunk_boundary_is_damage() {
        val file = seal(Random(3).nextBytes(chunk * 3 + 5))
        val parts = chunks(file)
        assertThrows(DamagedBackup::class.java) { open(rebuilt(file, parts.dropLast(1))) }
    }

    @Test fun cut_inside_a_chunk_is_damage() {
        val file = seal(Random(4).nextBytes(chunk * 3))
        assertThrows(DamagedBackup::class.java) { open(file.copyOf(file.size - 100)) }
    }

    @Test fun a_dropped_middle_chunk_is_damage() {
        val file = seal(Random(5).nextBytes(chunk * 3 + 5))
        val parts = chunks(file)
        assertThrows(DamagedBackup::class.java) { open(rebuilt(file, parts.filterIndexed { i, _ -> i != 1 })) }
    }

    @Test fun anything_after_the_last_chunk_is_damage() {
        val file = seal(Random(6).nextBytes(chunk + 5))
        val parts = chunks(file)
        assertThrows(DamagedBackup::class.java) { open(rebuilt(file, parts + parts.last())) }
    }

    @Test fun a_changed_header_never_opens() {
        val file = seal(ByteArray(100))
        // The chunk size field: the verifier covers it, so it reads as a wrong passphrase rather than opening.
        file[8 + 2 + 1 + 4 + 1 + 16 + 3] = (file[8 + 2 + 1 + 4 + 1 + 16 + 3] + 1).toByte()
        assertThrows(BackupException::class.java) { open(file) }
    }

    @Test fun a_newer_version_is_refused_and_not_called_damage() {
        val file = seal(ByteArray(10))
        file[9] = 99
        assertThrows(UnsupportedBackup::class.java) { open(file) }
    }

    @Test fun not_a_backup_at_all() {
        assertThrows(DamagedBackup::class.java) { open(ByteArray(200) { 7 }) }
        assertThrows(DamagedBackup::class.java) { open(ByteArray(3)) }
    }
}
