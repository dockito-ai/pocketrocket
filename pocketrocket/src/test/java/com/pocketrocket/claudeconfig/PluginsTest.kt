package com.pocketrocket.claudeconfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies as measured on Claude Code 2.1.314 with a throwaway marketplace and fake values, 30 Oct 2026. */
class PluginsTest {
    private fun out(text: String, exit: Int = 0, err: String = "") = CliOutput(exit, text.lines(), err.lines().filter { it.isNotEmpty() })

    private val list = """[
      {"id":"p1@tmpmp","version":"1.0.0","scope":"user","enabled":true,"installPath":"/x/SECRETPATH","hasUserConfig":true,"projectEnabled":false,"somethingNew":{"a":1}},
      {"id":"p2@tmpmp","scope":"user","enabled":false,"mcpServers":{"thing":{"type":"http","url":"http://h/x?token=PLUGURL","headers":{"Authorization":"Bearer PLUGHDR"}}}},
      {"id":"design@org","version":"2","scope":"synced","enabled":true},
      {"id":"odd@m","scope":"newscope"},
      {"noId":true}, 7
    ]"""

    private val form = """{"pluginId":"p1@tmpmp","displayName":"p1",
      "schema":{"api_token":{"type":"string","title":"API token","description":"tok","required":true,"sensitive":true},
                "region":{"type":"string","title":"Region","description":"where","default":"eu"},
                "my_api_key":{"type":"string","title":"Key"},
                "on":{"type":"boolean","title":"On"},"odd":3},
      "inputs":{"api_token":"LEAKEDSENSITIVE","region":"us","my_api_key":"LEAKEDBYNAME","on":"false"},
      "choices":{"on":["true","false"]},"configured":["api_token","region"],"unconfigured":["on"]}"""

    @Test fun theListKeepsOnlyWhatIsSafe() {
        val plugins = PluginParser.list(out(list))!!
        assertEquals(listOf("p1@tmpmp", "p2@tmpmp", "design@org", "odd@m"), plugins.map { it.id })
        val all = plugins.toString()
        listOf("PLUGURL", "PLUGHDR", "SECRETPATH").forEach { assertFalse("a value survived", all.contains(it)) }
        assertEquals(listOf("thing"), plugins[1].mcpServers)
        assertEquals("p1", plugins[0].name)
        assertEquals("tmpmp", plugins[0].marketplace)
        assertEquals(true, plugins[0].hasOptions)
        assertNull(plugins[3].enabled)
    }

    @Test fun aListThatIsntOneIsNull() {
        assertNull(PluginParser.list(out("not json", 1)))
        assertNull(PluginParser.list(out("[claude] Claude Code is not installed yet.", 127)))
        assertEquals(emptyList<PluginInfo>(), PluginParser.list(out("[]")))
    }

    @Test fun syncedIsReadOnly() {
        val synced = PluginInfo("design@org", scope = "synced")
        assertTrue(synced.readOnly)
        assertTrue(synced.readOnlyReason!!.contains("organization"))
        assertFalse(PluginInfo("a@b", scope = "user").readOnly)
        assertTrue(PluginInfo("a@b", scope = "project").readOnly)
        assertTrue("unknown scopes aren't touched", PluginInfo("a@b", scope = "new").readOnly)
        assertTrue(PluginInfo("a@b").readOnly)
    }

    @Test fun thePanelRunsNothingForASyncedPlugin() {
        val cli = FakeCli { CliOutput(0, listOf("[]"), emptyList()) }
        runBlocking {
            val panel = PluginPanel(cli, this, Any(), Dispatchers.Unconfined)
            val synced = PluginInfo("design@org", scope = "synced")
            panel.setEnabled(synced, false)
            panel.uninstall(synced)
            panel.update(synced)
        }
        assertTrue(cli.ran.isEmpty())
    }

    @Test fun commandsNameTheUserScopeAndNeverPassYes() {
        val all = listOf(
            PluginCommands.enable("a@b"), PluginCommands.disable("a@b"), PluginCommands.uninstall("a@b"),
            PluginCommands.install("a@b"), PluginCommands.update("a@b"),
        )
        all.forEach { assertEquals(listOf("-s", "user", "--json"), it.takeLast(3)) }
        assertFalse((all + listOf(PluginCommands.marketplaceAdd("x"), PluginCommands.marketplaceRemove("x"))).flatten().contains("-y"))
        assertEquals(listOf("plugin", "configure", "a@b", "--json", "--values-stdin"), PluginCommands.configureSave("a@b"))
    }

