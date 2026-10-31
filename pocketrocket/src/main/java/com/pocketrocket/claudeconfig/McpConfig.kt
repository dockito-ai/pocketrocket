package com.pocketrocket.claudeconfig

import com.pocketrocket.browser.ChromiumMcp
import org.json.JSONArray
import org.json.JSONObject

/*
 * Local MCP servers (user scope) as Claude Code's `.claude.json` holds them, and the arguments that change them. Pure:
 * no Android, no process. `mcp list` and `mcp get` have no JSON and `get` prints values in clear, so the file is the
 * only way to read an entry. Values never leave this file's functions except as the config handed to `add-json`.
 */

/** The transports the form offers. Others (`ws`) can be listed and removed, not edited. */
enum class McpTransport(val cli: String) {
    Stdio("stdio"),
    Http("http"),
    Sse("sse"),
    ;

    companion object {
        /** No `type` is an older stdio entry (measured: `add-json` accepts `{"command":"node"}` and stores no type). */
        fun of(type: String?): McpTransport? = if (type.isNullOrEmpty()) Stdio else entries.firstOrNull { it.cli == type }
    }
}

/**
 * One configured server without its values. [urlHost] instead of the URL, and only a count of arguments, because a
 * hosted URL's path or query and a command's arguments often hold a token. Safe to keep and show.
 */
data class LocalMcpServer(
    val name: String,
    val type: String,
    val command: String? = null,
    val urlHost: String? = null,
    val argCount: Int = 0,
    val envKeys: List<String> = emptyList(),
    val headerKeys: List<String> = emptyList(),
) {
    /** Tools installs and removes it (decision 37). */
    val managedByTools: Boolean get() = name == ChromiumMcp.SERVER

    val transport: McpTransport? get() = McpTransport.of(type)

    val canEdit: Boolean get() = !managedByTools && transport != null

    val canRemove: Boolean get() = !managedByTools
}

/** Reads `.claude.json`. Pure. */
object McpConfigFile {
    const val NAME = ".claude.json"

    /** The user-scope servers, or null when [text] isn't readable as a config. */
    fun servers(text: String): List<LocalMcpServer>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val servers = root.optJSONObject("mcpServers") ?: return emptyList()
        return servers.keys().asSequence().toList().sorted().mapNotNull { name ->
            servers.optJSONObject(name)?.let { summary(name, it) }
        }
    }

    /** The whole entry with its values, for `add-json` and nothing else. Don't keep it or print it. */
    fun entry(text: String, name: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()?.optJSONObject("mcpServers")?.optJSONObject(name)

    private fun summary(name: String, o: JSONObject) = LocalMcpServer(
        name = name,
        type = o.textOrNull("type") ?: McpTransport.Stdio.cli,
        command = o.textOrNull("command"),
        urlHost = o.textOrNull("url")?.let { CliText.urlWithoutSecrets(it).substringAfter("://", "") }?.takeIf { it.isNotEmpty() },
        argCount = o.optJSONArray("args")?.length() ?: 0,
        envKeys = o.optJSONObject("env")?.keys()?.asSequence()?.toList()?.sorted().orEmpty(),
        headerKeys = o.optJSONObject("headers")?.keys()?.asSequence()?.toList()?.sorted().orEmpty(),
    )
}

/** Ways to start the form. Common transports, with what is known to work on this phone filled in. */
enum class McpPreset(val label: String, val note: String, val transport: McpTransport, val command: String, val args: String) {
    Npx("npm package", "Runs through npx. Needs the network the first time.", McpTransport.Stdio, "npx", "-y\n"),
    Node("Node script", "A script already on this phone. The way that is known to work here.", McpTransport.Stdio, "node", ""),
    Command("Other command", "Any program on this phone.", McpTransport.Stdio, "", ""),
    Http("HTTP", "A server on the internet, at a URL.", McpTransport.Http, "", ""),
    Sse("SSE", "An older kind of server at a URL.", McpTransport.Sse, "", ""),
}

/** A value in the form: what is saved stays saved, and the form never has it, or a new one typed on the phone. */
sealed interface Kept<out T> {
    data object Saved : Kept<Nothing>

    class New<T>(val value: T) : Kept<T> {
        override fun toString() = "New(hidden)"
    }
}

/** An environment variable or header. */
class McpValue(val key: String, val value: Kept<String>) {
    override fun toString() = "McpValue($key)"
}

/** What the form produced. Holds typed secrets, so it prints as little as possible. */
class McpDraft(
    val name: String,
    val transport: McpTransport,
    val command: String = "",
    val args: Kept<List<String>> = Kept.New(emptyList()),
    val url: Kept<String> = Kept.New(""),
    val env: List<McpValue> = emptyList(),
    val headers: List<McpValue> = emptyList(),
) {
    override fun toString() = "McpDraft($name, ${transport.cli})"
}

