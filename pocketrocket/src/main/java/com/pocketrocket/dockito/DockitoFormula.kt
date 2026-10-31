package com.pocketrocket.dockito

import com.pocketrocket.updater.ClaudeVersions
import java.net.HttpURLConnection
import java.net.URL

/** One Dockito CLI release for this phone: the linux-arm64 tarball and the SHA-256 the formula pins. */
data class DockitoRelease(val version: String, val url: String, val sha256: String)

/**
 * Dockito's Homebrew formula (dockito-ai/homebrew-tap, Formula/dockito.rb) is where the CLI's releases are
 * published. It names the version and, under `on_linux do / on_arm do`, the linux-arm64 tarball's URL and
 * SHA-256. The setup script downloads that URL and refuses it unless it matches that SHA-256.
 */
object DockitoFormula {
    private const val FORMULA = "https://raw.githubusercontent.com/dockito-ai/homebrew-tap/HEAD/Formula/dockito.rb"
    const val RELEASES = "https://github.com/dockito-ai/homebrew-tap/releases/download"

    private val VERSION = Regex("""^\s*version\s+"([^"]+)"""", RegexOption.MULTILINE)
    private val URL_LINE = Regex("""url\s+"([^"]+)"""")
    private val SHA_LINE = Regex("""sha256\s+"([^"]+)"""")
    private val SHA = Regex("^[a-f0-9]{64}$")

    /** The asset this phone runs for [version]; the only URL accepted. */
    fun assetUrl(version: String) = "$RELEASES/v$version/dockito-linux-arm64.tar.gz"

    /**
     * The linux-arm64 release in [formula], or null when the formula has no such entry, its version is
     * older than [DockitoCli.MIN_VERSION], or its URL or checksum is wrong (a URL off the tap or for another version is refused).
     */
    fun parse(formula: String): DockitoRelease? {
        val version = VERSION.find(formula)?.groupValues?.get(1)?.takeIf { ClaudeVersions.isVersion(it) && DockitoCli.supports(it) } ?: return null
        val linux = block(formula, "on_linux") ?: return null
        val arm = block(linux, "on_arm") ?: return null
        val url = URL_LINE.find(arm)?.groupValues?.get(1) ?: return null
        val sha = SHA_LINE.find(arm)?.groupValues?.get(1)?.lowercase() ?: return null
        if (url != assetUrl(version) || !SHA.matches(sha)) return null
        return DockitoRelease(version, url, sha)
    }

    /** The newest release, or null when the formula cannot be read (offline) or parsed. Blocking. */
    fun fetch(): DockitoRelease? = runCatching {
        val connection = URL(FORMULA).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) return null
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** The text between `<name> do` and its matching `end`, counting nested `do … end` blocks. */
    private fun block(text: String, name: String): String? {
        val start = Regex("""\b$name\s+do\b""").find(text) ?: return null
        val body = text.substring(start.range.last + 1)
        var depth = 1
        val out = StringBuilder()
        for (line in body.lines()) {
            val t = line.trim()
            if (Regex("""\bdo\s*(\|[^|]*\|)?\s*$""").containsMatchIn(t)) depth++
            if (t == "end") {
                depth--
                if (depth == 0) return out.toString()
            }
            out.appendLine(line)
        }
        return null
    }
}
