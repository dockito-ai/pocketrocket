package com.pocketrocket.ui

import com.pocketrocket.files.FileRules

/**
 * Route strings for [PocketRocketApp]'s back stack. Routes that carry a folder use `|` between parts,
 * since paths have slashes.
 */
object Routes {
    const val NEW_WORKSPACE = "ws-new"
    const val TOOLS = "tools"
    /** Settings › Tools › a tool's page (decisions 39 and 64). */
    const val TOOL_NODE = "tool-node"
    const val TOOL_DOCKITO = "tool-dockito"
    const val TOOL_CHROMIUM = "tool-chromium"
    const val TOOL_ANDROID_BUILD = "tool-android-build"
    const val TOOL_ANDROID_NDK = "tool-android-ndk"
    const val TOOL_WRANGLER = "tool-wrangler"
    const val TOOL_FLUTTER = "tool-flutter"
    const val TOOL_RUST = "tool-rust"
    const val TOOL_PYTHON = "tool-python"
    const val TOOL_BUN = "tool-bun"
    const val TOOL_GO = "tool-go"
    const val TOOL_DENO = "tool-deno"
    const val TOOL_RUBY = "tool-ruby"
    const val TOOL_PHP = "tool-php"
    const val TOOL_JAVA = "tool-java"
    const val TOOL_AWS = "tool-aws"
    const val TOOL_FLY = "tool-flyctl"
    const val TOOL_VERCEL = "tool-vercel"
    const val TOOL_NETLIFY = "tool-netlify"
    const val TOOL_FIREBASE = "tool-firebase"
    const val TOOL_CPP = "tool-cpp"
    const val TOOL_GIT = "tool-git"
    /** Settings › Updates › Claude Code: versions, rollback, sessions on the old version (R9). */
    const val CLAUDE_CODE = "claude-code"
    /** Settings › Health: checks with Fix buttons and the last stop (R15). */
    const val HEALTH = "health"
    /** Settings › Usage: the app's storage, memory and battery. */
    const val USAGE = "usage"
    /** Settings › Updates: PocketRocket, Claude Code, Git and Dockito (decision 41). */
    const val UPDATES = "updates"
    /** Settings › Backup, and its Restore page (decision 53). */
    const val BACKUP = "backup"
    const val RESTORE = "restore"
    /** Settings › About: version, source and licences (decision 88). */
    const val ABOUT = "about"

    /** Settings › Assistant: Rocket's switches (decision 52). */
    const val ASSISTANT = "assistant"
    /** Settings › Assistant › Activity: what Rocket did. */
    const val ASSISTANT_ACTIVITY = "assistant-activity"
    /** Settings › Assistant › Sensitive actions: what's protected, read-only. */
    const val ASSISTANT_SENSITIVE = "assistant-sensitive"

    /** Settings › Assistant › Memory: Rocket's notes and nicknames. */
    const val ASSISTANT_MEMORY = "assistant-memory"

    /** Settings › Assistant › Projects: a switch per project. */
    const val ASSISTANT_PROJECTS = "assistant-projects"

    /** New workspace on an existing folder (a repo that isn't a workspace yet, decision 72): name and folder filled in. */
    fun newWorkspace(folder: String) = "$NEW_WORKSPACE|$folder"

    fun workspace(path: String) = "ws|$path"
    fun workspaceSettings(path: String) = "ws-settings|$path"

    /** The file manager at [folder] (decision 80): home from the Workspaces tab, a workspace's folder from it. */
    fun files(folder: String) = "files|$folder"

    /** A file in the file manager's read-only viewer. */
    fun file(path: String) = "file|$path"

    /** The `CLAUDE.md` of a Claude account, or of a folder. */
    fun accountMemory(id: String) = "memory-account|$id"
    fun folderMemory(path: String) = "memory-folder|$path"

    /** Clone into [into] (a workspace or any folder; ~/projects from the Workspaces tab). */
    fun clone(into: String) = "clone|$into"

    /** New session in [folder] (blank: pick one), on the Resume earlier tab when [resume]. */
    fun newSession(folder: String = "", resume: Boolean = false) = "session-new|$folder|${if (resume) "resume" else "new"}"

    /** The folder [route] is about (`ws|<path>`, `clone|<path>`, `files|<path>`...), or null. */
    private fun pathOf(route: String): String? = route.substringAfter('|', "").substringBefore('|').ifEmpty { null }

    /** [route] with its folder moved from [from] to [to] when it is [from] or inside it. */
    fun moved(route: String, from: String, to: String): String {
        val path = pathOf(route) ?: return route
        if (!FileRules.isInside(path, from)) return route
        return route.replaceFirst("|$path", "|" + to + path.removePrefix(from))
    }

    /** Whether [route] is about [folder] or something inside it. */
    fun inside(route: String, folder: String): Boolean = pathOf(route)?.let { FileRules.isInside(it, folder) } == true
}
