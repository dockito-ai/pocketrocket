package com.pocketrocket.usage

import android.system.Os
import android.system.OsConstants
import java.util.concurrent.ConcurrentHashMap

/** The little of the file system a walk needs, apart so the walk can be tested. */
internal interface WalkFs {
    /** What the path itself is, never what a link points to. Null when it can't be read. */
    fun lstat(path: String): Entry?

    /** Names in a folder. Null when it can't be read. */
    fun list(path: String): Array<String>?

    class Entry(val isDir: Boolean, val bytes: Long, val device: Long, val inode: Long, val links: Long, val isLink: Boolean = false)
}

internal object AndroidWalkFs : WalkFs {
    override fun lstat(path: String): WalkFs.Entry? = runCatching {
        val s = Os.lstat(path)
        // Blocks are always 512 bytes here, and show what the file takes on disk rather than its length.
        WalkFs.Entry(OsConstants.S_ISDIR(s.st_mode), s.st_blocks * 512, s.st_dev, s.st_ino, s.st_nlink, OsConstants.S_ISLNK(s.st_mode))
    }.getOrNull()

    override fun list(path: String): Array<String>? = java.io.File(path).list()
}

/**
 * Which name of a hard linked file carries its bytes. The first name seen keeps them for good, so a later walk
 * of any part of the tree gives the same answer as the walk that made the totals. Safe across threads.
 */
class HardLinks {
    private val owners = ConcurrentHashMap<Pair<Long, Long>, String>()

    internal fun counts(path: String, entry: WalkFs.Entry): Boolean {
        if (entry.isDir || entry.links <= 1) return true
        val owner = owners.putIfAbsent(entry.device to entry.inode, path)
        return owner == null || owner == path
    }
}

/** What a walk found: bytes for each kind, and how many folders it couldn't read. */
internal class WalkResult<K>(val bytes: Map<K, Long>, val unreadable: Int)

/**
 * Walks a folder tree, handing [onEntry] each path that carries bytes, with its kind. Each path takes the kind
 * given for it in [kinds], or else its parent's. Paths in [kinds] and given to [onEntry] are relative to the same
 * place: [relative] is where [root] sits. Links are the link itself and never followed: `~/storage` points at the
 * phone's shared storage. A file with several hard links is reported under one name only, by [links]. With [only],
 * other kinds are left out, and not entered unless they hold some of that kind further down. [onFolder] runs before
 * each folder, so a cancelled caller can stop the walk by throwing. Returns how many folders couldn't be read.
 */
internal fun <K> walkEntries(
    fs: WalkFs,
    root: String,
    rootKind: K,
    kinds: Map<String, K>,
    relative: String = "",
    only: K? = null,
    links: HardLinks = HardLinks(),
    onFolder: () -> Unit = {},
    onEntry: (relative: String, kind: K, entry: WalkFs.Entry) -> Unit,
): Int {
    var unreadable = 0
    val folders = ArrayDeque<Triple<String, String, K>>()

    fun holdsWanted(path: String) = kinds.any { (key, kind) -> kind == only && (path.isEmpty() || key.startsWith("$path/")) }

    fun visit(path: String, rel: String, inherited: K) {
        val entry = fs.lstat(path) ?: return
        val kind = kinds[rel] ?: inherited
        val wanted = only == null || kind == only
        if (wanted && links.counts(path, entry)) onEntry(rel, kind, entry)
        if (entry.isDir && (wanted || holdsWanted(rel))) folders.addLast(Triple(path, rel, kind))
    }

    visit(root, relative, rootKind)
    while (folders.isNotEmpty()) {
        onFolder()
        val (path, rel, kind) = folders.removeLast()
        val names = fs.list(path)
        if (names == null) {
            unreadable++
            continue
        }
        names.forEach { name -> visit("$path/$name", if (rel.isEmpty()) name else "$rel/$name", kind) }
    }
    return unreadable
}

/** Adds up a folder tree by kind, so every byte lands in exactly one kind. See [walkEntries]. */
internal fun <K> walkDisk(
    fs: WalkFs,
    root: String,
    rootKind: K,
    kinds: Map<String, K>,
    links: HardLinks = HardLinks(),
    onFolder: () -> Unit = {},
): WalkResult<K> {
    val totals = HashMap<K, Long>()
    val unreadable = walkEntries(fs, root, rootKind, kinds, links = links, onFolder = onFolder) { _, kind, entry ->
        totals[kind] = (totals[kind] ?: 0L) + entry.bytes
    }
    return WalkResult(totals, unreadable)
}
