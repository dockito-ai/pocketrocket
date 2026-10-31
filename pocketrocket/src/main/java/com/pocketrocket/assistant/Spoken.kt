package com.pocketrocket.assistant

/**
 * Wording for the ear: "git push origin feat/auth" reads as "push feat auth to origin". Short and approximate, so never
 * what an approval rests on, and it leaves out anything that could be a secret: when in doubt the command stays on screen.
 */
object Spoken {
    /** The sentence the voice picker plays: something Rocket really says. */
    const val SAMPLE = "api server wants to push feat auth to origin."

    private const val MAX_CHARS = 320
    private const val MAX_PLAIN_WORDS = 3
    private const val MAX_TOKEN_CHARS = 40
    private const val THE_REST = " The rest is on screen."

    private val JOINERS = Regex("""(?<=[\p{L}\d])[-_/](?=[\p{L}\d])""")
    private val FILE_DOT = Regex("""(?<=\p{L})\.(?=\p{L})""")
    private val THOUSANDS = Regex("""\b(\d+(?:\.\d+)?)k\b""")
    private val MILLIONS = Regex("""\b(\d+(?:\.\d+)?)M\b""")
    private val SPACES = Regex("""\s+""")
    /** Anything beyond one plain command: chains, pipes, redirects, substitution, quoting. */
    private val NOT_PLAIN = Regex("""[;&|<>`$"'\\\n()]""")
    private val QUOTED = Regex(""""[^"]*"|'[^']*'""")
    private val PACKAGE_TOOLS = setOf("npm", "yarn", "pnpm", "bun", "npx")
    private val PLAIN_WORD = Regex("""[A-Za-z0-9._/~:-]+""")
    private val SECRET_LOOKING = Regex("""^(?:ghp_|gho_|ghs_|github_pat_|sk-|xox[a-z]-|glpat-|AKIA).*|.*[A-Fa-f0-9]{24,}.*|.*[A-Za-z0-9+/_-]{32,}.*""")

    /** A name as you'd say it: "api-server" is "api server". */
    fun name(text: String): String = words(text)

    /** A reply as you'd say it: names opened up, numbers and signs in words. */
    fun sentence(text: String): String = fit(
        text.replace(THOUSANDS, "$1 thousand").replace(MILLIONS, "$1 million")
            .replace("%", " percent").replace("…", "")
            .replace(JOINERS, " ").replace(SPACES, " ").trim(),
    )

    /** Cut at a sentence's end when a reply is too long to listen to. */
    fun fit(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val head = text.take(MAX_CHARS)
        val end = head.lastIndexOf(". ")
        return (if (end > 0) head.take(end + 1) else head.substringBeforeLast(' ') + ".") + THE_REST
    }

