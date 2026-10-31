package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.ModelOption
import com.pocketrocket.sessions.objects
import org.json.JSONObject

/** One skill from `get_skills_dialog`. Only these fields are copied out; [description] has been scrubbed. */
data class SkillInfo(
    val name: String,
    val displayName: String,
    val description: String?,
    val source: String?,
    val tokens: Int,
    val state: String,
    val lockedBy: String?,
) {
    val stateLabel: String
        get() = when (state) {
            "on" -> "On"
            "off" -> "Off"
            "name-only" -> "Name only"
            "user-invocable-only" -> "Only when you call it"
            else -> "Unknown"
        }

    val sourceLabel: String
        get() = when (source) {
            "user" -> "This account"
            "project" -> "This folder"
            "plugin" -> "Plugin"
            null -> "Other"
            else -> SafeValue.of(source) ?: "Other"
        }
}

/** One setting fit to show. [source] is in the app's words; [managed] when the organization set it. */
data class SettingRow(val label: String, val value: String, val source: String, val managed: Boolean)

/**
 * What `get_settings` may show. Nothing else from the reply is kept: other keys are only counted in [hiddenCount] and
 * [errorCount] replaces the text of any errors. [managedKeys] are names only, and decide what can't be changed here.
 */
data class SettingsView(
    val rows: List<SettingRow> = emptyList(),
    val hiddenCount: Int = 0,
    val errorCount: Int = 0,
    val outputStyle: String? = null,
    val appliedModel: String? = null,
    val appliedEffort: String? = null,
    val managedKeys: Set<String> = emptySet(),
) {
    fun isManaged(key: String) = key in managedKeys
}

/** A string that is plainly not a secret: short, one line, no query string or `key=value`, no long run like a token. */
internal object SafeValue {
    private const val MAX = 64
    private val PLAIN = Regex("""[\p{L}\p{M}\p{N} ._:/@+\[\]-]+""")
    private val TOKEN = Regex("""[A-Za-z0-9_]{24,}""")

    fun of(text: String): String? = text.takeIf { it.length <= MAX && PLAIN.matches(it) && !TOKEN.containsMatchIn(it) }
}

/** `get_skills_dialog` to plain values. Pure, from an allowlist. */
object SkillsParser {
    private const val MAX_DESCRIPTION = 160

    /** Null when the reply has no such list. */
    fun parse(reply: JSONObject): List<SkillInfo>? = reply.optJSONArray("skills")?.objects()?.mapNotNull { o ->
        val name = o.text("name") ?: return@mapNotNull null
        SkillInfo(
            name = name,
            displayName = o.text("display_name") ?: name,
            description = McpParser.scrub(o.text("description"), max = MAX_DESCRIPTION),
            source = o.text("source"),
            tokens = o.optInt("tokens"),
            state = o.optString("state"),
            lockedBy = o.text("locked_by"),
        )
    }?.sortedBy { it.displayName.lowercase() }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}

/**
 * `get_settings` to a [SettingsView]. Pure. A key shows only if [SHOWN] names it and its value is a plain boolean, number
 * or [SafeValue]; everything else, above all `env`, `hooks`, `apiKeyHelper` and headers, is never read past its name.
 * The list is of keys that hold preferences, not of keys seen in a real reply, so a key missing here just stays hidden.
 */
object SettingsParser {
    /** Key to the label shown. */
    private val SHOWN = linkedMapOf(
        "model" to "Model",
        SetupChoices.OUTPUT_STYLE_KEY to "Output style",
        SetupChoices.EFFORT_KEY to "Effort",
        "language" to "Language",
        "alwaysThinkingEnabled" to "Thinking on by default",
        "autoMemoryEnabled" to "Auto memory",
        "includeGitInstructions" to "Git instructions",
        "respectGitignore" to "Respect .gitignore",
        "cleanupPeriodDays" to "Keep transcripts, days",
        "autoUpdatesChannel" to "Update channel",
        "disableAllHooks" to "All hooks off",
        "enableAllProjectMcpServers" to "Trust all .mcp.json servers",
    )

