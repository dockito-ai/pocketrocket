package com.pocketrocket.clitools

import com.pocketrocket.clilogin.LoginSpec
import com.pocketrocket.node.NpmGlobal
import com.pocketrocket.setup.ToolPresence
import java.io.File

/** One npm CLI as it is on the phone now: its folder's version, and what keeps it from running ([ToolPresence]). */
data class NpmPresence(val version: String?, val problem: String? = null)

/**
 * The deploy CLIs that come from npm (decision 98): Vercel, Netlify and Firebase, installed globally with
 * `npm install -g`. The setup script's `--only <option>` / `<option>-remove` installs and removes them
 * (`npm_tool_*`); `<option>-check` and `<option>-update` are their Updates row. On the phone means npm's folder for the package is there, whoever ran the install, so a terminal's
 * `npm i -g vercel` shows on the tile too. Sizes are from the run on the phone (docs/tool-sweep.md): each takes two to
 * three minutes.
 */
enum class NpmTool(
    val option: String,
    val npm: String,
    /** Its program in `$PREFIX/bin`. */
    val bin: String,
    val title: String,
    val downloadMb: Int,
    val installedMb: Int,
    val installMinutes: Int,
    val login: LoginSpec,
) {
    Vercel("vercel", "vercel", "vercel", "the Vercel CLI", downloadMb = 197, installedMb = 215, installMinutes = 2, login = VercelLogin),
    Netlify("netlify", "netlify-cli", "netlify", "the Netlify CLI", downloadMb = 263, installedMb = 254, installMinutes = 3, login = NetlifyLogin),
    Firebase("firebase", "firebase-tools", "firebase", "the Firebase CLI", downloadMb = 140, installedMb = 260, installMinutes = 2, login = FirebaseLogin),
    ;

    val removeOption: String get() = "$option-remove"
    /** `--only <option>-update`: `npm install -g <npm>@latest`, with the install's progress. */
    val updateOption: String get() = "$option-update"
    /** `--only <option>-check`: prints `npm view <npm> version`; no steps, no lock. */
    val checkOption: String get() = "$option-check"
    val installSteps: List<String> get() = listOf("Checking the device", "Installing $title")
    val updateSteps: List<String> get() = listOf("Checking the device", "Updating $title")
    val removeSteps: List<String> get() = listOf("Checking the device", "Removing $title")

    /**
     * What is on the phone under [prefix], or null when npm's folder for it isn't there. A folder without its
     * `$PREFIX/bin` link (an npm run that stopped midway) is there but needs Repair.
     */
    fun presence(prefix: File): NpmPresence? {
        val dir = NpmGlobal.dir(prefix, npm)
        if (!File(dir, "package.json").isFile) return null
        val launcher = File(prefix, "bin/$bin").exists()
        return NpmPresence(NpmGlobal.version(dir), ToolPresence.of(true, listOfNotNull("$bin command".takeUnless { launcher })).problem)
    }
}
