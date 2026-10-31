package com.pocketrocket.backup

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/** Called as content moves: bytes of file content so far, and the item in hand. */
typealias ArchiveProgress = (bytes: Long, item: String) -> Unit

/**
 * The gzip-compressed tar inside the encrypted file, with [BackupManifest] as its first entry. A tar written
 * here rather than the prefix's: each entry is checked as it is read, before anything lands on disk, and a
 * restore never hands an untrusted file to a tool that would unpack it first. Keeps links and permission bits.
 */
internal object BackupArchive {
    private const val BUFFER = 64 * 1024

    data class Written(val entries: Int, val bytes: Long)

    fun write(out: OutputStream, manifest: BackupManifest, plan: List<PlanEntry>, progress: ArchiveProgress, isCancelled: () -> Boolean): Written {
        val tar = TarArchiveOutputStream(GZIPOutputStream(out, BUFFER), "UTF-8").apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            setAddPaxHeadersForNonAsciiNames(true)
        }
        var written = 0
        var bytes = 0L
        tar.use {
            val json = manifest.toJson().toByteArray()
            it.putArchiveEntry(TarArchiveEntry(BackupManifest.ENTRY).apply { size = json.size.toLong() })
            it.write(json)
            it.closeArchiveEntry()
            val buffer = ByteArray(BUFFER)
            for (entry in plan) {
                if (isCancelled()) throw BackupCancelled()
                // Taken again now: the scan may be minutes old. Something gone or changed kind just stays out.
                val attrs = try {
                    Files.readAttributes(entry.path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    continue
                }
                when (entry.kind) {
                    PlanEntry.Kind.Directory -> {
                        if (!attrs.isDirectory) continue
                        it.putArchiveEntry(TarArchiveEntry(entry.name + "/").apply { mode = modeOf(attrs) or DIR_FLAG; modTime = java.util.Date(attrs.lastModifiedTime().toMillis()) })
                        it.closeArchiveEntry()
                    }
                    PlanEntry.Kind.Link -> {
                        if (!attrs.isSymbolicLink) continue
                        it.putArchiveEntry(TarArchiveEntry(entry.name, TarConstants.LF_SYMLINK).apply { linkName = entry.linkTarget })
                        it.closeArchiveEntry()
                    }
                    PlanEntry.Kind.File -> {
                        if (!attrs.isRegularFile) continue
                        val copied = writeFile(it, entry, attrs, buffer, isCancelled) { n -> bytes += n; progress(bytes, entry.item) }
                        if (!copied) continue
                    }
                }
                written++
            }
        }
        return Written(written, bytes)
    }

    /** The header promises a size, so a file that shrinks mid-read is padded and one that grows is cut there. */
    private fun writeFile(tar: TarArchiveOutputStream, entry: PlanEntry, attrs: PosixFileAttributes, buffer: ByteArray, isCancelled: () -> Boolean, moved: (Int) -> Unit): Boolean {
        val input = try {
            Files.newInputStream(entry.path)
        } catch (_: NoSuchFileException) {
            return false
        }
        input.use { file ->
            val size = attrs.size()
            tar.putArchiveEntry(TarArchiveEntry(entry.name).apply {
                setSize(size)
                mode = modeOf(attrs) or FILE_FLAG
                modTime = java.util.Date(attrs.lastModifiedTime().toMillis())
            })
            var left = size
            while (left > 0) {
                if (isCancelled()) throw BackupCancelled()
                val n = file.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) break
                tar.write(buffer, 0, n)
                left -= n
                moved(n)
            }
            if (left > 0) java.util.Arrays.fill(buffer, 0)
            while (left > 0) {
                val n = minOf(left, buffer.size.toLong()).toInt()
                tar.write(buffer, 0, n)
                left -= n
            }
            tar.closeArchiveEntry()
        }
        return true
    }

    /** Reads the manifest and stops, for Restore's summary. [input] is left for the caller to close. */
    fun readManifest(input: InputStream): BackupManifest {
        val tar = openTar(input)
        return guarded { manifestOf(tar) }
    }

    /**
     * Reads the whole archive: the manifest first, then [onEntry] for every other entry, with the tar's stream
     * positioned at its content. Anything malformed becomes [DamagedBackup].
     */
    fun read(input: InputStream, onManifest: (BackupManifest) -> Unit, onEntry: (TarArchiveEntry, InputStream) -> Unit) {
        val tar = openTar(input)
        guarded {
            onManifest(manifestOf(tar))
            while (true) {
                val entry = tar.nextTarEntry ?: break
                onEntry(entry, tar)
            }
        }
    }

    private fun openTar(input: InputStream) = guarded { TarArchiveInputStream(GZIPInputStream(BufferedInputStream(input, BUFFER), BUFFER), "UTF-8") }

    private fun manifestOf(tar: TarArchiveInputStream): BackupManifest {
        val first = tar.nextTarEntry ?: throw DamagedBackup()
        if (first.name != BackupManifest.ENTRY || first.size > BackupManifest.MAX_SIZE) throw DamagedBackup()
        val bytes = ByteArray(first.size.toInt())
        var at = 0
        while (at < bytes.size) {
            val n = tar.read(bytes, at, bytes.size - at)
            if (n < 0) throw DamagedBackup()
            at += n
        }
        return BackupManifest.parse(String(bytes)) ?: throw DamagedBackup()
    }

    /** Authenticated content can still be malformed if it was crafted: every parser failure reads as damage. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: BackupException) {
        throw e
    } catch (e: ZipException) {
        throw DamagedBackup()
    } catch (e: EOFException) {
        throw DamagedBackup()
    } catch (e: IllegalArgumentException) {
        throw DamagedBackup()
    } catch (e: IOException) {
        // Includes a full disk during a restore, so it keeps its own message.
        if (e.message.orEmpty().contains("tar", ignoreCase = true)) throw DamagedBackup() else throw e
    }

    private const val FILE_FLAG = 0x8000
    private const val DIR_FLAG = 0x4000

    private val BITS = listOf(
        PosixFilePermission.OWNER_READ to 256, PosixFilePermission.OWNER_WRITE to 128, PosixFilePermission.OWNER_EXECUTE to 64,
        PosixFilePermission.GROUP_READ to 32, PosixFilePermission.GROUP_WRITE to 16, PosixFilePermission.GROUP_EXECUTE to 8,
        PosixFilePermission.OTHERS_READ to 4, PosixFilePermission.OTHERS_WRITE to 2, PosixFilePermission.OTHERS_EXECUTE to 1,
    )

    fun modeOf(attrs: PosixFileAttributes): Int = BITS.filter { it.first in attrs.permissions() }.sumOf { it.second }

    /** Only the nine permission bits: a backup can't set setuid or sticky. */
    fun permissionsOf(mode: Int): Set<PosixFilePermission> = BITS.filter { mode and it.second != 0 }.map { it.first }.toSet()
}
