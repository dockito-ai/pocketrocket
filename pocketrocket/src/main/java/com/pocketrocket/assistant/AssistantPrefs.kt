package com.pocketrocket.assistant

import android.content.Context
import com.pocketrocket.profiles.AccountList
import com.pocketrocket.profiles.ClaudeAccountEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What it takes to allow, push or switch to Auto mode on a locked phone (decision 54). */
enum class LockedPhoneRule(val label: String) { FingerprintOrSpokenYes("Fingerprint or spoken yes"), FingerprintOnly("Fingerprint only") }

/** Every switch of the assistant. Defaults are decision 54's. */
data class AssistantSettings(
    /** Off removes the Rocket tab and runs nothing. */
    val enabled: Boolean = true,
    /** Hey Rocket: off until he switches it on, and it listens only then (decision 62). */
    val wakeWord: Boolean = false,
    /** Speak over the speaker too, not only headphones. */
    val speaker: Boolean = false,
    /** The picked voice ([Voices.id]); null is the best on-device voice ([Voices.best]). */
    val voice: String? = null,
    /** When Quiet ends, in epoch milliseconds; 0 when it isn't on. */
    val quietUntil: Long = 0L,
    /** The Claude account Rocket runs as; null is Default, whichever account that is. */
    val loginId: String? = null,
    /** A model alias or id for the CLI. Sonnet: the brain is a working agent now (decision 61). */
    val model: String = "sonnet",
    val lockedPhone: LockedPhoneRule = LockedPhoneRule.FingerprintOrSpokenYes,
) {
    fun isQuiet(now: Long = System.currentTimeMillis()): Boolean = quietUntil > now

    /** The login choices, Default first, as (account id, label); null is Default. */
    fun loginChoices(accounts: AccountList<ClaudeAccountEntry>): List<Pair<String?, String>> =
        listOf(null to "Default (${accounts.default?.name ?: "none"})") + accounts.accounts.map { it.id to it.name }

    /** The chosen login's label. An account removed since falls back to Default. */
    fun loginLabel(accounts: AccountList<ClaudeAccountEntry>): String =
        loginChoices(accounts).let { all -> all.firstOrNull { it.first == loginId } ?: all.first() }.second

    /** The name of the account Rocket would run as. */
    fun loginName(accounts: AccountList<ClaudeAccountEntry>): String? = (accounts.get(loginId) ?: accounts.default)?.name
}

/** The assistant's switches, stored in the module's SharedPreferences and observable while the app runs. */
object AssistantPrefs {
    private const val FILE = "pocketrocket"
    private const val ONE_HOUR_MS = 60 * 60_000L
    /**
     * New keys for two changed defaults: 1.0.3 wrote every switch on each change, so its Hey Rocket on and Haiku would
     * otherwise read as his choice. Hey Rocket starts off (decision 62) and Rocket on Sonnet (decision 61).
     */
    private const val KEY_WAKE_WORD = "assistant_wake_word_v2"
    private const val KEY_MODEL = "assistant_model_v2"

    private var flow: MutableStateFlow<AssistantSettings>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var quietTimer: Job? = null

    fun settings(context: Context): StateFlow<AssistantSettings> {
        val state = state(context)
        // The timer sleeps with the phone, so it can run late: anyone looking finds Quiet over on time.
        if (state.value.quietUntil != 0L && !state.value.isQuiet()) update(context) { it.copy(quietUntil = 0L) }
        return state.asStateFlow()
    }

    fun update(context: Context, change: (AssistantSettings) -> AssistantSettings) {
        val state = state(context)
        state.update(change)
        write(context, state.value)
        scheduleQuietEnd(context, state.value.quietUntil)
    }

    /** Quiet for one hour from now; it switches itself off after that. */
    fun startQuiet(context: Context) = update(context) { it.copy(quietUntil = System.currentTimeMillis() + ONE_HOUR_MS) }

    fun endQuiet(context: Context) = update(context) { it.copy(quietUntil = 0L) }

    private fun scheduleQuietEnd(context: Context, until: Long) {
        quietTimer?.cancel()
        if (until == 0L) return
        val app = context.applicationContext
        quietTimer = scope.launch {
            delay(until - System.currentTimeMillis())
            endQuiet(app)
        }
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<AssistantSettings> = flow ?: MutableStateFlow(read(context)).also {
        flow = it
        scheduleQuietEnd(context, it.value.quietUntil)
    }

    private fun read(context: Context): AssistantSettings {
        val p = prefs(context)
        val d = AssistantSettings()
        return AssistantSettings(
            enabled = p.getBoolean("assistant_enabled", d.enabled),
            wakeWord = p.getBoolean(KEY_WAKE_WORD, d.wakeWord),
            speaker = p.getBoolean("assistant_speaker", d.speaker),
            voice = p.getString("assistant_voice", d.voice),
            quietUntil = p.getLong("assistant_quiet_until", d.quietUntil),
            loginId = p.getString("assistant_login", d.loginId),
            model = p.getString(KEY_MODEL, d.model) ?: d.model,
            lockedPhone = p.getString("assistant_locked_phone", null)?.let { n -> LockedPhoneRule.entries.firstOrNull { it.name == n } } ?: d.lockedPhone,
        )
    }

    private fun write(context: Context, s: AssistantSettings) {
        prefs(context).edit()
            .putBoolean("assistant_enabled", s.enabled)
            .putBoolean(KEY_WAKE_WORD, s.wakeWord)
            .remove("assistant_announce_needs_you")
            .remove("assistant_announce_fails")
            .remove("assistant_announce_done")
            .putBoolean("assistant_speaker", s.speaker)
            .putString("assistant_voice", s.voice)
            .putLong("assistant_quiet_until", s.quietUntil)
            .putString("assistant_login", s.loginId)
            .putString(KEY_MODEL, s.model)
            .putString("assistant_locked_phone", s.lockedPhone.name)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
