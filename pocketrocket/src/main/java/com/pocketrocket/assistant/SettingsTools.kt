package com.pocketrocket.assistant

import com.pocketrocket.settings.ThemeMode
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import com.pocketrocket.updates.updateOrder

/** Finds what a spoken name means: an exact match on any of its names, else any that contain it. Spaces and case don't count. */
internal fun <T> pickByName(wanted: String, choices: List<T>, names: (T) -> List<String>): List<T> {
    val key = ProjectNames.key(wanted)
    if (key.isEmpty()) return emptyList()
    return choices.filter { c -> names(c).any { ProjectNames.key(it) == key } }.ifEmpty {
        choices.filter { c -> names(c).any { key in ProjectNames.key(it) } }
    }
}

/**
 * Settings by voice or typing (build step 6). The list here is the whole of it: there's no tool for a login, a secret or a safety
 * rule, so nothing Claude is told can reach one, and `SettingsToolsTest` fails if one is added. Theme, voice, Quiet and
 * Rocket's own model just happen and take a fingerprint on a locked phone. Default accounts, tools and updates go through [ToolHost.confirm].
 */
class SettingsTools(private val host: ToolHost) : ToolSet() {
    private val settings get() = host.settings

    internal val tools = listOf(
        Tool("list_settings", "What Settings shows now: theme, voice, Quiet, your model, default accounts, tools.", emptyList()) { listSettings() },
        Tool("list_voices", "The voices on this phone, to pick from. Never cloud ones.", emptyList()) { listVoices() },
        Tool("set_theme", "Change the app's theme. It just happens.", listOf(text("theme", "Dark, Light or System"))) { setTheme(it) },
        Tool("set_voice", "Change the voice you speak in. It just happens.", listOf(text("voice", "A voice from list_voices, or \"default\" for the phone's"))) { setVoice(it) },
        Tool("set_quiet", "Quiet: no listening or speaking for one hour, or end it. It can't be any other length.", listOf(Param("on", "boolean", "On or off"))) { change(SettingChange.Quiet(it.bool("on"))) },
        Tool("set_rocket_model", "Change the model you yourself run on. Not a session's: that's set_model.", listOf(text("model", "A model's name, like Haiku or Opus"))) { setRocketModel(it) },
        Tool(
            "ask_to_set_default_account", "Ask to change the default Claude or GitHub account. Changes it only if he says yes.",
            listOf(text("service", "Claude or GitHub"), text("account", "The account's name"))
        ) { askToSetDefault(it) },
        Tool("ask_to_install_tool", "Ask to install a tool from Settings › Tools. Installs it only if he says yes.", listOf(text("tool", "The tool's name"))) { askToInstallTool(it) },
        Tool("check_updates", "Look for new versions of PocketRocket, Claude Code, Git and Dockito. Installs nothing.", emptyList()) { checkUpdates() },
        Tool("ask_to_update", "Ask to update one of those, or all that have an update. Starts it only if he says yes.", listOf(text("what", "PocketRocket, Claude Code, Git, Dockito or all"))) { askToUpdate(it) },
    )

    private fun listSettings(): ToolResult {
        val f = settings.facts()
        val tools = settings.tools().joinToString { "${it.label}: ${if (it.installed) "installed" else "not installed"}" }
        return ok(
            AssistantTools.data(
                "settings",
                """
                Theme: ${f.theme.label}
                Voice: ${f.voice ?: "the best voice on this phone"}
                Quiet: ${f.quietUntil?.let { "on until $it" } ?: "off"}
                Your model: ${f.rocketModel}
                Default Claude account: ${f.defaultClaude ?: "none"}
                Default GitHub account: ${f.defaultGitHub ?: "none"}
                Tools: $tools
                """.trimIndent(),
            ),
        )
    }

    private suspend fun listVoices(): ToolResult {
        val voices = settings.voices()
        if (voices.isEmpty()) return ok("No on-device voices are installed. Android's text-to-speech settings can add them.")
        val shown = voices.take(MAX_VOICES).joinToString("\n") { "${it.label} | ${it.note}" }
        val more = if (voices.size > MAX_VOICES) "\nAnd ${voices.size - MAX_VOICES} more, in Settings under Assistant." else ""
        return ok(AssistantTools.data("voices", shown + more))
    }

    private suspend fun setTheme(args: ToolArgs): ToolResult {
        val wanted = args.string("theme")
        val mode = ThemeMode.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: throw ToolRefused("Themes are Dark, Light and System.")
        return change(SettingChange.Theme(mode))
    }

    private suspend fun setVoice(args: ToolArgs): ToolResult {
        val wanted = args.string("voice")
        if (ProjectNames.key(wanted) in PHONE_DEFAULT) return change(SettingChange.Voice(null, "the best voice on this phone"))
        val found = pickByName(wanted, settings.voices()) { listOf(it.id, Voices.nameOf(it.id).orEmpty(), it.label) }
        return when (found.size) {
            0 -> throw ToolRefused("There's no voice like $wanted on this phone. list_voices has them.")
            1 -> change(SettingChange.Voice(found.single().id, found.single().label))
            else -> throw ToolRefused("$wanted could be ${found.size} voices: ${found.take(MAX_CHOICES).joinToString { "${it.label} (${it.note})" }}. Ask him which.")
        }
    }

    private suspend fun setRocketModel(args: ToolArgs): ToolResult {
        val wanted = args.string("model")
        val models = settings.rocketModels()
        val found = pickByName(wanted, models) { listOf(it.first, it.second) }
        return when (found.size) {
            1 -> change(SettingChange.RocketModel(found.single().first, found.single().second))
            0 -> throw ToolRefused("I can't run on $wanted. The models are: ${models.joinToString { it.second }}.")
            else -> throw ToolRefused("$wanted could be: ${found.joinToString { it.second }}. Ask him which.")
        }
    }

