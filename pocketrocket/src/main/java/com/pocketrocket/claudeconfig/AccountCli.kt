package com.pocketrocket.claudeconfig

import android.content.Context
import com.pocketrocket.auth.ClaudeCli
import com.pocketrocket.profiles.AccountPaths
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * [ConfigCli] for one account's config dir ([configDir] null is the first account's `~/.claude`), through
 * [ClaudeCli.start]. Not [ClaudeCli.run]: that mixes stdout with stderr, can't take stdin or time out, and reads through
 * Termux's StreamGobbler, which logs every line when Termux's log level is verbose. Nothing here logs anything.
 */
class AccountCli(context: Context, private val configDir: File?) : ConfigCli {
    private val context = context.applicationContext

    override fun run(arguments: List<String>, stdin: String?, timeoutMs: Long): CliOutput {
        val process = try {
            ClaudeCli.start(context, arguments.toTypedArray(), configDir)
        } catch (e: Exception) {
            // Not e.message: it isn't needed, and this class never prints what a command was given.
            return CliOutput(-1, emptyList(), listOf("Couldn't start Claude Code. Is it installed?"))
        }
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val readers = listOf(reader(process.inputStream, stdout), reader(process.errorStream, stderr))
        // On a thread of its own, so a command that prints before reading can't block on a full pipe.
        thread(name = "claude-config-stdin", isDaemon = true) {
            runCatching { process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } } }
        }
        val timedOut = AtomicBoolean(false)
        val watchdog = thread(name = "claude-config-timeout", isDaemon = true) {
            try {
                Thread.sleep(timeoutMs)
                timedOut.set(true)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }
        val exit = process.waitFor()
        watchdog.interrupt()
        // A child left behind (git, say) can hold the pipes open, so don't wait for ever.
        readers.forEach { it.join(READER_WAIT_MS) }
        return CliOutput(exit, synchronized(stdout) { stdout.toList() }, synchronized(stderr) { stderr.toList() }, timedOut.get())
    }

    private fun reader(stream: InputStream, into: MutableList<String>): Thread = thread(name = "claude-config-read", isDaemon = true) {
        runCatching { stream.bufferedReader().forEachLine { line -> synchronized(into) { into += line } } }
    }

    private companion object {
        const val READER_WAIT_MS = 2_000L
    }
}

/** Where one account's server list and the stdio helper are on this phone. Never `.credentials.json`. */
object LocalMcpFiles {
    /** Termux's own `exec` shim: see [StdioAdvice]. */
    val execHelper: String get() = "${TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH}/libtermux-exec.so"
    val binDir: String get() = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH

    /** `<config dir>/.claude.json`; the first account, with no CLAUDE_CONFIG_DIR, has `~/.claude.json`. */
    fun config(configDir: File?) = File(configDir ?: AccountPaths.home, McpConfigFile.NAME)

    /** Null when there is none yet. May throw. */
    fun read(configDir: File?): String? = config(configDir).takeIf { it.exists() }?.readText()

    /** How a script starts, or null when it isn't a readable file. */
    fun firstLine(path: String): String? = runCatching {
        File(path).takeIf { it.isFile && it.canRead() }?.bufferedReader()?.use { reader ->
            val start = CharArray(FIRST_LINE_MAX)
            val read = reader.read(start)
            if (read <= 0) null else String(start, 0, read).lineSequence().first()
        }
    }.getOrNull()

    private const val FIRST_LINE_MAX = 200
}
