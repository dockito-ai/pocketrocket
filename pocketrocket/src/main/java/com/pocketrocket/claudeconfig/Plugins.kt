package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.objects
import org.json.JSONObject

/*
 * Claude Code's plugins through `claude plugin ... --json`. Pure: no Android, no process. Every parser copies an
 * allowlist out of a reply (`plugin list` puts a plugin's MCP config in `mcpServers`, with header values in clear), and
 * anything unknown is ignored rather than fatal. Measured on 2.1.314 with a local marketplace; `synced` wasn't.
 */

/** An installed plugin. Safe to keep and show. */
data class PluginInfo(
    val id: String,
    val version: String? = null,
    val scope: String? = null,
    /** Null when the reply didn't say. */
    val enabled: Boolean? = null,
    val hasOptions: Boolean = false,
    /** Names only. */
    val mcpServers: List<String> = emptyList(),
) {
    val name: String get() = id.substringBefore('@')
    val marketplace: String? get() = id.substringAfter('@', "").takeIf { it.isNotEmpty() }

    /** Why the app won't change it, or null when it can. */
    val readOnlyReason: String? get() = PluginRules.readOnlyReason(scope)
    val readOnly: Boolean get() = readOnlyReason != null
}

object PluginRules {
    const val USER = "user"
    const val SYNCED = "synced"

    /**
     * Only `user` is changed from here. `synced` comes from the organization and was never measured, so it stays
     * read-only. `project` and `local` belong to a folder, and this runs in none. A scope this app doesn't know isn't touched.
     */
    fun readOnlyReason(scope: String?): String? = when (scope) {
        USER -> null
        SYNCED -> "From your organization. It can't be changed here."
        "project", "local" -> "Set for one folder. Change it in a terminal there."
        else -> "This app doesn't know how to change a plugin of this kind."
    }
}

/** A plugin a marketplace offers. */
data class AvailablePlugin(
    val id: String,
    val name: String,
    val description: String?,
    val marketplace: String?,
    val version: String?,
    /** Where it comes from, in words. */
    val source: String,
)

data class MarketplaceInfo(val name: String, val source: String)

/** A command a marketplace declares for install. Shown as it is, and accepted only by this [sha256]. */
data class ShownCommand(val command: String, val sha256: String)

/** The result of a change. [message] has been scrubbed. */
data class PluginOutcome(val ok: Boolean, val message: String, val failureCode: String? = null, val shownCommand: ShownCommand? = null)

/** One option of `plugin configure`. [current] is null for anything secret. */
data class PluginOption(
    val key: String,
    val title: String,
    val description: String?,
    val required: Boolean,
    val secret: Boolean,
    val configured: Boolean,
    val current: String?,
    val choices: List<String>,
)

data class PluginForm(val pluginId: String, val displayName: String, val options: List<PluginOption>) {
    override fun toString() = "PluginForm($pluginId, ${options.size} options)"
}

/** The text of `plugin details`, the only form it has, and the always-on cost out of it. */
data class PluginDetails(val pluginId: String, val text: String, val alwaysOn: String?)

/** Arguments. Scope is always explicit. Nothing here passes `-y`: a declared command is accepted only by its hash, after being shown. */
object PluginCommands {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** An id or source that the CLI wouldn't take for a flag. */
    fun isSafeArgument(value: String): Boolean = value.isNotBlank() && !value.startsWith("-") && value.none { it.isWhitespace() }

    val list: List<String> = listOf("plugin", "list", "--json")
    val available: List<String> = listOf("plugin", "list", "--available", "--json")
    val marketplaces: List<String> = listOf("plugin", "marketplace", "list", "--json")

    fun details(id: String) = listOf("plugin", "details", id)
    fun configureRead(id: String) = listOf("plugin", "configure", id, "--json")
    fun configureSave(id: String) = listOf("plugin", "configure", id, "--json", "--values-stdin")
    fun enable(id: String) = listOf("plugin", "enable", id, "-s", PluginRules.USER, "--json")
    fun disable(id: String) = listOf("plugin", "disable", id, "-s", PluginRules.USER, "--json")
    fun uninstall(id: String) = listOf("plugin", "uninstall", id, "-s", PluginRules.USER, "--json")
    fun install(id: String, accept: ShownCommand? = null) = listOf("plugin", "install", id, "-s", PluginRules.USER, "--json") + accepting(accept)
    fun update(id: String, accept: ShownCommand? = null) = listOf("plugin", "update", id, "-s", PluginRules.USER, "--json") + accepting(accept)
    fun marketplaceAdd(source: String) = listOf("plugin", "marketplace", "add", source, "--scope", PluginRules.USER, "--json")
    fun marketplaceUpdate(name: String) = listOf("plugin", "marketplace", "update", name, "--json")
    fun marketplaceRemove(name: String) = listOf("plugin", "marketplace", "remove", name, "--json")