    @Test fun aDeclaredCommandIsAcceptedOnlyByItsHash() {
        val sha = "c4d608d807cc9e4b3e436745292d061ab6d78ea5819a873c10cce88e8ab8c119"
        assertEquals(listOf("--accept-command", sha), PluginCommands.install("a@b", ShownCommand("echo hi", sha)).takeLast(2))
        assertFalse(PluginCommands.install("a@b", ShownCommand("echo hi", "--yes")).contains("--yes"))
    }

    @Test fun whatLooksLikeAFlagIsntAnArgument() {
        assertFalse(PluginCommands.isSafeArgument("--marketplace"))
        assertFalse(PluginCommands.isSafeArgument("a b"))
        assertFalse(PluginCommands.isSafeArgument(" "))
        assertTrue(PluginCommands.isSafeArgument("owner/repo"))
    }

    @Test fun aRefusedInstallShowsItsCommand() {
        val output = out(
            """"cmdp" is installed by running a command from marketplace "tmpmp" on this machine:
  echo hi
{"command":"install","outcome":"failed","plugin":"cmdp@tmpmp","message":"not run","failureCode":"command_source_refused","shownCommand":{"kind":"command_source","pluginId":"cmdp@tmpmp","command":"echo hi","mode":"copy","sha256":"c4d608d807cc9e4b3e436745292d061ab6d78ea5819a873c10cce88e8ab8c119"}}""",
            1,
        )
        val outcome = PluginParser.outcome(output, "x")
        assertFalse(outcome.ok)
        assertEquals("command_source_refused", outcome.failureCode)
        assertEquals("echo hi", outcome.shownCommand!!.command)
        assertEquals("not run", outcome.message)
    }

    @Test fun aHashThatIsntOneIsntOffered() {
        val outcome = PluginParser.outcome(out("""{"outcome":"failed","shownCommand":{"command":"x","sha256":"--yes"}}""", 1), "fallback")
        assertNull(outcome.shownCommand)
    }

    @Test fun failedCommandsKeepTheirRealText() {
        val json = PluginParser.outcome(out("""{"outcome":"failed","message":"Marketplace 'nope' not found","failureCode":"not_configured"}""", 1, "✘ also this"), "x")
        assertEquals("Marketplace 'nope' not found", json.message)
        val plain = PluginParser.outcome(out("", 1, "No installed plugin has the id"), "x")
        assertFalse(plain.ok)
        assertEquals("No installed plugin has the id", plain.message)
        assertEquals("fallback", PluginParser.outcome(out("", 1), "fallback").message)
        assertTrue(PluginParser.outcome(CliOutput(-1, emptyList(), emptyList(), timedOut = true), "x").message.contains("too long"))
    }

    @Test fun anExitOfZeroWithOutcomeFailedIsAFailure() {
        assertFalse(PluginParser.outcome(out("""{"outcome":"failed","message":"no"}"""), "x").ok)
        assertTrue(PluginParser.outcome(out("""{"outcome":"ok","message":"yes"}"""), "x").ok)
    }

    @Test fun errorTextLosesTokensInUrls() {
        val outcome = PluginParser.outcome(out("""{"outcome":"failed","message":"clone https://u:CLONETOKEN@git.dev/a?b=QUERYTOKEN failed"}""", 1), "x")
        assertFalse(outcome.message.contains("CLONETOKEN"))
        assertFalse(outcome.message.contains("QUERYTOKEN"))
    }