    /** What comes after "wants to", or null when the command is better left to the screen. */
    fun command(command: String): String? {
        val tokens = command.trim().split(SPACES).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any(::secretLooking)) return null
        if (tokens[0] == "git") return git(tokens.drop(1), command)
        if (NOT_PLAIN.containsMatchIn(command) || tokens.any { !PLAIN_WORD.matches(it) }) return null
        return when {
            tokens[0] == "rm" -> remove(tokens.drop(1))
            tokens[0] in PACKAGE_TOOLS && tokens.getOrNull(1) == "run" && tokens.size == 3 -> "run ${tokens[0]} ${words(tokens[2])}"
            tokens[0].endsWith("gradlew") && tokens.size == 2 -> "run gradle ${words(tokens[1].removePrefix(":"))}"
            tokens.size <= MAX_PLAIN_WORDS -> "run " + tokens.joinToString(" ") { words(it.trimStart('-')) }
            else -> null
        }
    }

    /**
     * The command read out as it's written, for an approval to rest on: "git push origin feat/auth" is "git push origin feat
     * slash auth". Null when it shouldn't be approved by ear: long, more than one plain command, or anything that could be a secret.
     */
    fun closely(command: String): String? {
        val tokens = command.trim().split(SPACES).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any(::secretLooking)) return null
        if (tokens.size >= 2 && tokens[0] == "git" && tokens[1] == "commit" && commitIsPlain(command)) return "git commit, with its message on screen"
        if (NOT_PLAIN.containsMatchIn(command) || tokens.any { !PLAIN_WORD.matches(it) }) return null
        return tokens.joinToString(" ", transform = ::spell).takeIf { it.length <= MAX_CLOSELY }
    }

    /** One quoted message and known flags, with nothing in the quotes the shell would run. */
    private fun commitIsPlain(command: String): Boolean {
        if (COMMIT_RUNS.containsMatchIn(command)) return false
        val outside = command.replace(QUOTED, "")
        if (NOT_PLAIN.containsMatchIn(outside)) return false
        return outside.trim().split(SPACES).drop(2).all { it in COMMIT_FLAGS }
    }

    private fun spell(token: String): String = token.map { SPELLED[it]?.let { word -> " $word " } ?: it.toString() }.joinToString("").replace(SPACES, " ").trim()

    private fun git(args: List<String>, whole: String): String? {
        val sub = args.firstOrNull() ?: return null
        val rest = args.drop(1)
        val flags = rest.filter { it.startsWith("-") }
        val named = rest.filterNot { it.startsWith("-") }
        // A commit message can hold anything, and is the only argument here worth quoting, so it's the one exception.
        if (sub == "commit") {
            if (NOT_PLAIN.containsMatchIn(whole.replace(QUOTED, ""))) return null
            if (rest.any { "amend" in it }) return "amend the last commit"
            return if (flags.isEmpty() || flags.all { it in COMMIT_FLAGS }) "make a commit" else null
        }
        if (NOT_PLAIN.containsMatchIn(whole) || named.any { !PLAIN_WORD.matches(it) }) return null
        return when (sub) {
            "push" -> push(flags, named)
            "pull" -> if (flags.isEmpty()) named.firstOrNull()?.let { "pull from ${words(it)}" } ?: "pull" else null
            "fetch" -> if (flags.isEmpty()) "fetch" else null
            "status" -> "check git status"
            "diff" -> if (flags.isEmpty() && named.isEmpty()) "look at the diff" else null
            "log" -> "read the git log"
            "add" -> "stage files"
            "stash" -> if (rest.isEmpty()) "stash changes" else null
            "merge" -> if (flags.isEmpty()) named.singleOrNull()?.let { "merge ${words(it)}" } else null
            "rebase" -> if (flags.isEmpty()) named.singleOrNull()?.let { "rebase onto ${words(it)}" } else null
            "clone" -> if (flags.isEmpty()) named.singleOrNull()?.let(::cloneName)?.let { "clone $it" } else null
            "switch", "checkout" -> switch(flags, named)
            "branch" -> when {
                flags.singleOrNull() == "-D" -> named.singleOrNull()?.let { "force delete branch ${words(it)}" }
                flags.singleOrNull() == "-d" -> named.singleOrNull()?.let { "delete branch ${words(it)}" }
                flags.isEmpty() && named.isEmpty() -> "list branches"
                flags.isEmpty() -> named.singleOrNull()?.let { "create branch ${words(it)}" }
                else -> null
            }
            "reset" -> when {
                flags.singleOrNull() != "--hard" -> null
                named.isEmpty() -> "hard reset"
                else -> named.singleOrNull()?.let { "hard reset to ${words(it)}" }
            }
            else -> null
        }
    }

    private fun push(flags: List<String>, named: List<String>): String? {
        if (named.size > 2 || named.any { ':' in it }) return null
        val force = flags.any { it == "-f" || it.startsWith("--force") }
        val known = flags.all { it == "-f" || it.startsWith("--force") || it == "-u" || it == "--set-upstream" }
        if (flags.any { it == "-d" || it == "--delete" } && flags.size == 1 && named.size == 2) return "delete ${words(named[1])} on ${words(named[0])}"
        if (!known) return null
        val verb = if (force) "force push" else "push"
        return when (named.size) {
            0 -> verb
            1 -> "$verb to ${words(named[0])}"
            else -> "$verb ${words(named[1])} to ${words(named[0])}"
        }
    }

    private fun switch(flags: List<String>, named: List<String>): String? {
        val branch = named.singleOrNull() ?: return null
        return when {
            flags.isEmpty() -> "switch to ${words(branch)}"
            flags.singleOrNull() in setOf("-b", "-c") -> "create branch ${words(branch)}"
            else -> null
        }
    }

    private fun remove(args: List<String>): String? {
        val flags = args.filter { it.startsWith("-") }
        val paths = args.filterNot { it.startsWith("-") }
        val force = flags.any { 'f' in it }
        val verb = if (force) "force delete" else "delete"
        val path = paths.singleOrNull() ?: return if (paths.isEmpty()) null else "$verb several files"
        return words(path).takeIf { it.isNotEmpty() }?.let { "$verb $it" }
    }

    private fun cloneName(url: String): String? = words(url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")).takeIf { it.isNotEmpty() }

    private fun secretLooking(token: String): Boolean =
        !token.startsWith("-") && token.contains('=') || token.contains('@') && !token.startsWith("git@") ||
            token.length > MAX_TOKEN_CHARS && '/' !in token || SECRET_LOOKING.matches(token.trim('"', '\''))

    /** "feat/auth" is "feat auth", "./src/session.ts" is "src session dot ts". */
    private fun words(token: String): String =
        token.removePrefix("./").removePrefix("~/").trimEnd('/').trimStart('/').replace(FILE_DOT, " dot ")
            .replace(JOINERS, " ").replace(SPACES, " ").trim()

    private val SPELLED = mapOf('/' to "slash", '-' to "dash", '.' to "dot", '_' to "underscore", ':' to "colon", '~' to "tilde")
    private val COMMIT_RUNS = Regex("""[$`\\\n]""")
    private const val MAX_CLOSELY = 160
    private val COMMIT_FLAGS = setOf("-m", "-a", "-am", "--message", "--all")
}