/** The arguments. `add-json` rather than `add`: one path for every transport, no `-e` swallowing what follows, and it keeps an entry's `oauth`. */
object McpCommands {
    /** Always `-s user`: the default scope is one folder, and every account's servers here are account-wide. */
    fun addJson(name: String, config: JSONObject): List<String> = listOf("mcp", "add-json", "-s", "user", name, config.toString())

    fun remove(name: String): List<String> = listOf("mcp", "remove", name, "-s", "user")
}

sealed interface McpBuild {
    class Ready(val config: JSONObject) : McpBuild {
        override fun toString() = "Ready"
    }

    data class Problem(val text: String) : McpBuild
}

/** From the form to a config. Pure. */
object McpDrafts {
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val HEADER_KEY = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    /** What is wrong with [draft] in plain words, or null. Says nothing about the values themselves. */
    fun problem(draft: McpDraft): String? {
        if (!NAME.matches(draft.name)) return "A name can only have letters, numbers, hyphens and underscores, and starts with a letter or number."
        if (draft.name == ChromiumMcp.SERVER) return "${ChromiumMcp.SERVER} is managed in Settings, Tools."
        when (draft.transport) {
            McpTransport.Stdio -> {
                if (draft.command.isBlank()) return "Enter the command to run."
                if (draft.command.startsWith("-")) return "The command can't start with a dash."
            }
            McpTransport.Http, McpTransport.Sse -> {
                val url = (draft.url as? Kept.New)?.value
                if (url != null && !(url.startsWith("https://") || url.startsWith("http://"))) return "The URL has to start with https:// or http://."
                if (url != null && url.any(Char::isWhitespace)) return "The URL can't have spaces."
            }
        }
        draft.env.firstOrNull { !ENV_KEY.matches(it.key) }?.let { return "\"${it.key}\" can't be a variable name: letters, numbers and underscores only." }
        draft.headers.firstOrNull { !HEADER_KEY.matches(it.key) }?.let { return "\"${it.key}\" can't be a header name." }
        duplicate(draft.env)?.let { return "$it is there twice." }
        duplicate(draft.headers)?.let { return "$it is there twice." }
        (draft.env + draft.headers).firstOrNull { (it.value as? Kept.New)?.value.isNullOrEmpty() && it.value is Kept.New }?.let { return "Enter a value for ${it.key}." }
        draft.headers.firstOrNull { v -> (v.value as? Kept.New)?.value?.any { it == '\n' || it == '\r' } == true }?.let { return "The value of ${it.key} has to be one line." }
        return null
    }

    private fun duplicate(values: List<McpValue>): String? = values.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.key

    /**
     * The config for [draft]. On an edit, [saved] is the entry from the file: [Kept.Saved] takes its value from there,
     * a key the form dropped is gone, and fields the form doesn't know (`oauth`) stay. [execHelper]: see [StdioAdvice].
     */
    fun build(draft: McpDraft, saved: JSONObject?, execHelper: String): McpBuild {
        problem(draft)?.let { return McpBuild.Problem(it) }
        val config = if (saved != null) JSONObject(saved.toString()) else JSONObject()
        config.put("type", draft.transport.cli)
        when (draft.transport) {
            McpTransport.Stdio -> {
                config.put("command", draft.command.trim())
                when (val args = draft.args) {
                    is Kept.New -> config.put("args", JSONArray(args.value))
                    Kept.Saved -> if (saved?.optJSONArray("args") == null) config.put("args", JSONArray())
                }
                val env = values(draft.env, saved?.optJSONObject("env")) ?: return lostValue()
                saved?.optJSONObject("env")?.textOrNull(StdioAdvice.LD_PRELOAD)?.let { env.putIfAbsent(StdioAdvice.LD_PRELOAD, it) }
                StdioAdvice.applyExecHelper(env, draft.command, execHelper)
                if (env.isEmpty()) config.remove("env") else config.put("env", JSONObject(env as Map<*, *>))
            }
            McpTransport.Http, McpTransport.Sse -> {
                when (val url = draft.url) {
                    is Kept.New -> config.put("url", url.value)
                    Kept.Saved -> if (saved?.textOrNull("url") == null) return lostValue()
                }
                val headers = values(draft.headers, saved?.optJSONObject("headers")) ?: return lostValue()
                if (headers.isEmpty()) config.remove("headers") else config.put("headers", JSONObject(headers as Map<*, *>))
            }
        }
        return McpBuild.Ready(config)
    }

    private fun lostValue() = McpBuild.Problem("A saved value isn't in the config any more. Replace it and type it again.")

    /** Null when a value that should be saved isn't. */
    private fun values(values: List<McpValue>, saved: JSONObject?): MutableMap<String, String>? {
        val out = linkedMapOf<String, String>()
        values.forEach { v ->
            out[v.key] = when (val value = v.value) {
                is Kept.New -> value.value
                Kept.Saved -> saved?.textOrNull(v.key) ?: return null
            }
        }
        return out
    }
}

