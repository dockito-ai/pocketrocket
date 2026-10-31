package com.pocketrocket.assistant

import android.content.Context
import android.text.format.DateFormat
import com.pocketrocket.appupdate.ApkInstaller
import com.pocketrocket.appupdate.AppUpdater
import com.pocketrocket.browser.Chromium
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.sessions.ModelCatalog
import com.pocketrocket.settings.ThemeMode
import com.pocketrocket.settings.ThemePrefs
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import com.pocketrocket.updates.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A setting that just happens on an unlocked phone. Only what's here can change that way, and nothing here relaxes a protection:
 * logins, secrets and the safety rules have no case, no tool and no phrase ([SettingPhrases.refusal]).
 */
sealed interface SettingChange {
    /** What's now true, said after it's done and written in Activity. */
    val said: String

    /** What a fingerprint on a locked phone would allow. */
    val asked: String

    data class Theme(val mode: ThemeMode) : SettingChange {
        override val said get() = "Theme is now ${mode.label}."
        override val asked get() = "Change the theme to ${mode.label}"
    }

    /** [id] null is the best on-device voice. */
    data class Voice(val id: String?, val name: String) : SettingChange {
        override val said get() = "Voice is now $name."
        override val asked get() = "Change Rocket's voice"
    }

    /** One hour, as the Quiet tile does. */
    data class Quiet(val on: Boolean) : SettingChange {
        override val said get() = if (on) "Quiet for an hour: no listening or speaking." else "Quiet is off."
        override val asked get() = if (on) "Go quiet for an hour" else "Turn Quiet off"
    }

    /** The model Rocket itself runs on. Sessions choose their own. */
    data class RocketModel(val value: String, val name: String) : SettingChange {
        override val said get() = "I now run on $name. Sessions aren't affected."
        override val asked get() = "Change Rocket's model to $name"
    }
}

/** The one way a [SettingChange] happens, through the same calls the Settings screens make, so they update as it does. */
object SettingsControl {
    fun apply(context: Context, change: SettingChange) {
        when (change) {
            is SettingChange.Theme -> ThemePrefs.set(context, change.mode)
            is SettingChange.Voice -> AssistantPrefs.update(context) { it.copy(voice = change.id) }
            is SettingChange.Quiet -> if (change.on) AssistantPrefs.startQuiet(context) else AssistantPrefs.endQuiet(context)
            is SettingChange.RocketModel -> AssistantPrefs.update(context) { it.copy(model = change.value) }
        }
    }
}

/** Settings said in so many words, answered with no Claude. Anything else about settings goes to the brain, which has the same limits. */
object SettingPhrases {
    private const val THEMES = "(dark|light|system)"
    private val THEME = listOf(
        Regex("""^(?:(?:use|switch to|change to|go to|set|turn on|enable) )?(?:the )?$THEMES (?:theme|mode)$"""),
        Regex("""^(?:change|set|switch) (?:the )?(?:app )?theme to (?:the )?$THEMES(?: theme| mode)?$"""),
        Regex("""^go (dark|light)$"""),
    )
    // Not "quiet" or "be quiet": it closes the mic for an hour, so it isn't for a stray word.
    private val QUIET_ON = Regex("""^(?:go quiet|quiet(?: mode)? on|turn on quiet(?: mode)?|(?:be )?quiet for (?:an|one|1) hour)$""")
    private val QUIET_OFF = Regex("""^(?:quiet(?: mode)? off|turn off quiet(?: mode)?|stop being quiet|end quiet(?: mode)?)$""")

    private val LOGIN = Regex("""^(?:log ?in|log ?out|sign ?in|sign ?out)\b.*$""")
    private val SECRET = Regex("""^(?:show|read|tell|change|set|give|what(?:'s| is|s))(?: me)?(?: out)? (?:my|the) (?:[a-z]+ )?(?:token|password|api key|secret)$""")
    private const val RELAX = """(?:turn off|switch off|disable|remove|skip|drop|relax|loosen|change|edit|stop asking for|stop requiring|don't ask for|do not ask for|don't require|do not require)"""
    private const val RULE = """(?:fingerprints?|sensitive (?:list|actions?)|approvals?|approval rules?|locked[- ]phone(?: rule)?|safety rules?)"""
    private val SAFETY = listOf(
        Regex("""^$RELAX (?:(?:the|my|your|rocket's|a|an) )?$RULE(?: for .+| on .+)?$"""),
        Regex("""^(?:remove|take|drop) .+ (?:from|off) (?:the )?sensitive (?:list|actions?)$"""),
        Regex("""^(?:turn off|switch off|disable) (?:rocket|(?:the )?(?:voice )?assistant)$"""),
    )

