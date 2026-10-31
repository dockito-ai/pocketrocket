package com.pocketrocket.github

import android.content.Context
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
import java.io.File

/**
 * Runs `gh` (and the ssh tools) in Termux's environment for one account: every call names the
 * GH_CONFIG_DIR to use, so nothing touches another account's login. Output is not logged.
 */
object GhCli {
    private val home = File(TERMUX_HOME_DIR_PATH)
    fun bin(name: String) = "$TERMUX_BIN_PREFIX_DIR_PATH/$name"

    fun environment(ghConfigDir: File) = mapOf(
        "GH_CONFIG_DIR" to ghConfigDir.path,
        "GH_NO_UPDATE_NOTIFIER" to "1",
        "GH_PROMPT_DISABLED" to "1",
        "NO_COLOR" to "1",
    )

    /** Starts `gh <arguments>` for [ghConfigDir], stdin open. */
    fun start(context: Context, ghConfigDir: File, vararg arguments: String): Process =
        TermuxCommand.start(context, bin("gh"), arrayOf(*arguments), home, environment(ghConfigDir))

    /** Runs `gh <arguments>` to the end. Returns the exit code and stdout lines (stderr after). Blocking. */
    fun run(context: Context, ghConfigDir: File, vararg arguments: String): Pair<Int, List<String>> =
        runTool(context, bin("gh"), arrayOf(*arguments), environment(ghConfigDir))

    /** Runs any Termux tool to the end, collecting its output. Blocking. */
    fun runTool(context: Context, executable: String, arguments: Array<String>, env: Map<String, String> = emptyMap()): Pair<Int, List<String>> {
        val lines = mutableListOf<String>()
        val process = TermuxCommand.start(context, executable, arguments, home, env)
        process.outputStream.close()
        val exit = TermuxCommand.drain(process) { synchronized(lines) { lines += it } }
        return exit to lines
    }
}
