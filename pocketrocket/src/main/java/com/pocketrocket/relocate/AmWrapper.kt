package com.pocketrocket.relocate

import android.util.Log
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Points `$PREFIX/bin/am` at `termux-am` (decision 36).
 *
 * The termux-am package's `am` runs its own dex through app_process, and that dex has "com.termux" compiled in as the
 * calling package, so Android refuses its broadcasts once we run as another id ("not allowed to send as package").
 * The byte swap can't fix it without breaking the dex checksum. `termux-am` sends the same command through the app's
 * am socket server instead (on by default), so it runs inside the app as our own id. Termux's scripts
 * (termux-setup-storage, termux-open, termux-reload-settings) all call plain `am`, so `am` becomes a two-line
 * wrapper that execs `termux-am`.
 *
 * The original is moved aside with a dpkg diversion to `bin/am.termux`, so upgrading or reinstalling termux-am lands
 * there and never overwrites the wrapper. Runs on every start on the real $PREFIX: the diversion is only added once
 * (dpkg's database is checked first) and the wrapper only rewritten when it differs.
 */
object AmWrapper {
    private const val TAG = "AmWrapper"
    private const val AM = "bin/am"
    private const val DIVERTED = "bin/am.termux"

    @JvmStatic
    fun install() {
        if (!PrefixRelocation.needed) return
        val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        try {
            if (!isDiverted(prefix) && !divert(prefix)) return
            val am = File(prefix, AM)
            PrefixRelocation.writeIfChanged(am, wrapper(prefix.path))
            am.setExecutable(true, true)
        } catch (e: Exception) {
            Log.w(TAG, "could not install the am wrapper", e)
        }
    }

    /** dpkg's diversions file lists each diversion as three lines: from, to, owning package. */
    private fun isDiverted(prefix: File): Boolean {
        val db = File(prefix, "var/lib/dpkg/diversions")
        return db.isFile && db.readLines().contains("${prefix.path}/$DIVERTED")
    }

    /** `dpkg-divert --add --rename`: records the diversion and moves the package's am to am.termux. Fails harmlessly
     *  (and is retried next start) when dpkg is busy, e.g. apt running in a terminal. */
    private fun divert(prefix: File): Boolean {
        val p = prefix.path
        val process = ProcessBuilder(
            "$p/bin/dpkg-divert", "--add", "--rename", "--divert", "$p/$DIVERTED", "$p/$AM",
        ).redirectErrorStream(true).apply {
            environment().apply {
                put("PREFIX", p)
                put("PATH", "$p/bin")
                put("TMPDIR", "$p/tmp")
                put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH)
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
            }
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        val ok = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        if (!ok) Log.w(TAG, "dpkg-divert failed: $output")
        return ok && isDiverted(prefix)
    }

    private fun wrapper(prefix: String) = """
        |#!$prefix/bin/sh
        |# PocketRocket (decision 36), written by the app (AmWrapper.kt); edits here are overwritten.
        |# The package's am is diverted to am.termux; it sends as com.termux, which Android refuses for this app.
        |exec $prefix/bin/termux-am "${'$'}@"
        |""".trimMargin()
}
