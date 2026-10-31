package com.pocketrocket.assistant

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real server, the real flags and the real hook against a real `claude` on the computer, spending a little of its
 * login's usage, so it only runs when asked: `ROCKET_REAL_CLAUDE=<scratch folder> ./gradlew :pocketrocket:testDebugUnitTest --tests '*RealClaudeCheck'`.
 */
class RealClaudeCheck {
    private val scratch = System.getenv("ROCKET_REAL_CLAUDE")

    private val calls = AtomicInteger()
    private val catalog = object : ToolCatalog {
        override fun list() = JSONArray()
            .put(JSONObject().put("name", "list_sessions").put("description", "List sessions").put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(JSONObject().put("name", "slow_tool").put("description", "Takes about 70 seconds, as an approval can").put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())))
        override suspend fun call(name: String, arguments: JSONObject): ToolResult {
            calls.incrementAndGet()
            if (name == "slow_tool") delay(70_000)
            return ToolResult(AssistantTools.data("sessions", if (name == "slow_tool") "slow finished" else "nimbus (working)"))
        }
    }

    private class Run(args: List<String>, dir: File) {
        val out = mutableListOf<JSONObject>()
        private val process = ProcessBuilder(listOf("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--permission-prompt-tool", "stdio", "--session-id", UUID.randomUUID().toString()) + args)
            .directory(dir).redirectError(File(dir, "stderr.txt")).apply { environment().putAll(BrainFiles.ENVIRONMENT) }.start()
        private val stdin = process.outputStream.bufferedWriter()
        private var results = 0
        private val lock = Object()
        var onPermission: (JSONObject) -> Unit = {}

        init {
            thread(isDaemon = true) {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    synchronized(lock) {
                        out += o
                        if (o.optString("type") == "result") { results++; lock.notifyAll() }
                    }
                    if (o.optString("type") == "control_request") onPermission(o)
                }
                synchronized(lock) { results = 1000; lock.notifyAll() }
            }
            send(JSONObject().put("type", "control_request").put("request_id", "init").put("request", JSONObject().put("subtype", "initialize")))
        }

        fun send(o: JSONObject) { stdin.write(o.toString()); stdin.write("\n"); stdin.flush() }
        fun answer(id: String, allow: Boolean) = send(JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success").put("request_id", id).put("response",
            if (allow) JSONObject().put("behavior", "allow").put("updatedInput", JSONObject()) else JSONObject().put("behavior", "deny").put("message", "He said no"))))
        fun say(text: String) {
            val before = synchronized(lock) { results }
            send(JSONObject().put("type", "user").put("message", JSONObject().put("role", "user").put("content", text)))
            synchronized(lock) { val until = System.currentTimeMillis() + 240_000; while (results == before && System.currentTimeMillis() < until) lock.wait(1000) }
        }
        fun close() { runCatching { stdin.close() }; process.destroy() }
        fun inits() = synchronized(lock) { out.filter { it.optString("subtype") == "init" } }
        fun lastResult() = synchronized(lock) { out.last { it.optString("type") == "result" } }
        fun all() = synchronized(lock) { out.toList() }
    }

    private fun tools(init: JSONObject) = init.getJSONArray("tools").let { a -> (0 until a.length()).map { a.getString(it) } }

    /** Decision 61: the brain's real flags keep his setup (Bash, his MCP servers) and add Rocket's server and its stricter hook. */
    @Test fun theBrainHasHisSetupPlusRocketsServerAndHook() {
        assumeTrue(scratch != null)
        val dir = File(scratch, "brain").apply { mkdirs() }
        File(dir, "CLAUDE.md").writeText(BrainFiles.claudeMd(MemoryBook(listOf(Note("a", "The secret word is pineapple")))))
        val server = McpServer(
            catalog, { JSONObject() }, CoroutineScope(SupervisorJob() + Dispatchers.IO),
            rocketHook = { AutoModeNet.decideForRocket(it, locked = false, scratch = listOf(File(dir, "scratch").path)) },
        ).also { it.start() }
        val config = File(dir, "mcp.json").apply { writeText(BrainFiles.mcpConfig(server.port, server.mcpToken)) }
        val hook = File(dir, "hook.json").apply { writeText(AutoModeNet.settings(server.port, server.hookToken, McpServer.ROCKET_HOOK_PATH)) }
        val asked = mutableListOf<String>()
        val run = Run(BrainFiles.arguments(config.path, hook.path) + listOf("--model", "haiku", "--permission-mode", "auto"), dir)
        run.onPermission = { o ->
            val r = o.optJSONObject("request")
            if (r?.optString("subtype") == "can_use_tool") {
                asked += r.optJSONObject("input")?.optString("command").orEmpty()
                run.answer(o.getString("request_id"), allow = false)
            }
        }
        try {
            run.say("Call list_sessions, then run `echo bash-works` with Bash, then try `git push --force origin nothing-here` with Bash once (carry on if it's refused). Then tell me the sessions, the echo output and the secret word, in one line.")
            val said = run.lastResult().optString("result")
            val init = run.inits().last()
            println("SAID: $said\nTOOLS: ${tools(init)}\nSERVERS: ${init.getJSONArray("mcp_servers")}\nASKED: $asked")
            assertTrue(said, said.contains("nimbus") && said.contains("bash-works") && said.contains("pineapple", ignoreCase = true))
            assertTrue("Bash" in tools(init))
            assertTrue("mcp__rocket__list_sessions" in tools(init))
            assertFalse("AskUserQuestion" in tools(init))
            assertTrue(asked.toString(), asked.any { it.contains("--force") })
            assertFalse(asked.toString(), asked.any { it.startsWith("echo") })
        } finally {
            run.close()
            server.stop()
        }
    }

    @Test fun theHookStopsASensitiveCommandInAutoAndLetsOthersThrough() {
        assumeTrue(scratch != null)
        val dir = File(scratch, "hook").apply { mkdirs() }
        File(dir, "keep.txt").writeText("still here")
        val server = McpServer(catalog, { AutoModeNet.decide(it, enabled = true) }, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { it.start() }
        val settings = File(dir, "hook.json").apply { writeText(AutoModeNet.settings(server.port, server.hookToken)) }
        val asked = mutableListOf<String>()
        val run = Run(listOf("--model", "haiku", "--tools", "Bash", "--strict-mcp-config", "--setting-sources", "project", "--settings", settings.path, "--permission-mode", "auto"), dir)
        run.onPermission = { o ->
            val r = o.optJSONObject("request")
            if (r?.optString("subtype") == "can_use_tool") {
                asked += r.optJSONObject("input")?.optString("command").orEmpty()
                run.answer(o.getString("request_id"), allow = false)
            }
        }
        try {
            run.say("Run these with Bash one at a time, carrying on if one is refused: `echo fine`, `rm keep.txt`, `git push --force origin nothing-here`. Then say DONE.")
            println("ASKED: $asked")
            assertTrue(asked.toString(), asked.any { it.startsWith("rm ") })
            assertTrue(asked.toString(), asked.any { it.contains("--force") })
            assertFalse(asked.toString(), asked.any { it.startsWith("echo") })
            assertTrue("rm was refused", File(dir, "keep.txt").exists())
        } finally {
            run.close()
            server.stop()
        }
    }
}
