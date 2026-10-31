package com.pocketrocket.assistant

import com.pocketrocket.sessions.PendingPermission

/** What it takes to approve something. Decided here and nowhere else: not by Claude, not by Rocket's memory, not by a setting. */
enum class Protection {
    /** Said aloud, closely, and answered with a spoken yes (or a tap on the approval screen). */
    SpokenYes,

    /** Too long or odd to approve by ear: Rocket says "check your screen" and only a tap answers it. */
    Screen,

    /** The fingerprint popup, with the phone's PIN as fallback, every time (decision 57). */
    Fingerprint,
}

/**
 * Something Rocket's own tools (build step 5) want to do. Each case names exactly what will happen, and the app writes the
 * popup from these fields. A new case won't compile until [SensitiveActions.protectionOf] gives it a rule.
 */
sealed interface RocketAction {
    data class Push(val repo: String, val branch: String, val remote: String = "origin") : RocketAction
    data class Merge(val repo: String, val what: String) : RocketAction
    data class OpenPr(val repo: String, val branch: String, val title: String) : RocketAction
    /** Accept edits or Auto: fewer prompts from then on. */
    data class SetMode(val session: String, val mode: String) : RocketAction
    data class DeleteBranch(val repo: String, val branch: String, val onRemote: Boolean) : RocketAction
    data class ForcePush(val repo: String, val branch: String, val remote: String = "origin") : RocketAction
    data class DiscardChanges(val repo: String, val what: String) : RocketAction
    data class SendMessage(val to: String, val message: String) : RocketAction
    /** A new agent in Accept edits or Auto. Manual and Plan don't ask. */
    data class StartAgent(val project: String, val mode: String, val model: String?) : RocketAction
    /** A message to a session in Auto, read back because a misheard one would run with no later check. */
    data class MessageSession(val session: String, val message: String) : RocketAction
    /** Something that just happens on an unlocked phone, and takes a fingerprint on a locked one. */
    data class WhileLocked(val what: String) : RocketAction
    /** A habit Rocket noticed: saved only on a yes (decision 56). */
    data class SaveHabit(val text: String) : RocketAction
    /** The account new sessions use where a folder hasn't picked one. [service] is "Claude" or "GitHub". */
    data class SetDefaultAccount(val service: String, val account: String, val instead: String?) : RocketAction
    /** One of Settings › Tools, downloaded and installed. */
    data class InstallTool(val tool: String) : RocketAction
    /** [names] to say, [versions] to show, both from the Updates page's own rows. [warning]: what else it does. */
    data class InstallUpdate(val names: String, val versions: String, val warning: String?) : RocketAction
    /** Anything else. [description] is shown as it is; it can't make the action less protected. */
    data class Unknown(val description: String) : RocketAction
}

/** The sensitive list. Plain rules with no Android and no state, so nothing at runtime can change them. */
object SensitiveActions {
    /** What Settings › Assistant › Sensitive actions shows. */
    val FINGERPRINT = listOf(
        "Delete a branch" to "Here or on GitHub",
        "Force-push" to "Including force-with-lease",
        "Discard changes" to "Hard reset, restore, clean, removing files",
        "Send a message to other people" to "Slack and the like, PR and issue comments",
        "Anything Rocket doesn't recognise" to "Commands that run other commands, and every tool it doesn't know",
    )
    val SPOKEN = listOf("Push", "Merge", "Open a PR", "Switch a session to Accept edits or Auto", "Start an agent in Accept edits or Auto", "A message to a session in Auto", "Allow an ordinary command", "Change the default Claude or GitHub account", "Install a tool or an update")

    /** Tools Claude Code ships with. Anything else, such as an MCP tool, could do anything. */
    private val FILE_TOOLS = setOf("Edit", "MultiEdit", "NotebookEdit", "Write", "Read")
    private val KNOWN_TOOLS = FILE_TOOLS + setOf("Bash", "Glob", "Grep", "LS", "NotebookRead", "TodoWrite", "WebFetch", "WebSearch", "Task", "Agent", "BashOutput", "KillShell", "ExitPlanMode")

    fun protectionOf(action: RocketAction): Protection = when (action) {
        is RocketAction.Push, is RocketAction.Merge, is RocketAction.OpenPr, is RocketAction.SetMode, is RocketAction.StartAgent, is RocketAction.SaveHabit,
        is RocketAction.SetDefaultAccount, is RocketAction.InstallTool, is RocketAction.InstallUpdate -> Protection.SpokenYes
        // It has to be heard to be judged: one that can't be read aloud is answered on the screen.
        is RocketAction.MessageSession -> if (Spoken.closely(action.message) != null) Protection.SpokenYes else Protection.Screen
        is RocketAction.WhileLocked, is RocketAction.DeleteBranch, is RocketAction.ForcePush, is RocketAction.DiscardChanges, is RocketAction.SendMessage, is RocketAction.Unknown -> Protection.Fingerprint
    }

