package com.pocketrocket.sessions.demo

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.transport.FakeLines
import com.pocketrocket.sessions.transport.FakeScript
import com.pocketrocket.sessions.transport.FakeStep
import com.pocketrocket.sessions.transport.FakeStep.Emit
import org.json.JSONObject

/**
 * Hand-written conversations for trying the session UI without a Claude login. Each one ends in a
 * different state so the grid shows them all: waiting for permission, working, done, error.
 */
enum class DemoConversation(val folder: String, val prompt: String?, val profile: ClaudeProfile = ClaudeProfile("personal")) {

    /** Reads, then asks to run the tests and waits for Allow. */
    Permission("~/code/pocketrocket", "Run the unit tests and fix anything that fails") {
        override fun turns(lines: FakeLines): List<List<FakeStep>> {
            val (readId, read) = lines.toolUse("Read", JSONObject().put("file_path", "/data/data/app.pocket/files/home/code/pocketrocket/build.gradle"))
            val (testId, test) = lines.toolUse("Bash", JSONObject().put("command", "./gradlew :pocketrocket:testDebugUnitTest").put("description", "Run the unit tests"))
            return listOf(
                listOf(
                    Emit(400, lines.init()),
                    Emit(900, lines.text("I'll check how the tests are set up, then run them.")),
                    Emit(700, read),
                    Emit(500, lines.toolResult(readId, "plugins {\n    id \"com.android.library\"\n    id \"org.jetbrains.kotlin.android\"\n}\n...")),
                    Emit(600, test),
                    FakeStep.AskPermission(
                        delayMs = 300,
                        requestId = "demo-perm-1",
                        line = lines.permission("demo-perm-1", "Bash", JSONObject().put("command", "./gradlew :pocketrocket:testDebugUnitTest").put("description", "Run the unit tests"), testId, allowRule = "./gradlew :pocketrocket:testDebugUnitTest"),
                        onAllow = listOf(
                            Emit(1800, lines.toolResult(testId, "> Task :pocketrocket:testDebugUnitTest\n\nStreamJsonParserTest > parsesPermissionRequest PASSED\nSessionReducerTest > resultEndsTurn FAILED\n\n42 tests completed, 1 failed")),
                            Emit(900, lines.text("One failure: `SessionReducerTest > resultEndsTurn`. The reducer keeps the permission card after the turn ends. Clearing `permission` on **result** fixes it:\n\n```kotlin\nval ended = state.copy(permission = null)\n```\n\nAll **42 tests** pass now.")),
                            Emit(300, lines.result("All 42 tests pass.")),
                        ),
                        onDeny = listOf(
                            Emit(400, lines.toolResult(testId, "The user doesn't want to proceed with this tool use.", isError = true)),
                            Emit(700, lines.text("Okay, I won't run the tests. Tell me if you'd rather I just read through them.")),
                            Emit(300, lines.result("Stopped before running the tests.")),
                        ),
                    ),
                ),
            )
        }
    },

    /** Keeps working for a while: searching, reading, editing. */
    Working("~/code/dockito-web", "Add dark mode to the settings page", ClaudeProfile("work", "/data/data/app.pocket/files/home/.pocketrocket/profiles/work")) {
        override fun turns(lines: FakeLines): List<List<FakeStep>> {
            val steps = mutableListOf<FakeStep>(
                Emit(500, lines.init()),
                Emit(1200, lines.text("I'll send two agents ahead: one maps the theme code, the other checks the dark colours' contrast.")),
            )
            steps += twoAgents(lines)
            steps += Emit(1200, lines.text("Both are back. Let me find where the settings page and the theme live."))
            val calls = listOf(
                "Grep" to JSONObject().put("pattern", "ThemeProvider"),
                "Read" to JSONObject().put("file_path", "/home/code/dockito-web/src/settings/SettingsPage.tsx"),
                "Read" to JSONObject().put("file_path", "/home/code/dockito-web/src/theme/tokens.ts"),
                "Edit" to JSONObject().put("file_path", "/home/code/dockito-web/src/theme/tokens.ts"),
                "Edit" to JSONObject().put("file_path", "/home/code/dockito-web/src/settings/SettingsPage.tsx"),
                "Bash" to JSONObject().put("command", "npm run typecheck"),
            )
            calls.forEach { (name, input) ->
                val (id, line) = lines.toolUse(name, input)
                steps += Emit(2200, line)
                steps += Emit(2600, lines.toolResult(id, "ok"))
            }
            steps += Emit(1500, lines.text("The tokens now have a dark set and the settings page has a theme switch. Running the type check once more."))
            val (id, line) = lines.toolUse("Bash", JSONObject().put("command", "npm test -- settings"))
            steps += Emit(1500, line)
            steps += Emit(120_000, lines.toolResult(id, "PASS src/settings/SettingsPage.test.tsx"))
            steps += Emit(500, lines.result("Dark mode added."))
            return listOf(steps)
        }
    },

