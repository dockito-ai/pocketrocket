package com.pocketrocket.node

import org.json.JSONObject
import java.io.File

/** npm's global packages on the phone (`npm install -g`), read from their folders: Wrangler and the deploy CLIs. */
object NpmGlobal {
    /** npm's folder for [pkg] under [prefix], whoever ran the install. */
    fun dir(prefix: File, pkg: String): File = File(prefix, "lib/node_modules/$pkg")

    /** The version in an npm package's package.json ([dir]), or null. */
    fun version(dir: File): String? =
        runCatching { JSONObject(File(dir, "package.json").readText()).optString("version").takeIf { it.isNotBlank() } }.getOrNull()

    private val SEMVER = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+\S*)?$""")

    /**
     * The version `npm view <package> version` printed: its last line that is a version ("65.9.1"). npm's warnings
     * ("npm warn …") and blank lines don't count; an error prints none, so null.
     */
    fun viewedVersion(lines: List<String>): String? =
        lines.map { it.trim().removeSurrounding("'") }.lastOrNull { SEMVER.matches(it) }

    /**
     * Whether [latest] is a newer release than [installed], as semver orders them (a pre-release before its release).
     * False when either is missing or not a version, and when the phone already has a newer one: never a downgrade.
     */
    fun newer(installed: String?, latest: String?): Boolean {
        val a = installed?.let { SEMVER.matchEntire(it.trim()) } ?: return false
        val b = latest?.let { SEMVER.matchEntire(it.trim()) } ?: return false
        for (i in 1..3) {
            val x = a.groupValues[i].toLong()
            val y = b.groupValues[i].toLong()
            if (x != y) return y > x
        }
        val pa = a.groupValues[4]
        val pb = b.groupValues[4]
        return when {
            pa == pb -> false
            pa.isEmpty() -> false // the release is on the phone; any pre-release of it is older
            pb.isEmpty() -> true
            else -> comparePre(pb, pa) > 0
        }
    }

    /** semver's pre-release order: dot parts left to right, numbers by value and before words. */
    private fun comparePre(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val n = x[i].toLongOrNull()
            val m = y[i].toLongOrNull()
            val c = when {
                n != null && m != null -> n.compareTo(m)
                n != null -> -1
                m != null -> 1
                else -> x[i].compareTo(y[i])
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
