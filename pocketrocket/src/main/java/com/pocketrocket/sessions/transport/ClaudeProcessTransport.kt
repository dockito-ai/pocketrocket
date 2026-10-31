package com.pocketrocket.sessions.transport

import android.content.Context
import com.pocketrocket.auth.ClaudeUsage
import com.pocketrocket.sessions.protocol.StreamJsonWriter
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import kotlin.concurrent.thread

/**
 * The real thing: one long-lived `claude -p` in stream-json mode, run through the PocketRocket launcher
 * in Termux's environment. Permission prompts come back as `can_use_tool` control requests on stdout
 * (`--permission-prompt-tool stdio`), after the SDK-style `initialize` handshake. Nothing read here is
 * logged: it is the user's conversation.
 */
class ClaudeProcessTransport(
    private val context: Context,
    private val folder: File,
    private val conversationId: String,
    private val resume: Boolean,
    private val environment: Map<String, String> = emptyMap(),
    private val customArguments: Array<String>? = null,
    /** Flags on top of the usual ones: the brain's lockdown, or the hook for agents. */
    private val extraArguments: List<String> = emptyList(),
) : ClaudeTransport {

    @Volatile private var process: Process? = null
    private val lock = Any()
    private var stdin: Writer? = null
    private val pending = mutableListOf<String>()
    @Volatile private var closed = false

    override fun start(listener: ClaudeTransport.Listener) {
        thread(name = "claude-${conversationId.take(8)}", isDaemon = true) {
            val started = try {
                TermuxCommand.start(context, "$TERMUX_PREFIX_DIR_PATH/bin/claude", arguments(), folder, environment)
            } catch (e: Exception) {
                listener.onExit(-1, e.message)
                return@thread
            }
            process = started
            // Closed while it was starting: close() found no process to stop, so it stops here.
            if (closed) {
                started.destroy()
                return@thread
            }
            synchronized(lock) {
                stdin = started.outputStream.bufferedWriter()
                write(StreamJsonWriter.initialize("pr-init"))
                pending.forEach(::write)
                pending.clear()
            }
            val stderr = reader(started.errorStream, listener::onStderr)
            val configDir = environment["CLAUDE_CONFIG_DIR"]
            readLines(started.inputStream) { line ->
                ClaudeUsage.observe(configDir, line) // plan usage per account, from rate_limit_event
                listener.onLine(line)
            }
            val code = started.waitFor()
            stderr.join()
            if (!closed) listener.onExit(code, null)
        }
    }

    private fun arguments(): Array<String> = customArguments ?: arrayOf(
        "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        // Without this, -p denies anything that needs approval; with it, approvals arrive as can_use_tool control requests.
        "--permission-prompt-tool", "stdio",
        // Text arrives as stream_event deltas too, so replies grow token by token.
        "--include-partial-messages",
        // Each message of ours comes back as Claude reads it, so one sent mid-turn joins the chat where it is answered.
        "--replay-user-messages",
        if (resume) "--resume" else "--session-id", conversationId,
        *extraArguments.toTypedArray(),
    )

    private fun reader(stream: InputStream, onLine: (String) -> Unit) =
        thread(isDaemon = true) { readLines(stream, onLine) }

    /** Reads until the stream ends. [close] destroys the process mid-read, which throws here; that is the end too. */
    private fun readLines(stream: InputStream, onLine: (String) -> Unit) {
        try {
            stream.bufferedReader().forEachLine(onLine)
        } catch (_: IOException) {
        }
    }

    /** Lines sent before the process is up wait in [pending]. */
    override fun send(line: String) {
        synchronized(lock) {
            if (stdin == null) pending.add(line) else write(line)
        }
    }

    private fun write(line: String) {
        runCatching { stdin?.apply { write(line); write("\n"); flush() } }
    }

    override fun close() {
        closed = true
        synchronized(lock) { runCatching { stdin?.close() } }
        process?.destroy()
    }

    companion object {
        /**
         * A process for control requests only (`claudeconfig/ClaudeConfigProbe`): no conversation id, nothing saved as
         * a session. It handshakes like any other, then waits on stdin and exits when stdin closes.
         */
        fun controlOnly(context: Context, folder: File, environment: Map<String, String>) = ClaudeProcessTransport(
            context, folder, "control", resume = false, environment,
            arrayOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--no-session-persistence"),
        )
    }
}