    /** From the request's real input, never its one-line summary, which stops at the first line of a command. */
    fun protectionOf(asked: PendingPermission): Protection = when {
        asked.toolName !in KNOWN_TOOLS -> Protection.Fingerprint
        asked.toolName == "Bash" -> {
            val command = asked.fullCommand()
            when {
                command.isBlank() || commandIsSensitive(command) -> Protection.Fingerprint
                Spoken.closely(command) != null -> Protection.SpokenYes
                else -> Protection.Screen
            }
        }
        asked.toolName in FILE_TOOLS -> if (Spoken.closely(asked.fullPath().substringAfterLast('/')) != null) Protection.SpokenYes else Protection.Screen
        else -> Protection.Screen
    }

    /** A command recognised as destructive or as reaching other people, as opposed to one merely not recognised. */
    fun isRecognisedSensitive(asked: PendingPermission): Boolean = asked.toolName == "Bash" && commandIsSensitive(asked.fullCommand())

    /**
     * Whether any part of [command] is on the list. Errs towards yes. It reads what's written, so a script that force-pushes
     * isn't caught: that is why this is a net for agents and not a guarantee (`docs/assistant.md`).
     */
    fun commandIsSensitive(command: String): Boolean = commandIsSensitive(command, Scratch.NONE)

    /**
     * As above, except that `rm` and `rmdir` of paths that are all inside [scratch] don't count: Rocket's own folder and the
     * temp folder, where removing a file loses nothing of his. A path with `..`, a variable, a glob or `~` isn't read as inside.
     */
    fun commandIsSensitive(command: String, scratch: Scratch): Boolean = segments(command).any { segmentIsSensitive(it, scratch) }

    /** Push, merge and opening a PR: a spoken yes (decision 57), not a fingerprint. Force and delete are [commandIsSensitive]'s. */
    fun commandNeedsYes(command: String): Boolean = segments(command).any { words ->
        val run = words.dropWhile { it in PREFIXES || ASSIGNMENT.matches(it) || it.startsWith("-") && words.first() in PREFIXES }
        val args = run.drop(1)
        when (run.firstOrNull()?.substringAfterLast('/')) {
            "git" -> withoutGitOptions(args).firstOrNull() == "push"
            "gh" -> args.getOrNull(0) == "pr" && args.getOrNull(1) in setOf("merge", "create")
            else -> false
        }
    }

    /** Folders where removing things loses nothing: [roots] are absolute, [cwd] is where a relative path starts. */
    class Scratch(roots: List<String>, val cwd: String?) {
        private val roots = roots.map { it.trimEnd('/') }.filter { it.startsWith("/") && it.length > 1 }

        fun holds(path: String): Boolean {
            if (path.isEmpty() || path.any { it in "$*?[{~`" } || path.split('/').any { it == ".." }) return false
            val full = if (path.startsWith("/")) path else cwd?.trimEnd('/')?.let { "$it/$path" } ?: return false
            val clean = full.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/", prefix = "/")
            return roots.any { clean.startsWith("$it/") }
        }

        companion object {
            val NONE = Scratch(emptyList(), null)
        }
    }

    private fun segmentIsSensitive(words: List<String>, scratch: Scratch): Boolean {
        // What runs inside "$(...)" or backticks counts too, quoted or not.
        if (words.any { word -> SUBSTITUTION.findAll(word).any { commandIsSensitive(word.substring(it.range.last + 1), scratch) } }) return true
        val run = words.dropWhile { it in PREFIXES || ASSIGNMENT.matches(it) || it.startsWith("-") && words.first() in PREFIXES }
        val tool = run.firstOrNull()?.substringAfterLast('/') ?: return false
        val args = run.drop(1)
        return when {
            (tool == "rm" || tool == "rmdir") && run.first() == tool && words.first() == tool -> {
                val paths = args.filterNot { it.startsWith("-") && it != "-" }
                paths.isEmpty() || !paths.all(scratch::holds)
            }
            tool in ALWAYS || tool.startsWith("mkfs") -> true
            tool == "git" -> git(args)
            tool == "gh" -> gh(args)
            tool == "find" -> "-delete" in args || "-exec" in args || "-execdir" in args
            tool == "curl" || tool == "wget" || tool == "http" -> sendsData(args)
            else -> false
        }
    }

