package com.pocketrocket.health

import android.content.Context
import android.os.StatFs
import com.pocketrocket.auth.ClaudeCli
import com.pocketrocket.github.GhCli
import com.pocketrocket.health.HealthState.NeedsAttention
import com.pocketrocket.health.HealthState.Ok
import com.pocketrocket.profiles.AccountPaths
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** SETUP: Claude Code starts, the tools run, free space, the two hosts the app downloads from. */
internal object SetupChecks {

    /** The setup script's base tools, by the command each one installs and how to ask its version. */
    private val TOOLS = listOf(
        "git" to "--version", "gh" to "--version", "ssh" to "-V", "node" to "--version", "npm" to "--version",
        "rg" to "--version", "jq" to "--version", "curl" to "--version", "zellij" to "--version",
    )
    private val HOSTS = listOf("downloads.claude.ai", "github.com")
    private const val LOW_SPACE = 2L * 1024 * 1024 * 1024

    suspend fun run(context: Context): HealthGroup = coroutineScope {
        val claude = async(Dispatchers.IO) { claude(context) }
        val tools = async(Dispatchers.IO) { tools(context) }
        val network = async(Dispatchers.IO) { network() }
        HealthGroup("SETUP", listOf(claude.await(), tools.await(), storage(), network.await()))
    }

    private fun claude(context: Context): HealthItem {
        val (exit, lines) = runCatching { ClaudeCli.run(context, arrayOf("--version"), null) }.getOrElse { -1 to listOf(it.message.orEmpty()) }
        val version = lines.firstOrNull()?.substringBefore(' ')?.takeIf { exit == 0 && it.firstOrNull()?.isDigit() == true }
        return if (version != null) HealthItem("Claude Code", Ok, "$version · starts correctly")
        else HealthItem(
            "Claude Code", NeedsAttention, lines.lastOrNull()?.take(80)?.let { "Doesn’t start: $it" } ?: "Doesn’t start",
            HealthFix.Navigate("claude-code"),
        )
    }

    /**
     * Each tool is actually run (`git --version` …), not just looked for, so a binary Android refuses to
     * execute (an SELinux denial, a broken link, a missing library) shows up here as a failed check.
     */
    private suspend fun tools(context: Context): HealthItem = coroutineScope {
        val results = TOOLS.map { (name, flag) ->
            async(Dispatchers.IO) { name to runTool(context, name, flag) }
        }.awaitAll()
        val extra = userPackages().filterNot { installed(it) }
        val broken = results.filter { it.second != null }
        when {
            broken.isEmpty() && extra.isEmpty() -> HealthItem("Tools", Ok, "${TOOLS.joinToString(", ") { it.first }} · all run")
            else -> {
                val parts = broken.map { (name, why) -> "$name ($why)" } + extra.map { "$it (not installed)" }
                HealthItem("Tools", NeedsAttention, parts.joinToString(", "), HealthFix.Navigate("tools"))
            }
        }
    }

    /** Null when the tool ran, else a short reason. */
    private fun runTool(context: Context, name: String, flag: String): String? {
        val bin = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, name)
        if (!bin.exists()) return "missing"
        val (exit, lines) = runCatching { GhCli.runTool(context, bin.path, arrayOf(flag)) }.getOrElse { e ->
            return if ("denied" in e.message.orEmpty().lowercase()) "permission denied" else e.message?.takeLast(40) ?: "won’t run"
        }
        val text = lines.joinToString(" ")
        return when {
            "denied" in text.lowercase() -> "permission denied"
            exit == 0 -> null
            // ssh -V prints its version on stderr and exits 0; anything else non-zero is a failure
            else -> "exit $exit"
        }
    }

    /** Packages Shankar listed in ~/.pocketrocket/tools.txt. */
    private fun userPackages(): List<String> = runCatching {
        File(AccountPaths.home, ".pocketrocket/tools.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun installed(pkg: String) = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/info/$pkg.list").exists()

    private fun storage(): HealthItem {
        val free = runCatching { StatFs(TermuxConstants.TERMUX_HOME_DIR_PATH).availableBytes }.getOrDefault(-1L)
        if (free < 0) return HealthItem("Storage", HealthState.CantCheck, "Couldn’t read free space")
        val text = "${formatBytes(free)} free"
        return if (free >= LOW_SPACE) HealthItem("Storage", Ok, text)
        else HealthItem("Storage", NeedsAttention, "Only $text. Updates need about 1 GB", HealthFix.Open(SystemScreen.Storage))
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 10) "${gb.toLong()} GB" else if (gb >= 1) "%.1f GB".format(gb) else "${bytes / 1_000_000} MB"
    }

    private fun network(): HealthItem {
        val down = HOSTS.filterNot(::reachable)
        return when {
            down.isEmpty() -> HealthItem("Network", Ok, "${HOSTS.joinToString(" and ")} reachable")
            down.size == HOSTS.size -> HealthItem("Network", NeedsAttention, "Offline: can’t reach ${HOSTS.joinToString(" or ")}")
            else -> HealthItem("Network", NeedsAttention, "Can’t reach ${down.joinToString(" or ")}")
        }
    }

    /** Any HTTP answer counts: the host resolved and spoke TLS. */
    private fun reachable(host: String): Boolean = runCatching {
        val connection = URL("https://$host/").openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = false
        try { connection.responseCode > 0 } finally { connection.disconnect() }
    }.getOrDefault(false)
}