    private fun accepting(shown: ShownCommand?): List<String> =
        if (shown != null && SHA256.matches(shown.sha256)) listOf("--accept-command", shown.sha256) else emptyList()
}

object PluginParser {
    private const val MAX_DESCRIPTION = 240
    private const val MAX_DETAIL_LINES = 80
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val ALWAYS_ON = Regex("""^\s*Always-on:\s*(.+)$""")

    /** Names that say the value is a secret even if the plugin's manifest doesn't. */
    private val SECRET_NAME = Regex("""(?i)(token|secret|passw|api.?key|credential|private.?key|auth)""")

    /** Null when the reply isn't a list. */
    fun list(output: CliOutput): List<PluginInfo>? {
        val array = CliJson.arrayIn(output.stdout) ?: return null
        return array.objects().mapNotNull { o ->
            val id = o.textOrNull("id") ?: return@mapNotNull null
            PluginInfo(
                id = id,
                version = o.textOrNull("version"),
                scope = o.textOrNull("scope"),
                enabled = if (o.opt("enabled") is Boolean) o.optBoolean("enabled") else null,
                hasOptions = o.optBoolean("hasUserConfig"),
                mcpServers = o.optJSONObject("mcpServers")?.keys()?.asSequence()?.toList()?.sorted().orEmpty(),
            )
        }
    }

    fun available(output: CliOutput): List<AvailablePlugin>? {
        val array = CliJson.objectIn(output.stdout)?.optJSONArray("available") ?: return null
        return array.objects().mapNotNull { o ->
            val id = o.textOrNull("pluginId") ?: return@mapNotNull null
            val marketplace = o.textOrNull("marketplaceName")
            AvailablePlugin(
                id = id,
                name = o.textOrNull("name") ?: id.substringBefore('@'),
                description = o.textOrNull("description")?.take(MAX_DESCRIPTION),
                marketplace = marketplace,
                version = o.textOrNull("version"),
                source = source(o.opt("source"), marketplace),
            )
        }
    }

    fun marketplaces(output: CliOutput): List<MarketplaceInfo>? {
        val array = CliJson.arrayIn(output.stdout) ?: return null
        return array.objects().mapNotNull { o ->
            val name = o.textOrNull("name") ?: return@mapNotNull null
            val nested = o.optJSONObject("source")
            val where = listOf("repo", "url", "path").firstNotNullOfOrNull { nested?.textOrNull(it) ?: o.textOrNull(it) } ?: o.textOrNull("installLocation")
            val kind = o.textOrNull("source") ?: nested?.textOrNull("source")
            MarketplaceInfo(name, CliText.clean(listOfNotNull(kind, where).joinToString(": ")) ?: "Source not reported")
        }
    }

    /** `source` is a path inside the marketplace, or an object with a kind. Names the command when one will run. */
    private fun source(value: Any?, marketplace: String?): String {
        val from = marketplace?.let { " in marketplace $it" }.orEmpty()
        return when (value) {
            is String -> CliText.clean(value + from) ?: "Not reported"
            is JSONObject -> {
                val kind = value.textOrNull("source") ?: "source"
                val where = listOf("url", "repo", "package", "path", "command").firstNotNullOfOrNull { value.textOrNull(it) }
                when {
                    kind == "command" && where != null -> "Runs a command on this phone: $where"
                    where != null -> CliText.clean("$kind: $where") ?: kind
                    else -> kind
                }
            }
            else -> "Not reported$from"
        }
    }

    /** Works for any `--json` command. [values] were given to it and must not come back in its text. */
    fun outcome(output: CliOutput, fallback: String, values: Collection<String> = emptyList()): PluginOutcome {
        val json = CliJson.objectIn(output.stdout)
        val said = json?.textOrNull("message")
        val failed = !output.ok || json?.optString("outcome") == "failed"
        val message = if (failed) CliText.clean(said, values = values) ?: CliText.failure(output, fallback, values = values)
        else CliText.clean(said, values = values) ?: fallback
        return PluginOutcome(!failed, message, json?.textOrNull("failureCode"), json?.optJSONObject("shownCommand")?.let(::shownCommand))
    }

