package com.pocketrocket.clitools

import com.pocketrocket.clilogin.CodeEntry
import com.pocketrocket.clilogin.LoginSpec
import com.pocketrocket.clilogin.LoginText
import com.termux.shared.termux.TermuxConstants
import org.json.JSONObject
import java.io.File

/*
 * The deploy CLIs' logins (decision 98), from the run on the phone ("Login, logout, status" in docs/tool-sweep.md).
 * Vercel, Netlify and flyctl print an address and wait until it is allowed; Firebase and the AWS CLI need the code the
 * browser shows. Signed in is read from each CLI's own config file.
 */

/** npm CLIs start with `#!/usr/bin/env node`, which needs Termux's exec helper, as in a terminal. */
private val npmEnvironment = mapOf("LD_PRELOAD" to "${TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH}/libtermux-exec.so")

private fun json(file: File): JSONObject? = LoginText.read(file)?.let { runCatching { JSONObject(it) }.getOrNull() }

/** The last line that says something, without the CLI's "> " prompt mark. */
private fun lastLine(lines: List<String>, skip: (String) -> Boolean = { false }): String? =
    lines.map { it.trim().removePrefix(">").trim() }.lastOrNull { it.isNotEmpty() && !skip(it) }

/** `vercel login`: a device code; the CLI polls until it is allowed. The token is in `auth.json`. */
object VercelLogin : LoginSpec {
    override val key = "vercel"
    override val executable = "bin/vercel"
    override fun loginArguments(home: File) = listOf("login")
    override val logoutArguments = listOf("logout")
    override val url = Regex("https://vercel\\.com/oauth/device\\?user_code=\\S+")
    override fun environment(run: File) = npmEnvironment + ("VERCEL_TELEMETRY_DISABLED" to "1")
    const val AUTH_RELATIVE = ".local/share/com.vercel.cli/auth.json"
    override fun signedIn(home: File) = json(File(home, AUTH_RELATIVE))?.optString("token").isNullOrBlank().not()
    override val whoamiArguments = listOf("whoami")
    override fun whoami(lines: List<String>) = lastLine(lines) { it.startsWith("Vercel CLI") || it.contains(' ') }
}

/** `netlify login`: a ticket the CLI polls. Its config names the user and holds their token. */
object NetlifyLogin : LoginSpec {
    override val key = "netlify"
    override val executable = "bin/netlify"
    override fun loginArguments(home: File) = listOf("login")
    override val logoutArguments = listOf("logout")
    override val url = Regex("https://app\\.netlify\\.com/authorize\\?\\S+")
    override fun environment(run: File) = npmEnvironment + ("NETLIFY_TELEMETRY_DISABLED" to "1")
    const val CONFIG_RELATIVE = ".config/netlify/config.json"

    /** `netlify status` exits 0 either way, so the config says: its `userId` and that user's token. */
    private fun user(home: File): JSONObject? {
        val config = json(File(home, CONFIG_RELATIVE)) ?: return null
        val id = config.optString("userId").takeIf { it.isNotBlank() } ?: return null
        return config.optJSONObject("users")?.optJSONObject(id)?.takeIf { it.optJSONObject("auth")?.optString("token").isNullOrBlank().not() }
    }

    override fun signedIn(home: File) = user(home) != null
    override fun account(home: File) = user(home)?.optString("email")?.takeIf { it.isNotBlank() }
}

/**
 * `firebase login --non-interactive` prints the address and ends, keeping the login's state in its config; the code the
 * browser shows then goes to `firebase login <code>`.
 */
object FirebaseLogin : LoginSpec {
    override val key = "firebase"
    override val executable = "bin/firebase"
    override fun loginArguments(home: File) = listOf("login", "--non-interactive")
    override val logoutArguments = listOf("logout", "--non-interactive")
    override val url = Regex("https://auth\\.firebase\\.tools/login\\?\\S+")
    override val code = CodeEntry.Command { listOf("login", it, "--non-interactive") }
    override fun environment(run: File) = npmEnvironment
    const val CONFIG_RELATIVE = ".config/configstore/firebase-tools.json"

    override fun signedIn(home: File): Boolean {
        val config = json(File(home, CONFIG_RELATIVE)) ?: return false
        return config.optJSONObject("tokens")?.optString("refresh_token").isNullOrBlank().not() || config.optJSONObject("user") != null
    }

    override fun account(home: File) = json(File(home, CONFIG_RELATIVE))?.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() }
}

/**
 * The AWS CLI's console login, `aws login --remote`: it prints the address, then reads the code from stdin. Without a
 * region in `~/.aws/config` it would ask for one first, so the app passes [DEFAULT_REGION].
 */
object AwsLogin : LoginSpec {
    const val DEFAULT_REGION = "us-east-1"
    const val CONFIG_RELATIVE = ".aws/config"
    const val CACHE_RELATIVE = ".aws/login/cache"
    private val REGION = Regex("""^\s*region\s*=\s*\S+""")

    override val key = "aws"
    override val executable = "bin/aws"
    override fun loginArguments(home: File): List<String> {
        val hasRegion = LoginText.read(File(home, CONFIG_RELATIVE))?.lineSequence()?.any { REGION.containsMatchIn(it) } == true
        return listOf("login", "--remote") + if (hasRegion) emptyList() else listOf("--region", DEFAULT_REGION)
    }
    override val logoutArguments = listOf("logout", "--all")
    override val url = Regex("https://[\\w.-]*signin[\\w.-]*\\.(?:aws\\.amazon|amazonaws)\\.com/\\S*authorize\\?\\S+")
    override val code = CodeEntry.Stdin
    // Python buffers a pipe; the address has to show before the CLI waits for the code.
    override fun environment(run: File) = mapOf("PYTHONUNBUFFERED" to "1", "AWS_PAGER" to "")

    /** A console login keeps its tokens in this folder; `aws logout --all` empties it. */
    override fun signedIn(home: File) = File(home, CACHE_RELATIVE).listFiles()?.any { it.isFile && it.name.endsWith(".json") } == true
    override val whoamiArguments = listOf("sts", "get-caller-identity", "--query", "Arn", "--output", "text")
    override fun whoami(lines: List<String>) = lines.map { it.trim() }.lastOrNull { it.startsWith("arn:") }
}

/** `flyctl auth login`: an address it polls for 15 minutes. While it waits it keeps `~/.fly/pending-login.json`. */
object FlyLogin : LoginSpec {
    const val CONFIG_RELATIVE = ".fly/config.yml"
    const val PENDING_RELATIVE = ".fly/pending-login.json"
    /** A token that isn't empty (`access_token: ""` once logged out). */
    private val TOKEN = Regex("""^\s*access_token:\s*["']?[^"'\s]+""")

    override val key = "flyctl"
    override val executable = "bin/flyctl"
    override fun loginArguments(home: File) = listOf("auth", "login")
    override val logoutArguments = listOf("auth", "logout")
    override val url = Regex("https://fly\\.io/app/auth/cli/\\w+")
    override fun signedIn(home: File) = LoginText.read(File(home, CONFIG_RELATIVE))?.lineSequence()?.any { TOKEN.containsMatchIn(it) } == true
    override val whoamiArguments = listOf("auth", "whoami")
    override fun whoami(lines: List<String>) = lastLine(lines)?.takeIf { '@' in it && ' ' !in it }
    override fun cancelled(home: File) {
        File(home, PENDING_RELATIVE).delete()
    }
}
