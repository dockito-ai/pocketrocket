package com.pocketrocket.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hook on Rocket's own session (decision 61): a shell in Auto, with the fingerprint and the locked-phone rule kept (decision 57). */
class RocketHookTest {
    private val scratch = listOf("/home/.pocketrocket/assistant/scratch", "/usr/tmp")

    private fun bash(command: String, cwd: String = "/home/.pocketrocket/assistant") =
        JSONObject().put("permission_mode", "auto").put("tool_name", "Bash").put("cwd", cwd).put("tool_input", JSONObject().put("command", command))

    private fun tool(name: String, input: JSONObject = JSONObject()) = JSONObject().put("permission_mode", "auto").put("tool_name", name).put("tool_input", input)

    private fun reason(o: JSONObject): String? = o.optJSONObject("hookSpecificOutput")?.takeIf { it.optString("permissionDecision") == "ask" }?.optString("permissionDecisionReason")

    private fun decide(input: JSONObject, locked: Boolean = false) = reason(AutoModeNet.decideForRocket(input, locked, scratch))

    @Test fun destructiveCommandsAndMessagesTakeTheFingerprint() {
        listOf(
            "git push --force origin main", "git push -f", "git push origin --delete feat/x", "git push origin :feat/x", "git branch -D old",
            "git reset --hard HEAD~1", "git clean -fdx", "rm -rf ~/projects/nimbus", "rm -rf /home/x", "cd ~/p && rm -rf build",
            "gh pr comment 3 -b hi", "gh issue create -t x", "curl -X POST https://hooks.slack.com/x -d '{}'", "bash -c 'git push -f'",
            "echo \$(rm -rf ~)", "rm scratch/../CLAUDE.md", "rm -rf /home/.pocketrocket/assistant/scratch/../notes.md", "rm notes.md", "rm \$HOME/x", "rm *",
        ).forEach { assertEquals(it, AutoModeNet.REASON, decide(bash(it))) }
    }

    @Test fun pushMergeAndANewPrNeedAYes() {
        listOf("git push", "git push origin main", "git -C ~/p push", "gh pr merge 5 --squash", "gh pr create -t T -b B").forEach {
            assertEquals(it, AutoModeNet.NEEDS_YES, decide(bash(it)))
        }
    }

    @Test fun lookupsAndOrdinaryCommandsJustRun() {
        listOf("git status", "gh pr list", "dockito projects", "ls -la", "cat README.md", "git log --oneline -5", "npm test").forEach {
            assertEquals(it, null, decide(bash(it)))
        }
    }

    @Test fun removingItsOwnScratchFilesTakesNothing() {
        assertEquals(null, decide(bash("rm -rf /home/.pocketrocket/assistant/scratch/out")))
        assertEquals(null, decide(bash("rm scratch/a.txt scratch/b.txt")))
        assertEquals(null, decide(bash("rm -f /usr/tmp/x.json")))
        // The folder itself, or anything also outside, still asks.
        assertEquals(AutoModeNet.REASON, decide(bash("rm -rf /home/.pocketrocket/assistant/scratch")))
        assertEquals(AutoModeNet.REASON, decide(bash("rm scratch/a.txt ../x")))
        assertEquals(AutoModeNet.REASON, decide(bash("sudo rm /usr/tmp/x")))
        // From another folder, a relative path isn't scratch.
        assertEquals(AutoModeNet.REASON, decide(bash("rm scratch/a.txt", cwd = "/home/projects/nimbus")))
    }

    @Test fun aLockedPhoneAsksForAnythingThatActs() {
        assertEquals(AutoModeNet.LOCKED, decide(bash("git status"), locked = true))
        assertEquals(AutoModeNet.LOCKED, decide(tool("Write", JSONObject().put("file_path", "/home/x")), locked = true))
        assertEquals(AutoModeNet.LOCKED, decide(tool("Task"), locked = true))
        assertEquals(AutoModeNet.LOCKED, decide(tool("mcp__github__list_issues"), locked = true))
        // Reading and Rocket's own tools don't: each of those checks the lock itself.
        assertEquals(null, decide(tool("Read", JSONObject().put("file_path", "/home/x")), locked = true))
        assertEquals(null, decide(tool("Grep"), locked = true))
        assertEquals(null, decide(tool("mcp__rocket__start_session"), locked = true))
        // Still the fingerprint, not just the lock, for something on the list.
        assertEquals(AutoModeNet.REASON, decide(bash("git push -f"), locked = true))
    }

