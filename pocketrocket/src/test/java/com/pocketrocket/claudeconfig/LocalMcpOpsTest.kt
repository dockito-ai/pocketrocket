package com.pocketrocket.claudeconfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMcpOpsTest {
    private val helper = "/p/lib/libtermux-exec.so"
    private val fakes = listOf("OLDENVSECRET", "NEWENVSECRET", "ARGSECRET")

    private val file = """{"mcpServers":{
        "tool":{"type":"stdio","command":"node","args":["s.js","ARGSECRET"],"env":{"K":"OLDENVSECRET"}},
        "chrome-devtools":{"type":"stdio","command":"node"}}}"""

    /** Answers each command by its first words, and records them. Errors echo what was sent, the worst a CLI could do. */
    private class FakeCli(val answers: (List<String>) -> Int) : ConfigCli {
        val ran = mutableListOf<List<String>>()
        override fun run(arguments: List<String>, stdin: String?, timeoutMs: Long): CliOutput {
            ran += arguments
            val exit = answers(arguments)
            return CliOutput(exit, emptyList(), if (exit == 0) emptyList() else listOf("refused: " + arguments.joinToString(" ")))
        }
    }

    private fun ops(cli: ConfigCli, text: String? = file) = LocalMcpOps(cli, { text }, helper)

    private val edit = McpDraft("tool", McpTransport.Stdio, "node", Kept.Saved, env = listOf(McpValue("K", Kept.New("NEWENVSECRET"))))

    private fun sent(arguments: List<String>) = JSONObject(arguments.last())

    private fun assertClean(text: String) = assertFalse("a secret reached a message", fakes.any { text.contains(it) })

    @Test fun anEditRemovesThenAdds() {
        val cli = FakeCli { 0 }
        assertEquals(McpChange.Done, ops(cli).edit(edit))
        assertEquals(listOf("mcp", "remove", "tool", "-s", "user"), cli.ran[0])
        assertEquals("add-json", cli.ran[1][1])
        assertEquals("NEWENVSECRET", sent(cli.ran[1]).getJSONObject("env").getString("K"))
        assertEquals(2, cli.ran.size)
    }

    @Test fun aRefusedEditPutsTheOldEntryBack() {
        var adds = 0
        val cli = FakeCli { if (it[1] == "add-json") (if (adds++ == 0) 1 else 0) else 0 }
        val change = ops(cli).edit(edit) as McpChange.Restored
        assertClean(change.message)
        assertEquals(3, cli.ran.size)
        val restored = sent(cli.ran[2])
        assertEquals("OLDENVSECRET", restored.getJSONObject("env").getString("K"))
        assertEquals("ARGSECRET", restored.getJSONArray("args").getString(1))
    }

    @Test fun aFailedRestoreSaysSo() {
        val cli = FakeCli { if (it[1] == "add-json") 1 else 0 }
        val change = ops(cli).edit(edit) as McpChange.Lost
        assertClean(change.message)
    }

    @Test fun whenTheRemoveFailsNothingElseRuns() {
        val cli = FakeCli { 1 }
        val change = ops(cli).edit(edit) as McpChange.Failed
        assertEquals(1, cli.ran.size)
        assertClean(change.message)
    }

    @Test fun aProblemWithTheFormStopsBeforeTheRemove() {
        val cli = FakeCli { 0 }
        val bad = McpDraft("tool", McpTransport.Stdio, "node", env = listOf(McpValue("GONE", Kept.Saved)))
        assertTrue(ops(cli).edit(bad) is McpChange.Failed)
        assertTrue(cli.ran.isEmpty())
    }

    @Test fun anEntryThatWentAwayIsntEdited() {
        val cli = FakeCli { 0 }
        assertTrue(ops(cli, "{}").edit(edit) is McpChange.Failed)
        assertTrue(ops(cli, null).edit(edit) is McpChange.Failed)
        assertTrue(cli.ran.isEmpty())
    }

    @Test fun chromeDevtoolsCantBeChanged() {
        val cli = FakeCli { 0 }
        assertTrue(ops(cli).remove("chrome-devtools") is McpChange.Failed)
        assertTrue(ops(cli).edit(McpDraft("chrome-devtools", McpTransport.Stdio, "node")) is McpChange.Failed)
        assertTrue(ops(cli).add(McpDraft("chrome-devtools", McpTransport.Stdio, "node")) is McpChange.Failed)
        assertTrue(cli.ran.isEmpty())
    }

    @Test fun aRefusedAddHidesTheTypedValues() {
        val cli = FakeCli { 1 }
        val change = ops(cli).add(McpDraft("n", McpTransport.Stdio, "node", Kept.New(listOf("ARGSECRET")), env = listOf(McpValue("K", Kept.New("NEWENVSECRET"))))) as McpChange.Failed
        assertClean(change.message)
        assertTrue(change.message.startsWith("refused"))
    }

    @Test fun addingPassesNothingOnStdinAndValuesOnlyInTheConfig() {
        val cli = FakeCli { 0 }
        ops(cli).add(McpDraft("n", McpTransport.Http, url = Kept.New("https://x.dev/m")))
        assertEquals(listOf("mcp", "add-json", "-s", "user", "n"), cli.ran.single().take(5))
    }

    @Test fun anUnreadableConfigIsAProblem() {
        val listing = LocalMcpOps(FakeCli { 0 }, { throw IllegalStateException("x") }, helper).list()
        assertNull(listing.servers)
        assertTrue(listing.problem != null)
        assertEquals(emptyList<LocalMcpServer>(), ops(FakeCli { 0 }, null).list().servers)
    }

    private val local = LocalMcpServer("tool", "stdio", command = "node")

    private fun row(status: McpServer?, checkedAt: Long?, changedAt: Long? = null, checking: Boolean = false) = LocalMcpRows.of(
        listOf(local), listOfNotNull(status), checkedAt, if (changedAt == null) emptyMap() else mapOf("tool" to changedAt), checking, helper,
    ).single()

    @Test fun onlyStatusSaysItWorks() {
        val connected = McpServer("tool", McpState.Connected, scope = "user", toolCount = 2)
        assertEquals("Not checked yet", row(null, null).line)
        assertEquals("Checking whether it starts", row(null, null, checking = true).line)
        assertEquals("Connected · 2 tools", row(connected, 10).line)
        val stale = row(connected, 10, changedAt = 20)
        assertNull(stale.status)
        assertFalse(stale.line.contains("Connected"))
        assertEquals("Connected · 2 tools", row(connected, 30, changedAt = 20).line)
    }

    @Test fun aFailedServerShowsItsErrorAndWhatItMeans() {
        val failed = row(McpServer("tool", McpState.Failed, scope = "user", error = "ENOENT: no such file"), 10)
        assertTrue(failed.line.contains("ENOENT"))
        assertTrue(failed.help!!.contains("interpreter"))
    }

    @Test fun theSameNameInAFolderDoesntCount() {
        assertNull(row(McpServer("tool", McpState.Connected, scope = "project"), 10).status)
    }

    @Test fun thePanelAsksForARealCheckAfterAChangeNotAfterARefusal() {
        var checks = 0
        var saved = 0
        val cli = FakeCli { if (it[1] == "add-json") 1 else 0 }
        lateinit var panel: LocalMcpPanel
        runBlocking {
            panel = LocalMcpPanel(ops(cli), this, Any(), { checks++ }, Dispatchers.Unconfined, now = { 5L })
            panel.add(McpDraft("n", McpTransport.Stdio, "node")) { saved++ }
        }
        assertEquals(0, checks)
        assertEquals(0, saved)
        assertEquals(ServerNotice.Kind.Problem, panel.state.value.notice?.kind)

        val fine = FakeCli { 0 }
        runBlocking {
            panel = LocalMcpPanel(ops(fine), this, Any(), { checks++ }, Dispatchers.Unconfined, now = { 5L })
            panel.remove("tool")
        }
        assertEquals(1, checks)
        assertEquals(5L, panel.state.value.changedAt["tool"])
        assertNull(panel.state.value.busy)
    }
}
