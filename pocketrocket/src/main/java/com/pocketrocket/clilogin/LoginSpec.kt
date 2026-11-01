package com.pocketrocket.clilogin

import java.io.File

/** How the code the browser shows reaches the CLI, for logins that don't finish by themselves. */
sealed interface CodeEntry {
    /** The login command waits for it on stdin (`aws login --remote`). */
    data object Stdin : CodeEntry

    /** The login command prints the address and ends; a second command takes the code (`firebase login <code>`). */
    data class Command(val arguments: (String) -> List<String>) : CodeEntry
}

/**
 * One CLI's login facts (decisions 49 and 98): the commands, the address it prints, how a pasted code reaches it, and
 * how its config says someone is signed in. Plain facts and text, tested on their own; [CliLogin] runs them.
 */
interface LoginSpec {
    /** Its run folders' name under the temp folder. */
    val key: String

    /** The program, relative to `$PREFIX` ("bin/vercel"). */
    val executable: String

    fun loginArguments(home: File): List<String>

    val logoutArguments: List<String>

    /** The address to open in the browser, in the login's output. */
    val url: Regex

    /** Null when the login finishes by itself once allowed in the browser. */
    val code: CodeEntry? get() = null

    /** Extra variables for each run; [run] is a folder for this run only, deleted when it ends. */
    fun environment(run: File): Map<String, String> = emptyMap()

    /** Whether the CLI's config has a login. Only tests for it: no token is ever kept or shown. */
    fun signedIn(home: File): Boolean

    /** Who is signed in, when the config says (an email). */
    fun account(home: File): String? = null

    /** A command that names the account, for CLIs whose config doesn't; needs the network. */
    val whoamiArguments: List<String>? get() = null

    /** The account from [whoamiArguments]'s output. */
    fun whoami(lines: List<String>): String? = null

    /** Tidies up after a login was cancelled (flyctl's pending-login file). */
    fun cancelled(home: File) {}
}

/** The text side of a login's output. */
object LoginText {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val ANY_LINK = Regex("https?://\\S+")

    fun clean(line: String): String = line.replace(ANSI, "").replace("\r", "")

    /** The line with its links taken out (a sign-in address is one), for what stays on the page after the login ends. */
    fun withoutUrl(line: String): String = clean(line).replace(ANY_LINK, "(link removed)")

    /** A file's text, or null when it isn't there. */
    fun read(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
}
