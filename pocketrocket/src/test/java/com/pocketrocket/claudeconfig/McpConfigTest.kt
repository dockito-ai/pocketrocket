package com.pocketrocket.claudeconfig

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigTest {
    private val helper = "/prefix/lib/libtermux-exec.so"

    private fun config(draft: McpDraft, saved: JSONObject? = null): JSONObject = (McpDrafts.build(draft, saved, helper) as McpBuild.Ready).config

    private fun new(value: String) = Kept.New(value)

    /** Shapes as Claude Code 2.1.314 wrote them, with fake values. */
    private val file = """{"firstStartTime":"x","mcpServers":{
        "api":{"type":"http","url":"https://mcp.example.com/s/PATHSECRET/mcp?token=QUERYSECRET","headers":{"Authorization":"Bearer HEADERSECRET"},"oauth":{"clientId":"cid"}},
        "tool":{"type":"stdio","command":"node","args":["s.js","--key","ARGSECRET"],"env":{"K":"ENVSECRET"}},
        "old":{"command":"node","args":[]},
        "sock":{"type":"ws","url":"ws://x/y"},
        "chrome-devtools":{"type":"stdio","command":"/bin/node","args":["c.js"]},
        "odd":7}}"""
    private val fakes = listOf("PATHSECRET", "QUERYSECRET", "HEADERSECRET", "ARGSECRET", "ENVSECRET")

    @Test fun stdioArguments() {
        val args = McpCommands.addJson("s", config(McpDraft("s", McpTransport.Stdio, "node", Kept.New(listOf("a b.js", "--x")), env = listOf(McpValue("K", new("V"))))))
        assertEquals(listOf("mcp", "add-json", "-s", "user", "s"), args.take(5))
        val sent = JSONObject(args[5])
        assertEquals("stdio", sent.getString("type"))
        assertEquals("node", sent.getString("command"))
        assertEquals("a b.js", sent.getJSONArray("args").getString(0))
        assertEquals("V", sent.getJSONObject("env").getString("K"))
    }

    @Test fun httpAndSseArguments() {
        listOf(McpTransport.Http to "http", McpTransport.Sse to "sse").forEach { (transport, type) ->
            val sent = config(McpDraft("s", transport, url = new("https://x.dev/mcp"), headers = listOf(McpValue("X-Key", new("V")))))
            assertEquals(type, sent.getString("type"))
            assertEquals("https://x.dev/mcp", sent.getString("url"))
            assertEquals("V", sent.getJSONObject("headers").getString("X-Key"))
            assertFalse(sent.has("command"))
        }
    }

    @Test fun everyCommandNamesTheUserScope() {
        assertEquals(listOf("mcp", "remove", "s", "-s", "user"), McpCommands.remove("s"))
    }

    @Test fun npxAndShellGetTheHelperAndNodeDoesnt() {
        assertEquals(helper, config(McpDraft("s", McpTransport.Stdio, "npx")).getJSONObject("env").getString("LD_PRELOAD"))
        assertEquals(helper, config(McpDraft("s", McpTransport.Stdio, "/p/bin/sh")).getJSONObject("env").getString("LD_PRELOAD"))
        assertFalse(config(McpDraft("s", McpTransport.Stdio, "node")).has("env"))
    }

    @Test fun aValueOfTheUsersOwnForTheHelperStays() {
        val sent = config(McpDraft("s", McpTransport.Stdio, "npx", env = listOf(McpValue("LD_PRELOAD", new("/mine.so")))))
        assertEquals("/mine.so", sent.getJSONObject("env").getString("LD_PRELOAD"))
    }

    @Test fun theHelperGoesWhenTheCommandStopsNeedingIt() {
        val saved = JSONObject("""{"type":"stdio","command":"npx","args":[],"env":{"LD_PRELOAD":"$helper"}}""")
        assertFalse(config(McpDraft("s", McpTransport.Stdio, "node", Kept.Saved), saved).has("env"))
        assertEquals(helper, config(McpDraft("s", McpTransport.Stdio, "npx", Kept.Saved), saved).getJSONObject("env").getString("LD_PRELOAD"))
    }

    @Test fun anEditKeepsSavedValuesWithoutTheFormHavingThem() {
        val saved = McpConfigFile.entry(file, "tool")!!
        val sent = config(McpDraft("tool", McpTransport.Stdio, "node", Kept.Saved, env = listOf(McpValue("K", Kept.Saved))), saved)
        assertEquals("ENVSECRET", sent.getJSONObject("env").getString("K"))
        assertEquals("ARGSECRET", sent.getJSONArray("args").getString(2))
    }

    @Test fun anEditCanDropAndReplace() {
        val saved = McpConfigFile.entry(file, "api")!!
        val sent = config(McpDraft("api", McpTransport.Http, url = Kept.Saved, headers = listOf(McpValue("X", new("NEW")))), saved)
        assertFalse(sent.getJSONObject("headers").has("Authorization"))
        assertEquals("NEW", sent.getJSONObject("headers").getString("X"))
        assertTrue(sent.getString("url").contains("PATHSECRET"))
        assertEquals("cid", sent.getJSONObject("oauth").getString("clientId"))
    }

    @Test fun aSavedValueThatIsGoneIsAskedForAgain() {
        val saved = JSONObject("""{"type":"stdio","command":"node"}""")
        assertTrue(McpDrafts.build(McpDraft("s", McpTransport.Stdio, "node", env = listOf(McpValue("K", Kept.Saved))), saved, helper) is McpBuild.Problem)
    }

    @Test fun formProblems() {
        fun bad(draft: McpDraft) = assertNotNull(McpDrafts.problem(draft))
        bad(McpDraft("bad name", McpTransport.Stdio, "node"))
        bad(McpDraft("-flag", McpTransport.Stdio, "node"))
        bad(McpDraft("chrome-devtools", McpTransport.Stdio, "node"))
        bad(McpDraft("s", McpTransport.Stdio, ""))
        bad(McpDraft("s", McpTransport.Http, url = new("ftp://x")))
        bad(McpDraft("s", McpTransport.Http, url = new("https://x y")))
        bad(McpDraft("s", McpTransport.Stdio, "node", env = listOf(McpValue("A B", new("v")))))
        bad(McpDraft("s", McpTransport.Stdio, "node", env = listOf(McpValue("A", new("")))))
        bad(McpDraft("s", McpTransport.Stdio, "node", env = listOf(McpValue("A", new("1")), McpValue("A", new("2")))))
        bad(McpDraft("s", McpTransport.Http, url = new("https://x"), headers = listOf(McpValue("A", new("1\n2")))))
        assertNull(McpDrafts.problem(McpDraft("my_server-1", McpTransport.Stdio, "node", env = listOf(McpValue("A", Kept.Saved)))))
    }

    @Test fun theListHasNoValues() {
        val servers = McpConfigFile.servers(file)!!
        val all = servers.toString()
        assertFalse("a secret reached the list", fakes.any { all.contains(it) })
        val api = servers.first { it.name == "api" }
        assertEquals("mcp.example.com", api.urlHost)
        assertEquals(listOf("Authorization"), api.headerKeys)
        val tool = servers.first { it.name == "tool" }
        assertEquals(3, tool.argCount)
        assertEquals(listOf("K"), tool.envKeys)
    }

    @Test fun whatCanBeChanged() {
        val servers = McpConfigFile.servers(file)!!.associateBy { it.name }
        assertEquals(setOf("api", "tool", "old", "sock", "chrome-devtools"), servers.keys)
        assertTrue(servers.getValue("old").canEdit)
        assertFalse("a websocket entry has no form", servers.getValue("sock").canEdit)
        assertTrue(servers.getValue("sock").canRemove)
        val chrome = servers.getValue("chrome-devtools")
        assertTrue(chrome.managedByTools)
        assertFalse(chrome.canEdit)
        assertFalse(chrome.canRemove)
    }

    @Test fun anUnreadableFileIsntAnEmptyList() {
        assertNull(McpConfigFile.servers("{broken"))
        assertEquals(emptyList<LocalMcpServer>(), McpConfigFile.servers("{}"))
    }

    @Test fun printingADraftShowsNoValues() {
        val draft = McpDraft("s", McpTransport.Http, url = new("https://x/QUERYSECRET"), headers = listOf(McpValue("A", new("HEADERSECRET"))))
        val printed = "$draft ${draft.headers} ${draft.url}"
        assertFalse("a secret was printed", fakes.any { printed.contains(it) })
        assertEquals("Ready", (McpDrafts.build(draft, null, helper) as McpBuild.Ready).toString())
    }

    @Test fun theConfirmationShowsTheCommandAndNoValues() {
        val review = McpPreview.of(McpDraft("s", McpTransport.Stdio, "npx", Kept.New(listOf("-y", "a pkg")), env = listOf(McpValue("K", new("ENVSECRET")))), helper)
        assertEquals("npx -y 'a pkg'", review.what)
        val all = review.toString()
        assertFalse("a secret was shown", all.contains("ENVSECRET"))
        assertTrue(all.contains("K (values hidden)"))
        assertTrue(all.contains("LD_PRELOAD=$helper"))
        assertEquals("https://x/y?a=1", McpPreview.of(McpDraft("s", McpTransport.Http, url = new("https://x/y?a=1")), helper).what)
    }

    @Test fun theConfirmationOfAnEditHidesWhatIsSaved() {
        val stdio = McpPreview.of(McpDraft("s", McpTransport.Stdio, "node", Kept.Saved), helper).what
        assertTrue(stdio.startsWith("node") && stdio.contains("hidden"))
        assertTrue(McpPreview.of(McpDraft("s", McpTransport.Http, url = Kept.Saved), helper).what.contains("hidden"))
    }

    @Test fun measuredStartFailuresAreExplained() {
        assertTrue(StdioAdvice.explain("ENOENT: no such file or directory, posix_spawn 'x'", false, helper)!!.contains("/usr/bin/env"))
        assertTrue(StdioAdvice.explain("Connection closed", false, helper)!!.contains(helper))
        assertFalse(StdioAdvice.explain("Connection closed", true, helper)!!.contains(helper))
        assertNull(StdioAdvice.explain("something else", false, helper))
        assertNull(StdioAdvice.explain(null, false, helper))
    }

    @Test fun warningsBeforeAdding() {
        assertTrue(StdioAdvice.warnings("/a/b/tool", "#!/usr/bin/env node").single().contains("ENOENT"))
        assertTrue(StdioAdvice.warnings("/a/b/tool.js", null).single().contains("node"))
        assertTrue(StdioAdvice.warnings("/a/b/tool", "#!/data/x/bin/env node").isEmpty())
        assertTrue(StdioAdvice.warnings("node", null).isEmpty())
        assertEquals(1, StdioAdvice.warnings("npx", null).size)
        assertEquals("/bin/node", StdioAdvice.scriptPath("node", "/bin"))
        assertEquals("/x/y", StdioAdvice.scriptPath("/x/y", "/bin"))
    }

    @Test fun cliTextHidesWhatItWasGiven() {
        val config = JSONObject("""{"type":"http","url":"https://h.dev/PATHSECRET/x?t=QUERYSECRET","headers":{"A":"Bearer HEADERSECRET"},"env":{"K":"ENVSECRET"}}""")
        val text = CliText.clean("bad https://h.dev/PATHSECRET/x?t=QUERYSECRET Bearer HEADERSECRET ENVSECRET TYPEDSECRET", listOf(config), listOf("TYPEDSECRET"))!!
        assertFalse("a secret survived", (fakes + "TYPEDSECRET").any { text.contains(it) })
        assertTrue(text.startsWith("bad https://h.dev"))
    }

    @Test fun urlCredentialsAreScrubbed() {
        assertFalse(McpParser.scrub("clone https://user:TOKEN1234@github.com/a/b failed")!!.contains("TOKEN1234"))
        assertEquals("https://h.dev", CliText.urlWithoutSecrets("https://u:p@h.dev/a?b=c"))
    }

    @Test fun jsonIsFoundAmongOtherLines() {
        val o = CliJson.objectIn(listOf("human text", "{", "  \"a\": 1", "}", "more"))!!
        assertEquals(1, o.getInt("a"))
        assertEquals(1, CliJson.arrayIn(listOf("note", "[", "{\"a\":1}", "]"))!!.length())
        assertNull(CliJson.arrayIn(listOf("[claude] Claude Code is not installed yet.")))
        assertNull(CliJson.objectIn(listOf("no json")))
    }
}
