package com.pocketrocket.termux

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * dpkg's status file, `$PREFIX/var/lib/dpkg/status`: which packages are installed right now, however they got there
 * (a tool's Install, `pkg install` in a terminal, or a dependency of something else). A plain file read, no process.
 */
object DpkgStatus {
    private val file = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/status")

    /** One installed package: its version as dpkg has it ("1:2.51.0-1") and its Installed-Size in KB. */
    data class Package(val version: String, val installedKb: Int?)

    /** The installed packages among [names] (every one when null), by name. Removed ones that kept config don't count. */
    fun parse(text: String?, names: Set<String>? = null): Map<String, Package> = parse(text) { names == null || it in names }

    /** The installed packages whose name passes [keep] (Rust's: `rust` and every `rust-std-*`), by name. */
    fun parse(text: String?, keep: (String) -> Boolean): Map<String, Package> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Package>()
        for (block in text.split("\n\n")) {
            var name: String? = null
            var version: String? = null
            var kb: Int? = null
            var installed = false
            for (line in block.lineSequence()) {
                when {
                    line.startsWith("Package: ") -> name = line.substringAfter(": ").trim()
                    line.startsWith("Version: ") -> version = line.substringAfter(": ").trim()
                    line.startsWith("Installed-Size: ") -> kb = line.substringAfter(": ").trim().toIntOrNull()
                    line.startsWith("Status: ") -> installed = line.trim().endsWith(" installed") && line.contains(" ok ")
                }
            }
            if (name != null && installed && keep(name)) out[name] = Package(version.orEmpty(), kb)
        }
        return out
    }

    /** [parse] of the phone's status file. Blocking; call off the main thread. */
    fun read(names: Set<String>? = null): Map<String, Package> = read { names == null || it in names }

    /** [parse] of the phone's status file, by name test. Blocking (one small file). */
    fun read(keep: (String) -> Boolean): Map<String, Package> =
        parse(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull(), keep)

    /** Their Installed-Size together in MB, at least 1; null when there are none or dpkg gives no sizes. */
    fun sizeMb(packages: Collection<Package>): Int? {
        val kb = packages.mapNotNull { it.installedKb }.takeIf { it.isNotEmpty() }?.sum() ?: return null
        return ((kb + 512) / 1024).coerceAtLeast(1)
    }

    /** A version as shown: no epoch ("1:2.51.0" reads "2.51.0"). */
    fun display(version: String): String = version.substringAfter(':')
}
