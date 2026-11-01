package com.pocketrocket.wrangler

import com.pocketrocket.clilogin.LoginSpec
import com.pocketrocket.clilogin.LoginText
import java.io.File

/**
 * `wrangler login --browser=false` (decision 49): it prints Cloudflare's OAuth address and waits on localhost for the
 * browser to come back. Its debug log, with the address in it, goes to the run's own folder, deleted when it ends.
 */
object WranglerLogin : LoginSpec {
    override val key = "wrangler"
    override val executable = "bin/wrangler"
    override fun loginArguments(home: File) = listOf("login", "--browser=false")
    override val logoutArguments = listOf("logout")
    override val url = WranglerTool.OAUTH_URL
    override fun environment(run: File) = mapOf("WRANGLER_LOG_PATH" to File(run, "wrangler.log").path, "WRANGLER_SEND_METRICS" to "false")
    override fun signedIn(home: File) = WranglerTool.hasLogin(LoginText.read(File(home, WranglerTool.CONFIG_RELATIVE)))
    override val whoamiArguments = listOf("whoami")
    override fun whoami(lines: List<String>) = WranglerTool.email(lines)
}
