package com.pocketrocket.files

import java.io.File

/**
 * The file manager's changes on disk: new folder, rename and delete. Each returns an error message, or null
 * when it worked. All of them touch the disk: call them off the main thread.
 */
object FileOps {
    /**
     * True for a symlink (java.nio's isSymbolicLink needs API 26): its real path isn't its parent's real path
     * plus its name. When that can't be worked out it counts as a link, so nothing is walked through it.
     */
    fun isSymlink(f: File): Boolean = runCatching {
        val parent = f.absoluteFile.parentFile?.canonicalFile ?: return@runCatching false
        File(parent, f.name).canonicalFile != File(parent, f.name)
    }.getOrDefault(true)

    fun newFolder(parent: File, name: String): String? {
        FileRules.nameProblem(name)?.let { return it }
        val dir = File(parent, name)
        return when {
            taken(parent, name) -> "$name already exists."
            !dir.mkdir() -> "Couldn't create $name."
            else -> null
        }
    }

    /** Renames [from] to [name] in the same folder. Never replaces something already there. */
    fun rename(from: File, name: String): String? {
        FileRules.nameProblem(name)?.let { return it }
        if (name == from.name) return null
        val parent = from.absoluteFile.parentFile ?: return "Couldn't rename ${from.name}."
        if (taken(parent, name)) return "$name already exists."
        return if (from.renameTo(File(parent, name))) null else "Couldn't rename ${from.name}."
    }

    /** Whether [parent] already has an entry called [name]: exists() follows links, list() also sees a dangling one. */
    private fun taken(parent: File, name: String) = File(parent, name).exists() || parent.list()?.contains(name) == true

    /**
     * Deletes [target]. A symlink is removed as a link, whatever it points to. A folder is emptied depth first
     * without ever following a symlink inside it (each link is removed, not what it points to) and without going
     * into a folder whose real path is outside [target]'s.
     */
    fun delete(target: File): String? {
        val file = target.absoluteFile
        if (isSymlink(file) || !file.isDirectory) {
            return if (file.delete()) null else "Couldn't delete ${file.name}."
        }
        val root = runCatching { file.canonicalPath }.getOrElse { return "Couldn't delete ${file.name}." }
        return if (deleteTree(file, root)) null else "Couldn't delete everything in ${file.name}; what's left is still there."
    }

    private fun deleteTree(file: File, root: String): Boolean {
        if (isSymlink(file) || !file.isDirectory) return file.delete()
        val real = runCatching { file.canonicalPath }.getOrNull() ?: return false
        if (!FileRules.isInside(real, root)) return false
        var ok = true
        file.listFiles()?.forEach { if (!deleteTree(it, root)) ok = false } ?: return false
        return file.delete() && ok
    }

    /** What deleting [target] would remove: files (links count as files) and folders inside it, stopping at [cap]. */
    data class Contents(val files: Int, val folders: Int, val capped: Boolean)

    fun contents(target: File, cap: Int = 10_000): Contents {
        var files = 0
        var folders = 0
        fun walk(dir: File) {
            dir.listFiles()?.forEach { child ->
                if (files + folders >= cap) return
                if (!isSymlink(child) && child.isDirectory) { folders++; walk(child) } else files++
            }
        }
        if (!isSymlink(target) && target.isDirectory) walk(target)
        return Contents(files, folders, files + folders >= cap)
    }
}
