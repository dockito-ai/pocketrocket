package com.pocketrocket.termux

import android.content.Context
import com.termux.shared.shell.StreamGobbler
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.io.File

/**
 * Runs a command in Termux's environment (TermuxShellEnvironment: PREFIX, PATH, HOME, TMPDIR and the
 * shebang handling Termux uses for its own app shells), streaming stdout and stderr line by line.
 * Termux's AppShell only hands back output when the command ends, so this streams it instead.
 */
object TermuxCommand {

    /** Blocks until the command exits; [onLine] is called from two reader threads. Returns the exit code. */
    fun run(
        context: Context,
        executable: String,
        arguments: Array<String>,
        workingDirectory: File,
        onLine: (String) -> Unit,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Int {
        val process = start(context, executable, arguments, workingDirectory, extraEnvironment)
        process.outputStream.close()
        return drain(process, onLine)
    }

    /**
     * Starts the command and returns the live process, stdin left open, for callers that talk to it.
     * [extraEnvironment] is added on top of Termux's environment (for example CLAUDE_CONFIG_DIR).
     */
    fun start(
        context: Context,
        executable: String,
        arguments: Array<String>,
        workingDirectory: File,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Process {
        val command = ExecutionCommand(
            -1, executable, arguments, null, workingDirectory.path, ExecutionCommand.Runner.APP_SHELL.getName(), false,
        )
        val shell = TermuxShellEnvironment()
        val args = shell.setupShellCommandArguments(command.executable, command.arguments)
        val environment = HashMap(shell.setupShellCommandEnvironment(context, command)).apply { putAll(extraEnvironment) }
        val environ = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment)
        return Runtime.getRuntime().exec(args, environ.toTypedArray(), workingDirectory)
    }

    /** Streams a started process's stdout and stderr to [onLine] until it exits. Returns the exit code. */
    fun drain(process: Process, onLine: (String) -> Unit): Int {
        val readers = listOf(process.inputStream, process.errorStream).mapIndexed { i, stream ->
            StreamGobbler("pocketrocket-$i", stream, { line -> onLine(line) }, null, null).apply { start() }
        }
        val exitCode = process.waitFor()
        readers.forEach { it.join() }
        return exitCode
    }
}
