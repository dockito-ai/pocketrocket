package com.pocketrocket.sessions.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One step of a scripted reply. Lines are real stream-json, the same shapes the CLI prints. */
sealed interface FakeStep {
    data class Emit(val delayMs: Long, val line: String) : FakeStep

    /** Emits a `can_use_tool` control request and waits for the host's answer before going on. */
    data class AskPermission(
        val delayMs: Long,
        val requestId: String,
        val line: String,
        val onAllow: List<FakeStep>,
        val onDeny: List<FakeStep>,
    ) : FakeStep
}

/** The replies of a fake conversation: one list of steps per user message, then [fallback] for the rest. */
class FakeScript(val turns: List<List<FakeStep>>, val fallback: (userText: String) -> List<FakeStep>)

/** Replays a [FakeScript] as if it were Claude Code, reacting to user messages and permission answers. */
class FakeClaudeTransport(private val script: FakeScript) : ClaudeTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val userTurns = Channel<String>(Channel.UNLIMITED)
    private val answers = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private var currentTurn: Job? = null
    private var turnIndex = 0
    private var listener: ClaudeTransport.Listener? = null
    /** Subagent task ids to their Agent call ids, from the lines played; and the calls whose agent was stopped. */
    private val agentTasks = mutableMapOf<String, String>()
    private val stoppedAgents = mutableSetOf<String>()

    override fun start(listener: ClaudeTransport.Listener) {
        this.listener = listener
        scope.launch {
            for (text in userTurns) {
                val steps = script.turns.getOrNull(turnIndex++) ?: script.fallback(text)
                currentTurn = launch { play(steps, listener) }
                currentTurn?.join()
            }
        }
    }

    private suspend fun play(steps: List<FakeStep>, listener: ClaudeTransport.Listener) {
        for (step in steps) {
            when (step) {
                is FakeStep.Emit -> {
                    delay(step.delayMs)
                    if (played(step.line)) listener.onLine(step.line)
                }
                is FakeStep.AskPermission -> {
                    delay(step.delayMs)
                    val answer = CompletableDeferred<Boolean>()
                    synchronized(answers) { answers[step.requestId] = answer }
                    listener.onLine(step.line)
                    play(if (answer.await()) step.onAllow else step.onDeny, listener)
                }
            }
        }
    }

    override fun send(line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("type")) {
            "user" -> userTurns.trySend(json.optJSONObject("message")?.optString("content").orEmpty())
            "control_response" -> {
                val response = json.optJSONObject("response") ?: return
                val answer = synchronized(answers) { answers.remove(response.optString("request_id")) }
                answer?.complete(response.optJSONObject("response")?.optString("behavior") == "allow")
            }
            "control_request" -> controlRequest(json.optString("request_id"), json.optJSONObject("request") ?: return)
        }
    }

    /** Answers mode and model switches the way the CLI does (it echoes what is now in effect). */
    private fun controlRequest(id: String, request: JSONObject) {
        val reply = when (request.optString("subtype")) {
            "interrupt" -> return interrupt()
            "stop_task" -> return stopTask(id, request.optString("task_id"))
            "set_permission_mode" -> JSONObject().put("mode", request.optString("mode"))
            "set_model" -> JSONObject().put("model", request.optString("model"))
            "mcp_status" -> FakeLines.mcpStatus()
            "mcp_reconnect", "mcp_toggle" -> JSONObject()
            "mcp_authenticate" -> return refuse(id, "A demo session can't sign in.")
            else -> return
        }
        val response = JSONObject().put("subtype", "success").put("request_id", id).put("response", reply)
        listener?.onLine(JSONObject().put("type", "control_response").put("response", response).toString())
    }

    private fun refuse(id: String, error: String) {
        val response = JSONObject().put("subtype", "error").put("request_id", id).put("error", error)
        listener?.onLine(JSONObject().put("type", "control_response").put("response", response).toString())
    }

    /**
     * Whether [line] goes out: a stopped agent's own lines don't. Notes each subagent's task id on the way, so
     * `stop_task` can find it.
     */
    private fun played(line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return true
        val system = json.optString("type") == "system"
        val toolUseId = json.optString("tool_use_id")
        synchronized(agentTasks) {
            if (system && json.optString("task_type") == "local_agent") agentTasks[json.optString("task_id")] = toolUseId
            return json.optString("parent_tool_use_id") !in stoppedAgents && !(system && toolUseId in stoppedAgents)
        }
    }

    /** Like the CLI (checked on 2.1.309): the agent ends as stopped and the turn goes on without it. */
    private fun stopTask(id: String, taskId: String) {
        val toolUseId = synchronized(agentTasks) { agentTasks[taskId]?.also { stoppedAgents += it } } ?: return refuse(id, "No task $taskId")
        listener?.onLine(
            JSONObject().put("type", "system").put("subtype", "task_notification").put("task_id", taskId).put("tool_use_id", toolUseId)
                .put("status", "stopped").toString(),
        )
        val response = JSONObject().put("subtype", "success").put("request_id", id).put("response", JSONObject())
        listener?.onLine(JSONObject().put("type", "control_response").put("response", response).toString())
    }

    /** Like the CLI: the turn stops and ends with a result. */
    private fun interrupt() {
        val turn = currentTurn ?: return
        if (!turn.isActive) return
        turn.cancel()
        listener?.onLine(JSONObject().put("type", "result").put("subtype", "error_during_execution").put("is_error", false).put("result", "Interrupted").toString())
    }

    override fun close() {
        scope.cancel()
    }
}