    /**
     * A short question with an answer. A background subagent it started goes on for a while after the turn ends, so
     * the card reads "1 agent running" until it finishes and only then Done (decision 79).
     */
    Done("~/notes", "What's the difference between git rebase and merge? Short.") {
        override fun turns(lines: FakeLines): List<List<FakeStep>> {
            val task = "Find rebase and merge examples in my notes"
            val (agentId, agent) = lines.toolUse("Agent", JSONObject().put("description", task).put("subagent_type", "Explore").put("prompt", "Look through ~/notes for examples of rebase and merge.").put("run_in_background", true))
            return listOf(
                listOf(
                    Emit(300, lines.init()),
                    Emit(600, agent),
                    Emit(400, lines.agentStarted(agentId, "demo-agent-1", task)),
                    Emit(300, lines.toolResult(agentId, "Async agent launched successfully.\nagentId: demo-agent-1")),
                    Emit(900, lines.text("**Merge** joins two branches with a new merge commit. History stays exactly as it happened.\n\n**Rebase** replays your commits on top of the other branch, so history is a straight line, but the commits get new ids.\n\n- Shared branch: merge\n- Your own branch before a PR: rebase\n\nAn agent is still looking for examples in your notes.")),
                    Emit(200, lines.result("done")),
                    Emit(45_000, lines.agentEnded(agentId, "demo-agent-1", "completed", "Found 3 notes with rebase and merge examples.", FakeLines.AgentTotals(8_600, 5, 46_000))),
                ),
            )
        }
    },

    /** A turn that fails. */
    Failed("~/code/api-server", "Deploy the staging build", ClaudeProfile("work", "/data/data/app.pocket/files/home/.pocketrocket/profiles/work")) {
        override fun turns(lines: FakeLines): List<List<FakeStep>> {
            val (id, line) = lines.toolUse("Bash", JSONObject().put("command", "npm run deploy:staging"))
            return listOf(
                listOf(
                    Emit(300, lines.init()),
                    Emit(700, lines.text("Starting the staging deploy.")),
                    Emit(500, line),
                    Emit(900, lines.toolResult(id, "Error: connect ETIMEDOUT 10.0.4.12:443\n    at TCPConnectWrap.afterConnect", isError = true)),
                    Emit(400, lines.result("API Error: 529 Overloaded. Try again in a moment.", isError = true)),
                ),
            )
        }
    };

    protected abstract fun turns(lines: FakeLines): List<List<FakeStep>>

    fun script(conversationId: String): FakeScript {
        val lines = FakeLines(conversationId, folder)
        return FakeScript(turns(lines)) { text ->
            listOf(
                Emit(900, lines.text("This is a demo session, so I can't really do that. In a real session Claude Code would answer:\n\n> $text")),
                Emit(300, lines.result("demo")),
            )
        }
    }
}

/**
 * Two subagents at once, in the lines Claude Code prints for them, so an agent's full screen can be tried without
 * Claude: each has a prompt, tool calls with results, its own messages and a final answer. The Explore one is back in
 * about 15 s; the other runs on for about 35 s.
 */
