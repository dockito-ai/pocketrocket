package com.pocketrocket.workspaces

import android.content.Context
import android.util.Log
import com.pocketrocket.git.GitCli
import com.pocketrocket.github.GitIdentity
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.GitHubAccountEntry
import com.pocketrocket.termux.TermuxCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/** Where a clone stands. */
sealed interface CloneProgress {
    /**
     * [phase] is git's own word ("Receiving objects"), [percent] its number when it gave one, [count] and
     * [total] the "(1234/2742)" beside it, [size] and [speed] the "12.34 MiB | 3.21 MiB/s" while downloading.
     */
    data class Running(
        val phase: String, val percent: Int?,
        val count: Int? = null, val total: Int? = null, val size: String? = null, val speed: String? = null,
    ) : CloneProgress
    /** [workspace] is the workspace the repo landed in, once it is bound (CloneManager). */
    data class Done(val dir: File, val workspace: String? = null) : CloneProgress
    data class Failed(val message: String) : CloneProgress
}

/** A remote's branches: [default] is where its HEAD points (null when it doesn't say), listed first in [names]. */
data class RemoteBranches(val default: String?, val names: List<String>)

/**
 * `git clone --progress` into a folder, with the environment of the workspace it lands in: its GitHub
 * account's GH_CONFIG_DIR and, for SSH URLs, that account's key as GIT_SSH_COMMAND. (~/.gitconfig's
 * includeIf for the workspace only applies once the repo exists, so the clone itself names the key.)
 */
object RepoClone {
    private const val TAG = "PocketRocketClone"
    private val progress = Regex("""^(?:remote: )?([A-Za-z ]+):\s+(\d+)%(?:\s*\((\d+)/(\d+)\))?(?:,\s*([\d.]+ [KMG]?i?B)(?:\s*\|\s*([\d.]+ [KMG]?i?B/s))?)?""")

    /** One progress line from git, e.g. "Receiving objects:  45% (1234/2742), 12.34 MiB | 3.21 MiB/s". */
    internal fun parse(line: String): CloneProgress.Running? = progress.find(line)?.let { m ->
        val g = m.groupValues
        CloneProgress.Running(
            g[1].trim(), g[2].toInt(), g[3].toIntOrNull(), g[4].toIntOrNull(),
            g[5].ifEmpty { null }?.let(::bytes), g[6].ifEmpty { null }?.removeSuffix("/s")?.let { bytes(it) + "/s" },
        )
    }

    /** git's "12.34 MiB" as "12.3 MB": the words people read, one decimal. */
    private fun bytes(text: String): String {
        val (number, unit) = text.split(' ', limit = 2)
        val n = number.toDoubleOrNull() ?: return text
        val shown = if (n >= 100 || n == Math.floor(n)) n.toLong().toString() else String.format(java.util.Locale.US, "%.1f", n)
        return "$shown ${unit.replace("iB", "B")}"
    }

    /**
     * The whole clone as one number, for the notification and the bar: git counts each phase from 0 to 100,
     * so receiving objects is 0-80, resolving deltas 80-95 and checking out files 95-100. The remote's own
     * counting and compressing come before anything is received: null (no number yet).
     */
    fun overallPercent(p: CloneProgress.Running): Int? {
        val n = p.percent ?: return null
        return when {
            p.phase.startsWith("Receiving") -> n * 80 / 100
            p.phase.startsWith("Resolving") -> 80 + n * 15 / 100
            p.phase.startsWith("Updating") || p.phase.startsWith("Checking out") -> 95 + n * 5 / 100
            else -> null
        }
    }

    /** "owner/repo" for a GitHub-style URL (https or git@host:owner/repo.git), else the folder name git would pick. */
    fun labelFor(url: String): String {
        val path = url.trim().trimEnd('/').removeSuffix(".git").let { u ->
            if (u.contains("://")) u.substringAfter("://").substringAfter('/', "") else u.substringAfter(':', "")
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else nameFor(url)
    }

    /** The folder name git would pick for [url]: its last path part without `.git`. */
    fun nameFor(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifEmpty { "repo" }

    /** The git environment for talking to a remote from [parent] as [account]. */
    private fun envFor(parent: File, account: GitHubAccountEntry?): Map<String, String> = buildMap {
        putAll(AccountWiring.envFor(parent.path))
        account?.let { put("GH_CONFIG_DIR", it.ghConfigDir.path) }
        put("GIT_TERMINAL_PROMPT", "0")
        account?.takeIf { it.sshKey.exists() }?.let { put("GIT_SSH_COMMAND", GitIdentity.sshCommand(it)) }
    }

    /** `git ls-remote --symref` output as the remote's branches: its default (HEAD) first, then the rest by name. */
    internal fun parseBranches(lines: List<String>): RemoteBranches {
        val rows = lines.map { it.trim().split(Regex("\\s+")) }
        val default = rows.firstOrNull { it.size == 3 && it[0] == "ref:" && it[2] == "HEAD" }?.get(1)?.removePrefix("refs/heads/")
        val names = rows.filter { it.size == 2 && it[1].startsWith("refs/heads/") }.map { it[1].removePrefix("refs/heads/") }.sorted()
        return RemoteBranches(default, listOfNotNull(default) + (names - setOfNotNull(default)))
    }

    /** The branches of [url], asked as [account] with the key the clone would use. */
    suspend fun branches(context: Context, url: String, parent: File, account: GitHubAccountEntry?): Result<RemoteBranches> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
                val dir = parent.takeIf { it.isDirectory } ?: AccountPaths.home
                val exit = TermuxCommand.run(
                    context, GitCli.executable.path, arrayOf("ls-remote", "--symref", url.trim(), "HEAD", "refs/heads/*"),
                    dir, { lines += it }, envFor(parent, account),
                )
                if (exit != 0) error(lines.lastOrNull { it.startsWith("fatal:") || it.startsWith("ERROR") }?.removePrefix("fatal: ") ?: "git exited with $exit")
                parseBranches(lines.toList())
            }
        }

