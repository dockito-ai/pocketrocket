package com.pocketrocket.browser

/**
 * How Claude Code is told to launch headless Chromium (decision 37). One user-scoped
 * `chrome-devtools` server per Claude account. `--isolated` gives each session its own profile
 * and its own browser process, so sessions do not share a page.
 *
 * `--no-sandbox` and `--disable-gpu` are what the emulator run needed under Termux.
 * The server is the version the spike drove, installed globally by the setup script. It is started as
 * `node <script>`: Claude Code is a glibc binary with no `/usr/bin/env`, so the script's own
 * `#!/usr/bin/env node` line cannot run (measured on the emulator: ENOENT on spawn).
 */
object ChromiumMcp {
    const val SERVER = "chrome-devtools"
    const val MCP_VERSION = "1.12.0"
    /**
     * Measured on the emulator. A fresh install downloads 312 MB with its dependencies (149 packages; the spike's
     * 187 MB was on a phone that already had some), apt said 838 MB of additional disk space. Shown rounded.
     */
    const val DOWNLOAD_MB = 310
    const val INSTALLED_MB = 840
    /** Measured in the spike: one headless browser with a page open. */
    const val MEMORY_MB = 360

    /** The steps `--only chromium` and `--only chromium-remove` print, so the page can list them before they start. */
    val INSTALL_STEPS = listOf("Checking the device", "Enabling the x11 repository", "Installing Chromium", "Installing Chrome DevTools MCP")
    val REMOVE_STEPS = listOf("Checking the device", "Removing Chromium", "Removing Chrome DevTools MCP")

    fun addArguments(binDir: String): Array<String> = arrayOf(
        "mcp", "add", "--scope", "user", SERVER, "--",
        "$binDir/node",
        "${java.io.File(binDir).parent}/lib/node_modules/chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js",
        "--headless=true",
        "--isolated=true",
        "--executablePath=$binDir/chromium-browser",
        "--chrome-arg=--no-sandbox",
        "--chrome-arg=--disable-gpu",
    )

    fun removeArguments(): Array<String> = arrayOf("mcp", "remove", SERVER, "--scope", "user")
}