    @Test fun anOptionsFormHasNoSecretValues() {
        val parsed = PluginParser.form(out(form))!!
        assertEquals(setOf("api_token", "region", "my_api_key", "on"), parsed.options.map { it.key }.toSet())
        val byKey = parsed.options.associateBy { it.key }
        assertTrue(byKey.getValue("api_token").secret)
        assertNull(byKey.getValue("api_token").current)
        assertTrue(byKey.getValue("api_token").configured)
        assertTrue("named like a secret, not marked as one", byKey.getValue("my_api_key").secret)
        assertNull(byKey.getValue("my_api_key").current)
        assertEquals("us", byKey.getValue("region").current)
        assertEquals(listOf("true", "false"), byKey.getValue("on").choices)
        assertFalse("a secret was parsed", parsed.options.toString().contains("LEAKED"))
    }

    @Test fun onlyChangedValuesAreSent() {
        val parsed = PluginParser.form(out(form))!!
        val changed = PluginValues.changed(parsed, mapOf("api_token" to "", "region" to "us", "on" to "true", "my_api_key" to "NEW"))
        assertEquals(mapOf("on" to "true", "my_api_key" to "NEW"), changed)
        assertEquals("""{"k":"v"}""", PluginValues.stdin(mapOf("k" to "v")))
        assertNotNull(PluginValues.problem(parsed, mapOf("on" to "a\nb")))
        assertNull(PluginValues.problem(parsed, mapOf("on" to "ab")))
    }

    @Test fun savedOptionsAndRefusals() {
        assertTrue(PluginParser.saved(out("""{"pluginId":"p","saved":["a"],"unconfigured":[]}"""), emptyList()).ok)
        val refused = PluginParser.saved(out("""{"refused":{"option":"cnt","message":"Count takes a number, not TYPEDVALUE"}}""", 1, "Failed to save TYPEDVALUE"), listOf("TYPEDVALUE"))
        assertFalse(refused.ok)
        assertTrue(refused.message.startsWith("Count takes a number"))
        assertFalse(refused.message.contains("TYPEDVALUE"))
        val plain = PluginParser.saved(out("", 1, "bad TYPEDVALUE"), listOf("TYPEDVALUE"))
        assertFalse(plain.message.contains("TYPEDVALUE"))
    }

    @Test fun saveGoesOnStdinAndNotInTheArguments() {
        val cli = FakeCli { CliOutput(0, listOf("""{"saved":["region"]}"""), emptyList()) }
        val parsed = PluginParser.form(out(form))!!
        runBlocking {
            val panel = PluginPanel(cli, this, Any(), Dispatchers.Unconfined)
            panel.saveForm(parsed, mapOf("api_token" to "TYPEDSECRET"))
        }
        assertEquals(PluginCommands.configureSave("p1@tmpmp"), cli.ran.first())
        assertEquals("""{"api_token":"TYPEDSECRET"}""", cli.stdin.first())
        assertFalse(cli.ran.flatten().any { it.contains("TYPEDSECRET") })
    }

    @Test fun detailsGiveTheCost() {
        val details = PluginParser.details(
            "p1@tmpmp",
            out("p1 1.0.0\n  Source: p1@tmpmp\n\nProjected token cost\n  Always-on:   ~7 tok   added to every session\n"),
        )!!
        assertEquals("~7 tok   added to every session", details.alwaysOn)
        assertTrue(details.text.startsWith("p1 1.0.0"))
        assertNull(PluginParser.details("x", out("", 1, "not found")))
        assertNull(PluginParser.details("x", out("no cost here"))!!.alwaysOn)
    }

    @Test fun whatIsAvailableSaysWhereItComesFrom() {
        val rows = PluginParser.available(
            out(
                """{"installed":[],"available":[
                {"pluginId":"p1@m","name":"p1","description":"d","marketplaceName":"m","version":"1","source":"./plugins/p1"},
                {"pluginId":"c@m","name":"c","marketplaceName":"m","source":{"source":"command","command":"echo hi"}},
                {"pluginId":"s@o","name":"s","marketplaceName":"o","source":{"source":"url","url":"https://t:TOKEN@github.com/o/s.git?x=QUERY","sha":"8"}},
                {"pluginId":"u@o","marketplaceName":"o","source":{"source":"newkind"}},
                {"pluginId":"n@o"},{"name":"noid"}]}""",
            ),
        )!!
        assertEquals(5, rows.size)
        assertEquals("./plugins/p1 in marketplace m", rows[0].source)
        assertEquals("Runs a command on this phone: echo hi", rows[1].source)
        assertTrue(rows[2].source.contains("github.com/o/s.git"))
        assertFalse(rows[2].source.contains("TOKEN"))
        assertFalse(rows[2].source.contains("QUERY"))
        assertEquals("newkind", rows[3].source)
        assertEquals("Not reported", rows[4].source)
    }