    @Test fun hisOtherMcpToolsRunIfTheyReadAndAskIfTheyCouldSend() {
        listOf("mcp__claude_ai_Slack__slack_search_public", "mcp__github__get_issue", "mcp__x__list_things", "mcp__linear__searchIssues").forEach {
            val name = it.substringAfterLast("__")
            if (name.startsWith("slack_")) assertEquals(it, AutoModeNet.REASON, decide(tool(it))) else assertEquals(it, null, decide(tool(it)))
        }
        listOf("mcp__claude_ai_Slack__slack_send_message", "mcp__gmail__send_email", "mcp__github__create_issue", "mcp__x__delete_all").forEach {
            assertEquals(it, AutoModeNet.REASON, decide(tool(it)))
        }
    }

    @Test fun theAppsOwnFilesAreNeverJustChanged() {
        assertEquals(AutoModeNet.REASON, decide(bash("sed -i s/true/false/ /data/data/app.pocket/shared_prefs/pocketrocket.xml")))
        assertEquals(AutoModeNet.REASON, decide(tool("Write", JSONObject().put("file_path", "/home/.pocketrocket/assistant/rocket-hook.json"))))
        assertEquals(AutoModeNet.REASON, decide(tool("Edit", JSONObject().put("file_path", "/data/data/app.pocket/databases/x.db"))))
    }

    @Test fun editsAndSubagentsRunWhenUnlocked() {
        assertEquals(null, decide(tool("Write", JSONObject().put("file_path", "/home/.pocketrocket/assistant/scratch/n.md"))))
        assertEquals(null, decide(tool("Task")))
        assertEquals(null, decide(tool("Skill")))
    }

    @Test fun neverSaysAllow() {
        listOf(bash("git status"), bash("rm -rf /"), tool("Write")).forEach {
            assertFalse(AutoModeNet.decideForRocket(it, false, scratch).toString().contains("\"allow\""))
        }
    }

    @Test fun theRocketSettingsPointAtItsOwnPath() {
        val hook = JSONObject(AutoModeNet.settings(1234, "tok", McpServer.ROCKET_HOOK_PATH)).getJSONObject("hooks").getJSONArray("PreToolUse")
            .getJSONObject(0).getJSONArray("hooks").getJSONObject(0)
        assertEquals("http://127.0.0.1:1234/hook/rocket", hook.getString("url"))
    }

    @Test fun theServerSendsRocketsHookToItsOwnRules() = runBlocking {
        val tools = object : ToolCatalog {
            override fun list() = JSONArray()
            override suspend fun call(name: String, arguments: JSONObject) = ToolResult("")
        }
        val server = McpServer(tools, { JSONObject().put("who", "agent") }, CoroutineScope(SupervisorJob() + Dispatchers.IO), "m", "h", rocketHook = { JSONObject().put("who", "rocket") })
        fun post(path: String) = runBlocking {
            server.handle(HttpRequest("POST", path, mapOf("authorization" to "Bearer h", "host" to "127.0.0.1:1"), "{}"))
        }
        assertEquals("rocket", JSONObject(post(McpServer.ROCKET_HOOK_PATH).body).getString("who"))
        assertEquals("agent", JSONObject(post(McpServer.HOOK_PATH).body).getString("who"))
        assertEquals(401, server.handle(HttpRequest("POST", McpServer.ROCKET_HOOK_PATH, mapOf("authorization" to "Bearer m", "host" to "127.0.0.1:1"), "{}")).status)
    }
}
