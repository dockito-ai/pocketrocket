package com.pocketrocket.pkgtools

import com.pocketrocket.termux.DpkgStatus

/** Another package that needs part of a tool, so that part stays on uninstall: Headless Chrome needs python and pip. */
data class HeldBy(val pkg: String, val name: String, val keeps: List<String>)

/**
 * Add-ons that are only Termux packages (decision 92): Python with pip and uv, Bun, and Rust's wasm target (an option
 * on the Rust page). The setup script's `--only <option>` / `<option>-remove` installs and removes them and holds the
 * same package lists (`pkg_tool_packages`). What is installed is read from dpkg's status each time, never from a flag
 * the app saved, so a `pkg install` or `pkg uninstall` in a terminal shows on the tile too. Sizes are apt's, from the
 * desk evaluation on the phone (docs/tool-sweep.md on `fix/env-shebang`).
 */
enum class PackageTool(
    val option: String,
    /** What the steps call it: "Installing Python, pip and uv". */
    val title: String,
    /** Its packages and the name each version shows under; the first is the one that means "installed". */
    val packages: List<Pair<String, String>>,
    val downloadMb: Int,
    val installedMb: Int,
    val heldBy: HeldBy? = null,
) {
    Python(
        "python", "Python, pip and uv", listOf("python" to "Python", "python-pip" to "pip", "uv" to "uv"),
        downloadMb = 16, installedMb = 67,
        heldBy = HeldBy("chromium", "Headless Chrome", listOf("python", "python-pip")),
    ),
    Bun("bun", "Bun", listOf("bun" to "Bun"), downloadMb = 25, installedMb = 85),
    RustWasm("rust-wasm", "the wasm target", listOf("rust-std-wasm32-unknown-unknown" to "wasm32-unknown-unknown"), downloadMb = 21, installedMb = 90),
    ;

    val main: String get() = packages.first().first
    val removeOption: String get() = "$option-remove"
    val installSteps: List<String> get() = listOf("Checking the device", "Installing $title")
    val removeSteps: List<String> get() = listOf("Checking the device", "Removing $title")

    /** Every package worth reading from dpkg for this tool: its own and the one that holds part of it. */
    val watched: Set<String> get() = packages.map { it.first }.toSet() + listOfNotNull(heldBy?.pkg)

    /** On the phone: its main package is installed, whoever put it there. */
    fun installed(pkgs: Map<String, DpkgStatus.Package>): Boolean = main in pkgs

    /** Its other packages that aren't there (Python without uv): their names, for "Add uv". */
    fun missing(pkgs: Map<String, DpkgStatus.Package>): List<String> =
        if (!installed(pkgs)) emptyList() else packages.filter { it.first !in pkgs }.map { it.second }

    /** Versions as the page lists them ("Python" to "3.14.8"), only for what is installed. */
    fun versions(pkgs: Map<String, DpkgStatus.Package>): List<Pair<String, String>> =
        packages.mapNotNull { (pkg, label) -> pkgs[pkg]?.let { label to DpkgStatus.display(it.version) } }

    /** Its installed packages' size in MB, from dpkg; null when none is there or dpkg gives no sizes. */
    fun sizeMb(pkgs: Map<String, DpkgStatus.Package>): Int? = DpkgStatus.sizeMb(packages.mapNotNull { pkgs[it.first] })

    /** What keeps part of it now (Headless Chrome is installed), or null. */
    fun held(pkgs: Map<String, DpkgStatus.Package>): HeldBy? = heldBy?.takeIf { it.pkg in pkgs }

    /** The names of its installed parts Uninstall would really take away; empty when everything stays. */
    fun removable(pkgs: Map<String, DpkgStatus.Package>): List<String> {
        val kept = held(pkgs)?.keeps.orEmpty()
        return packages.filter { it.first in pkgs && it.first !in kept }.map { it.second }
    }

    /** The names of the parts that stay because of [held]. */
    fun kept(pkgs: Map<String, DpkgStatus.Package>): List<String> {
        val kept = held(pkgs)?.keeps.orEmpty()
        return packages.filter { it.first in pkgs && it.first in kept }.map { it.second }
    }

    /**
     * The uninstall sheet's text: what goes, what stays for another tool, then [note]. "Headless Chrome needs Python
     * and pip, so they stay. uv is removed."
     */
    fun removeLine(pkgs: Map<String, DpkgStatus.Package>, note: String): String {
        val gone = removable(pkgs)
        val kept = kept(pkgs)
        val stays = held(pkgs)?.takeIf { kept.isNotEmpty() }?.let { "${it.name} needs ${names(kept)}, so ${if (kept.size == 1) "it stays" else "they stay"}." }
        val goes = gone.takeIf { it.isNotEmpty() }?.let { "${names(it)} ${if (it.size == 1) "is" else "are"} removed." }
        return listOfNotNull(stays, goes, note).joinToString(" ")
    }

    companion object {
        /** Every package any tool watches, for one read of dpkg's status. */
        val allWatched: Set<String> get() = entries.flatMap { it.watched }.toSet()

        /** "Python and pip", "uv", "a, b and c". */
        fun names(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
