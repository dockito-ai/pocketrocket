package com.pocketrocket.workspaces

import com.pocketrocket.profiles.AccountEntry
import com.pocketrocket.profiles.FolderBinding
import java.io.File

/**
 * The pure half of `pocketrocket-workspace` (decision 73): what a request file says, what its arguments
 * mean, which paths are allowed and which accounts a new workspace gets. No Android and no app state here;
 * [WorkspaceCliRunner] does the work and [WorkspaceRequests] carries requests and answers.
 */
object WorkspaceCli {
    /** First field of every request file; a script from another version is refused plainly. */
    const val VERSION = "pocketrocket-workspace/1"

    /** Set for every session's process: its folder, so the script knows which workspace it runs in. */
    const val SESSION_FOLDER_ENV = "POCKETROCKET_SESSION_FOLDER"

    /**
     * What every session's Claude is told (appended to its system prompt), so it registers repos it clones.
     * Kept short: it is in every conversation.
     */
    const val CLAUDE_NOTE = "This phone runs PocketRocket. Its workspaces are folders under ~/projects listed in the app. " +
        "For a new clone under ~/projects, prefer `pocketrocket-workspace clone <url> [folder]`: it clones and makes the repo " +
        "a workspace with this workspace's accounts. After cloning a repo under ~/projects any other way, register it with " +
        "`pocketrocket-workspace add <path>`. Do this on your own, without being asked. `pocketrocket-workspace list` shows " +
        "workspaces and account ids; it can't remove, rename or move workspaces."

    val USAGE = """
        |Usage:
        |  pocketrocket-workspace add <path> [--name N] [--github ID] [--claude ID] [--dockito ID]
        |  pocketrocket-workspace clone <url> [folder] [--branch B] [--name N] [--github ID] [--claude ID] [--dockito ID]
        |  pocketrocket-workspace list [--json]
        |Paths are under ~/projects. Accounts not given are those of the workspace you are in, else the defaults.
    """.trimMargin()

    /** What a request file carries: the caller's folder, the session's folder (when started by a session), whether Claude ran it, and the arguments. */
    data class Request(val cwd: String, val sessionFolder: String?, val fromClaude: Boolean, val args: List<String>)

    /** Account ids (or names) picked with flags; null: not given. */
    data class AccountPicks(val claude: String? = null, val github: String? = null, val dockito: String? = null)

    sealed interface Command {
        data class Add(val path: String, val name: String?, val accounts: AccountPicks) : Command
        data class Clone(val url: String, val folder: String?, val branch: String?, val name: String?, val accounts: AccountPicks) : Command
        data class ListAll(val json: Boolean) : Command
    }

    /** A request the app refuses, with the message the script prints. */
    class Error(message: String) : Exception(message)

    /**
     * Reads a request file: NUL-separated fields, [VERSION], `key=value` lines (cwd, session, claude),
     * `--`, then the arguments as given. The script writes each field followed by NUL. Null when it isn't one.
     */
    fun parseRequest(text: String): Request? {
        val parts = text.split('\u0000').let { if (text.endsWith('\u0000')) it.dropLast(1) else it }
        if (parts.firstOrNull() != VERSION) return null
        val sep = parts.indexOf("--").takeIf { it > 0 } ?: return null
        val head = parts.subList(1, sep).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val cwd = head["cwd"]?.takeIf { it.startsWith("/") } ?: return null
        return Request(cwd, head["session"]?.ifBlank { null }, head["claude"].orEmpty().isNotBlank() && head["claude"] != "0", parts.drop(sep + 1))
    }

