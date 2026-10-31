package com.pocketrocket.setup

import android.content.Context
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * The setup script shipped in assets/setup/ (built from the repo's setup/ folder). It is copied to
 * ~/.pocketrocket/setup/ and run with bash in Termux's environment.
 */
object SetupScript {
    private const val ASSET_DIR = "setup"
    private const val SCRIPT = "pocketrocket-setup.sh"

    private val installDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".pocketrocket/setup")

    /**
     * Runs the script to the end with [options] (none: full setup; `--only claude`, `--switch X.Y.Z`) and
     * [environment] on top of Termux's, calling [onLine] for every output line. Returns the exit code.
     */
    fun run(
        context: Context,
        options: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Int {
        copyAssets(context)
        val bash = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/bash"
        val arguments = arrayOf(File(installDir, SCRIPT).path) + options
        return TermuxCommand.run(context, bash, arguments, installDir, onLine, environment)
    }

    private fun copyAssets(context: Context) {
        installDir.mkdirs()
        val assets = context.assets
        for (name in assets.list(ASSET_DIR).orEmpty()) {
            val target = File(installDir, name)
            assets.open("$ASSET_DIR/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            target.setExecutable(true, true)
        }
    }
}
