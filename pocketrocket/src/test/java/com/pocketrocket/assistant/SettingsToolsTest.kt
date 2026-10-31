package com.pocketrocket.assistant

import com.pocketrocket.settings.ThemeMode
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSettings : SettingsHost {
    val applied = mutableListOf<SettingChange>()
    val did = mutableListOf<String>()
    var defaultClaude: String? = "personal"
    var defaultGitHub: String? = "shankar"
    var rocketOnDefault = true
    var toolList = listOf(ToolFacts("chromium", "Headless Chrome", installed = false, busy = false))
    var voiceList = listOf(
        VoiceOption("g::en-in-x-a", "English (India)", "en-in-x-a", "en", 400),
        VoiceOption("g::en-us-x-a", "English (United States)", "en-us-x-a", "en", 400),
        VoiceOption("g::en-us-x-b", "English (United States)", "en-us-x-b", "en", 300),
    )
    var rows = listOf(
        row(UpdateComponent.PocketRocket, "1.0", "1.1", true),
        row(UpdateComponent.ClaudeCode, "2.1.0", "2.1.1", true),
        row(UpdateComponent.Git, "2.50", "2.50", false),
    )
    var canInstall = true
    var active = 0
    var checked = false

    override fun facts() = SettingsFacts(ThemeMode.Dark, null, null, "haiku", defaultClaude, defaultGitHub, rocketOnDefault)
    override fun apply(change: SettingChange) { applied += change }
    override suspend fun voices() = voiceList
    override fun rocketModels() = listOf("opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku")
    override fun setDefaultAccount(service: AccountService, id: String) { did += "default $service $id" }
    override fun tools() = toolList
    override fun installTool(id: String) { did += "install $id" }
    override suspend fun updates(check: Boolean): List<UpdateRow> { checked = checked || check; return rows }
    override fun startUpdates(components: List<UpdateComponent>) { did += "update $components" }
    override fun canInstallApp() = canInstall
    override fun activeSessions() = active

    companion object {
        fun row(c: UpdateComponent, installed: String?, latest: String?, available: Boolean) = UpdateRow(c, installed, latest, available, checking = false)
    }
}

class SettingsToolsTest {
    private val host = AssistantToolsTest.Host()
    private val settings get() = host.settings
    private val tools = AssistantTools(host)

    private fun call(name: String, vararg args: Pair<String, Any>): ToolResult = runBlocking {
        tools.call(name, JSONObject().apply { args.forEach { (k, v) -> put(k, v) } })
    }