    const val NOT_LOGINS = "I can't do logins. They're yours to do, in Settings under Accounts."
    const val NOT_SECRETS = "I can't read or change tokens and passwords. They stay with the tools that use them, and logins are in Settings under Accounts."
    const val NOT_SAFETY = "I can't change that. Rocket's safety rules and its off switch are yours to change, in Settings under Assistant."

    /** [q] is already tidied by [Router]. */
    fun change(q: String): SettingChange? {
        THEME.firstNotNullOfOrNull { it.find(q) }?.let { found ->
            return SettingChange.Theme(ThemeMode.entries.first { it.name.equals(found.groupValues[1], ignoreCase = true) })
        }
        if (QUIET_ON.matches(q)) return SettingChange.Quiet(true)
        if (QUIET_OFF.matches(q)) return SettingChange.Quiet(false)
        return null
    }

    /** What's never done, said plainly, with no Claude. Everything else that's forbidden just has no tool. */
    fun refusal(q: String): String? = when {
        LOGIN.matches(q) -> NOT_LOGINS
        SECRET.matches(q) -> NOT_SECRETS
        SAFETY.any { it.matches(q) } -> NOT_SAFETY
        else -> null
    }
}

/** What Settings shows now, in words. */
data class SettingsFacts(
    val theme: ThemeMode,
    /** Null is the best on-device voice. */
    val voice: String?,
    /** When Quiet ends, as a time; null when it's off. */
    val quietUntil: String?,
    val rocketModel: String,
    val defaultClaude: String?,
    val defaultGitHub: String?,
    /** Rocket's login is Default, so it moves with the default Claude account. */
    val rocketOnDefault: Boolean,
)

/** One of Settings › Tools. */
data class ToolFacts(val id: String, val label: String, val installed: Boolean, val busy: Boolean)

/** Which accounts have a default a yes can move. */
enum class AccountService(val label: String) { Claude("Claude"), GitHub("GitHub") }

/** What the settings tools see of the app. Plain enough to fake. There's nothing here for logins, secrets or safety rules. */
interface SettingsHost {
    fun facts(): SettingsFacts
    fun apply(change: SettingChange)
    suspend fun voices(): List<VoiceOption>
    /** (value, name) of what Rocket can run on. */
    fun rocketModels(): List<Pair<String, String>>
    fun setDefaultAccount(service: AccountService, id: String)
    fun tools(): List<ToolFacts>
    fun installTool(id: String)
    /** The Updates page's rows. [check]: ask for the newest versions first. */
    suspend fun updates(check: Boolean): List<UpdateRow>
    fun startUpdates(components: List<UpdateComponent>)
    /** Android lets PocketRocket install its own new version. Asking takes the Updates page. */
    fun canInstallApp(): Boolean
    /** Sessions that restarting the app would pause. */
    fun activeSessions(): Int
}

internal class AppSettingsHost(private val app: Context) : SettingsHost {
    override fun facts(): SettingsFacts {
        val s = AssistantPrefs.settings(app).value
        val claude = ClaudeAccounts.state.value
        return SettingsFacts(
            theme = ThemePrefs.mode(app).value,
            voice = Voices.nameOf(s.voice),
            quietUntil = s.quietUntil.takeIf { s.isQuiet() }?.let { DateFormat.getTimeFormat(app).format(it) },
            rocketModel = s.model,
            defaultClaude = claude.default?.name,
            defaultGitHub = GitHubAccounts.state.value.default?.name,
            rocketOnDefault = claude.get(s.loginId) == null,
        )
    }

    override fun apply(change: SettingChange) = SettingsControl.apply(app, change)

    override suspend fun voices(): List<VoiceOption> = withContext(Dispatchers.Main) { Voices.installed(app) }

    override fun rocketModels() = ModelCatalog.forRocket(app).map { it.value to it.displayName }

    override fun setDefaultAccount(service: AccountService, id: String) = when (service) {
        AccountService.Claude -> ClaudeAccounts.setDefault(id)
        AccountService.GitHub -> GitHubAccounts.setDefault(id)
    }

    override fun tools() = listOf(ToolFacts(CHROMIUM, "Headless Chrome", Chromium.installed(), Chromium.state.value.running))

    override fun installTool(id: String) {
        if (id == CHROMIUM) Chromium.install(app)
    }

    override suspend fun updates(check: Boolean): List<UpdateRow> {
        withContext(Dispatchers.Main) {
            Updates.onAppOpen(app)
            if (check) Updates.checkAll(app)
        }
        return withTimeoutOrNull(CHECK_MS) { Updates.settledRows() } ?: Updates.state.value.rows
    }

    override fun startUpdates(components: List<UpdateComponent>) = Updates.update(app, components)

    override fun canInstallApp() = ApkInstaller.canInstall(app)

    override fun activeSessions() = AppUpdater.activeSessions()

    private companion object {
        const val CHROMIUM = "chromium"
        const val CHECK_MS = 30_000L
    }
}
