package com.pocketrocket.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Moves a staged tree into place, all of it or none of it. Each thing is one rename on the same disk: the
 * old one goes aside into [oldDir], the new one takes its place. A journal in [workDir] lists each move before it
 * happens, so a failure rolls back at once and a crash mid-way is rolled back by [recover] on the next start.
 *
 * Files and links move one by one. A repo (a folder with `.git`) and the trees in [BackupAllowlist.isWholeTree]
 * are replaced whole. Other folders merge: whatever else is on the phone in them stays.
 */
internal class RestoreInstaller(
    private val workDir: File,
    private val filesRoot: File,
    private val move: (File, File) -> Unit = ::rename,
) {
    private val stagedDir = File(workDir, STAGED)
    private val oldDir = File(workDir, OLD)
    private val journal = File(workDir, JOURNAL)

    fun install() {
        journal.parentFile?.mkdirs()
        val moved = mutableListOf<String>()
        try {
            journal.bufferedWriter().use { log ->
                walk(stagedDir) { name ->
                    log.write(name)
                    log.newLine()
                    log.flush()
                    moved += name
                    moveIn(name)
                }
            }
        } catch (e: Exception) {
            rollback(moved)
            journal.delete()
            throw e
        }
        // Past this point the restore has happened.
        journal.delete()
    }

    private fun walk(dir: File, onUnit: (String) -> Unit) {
        dir.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
            val name = child.relativeTo(stagedDir).path
            val target = File(filesRoot, name)
            val folder = Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)
            val whole = BackupAllowlist.isWholeTree(name) || Files.exists(File(child, ".git").toPath(), LinkOption.NOFOLLOW_LINKS)
            if (folder && !whole && Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) walk(child, onUnit) else onUnit(name)
        }
    }

    private fun moveIn(name: String) {
        val target = File(filesRoot, name)
        val aside = File(oldDir, name)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            aside.parentFile?.mkdirs()
            move(target, aside)
        }
        target.parentFile?.mkdirs()
        move(File(stagedDir, name), target)
    }

    private fun rollback(names: List<String>) {
        names.asReversed().forEach { name ->
            val target = File(filesRoot, name)
            val staged = File(stagedDir, name)
            val aside = File(oldDir, name)
            // The new one reached its place only if it is no longer staged.
            if (!Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS) && Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                runCatching { staged.parentFile?.mkdirs(); rename(target, staged) }
            }
            if (Files.exists(aside.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                runCatching { target.parentFile?.mkdirs(); rename(aside, target) }
            }
        }
    }

    /** Rolls back a restore that was cut off mid-move. True when there was one. */
    fun recover(): Boolean {
        if (!journal.exists()) return false
        rollback(journal.readLines().filter { it.isNotEmpty() })
        journal.delete()
        return true
    }

    companion object {
        const val STAGED = "staged"
        const val OLD = "old"
        const val JOURNAL = "journal"

        fun rename(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                throw IOException("Couldn't move ${to.name} into place.", e)
            }
        }
    }
}