/**
 * What the confirmation shows. [what] is the exact command or URL when it was typed now; anything already saved stays
 * hidden and is named as such. Values of variables and headers never appear.
 */
data class McpReview(val what: String, val notes: List<String>)

object McpPreview {
    fun of(draft: McpDraft, execHelper: String): McpReview {
        val notes = mutableListOf<String>()
        val what = when (draft.transport) {
            McpTransport.Stdio -> {
                val command = shellWord(draft.command.trim())
                when (val args = draft.args) {
                    is Kept.New -> (listOf(command) + args.value.map(::shellWord)).joinToString(" ")
                    Kept.Saved -> "$command  + its saved arguments (hidden)"
                }
            }
            McpTransport.Http, McpTransport.Sse -> when (val url = draft.url) {
                is Kept.New -> url.value
                Kept.Saved -> "Its saved URL (hidden)"
            }
        }
        if (draft.env.isNotEmpty()) notes += "Variables: " + draft.env.joinToString(", ") { it.key } + " (values hidden)"
        if (draft.headers.isNotEmpty()) notes += "Headers: " + draft.headers.joinToString(", ") { it.key } + " (values hidden)"
        if (draft.transport == McpTransport.Stdio && StdioAdvice.needsExecHelper(draft.command) && draft.env.none { it.key == StdioAdvice.LD_PRELOAD }) {
            notes += "Also sets ${StdioAdvice.LD_PRELOAD}=$execHelper, which ${StdioAdvice.commandName(draft.command)} needs to start on this phone."
        }
        return McpReview(what, notes)
    }

    private fun shellWord(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "-_./:=@%+,~" }) word else "'" + word.replace("'", "'\\''") + "'"
}

/** How stdio servers start on this phone, as measured in `docs/chat-sessions.md` ("Starting stdio servers on the phone"). Pure. */
object StdioAdvice {
    const val LD_PRELOAD = "LD_PRELOAD"

    /**
     * Commands that start other programs by a `#!/usr/bin/env` line or through a shell. The launcher unsets LD_PRELOAD
     * and Android has no /usr/bin/env, so these show `Connection closed` without libtermux-exec. Measured for `npx` and
     * `sh`; the rest work the same way but weren't measured.
     */
    private val NEEDS_HELPER = setOf("npx", "npm", "pnpm", "pnpx", "yarn", "bunx", "sh", "bash", "dash", "zsh", "uvx", "pipx")
    private val SCRIPT = Regex("""\.(js|mjs|cjs)$""")

    fun commandName(command: String): String = command.trim().substringAfterLast('/')

    fun needsExecHelper(command: String): Boolean = commandName(command) in NEEDS_HELPER

    /** Adds the helper when the command needs it, and takes it away when it no longer does. A value of the user's own stays. */
    fun applyExecHelper(env: MutableMap<String, String>, command: String, execHelper: String) {
        when {
            needsExecHelper(command) -> env.putIfAbsent(LD_PRELOAD, execHelper)
            env[LD_PRELOAD] == execHelper -> env.remove(LD_PRELOAD)
        }
    }

    /** The file whose first line says how [command] starts: a path, or a bare name in [binDir]. */
    fun scriptPath(command: String, binDir: String): String =
        command.trim().let { if (it.contains('/')) it else "$binDir/$it" }

    /** What to warn about before adding. [firstLine] is the start of [scriptPath], when it could be read. */
    fun warnings(command: String, firstLine: String?): List<String> = buildList {
        val name = commandName(command)
        if (firstLine != null && firstLine.startsWith("#!") && firstLine.contains("/usr/bin/env")) {
            add("$name starts with #!/usr/bin/env, which doesn't exist on Android, so it will fail with ENOENT. Run it with its interpreter instead, for example: node ${command.trim()}")
        } else if (SCRIPT.containsMatchIn(name)) {
            add("A script run directly often fails here with ENOENT. Use node as the command and the script as its argument.")
        }
        if (name == "npx") add("The first start downloads the package and may not finish before Claude Code gives up on it. Check again once it has.")
    }

    /** What an error from `mcp_status` means here, or null when there is nothing to add. */
    fun explain(error: String?, hasExecHelper: Boolean, execHelper: String): String? = when {
        error == null -> null
        error.contains("ENOENT") ->
            "Either the file doesn't exist, or it is a script starting with #!/usr/bin/env, which Android doesn't have: the message is the same. Run it with its interpreter, for example node script.js."
        error.contains("Connection closed") && !hasExecHelper ->
            "The server stopped as it started. Commands that start through npx or a shell need the variable $LD_PRELOAD set to $execHelper. Edit the server and add it."
        error.contains("Connection closed") ->
            "The server stopped as it started. It may have printed why in a terminal: try the same command there."
        error.contains("timed out", ignoreCase = true) ->
            "It didn't answer in time. A first start may be downloading. Check again in a minute."
        else -> null
    }
}
