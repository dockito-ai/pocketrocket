package com.pocketrocket.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeNetTest {
    private fun input(mode: String, command: String, tool: String = "Bash") =
        JSONObject().put("permission_mode", mode).put("tool_name", tool).put("tool_input", JSONObject().put("command", command))

    private fun asks(o: JSONObject) = o.optJSONObject("hookSpecificOutput")?.optString("permissionDecision") == "ask"

    @Test fun asksForSensitiveCommandsInAuto() {
        listOf("git push --force origin main", "rm -rf build", "git reset --hard", "gh pr close 3", "cd x && git branch -D y", "bash deploy.sh").forEach {
            assertTrue(it, asks(AutoModeNet.decide(input("auto", it), enabled = true)))
        }
        assertTrue(asks(AutoModeNet.decide(input("bypassPermissions", "rm x"), enabled = true)))
    }

    @Test fun leavesEverythingElseAlone() {
        assertEquals(0, AutoModeNet.decide(input("auto", "npm test"), enabled = true).length())
        assertEquals(0, AutoModeNet.decide(input("auto", "git push origin main"), enabled = true).length())
        // Other modes already ask.
        assertEquals(0, AutoModeNet.decide(input("default", "rm x"), enabled = true).length())
        assertEquals(0, AutoModeNet.decide(input("acceptEdits", "rm x"), enabled = true).length())
        // An unrecognised tool stays as it was, or every browser step in Auto would stop.
        assertEquals(0, AutoModeNet.decide(input("auto", "rm x", tool = "mcp__chrome__click"), enabled = true).length())
        assertEquals(0, AutoModeNet.decide(input("auto", "rm x"), enabled = false).length())
        assertEquals(0, AutoModeNet.decide(JSONObject(), enabled = true).length())
    }

    @Test fun neverSaysAllow() {
        // "allow" would skip Auto's own checks too. It only ever says "ask" or nothing.
        assertFalse(AutoModeNet.decide(input("auto", "rm x"), enabled = true).toString().contains("\"allow\""))
    }

    @Test fun aScriptGetsPastIt() {
        // Why Settings says it's a net and not a guarantee.
        assertEquals(0, AutoModeNet.decide(input("auto", "./deploy.sh"), enabled = true).length())
        assertEquals(0, AutoModeNet.decide(input("auto", "python3 tidy.py"), enabled = true).length())
    }

    @Test fun settingsAreAnHttpHookWithItsToken() {
        val hook = JSONObject(AutoModeNet.settings(1234, "tok")).getJSONObject("hooks").getJSONArray("PreToolUse").getJSONObject(0).getJSONArray("hooks").getJSONObject(0)
        assertEquals("http", hook.getString("type"))
        assertEquals("http://127.0.0.1:1234/hook", hook.getString("url"))
        assertEquals("Bearer tok", hook.getJSONObject("headers").getString("Authorization"))
    }
}