    /**
     * True when [dir] is what a clone of [url] leaves when it dies before finishing (git or the app
     * killed): only a `.git` whose origin is [url] and which never got a branch. Safe to clear and retry.
     */
    internal fun isLeftoverOf(dir: File, url: String): Boolean {
        val git = File(dir, ".git")
        if (dir.list()?.toList() != listOf(".git") || !git.isDirectory) return false
        val origin = File(git, "config").takeIf { it.isFile }?.readLines()
            ?.map { it.trim() }?.dropWhile { it != "[remote \"origin\"]" }?.drop(1)?.takeWhile { !it.startsWith("[") }
            ?.firstOrNull { it.startsWith("url") }?.substringAfter('=')?.trim()
        val branches = File(git, "refs/heads").list().orEmpty()
        return origin == url.trim() && branches.isEmpty() && !File(git, "packed-refs").exists()
    }

    /**
     * Clones [url] into [parent]/[name] as [account] (the folder's GitHub account unless another was
     * picked; the caller then binds the new repo to it), checking out [branch], or the remote's default
     * when null. Cancelling the flow kills git.
     */
    fun clone(
        context: Context, url: String, parent: File, name: String,
        account: GitHubAccountEntry? = AccountWiring.githubFor(parent.path),
        branch: String? = null,
    ): Flow<CloneProgress> = callbackFlow {
        parent.mkdirs()
        val target = File(parent, name)
        if (isLeftoverOf(target, url)) target.deleteRecursively()
        if (target.exists() && (target.list()?.isNotEmpty() == true)) {
            trySend(CloneProgress.Failed("${target.name} already exists in this folder."))
            close()
            awaitClose {}
            return@callbackFlow
        }
        trySend(CloneProgress.Running("Starting", null))
        val args = listOf("clone", "--progress") + (branch?.let { listOf("--branch", it) } ?: emptyList()) + listOf(url.trim(), name)
        val process = TermuxCommand.start(context, GitCli.executable.path, args.toTypedArray(), parent, envFor(parent, account))
        process.outputStream.close()
        val tail = ArrayDeque<String>()
        val readers = listOf(process.inputStream, process.errorStream).map { stream ->
            thread(name = "clone-reader") {
                // git rewrites its progress line with \r, so split on both.
                val line = StringBuilder()
                fun flush() {
                    val text = line.toString().trim()
                    line.setLength(0)
                    if (text.isEmpty()) return
                    synchronized(tail) { tail.addLast(text); if (tail.size > 6) tail.removeFirst() }
                    parse(text)?.let { trySend(it) }
                        ?: if (text.startsWith("Cloning into")) trySend(CloneProgress.Running("Connecting", null)) else Unit
                }
                // Cancelling closes the streams under us; that read failure is just the end of output.
                runCatching {
                    stream.reader().use { r ->
                        while (true) {
                            val c = r.read()
                            if (c < 0) break
                            if (c == '\r'.code || c == '\n'.code) flush() else line.append(c.toChar())
                        }
                    }
                }
                flush()
            }
        }
        thread(name = "clone-wait") {
            val exit = process.waitFor()
            readers.forEach { it.join() }
            Log.i(TAG, "clone ${nameFor(url)} -> $exit")
            if (exit == 0) trySend(CloneProgress.Done(target))
            else {
                val why = synchronized(tail) { tail.lastOrNull { it.startsWith("fatal:") || it.startsWith("ERROR") } ?: tail.lastOrNull() }
                // git cleans up after itself on most failures, but not when killed; don't leave half a repo.
                target.deleteRecursively()
                trySend(CloneProgress.Failed(why?.removePrefix("fatal: ") ?: "git exited with $exit"))
            }
            close()
        }
        awaitClose { if (process.isAlive) process.destroy() }
    }.flowOn(Dispatchers.IO)
}