    private fun names(t: AssistantTools = tools) = t.list().let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }

    // The whole list. A new tool has to be added here on purpose, and be judged against the rule when it is.

    @Test fun theSettingsToolsAreExactlyThese() {
        assertEquals(
            setOf("list_settings", "list_voices", "set_theme", "set_voice", "set_quiet", "set_rocket_model", "ask_to_set_default_account", "ask_to_install_tool", "check_updates", "ask_to_update"),
            SettingsTools(host).tools.map { it.name }.toSet(),
        )
    }

    @Test fun noToolExistsForLoginsSecretsOrSafetyRules() {
        val forbidden = listOf("login", "logout", "signin", "signout", "auth", "token", "secret", "password", "key", "sensitive", "approval", "approve", "lock", "fingerprint", "rule", "project_switch", "enable_project", "disable_project", "hook", "wake", "listen", "assistant_off", "enable", "disable", "remove", "speaker")
        val settingsTools = SettingsTools(host).tools
        settingsTools.forEach { tool ->
            forbidden.forEach { word -> assertFalse("${tool.name} / $word", tool.name.contains(word)) }
            tool.params.forEach { p -> forbidden.forEach { word -> assertFalse("${tool.name}.${p.name} / $word", p.name.contains(word)) } }
        }
        // Nor anywhere else in the catalogue.
        names().forEach { name ->
            listOf("login", "token", "secret", "password", "sensitive", "fingerprint", "locked", "hook").forEach { assertFalse("$name / $it", name.contains(it)) }
        }
        listOf("set_login", "sign_in", "set_locked_phone", "set_sensitive", "add_sensitive", "set_project_enabled", "set_assistant_enabled").forEach {
            assertTrue(it, call(it).isError)
        }
    }

    @Test fun noneOfWhatJustHappensCanRelaxAProtection() {
        // Each case is listed so a new one fails to compile here and gets looked at.
        fun relaxes(change: SettingChange) = when (change) {
            is SettingChange.Theme, is SettingChange.Voice, is SettingChange.Quiet, is SettingChange.RocketModel -> false
        }
        assertFalse(relaxes(SettingChange.Quiet(true)))
    }

    @Test fun theBrainIsToldTheToolsAndTheRefusals() {
        val md = BrainFiles.claudeMd(MemoryBook())
        SettingsTools(host).tools.map { it.name }.forEach { assertTrue(it, md.contains(it)) }
        listOf("signing in or out", "tokens and passwords", "the sensitive list", "approval rules", "the locked-phone rule", "the per-project switches", "turning Rocket off").forEach { assertTrue(it, md.contains(it)) }
        assertTrue(md.contains("Settings"))
    }

    // What just happens.

    @Test fun plainSettingsJustHappenAndSayWhatChanged() {
        val r = call("set_theme", "theme" to "light")
        assertEquals(SettingChange.Theme(ThemeMode.Light), settings.applied.single())
        assertEquals("Theme is now Light.", r.text)
        call("set_quiet", "on" to true)
        call("set_rocket_model", "model" to "Opus")
        call("set_voice", "voice" to "English (India)")
        call("set_voice", "voice" to "default")
        assertEquals(
            listOf(
                SettingChange.Quiet(true),
                SettingChange.RocketModel("opus", "Opus"), SettingChange.Voice("g::en-in-x-a", "English (India)"), SettingChange.Voice(null, "the best voice on this phone"),
            ),
            settings.applied.drop(1),
        )
        assertTrue(host.asked.isEmpty())
        assertEquals(5, host.logged.size)
    }

    @Test fun lockedPhoneTakesAFingerprintForEachOfThose() {
        host.locked = true
        host.answerYes = false
        listOf(
            "set_theme" to arrayOf<Pair<String, Any>>("theme" to "dark"), "set_quiet" to arrayOf<Pair<String, Any>>("on" to true),
            "set_rocket_model" to arrayOf<Pair<String, Any>>("model" to "opus"), "set_voice" to arrayOf<Pair<String, Any>>("voice" to "default"),
        ).forEach { (name, args) -> assertTrue(call(name, *args).text, call(name, *args).text.contains("Not changed")) }
        assertTrue(settings.applied.isEmpty())
        assertTrue(host.logged.isEmpty())
        assertTrue(host.asked.all { it is RocketAction.WhileLocked && SensitiveActions.protectionOf(it) == Protection.Fingerprint })
        host.answerYes = true
        call("set_theme", "theme" to "dark")
        assertEquals(SettingChange.Theme(ThemeMode.Dark), settings.applied.single())
    }

    @Test fun argumentsAreChecked() {
        listOf(
            call("set_theme"), call("set_theme", "theme" to "blue"), call("set_theme", "theme" to 3),
            call("set_quiet"), call("set_quiet", "on" to "yes"), call("set_quiet", "hours" to 2),
            call("set_rocket_model", "model" to "gpt"), call("set_rocket_model", "model" to "--dangerous"), call("set_rocket_model"),
            call("set_voice", "voice" to "klingon"), call("set_voice"),
            call("ask_to_set_default_account", "service" to "dockito", "account" to "x"), call("ask_to_set_default_account", "service" to "github"), call("ask_to_set_default_account", "service" to "github", "account" to "nobody"),
            call("ask_to_install_tool", "tool" to "nmap"), call("ask_to_update", "what" to "linux"),
        ).forEach { assertTrue(it.text, it.isError) }
        assertTrue(settings.applied.isEmpty())
        assertTrue(settings.did.isEmpty())
        assertTrue(host.asked.isEmpty())
    }

    @Test fun rocketsModelIsOnlyWhatTheScreenOffers() {
        assertTrue(call("set_rocket_model", "model" to "default").isError)
    }

    @Test fun aVoiceNameThatFitsSeveralIsAsked() {
        val r = call("set_voice", "voice" to "English (United States)")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("en-us-x-b") && r.text.contains("Ask him"))
        assertTrue(settings.applied.isEmpty())
        call("set_voice", "voice" to "en-us-x-b")
        assertEquals(SettingChange.Voice("g::en-us-x-b", "English (United States)"), settings.applied.single())
    }

    @Test fun listsSayWhatsThere() {
        val all = call("list_settings").text
        listOf("Dark", "best voice on this phone", "haiku", "personal", "shankar", "Headless Chrome: not installed").forEach { assertTrue(it, all.contains(it)) }
        assertTrue(call("list_voices").text.contains("English (India)"))
    }

    // What needs a yes.

    @Test fun defaultAccountsNeedAYes() {
        host.answerYes = false
        assertTrue(call("ask_to_set_default_account", "service" to "github", "account" to "acme").text.contains("Not changed"))
        assertEquals(RocketAction.SetDefaultAccount("GitHub", "acme", "shankar"), host.asked.single())
        assertTrue(settings.did.isEmpty())
        host.answerYes = true
        call("ask_to_set_default_account", "service" to "github", "account" to "acme")
        call("ask_to_set_default_account", "service" to "Claude", "account" to "work")
        assertEquals(listOf("default GitHub g2", "default Claude w"), settings.did)
    }

    @Test fun aDefaultAccountChangeSaysWhenRocketMovesWithIt() {
        assertTrue(call("ask_to_set_default_account", "service" to "claude", "account" to "work").text.contains("new conversation"))
        settings.defaultClaude = "personal"
        settings.rocketOnDefault = false
        assertFalse(call("ask_to_set_default_account", "service" to "claude", "account" to "work").text.contains("new conversation"))
    }

    @Test fun whatsAlreadyTrueDoesntAsk() {
        call("ask_to_set_default_account", "service" to "claude", "account" to "personal")
        settings.toolList = listOf(ToolFacts("chromium", "Headless Chrome", installed = true, busy = false))
        call("ask_to_install_tool", "tool" to "chrome")
        call("ask_to_update", "what" to "git")
        assertTrue(host.asked.isEmpty())
        assertTrue(settings.did.isEmpty())
    }

    @Test fun installingAToolNeedsAYes() {
        host.answerYes = false
        call("ask_to_install_tool", "tool" to "chrome")
        assertEquals(RocketAction.InstallTool("Headless Chrome"), host.asked.single())
        assertTrue(settings.did.isEmpty())
        host.answerYes = true
        val r = call("ask_to_install_tool", "tool" to "chromium")
        assertEquals("install chromium", settings.did.single())
        assertTrue("it doesn't claim it finished", r.text.contains("isn't done"))
    }

    @Test fun updatesNeedAYesAndTheAppGoesLast() {
        host.answerYes = false
        call("ask_to_update", "what" to "all")
        val asked = host.asked.single() as RocketAction.InstallUpdate
        assertEquals("Claude Code and PocketRocket", asked.names)
        assertEquals("Claude Code 2.1.0 to 2.1.1\nPocketRocket 1.0 to 1.1", asked.versions)
        assertEquals("The app restarts", asked.warning)
        assertTrue(settings.did.isEmpty())
        host.answerYes = true
        val r = call("ask_to_update", "what" to "all")
        assertEquals("update [ClaudeCode, PocketRocket]", settings.did.single())
        assertTrue(r.text.contains("isn't done"))
    }

    @Test fun updatingOneAsksAboutOnlyThat() {
        call("ask_to_update", "what" to "claude code")
        val asked = host.asked.single() as RocketAction.InstallUpdate
        assertEquals("Claude Code", asked.names)
        assertNull(asked.warning)
        assertEquals("update [ClaudeCode]", settings.did.single())
    }

    @Test fun updatingTheAppSaysItPausesSessions() {
        settings.active = 2
        call("ask_to_update", "what" to "pocketrocket")
        assertEquals("The app restarts, pausing 2 working sessions", (host.asked.single() as RocketAction.InstallUpdate).warning)
    }

    @Test fun updatingTheAppWithoutAndroidsPermissionPointsToTheScreen() {
        settings.canInstall = false
        val r = call("ask_to_update", "what" to "all")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("Settings › Updates"))
        assertTrue(host.asked.isEmpty())
    }

    @Test fun checkingInstallsNothing() {
        val r = call("check_updates")
        assertTrue(settings.checked)
        assertTrue(r.text, r.text.contains("1.1 is out") && r.text.contains("up to date"))
        assertTrue(settings.did.isEmpty())
        assertTrue(host.asked.isEmpty())
    }

    @Test fun everyOneThatNeedsAYesIsASpokenYes() {
        listOf(RocketAction.SetDefaultAccount("Claude", "a", null), RocketAction.InstallTool("t"), RocketAction.InstallUpdate("n", "v", null)).forEach { action ->
            assertEquals(Protection.SpokenYes, SensitiveActions.protectionOf(action))
            val approval = ConfirmGate.ofAction(action)
            assertTrue(approval.speech, approval.speech.endsWith(ConfirmGate.ASK))
            // On a locked phone a yes by voice or tap still isn't enough.
            assertFalse(ConfirmGate.accepts(approval.protection, Answered.AllowByVoice, locked = true))
            assertFalse(ConfirmGate.accepts(approval.protection, Answered.AllowByTap, locked = true))
        }
    }

    @Test fun theApprovalShowsWhatWillHappen() {
        val a = ConfirmGate.ofAction(RocketAction.SetDefaultAccount("GitHub", "acme", "shankar"))
        assertEquals("acme", a.detail)
        assertEquals("Instead of shankar", a.where)
        assertEquals("Claude Code 2.1.0 to 2.1.1", ConfirmGate.ofAction(RocketAction.InstallUpdate("Claude Code", "Claude Code 2.1.0 to 2.1.1", null)).detail)
    }

    @Test fun debugConfirmReachesTheNewKinds() {
        val debug = object : ToolHost by host { override val debugBuild = true }
        val t = AssistantTools(debug)
        listOf("default_account", "install_tool", "install_update").forEach { runBlocking { t.call("debug_confirm", JSONObject().put("kind", it)) } }
        assertEquals(3, host.asked.size)
        assertTrue(settings.did.isEmpty())
    }

    // By name.

    @Test fun pickByNameIsExactThenPart() {
        val choices = listOf("opus", "opus plan", "haiku")
        assertEquals(listOf("opus"), pickByName("Opus", choices) { listOf(it) })
        assertEquals(listOf("opus plan"), pickByName("plan", choices) { listOf(it) })
        assertEquals(listOf("opus", "opus plan"), pickByName("opu", choices) { listOf(it) })
        assertTrue(pickByName("  ", choices) { listOf(it) }.isEmpty())
    }
}

