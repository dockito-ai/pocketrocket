package com.pocketrocket.ui

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

    /** The `CLAUDE.md` of a Claude account, or of a folder. */
    fun accountMemory(id: String) = "memory-account|$id"
    fun folderMemory(path: String) = "memory-folder|$path"

    /** Clone into [into] (a workspace or any folder; ~/projects from the Workspaces tab). */
    fun clone(into: String) = "clone|$into"

    /** New session in [folder] (blank: pick one), on the Resume earlier tab when [resume]. */
    fun newSession(folder: String = "", resume: Boolean = false) = "session-new|$folder|${if (resume) "resume" else "new"}"
}
