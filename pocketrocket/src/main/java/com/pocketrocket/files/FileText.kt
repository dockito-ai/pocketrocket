package com.pocketrocket.files

import java.io.File

/** A text file as the viewer shows it: [text] is its start when [truncated]; [size] is the whole file's. */
data class TextFile(val text: String, val size: Long, val truncated: Boolean)

object FileText {
    /**
     * Reads [file] as UTF-8 for the read-only viewer, at most [limit] bytes. Past the limit the text ends at the
     * last whole line before it. Null when the file isn't text (see [FileKinds.looksBinary]). Reads the disk: call
     * it off the main thread.
     */
    fun read(file: File, limit: Int = FileKinds.TEXT_LIMIT): TextFile? {
        val size = file.length()
        val buffer = ByteArray(minOf(size, limit.toLong()).toInt().coerceAtLeast(0))
        val read = file.inputStream().use { FileKinds.readFully(it, buffer) }
        if (FileKinds.looksBinary(buffer, minOf(read, FileKinds.SNIFF_BYTES))) return null
        val truncated = size > read
        var end = read
        if (truncated) {
            val newline = (read - 1 downTo 0).firstOrNull { buffer[it] == '\n'.code.toByte() }
            if (newline != null && newline > 0) end = newline + 1
        }
        return TextFile(String(buffer, 0, end, Charsets.UTF_8), size, truncated)
    }
}
