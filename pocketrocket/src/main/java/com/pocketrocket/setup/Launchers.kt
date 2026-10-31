package com.pocketrocket.setup

import android.content.Context
import android.util.Log
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * PocketRocket's scripts in $PREFIX/bin, copied from the app's assets (setup/, built from the repo's setup folder)
 * when they differ, so a phone set up by an older APK gets the current ones after an app update.
 */
object Launchers {
    private const val TAG = "PocketRocketLaunchers"

    /**
     * App start: `claude`, where setup installed it (it is how the app tells Claude Code is there), and
     * `pocketrocket-bash`, the shell it gives Claude Code's Bash tool (docs/tool-sweep.md, Phase 0).
     */
    fun refreshClaude(context: Context) {
        if (!File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "claude").exists()) return
        install(context, "pocketrocket-bash")
        install(context, "claude")
    }

    /** Copies setup/[name] to $PREFIX/bin/[name] when it differs, by rename so a running copy is never half-written. */
    fun install(context: Context, name: String) = runCatching {
        val bin = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
        if (!bin.isDirectory) return@runCatching
        val text = context.assets.open("setup/$name").use { it.readBytes() }
        val target = File(bin, name)
        if (!target.exists() || !target.readBytes().contentEquals(text)) {
            val tmp = File(bin, ".$name.tmp")
            tmp.writeBytes(text)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(target)) tmp.delete()
        }
        target.setExecutable(true, false)
    }.onFailure { Log.w(TAG, "could not install $name", it) }
}