/** Router's share: said plainly, no Claude. */
class SettingPhrasesTest {
    private fun route(q: String, locked: Boolean = false) = Router.route(q, emptyList(), locked)

    private fun changeOf(q: String) = (route(q).act as? RocketAct.Change)?.change

    @Test fun plainSettingsNeedNoClaude() {
        mapOf(
            "dark mode" to SettingChange.Theme(ThemeMode.Dark),
            "Hey Rocket, switch to light mode." to SettingChange.Theme(ThemeMode.Light),
            "use the system theme" to SettingChange.Theme(ThemeMode.System),
            "change the theme to dark" to SettingChange.Theme(ThemeMode.Dark),
            "go light" to SettingChange.Theme(ThemeMode.Light),
            "go quiet" to SettingChange.Quiet(true),
            "quiet for an hour" to SettingChange.Quiet(true),
            "turn off quiet" to SettingChange.Quiet(false),
        ).forEach { (q, change) ->
            assertEquals(q, change, changeOf(q))
            assertFalse(q, route(q).toBrain)
        }
    }

    @Test fun theReplyIsTheChangesOwnWords() {
        assertEquals("Theme is now Dark.", route("dark mode").reply.text)
    }

    @Test fun aStrayWordDoesntGoQuiet() {
        listOf("quiet", "be quiet", "shh", "dark", "light").forEach { assertNull(it, changeOf(it)) }
    }