private fun twoAgents(lines: FakeLines): List<FakeStep> {
    val root = "/home/code/dockito-web"
    val (map, mapLine) = lines.toolUse(
        "Agent",
        JSONObject().put("description", "Map the theme code").put("subagent_type", "Explore").put(
            "prompt",
            "Find how theming works in this repo. I need:\n" +
                "1. Where the theme tokens are defined and how components read them.\n" +
                "2. Whether there is already a ThemeProvider or a dark palette.\n" +
                "3. Where the settings page lives and how its sections are laid out.\n" +
                "4. Any tests that cover the settings page.\n\n" +
                "Report file paths with one line each on what they hold. Don't change anything. Keep it under 200 words.",
        ),
    )
    val (audit, auditLine) = lines.toolUse(
        "Agent",
        JSONObject().put("description", "Check dark colour contrast").put("subagent_type", "general-purpose")
            .put("prompt", "Take the colour tokens in src/theme/tokens.ts, propose a dark set, and check every text/background pair meets WCAG AA. Report the pairs that fail."),
    )
    val mapTask = "a1demo4map"
    val auditTask = "a2demo4aud"
    fun tool(parent: String, name: String, input: JSONObject, result: String, after: Long, isError: Boolean = false): List<FakeStep> {
        val (id, line) = lines.toolUse(name, input, parent)
        return listOf(Emit(after, line), Emit(900, lines.toolResult(id, result, isError, parent)))
    }
    return buildList {
        add(Emit(1000, mapLine))
        add(Emit(100, lines.agentStarted(map, mapTask, "Map the theme code")))
        add(Emit(400, auditLine))
        add(Emit(100, lines.agentStarted(audit, auditTask, "Check dark colour contrast")))
        add(Emit(800, lines.text("I'll start from the theme provider and work out from there.", map)))
        addAll(tool(map, "Grep", JSONObject().put("pattern", "ThemeProvider").put("path", root), "src/theme/ThemeProvider.tsx\nsrc/App.tsx\nsrc/settings/SettingsPage.tsx", 600))
        add(Emit(300, lines.agentProgress(map, mapTask, "Reading ThemeProvider.tsx", FakeLines.AgentTotals(4_200, 1))))
        addAll(tool(audit, "Read", JSONObject().put("file_path", "$root/src/theme/tokens.ts"), "export const tokens = {\n  bg: '#ffffff',\n  text: '#1a1d23',\n  muted: '#6b7280',\n  accent: '#3b82f6',\n};", 700))
        addAll(tool(map, "Read", JSONObject().put("file_path", "$root/src/theme/ThemeProvider.tsx"), "export function ThemeProvider({ children }) {\n  return <ThemeContext.Provider value={tokens}>{children}</ThemeContext.Provider>;\n}", 1200))
        add(Emit(600, lines.text("Proposed dark set: bg #0f1115, text #e6e8eb, muted #8b93a1, accent #60a5fa. Checking each pair now.", audit)))
        addAll(tool(map, "Glob", JSONObject().put("pattern", "src/settings/**/*.tsx"), "src/settings/SettingsPage.tsx\nsrc/settings/SettingsSection.tsx\nsrc/settings/SettingsPage.test.tsx", 1400))
        add(Emit(300, lines.agentProgress(map, mapTask, "Reading SettingsPage.tsx", FakeLines.AgentTotals(9_800, 3))))
        addAll(tool(audit, "Bash", JSONObject().put("command", "npx wcag-contrast '#e6e8eb' '#0f1115'").put("description", "Check text on background"), "Contrast ratio 15.8:1 — AA pass, AAA pass", 900))
        addAll(tool(map, "Read", JSONObject().put("file_path", "$root/src/settings/SettingsPage.tsx"), "export default function SettingsPage() {\n  return (<>\n    <SettingsSection title=\"Account\" />\n    <SettingsSection title=\"Notifications\" />\n  </>);\n}", 1300))
        add(Emit(700, lines.text("That's everything asked for.", map)))
        add(Emit(400, lines.agentEnded(map, mapTask, "completed", "Map the theme code", FakeLines.AgentTotals(12_400, 4, 14_100))))
        add(Emit(200, lines.toolResult(
            map,
            "**Theme**\n- `src/theme/tokens.ts`: one light palette, plain object.\n- `src/theme/ThemeProvider.tsx`: passes `tokens` through context; no dark set yet.\n\n" +
                "**Settings**\n- `src/settings/SettingsPage.tsx`: Account and Notifications sections.\n- `src/settings/SettingsSection.tsx`: the section card.\n- `src/settings/SettingsPage.test.tsx`: one render test.",
            totals = FakeLines.AgentTotals(12_400, 4, 14_100),
        )))
        addAll(tool(audit, "Bash", JSONObject().put("command", "npx wcag-contrast '#8b93a1' '#0f1115'").put("description", "Check muted text on background"), "Contrast ratio 6.1:1 — AA pass", 2500))
        addAll(tool(audit, "Bash", JSONObject().put("command", "npx wcag-contrast '#60a5fa' '#0f1115'").put("description", "Check accent on background"), "Contrast ratio 7.4:1 — AA pass", 3500))
        add(Emit(300, lines.agentProgress(audit, auditTask, "Checking the accent on cards", FakeLines.AgentTotals(8_900, 4))))
        addAll(tool(audit, "Bash", JSONObject().put("command", "npx wcag-contrast '#ffffff' '#60a5fa'").put("description", "Check button text on accent"), "Contrast ratio 2.5:1 — AA fail", 4000))
        add(Emit(800, lines.text("One pair fails: white button text on the lighter accent. A darker accent for buttons fixes it.", audit)))
        addAll(tool(audit, "Bash", JSONObject().put("command", "npx wcag-contrast '#ffffff' '#2563eb'").put("description", "Check button text on darker accent"), "Contrast ratio 5.2:1 — AA pass", 3000))
        add(Emit(600, lines.agentEnded(audit, auditTask, "completed", "Check dark colour contrast", FakeLines.AgentTotals(15_300, 6, 34_800))))
        add(Emit(200, lines.toolResult(
            audit,
            "Dark set: bg `#0f1115`, text `#e6e8eb`, muted `#8b93a1`, accent `#60a5fa`.\n\nEvery pair passes AA except **white on `#60a5fa`** (2.5:1). Use `#2563eb` for button backgrounds (5.2:1).",
            totals = FakeLines.AgentTotals(15_300, 6, 34_800),
        )))
    }
}
