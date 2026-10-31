package com.pocketrocket.wrangler

/** The fixed facts of the Wrangler tool (decisions 49 and 82), and what its output means. Sizes are measured on the emulator. */
object WranglerTool {
    /** The version the setup script pins; shown on the page. */
    const val VERSION = "4.156.0"

    /** Inside the package folder; the setup script writes it last, so it means the whole install finished. */
    const val INSTALLED_MARKER = ".pocketrocket-installed"
    const val PACKAGE_RELATIVE = "lib/node_modules/wrangler"

    /** The workerd Wrangler pins, built for Android with tcmalloc off (github.com/dockito-ai/workerd-android). */
    const val WORKERD_VERSION = "1.20261023.1"

    /** The Android build of workerd inside the package folder, put there by the setup script. */
    const val WORKERD_RELATIVE = "node_modules/workerd/lib/workerd-glibc"

    /** Wrangler's own config file under the home folder. The login lives here and nowhere else. */
    const val CONFIG_RELATIVE = ".config/.wrangler/config/default.toml"

    /** Wrangler from npm (78 MB, 62 MB on the phone) plus workerd (39 MB, 141 MB on the phone). */
    const val DOWNLOAD_MB = 117
    const val INSTALLED_MB = 203

    val INSTALL_STEPS = listOf("Checking the device", "Installing Wrangler", "Downloading the local runtime", "Checking Wrangler")
    val REMOVE_STEPS = listOf("Checking the device", "Removing Wrangler")

    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val OAUTH_URL = Regex("https://dash\\.cloudflare\\.com/oauth2/auth\\?\\S+")
    private val ANY_OAUTH_QUERY = Regex("https?://\\S*oauth2?\\S*")
    private val EMAIL = Regex("associated with the email\\s+(\\S+@\\S+?)\\.?\\s*$")

    fun clean(line: String): String = line.replace(ANSI, "")

    /** The address to open in the browser, if this line of `wrangler login --browser=false` has it. */
    fun oauthUrl(line: String): String? = OAUTH_URL.find(clean(line))?.value

    /** The line with any sign-in address taken out, for what stays on the page after the sign-in ends. */
    fun withoutUrl(line: String): String = clean(line).replace(ANY_OAUTH_QUERY, "(link removed)")

    /** Whether Wrangler's config has a login. Only tests for the key: the token's value is never kept or shown. */
    fun hasLogin(configText: String?): Boolean =
        configText?.lineSequence()?.any { it.trimStart().startsWith("oauth_token") } == true

    /** The account's email from `wrangler whoami`, when it says one. */
    fun email(whoami: List<String>): String? = whoami.firstNotNullOfOrNull { line -> EMAIL.find(clean(line))?.groupValues?.get(1) }
}