    @Test fun anotherLengthOfQuietGoesToTheBrain() {
        assertTrue(route("quiet for two hours").toBrain)
    }

    @Test fun whatIsntPlainStillGoesToTheBrain() {
        listOf("use the indian voice", "update claude code", "make acme the default github account", "don't say when a session is done", "install chrome").forEach { assertTrue(it, route(it).toBrain) }
    }

    @Test fun loginsSecretsAndSafetyRulesGetAPlainRefusal() {
        mapOf(
            "log in to github" to SettingPhrases.NOT_LOGINS,
            "sign out" to SettingPhrases.NOT_LOGINS,
            "what's my github token" to SettingPhrases.NOT_SECRETS,
            "show me the password" to SettingPhrases.NOT_SECRETS,
            "turn off the fingerprint" to SettingPhrases.NOT_SAFETY,
            "stop asking for approval" to SettingPhrases.NOT_SAFETY,
            "don't ask for my fingerprint" to SettingPhrases.NOT_SAFETY,
            "remove force push from the sensitive list" to SettingPhrases.NOT_SAFETY,
            "change the locked phone rule" to SettingPhrases.NOT_SAFETY,
            "turn off rocket" to SettingPhrases.NOT_SAFETY,
        ).forEach { (q, says) ->
            val r = route(q)
            assertEquals(q, says, r.reply.text)
            assertNull(q, r.act)
            assertFalse(q, r.toBrain)
            assertTrue(q, says.contains("Settings") || says == SettingPhrases.NOT_SECRETS)
        }
    }

    @Test fun refusalsDontCatchOrdinaryRequests() {
        listOf("how many tokens has nimbus used", "tell nimbus to remove the approval flow", "what does login-service want", "change the fingerprint screen in nimbus to blue").forEach {
            assertNull(it, SettingPhrases.refusal(it.lowercase()))
        }
    }

    @Test fun announcementsAreNoLongerASetting() {
        assertNull(changeOf("turn off announcements"))
        assertNull(changeOf("announcements on"))
    }
}
