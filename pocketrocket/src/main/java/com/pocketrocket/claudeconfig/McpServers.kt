package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.objects
import org.json.JSONArray
import org.json.JSONObject

/** A server's `status` in `mcp_status`. [Unknown] is anything newer than this app. */
enum class McpState(val cli: String) {
    Connected("connected"),
    NeedsAuth("needs-auth"),
    Failed("failed"),
    Disabled("disabled"),
    Pending("pending"),
    Unknown(""),
    ;

    companion object {
        fun of(cli: String): McpState = entries.firstOrNull { it.cli == cli } ?: Unknown
    }
}

/**
 * One MCP server as a session sees it. Only the fields below are ever copied out of a reply: `config` (header values
 * and arguments in clear), tool descriptions and everything else stay behind, so this is safe to keep, show and cache.
 * [name] is what control requests take; [error] has been through [McpParser]'s scrub.
 */
data class McpServer(
    val name: String,
    val state: McpState,
    val scope: String? = null,
    val transport: String? = null,
    val urlHost: String? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val toolCount: Int = 0,
    val serverVersion: String? = null,
) {
    /** A claude.ai connector: it comes from the login, and signs in on claude.ai. */
    val isConnector: Boolean get() = scope == "claudeai" || transport == "claudeai-proxy"

    /** Named `plugin:<plugin>:<server>`. */
    val isPlugin: Boolean get() = name.startsWith(PLUGIN_PREFIX)

    val pluginName: String? get() = if (isPlugin) name.removePrefix(PLUGIN_PREFIX).substringBefore(':') else null

    /** "Google Drive" for `claude.ai Google Drive`, "slack" for `plugin:design:slack`. */
    val displayName: String
        get() = when {
            isPlugin -> name.removePrefix(PLUGIN_PREFIX).substringAfter(':')
            else -> name.removePrefix(CONNECTOR_PREFIX)
        }

    /** Where it comes from, in the app's words. */
    val sourceLabel: String
        get() = when {
            isConnector -> "Connector"
            isPlugin -> "Plugin $pluginName"
            scope == "user" -> "All folders"
            scope == "local" -> "This folder"
            scope == "project" -> "This folder's .mcp.json"
            scope == "dynamic" -> "This session"
            else -> scope ?: "MCP server"
        }

    private companion object {
        const val PLUGIN_PREFIX = "plugin:"
        const val CONNECTOR_PREFIX = "claude.ai "
    }
}

/** What `mcp_authenticate` answered. [url] holds the flow's `state` for local servers: open it, never log or store it. */
data class McpAuthStart(val url: String?, val requiresUserAction: Boolean, val callbackExpected: Boolean) {
    override fun toString() = "McpAuthStart(requiresUserAction=$requiresUserAction, callbackExpected=$callbackExpected)"
}

/** Turns control replies into plain values. Pure; works from an allowlist, so unknown fields can't leak through. */
object McpParser {

    /** The servers in an `mcp_status` reply, or null when it has no such list. */
    fun status(reply: JSONObject): List<McpServer>? =
        reply.optJSONArray("mcpServers")?.objects()?.mapNotNull(::server)

    private fun server(o: JSONObject): McpServer? {
        val name = o.text("name") ?: return null
        val tools = o.optJSONArray("tools")
        return McpServer(
            name = name,
            state = McpState.of(o.optString("status")),
            scope = o.text("scope"),
            transport = o.text("transport"),
            urlHost = o.text("urlHost"),
            error = scrub(o.text("error"), o.optJSONObject("config")),
            errorCode = o.text("error_code"),
            toolCount = tools?.length() ?: 0,
            serverVersion = o.optJSONObject("serverInfo")?.text("version"),
        )
    }

    /** `url` is null unless it can be opened in a browser. */
    fun authStart(reply: JSONObject) = McpAuthStart(
        url = reply.text("authUrl")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        requiresUserAction = reply.optBoolean("requiresUserAction"),
        callbackExpected = reply.optBoolean("callbackExpected"),
    )

    /**
     * An error message fit to show: no URL query strings, nothing from the server's [config], not too long. Claude
     * Code redacts its own messages (`?token=REDACTED`, 2.1.314), which isn't a promise that holds for every version
     * or every server's own error text.
     */
    fun scrub(error: String?, config: JSONObject? = null, max: Int = MAX_ERROR): String? {
        if (error.isNullOrBlank()) return null
        var text = URL_USER.replace(URL_QUERY.replace(error, "$1"), "$1$HIDDEN@")
        secretsIn(config).sortedByDescending { it.length }.forEach { text = text.replace(it, HIDDEN) }
        return text.trim().take(max)
    }

    /** Values under `config` that could be secret, whole and word by word ("Bearer abc"). */
    private fun secretsIn(config: JSONObject?): Set<String> {
        if (config == null) return emptySet()
        val found = mutableSetOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is String -> {
                    if (value.length >= MIN_SECRET) found += value
                    value.split(' ').filterTo(found) { it.length >= MIN_SECRET_WORD }
                }
                is JSONObject -> value.keys().forEach { walk(value.opt(it)) }
                is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
            }
        }
        config.keys().forEach { key -> if (key !in PLAIN_CONFIG) walk(config.opt(key)) }
        return found
    }

    /** Config fields that aren't secret and that error messages rightly name. A URL's query goes through [URL_QUERY]. */
    private val PLAIN_CONFIG = setOf("type", "command", "url", "id")
    private val URL_QUERY = Regex("""(https?://[^\s?'")]+)\?[^\s'")]*""")
    private val URL_USER = Regex("""(https?://)[^/\s@'")]+@""")
    private const val HIDDEN = "[hidden]"
    private const val MIN_SECRET = 4
    private const val MIN_SECRET_WORD = 8
    private const val MAX_ERROR = 300

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}
