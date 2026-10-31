package com.pocketrocket.backup

/**
 * What may be in a backup, as names relative to the app's files folder ("home/.claude/settings.json").
 * Only these go in, and a restore refuses anything else, so a new file stays out until it is added here.
 */
internal object BackupAllowlist {
    const val HOME = "home"
    const val ACCOUNTS = "$HOME/.pocketrocket/accounts"
    const val PROJECTS = "$HOME/projects"
    const val WORKSPACES_FILE = "$HOME/.pocketrocket/workspaces.json"
    const val MAIN_CLAUDE = "$HOME/.claude"
    const val MAIN_CLAUDE_JSON = "$HOME/.claude.json"

    private val ACC = Regex.escape(ACCOUNTS)
    private val CLAUDE_BASE = "(?:${Regex.escape(MAIN_CLAUDE)}|$ACC/claude/[^/]+)"

    private val KNOWN = Regex(
        listOf(
            Regex.escape(WORKSPACES_FILE),
            Regex.escape(MAIN_CLAUDE_JSON),
            "$ACC/(?:claude|github|dockito|bindings)\\.json",
            "$ACC/github/known_hosts",
            "$ACC/github/[^/]+/(?:gitconfig|id_ed25519|id_ed25519\\.pub)",
            "$ACC/github/[^/]+/gh(?:/.*)?",
            "$ACC/dockito(?:/.*)?",
            "$CLAUDE_BASE/(?:settings\\.json|CLAUDE\\.md|\\.claude\\.json|\\.credentials\\.json)",
            "$CLAUDE_BASE/(?:skills|plugins|agents|commands)(?:/.*)?",
            "$CLAUDE_BASE/projects/[^/]+/memory(?:/.*)?",
            Regex.escape(PROJECTS) + "(?:/.*)?",
        ).joinToString("|"),
    )

    /** Trees replaced whole on restore. Other folders merge, so what else is on the phone stays. */
    private val WHOLE = Regex(
        "$CLAUDE_BASE/(?:skills|plugins|agents|commands)|$CLAUDE_BASE/projects/[^/]+/memory|$ACC/github/[^/]+/gh|$ACC/dockito",
    )

    fun isWholeTree(name: String) = WHOLE.matches(name)

    /** Whether [name] is one of the allowed paths, given the extra folders [workspaceRoots] the backup holds. */
    fun isKnown(name: String, workspaceRoots: Collection<String>): Boolean =
        KNOWN.matches(name) || workspaceRoots.any { name == it || name.startsWith("$it/") }

    /**
     * Whether a workspace folder outside ~/projects can go in: a folder inside home, not hidden, not ~/storage.
     * Applied to a backup's own list on restore too, so a crafted file can't call ~/.ssh or ~/.bashrc a workspace.
     */
    fun isWorkspaceRoot(name: String): Boolean {
        val parts = name.split('/')
        if (parts.size < 2 || parts[0] != HOME || !isSafeName(name)) return false
        return !parts[1].startsWith(".") && parts[1] != "storage"
    }

    /** No absolute path, no `.` or `..` part, no empty part, no NUL. */
    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000')) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /**
     * Whether a link at [name] pointing at [target] stays inside the known paths. Worked out from the text, so
     * `..` is allowed only at the start of a target: after a folder name it might follow another link somewhere
     * the text doesn't show. An absolute target must be under [filesRoot], the app's files folder.
     */
    fun linkStaysInside(name: String, target: String, filesRoot: String, workspaceRoots: Collection<String>): Boolean {
        if (target.isEmpty() || target.contains('\u0000')) return false
        val relative = if (target.startsWith("/")) {
            if (!target.startsWith("$filesRoot/")) return false
            target.removePrefix("$filesRoot/").also { if (it.isEmpty()) return false }
        } else {
            target
        }
        val base = if (target.startsWith("/")) mutableListOf() else name.split('/').dropLast(1).toMutableList()
        var named = false
        for (part in relative.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (named || base.isEmpty()) return false
                    base.removeAt(base.size - 1)
                }
                else -> {
                    named = true
                    base.add(part)
                }
            }
        }
        return base.isNotEmpty() && isKnown(base.joinToString("/"), workspaceRoots)
    }
}
