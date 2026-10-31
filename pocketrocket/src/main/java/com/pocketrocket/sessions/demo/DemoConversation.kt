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
            val steps = mutableListOf<FakeStep>(Emit(500, lines.init()), Emit(1200, lines.text("Let me find where the settings page and the theme live.")))
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
                    Emit(400, lines.taskStarted("demo-agent-1", agentId, task)),
                    Emit(300, lines.toolResult(agentId, "Async agent launched successfully.\nagentId: demo-agent-1")),
                    Emit(900, lines.text("**Merge** joins two branches with a new merge commit. History stays exactly as it happened.\n\n**Rebase** replays your commits on top of the other branch, so history is a straight line, but the commits get new ids.\n\n- Shared branch: merge\n- Your own branch before a PR: rebase\n\nAn agent is still looking for examples in your notes.")),
                    Emit(200, lines.result("done")),
                    Emit(45_000, lines.taskNotification("demo-agent-1", agentId, "completed", "Found 3 notes with rebase and merge examples.")),
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