    @Test fun searchLeavesOutWhatIsInstalled() {
        val rows = listOf(AvailablePlugin("a@m", "a", "first thing", "m", null, "s"), AvailablePlugin("b@m", "b", "Second", "m", null, "s"))
        assertEquals(listOf("b@m"), PluginCatalog.search(rows, listOf(PluginInfo("a@m")), "").map { it.id })
        assertEquals(listOf("b@m"), PluginCatalog.search(rows, emptyList(), "second").map { it.id })
        assertEquals(PluginCatalog.MAX_SHOWN, PluginCatalog.search(List(60) { AvailablePlugin("$it@m", "x", null, "m", null, "s") }, emptyList(), "").size)
    }

    @Test fun marketplaces() {
        val rows = PluginParser.marketplaces(out("""[{"name":"tmpmp","source":"directory","path":"/x/mp"},{"source":"x"},{"name":"h","source":{"source":"github","repo":"o/r"}}]"""))!!
        assertEquals("tmpmp", rows[0].name)
        assertEquals("directory: /x/mp", rows[0].source)
        assertEquals(2, rows.size)
        assertEquals("github: o/r", rows[1].source)
    }

    @Test fun theSourceBeforeInstalling() {
        val mine = listOf(MarketplaceInfo("m", "github: o/r"))
        assertTrue(PluginCatalog.sourceOf("a@m", emptyList(), mine).contains("github: o/r"))
        assertEquals("s", PluginCatalog.sourceOf("a@m", listOf(AvailablePlugin("a@m", "a", null, "m", null, "s")), mine))
    }

    @Test fun anInstallStoppedAtACommandAsksAndAcceptsOnce() {
        val sha = "c4d608d807cc9e4b3e436745292d061ab6d78ea5819a873c10cce88e8ab8c119"
        val refusal = """{"outcome":"failed","message":"not run","shownCommand":{"command":"echo hi","sha256":"$sha"}}"""
        val cli = FakeCli { args ->
            when {
                args.take(2) == listOf("plugin", "list") -> CliOutput(0, listOf("[]"), emptyList())
                "--accept-command" in args -> CliOutput(0, listOf("""{"outcome":"ok","message":"Installed."}"""), emptyList())
                else -> CliOutput(1, listOf(refusal), emptyList())
            }
        }
        lateinit var panel: PluginPanel
        runBlocking {
            panel = PluginPanel(cli, this, Any(), Dispatchers.Unconfined)
            panel.install("c@m")
        }
        val asked = panel.state.value.toAccept!!
        assertEquals("echo hi", asked.shown.command)
        assertTrue("nothing accepted yet", cli.ran.none { "--accept-command" in it })
        runBlocking {
            panel = PluginPanel(cli, this, Any(), Dispatchers.Unconfined)
            panel.accept(asked)
        }
        assertTrue(cli.ran.last { it[1] == "install" }.contains(sha))
        assertNull(panel.state.value.toAccept)
        assertEquals(ServerNotice.Kind.Info, panel.state.value.notice?.kind)
    }

    @Test fun aFailedCommandDoesntThrowOrLoseTheList() {
        val cli = FakeCli { CliOutput(1, emptyList(), listOf("boom")) }
        lateinit var panel: PluginPanel
        runBlocking {
            panel = PluginPanel(cli, this, Any(), Dispatchers.Unconfined)
            panel.load()
        }
        assertEquals("boom", panel.state.value.problem)
        assertTrue(panel.state.value.loaded)
        assertNull(panel.state.value.busy)
    }

    private class FakeCli(val answer: (List<String>) -> CliOutput) : ConfigCli {
        val ran = mutableListOf<List<String>>()
        val stdin = mutableListOf<String?>()
        override fun run(arguments: List<String>, stdin: String?, timeoutMs: Long): CliOutput {
            ran += arguments
            this.stdin += stdin
            return answer(arguments)
        }
    }
}