    private fun shownCommand(o: JSONObject): ShownCommand? {
        val command = o.textOrNull("command") ?: return null
        val sha = o.textOrNull("sha256")?.takeIf { SHA256.matches(it) } ?: return null
        return ShownCommand(command, sha)
    }

    /** Null when the reply isn't a form. A value that may be secret isn't copied, whatever the reply held. */
    fun form(output: CliOutput): PluginForm? {
        val o = CliJson.objectIn(output.stdout) ?: return null
        val schema = o.optJSONObject("schema") ?: return null
        val id = o.textOrNull("pluginId") ?: return null
        val inputs = o.optJSONObject("inputs")
        val choices = o.optJSONObject("choices")
        val configured = o.optJSONArray("configured")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().toSet()
        val options = schema.keys().asSequence().toList().mapNotNull { key ->
            val field = schema.optJSONObject(key) ?: return@mapNotNull null
            val secret = field.optBoolean("sensitive") || SECRET_NAME.containsMatchIn(key)
            PluginOption(
                key = key,
                title = field.textOrNull("title") ?: key,
                description = field.textOrNull("description"),
                required = field.optBoolean("required"),
                secret = secret,
                configured = key in configured,
                current = if (secret) null else inputs?.textOrNull(key),
                choices = choices?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            )
        }
        return PluginForm(id, o.textOrNull("displayName") ?: id, options)
    }

    /** The result of saving: `refused.message` is the reason when it was refused. [values] never come back. */
    fun saved(output: CliOutput, values: Collection<String>): PluginOutcome {
        val o = CliJson.objectIn(output.stdout)
        val refused = o?.optJSONObject("refused")
        if (refused != null || !output.ok) {
            val message = CliText.clean(refused?.textOrNull("message"), values = values) ?: CliText.failure(output, "Claude Code didn't save them.", values = values)
            return PluginOutcome(false, message)
        }
        return PluginOutcome(true, "Saved. New sessions on this account will use them.")
    }

    fun details(pluginId: String, output: CliOutput): PluginDetails? {
        if (!output.ok) return null
        val lines = output.stdout.take(MAX_DETAIL_LINES).map { line -> CliText.clean(line).orEmpty() }
        val alwaysOn = lines.firstNotNullOfOrNull { ALWAYS_ON.matchEntire(it)?.groupValues?.get(1)?.trim() }
        return PluginDetails(pluginId, lines.joinToString("\n").trim(), alwaysOn)
    }
}

/** What goes on stdin of `configure --values-stdin`: only what the user changed. Pure. */
object PluginValues {
    /** Options left out keep their values (measured), and so does a blank one, so a secret field left blank is simply not sent. */
    fun changed(form: PluginForm, typed: Map<String, String>): Map<String, String> =
        form.options.mapNotNull { option ->
            val value = typed[option.key]?.takeIf { it.isNotEmpty() && it != option.current } ?: return@mapNotNull null
            option.key to value
        }.toMap()

    /** "single-line strings", says `--help`. */
    fun problem(form: PluginForm, values: Map<String, String>): String? =
        form.options.firstOrNull { o -> values[o.key]?.any { it == '\n' || it == '\r' } == true }?.let { "${it.title} has to be one line." }

    fun stdin(values: Map<String, String>): String = JSONObject(values as Map<*, *>).toString()
}

/** Searching what marketplaces offer. Pure. */
object PluginCatalog {
    const val MAX_SHOWN = 40

    fun search(available: List<AvailablePlugin>, installed: List<PluginInfo>, query: String): List<AvailablePlugin> {
        val have = installed.map { it.id }.toSet()
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        return available.asSequence()
            .filter { it.id !in have }
            .filter { p -> words.all { w -> p.id.lowercase().contains(w) || p.description?.lowercase()?.contains(w) == true } }
            .take(MAX_SHOWN)
            .toList()
    }

    /** What to show before installing [id], from what is known about it. */
    fun sourceOf(id: String, available: List<AvailablePlugin>, marketplaces: List<MarketplaceInfo>): String {
        available.firstOrNull { it.id == id }?.let { return it.source }
        val marketplace = id.substringAfter('@', "")
        marketplaces.firstOrNull { it.name == marketplace }?.let { return "Marketplace $marketplace (${it.source})" }
        return if (marketplace.isEmpty()) "Whichever added marketplace offers it. Not known until it is installed."
        else "Marketplace $marketplace, which Claude Code will look up."
    }
}
