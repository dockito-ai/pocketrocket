package com.pocketrocket.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/** What a running backup or restore reports. Steps are indexes into [BackupRunner.BACKUP_STEPS] or [BackupRunner.RESTORE_STEPS]. */
interface BackupEvents {
    fun step(index: Int)
    fun progress(bytes: Long, total: Long, item: String)
}

/** The two jobs, on plain files, with no Android in them. All the safety promises of decision 53 are in here. */
internal object BackupRunner {
    val BACKUP_STEPS = listOf("Working out what goes in", "Packing and encrypting", "Reading it back and checking", "Replacing the old backup")
    val RESTORE_STEPS = listOf("Unpacking and checking", "Merging accounts and reapplying setup defaults", "Moving into place", "Restarting PocketRocket")

    const val WORK_DIR = ".pr-restore"
    /** Room kept free beyond what the numbers say: tar headers, directory blocks. */
    private const val SPARE = 16L * 1024 * 1024

    fun tempFor(dest: File) = File(dest.parentFile, ".${dest.name}.tmp")

    /** What a backup needs free beside the old file, at most. Compression usually makes it less. */
    fun neededFor(totalBytes: Long) = totalBytes + BackupFormat.overhead(totalBytes) + SPARE

    /**
     * Writes a new [dest]. The temp beside it is read back and checked with the passphrase before it is renamed
     * over the old one. On any failure or cancel the temp is deleted and [dest] isn't touched.
     */
    fun backup(
        dest: File,
        home: File,
        passphrase: CharArray,
        appVersion: String,
        repoFacts: (File) -> RepoFacts?,
        events: BackupEvents,
        isCancelled: () -> Boolean,
        now: Long = System.currentTimeMillis(),
        iterations: Int = BackupFormat.ITERATIONS,
        replace: (File, File) -> Unit = ::replaceFile,
    ) {
        val temp = tempFor(dest)
        try {
            events.step(0)
            val plan = BackupScanner(home, repoFacts, isCancelled).scan()
            dest.parentFile?.mkdirs()
            temp.delete()
            val needed = neededFor(plan.totalBytes)
            val free = dest.parentFile!!.usableSpace
            if (free in 0 until needed) throw NotEnoughSpace(needed, free)

            events.step(1)
            val total = plan.totalBytes
            val written = BufferedOutputStream(FileOutputStream(temp), 256 * 1024).use { file ->
                EncryptingOutput(file, passphrase, iterations).use { sealed ->
                    BackupArchive.write(sealed, plan.manifest(now, appVersion), plan.entries, { bytes, item -> events.progress(bytes, total, item) }, isCancelled)
                }
            }

            events.step(2)
            events.progress(0, written.bytes, "")
            verify(temp, passphrase, written, home.parentFile!!.path, events, isCancelled)

            events.step(3)
            if (isCancelled()) throw BackupCancelled()
            replace(temp, dest)
        } finally {
            temp.delete()
        }
    }

    /** Reads the whole temp back the way a restore would, with the same checks on every entry. */
    private fun verify(file: File, passphrase: CharArray, written: BackupArchive.Written, filesRoot: String, events: BackupEvents, isCancelled: () -> Boolean) {
        var entries = 0
        var bytes = 0L
        var manifest: BackupManifest? = null
        val buffer = ByteArray(64 * 1024)
        DecryptingInput(BufferedInputStream(file.inputStream(), 256 * 1024), passphrase).use { input ->
            BackupArchive.read(input, { manifest = it }) { entry, content ->
                if (isCancelled()) throw BackupCancelled()
                EntryChecks.check(entry, manifest!!, filesRoot)
                entries++
                while (true) {
                    val n = content.read(buffer)
                    if (n < 0) break
                    bytes += n
                    events.progress(bytes, written.bytes, "")
                }
            }
        }
        if (entries != written.entries || bytes != written.bytes) throw IOException("The new backup didn't read back the same.")
    }

    /** Renames over the old file. Where that isn't allowed, the old one is set aside until the new one is in place. */
    fun replaceFile(temp: File, dest: File) {
        if (temp.renameTo(dest)) return
        try {
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // Below.
        }
        val aside = File(dest.parentFile, ".${dest.name}.old")
        aside.delete()
        if (dest.exists() && !dest.renameTo(aside)) throw IOException("Couldn't replace the old backup. It is untouched.")
        if (!temp.renameTo(dest)) {
            aside.renameTo(dest)
            throw IOException("Couldn't replace the old backup. It is untouched.")
        }
        aside.delete()
    }

    /** Opens [open]'s file far enough to say what's in it. Nothing on the phone changes. */
    fun preview(open: () -> InputStream, passphrase: CharArray, home: File): RestorePreview =
        open().use { raw ->
            DecryptingInput(BufferedInputStream(raw, 256 * 1024), passphrase).use { input ->
                RestorePreviewer.build(BackupArchive.readManifest(input), home)
            }
        }

