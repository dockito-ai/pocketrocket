package com.pocketrocket.sessions

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads a background shell's output file ([ShellRun.outputFile]), which Claude Code keeps in its temp folder under the
 * app's own user. Colour codes and carriage-return redraws are taken out so it reads like a log.
 */
object ShellOutput {

    /** Lines shown live; older ones are a Copy all away. */
    const val TAIL_LINES = 200

    /** Bytes read from the end for the tail: enough for [TAIL_LINES] long lines without reading a huge log. */
    private const val TAIL_BYTES = 64 * 1024L

    /** Copy all stops here, so a runaway log can't fill the clipboard. */
    private const val ALL_BYTES = 2 * 1024 * 1024L

    /** The last [lines] lines, or null when the file can't be read (not there yet, or gone with its session). */
    fun tail(path: String, lines: Int = TAIL_LINES): List<String>? {
        val text = readEnd(path, TAIL_BYTES) ?: return null
        val all = clean(text).lines()
        // A cut at the start of the window leaves half a line; drop it unless the whole file fit.
        val whole = File(path).length() <= TAIL_BYTES
        return (if (whole) all else all.drop(1)).dropLastWhile { it.isEmpty() }.takeLast(lines)
    }

    /** The whole output (its last 2 MB for a longer one), for Copy all. */
    fun all(path: String): String? = readEnd(path, ALL_BYTES)?.let(::clean)

    private fun readEnd(path: String, max: Long): String? = runCatching {
        RandomAccessFile(path, "r").use { file ->
            val length = file.length()
            val from = (length - max).coerceAtLeast(0)
            val bytes = ByteArray((length - from).toInt())
            file.seek(from)
            file.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    private val ansi = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\-_])""")

    /** Strips terminal escapes, and keeps only what the last `\r` in a line left on screen (progress bars). */
    internal fun clean(text: String): String = text.replace(ansi, "").replace("\r\n", "\n")
        .split('\n').joinToString("\n") { it.trimEnd('\r').substringAfterLast('\r') }
}
