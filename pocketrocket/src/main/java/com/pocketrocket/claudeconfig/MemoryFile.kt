package com.pocketrocket.claudeconfig

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** What a file held when it was read: a hash of its bytes, or [Missing]. Its contents, never a time, so a touch isn't a change. */
data class MemoryStamp(val hash: String?) {
    val missing: Boolean get() = hash == null

    companion object {
        val Missing = MemoryStamp(null)
    }
}

/** What opening a `CLAUDE.md` found. A file that doesn't exist is [Text] with nothing in it: saving creates it. */
sealed interface MemoryRead {
    class Text(val text: String, val stamp: MemoryStamp) : MemoryRead
    data class TooLarge(val bytes: Long) : MemoryRead
    data object NotText : MemoryRead
    data class Unreadable(val reason: String) : MemoryRead
}

/** What saving did. After [Conflict] and [Failed] the file on disk is as it was. */
sealed interface MemorySave {
    data class Saved(val stamp: MemoryStamp) : MemorySave

    /** The file no longer is what was opened; [current] is what it is now, for a save that means to replace it. */
    data class Conflict(val current: MemoryStamp) : MemorySave
    data class Failed(val reason: String) : MemorySave
}

/** Reads and writes one memory file. No Android, blocking, never throws an I/O failure. */
object MemoryFile {
    const val NAME = "CLAUDE.md"

    /** Well past what is useful as memory, and about what a Compose text field can still type into. */
    const val MAX_BYTES = 256 * 1024

    /** Any file that isn't a plain one: there is no way to have opened it, so it never matches. */
    private val NotAFile = MemoryStamp("not-a-file")

    fun read(file: File): MemoryRead = try {
        when {
            !file.exists() -> MemoryRead.Text("", MemoryStamp.Missing)
            // A pipe would block for ever, and a folder has nothing to read.
            !file.isFile -> MemoryRead.Unreadable("It isn't a plain file.")
            file.length() > MAX_BYTES -> MemoryRead.TooLarge(file.length())
            else -> {
                val bytes = FileInputStream(file).use { readUpTo(it, MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) {
                    MemoryRead.TooLarge(bytes.size.toLong())
                } else {
                    decode(bytes)?.let { MemoryRead.Text(it, stampOf(bytes)) } ?: MemoryRead.NotText
                }
            }
        }
    } catch (e: IOException) {
        MemoryRead.Unreadable(COULD_NOT_READ)
    } catch (e: SecurityException) {
        MemoryRead.Unreadable(COULD_NOT_READ)
    }

    /**
     * Writes [text] if the file still is what [expected] says, through a temp file beside it and a rename, so nothing
     * ever reads half a file. No fallback to writing in place: if the rename fails, the old file stays whole. A link
     * is followed, so the file it points at changes and the link stays. Between the check and the rename there is a
     * moment where another writer can slip in; files have no lock that Claude Code would respect.
     */
    fun save(file: File, text: String, expected: MemoryStamp): MemorySave {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return MemorySave.Failed("It's too large to save here: ${sizeLabel(bytes.size.toLong())}. The limit is ${sizeLabel(MAX_BYTES.toLong())}.")
        return try {
            val target = file.canonicalFile
            val current = stampOf(target)
            if (current != expected) return MemorySave.Conflict(current)
            val parent = target.parentFile ?: return MemorySave.Failed(COULD_NOT_SAVE)
            if (!parent.isDirectory && !parent.mkdirs()) return MemorySave.Failed("Couldn't create the folder for it.")
            val temp = File.createTempFile(".$NAME.", ".tmp", parent)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) return MemorySave.Failed(COULD_NOT_SAVE)
            } finally {
                // Gone already after a rename.
                temp.delete()
            }
            MemorySave.Saved(stampOf(bytes))
        } catch (e: IOException) {
            MemorySave.Failed(COULD_NOT_SAVE)
        } catch (e: SecurityException) {
            MemorySave.Failed(COULD_NOT_SAVE)
        }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }

    private fun stampOf(file: File): MemoryStamp = when {
        !file.exists() -> MemoryStamp.Missing
        !file.isFile -> NotAFile
        else -> FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance(HASH)
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            MemoryStamp(hex(digest.digest()))
        }
    }

    private fun stampOf(bytes: ByteArray) = MemoryStamp(hex(MessageDigest.getInstance(HASH).digest(bytes)))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Strict: a file that isn't UTF-8 would come back changed after a save, so it isn't text here. */
    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .takeUnless { it.contains('\u0000') }
    } catch (e: CharacterCodingException) {
        null
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val into = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val count = input.read(into, filled, limit - filled)
            if (count < 0) break
            filled += count
        }
        return into.copyOf(filled)
    }

    private const val HASH = "SHA-256"
    private const val BUFFER = 16 * 1024
    private const val COULD_NOT_READ = "Couldn't read it."
    private const val COULD_NOT_SAVE = "Couldn't save it. The file is unchanged."
}
