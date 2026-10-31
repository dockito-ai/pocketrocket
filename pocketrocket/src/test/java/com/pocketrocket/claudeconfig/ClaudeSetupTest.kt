package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.ModelOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSetupTest {

    // Fake values throughout. A leak is any of these appearing in what the parser keeps.
    private val secrets = listOf("SECRET-ENV", "SECRET-HEADER", "SECRET-HOOK", "SECRET-HELPER", "SECRET-NESTED", "API_TOKEN_NAME")
    private val secretReply = """{
        "effective": {
          "env": {"API_TOKEN_NAME": "SECRET-ENV"},
          "apiKeyHelper": "/bin/SECRET-HELPER",
          "hooks": {"PreToolUse": [{"hooks": [{"type": "command", "command": "SECRET-HOOK"}]}]},
          "headers": {"Authorization": "Bearer SECRET-HEADER"},
          "permissions": {"allow": ["Bash(SECRET-NESTED)"]},
          "model": "claude-opus-5-5"
        },
        "sources": [{"source": "userSettings", "settings": {
          "env": {"API_TOKEN_NAME": "SECRET-ENV"}, "apiKeyHelper": "/bin/SECRET-HELPER", "model": "claude-opus-5-5"
        }}],
        "applied": {"model": "claude-opus-5-5", "effort": "medium", "advisor": null},
        "errors": [{"path": "env", "message": "SECRET-ENV is bad"}]
    }"""

    @Test fun onlyAllowedKeysSurvive() {
        val view = SettingsParser.parse(JSONObject(secretReply))!!
        secrets.forEach { assertFalse(it, view.toString().contains(it)) }
        assertEquals(listOf("Effort", "Model"), view.rows.map { it.label })
        assertEquals(5, view.hiddenCount)
    }

    @Test fun keyNamesOutsideTheListAreNotKept() {
        val view = SettingsParser.parse(JSONObject(secretReply))!!
        listOf("env", "hooks", "apiKeyHelper", "headers", "permissions").forEach { key ->
            assertFalse(key, view.rows.any { it.label.contains(key, ignoreCase = true) })
        }
    }

    @Test fun errorsAreCountedNotRepeated() {
        assertEquals(1, SettingsParser.parse(JSONObject(secretReply))!!.errorCount)
        assertEquals(0, SettingsParser.parse(JSONObject("""{"effective":{}}"""))!!.errorCount)
    }

    @Test fun aSecretInAnAllowedKeyIsHidden() {
        val values = listOf(
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789",
            "https://proxy.example/model?token=abc",
            "a=b",
            "x".repeat(65),
            "line\nline",
        )
        values.forEach { value ->
            val reply = JSONObject().put("effective", JSONObject().put("model", value)).put("sources", JSONArray())
            val view = SettingsParser.parse(reply)!!
            assertEquals(value, SettingsParser.HIDDEN_VALUE, view.rows.single().value)
            assertFalse(value, view.toString().contains(value))
        }
    }

    @Test fun anObjectInAnAllowedKeyIsHidden() {
        val view = SettingsParser.parse(JSONObject("""{"effective":{"language":{"x":"SECRET-NESTED"}}}"""))!!
        assertEquals(SettingsParser.HIDDEN_VALUE, view.rows.single().value)
        assertFalse(view.toString().contains("SECRET-NESTED"))
    }

    @Test fun plainValuesShow() {
        val view = SettingsParser.parse(JSONObject("""{"effective":{"model":"claude-opus-5-5[1m]","alwaysThinkingEnabled":true,"cleanupPeriodDays":30,"language":"हिन्दी"}}"""))!!
        assertEquals(listOf("claude-opus-5-5[1m]", "हिन्दी", "On", "30"), view.rows.map { it.value })
    }

    @Test fun theHighestPrioritySourceNamesAValueWhateverTheOrder() {
        val reply = """{"effective":{"model":"m","language":"l"},"sources":[
            {"source":"flagSettings","settings":{"model":"m"}},
            {"source":"userSettings","settings":{"model":"old","language":"l"}},
            {"source":"somethingNew","settings":{"model":"older"}}
        ]}"""
        val rows = SettingsParser.parse(JSONObject(reply))!!.rows.associateBy { it.label }
        assertEquals("This session", rows.getValue("Model").source)
        assertEquals("Account", rows.getValue("Language").source)
    }

    @Test fun orgValuesAreMarkedManaged() {
        val reply = """{"effective":{"model":"m","outputStyle":"Concise","language":"l","env":{"A":"b"}},"sources":[
            {"source":"userSettings","settings":{"model":"mine","language":"l"}},
            {"source":"policySettings","settings":{"model":"m","outputStyle":"Concise","env":{"A":"b"}}}
        ]}"""
        val view = SettingsParser.parse(JSONObject(reply))!!
        val rows = view.rows.associateBy { it.label }
        assertTrue(rows.getValue("Model").managed)
        assertEquals("Organization", rows.getValue("Model").source)
        assertFalse(rows.getValue("Language").managed)
        assertTrue(view.isManaged("outputStyle"))
        assertFalse(view.isManaged("effortLevel"))
    }

    @Test fun nullValuesAreNotSet() {
        assertTrue(SettingsParser.parse(JSONObject("""{"effective":{"model":null}}"""))!!.rows.isEmpty())
    }

    @Test fun noEffectiveIsNoView() = assertNull(SettingsParser.parse(JSONObject("{}")))

    @Test fun measuredReplyReads() {
        // Throwaway config dir, 2.1.314: a saved effort under its model, a style and effort set for the session.
        val view = SettingsParser.parse(JSONObject("""{"effective":{"modelSettings":{"claude-opus-5-5":{"effortLevel":"low"}},"outputStyle":"Explanatory","effortLevel":"high"},
            "sources":[{"source":"userSettings","settings":{"modelSettings":{"claude-opus-5-5":{"effortLevel":"low"}}}},{"source":"flagSettings","settings":{"outputStyle":"Explanatory","effortLevel":"high"}}],
            "applied":{"model":"claude-opus-5-5","effort":"high","advisor":null,"ultracode":false}}"""))!!
        assertEquals("Explanatory", view.outputStyle)
        assertEquals("high", view.appliedEffort)
        assertEquals("claude-opus-5-5", view.appliedModel)
        assertEquals(1, view.hiddenCount)
        assertEquals("This session", view.rows.single { it.label == "Effort" }.source)
    }

    @Test fun skillsKeepOnlyWhatIsShown() {
        val reply = JSONObject("""{"skills":[
            {"name":"zed","display_name":"Zed","description":"Uses https://x.example/a?key=SECRET-HEADER here","source":"user","tokens":5,"state":"on","advertised":true,"handles":{"h":"SECRET-HOOK"}},
            {"name":"abc","description":"${"d".repeat(400)}","source":"plugin","tokens":7,"state":"off","locked_by":"plugin"},
            {"description":"no name"}
        ]}""")
        val skills = SkillsParser.parse(reply)!!
        assertEquals(listOf("abc", "zed"), skills.map { it.name })
        assertEquals("abc", skills[0].displayName)
        assertEquals(160, skills[0].description!!.length)
        assertEquals("plugin", skills[0].lockedBy)
        assertEquals("Off", skills[0].stateLabel)
        assertEquals("On", skills[1].stateLabel)
        assertEquals("This account", skills[1].sourceLabel)
        secrets.forEach { assertFalse(it, skills.toString().contains(it)) }
    }

    @Test fun anUnknownSkillStateDoesntBreak() {
        assertEquals("Unknown", SkillsParser.parse(JSONObject("""{"skills":[{"name":"a","state":"new"}]}"""))!!.single().stateLabel)
    }

    @Test fun noSkillsListIsNull() = assertNull(SkillsParser.parse(JSONObject("{}")))

    @Test fun effortLevelsFollowTheModelInUse() {
        val models = listOf(
            ModelOption("default", "Default", "", "a", listOf("low", "max")),
            ModelOption("small", "Small", "", "b"),
        )
        assertEquals(listOf("low", "max"), SetupChoices.effortLevels(models, "a"))
        assertTrue(SetupChoices.effortLevels(models, "b").isEmpty())
        assertTrue(SetupChoices.effortLevels(models, null).isEmpty())
    }

    @Test fun modelOptionsReadTheirLevels() {
        val models = ModelOption.listFrom(JSONArray("""[{"value":"opus","supportedEffortLevels":["low","high"]},{"value":"haiku"}]"""))
        assertEquals(listOf("low", "high"), models[0].effortLevels)
        assertTrue(models[1].effortLevels.isEmpty())
    }

    @Test fun stylesAreTheListedOnes() {
        val reply = JSONObject("""{"available_output_styles":["default","Concise","odd=name",5]}""")
        assertEquals(listOf("default", "Concise"), SetupChoices.outputStyles(reply))
        assertTrue(SetupChoices.outputStyles(JSONObject("{}")).isEmpty())
    }

    // The panel.

    private class FakeChannel(private val replies: Map<String, List<ControlReply>>) : ControlChannel {
        val sent = mutableListOf<Pair<String, Map<String, Any>>>()
        override suspend fun request(subtype: String, fields: Map<String, Any>, timeoutMs: Long): ControlReply {
            val index = sent.count { it.first == subtype }
            sent += subtype to fields
            val answers = replies[subtype] ?: return ControlReply.Refused("${ControlReply.UNSUPPORTED}: $subtype")
            return answers[minOf(index, answers.lastIndex)]
        }
        fun asked(subtype: String) = sent.filter { it.first == subtype }
    }

    private class FakeHost(override val live: Boolean, val channel: ControlChannel) : McpHost {
        var opened = 0
        override suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T {
            opened++
            return block(McpConnection(channel, 0L, null))
        }
        override suspend fun freshStatus(serverName: String): McpState? = null
    }

    private fun ok(json: String = "{}") = ControlReply.Success(JSONObject(json))
    private fun settingsWith(style: String? = null, effort: String = "medium", policy: String = "") = ok(
        """{"effective":{${style?.let { "\"outputStyle\":\"$it\"" } ?: ""}},"sources":[$policy],"applied":{"model":"m","effort":"$effort"}}""",
    )
    private val skillList = ok("""{"skills":[{"name":"a","state":"on"}]}""")
    private val styleList = ok("""{"available_output_styles":["default","Concise"]}""")

    private fun setup(live: Boolean, replies: Map<String, List<ControlReply>>): Triple<SetupPanel, FakeChannel, FakeHost> {
        val channel = FakeChannel(replies)
        val host = FakeHost(live, channel)
        return Triple(SetupPanel(host, CoroutineScope(Dispatchers.Unconfined)), channel, host)
    }

    @Test fun aProbeReadsOnceInOneProcessAndNeverWrites() {
        val (panel, channel, host) = setup(false, mapOf("get_skills_dialog" to listOf(skillList), "get_settings" to listOf(settingsWith())))
        panel.refresh()
        val state = panel.state.value
        assertTrue(state.loaded)
        assertEquals(1, host.opened)
        assertEquals("a", state.skills.single().name)
        assertFalse(state.canReloadSkills)
        panel.pickStyle("Concise")
        panel.saveStyleForFolder("Concise")
        panel.pickEffort("high")
        panel.saveEffortForAccount("high")
        panel.reloadSkills()
        assertEquals(setOf("get_skills_dialog", "get_settings"), channel.sent.map { it.first }.toSet())
    }

    @Test fun anOlderVersionHidesWhatItDoesntKnow() {
        val (panel, _, _) = setup(true, emptyMap())
        panel.refresh()
        val state = panel.state.value
        assertTrue(state.skillsUnsupported)
        assertTrue(state.settingsUnsupported)
        assertTrue(state.styles.isEmpty())
        assertFalse(state.busy)
    }

    @Test fun reloadAsksForTheListAgain() {
        val (panel, channel, _) = setup(true, mapOf("reload_skills" to listOf(ok()), "get_skills_dialog" to listOf(skillList)))
        panel.reloadSkills()
        assertEquals(listOf("reload_skills", "get_skills_dialog"), channel.sent.map { it.first })
        assertEquals("a", panel.state.value.skills.single().name)
    }

    @Test fun reloadUnknownToThisVersionGoesAway() {
        val (panel, _, _) = setup(true, emptyMap())
        panel.reloadSkills()
        assertFalse(panel.state.value.canReloadSkills)
    }

    @Test fun pickingAStyleIsSessionOnlyAndReadBack() {
        val (panel, channel, _) = setup(true, mapOf("apply_flag_settings" to listOf(ok()), "get_settings" to listOf(settingsWith("Concise"))))
        panel.pickStyle("Concise")
        val sent = channel.asked("apply_flag_settings").single().second["settings"] as JSONObject
        assertEquals("Concise", sent.getString("outputStyle"))
        assertTrue(channel.asked("update_settings").isEmpty())
        assertTrue(panel.state.value.notice!!.contains("this session only"))
        assertFalse(panel.state.value.noticeIsProblem)
    }

    @Test fun aSuccessThatChangedNothingIsReported() {
        // Measured: a level or style Claude Code can't use still answers success.
        val (panel, _, _) = setup(true, mapOf("apply_flag_settings" to listOf(ok()), "get_settings" to listOf(settingsWith())))
        panel.pickEffort("high")
        assertTrue(panel.state.value.noticeIsProblem)
    }

    @Test fun savingUsesTheOnlyTwoWritesClaudeCodeAllows() {
        val (panel, channel, _) = setup(true, mapOf("update_settings" to listOf(ok()), "get_settings" to listOf(settingsWith())))
        panel.saveStyleForFolder("Concise")
        panel.saveEffortForAccount("high")
        val sent = channel.asked("update_settings").map { it.second }
        assertEquals(listOf("localSettings", "userSettings"), sent.map { it["source"] })
        assertEquals("Concise", (sent[0]["settings"] as JSONObject).getString("outputStyle"))
        assertEquals("high", (sent[1]["settings"] as JSONObject).getString("effortLevel"))
        assertEquals(1, (sent[0]["settings"] as JSONObject).length())
    }

    @Test fun managedValuesAreLeftAlone() {
        val policy = """{"source":"policySettings","settings":{"outputStyle":"Concise","effortLevel":"low"}}"""
        val (panel, channel, _) = setup(true, mapOf("get_settings" to listOf(settingsWith("Concise", policy = policy)), "get_skills_dialog" to listOf(skillList)))
        panel.refresh()
        panel.pickStyle("default")
        panel.saveStyleForFolder("default")
        panel.pickEffort("high")
        panel.saveEffortForAccount("high")
        assertTrue(channel.asked("apply_flag_settings").isEmpty())
        assertTrue(channel.asked("update_settings").isEmpty())
    }

    @Test fun aRefusalIsShownScrubbed() {
        val (panel, _, _) = setup(true, mapOf("apply_flag_settings" to listOf(ControlReply.Refused("failed at https://x.example/a?token=SECRET-HEADER"))))
        panel.pickStyle("Concise")
        assertTrue(panel.state.value.noticeIsProblem)
        assertFalse(panel.state.value.notice!!.contains("SECRET-HEADER"))
    }

    @Test fun aProcessThatWontStartEndsInState() {
        val host = object : McpHost {
            override val live = false
            override suspend fun <T> withChannel(block: suspend (McpConnection) -> T): T = throw IllegalStateException("/secret/path")
            override suspend fun freshStatus(serverName: String): McpState? = null
        }
        val panel = SetupPanel(host, CoroutineScope(Dispatchers.Unconfined))
        panel.refresh()
        val state = panel.state.value
        assertFalse(state.busy)
        assertNotNull(state.settingsProblem)
        assertFalse(state.toString().contains("/secret/path"))
    }
}
