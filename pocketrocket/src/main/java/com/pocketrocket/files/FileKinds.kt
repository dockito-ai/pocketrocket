package com.pocketrocket.files

import java.io.File

/** How the file manager shows a file: as text, as an image, or handed to another app. */
enum class FileKind { Text, Image, Other }

/** What a file is, from its name and its first bytes. */
object FileKinds {
    /** The text viewer reads at most this much (about 1 MB); bigger files show their start and say so. */
    const val TEXT_LIMIT = 1024 * 1024

    /** How many leading bytes decide text or binary. */
    const val SNIFF_BYTES = 8 * 1024

    /** Images Android decodes itself. SVG is text, and shows as text. */
    val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    private const val PACKAGE_MIME = "application/vnd.android.package-archive"
    private val PACKAGE_EXTENSIONS = setOf("apk", "apks", "apkm", "xapk", "aab")

    /** Control characters text files do use. */
    private val TEXT_CONTROLS = setOf(0x09, 0x0A, 0x0D, 0x0C, 0x08, 0x1B)

    /** Kinds never worth reading as text, whatever their first bytes look like. */
    private val binaryExtensions = setOf(
        "zip", "gz", "tgz", "xz", "bz2", "zst", "7z", "rar", "tar", "jar", "aar", "class", "dex", "so", "o", "a",
        "pdf", "mp3", "mp4", "m4a", "mkv", "mov", "webm", "ogg", "opus", "wav", "flac", "avi",
        "ttf", "otf", "woff", "woff2", "ico", "psd", "db", "sqlite", "bin", "exe", "dll", "dylib",
        "jks", "keystore", "p12", "onnx", "pyc",
    ) + PACKAGE_EXTENSIONS

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** The kind from the name alone, or null when only the content can tell. */
    fun byName(name: String): FileKind? = when (extension(name)) {
        in imageExtensions -> FileKind.Image
        in binaryExtensions -> FileKind.Other
        else -> null
    }

    /** The kind of [name] whose first bytes are [sample]. */
    fun of(name: String, sample: ByteArray, length: Int = sample.size): FileKind =
        byName(name) ?: if (looksBinary(sample, length)) FileKind.Other else FileKind.Text

    /** Reads the first bytes of [file] and decides. Reads the disk: call it off the main thread. */
    fun of(file: File): FileKind {
        byName(file.name)?.let { return it }
        val sample = ByteArray(SNIFF_BYTES)
        val read = file.inputStream().use { input -> readFully(input, sample) }
        return if (looksBinary(sample, read)) FileKind.Other else FileKind.Text
    }

    /**
     * True when the first [length] bytes of [sample] aren't text: any NUL byte, more than one in ten bytes
     * a control character other than tab, newline, carriage return, form feed, backspace and escape, or more
     * than one in twenty bytes outside valid UTF-8. A multi-byte character cut off at the end of the sample
     * doesn't count. An empty file is text.
     */
    fun looksBinary(sample: ByteArray, length: Int = sample.size): Boolean {
        val n = length.coerceIn(0, sample.size)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = sample[i].toInt() and 0xFF
            if (b == 0) return true
            if ((b < 0x20 && b !in TEXT_CONTROLS) || b == 0x7F) control++
        }
        if (control * 10 > n) return true
        return invalidUtf8(sample, n) * 20 > n
    }

    /** Bytes in the first [n] of [bytes] that aren't part of a valid UTF-8 sequence, ignoring one cut off at the end. */
    internal fun invalidUtf8(bytes: ByteArray, n: Int): Int {
        var bad = 0
        var i = 0
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            val need = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> -1
            }
            if (need < 0) { bad++; i++; continue }
            if (i + need >= n && need > 0) {
                // Cut off by the sample's end: fine if what is there so far is continuation bytes.
                val rest = (i + 1 until n).all { (bytes[it].toInt() and 0xC0) == 0x80 }
                if (!rest) bad++
                break
            }
            val ok = (1..need).all { (bytes[i + it].toInt() and 0xC0) == 0x80 }
            if (ok) i += need + 1 else { bad++; i++ }
        }
        return bad
    }

    /** Android app packages: never opened from here (that would start Android's installer), only shared. */
    fun isPackage(name: String, mime: String? = null): Boolean = mime == PACKAGE_MIME || extension(name) in PACKAGE_EXTENSIONS

    /**
     * The power-of-two step to decode a [width] by [height] image so its longer side is at most about [max]:
     * the smallest one that fits (1 when it already does).
     */
    fun sampleSize(width: Int, height: Int, max: Int): Int {
        var step = 1
        val longer = maxOf(width, height)
        if (max <= 0) return 1
        while (longer / step > max) step *= 2
        return step
    }

    /** Fills [buffer] from [input] as far as the stream goes; returns how many bytes it read. */
    internal fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
