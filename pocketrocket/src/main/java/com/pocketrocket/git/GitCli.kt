package com.pocketrocket.git

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Runs `git` from Termux's $PREFIX/bin inside a repo folder, keeping stdout and stderr apart (stdout
 * is parsed, stderr is shown to the user when something fails). Every call is timed and logged under
 * [TAG] so the CLI-first choice (decision 19) can be checked against real repos.
 */
class GitCli(private val context: Context, val repo: File) {

    data class Result(val exitCode: Int, val out: String, val err: String, val millis: Long, val outTruncated: Boolean) {
        val ok get() = exitCode == 0
    }

    /** Runs `git <args>`; stdout beyond [maxOutBytes] is dropped and flagged. Blocking: call off the main thread. */
    fun run(vararg args: String, maxOutBytes: Int = DEFAULT_MAX_OUT): Result =
        exec(context, executable.path, arrayOf(*args), repo, ENVIRONMENT, maxOutBytes)

    companion object {
        const val TAG = "PocketRocketGit"
        const val DEFAULT_MAX_OUT = 8 * 1024 * 1024

        /**
         * Runs any Termux tool in [dir] the way [run] runs git: stdout and stderr apart, stdout capped, timed and
         * logged (tool name and first arguments only). `gh` for a repo goes through here too.
         */
        fun exec(context: Context, tool: String, args: Array<String>, dir: File, env: Map<String, String>, maxOutBytes: Int = DEFAULT_MAX_OUT): Result {
            val start = SystemClock.elapsedRealtime()
            val process = TermuxCommand.start(context, tool, args, dir, env)
            process.outputStream.close()
            var errBytes = ByteArray(0)
            val errReader = thread(name = "git-stderr") { errBytes = readCapped(process.errorStream, 64 * 1024).first }
            val (outBytes, truncated) = readCapped(process.inputStream, maxOutBytes)
            if (truncated) process.destroy()
            val exit = process.waitFor()
            errReader.join()
            val millis = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "${File(tool).name} ${args.take(3).joinToString(" ")} -> $exit in $millis ms (${outBytes.size} B)")
            return Result(exit, String(outBytes), String(errBytes).trim(), millis, truncated)
        }

        private fun readCapped(stream: InputStream, max: Int): Pair<ByteArray, Boolean> {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (buffer.size() + n > max) {
                    buffer.write(chunk, 0, max - buffer.size())
                    return buffer.toByteArray() to true
                }
                buffer.write(chunk, 0, n)
            }
            return buffer.toByteArray() to false
        }

        val executable = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "git")

        /**
         * Never wait on a prompt nobody can answer, and do not take index.lock for read-only commands so
         * the viewer never blocks a Claude session's own git calls.
         */
        private val ENVIRONMENT = mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_OPTIONAL_LOCKS" to "0",
            "GIT_PAGER" to "cat",
        )
    }
}
