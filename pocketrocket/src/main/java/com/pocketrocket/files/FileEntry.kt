package com.pocketrocket.files

import java.io.File

/**
 * One row of a folder in the file manager (decision 78). [size] is the file's length in bytes; for a folder,
 * [items] is how many entries it holds (null when it can't be read). [modified] is in epoch milliseconds.
 * [isLink] marks a symlink, shown as one and never followed when deleting.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
    val items: Int? = null,
) {
    val hidden: Boolean get() = name.startsWith(".")
}

/** Reading and ordering a folder's entries. */
object FileListing {
    /** Folders first, then files, each by name ignoring case (exact name breaks ties, so the order is stable). */
    val order: Comparator<FileEntry> = compareBy<FileEntry> { !it.isDir }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.name }

    fun sorted(entries: List<FileEntry>): List<FileEntry> = entries.sortedWith(order)

    /** [entries] without dot files unless [showHidden]. */
    fun visible(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> =
        if (showHidden) entries else entries.filterNot { it.hidden }

    /**
     * The entries of [dir], sorted, hidden ones included. Reads the disk: call it off the main thread.
     * Throws when the folder can't be read.
     */
    fun read(dir: File): List<FileEntry> {
        val children = dir.listFiles() ?: throw java.io.IOException("Can't read ${dir.path}")
        return sorted(children.map(::entry))
    }

    fun entry(file: File): FileEntry {
        val isDir = file.isDirectory
        return FileEntry(
            name = file.name,
            path = file.path,
            isDir = isDir,
            isLink = FileOps.isSymlink(file),
            size = if (isDir) 0L else file.length(),
            modified = file.lastModified(),
            items = if (isDir) file.list()?.size else null,
        )
    }
}