    /**
     * Restores a file over [home]: unpacks and checks everything in a staging folder beside it, arranges the
     * staged accounts to fit the phone's, and only then moves into place. Anything before the last step leaves the
     * phone as it was, and the installer rolls itself back if that step fails.
     */
    fun restore(
        open: () -> InputStream,
        passphrase: CharArray,
        home: File,
        sessionsRunning: () -> Int,
        events: BackupEvents,
        isCancelled: () -> Boolean,
        move: (File, File) -> Unit = RestoreInstaller::rename,
    ) {
        val filesRoot = home.parentFile!!
        val work = File(filesRoot, WORK_DIR)
        RestoreInstaller(work, filesRoot).recover()
        clear(work)
        try {
            sessionsRunning().let { if (it > 0) throw SessionsRunning(it) }
            events.step(0)
            val staged = File(work, RestoreInstaller.STAGED).apply { mkdirs() }
            open().use { raw ->
                DecryptingInput(BufferedInputStream(raw, 256 * 1024), passphrase).use { input ->
                    Staging(staged, filesRoot.path, events, isCancelled).unpack(input)
                }
            }

            events.step(1)
            if (isCancelled()) throw BackupCancelled()
            val accounts = StagedAccounts(File(staged, BackupAllowlist.HOME), home, work)
            accounts.merge(accounts.plan())

            events.step(2)
            if (isCancelled()) throw BackupCancelled()
            sessionsRunning().let { if (it > 0) throw SessionsRunning(it) }
            RestoreInstaller(work, filesRoot, move).install()
        } finally {
            clear(work)
        }
    }

    /** Deletes [dir], including folders a backup restored without write access. */
    fun clear(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.forEach { it.setWritable(true, true) }
        dir.deleteRecursively()
    }
}

/** Every entry of a backup goes through this, read back after a backup and read for a restore. */
internal object EntryChecks {
    /** Throws [UnsafeBackup] unless [entry] is a plain file, folder or link, at a known path, and a link stays inside. */
    fun check(entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry, manifest: BackupManifest, filesRoot: String): String {
        val name = entry.name.trimEnd('/')
        if (!BackupAllowlist.isSafeName(name)) throw UnsafeBackup(name)
        val roots = manifest.workspaceRoots
        if (roots.any { !BackupAllowlist.isWorkspaceRoot(it) }) throw UnsafeBackup("a workspace folder")
        if (!BackupAllowlist.isKnown(name, roots)) throw UnsafeBackup(name)
        when {
            entry.isSymbolicLink -> if (!BackupAllowlist.linkStaysInside(name, entry.linkName, filesRoot, roots)) throw UnsafeBackup(name)
            // isFile is true for anything whose name has no trailing slash, so the other kinds are named.
            entry.isLink || entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> throw UnsafeBackup(name)
        }
        return name
    }
}

/** Unpacks into a folder, refusing as it goes. Nothing can land outside it: no entry may pass through a link. */
internal class Staging(private val root: File, private val filesRoot: String, private val events: BackupEvents, private val isCancelled: () -> Boolean) {
    private val safeFolders = hashSetOf(root.path)
    private val folderTimes = mutableListOf<Triple<File, Long, Int>>()

    fun unpack(input: InputStream) {
        var manifest: BackupManifest? = null
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        BackupArchive.read(input, { m ->
            manifest = m
            val needed = BackupRunner.neededFor(m.totalBytes)
            val free = root.usableSpace
            if (free in 0 until needed) throw NotEnoughSpace(needed, free)
        }) { entry, content ->
            if (isCancelled()) throw BackupCancelled()
            val m = manifest!!
            val name = EntryChecks.check(entry, m, filesRoot)
            val target = File(root, name)
            makeFolders(target.parentFile!!, name)
            when {
                entry.isSymbolicLink -> {
                    refuseExisting(target, name)
                    Files.createSymbolicLink(target.toPath(), File(entry.linkName).toPath())
                }
                entry.isDirectory -> {
                    if (!safeFolders.contains(target.path)) {
                        refuseExisting(target, name)
                        target.mkdir()
                        safeFolders += target.path
                    }
                    folderTimes += Triple(target, entry.modTime.time, entry.mode)
                }
                else -> {
                    refuseExisting(target, name)
                    Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                        while (true) {
                            if (isCancelled()) throw BackupCancelled()
                            val n = content.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            bytes += n
                            events.progress(bytes, m.totalBytes, itemOf(name))
                        }
                    }
                    Files.setPosixFilePermissions(target.toPath(), BackupArchive.permissionsOf(entry.mode))
                    Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(entry.modTime.time))
                }
            }
        }
        if (manifest == null) throw DamagedBackup()
        // Last, deepest first: a folder without write access can't take its files, and writing changes its time.
        folderTimes.asReversed().forEach { (dir, time, mode) ->
            Files.setPosixFilePermissions(dir.toPath(), BackupArchive.permissionsOf(mode or 448))
            Files.setLastModifiedTime(dir.toPath(), FileTime.fromMillis(time))
        }
    }

    private fun refuseExisting(target: File, name: String) {
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw UnsafeBackup(name)
    }

    /** Makes the folders above an entry, refusing any that already exists as a link or a file. */
    private fun makeFolders(dir: File, name: String) {
        if (dir.path in safeFolders) return
        makeFolders(dir.parentFile!!, name)
        if (Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) throw UnsafeBackup(name)
        } else {
            dir.mkdir()
        }
        safeFolders += dir.path
    }

    private fun itemOf(name: String): String {
        val parts = name.split('/')
        return when {
            name.startsWith(BackupAllowlist.PROJECTS + "/") && parts.size > 2 -> parts[2]
            name.startsWith(BackupAllowlist.ACCOUNTS + "/github") -> "GitHub"
            name.startsWith(BackupAllowlist.ACCOUNTS + "/dockito") -> "Dockito"
            name.startsWith(BackupAllowlist.MAIN_CLAUDE) || name.startsWith(BackupAllowlist.ACCOUNTS + "/claude") -> "Claude config"
            parts.size > 1 && parts[1].startsWith(".") -> "Workspace list"
            else -> parts.last()
        }
    }
}