    /** Just happens, unlocked. Locked it takes a fingerprint, like starting an agent: anyone nearby can talk to the phone. */
    private suspend fun change(change: SettingChange): ToolResult {
        if (host.locked && !host.confirm(RocketAction.WhileLocked(change.asked))) return said("Not changed.")
        settings.apply(change)
        host.log(change.said, THROUGH_ROCKET)
        return ok(change.said)
    }

    private suspend fun askToSetDefault(args: ToolArgs): ToolResult {
        val named = args.string("service")
        val service = AccountService.entries.firstOrNull { it.label.equals(named, ignoreCase = true) } ?: throw ToolRefused("It's Claude or GitHub. Other accounts are in Settings.")
        val accounts = if (service == AccountService.Claude) host.claudeAccounts() else host.githubAccounts()
        val wanted = args.string("account")
        val found = pickByName(wanted, accounts) { listOf(it.first, it.second) }
        val account = when (found.size) {
            1 -> found.single()
            0 -> throw ToolRefused("There's no ${service.label} account called $wanted. They are: ${accounts.joinToString { it.second }.ifEmpty { "none" }}. Adding one is his to do in Settings.")
            else -> throw ToolRefused("$wanted could be: ${found.joinToString { it.second }}. Ask him which.")
        }
        val facts = settings.facts()
        val current = if (service == AccountService.Claude) facts.defaultClaude else facts.defaultGitHub
        if (current == account.second) return ok("${account.second} is already the default ${service.label} account.")
        if (!host.confirm(RocketAction.SetDefaultAccount(service.label, account.second, current))) return said("Not changed.")
        settings.setDefaultAccount(service, account.first)
        val line = "${account.second} is now the default ${service.label} account."
        host.log(line, THROUGH_ROCKET)
        return ok(if (service == AccountService.Claude && facts.rocketOnDefault) "$line I run on it too, so my next answer starts a new conversation." else line)
    }

    private suspend fun askToInstallTool(args: ToolArgs): ToolResult {
        val wanted = args.string("tool")
        val all = settings.tools()
        val found = pickByName(wanted, all) { listOf(it.id, it.label) }
        val tool = found.singleOrNull() ?: throw ToolRefused("The tools are: ${all.joinToString { it.label }}.")
        if (tool.installed) return ok("${tool.label} is already installed.")
        if (tool.busy) return ok("${tool.label} is already being changed. Settings › Tools shows how it goes.")
        if (!host.confirm(RocketAction.InstallTool(tool.label))) return said("Not installed.")
        settings.installTool(tool.id)
        host.log("Installing ${tool.label}", THROUGH_ROCKET)
        return ok("Installing ${tool.label}. It isn't done yet: Settings › Tools shows how it goes.")
    }

    private suspend fun checkUpdates(): ToolResult {
        val rows = settings.updates(check = true)
        if (rows.isEmpty()) return ok("Nothing to check yet.")
        return ok(AssistantTools.data("updates", rows.joinToString("\n", transform = ::describe)))
    }

    private fun describe(row: UpdateRow) = "${row.component.label}: " + when {
        row.running -> "updating"
        row.installed == null -> "not installed"
        row.available -> "${row.installed}, ${row.latest} is out"
        row.latest == null -> "${row.installed}, newest unknown"
        else -> "${row.installed}, up to date"
    }

    private suspend fun askToUpdate(args: ToolArgs): ToolResult {
        val rows = settings.updates(check = false)
        val wanted = args.string("what")
        val named = if (ProjectNames.key(wanted) in ALL) rows.filter { it.available } else {
            val found = pickByName(wanted, rows) { listOf(it.component.name, it.component.label) }
            if (found.size != 1) throw ToolRefused("It's one of ${UpdateComponent.entries.joinToString { it.label }}, or all.")
            found
        }
        val ready = named.filter { it.available }
        if (ready.isEmpty()) return ok((named.firstOrNull()?.let { "${describe(it)}." } ?: "Everything's up to date.") + " check_updates looks again.")
        val updatingApp = ready.any { it.component == UpdateComponent.PocketRocket }
        // Android's permission screen is for him to open, and it's the Updates page that waits for the answer.
        if (updatingApp && !settings.canInstallApp()) throw ToolRefused("Android hasn't let PocketRocket install its own updates yet. That's asked from Settings › Updates: tap Update there once.")
        val ordered = updateOrder(ready.map { it.component })
        val names = ordered.joinToString(" and ") { it.label }
        val versions = ordered.joinToString("\n") { c -> ready.first { it.component == c }.let { "${c.label} ${it.installed} to ${it.latest}" } }
        val active = settings.activeSessions()
        val warning = when {
            !updatingApp -> null
            active > 0 -> "The app restarts, pausing $active working ${if (active == 1) "session" else "sessions"}"
            else -> "The app restarts"
        }
        if (!host.confirm(RocketAction.InstallUpdate(names, versions, warning))) return said("Not updated.")
        settings.startUpdates(ordered)
        host.log("Updating $names", THROUGH_ROCKET)
        return ok("Updating $names. It isn't done yet: Settings › Updates shows how it goes.")
    }

    private companion object {
        const val THROUGH_ROCKET = "Through Rocket"
        const val MAX_VOICES = 30
        const val MAX_CHOICES = 6
        val PHONE_DEFAULT = setOf("default", "phonesdefault", "phonedefault", "thephonesdefault")
        val ALL = setOf("all", "everything")
    }
}
