package com.pocketrocket.usage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.PriorityQueue

/** One row of the storage tree. [id] is unique in the tree and starts with its parent's id then `/`. */
class StorageNode internal constructor(
    val id: String,
    val name: String,
    val bytes: Long,
    val note: String? = null,
    /** The folder this row sits in, for telling apart rows too deep to show it by indent. */
    val place: String? = null,
    internal val opening: Opening? = null,
    /** A folder in "Not from a package" with no such files anywhere inside, only folder entries. */
    internal val bare: Boolean = false,
) {
    val opens: Boolean get() = opening != null
}

/** How a row opens. Null on a [StorageNode] means there's nothing to open. */
internal sealed interface Opening {
    class Kind(val kind: StorageKind) : Opening
    class Folder(val relative: String, val kind: StorageKind, val unownedOnly: Boolean = false) : Opening
    class Package(val files: List<StorageNode>) : Opening
}

/** A row and how deep it sits, in the order rows read on screen. */
class TreeRow(val node: StorageNode, val depth: Int)

/** The rows showing: [children] gives what's under a node, or null if it's closed or not loaded yet. */
fun flattenTree(roots: List<StorageNode>, children: (StorageNode) -> List<StorageNode>?): List<TreeRow> {
    val rows = ArrayList<TreeRow>()
    fun add(nodes: List<StorageNode>, depth: Int) {
        nodes.forEach { node ->
            rows += TreeRow(node, depth)
            children(node)?.let { add(it, depth + 1) }
        }
    }
    add(roots, 0)
    return rows
}

/** Keeps the [limit] largest rows and adds up the rest, however many come, so big folders stay cheap. */
internal class LargestRows(private val limit: Int = SHOWN) {
    private val kept = PriorityQueue<StorageNode>(compareBy { it.bytes })
    private var smallerCount = 0
    private var smallerBytes = 0L

    /** Every byte added so far, merged or not. */
    var total = 0L
        private set

    fun add(node: StorageNode) {
        total += node.bytes
        kept.add(node)
        if (kept.size > limit) {
            val smallest = kept.poll()!!
            smallerCount++
            smallerBytes += smallest.bytes
        }
    }

    /** Largest first, with the merged row last. */
    fun rows(parentId: String): List<StorageNode> {
        val sorted = kept.sortedWith(compareByDescending<StorageNode> { it.bytes }.thenBy { it.name })
        if (smallerCount == 0) return sorted
        val name = if (smallerCount == 1) "1 smaller item" else "$smallerCount smaller items"
        return sorted + StorageNode("$parentId/\u0000smaller", name, smallerBytes)
    }

    companion object {
        const val SHOWN = 50
    }
}

/** The package a dpkg `<name>.list` file belongs to, without any `:arch`. Null for dpkg's other files. */
internal fun dpkgPackageName(fileName: String): String? =
    if (fileName.endsWith(".list")) fileName.removeSuffix(".list").substringBefore(':') else null

/**
 * A line of a dpkg `.list` file as a path relative to the app's data folder. Matched on `files/usr/` because
 * lines may still carry the prefix the package was built for. Null for anything outside it, like `/.`.
 */
internal fun dpkgRelativePath(line: String): String? {
    val at = line.trim().indexOf("/$USR/")
    return if (at < 0) null else line.trim().substring(at + 1).trimEnd('/')
}

/** Path to the package that owns it. A path in two `.list` files goes to the first package by name, so it counts once. */
internal fun dpkgOwners(lists: List<Pair<String, String>>): Map<String, String> {
    val owners = HashMap<String, String>()
    lists.sortedBy { it.first }.forEach { (pkg, text) ->
        text.lineSequence().forEach { line -> dpkgRelativePath(line)?.let { owners.putIfAbsent(it, pkg) } }
    }
    return owners
}

private const val USR = "files/usr"
private const val DPKG_INFO = "$USR/var/lib/dpkg/info"

/**
 * What's inside the storage rows, a level at a time. Each level is measured with the walk behind the totals, with
 * the same kinds, hard link choices and on-disk sizes, so the rows of a level add up to the row that opened them
 * unless files change in between. Nothing here changes anything on disk.
 */
