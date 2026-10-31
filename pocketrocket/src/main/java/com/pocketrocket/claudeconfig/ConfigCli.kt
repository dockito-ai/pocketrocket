package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.objects
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

/**
 * What one `claude` command printed, stdout and stderr apart: `--json` results are on stdout, and human text can come
 * either side of them. Some of it can hold secrets, so it isn't printable; turn it into text with [CliText].
 */
class CliOutput(val exit: Int, val stdout: List<String>, val stderr: List<String>, val timedOut: Boolean = false) {
    val ok: Boolean get() = exit == 0 && !timedOut

    override fun toString() = "CliOutput(exit=$exit, timedOut=$timedOut)"
}

/** Runs `claude <arguments>` for one account. Blocking, so off the main thread. Never throws; a failure is a [CliOutput]. */
interface ConfigCli {
    fun run(arguments: List<String>, stdin: String?, timeoutMs: Long): CliOutput

    companion object {
        /** Commands that only read or write local files. */
        const val QUICK_MS = 90_000L

        /** Commands that may clone or download. */
        const val NETWORK_MS = 5 * 60_000L
    }
}

/** One change at a time per account: `.claude.json` and `settings.json` are rewritten whole. Accounts don't wait for each other. */
object AccountLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun of(accountId: String): Any = locks.getOrPut(accountId) { Any() }
}

/** Finds a JSON result in lines that may have human text around it. Pure. */
object CliJson {
    fun objectIn(lines: List<String>): JSONObject? = scan(lines, '{') { it as? JSONObject }

    /** An array holding objects, or none. `[claude] not installed` also parses as an array, of strings, so that doesn't count. */
    fun arrayIn(lines: List<String>): JSONArray? =
        scan(lines, '[') { value -> (value as? JSONArray)?.takeIf { it.length() == 0 || it.objects().isNotEmpty() } }

    private fun <T> scan(lines: List<String>, opener: Char, accept: (Any?) -> T?): T? {
        lines.forEachIndexed { index, line ->
            if (line.trimStart().startsWith(opener)) {
                val value = runCatching { JSONTokener(lines.subList(index, lines.size).joinToString("\n")).nextValue() }.getOrNull()
                accept(value)?.let { return it }
            }
        }
        return null
    }
}

/** Output fit to show: everything goes through [McpParser.scrub], with what the command is known to have been given. Pure. */
object CliText {
    private const val MAX = 1_200
    private const val MIN_VALUE = 3
    private const val HIDDEN = "[hidden]"
    private val URL_HOST = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^/@?#]*@)?([^/?#]*).*$""")

    /** The scheme and host only: a URL's path and query can hold a token, and many hosted MCP URLs do. */
    fun urlWithoutSecrets(url: String): String = URL_HOST.matchEntire(url.trim())?.let { "${it.groupValues[1]}${it.groupValues[2]}" } ?: HIDDEN

    /** [configs] are MCP server configs, [values] anything else the command was given. */
    fun clean(text: String?, configs: List<JSONObject> = emptyList(), values: Collection<String> = emptyList()): String? {
        if (text.isNullOrBlank()) return null
        var out: String = text
        configs.forEach { config ->
            config.textOrNull("url")?.let { out = out.replace(it, urlWithoutSecrets(it) + "/" + HIDDEN) }
        }
        values.filter { it.length >= MIN_VALUE }.sortedByDescending { it.length }.forEach { out = out.replace(it, HIDDEN) }
        configs.forEach { config -> out = McpParser.scrub(out, config, MAX) ?: return null }
        return McpParser.scrub(out, null, MAX)
    }

    /** Why a command failed, in its own words, or [fallback] when it said nothing. */
    fun failure(output: CliOutput, fallback: String, configs: List<JSONObject> = emptyList(), values: Collection<String> = emptyList()): String {
        if (output.timedOut) return "Claude Code took too long and was stopped."
        val said = output.stderr.joinToString("\n").ifBlank { output.stdout.joinToString("\n") }
        return clean(said, configs, values) ?: fallback
    }
}

internal fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else (opt(key) as? String)?.takeIf(String::isNotBlank)
