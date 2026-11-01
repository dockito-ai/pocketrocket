package com.pocketrocket.wrangler

import com.pocketrocket.clilogin.LoginText
import com.pocketrocket.node.NpmGlobal
import com.pocketrocket.setup.ToolPresence
import java.io.File

/** The fixed facts of the Wrangler tool (decisions 49 and 82), and what its output means. Sizes are measured on the emulator. */
object WranglerTool {
    /** The version the setup script pins; shown on the page. */
    const val VERSION = "4.156.0"

    /** npm's global folder for it, under $PREFIX, whoever ran `npm install -g wrangler`. */
    const val PACKAGE_RELATIVE = "lib/node_modules/wrangler"

    /** The workerd Wrangler pins, built for Android with tcmalloc off (ports/workerd). */
    const val WORKERD_VERSION = "1.20261023.1"

    /** workerd's npm package inside Wrangler's folder. */
    const val WORKERD_PACKAGE_RELATIVE = "node_modules/workerd"

    /** The Android build of workerd and the wrapper that runs it, put there by the setup script; and workerd's loader. */
    const val WORKERD_RELATIVE = "$WORKERD_PACKAGE_RELATIVE/lib/workerd-glibc"
    const val WORKERD_WRAPPER_RELATIVE = "$WORKERD_PACKAGE_RELATIVE/lib/workerd-android"
    const val WORKERD_LOADER_RELATIVE = "$WORKERD_PACKAGE_RELATIVE/lib/main.js"

    /** The line the setup script adds to workerd's loader (wrangler_patch_workerd). Without it Wrangler won't start on Android. */
    const val LOADER_PATCH = "\"workerd-android\""

    /**
     * Wrangler as it is now, whoever installed it. It is on the phone while npm's folder for it is ([dir]). Wrangler
     * from a plain `npm install -g wrangler` lacks the loader line, so it won't start here; with the line but without
     * the Android workerd (an install from before local dev) it deploys but `wrangler dev` can't run. Repair runs the
     * tool's install, which puts in its pinned version with both.
     */
    fun presence(dir: File): ToolPresence {
        if (!File(dir, "package.json").isFile) return ToolPresence.Absent
        val patched = runCatching { File(dir, WORKERD_LOADER_RELATIVE).readText() }.getOrNull()?.contains(LOADER_PATCH) == true
        return when {
            !patched -> ToolPresence(true, "won't start until repaired")
            !File(dir, WORKERD_RELATIVE).isFile || !File(dir, WORKERD_WRAPPER_RELATIVE).isFile -> ToolPresence.of(true, listOf("local dev"))
            else -> ToolPresence(true)
        }
    }

    /** The version in an npm package's package.json ([dir]), or null. */
    fun packageVersion(dir: File): String? = NpmGlobal.version(dir)

    /** Wrangler's own config file under the home folder. The login lives here and nowhere else. */
    const val CONFIG_RELATIVE = ".config/.wrangler/config/default.toml"

    /** Wrangler from npm (78 MB, 62 MB on the phone) plus workerd (39 MB, 141 MB on the phone). */
    const val DOWNLOAD_MB = 117
    const val INSTALLED_MB = 203

    val INSTALL_STEPS = listOf("Checking the device", "Installing Wrangler", "Downloading the local runtime", "Checking Wrangler")
    val REMOVE_STEPS = listOf("Checking the device", "Removing Wrangler")

    /** The address `wrangler login --browser=false` prints ([WranglerLogin]). */
    val OAUTH_URL = Regex("https://dash\\.cloudflare\\.com/oauth2/auth\\?\\S+")
    private val EMAIL = Regex("associated with the email\\s+(\\S+@\\S+?)\\.?\\s*$")

    fun clean(line: String): String = LoginText.clean(line)

    /** The address to open in the browser, if this line of `wrangler login --browser=false` has it. */
    fun oauthUrl(line: String): String? = OAUTH_URL.find(clean(line))?.value

    /** The line with any sign-in address taken out, for what stays on the page after the sign-in ends. */
    fun withoutUrl(line: String): String = LoginText.withoutUrl(line)

    /** Whether Wrangler's config has a login. Only tests for the key: the token's value is never kept or shown. */
    fun hasLogin(configText: String?): Boolean =
        configText?.lineSequence()?.any { it.trimStart().startsWith("oauth_token") } == true

    /** The account's email from `wrangler whoami`, when it says one. */
    fun email(whoami: List<String>): String? = whoami.firstNotNullOfOrNull { line -> EMAIL.find(clean(line))?.groupValues?.get(1) }
}