class StorageTree internal constructor(
    private val fs: WalkFs,
    private val dataDir: String,
    private val kindBytes: Map<StorageKind, Long>,
    private val links: HardLinks,
    private val readText: (String) -> String? = { path -> runCatching { File(path).readText() }.getOrNull() },
) {
    /** Package ownership from the last time Packages opened, for "Not from a package". */
    @Volatile private var owners: Map<String, String> = emptyMap()

    /** One row for each kind. */
    val roots: List<StorageNode> = StorageKind.entries.map { kind ->
        val bytes = kindBytes[kind] ?: 0L
        StorageNode(kind.name, kind.label, bytes, kind.note, opening = if (bytes > 0) Opening.Kind(kind) else null)
    }

    /** What's inside [node], largest first. Walks the disk: call off the main thread. Cancellable. */
    suspend fun children(node: StorageNode): List<StorageNode> {
        val job = currentCoroutineContext()
        val tick = { job.ensureActive() }
        return when (val opening = node.opening) {
            null -> emptyList()
            is Opening.Package -> opening.files
            is Opening.Kind -> when (opening.kind) {
                StorageKind.Packages -> packages(node.id, tick)
                StorageKind.Other -> appAndOther(node, tick)
                else -> kindRows(node.id, opening.kind, tick)
            }
            is Opening.Folder -> LargestRows().also { folderInto(it, node.id, opening, tick) }.rows(node.id)
        }
    }

    private fun path(relative: String) = if (relative.isEmpty()) dataDir else "$dataDir/$relative"

    /** A kind with one folder opens straight to that folder's contents; with several, to those folders. */
    private fun kindRows(parentId: String, kind: StorageKind, tick: () -> Unit): List<StorageNode> {
        val folders = StorageUsage.FOLDERS.filterValues { it == kind }.keys.sorted()
        val largest = LargestRows()
        folders.singleOrNull()?.let { only ->
            folderInto(largest, parentId, Opening.Folder(only, kind), tick)
            return largest.rows(parentId)
        }
        folders.forEach { relative ->
            tick()
            rowFor(relative, UsageText.place(relative), kind, parentId, place = null, unownedOnly = false, tick)?.let(largest::add)
        }
        return largest.rows(parentId)
    }

    /** The app's own folders, then whatever else Android counts for the app, which no walk can see into. */
    private fun appAndOther(node: StorageNode, tick: () -> Unit): List<StorageNode> {
        val largest = LargestRows()
        folderInto(largest, node.id, Opening.Folder("", StorageKind.Other), tick)
        val rest = node.bytes - largest.total
        if (rest > 0) largest.add(StorageNode("${node.id}/\u0000app", "The app itself", rest, "The APK and anything else Android counts"))
        return largest.rows(node.id)
    }

    /** Adds the rows inside a folder to [largest], including what the folder's own entry takes, so they add up to it. */
    private fun folderInto(largest: LargestRows, parentId: String, folder: Opening.Folder, tick: () -> Unit) {
        val path = path(folder.relative)
        val own = fs.lstat(path)?.bytes ?: 0L
        val names = fs.list(path)
        if (names == null) {
            largest.add(StorageNode("$parentId/\u0000unreadable", "Can’t read this folder", own))
            return
        }
        if (names.isEmpty() && !folder.unownedOnly) {
            largest.add(StorageNode("$parentId/\u0000empty", "Empty folder", own))
            return
        }
        val place = UsageText.place(folder.relative)
        var bare = 0
        var bareBytes = 0L
        names.forEach { name ->
            tick()
            val relative = if (folder.relative.isEmpty()) name else "${folder.relative}/$name"
            val row = rowFor(relative, name, folder.kind, parentId, place, folder.unownedOnly, tick) ?: return@forEach
            if (row.bare) {
                bare++
                bareBytes += row.bytes
            } else {
                largest.add(row)
            }
        }
        if (bare > 0) largest.add(StorageNode("$parentId/\u0000bare", if (bare == 1) "1 folder of package files" else "$bare folders of package files", bareBytes, "Just the folders. The files are under their packages"))
        if (own > 0) largest.add(StorageNode("$parentId/\u0000own", "This folder’s own entry", own))
    }

    /** Measures one path into a row, or null if it's gone, of another kind, or has nothing here. */
    private fun rowFor(relative: String, name: String, kind: StorageKind, parentId: String, place: String?, unownedOnly: Boolean, tick: () -> Unit): StorageNode? {
        val entry = fs.lstat(path(relative)) ?: return null
        val own = StorageUsage.FOLDERS[relative] ?: kind
        var bytes = 0L
        var files = 0
        var counted = false
        val unreadable = walkEntries(fs, path(relative), own, StorageUsage.FOLDERS, relative, only = kind, links = links, onFolder = tick) { rel, _, e ->
            if (unownedOnly && !e.isDir && rel in owners) return@walkEntries
            counted = true
            bytes += e.bytes
            if (!e.isDir) files++
        }
        val id = "$parentId/$name"
        return when {
            own != kind && bytes == 0L -> null
            !counted && !entry.isDir -> if (unownedOnly) null else StorageNode(id, name, 0, "Hard link, counted under its other name", place)
            entry.isLink -> StorageNode(id, name, bytes, "Link, not followed", place)
            !entry.isDir -> StorageNode(id, name, bytes, null, place)
            unownedOnly && files == 0 -> StorageNode(id, name, bytes, bare = true)
            else -> StorageNode(
                id, name, bytes,
                if (unreadable > 0) "Some folders inside can’t be read" else null,
                place,
                Opening.Folder(relative, kind, unownedOnly),
            )
        }
    }

    /** Sizes files by the package that owns them, with one walk of the same tree. Folders aren't any package's. */
    private fun packages(parentId: String, tick: () -> Unit): List<StorageNode> {
        val owned = readOwners(tick)
        owners = owned
        val byPackage = HashMap<String, LargestRows>()
        var unowned = 0L
        walkEntries(fs, path(USR), StorageKind.Packages, StorageUsage.FOLDERS, USR, only = StorageKind.Packages, links = links, onFolder = tick) { rel, _, e ->
            val pkg = if (e.isDir) null else owned[rel]
            if (pkg == null) {
                unowned += e.bytes
            } else {
                val name = rel.removePrefix("$USR/")
                byPackage.getOrPut(pkg) { LargestRows() }.add(StorageNode("$parentId/$pkg/$name", name, e.bytes, if (e.isLink) "Link, not followed" else null))
            }
        }
        val largest = LargestRows()
        byPackage.forEach { (pkg, rows) ->
            val id = "$parentId/$pkg"
            largest.add(StorageNode(id, pkg, rows.total, opening = Opening.Package(rows.rows(id))))
        }
        largest.add(StorageNode("$parentId/\u0000unowned", "Not from a package", unowned, "Files no package owns, and all the folders", opening = Opening.Folder(USR, StorageKind.Packages, unownedOnly = true)))
        return largest.rows(parentId)
    }

    private fun readOwners(tick: () -> Unit): Map<String, String> {
        val info = path(DPKG_INFO)
        val lists = fs.list(info).orEmpty().mapNotNull { file ->
            tick()
            val pkg = dpkgPackageName(file) ?: return@mapNotNull null
            readText("$info/$file")?.let { pkg to it }
        }
        return dpkgOwners(lists)
    }
}
