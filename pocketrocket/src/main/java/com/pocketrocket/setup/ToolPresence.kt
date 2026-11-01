package com.pocketrocket.setup

import com.pocketrocket.termux.DpkgStatus
import java.io.File

/**
 * What a tool's pieces on disk and in dpkg say right now, however they got there: its tile, a terminal or a Claude
 * session. No flag saved at install counts, as decision 92 has for package tools. [present] means some of the tool is
 * on the phone, so its tile shows Installed and offers Uninstall; [problem] says what keeps it from working ("Gradle
 * missing"), and the page offers Repair, which runs its install again.
 */
data class ToolPresence(val present: Boolean, val problem: String? = null) {
    val complete: Boolean get() = present && problem == null

    companion object {
        val Absent = ToolPresence(false)

        /** [present], with the [missing] pieces named the way the tile says them ("Java 17, Gradle missing"). */
        fun of(present: Boolean, missing: List<String>): ToolPresence =
            ToolPresence(present, missing.takeIf { present && it.isNotEmpty() }?.let { "${it.joinToString(", ")} missing" })

        /** The [needed] packages (name to how the tile says it) that dpkg doesn't list as installed now. */
        fun missingPackages(pkgs: Map<String, DpkgStatus.Package>, needed: List<Pair<String, String>>): List<String> =
            needed.filter { it.first !in pkgs }.map { it.second }

        /** Whether any of [dir]'s sub-folders has [relative] in it (any platform with its package.xml). */
        fun anyChildHas(dir: File, relative: String): Boolean = dir.listFiles()?.any { File(it, relative).exists() } == true
    }
}