    /** The command in [args], or [Error] with what is wrong. */
    fun parseArgs(args: List<String>): Command {
        val verb = args.firstOrNull() ?: throw Error(USAGE)
        val allowed = when (verb) {
            "add" -> setOf("name", "github", "claude", "dockito")
            "clone" -> setOf("name", "github", "claude", "dockito", "branch")
            "list" -> setOf("json")
            else -> throw Error("Unknown command \"$verb\".\n$USAGE")
        }
        val flags = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        var i = 1
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && a.length > 2) {
                val key = a.removePrefix("--").substringBefore('=')
                if (key !in allowed) throw Error("$verb has no --$key.\n$USAGE")
                if (key == "json") { flags[key] = ""; i++; continue }
                val value = if ('=' in a) a.substringAfter('=') else args.getOrNull(++i) ?: throw Error("--$key needs a value.")
                if (value.isBlank()) throw Error("--$key needs a value.")
                flags[key] = value
            } else positional += a
            i++
        }
        val accounts = AccountPicks(flags["claude"], flags["github"], flags["dockito"])
        return when (verb) {
            "add" -> {
                if (positional.size != 1) throw Error("add takes one path.\n$USAGE")
                Command.Add(positional[0], flags["name"], accounts)
            }
            "clone" -> {
                if (positional.isEmpty() || positional.size > 2) throw Error("clone takes a URL and, optionally, a folder.\n$USAGE")
                Command.Clone(positional[0], positional.getOrNull(1), flags["branch"], flags["name"], accounts)
            }
            else -> {
                if (positional.isNotEmpty()) throw Error("list takes no arguments.\n$USAGE")
                Command.ListAll("json" in flags)
            }
        }
    }

    /** [raw] as an absolute, normalized path: `~` is [home], relative paths start at [cwd]. No trailing slash. */
    fun resolve(raw: String, cwd: String, home: String): String {
        val t = raw.trim()
        val abs = when {
            t == "~" -> home
            t.startsWith("~/") -> home + t.removePrefix("~")
            t.startsWith("/") -> t
            else -> "$cwd/$t"
        }
        return File(abs).normalize().path.trimEnd('/').ifEmpty { "/" }
    }

    /** Null when [path] is inside [projectsRoot] (not the root itself), else why it can't be used. */
    fun checkUnderProjects(path: String, projectsRoot: String): String? = when {
        path == projectsRoot -> "Pick a folder inside ~/projects, not ~/projects itself."
        !path.startsWith("$projectsRoot/") -> "Only folders under ~/projects can be workspaces; $path isn't."
        else -> null
    }

    /** The workspace [folder] is in (itself or the nearest one above it), or null. */
    fun containing(workspaces: List<Workspace>, folder: String): Workspace? =
        workspaces.filter { folder == it.path || folder.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    /** Workspaces inside [folder] (not [folder] itself). */
    fun inside(workspaces: List<Workspace>, folder: String): List<Workspace> = workspaces.filter { it.path.startsWith("$folder/") }

    /**
     * Where accounts that weren't given come from: the workspace [target] lands in, else the one the
     * calling session runs in, else the one the caller's folder is in. Null: the defaults.
     */
    fun accountSource(workspaces: List<Workspace>, target: String, sessionFolder: String?, cwd: String): Workspace? =
        containing(workspaces, target)
            ?: sessionFolder?.let { containing(workspaces, it) }
            ?: containing(workspaces, cwd)

    /** [given] with what wasn't given taken from [from] (a workspace's binding); null stays "the default". */
    fun fill(given: AccountPicks, from: FolderBinding?): AccountPicks =
        AccountPicks(given.claude ?: from?.claude, given.github ?: from?.github, given.dockito ?: from?.dockito)

    /** The account [query] names (its id, or its name ignoring case), or [Error] listing the ones there are. */
    fun <T : AccountEntry> match(kind: String, query: String, accounts: List<T>): T =
        accounts.firstOrNull { it.id == query }
            ?: accounts.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: throw Error(
                if (accounts.isEmpty()) "There is no $kind account on this phone yet."
                else "No $kind account \"$query\". There are: " + accounts.joinToString(", ") { if (it.id == it.name) it.id else "${it.id} (${it.name})" } + ".",
            )
}
