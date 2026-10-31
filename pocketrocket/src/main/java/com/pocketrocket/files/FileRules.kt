package com.pocketrocket.files

/**
 * The file manager's rules on paths, with no disk access: where back goes, the breadcrumb, which names are
 * allowed, and which folders need an extra confirmation before delete or must be renamed as workspaces.
 * Paths are absolute and normalised, without a trailing slash.
 */
object FileRules {
    /** True when [path] is [root] or anywhere under it. */
    fun isInside(path: String, root: String): Boolean =
        path == root || root == "/" || path.startsWith("$root/")

    /**
     * The folder above [current] that back goes to, or null when back leaves the screen: at [start] (where the
     * screen opened) or at [root] (the top it never goes above).
     */
    fun upFrom(current: String, start: String, root: String): String? {
        if (current == start || current == root || !isInside(current, root)) return null
        return current.substringBeforeLast('/').ifEmpty { "/" }
    }

    /** One step of the path bar: what it reads and the folder it opens. */
    data class Crumb(val label: String, val path: String)

    /** The path bar for [current] under [root]: "~" (or [rootLabel]), then each folder down to [current]. */
    fun crumbs(current: String, root: String, rootLabel: String = "~"): List<Crumb> {
        if (!isInside(current, root)) return listOf(Crumb(current, current))
        val crumbs = mutableListOf(Crumb(rootLabel, root))
        var path = root.trimEnd('/')
        current.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { part ->
            path = "$path/$part"
            crumbs += Crumb(part, path)
        }
        return crumbs
    }

    /** Why [name] can't name a new file or folder (or a renamed one), or null when it can. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Give it a name."
        name != name.trim() -> "Names can't start or end with a space."
        name == "." || name == ".." -> "That name is taken by the folder itself."
        '/' in name -> "Names can't contain /."
        '\u0000' in name || '\n' in name -> "Names can't contain that character."
        name.toByteArray(Charsets.UTF_8).size > 255 -> "That name is too long."
        else -> null
    }

    /** How careful deleting something has to be. */
    enum class DeleteGuard {
        /** One confirmation. */
        Normal,

        /** Home, ~/projects, a workspace, or a folder holding one: the name has to be typed to confirm. */
        Serious,
    }

    /**
     * How careful deleting [path] has to be. [protectedRoots] are home, ~/projects and every workspace. Deleting
     * a symlink only removes the link, so it's [DeleteGuard.Normal] unless it is itself a protected root.
     */
    fun deleteGuard(path: String, isLink: Boolean, protectedRoots: List<String>): DeleteGuard = when {
        path in protectedRoots -> DeleteGuard.Serious
        isLink -> DeleteGuard.Normal
        protectedRoots.any { isInside(it, path) } -> DeleteGuard.Serious
        else -> DeleteGuard.Normal
    }

    /** What renaming [path] does. */
    enum class RenameKind {
        /** A plain rename on disk. */
        Plain,

        /** A workspace or a folder holding one: renamed through the workspace list so its accounts move along. */
        Workspace,

        /** Home or ~/projects: never renamed. */
        Refused,
    }

    /** How [path] is renamed, given [fixedRoots] (home and ~/projects) and the [workspaces]' paths. */
    fun renameKind(path: String, isLink: Boolean, fixedRoots: List<String>, workspaces: List<String>): RenameKind = when {
        fixedRoots.any { isInside(it, path) } -> RenameKind.Refused
        path in workspaces -> RenameKind.Workspace
        isLink -> RenameKind.Plain
        workspaces.any { isInside(it, path) } -> RenameKind.Workspace
        else -> RenameKind.Plain
    }
}
