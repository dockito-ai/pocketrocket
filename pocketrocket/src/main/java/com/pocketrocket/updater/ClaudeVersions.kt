package com.pocketrocket.updater

import com.pocketrocket.profiles.AccountPaths
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where Claude Code versions are: installed ones under ~/.pocketrocket/claude/<version>/ (the setup script
 * keeps `current` and one older for rollback), and released ones as named by downloads.claude.ai's
 * `latest` and `stable` files, the same lookup the setup script and Anthropic's install.sh use.
 */
object ClaudeVersions {
    private const val RELEASES = "https://downloads.claude.ai/claude-code-releases"
    private val VERSION = Regex("""^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$""")

    private val dir: File get() = File(AccountPaths.home, ".pocketrocket/claude")

    /** The version `current` points at, or null when Claude Code is not installed. */
    fun installed(): String? = runCatching {
        File(dir, "current").canonicalFile.name.takeIf { isVersion(it) && File(dir, "$it/claude").exists() }
    }.getOrNull()

    /** The other installed version kept for rollback, newest first (the script keeps at most one). */
    fun kept(): String? {
        val current = installed()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && isVersion(it.name) && it.name != current && File(it, "claude").exists() }
            .map { it.name }
            .sortedWith(::compare)
            .lastOrNull()
    }

    /** Reads a channel file (`latest` or `stable`); null when it is missing or the network is down. */
    fun fetch(channel: String): String? = runCatching {
        val connection = URL("$RELEASES/$channel").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { it.readText().trim() }.takeIf(::isVersion)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun isVersion(text: String): Boolean = VERSION.matches(text)

    /**
     * Orders X.Y.Z versions numerically; a pre-release sorts before its release, and pre-releases of the
     * same version compare part by part, numbers as numbers (beta.9 < beta.25), as in semver.
     */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        val ra = a.substringAfter('-', "")
        val rb = b.substringAfter('-', "")
        if (ra.isEmpty() || rb.isEmpty()) return rb.length.coerceAtMost(1).compareTo(ra.length.coerceAtMost(1))
        val xa = ra.split('.')
        val xb = rb.split('.')
        for (i in 0 until minOf(xa.size, xb.size)) {
            val na = xa[i].toIntOrNull()
            val nb = xb[i].toIntOrNull()
            val d = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> xa[i].compareTo(xb[i])
            }
            if (d != 0) return d
        }
        return xa.size.compareTo(xb.size)
    }

    fun isNewer(candidate: String?, than: String?): Boolean =
        candidate != null && (than == null || compare(candidate, than) > 0)
}