    private fun git(raw: List<String>): Boolean {
        val args = withoutGitOptions(raw)
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            "push" -> rest.any { it == "--delete" || it == "--mirror" || it == "--prune" || it.startsWith("--force") || shortFlag(it, 'f') || shortFlag(it, 'd') || it.startsWith("+") || it.startsWith(":") || ":+" in it }
            "branch" -> rest.any { it == "--delete" || shortFlag(it, 'd') || shortFlag(it, 'D') }
            "tag" -> rest.any { it == "--delete" || shortFlag(it, 'd') }
            "reset" -> rest.any { it == "--hard" || it == "--merge" || it == "--keep" }
            "checkout" -> rest.any { it == "--" || it == "." || it == "--force" || shortFlag(it, 'f') }
            "switch" -> rest.any { it == "--discard-changes" || it == "--force" || shortFlag(it, 'f') }
            "restore" -> "--staged" !in rest || "--worktree" in rest
            "stash" -> rest.firstOrNull() == "drop" || rest.firstOrNull() == "clear"
            "clean", "rm", "filter-branch", "filter-repo" -> true
            // An alias or a -c option can make any of these something else.
            null -> false
            else -> raw.any { it == "-c" || it == "--config-env" }
        }
    }

    /** Past `git -C dir`, `--git-dir=x` and the like, to the subcommand. */
    private fun withoutGitOptions(args: List<String>): List<String> {
        var i = 0
        while (i < args.size && args[i].startsWith("-")) i += if (args[i] in GIT_OPTIONS_WITH_VALUE) 2 else 1
        return args.drop(i)
    }

    private fun gh(args: List<String>): Boolean {
        val noun = args.getOrNull(0)
        val verb = args.getOrNull(1)
        return when {
            noun == "api" -> sendsData(args.drop(1)) || args.any { it == "-f" || it == "-F" || it.startsWith("--field") || it.startsWith("--raw-field") || it == "--input" }
            verb == "delete" || verb == "close" -> true
            noun == "pr" -> verb == "comment" || verb == "review"
            noun == "issue" -> verb == "comment" || verb == "create"
            else -> false
        }
    }

    private fun sendsData(args: List<String>): Boolean = args.indices.any { i ->
        val a = args[i]
        a.startsWith("--data") || a.startsWith("--form") || a.startsWith("--post") || a == "--upload-file" || a == "--input" ||
            shortFlag(a, 'd') || shortFlag(a, 'F') || shortFlag(a, 'T') ||
            (a == "-X" || a == "--request" || a == "--method") && !args.getOrNull(i + 1).equals("GET", ignoreCase = true) ||
            METHOD_JOINED.matches(a)
    }

    /** "-f" and bunched flags like "-fu"; not "--force" and not a word with a dash in it. */
    private fun shortFlag(arg: String, flag: Char) = arg.length >= 2 && arg[0] == '-' && arg[1] != '-' && flag in arg

    /** The words of each simple command in [command], split where the shell would split them. Quotes group; they don't hide. */
    internal fun segments(command: String): List<List<String>> {
        val all = mutableListOf<List<String>>()
        var words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var any = false
        fun endWord() {
            if (any) words.add(word.toString())
            word.setLength(0)
            any = false
        }
        fun endSegment() {
            endWord()
            if (words.isNotEmpty()) all.add(words)
            words = mutableListOf()
        }
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                quote != null -> if (ch == quote) quote = null else word.append(ch)
                ch == '\\' -> { i++; command.getOrNull(i)?.takeIf { it != '\n' }?.let { word.append(it); any = true } }
                ch == '"' || ch == '\'' -> { quote = ch; any = true }
                ch == '\n' || ch in SPLITTERS -> endSegment()
                ch.isWhitespace() -> endWord()
                else -> { word.append(ch); any = true }
            }
            i++
        }
        endSegment()
        return all
    }

    private const val SPLITTERS = ";&|(){}`"
    private val SUBSTITUTION = Regex("""\$\(|`""")
    private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
    private val METHOD_JOINED = Regex("""^(?:-X|--request=|--method=)(?!GET$).+""", RegexOption.IGNORE_CASE)
    private val PREFIXES = setOf("sudo", "env", "nohup", "time", "command", "builtin", "nice")
    private val GIT_OPTIONS_WITH_VALUE = setOf("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--config-env")
    /** Removes things, reaches other people, or runs something this can't read. */
    private val ALWAYS = setOf("rm", "rmdir", "shred", "truncate", "dd", "mail", "sendmail", "mutt", "slack", "sh", "bash", "zsh", "dash", "eval", "exec", "source", ".", "xargs", "ssh")
}

/** The whole command as Claude Code will run it; the request's summary is only its first line. */
fun PendingPermission.fullCommand(): String = input.optString("command")

/** The whole path; the request's summary is only the file's name. */
fun PendingPermission.fullPath(): String = input.optString("file_path").ifBlank { input.optString("notebook_path") }.ifBlank { summary }