    /** Low to high priority, as `chat-sessions.md` measured. By name, not by the reply's order. */
    private val PRIORITY = listOf("userSettings", "projectSettings", "localSettings", "flagSettings", MANAGED_SOURCE)

    private const val MANAGED_SOURCE = "policySettings"

    /** Stands in for a value that isn't plain: it is set, and that is all that is said. */
    const val HIDDEN_VALUE = "Set, hidden"

    private const val IN_USE = "In use now"
    private const val UNKNOWN_SOURCE = "Default"

    fun sourceLabel(source: String?): String = when (source) {
        "userSettings" -> "Account"
        "projectSettings" -> "Folder, shared"
        "localSettings" -> "Folder, this phone"
        "flagSettings" -> "This session"
        MANAGED_SOURCE -> "Organization"
        null -> UNKNOWN_SOURCE
        else -> "Other"
    }

    /** Null when the reply has no `effective`. */
    fun parse(reply: JSONObject): SettingsView? {
        val effective = reply.optJSONObject("effective") ?: return null
        val sources = reply.optJSONArray("sources").objects().mapNotNull { o ->
            val settings = o.optJSONObject("settings") ?: return@mapNotNull null
            o.optString("source") to settings
        }.sortedBy { (source, _) -> PRIORITY.indexOf(source) }
        val managed = sources.firstOrNull { (source, _) -> source == MANAGED_SOURCE }?.second?.keys()?.asSequence()?.toSet().orEmpty()

        val keys = effective.keys().asSequence().toList()
        val rows = SHOWN.mapNotNull { (key, label) ->
            if (key !in keys || effective.isNull(key)) return@mapNotNull null
            val from = sources.lastOrNull { (_, settings) -> settings.has(key) }?.first
            SettingRow(label, show(effective.opt(key)), sourceLabel(from), from == MANAGED_SOURCE)
        }
        val applied = reply.optJSONObject("applied")
        val model = applied?.plain("model")
        val effort = applied?.plain("effort")
        val inUse = listOfNotNull(
            model?.takeIf { "model" !in keys }?.let { SettingRow("Model", it, IN_USE, false) },
            effort?.takeIf { SetupChoices.EFFORT_KEY !in keys }?.let { SettingRow("Effort", it, IN_USE, false) },
        )
        return SettingsView(
            rows = inUse + rows,
            hiddenCount = keys.count { it !in SHOWN },
            errorCount = reply.optJSONArray("errors")?.length() ?: if (reply.has("errors") && !reply.isNull("errors")) 1 else 0,
            outputStyle = effective.plain(SetupChoices.OUTPUT_STYLE_KEY),
            appliedModel = model,
            appliedEffort = effort,
            managedKeys = managed,
        )
    }

    private fun show(value: Any?): String = when (value) {
        is Boolean -> if (value) "On" else "Off"
        is Number -> value.toString()
        is String -> SafeValue.of(value) ?: HIDDEN_VALUE
        else -> HIDDEN_VALUE
    }

    private fun JSONObject.plain(key: String): String? = (opt(key) as? String)?.let(SafeValue::of)
}

/** What can be picked. Pure. */
object SetupChoices {
    /** The levels Claude Code lists for the model in use; none when it lists none, so nothing is offered blind. */
    fun effortLevels(models: List<ModelOption>, model: String?): List<String> =
        models.firstOrNull { it.resolvedModel == model && it.effortLevels.isNotEmpty() }?.effortLevels.orEmpty()

    /** The names in a `reload_output_styles` reply, leaving out any that could not safely be shown or sent back as is. */
    fun outputStyles(reply: JSONObject): List<String> {
        val list = reply.optJSONArray("available_output_styles") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> (list.opt(i) as? String)?.let(SafeValue::of) }
    }

    /** The style in effect: Claude Code leaves it out when none is set. */
    fun currentStyle(settings: SettingsView?): String = settings?.outputStyle ?: DEFAULT_STYLE

    const val DEFAULT_STYLE = "default"
    const val OUTPUT_STYLE_KEY = "outputStyle"
    const val EFFORT_KEY = "effortLevel"
}
